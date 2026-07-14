package cn.com.omnimind.bot.debug

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import android.util.Base64
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.omniflow.OmniFlowPythonRuntime
import cn.com.omnimind.bot.omniflow.omniFlowRunLogHostCall
import cn.com.omnimind.bot.terminal.EmbeddedTerminalRuntime
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class DebugOmniFlowPythonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val operation = intent?.getStringExtra("operation")?.takeIf(String::isNotBlank)
        if (operation != null) {
            val appContext = context.applicationContext
            val pending = goAsync()
            operationScope.launch {
                try {
                    val startedAtMs = System.currentTimeMillis()
                    val result = runCatching {
                        DebugOmniFlowPythonService.executeOperation(
                            context = appContext,
                            intent = intent,
                            operation = operation,
                            startedAtMs = startedAtMs,
                        )
                    }.getOrElse { error ->
                        DebugOmniFlowPythonService.failureResult(
                            error = error,
                            startedAtMs = startedAtMs,
                            source = "debug_omniflow_python_receiver",
                        )
                    }
                    DebugOmniFlowPythonService.writeResult(appContext, result)
                } finally {
                    pending.finish()
                }
            }
            return
        }
        val serviceIntent = Intent(context, DebugOmniFlowPythonService::class.java).apply {
            intent?.extras?.let(::putExtras)
        }
        runCatching { context.startService(serviceIntent) }
            .onFailure { error ->
                DebugOmniFlowPythonService.writeFailure(context, error)
            }
    }

    companion object {
        private val operationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

class DebugOmniFlowPythonService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val appContext = applicationContext
        scope.launch {
            try {
                val startedAtMs = System.currentTimeMillis()
                val result = runCatching {
                    val operation = intent?.getStringExtra("operation")?.takeIf(String::isNotBlank)
                    if (operation != null) {
                        return@runCatching executeOperation(
                            context = appContext,
                            intent = intent,
                            operation = operation,
                            startedAtMs = startedAtMs,
                        )
                    }
                    val command = intent.decodeBase64Extra("commandBase64")
                        ?: intent?.getStringExtra("command")?.takeIf(String::isNotBlank)
                        ?: error("command is required")
                    val workingDirectory = intent?.getStringExtra("workingDirectory")
                        ?.takeIf(String::isNotBlank)
                        ?: "/workspace"
                    val timeoutSeconds = intent?.getIntExtra("timeoutSeconds", DEFAULT_TIMEOUT_SECONDS)
                        ?.coerceIn(1, MAX_TIMEOUT_SECONDS)
                        ?: DEFAULT_TIMEOUT_SECONDS
                    val commandResult = EmbeddedTerminalRuntime.executeCommand(
                        context = appContext,
                        command = command,
                        workingDirectory = workingDirectory,
                        timeoutSeconds = timeoutSeconds,
                    )
                    linkedMapOf<String, Any?>(
                        "success" to commandResult.success,
                        "timed_out" to commandResult.timedOut,
                        "exit_code" to commandResult.exitCode,
                        "output" to commandResult.output,
                        "error_message" to commandResult.errorMessage,
                        "session_id" to commandResult.sessionId,
                        "working_directory" to workingDirectory,
                        "duration_ms" to (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L),
                        "source" to "debug_omniflow_python_service",
                    )
                }.getOrElse { error ->
                    failureResult(error, startedAtMs, "debug_omniflow_python_service")
                }
                writeResult(appContext, result)
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val RESULT_FILE = "debug-omniflow-python-result.json"
        private const val TAG = "DebugOmniFlowPythonService"
        private const val DEFAULT_TIMEOUT_SECONDS = 120
        private const val MAX_TIMEOUT_SECONDS = 600
        private val gson = GsonBuilder().disableHtmlEscaping().create()
        private val MAP_TYPE = object : TypeToken<Map<String, Any?>>() {}.type

        suspend fun executeOperation(
            context: Context,
            intent: Intent,
            operation: String,
            startedAtMs: Long,
        ): Map<String, Any?> {
            val payloadText = intent.decodeBase64Extra("payloadBase64")
                ?: intent.getStringExtra("payload")
            val payload = payloadText?.let(::jsonMap).orEmpty()
            val repeatCount = intent.getIntExtra("repeat", 1).coerceIn(1, 10)
            val callDurationsMs = mutableListOf<Long>()
            val bridgeResults = List(repeatCount) {
                val callStartedAt = SystemClock.elapsedRealtime()
                OmniFlowPythonRuntime.call(
                    context = context,
                    operation = operation,
                    payload = payload,
                    hostCall = omniFlowRunLogHostCall(context),
                ).also {
                    callDurationsMs += SystemClock.elapsedRealtime() - callStartedAt
                }
            }
            return linkedMapOf(
                "success" to true,
                "result" to bridgeResults.last(),
                "results" to bridgeResults,
                "call_duration_ms" to callDurationsMs,
                "duration_ms" to (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L),
                "source" to "debug_omniflow_python_bridge",
            )
        }

        fun failureResult(
            error: Throwable,
            startedAtMs: Long,
            source: String,
        ): Map<String, Any?> = linkedMapOf(
            "success" to false,
            "error_message" to error.message.orEmpty(),
            "error_type" to error.javaClass.name,
            "duration_ms" to (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L),
            "source" to source,
        )

        fun writeResult(context: Context, result: Map<String, Any?>) {
            val json = gson.toJson(result)
            File(context.applicationContext.filesDir, RESULT_FILE).writeText(json)
            OmniLog.i(TAG, json)
        }

        fun writeFailure(context: Context, error: Throwable) {
            val result = linkedMapOf<String, Any?>(
                "success" to false,
                "error_message" to error.message.orEmpty(),
                "error_type" to error.javaClass.name,
                "source" to "debug_omniflow_python_service_start",
            )
            writeResult(context, result)
        }

        private fun Intent?.decodeBase64Extra(name: String): String? {
            val encoded = this?.getStringExtra(name)?.takeIf(String::isNotBlank) ?: return null
            return runCatching {
                String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
                    .takeIf(String::isNotBlank)
            }.getOrNull()
        }

        private fun jsonMap(value: String): Map<String, Any?> =
            gson.fromJson<Map<String, Any?>>(value, MAP_TYPE) ?: emptyMap()
    }
}
