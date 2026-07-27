package cn.com.omnimind.bot.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.omniflow.OmniFlow
import cn.com.omnimind.bot.webchat.AgentRunService
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class DebugAgentCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val taskId = intent?.getStringExtra("taskId")?.trim().orEmpty()
        val reason = intent?.getStringExtra("reason")?.trim().orEmpty()
            .ifBlank { "debug_cli_cancel" }
        scope.launch {
            try {
                val result = runCatching {
                    val omniFlowStopRequested = OmniFlow.stop(
                        taskId.takeIf(String::isNotEmpty),
                    )
                    AgentRunService(appContext).cancelTask(taskId.takeIf(String::isNotEmpty)) +
                        linkedMapOf<String, Any?>(
                            "success" to true,
                            "cancel_mode" to if (taskId.isNotEmpty()) "targeted_agent_task" else "current_agent_task",
                            "omniflow_stop_requested" to omniFlowStopRequested,
                            "reason" to reason,
                        )
                }.getOrElse { error ->
                    linkedMapOf<String, Any?>(
                        "success" to false,
                        "error_message" to error.message.orEmpty(),
                        "error_type" to error.javaClass.name,
                        "reason" to reason,
                    )
                }
                val json = gson.toJson(result)
                File(appContext.filesDir, RESULT_FILE).writeText(json)
                OmniLog.i(TAG, json)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "DebugAgentCancelReceiver"
        const val RESULT_FILE = "debug-agent-cancel-result.json"
        val gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
