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
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startLock = Any()

    @Volatile
    private var client: OmniFlowPythonClient? = null

    fun start(context: Context) {
        val sharedClient = synchronized(startLock) {
            if (client != null) return
            OmniFlowPythonClient(context.applicationContext).also { client = it }
        }
        val startedAt = SystemClock.elapsedRealtime()
        runtimeScope.launch {
            runCatching { sharedClient.warmup() }
                .onSuccess { health ->
                    OmniLog.i(
                        TAG,
                        "warmup_ready durationMs=${SystemClock.elapsedRealtime() - startedAt} " +
                            "protocol=${health["protocol_version"]}",
                    )
                }
                .onFailure { error ->
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
