package cn.com.omnimind.bot.omniflow

import android.content.Context
import android.os.SystemClock
import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal object OmniFlowPythonRuntime {
    private const val TAG = "[OmniFlowPythonRuntime]"
    private const val EXPECTED_PROTOCOL = "omniflow.bridge.v2"
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startLock = Any()

    @Volatile
    private var client: OmniFlowPythonClient? = null

    @Volatile
    private var ready: Boolean = false

    fun start(context: Context) {
        val sharedClient = synchronized(startLock) {
            if (client != null) return
            OmniFlowPythonClient(context.applicationContext).also { client = it }
        }
        val startedAt = SystemClock.elapsedRealtime()
        runtimeScope.launch {
            runCatching {
                sharedClient.warmup().also { health ->
                    require(health["protocol_version"] == EXPECTED_PROTOCOL) {
                        "unsupported_omniflow_protocol:${health["protocol_version"]}"
                    }
                }
            }
                .onSuccess { health ->
                    ready = true
                    OmniLog.i(
                        TAG,
                        "warmup_ready durationMs=${SystemClock.elapsedRealtime() - startedAt} " +
                            "protocol=${health["protocol_version"]}",
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

    suspend fun call(
        context: Context,
        operation: String,
        payload: Map<String, Any?> = emptyMap(),
        hostCall: OmniFlowPythonHostCall? = null,
    ): Map<String, Any?> = sharedClient(context).call(operation, payload, hostCall)

    private fun sharedClient(context: Context): OmniFlowPythonClient =
        client ?: synchronized(startLock) {
            client ?: OmniFlowPythonClient(context.applicationContext).also { client = it }
        }
}
