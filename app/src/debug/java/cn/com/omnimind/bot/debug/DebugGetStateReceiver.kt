package cn.com.omnimind.bot.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.mcp.McpToolExecutors
import cn.com.omnimind.bot.util.AssistsUtil
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class DebugGetStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        scope.launch {
            val result = runCatching {
                if (!AssistsUtil.Core.isInitialized()) {
                    AssistsUtil.Core.initCore(appContext)
                }
                waitForAccessibility()
                McpToolExecutors.executeGetState(
                    appContext,
                    mapOf(
                        "include_xml" to (intent?.getBooleanExtra("includeXml", false) ?: false),
                        "include_screenshot" to (intent?.getBooleanExtra("includeScreenshot", false) ?: false),
                        "include_indexed_context" to (intent?.getBooleanExtra("includeIndexedContext", true) ?: true),
                        "include_marked_screenshot" to (intent?.getBooleanExtra("includeMarkedScreenshot", false) ?: false),
                        "include_image_content" to (intent?.getBooleanExtra("includeImageContent", false) ?: false),
                    )
                )
            }.getOrElse { error ->
                linkedMapOf<String, Any?>(
                    "success" to false,
                    "phase" to "exception",
                    "error_message" to error.message.orEmpty(),
                    "error_type" to error.javaClass.name,
                )
            }
            val json = gson.toJson(result)
            File(appContext.filesDir, RESULT_FILE).writeText(json)
            OmniLog.i(TAG, json)
        }
    }

    private suspend fun waitForAccessibility() {
        repeat(50) {
            if (AssistsService.instance != null && AccessibilityController.initController()) return
            delay(200L)
        }
        error("OOB accessibility service is not bound")
    }

    companion object {
        private const val TAG = "DebugGetStateReceiver"
        private const val RESULT_FILE = "debug-get-state-result.json"
        private val gson = GsonBuilder().disableHtmlEscaping().create()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
