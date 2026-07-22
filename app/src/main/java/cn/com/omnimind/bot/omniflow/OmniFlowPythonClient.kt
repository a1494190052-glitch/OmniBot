package cn.com.omnimind.bot.omniflow

import android.content.Context
import com.ai.assistance.operit.terminal.TerminalManager
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit

internal fun interface OmniFlowPythonHostCall {
    suspend fun invoke(method: String, payload: Map<String, Any?>): Map<String, Any?>
}

internal class OmniFlowPythonClient(
    private val processStarter: suspend (command: String, environment: Map<String, String>) -> Process,
    private val bridgeCommand: String = bridgeCommand(DEFAULT_SHELL_SITE_PACKAGES),
    private val requestIdFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private data class BridgeSession(
        val process: Process,
        val writer: BufferedWriter,
        val stdout: Channel<String>,
        val stdoutJob: Job,
        val stderrJob: Job,
        val stderr: StderrTail,
    )

    private class StderrTail {
        private val value = StringBuilder()

        @Synchronized
        fun append(line: String) {
            if (value.isNotEmpty()) value.append('\n')
            value.append(line)
            if (value.length > STDERR_TAIL_CHARS) {
                value.delete(0, value.length - STDERR_TAIL_CHARS)
            }
        }

        @Synchronized
        fun text(): String = value.toString().trim()
    }

    private val callMutex = Mutex()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: BridgeSession? = null
    private var closed = false

    constructor(context: Context, shellSitePackagesPath: String) : this(
        processStarter = { command, environment ->
            TerminalManager.getInstance(context.applicationContext).startLongLivedAlpineProcess(
                command = command,
                executorKey = "omniflow-${UUID.randomUUID()}",
                redirectErrorStream = false,
                extraEnvironment = environment,
            )
        },
        bridgeCommand = bridgeCommand(shellSitePackagesPath),
    )

    suspend fun warmup(): Map<String, Any?> = call("health")

    suspend fun call(
        operation: String,
        payload: Map<String, Any?> = emptyMap(),
        hostCall: OmniFlowPythonHostCall? = null,
        timeoutMs: Long = defaultTimeoutMs(operation),
    ): Map<String, Any?> = callMutex.withLock {
        check(!closed) { "omniflow_python_client_closed" }
        val activeSession = ensureSession()
        try {
            val requestId = requestIdFactory()
            writeRequest(activeSession.writer, requestId, operation, payload)
            withTimeout(timeoutMs) {
                readResponse(activeSession, requestId, hostCall)
            }
        } catch (error: Throwable) {
            if (
                error is CancellationException ||
                error !is OmniFlowPythonException ||
                error.type == "process_exit"
            ) {
                clearSession(activeSession)
            }
            throw error
        }
    }

    suspend fun close() = callMutex.withLock {
        if (closed) return@withLock
        closed = true
        val activeSession = session ?: return@withLock
        try {
            val requestId = requestIdFactory()
            writeRequest(activeSession.writer, requestId, "shutdown", emptyMap())
            withTimeout(SHUTDOWN_TIMEOUT_MS) {
                readResponse(activeSession, requestId, hostCall = null)
            }
        } catch (_: Throwable) {
        } finally {
            clearSession(activeSession)
        }
    }

    private suspend fun ensureSession(): BridgeSession {
        session?.takeIf { it.process.isAlive }?.let { return it }
        session?.let { clearSession(it) }
        val process = processStarter(
            bridgeCommand,
            mapOf("PYTHONUNBUFFERED" to "1", "OMNIBOT_HEADLESS" to "1"),
        )
        val stdout = Channel<String>(Channel.UNLIMITED)
        val stderr = StderrTail()
        val activeSession = BridgeSession(
            process = process,
            writer = OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8).buffered(),
            stdout = stdout,
            stdoutJob = ioScope.launch {
                try {
                    process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                        for (line in lines) {
                            if (stdout.trySend(line).isFailure) break
                        }
                    }
                } catch (_: IOException) {
                } finally {
                    stdout.close()
                }
            },
            stderrJob = ioScope.launch {
                try {
                    process.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                        lines.forEach(stderr::append)
                    }
                } catch (_: IOException) {
                }
            },
            stderr = stderr,
        )
        session = activeSession
        return activeSession
    }

    private suspend fun readResponse(
        session: BridgeSession,
        requestId: String,
        hostCall: OmniFlowPythonHostCall?,
    ): Map<String, Any?> {
        while (true) {
            val line = session.stdout.receiveCatching().getOrNull()
                ?: throw bridgeExited(session)
            if (line.isBlank()) continue
            val message = jsonMap(line)
            if (message["event"] == "host_call") {
                writeHostResponse(
                    writer = session.writer,
                    requestId = requestId,
                    message = message,
                    hostCall = hostCall,
                )
                continue
            }
            if (message["id"]?.toString() != requestId || !message.containsKey("ok")) {
                continue
            }
            if (message["ok"] != true) {
                val error = mapValue(message["error"])
                throw OmniFlowPythonException(
                    code = error["code"]?.toString().orEmpty().ifBlank { "python_call_failed" },
                    type = error["type"]?.toString().orEmpty(),
                )
            }
            return mapValue(message["result"])
        }
    }

    private fun writeRequest(
        writer: BufferedWriter,
        requestId: String,
        operation: String,
        payload: Map<String, Any?>,
    ) {
        writer.write(
            gson.toJson(
                linkedMapOf(
                    "id" to requestId,
                    "op" to operation,
                    "payload" to payload,
                )
            )
        )
        writer.newLine()
        writer.flush()
    }

    private fun bridgeExited(session: BridgeSession): OmniFlowPythonException {
        val exitCode = runCatching { session.process.exitValue() }.getOrNull()
        return OmniFlowPythonException(
            code = session.stderr.text().ifBlank {
                exitCode?.let { "python_bridge_exited_$it" } ?: "python_bridge_output_closed"
            },
            type = "process_exit",
        )
    }

    private fun clearSession(activeSession: BridgeSession) {
        if (session === activeSession) session = null
        if (activeSession.process.isAlive) {
            runCatching { activeSession.process.destroyForcibly() }
        }
        activeSession.stdout.close()
        activeSession.stdoutJob.cancel()
        activeSession.stderrJob.cancel()
        ioScope.launch {
            runCatching {
                activeSession.process.waitFor(PROCESS_EXIT_GRACE_MS, TimeUnit.MILLISECONDS)
            }
            runCatching { activeSession.writer.close() }
        }
    }

    private suspend fun writeHostResponse(
        writer: BufferedWriter,
        requestId: String,
        message: Map<String, Any?>,
        hostCall: OmniFlowPythonHostCall?,
    ) {
        val callId = message["call_id"]?.toString().orEmpty()
        val method = message["method"]?.toString().orEmpty()
        val response = runCatching {
            requireNotNull(hostCall) { "host_call_not_configured" }
                .invoke(method, mapValue(message["payload"]))
        }.fold(
            onSuccess = { result ->
                linkedMapOf<String, Any?>(
                    "id" to requestId,
                    "call_id" to callId,
                    "ok" to true,
                    "result" to result,
                )
            },
            onFailure = { error ->
                linkedMapOf<String, Any?>(
                    "id" to requestId,
                    "call_id" to callId,
                    "ok" to false,
                    "error" to linkedMapOf(
                        "code" to error.message.orEmpty().ifBlank { error.javaClass.simpleName },
                        "type" to error.javaClass.name,
                    ),
                )
            },
        )
        writer.write(gson.toJson(response))
        writer.newLine()
        writer.flush()
    }

    private fun jsonMap(value: String): Map<String, Any?> =
        @Suppress("UNCHECKED_CAST")
        (gson.fromJson<Map<String, Any?>>(value, MAP_TYPE) ?: emptyMap())

    private fun mapValue(value: Any?): Map<String, Any?> =
        @Suppress("UNCHECKED_CAST")
        ((value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: emptyMap())

    companion object {
        private const val PROCESS_EXIT_GRACE_MS = 1_000L
        private const val SHUTDOWN_TIMEOUT_MS = 1_000L
        private const val STDERR_TAIL_CHARS = 8_192
        private const val DEFAULT_CALL_TIMEOUT_MS = 30_000L
        private const val RUN_TIMEOUT_MS = 10 * 60_000L
        private const val DEFAULT_SHELL_SITE_PACKAGES =
            "/workspace/.omnibot/runtime/omniflow/current/site-packages"
        private val gson = GsonBuilder()
            .disableHtmlEscaping()
            .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
            .create()
        private val MAP_TYPE = object : TypeToken<Map<String, Any?>>() {}.type

        internal fun bridgeCommand(shellSitePackagesPath: String): String {
            require(shellSitePackagesPath.matches(Regex("/[A-Za-z0-9_./-]+"))) {
                "omniflow_runtime_path_invalid"
            }
            return """
            export PYTHONPATH='$shellSitePackagesPath'
            python_bin="${'$'}(command -v python3 || true)"
            if [ -z "${'$'}python_bin" ]; then echo 'omniflow_python_not_installed' >&2; exit 127; fi
            exec "${'$'}python_bin" -u -m oob_omniflow_bridge --store /workspace/.omnibot/omniflow/omniflow.json
            """.trimIndent()
        }

        private fun defaultTimeoutMs(operation: String): Long =
            if (operation == "run") RUN_TIMEOUT_MS else DEFAULT_CALL_TIMEOUT_MS
    }
}

internal class OmniFlowPythonException(
    val code: String,
    val type: String,
) : IllegalStateException(code)
