package cn.com.omnimind.bot.plugin.official

import android.content.Context
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.HttpAgentLlmClient
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.agent.tool.handlers.SharedHelper
import cn.com.omnimind.bot.agent.tool.handlers.ToolHandler
import cn.com.omnimind.bot.omniflow.OmniFlow
import cn.com.omnimind.bot.omniflow.OmniFlowPluginRuntime
import cn.com.omnimind.bot.omniflow.asOmniFlowModelClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

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
            val normalizedArguments = normalizeOmniFlowManagementArguments(toolName, args)
            val payload = OmniFlow.callTool(
                context = helper.context,
                toolCall = OmniFlow.ToolCall(
                    name = toolName,
                    arguments = normalizedArguments,
                ),
                modelClient = if (OmniFlowPluginRuntime.isEnabled()) {
                    HttpAgentLlmClient(CoroutineScope(currentCoroutineContext()))
                        .asOmniFlowModelClient()
                } else {
                    null
                },
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

/**
 * A registered RunLog Function must be visible to the recall router unless the caller
 * explicitly asks for a hidden artifact. Older Agent turns commonly supplied only
 * `register=true`, which produced a successful but unusable hidden Function.
 */
internal fun normalizeOmniFlowManagementArguments(
    toolName: String,
    args: JsonObject,
): Map<String, Any?> = buildMap {
    // Keep the existing helper conversion semantics in the caller for all normal values.
    // This map is intentionally assembled from the JsonObject below so the normalization
    // remains independent of Android Context and is easy to regression-test.
    args.entries.forEach { (key, value) ->
        put(key, jsonElementToManagementValue(value))
    }
    if (
        toolName == OmniFlowManagementTools.CONVERT_RUN_LOG &&
        args["register"]?.jsonPrimitive?.booleanOrNull == true &&
        !args.containsKey("agent_visible")
    ) {
        put("agent_visible", true)
    }
}

private fun jsonElementToManagementValue(
    value: JsonElement,
): Any? = when (value) {
    JsonNull -> null
    is JsonObject -> value.entries.associate { (key, item) ->
        key to jsonElementToManagementValue(item)
    }
    is JsonArray -> value.map(::jsonElementToManagementValue)
    is JsonPrimitive -> when {
        value.isString -> value.content
        value.booleanOrNull != null -> value.booleanOrNull
        value.longOrNull != null -> value.longOrNull
        value.doubleOrNull != null -> value.doubleOrNull
        else -> value.content
    }
}
