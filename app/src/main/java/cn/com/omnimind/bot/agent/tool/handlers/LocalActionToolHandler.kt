package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.assists.task.vlmserver.ActionExecutor
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.runlog.OobActionSchema
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolJson
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import kotlinx.serialization.json.JsonObject

class LocalActionToolHandler(
    private val actionExecutor: ActionExecutor,
) : ToolHandler {
    override val toolNames: Set<String> = OobActionSchema.replayableToolNames

    override fun canHandle(toolName: String): Boolean = toolName in toolNames

    override suspend fun execute(
        toolCall: AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle,
    ): ToolExecutionResult {
        val action = toolCall.function.name
        val argsMap = runCatching { AgentToolJson.jsonObjectToMap(args) }
            .getOrDefault(emptyMap())
        return runCatching {
            val result = actionExecutor.act(
                action = action,
                args = argsMap,
                source = "agent_local_action",
            )
            if (result.success) {
                ToolExecutionResult.ContextResult(
                    toolName = action,
                    summaryText = result.message.ifBlank { action },
                    previewJson = "{}",
                    rawResultJson = "{}",
                    success = true,
                )
            } else {
                ToolExecutionResult.Error(action, result.message)
            }
        }.getOrElse { error ->
            OmniLog.e(TAG, "local act failed: $action - ${error.message}", error)
            ToolExecutionResult.Error(action, error.message ?: "action failed")
        }
    }

    private companion object {
        const val TAG = "LocalActionToolHandler"
    }
}
