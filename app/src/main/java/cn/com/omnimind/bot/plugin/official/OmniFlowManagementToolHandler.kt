package cn.com.omnimind.bot.plugin.official

import android.content.Context
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.agent.tool.handlers.SharedHelper
import cn.com.omnimind.bot.agent.tool.handlers.ToolHandler
import cn.com.omnimind.bot.omniflow.OmniFlow
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class OmniFlowManagementToolHandler(context: Context) : ToolHandler {
    private val helper = SharedHelper(
        context = context.applicationContext,
        json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
    )

    override val toolNames: Set<String> = OmniFlowManagementTools.TOOL_NAMES

    override suspend fun execute(
        toolCall: AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle,
    ): ToolExecutionResult {
        val toolName = toolCall.function.name
        if (toolName !in toolNames) {
            return ToolExecutionResult.Error(toolName, "Unsupported OmniFlow management tool")
        }
        return try {
            helper.ensureRunActive()
            toolHandle.throwIfStopRequested()
            val payload = OmniFlow.callTool(
                context = helper.context,
                toolCall = OmniFlow.ToolCall(
                    name = toolName,
                    arguments = helper.jsonObjectToMap(args),
                ),
            ).payload
            val encoded = helper.mapToJsonElement(payload).toString()
            if (payload["success"] == false) {
                ToolExecutionResult.Error(
                    toolName,
                    payload["error_message"]?.toString()
                        ?: payload["error_code"]?.toString()
                        ?: "OmniFlow management tool failed",
                )
            } else {
                ToolExecutionResult.ContextResult(
                    toolName = toolName,
                    summaryText = summary(toolName, payload),
                    previewJson = encoded,
                    rawResultJson = encoded,
                    success = true,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ToolExecutionResult.Error(
                toolName,
                error.message.orEmpty().ifBlank { error.javaClass.simpleName },
            )
        }
    }

    private fun summary(toolName: String, payload: Map<String, Any?>): String = when (toolName) {
        OmniFlowManagementTools.LIST_FUNCTIONS ->
            "已读取 ${payload["count"] ?: 0} 个复用指令"
        OmniFlowManagementTools.LIST_RUN_LOGS ->
            "已读取 ${payload["count"] ?: (payload["runs"] as? List<*>)?.size ?: 0} 个 RunLog"
        OmniFlowManagementTools.CONVERT_RUN_LOG -> "RunLog 已转换为复用指令"
        else -> "OmniFlow 操作已完成"
    }
}
