package cn.com.omnimind.bot.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.com.omnimind.androidgui.AndroidGuiEnvironment
import cn.com.omnimind.baselib.util.OmniLog
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
                val environment = AndroidGuiEnvironment(appContext)
                waitForAccessibility(environment)
                val includeXml = intent?.getBooleanExtra("includeXml", false) ?: false
                val state = environment.observe(
                    captureScreenshot = intent?.getBooleanExtra("includeScreenshot", false) ?: false,
                ).asMap().toMutableMap()
                if (!includeXml) state.remove("xml")
                linkedMapOf<String, Any?>(
                    "success" to true,
                    "accessibility_status" to environment.accessibilityStatus().name.lowercase(),
                    "state" to state,
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

    private suspend fun waitForAccessibility(environment: AndroidGuiEnvironment) {
        if (!environment.isAccessibilityEnabled()) {
            error("android_gui_accessibility_disabled")
        }
        repeat(50) {
            if (environment.isReady()) return
            delay(200L)
        }
        error("android_gui_accessibility_not_ready")
    }

    companion object {
        private const val TAG = "DebugGetStateReceiver"
        private const val RESULT_FILE = "debug-get-state-result.json"
        private val gson = GsonBuilder().disableHtmlEscaping().create()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
