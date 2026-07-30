package cn.com.omnimind.bot.omniflow

import android.content.Context
import cn.com.omnimind.bot.agent.HttpAgentLlmClient
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OmniFlowToolChannel(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun handle(call: MethodCall, result: MethodChannel.Result): Boolean {
        if (call.method != METHOD_CALL_TOOL) return false
        val payload = call.arguments as? Map<*, *>
        val name = payload?.get("name")?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            result.error("OMNIFLOW_TOOL_CALL_INVALID", "name is required", null)
            return true
        }
        val arguments = (payload?.get("arguments") as? Map<*, *>)
            ?.entries
            ?.associate { (key, value) -> key.toString() to value }
            .orEmpty()
        scope.launch {
            runCatching {
                OmniFlow.callTool(
                    context = appContext,
                    toolCall = OmniFlow.ToolCall(name, arguments),
                    modelClient = if (OmniVlmPlugin.isEnabled()) {
                        HttpAgentLlmClient(CoroutineScope(currentCoroutineContext()))
                            .asOmniFlowModelClient()
                    } else {
                        null
                    },
                ).payload
            }.onSuccess { response ->
                withContext(Dispatchers.Main.immediate) { result.success(response) }
            }.onFailure { error ->
                withContext(Dispatchers.Main.immediate) {
                    result.error(
                        "OMNIFLOW_TOOL_CALL_FAILED",
                        error.message ?: error.javaClass.simpleName,
                        null,
                    )
                }
            }
        }
        return true
    }

    fun clear() {
        scope.cancel()
    }

    private companion object {
        const val METHOD_CALL_TOOL = "tools/call"
    }
}
