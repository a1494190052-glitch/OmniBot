package cn.com.omnimind.bot.vlm

import android.content.Context
import cn.com.omnimind.assists.task.vlmserver.UIContext
import cn.com.omnimind.assists.task.vlmserver.VLMRecallContextProvider
import cn.com.omnimind.assists.task.vlmserver.VLMRecallContextRequest
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentToolJson.mapToJsonElement
import cn.com.omnimind.bot.omniflow.OmniFlowFunctionRecallAdapter
import cn.com.omnimind.bot.omniflow.OmniFlowPythonRuntime
import cn.com.omnimind.bot.runlog.firstNonBlank
import cn.com.omnimind.bot.runlog.listArg
import cn.com.omnimind.bot.runlog.mapArg
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class VlmFunctionRecall internal constructor(
    private val configProvider: () -> VlmWorkspaceConfig.Snapshot,
    private val recall: suspend (Map<String, Any?>) -> Map<String, Any?>,
) : VLMRecallContextProvider {
    private val config get() = configProvider()

    constructor(context: Context) : this(
        configProvider = {
            VlmWorkspaceConfig.getInstance(context.applicationContext).get()
        },
        recall = createRecall(context.applicationContext),
    )

    override suspend fun enrich(request: VLMRecallContextRequest): UIContext {
        if (request.disableFunctionRecall || !config.recallEnabled) return request.context
        val goal = request.context.activeGoal().ifBlank { request.context.overallTask }.trim()
        if (goal.isBlank()) return request.context

        val maxTools = config.recallMaxToolsPerStep.coerceAtLeast(0)
        if (maxTools == 0) return request.context
        val startedAt = System.currentTimeMillis()
        val result = runCatching {
            recall(
                mapOf(
                    "goal" to goal,
                    "current_package" to request.currentPackageName,
                    "current_xml" to request.currentXml,
                    "k" to config.recallMaxCandidates.coerceAtLeast(1),
                ),
            )
        }.onFailure { OmniLog.w(TAG, "recall failed: ${it.message}") }
            .getOrNull()
            ?: return withDiagnostics(request.context, startedAt, "unavailable", emptyList(), "")

        val tools = candidates(result)
            .take(maxTools)
            .mapIndexedNotNull { index, candidate ->
                toolDefinition(index, candidate, goal)
            }
        return withDiagnostics(
            context = request.context.copy(
                dynamicToolDefinitions = request.context.dynamicToolDefinitions + tools,
            ),
            startedAt = startedAt,
            state = firstNonBlank(result["retrieval_state"]).ifBlank { "miss" },
            tools = tools,
            reason = firstNonBlank(result["reason"]),
        )
    }

    private fun candidates(payload: Map<String, Any?>): List<Map<String, Any?>> {
        val seen = linkedSetOf<String>()
        return listArg(payload["candidates"]).mapNotNull { raw ->
            val candidate = mapArg(raw)
            val functionId = firstNonBlank(mapArg(candidate["function"])["function_id"])
            candidate.takeIf { functionId.isNotBlank() && seen.add(functionId) }
        }
    }

    private fun toolDefinition(
        index: Int,
        candidate: Map<String, Any?>,
        goal: String,
    ): JsonObject? {
        val function = mapArg(candidate["function"])
        val functionId = firstNonBlank(function["function_id"])
        if (functionId.isBlank()) return null
        val inputSchema = mapArg(function["input_schema"])
        return buildJsonObject {
            put("type", "function")
            put("function_id", functionId)
            put("function", buildJsonObject {
                put("name", "${config.recallToolNamePrefix}_${index + 1}")
                put("tool_type", "oob_recalled_function")
                put("description", description(function, inputSchema, goal))
                put("parameters", mapToJsonElement(inputSchema) as JsonObject)
            })
        }
    }

    private fun description(
        function: Map<String, Any?>,
        inputSchema: Map<String, Any?>,
        goal: String,
    ): String {
        val functionId = firstNonBlank(function["function_id"])
        val name = firstNonBlank(function["name"])
        val description = firstNonBlank(function["description"])
        val properties = mapArg(inputSchema["properties"])
        val required = listArg(inputSchema["required"])
            .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
        val arguments = properties.entries.take(8).joinToString("; ") { (argument, raw) ->
            val property = mapArg(raw)
            val type = firstNonBlank(property["type"]).ifBlank { "string" }
            val detail = firstNonBlank(property["description"])
            buildString {
                append(argument)
                append(" (")
                if (argument in required) append("required, ")
                append(type)
                append(")")
                if (detail.isNotBlank()) append(": ${detail.take(96)}")
            }
        }
        return buildString {
            append("Saved workflow `$functionId`: $name. $description. ")
            append("Current goal: ${goal.replace(Regex("\\s+"), " ").take(160)}. ")
            if (arguments.isNotBlank()) append("Arguments: $arguments. ")
            append("Call only when this workflow clearly advances the current goal.")
        }.take(config.recallToolDescriptionChars.coerceAtLeast(200))
    }

    private fun withDiagnostics(
        context: UIContext,
        startedAt: Long,
        state: String,
        tools: List<JsonObject>,
        reason: String,
    ): UIContext {
        val names = tools.mapNotNull { definition ->
            (definition["function"] as? JsonObject)
                ?.get("name")
                ?.jsonPrimitive
                ?.contentOrNull
        }
        return context.copy(
            pageDiagnostics = context.pageDiagnostics + linkedMapOf(
                "recall_context_lookup_ms" to
                    (System.currentTimeMillis() - startedAt).coerceAtLeast(0L).toString(),
                "recall_context_state" to state,
                "recall_context_tool_count" to tools.size.toString(),
                "recall_context_tool_names" to names.joinToString(",").take(4000),
                "recall_context_runtime_source" to "omniflow_python",
                "recall_context_reason" to reason.take(240),
            ).filterValues(String::isNotBlank),
        )
    }

    private companion object {
        const val TAG = "VlmFunctionRecall"

        fun createRecall(context: Context): suspend (Map<String, Any?>) -> Map<String, Any?> {
            val adapter = OmniFlowFunctionRecallAdapter { operation, payload ->
                OmniFlowPythonRuntime.call(context, operation, payload)
            }
            return adapter::recall
        }
    }
}
