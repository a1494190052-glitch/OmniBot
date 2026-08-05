package cn.com.omnimind.bot.omniflow

import android.content.Context
import android.os.SystemClock
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object OmniFlowPythonRuntime {
    private const val TAG = "[OmniFlowPythonRuntime]"
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prepareMutex = Mutex()
    private val warmupLock = Any()

    @Volatile
    private var client: OmniFlowPythonClient? = null

    @Volatile
    private var ready: Boolean = false

    @Volatile
    private var activeManifest: OmniFlowRuntimeManifest? = null

    @Volatile
    private var platform: OmniFlowPlatform? = null

    @Volatile
    private var runtimeProvider: OmniFlowRuntimeProvider = OmniFlowRuntimeProvider()

    @Volatile
    private var warmupDeferred: Deferred<OmniFlowRuntimeManifest>? = null

    fun configure(
        value: OmniFlowPlatform,
        provider: OmniFlowRuntimeProvider,
    ) {
        platform = value
        runtimeProvider = provider
    }

    suspend fun shutdown() = prepareMutex.withLock {
        synchronized(warmupLock) {
            warmupDeferred?.cancel()
            warmupDeferred = null
        }
        val activeClient = client
        client = null
        activeManifest = null
        ready = false
        activeClient?.close()
    }

    fun start(context: Context) {
        if (ready) return
        warmupJob(context.applicationContext)
    }

    suspend fun call(
        context: Context,
        operation: String,
        payload: Map<String, Any?> = emptyMap(),
        hostCall: OmniFlowPythonHostCall? = null,
    ): Map<String, Any?> {
        awaitReady(context.applicationContext)
        return requireNotNull(client) { "omniflow_python_client_unavailable" }
            .call(operation, payload, hostCall)
    }

    internal suspend fun completeJson(request: ChatCompletionRequest): String =
        requireNotNull(platform) { "omniflow_platform_not_configured" }.completeJson(request)

    fun schedule(
        context: Context,
        operation: String,
        payload: Map<String, Any?>,
        hostCall: OmniFlowPythonHostCall,
    ): Map<String, Any?> {
        require(operation == "tools/call") { "background_operation_not_allowed:$operation" }
        runtimeScope.launch {
            runCatching {
                callIsolated(context, operation, payload, hostCall)
            }.onFailure { error ->
                if (error !is CancellationException) {
                    OmniLog.w(
                        TAG,
                        "background_operation_failed operation=$operation error=${error.message}",
                    )
                }
            }
        }
        return mapOf("accepted" to true)
    }

    private suspend fun callIsolated(
        context: Context,
        operation: String,
        payload: Map<String, Any?> = emptyMap(),
        hostCall: OmniFlowPythonHostCall? = null,
    ): Map<String, Any?> {
        awaitReady(context.applicationContext)
        val host = requireNotNull(platform) { "omniflow_platform_not_configured" }
        val preparedRuntime = runtimeProvider.prepare(context.applicationContext, host)
        val candidate = OmniFlowPythonClient(
            processStarter = { command, environment ->
                host.startProcess(context.applicationContext, command, environment)
            },
            bridgeCommand = OmniFlowPythonClient.bridgeCommand(
                preparedRuntime.shellPythonSourcePath,
                preparedRuntime.shellSitePackagesPath,
                preparedRuntime.shellOmniTransferRoot,
                preparedRuntime.shellOmniTransferCheckpointPath,
            ),
        )
        return try {
            candidate.call(operation, payload, hostCall)
        } finally {
            runCatching { candidate.close() }
        }
    }

    private suspend fun ensureReady(context: Context): OmniFlowRuntimeManifest {
        if (ready && client != null) {
            return requireNotNull(activeManifest) { "omniflow_runtime_manifest_unavailable" }
        }
        return prepareMutex.withLock {
            if (ready && client != null) {
                return requireNotNull(activeManifest) { "omniflow_runtime_manifest_unavailable" }
            }
            val host = requireNotNull(platform) { "omniflow_platform_not_configured" }
            val preparedRuntime = runtimeProvider.prepare(context, host)
            val candidate = OmniFlowPythonClient(
                processStarter = { command, environment ->
                    host.startProcess(context, command, environment)
                },
                bridgeCommand = OmniFlowPythonClient.bridgeCommand(
                    preparedRuntime.shellPythonSourcePath,
                    preparedRuntime.shellSitePackagesPath,
                    preparedRuntime.shellOmniTransferRoot,
                    preparedRuntime.shellOmniTransferCheckpointPath,
                ),
            )
            try {
                val initialization = candidate.initialize()
                require(initialization["protocolVersion"] == preparedRuntime.manifest.protocol) {
                    "unsupported_omniflow_protocol:${initialization["protocolVersion"]}"
                }
                val metadata = initialization["_meta"] as? Map<*, *>
                val runtime = metadata?.get("omniflow/runtime") as? Map<*, *> ?: emptyMap<Any, Any>()
                require(
                    runtime["contract_sha256"] == preparedRuntime.manifest.bridgeContractSha256,
                ) {
                    "omniflow_bridge_contract_mismatch:${runtime["contract_sha256"]}"
                }
                require(runtime["runtime_version"] == preparedRuntime.manifest.version) {
                    "omniflow_runtime_version_mismatch:${runtime["runtime_version"]}"
                }
                require(runtime["omniflow_commit"] == preparedRuntime.manifest.omniFlowCommit) {
                    "omniflow_commit_mismatch:${runtime["omniflow_commit"]}"
                }
                require(
                    runtime["omniflow_source_sha256"] ==
                        preparedRuntime.manifest.omniFlowSourceSha256,
                ) {
                    "omniflow_source_mismatch:${runtime["omniflow_source_sha256"]}"
                }
                require(
                    runtime["omnitransfer_commit"] == preparedRuntime.manifest.omniTransferCommit,
                ) {
                    "omnitransfer_commit_mismatch:${runtime["omnitransfer_commit"]}"
                }
                require(
                    runtime["omnitransfer_source_sha256"] ==
                        preparedRuntime.manifest.omniTransferSourceSha256,
                ) {
                    "omnitransfer_source_mismatch:${runtime["omnitransfer_source_sha256"]}"
                }
                require(runtime["omnitransfer_ready"] == true) {
                    "omnitransfer_runtime_unavailable:${runtime["omnitransfer_backend"]}"
                }
                val capabilities = (runtime["capabilities"] as? List<*>)
                    .orEmpty()
                    .mapTo(linkedSetOf()) { it.toString() }
                require(capabilities == preparedRuntime.manifest.capabilities) {
                    "omniflow_capabilities_mismatch:" +
                        "expected=${preparedRuntime.manifest.capabilities.sorted().joinToString(",")}:" +
                        "actual=${capabilities.sorted().joinToString(",")}"
                }
                client = candidate
                activeManifest = preparedRuntime.manifest
                ready = true
                preparedRuntime.manifest
            } catch (error: Throwable) {
                runCatching { candidate.close() }
                client = null
                activeManifest = null
                ready = false
                throw error
            }
        }
    }

    private fun warmupJob(context: Context): Deferred<OmniFlowRuntimeManifest> =
        synchronized(warmupLock) {
            warmupDeferred?.let { existing ->
                if (!existing.isCancelled) return@synchronized existing
            }
            val startedAt = SystemClock.elapsedRealtime()
            runtimeScope.async {
                OmniLog.i(TAG, "warmup_start")
                runCatching { ensureReady(context) }
                    .onSuccess { manifest ->
                        OmniLog.i(
                            TAG,
                            "warmup_ready durationMs=${SystemClock.elapsedRealtime() - startedAt} " +
                                "protocol=${manifest.protocol}",
                        )
                    }
                    .onFailure { error ->
                        ready = false
                        if (error !is CancellationException) {
                            OmniLog.w(
                                TAG,
                                "warmup_failed durationMs=${SystemClock.elapsedRealtime() - startedAt} " +
                                    "error=${error.message}",
                            )
                        }
                    }
                    .getOrThrow()
            }.also { created ->
                warmupDeferred = created
                created.invokeOnCompletion { error ->
                    if (error != null) {
                        synchronized(warmupLock) {
                            if (warmupDeferred === created) warmupDeferred = null
                        }
                    }
                }
            }
        }

    private suspend fun awaitReady(context: Context) {
        if (ready && client != null) return
        warmupJob(context).await()
    }
}
