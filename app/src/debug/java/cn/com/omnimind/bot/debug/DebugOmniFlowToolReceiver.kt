package cn.com.omnimind.bot.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import cn.com.omnimind.bot.agent.HttpAgentLlmClient
import cn.com.omnimind.bot.omniflow.OmniFlow
import cn.com.omnimind.bot.omniflow.OmniFlowPluginRuntime
import cn.com.omnimind.bot.omniflow.asOmniFlowModelClient
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

class DebugOmniFlowToolReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        scope.launch {
            try {
                val payload = runCatching {
                    val name = intent?.getStringExtra("name")?.trim().orEmpty()
                    require(name.isNotEmpty()) { "tool name is required" }
                    val argumentsJson = intent?.getStringExtra("argumentsBase64")
                        ?.let { String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8) }
                    val arguments = argumentsJson?.let {
                        gson.fromJson<Map<String, Any?>>(it, mapType)
                    }.orEmpty()
                    OmniFlow.callTool(
                        context = appContext,
                        toolCall = OmniFlow.ToolCall(name, arguments),
                        modelClient = if (OmniFlowPluginRuntime.isEnabled()) {
                            HttpAgentLlmClient(CoroutineScope(currentCoroutineContext()))
                                .asOmniFlowModelClient()
                        } else {
                            null
                        },
                    ).payload
                }.getOrElse { error ->
                    linkedMapOf(
                        "success" to false,
                        "error_message" to (error.message ?: error.javaClass.simpleName),
                        "error_type" to error.javaClass.name,
                    )
                }
                File(appContext.filesDir, RESULT_FILE).writeText(gson.toJson(payload))
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val RESULT_FILE = "debug-omniflow-tool-result.json"
        val gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
        val mapType = object : TypeToken<Map<String, Any?>>() {}.type
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
