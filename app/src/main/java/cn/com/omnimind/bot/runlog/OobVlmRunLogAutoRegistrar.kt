package cn.com.omnimind.bot.runlog

import android.content.Context
import cn.com.omnimind.baselib.runlog.InternalRunLogFinishEvent
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.omniflow.OobFunctionToolNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Publishes successful VLM RunLogs as agent-visible reusable Functions so the
 * next similar vlm_task can be selected by the existing OOB recall/replay path.
 */
object OobVlmRunLogAutoRegistrar {
    private const val TAG = "OobVlmRunLogAutoRegistrar"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun bind(context: Context) {
        val appContext = context.applicationContext ?: context
        InternalRunLogStore.setFinishListener { event ->
            if (!shouldAutoRegister(event)) return@setFinishListener
            scope.launch {
                registerRunLog(appContext, event)
            }
        }
    }

    internal fun shouldAutoRegister(event: InternalRunLogFinishEvent): Boolean {
        if (!event.success) return false
        if (event.runId.isBlank()) return false
        return event.source == "vlm" && event.toolName == "vlm_task"
    }

    private suspend fun registerRunLog(context: Context, event: InternalRunLogFinishEvent) {
        val result = runCatching {
            OobRunLogReplayService(context).convertRunLog(
                runId = event.runId,
                register = true,
                agentVisible = true,
            )
        }.onFailure { error ->
            OmniLog.w(TAG, "auto-register failed runId=${event.runId}: ${error.message}")
        }.getOrNull() ?: return

        val success = result["success"] == true
        val functionId = result["function_id"]?.toString().orEmpty()
        if (success) {
            OmniLog.i(TAG, "auto-registered VLM RunLog runId=${event.runId} functionId=$functionId")
            if (functionId.isNotBlank() && result["already_exists"] != true) {
                enhanceRegisteredFunction(context, event.runId, functionId)
            }
        } else {
            OmniLog.w(
                TAG,
                "auto-register skipped runId=${event.runId}: " +
                    "${result["error_code"] ?: result["error_message"] ?: "unknown"}"
            )
        }
    }

    private suspend fun enhanceRegisteredFunction(
        context: Context,
        runId: String,
        functionId: String,
    ) {
        val result = runCatching {
            OobOmniFlowToolkitService(context).executeTool(
                OobFunctionToolNames.FUNCTION_UPDATE,
                linkedMapOf(
                    "function_id" to functionId,
                    "run_id" to runId,
                    "mode" to "enhance",
                    "source" to "oob_vlm_runlog_auto_registrar",
                )
            )
        }.onFailure { error ->
            OmniLog.w(
                TAG,
                "auto-enhance failed runId=$runId functionId=$functionId: ${error.message}"
            )
        }.getOrNull() ?: return

        if (result["success"] == true) {
            OmniLog.i(
                TAG,
                "auto-enhanced VLM Function runId=$runId functionId=$functionId " +
                    "changed=${result["changed"]}"
            )
        } else {
            OmniLog.w(
                TAG,
                "auto-enhance skipped runId=$runId functionId=$functionId: " +
                    "${result["error_code"] ?: result["error_message"] ?: "unknown"}"
            )
        }
    }
}
