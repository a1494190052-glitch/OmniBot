package cn.com.omnimind.bot.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.util.AssistsUtil
import cn.com.omnimind.bot.vlm.VlmToolCoordinator
import com.google.gson.GsonBuilder
import java.io.File

class DebugVlmCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val taskId = intent?.getStringExtra("taskId")?.trim().orEmpty()
        val reason = intent?.getStringExtra("reason")?.trim().orEmpty()
            .ifBlank { "debug_cli_cancel" }
        val result = runCatching {
            if (!AssistsUtil.Core.isInitialized()) {
                AssistsUtil.Core.initCore(appContext)
            }
            if (taskId.isNotEmpty()) {
                VlmToolCoordinator.cancelTask(
                    taskId = taskId,
                    message = "VLM task cancelled by debug CLI: $reason",
                )
                linkedMapOf<String, Any?>(
                    "success" to true,
                    "task_id" to taskId,
                    "cancel_mode" to "targeted_vlm_task",
                    "reason" to reason,
                )
            } else {
                val cancelled = AssistsUtil.Core.cancelRunningTask(null)
                linkedMapOf<String, Any?>(
                    "success" to cancelled,
                    "task_id" to null,
                    "cancel_mode" to "current_pending_task",
                    "reason" to reason,
                )
            }
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
    }

    private companion object {
        const val TAG = "DebugVlmCancelReceiver"
        const val RESULT_FILE = "debug-vlm-cancel-result.json"
        val gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
    }
}
