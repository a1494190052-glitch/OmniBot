package cn.com.omnimind.bot.agent.tool.handlers

import android.content.Context
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolJson
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.function.FunctionApi
import cn.com.omnimind.bot.function.FunctionService
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

class FunctionToolHandler(
    context: Context,
) : ToolHandler {
    override val toolNames: Set<String> = FunctionApi.toolNames
    private val service = FunctionService(context)

    override suspend fun execute(
        toolCall: AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle,
    ): ToolExecutionResult {
        val toolName = toolCall.function.name
        return try {
            val argsMap = AgentToolJson.jsonObjectToMap(args)
            val result = service.executeTool(toolName, argsMap)
            val payloadJson = AgentToolJson.mapToJsonElement(result).toString()
            val success = result["success"] != false
            val summary = sequenceOf(
                result["summary"],
                result["message"],
                result["error_message"],
                result["retrieval_state"],
                toolName,
            ).map { it?.toString()?.trim().orEmpty() }
                .firstOrNull { it.isNotEmpty() }
                ?: toolName
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = summary,
                previewJson = payloadJson,
                rawResultJson = payloadJson,
                success = success,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OmniLog.e(TAG, "Function tool failed: $toolName - ${e.message}", e)
            ToolExecutionResult.Error(toolName, e.message ?: "Function tool failed")
        }
    }

    private companion object {
        const val TAG = "FunctionToolHandler"
    }
}
