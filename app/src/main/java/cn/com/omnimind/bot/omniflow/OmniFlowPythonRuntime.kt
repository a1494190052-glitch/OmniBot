package cn.com.omnimind.bot.omniflow

import android.content.Context
import android.os.SystemClock
import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object OmniFlowPythonRuntime {
    private const val TAG = "[OmniFlowPythonRuntime]"
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prepareMutex = Mutex()

    @Volatile
    private var client: OmniFlowPythonClient? = null

    @Volatile
    private var ready: Boolean = false

    @Volatile
    private var activeManifest: OmniFlowRuntimeManifest? = null

    fun start(context: Context) {
        if (ready) return
        val startedAt = SystemClock.elapsedRealtime()
        runtimeScope.launch {
            runCatching {
                ensureReady(context.applicationContext)
            }
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
        }
    }

    fun isReady(): Boolean = ready

    fun launchBackground(block: suspend () -> Unit) {
        runtimeScope.launch { block() }
    }

    suspend fun call(
        context: Context,
        operation: String,
        payload: Map<String, Any?> = emptyMap(),
        hostCall: OmniFlowPythonHostCall? = null,
    ): Map<String, Any?> {
        ensureReady(context.applicationContext)
        return requireNotNull(client) { "omniflow_python_client_unavailable" }
            .call(operation, payload, hostCall)
    }

    suspend fun callIsolated(
        context: Context,
        operation: String,
        payload: Map<String, Any?> = emptyMap(),
        hostCall: OmniFlowPythonHostCall? = null,
    ): Map<String, Any?> {
        ensureReady(context.applicationContext)
        val embeddedRuntime = OmniFlowEmbeddedRuntime.prepare(context.applicationContext)
        val candidate = OmniFlowPythonClient(
            context = context.applicationContext,
            shellSitePackagesPath = embeddedRuntime.shellSitePackagesPath,
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
            val embeddedRuntime = OmniFlowEmbeddedRuntime.prepare(context)
            val candidate = OmniFlowPythonClient(
                context = context,
                shellSitePackagesPath = embeddedRuntime.shellSitePackagesPath,
            )
            try {
                val health = candidate.warmup()
                require(health["protocol_version"] == embeddedRuntime.manifest.protocol) {
                    "unsupported_omniflow_protocol:${health["protocol_version"]}"
                }
                require(
                    health["contract_sha256"] == embeddedRuntime.manifest.bridgeContractSha256
                ) {
                    "omniflow_bridge_contract_mismatch:${health["contract_sha256"]}"
                }
                require(health["runtime_version"] == embeddedRuntime.manifest.version) {
                    "omniflow_runtime_version_mismatch:${health["runtime_version"]}"
                }
                require(health["omniflow_commit"] == embeddedRuntime.manifest.omniFlowCommit) {
                    "omniflow_commit_mismatch:${health["omniflow_commit"]}"
                }
                require(
                    health["omniflow_source_sha256"] == embeddedRuntime.manifest.omniFlowSourceSha256
                ) {
                    "omniflow_source_mismatch:${health["omniflow_source_sha256"]}"
                }
                require(health["omnitransfer_commit"] == embeddedRuntime.manifest.omniTransferCommit) {
                    "omnitransfer_commit_mismatch:${health["omnitransfer_commit"]}"
                }
                require(
                    health["omnitransfer_source_sha256"] ==
                        embeddedRuntime.manifest.omniTransferSourceSha256
                ) {
                    "omnitransfer_source_mismatch:${health["omnitransfer_source_sha256"]}"
                }
                require(health["omnitransfer_ready"] == true) {
                    "omnitransfer_runtime_unavailable:${health["omnitransfer_backend"]}"
                }
                val capabilities = (health["capabilities"] as? List<*>)
                    .orEmpty()
                    .mapTo(linkedSetOf()) { it.toString() }
                require(capabilities == embeddedRuntime.manifest.capabilities) {
                    "omniflow_capabilities_mismatch:" +
                        "expected=${embeddedRuntime.manifest.capabilities.sorted().joinToString(",")}:" +
                        "actual=${capabilities.sorted().joinToString(",")}"
                }
                client = candidate
                activeManifest = embeddedRuntime.manifest
                ready = true
                embeddedRuntime.manifest
            } catch (error: Throwable) {
                runCatching { candidate.close() }
                client = null
                activeManifest = null
                ready = false
                throw error
            }
        }
    }
}
