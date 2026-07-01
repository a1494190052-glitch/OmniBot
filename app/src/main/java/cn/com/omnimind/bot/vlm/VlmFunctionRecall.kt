package cn.com.omnimind.bot.vlm

import android.content.Context
import cn.com.omnimind.assists.task.vlmserver.UIContext
import cn.com.omnimind.assists.task.vlmserver.VLMRecallContextProvider
import cn.com.omnimind.assists.task.vlmserver.VLMRecallContextRequest
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.function.FunctionJson.firstNonBlank
import cn.com.omnimind.bot.function.FunctionJson.listArg
import cn.com.omnimind.bot.function.FunctionJson.mapArg
import cn.com.omnimind.bot.function.FunctionService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class VlmFunctionRecall(context: Context) : VLMRecallContextProvider {
    private val appContext = context.applicationContext
    private val config get() = VlmWorkspaceConfig.getInstance(appContext).get()

    override suspend fun enrich(request: VLMRecallContextRequest): UIContext {
        if (request.disableFunctionRecall || !config.recallEnabled) {
            return request.context
        }
        val goal = request.context.activeGoal().ifBlank { request.context.overallTask }.trim()
        if (goal.isBlank()) return request.context

        val startedAt = System.currentTimeMillis()
        // recallMaxCandidates = retrieval pool size; recallMaxToolsPerStep = injection cap.
        // Query with the full pool so the best match isn't excluded before ranking.
        val fetchK = config.recallMaxCandidates.coerceAtLeast(1)
        val injectMax = config.recallMaxToolsPerStep.coerceAtLeast(0)
        if (injectMax <= 0) {
            return request.context.copy(
                pageDiagnostics = request.context.pageDiagnostics + mapOf(
                    "recall_context_lookup_ms" to "0",
                    "recall_context_decision" to "disabled_by_max_tools",
                    "recall_context_tool_count" to "0",
                )
            )
        }
        val recallResult = runCatching {
            FunctionService(appContext).recall(
                mapOf(
                    "goal" to goal,
                    "current_package" to request.currentPackageName,
                    "current_xml" to request.currentXml,
                    "k" to fetchK,
                    "decision_mode" to config.recallDecisionMode,
                )
            )
        }.onFailure { OmniLog.w(TAG, "recall failed: ${it.message}") }
            .getOrNull()
            ?: return request.context.copy(
                pageDiagnostics = request.context.pageDiagnostics + mapOf(
                    "recall_context_lookup_ms" to elapsed(startedAt),
                    "recall_context_decision" to "error",
                    "recall_context_tool_count" to "0",
                )
            )

        val candidates = recalledCandidates(recallResult)
            .take(injectMax)
        val tools = candidates.mapIndexedNotNull { index, candidate ->
            buildToolDefinition(index = index, candidate = candidate, currentGoal = goal)
        }
        val ids = tools.mapNotNull { definition ->
            val function = definition["function"] as? JsonObject
            function?.get("name")?.jsonPrimitive?.contentOrNull
        }
        return request.context.copy(
            dynamicToolDefinitions = request.context.dynamicToolDefinitions + tools,
            pageDiagnostics = request.context.pageDiagnostics + mapOf(
                "recall_context_lookup_ms" to elapsed(startedAt),
                "recall_context_decision" to firstNonBlank(recallResult["decision"]).ifBlank { "miss" },
                "recall_context_tool_count" to tools.size.toString(),
                "recall_context_tool_names" to ids.joinToString(",").take(4000),
            )
        )
    }

    private fun recalledCandidates(payload: Map<String, Any?>): List<Map<String, Any?>> {
        val seen = linkedSetOf<String>()
        val candidates = mutableListOf<Map<String, Any?>>()

        fun add(candidate: Map<String, Any?>) {
            val functionId = firstNonBlank(candidate["function_id"])
            if (functionId.isEmpty() || !seen.add(functionId)) return
            candidates += candidate
        }

        listArg(payload["candidates"]).forEach { raw ->
            mapArg(raw).takeIf { it.isNotEmpty() }?.let(::add)
        }
        listArg(payload["capability_candidates"]).forEach { raw ->
            mapArg(raw).takeIf { it.isNotEmpty() }?.let(::add)
        }
        listArg(payload["catalog_function_candidates"]).forEach { raw ->
            mapArg(raw).takeIf { it.isNotEmpty() }?.let(::add)
        }
        return candidates
    }

    private fun buildToolDefinition(
        index: Int,
        candidate: Map<String, Any?>,
        currentGoal: String,
    ): JsonObject? {
        val api = apiDescriptor(candidate)
        val functionId = firstNonBlank(api["function_id"], candidate["function_id"]).takeIf { it.isNotEmpty() } ?: return null
        val toolName = "${config.recallToolNamePrefix}_${index + 1}"
        val inputSchema = mapArg(api["parameters"]).ifEmpty {
            mapArg(candidate["inputSchema"]).ifEmpty {
                mapArg(candidate["input_schema"])
            }
        }
        val description = buildDescription(api = api, candidate = candidate, functionId = functionId, currentGoal = currentGoal)
        return buildJsonObject {
            put("type", "function")
            put("function_id", functionId)
            put("function", buildJsonObject {
                put("name", toolName)
                put("toolType", "oob_recalled_function")
                put("description", description)
                put("parameters", sanitizeInputSchema(inputSchema))
            })
        }
    }

    private fun apiDescriptor(candidate: Map<String, Any?>): Map<String, Any?> {
        val api = mapArg(candidate["api"])
        if (api.isNotEmpty()) return api
        return linkedMapOf<String, Any?>(
            "function_id" to firstNonBlank(candidate["function_id"]),
            "name" to firstNonBlank(candidate["name"]),
            "description" to firstNonBlank(candidate["description"], candidate["name"], candidate["function_id"]),
            "parameters" to mapArg(candidate["inputSchema"]).ifEmpty { mapArg(candidate["input_schema"]) },
        )
    }

    private fun buildDescription(
        api: Map<String, Any?>,
        candidate: Map<String, Any?>,
        functionId: String,
        currentGoal: String,
    ): String {
        val profile = mapArg(candidate["function_profile"])
        val purpose = firstNonBlank(profile["purpose"], profile["use_when"])
        val apiName = firstNonBlank(api["name"], candidate["name"], functionId)
        val description = firstNonBlank(api["description"], candidate["description"], apiName, purpose, functionId)
        val goal = currentGoal.replace(Regex("\\s+"), " ").trim().take(160)
        val argumentNames = listArg(api["argument_names"]).ifEmpty {
            mapArg(mapArg(api["parameters"])["properties"]).keys.toList()
        }
            .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            .take(6)
            .joinToString(", ")
        val steps = listArg(candidate["step_summaries"])
            .take(config.recallStepSummaryCount)
            .joinToString("; ") { it.toString() }
        return buildString {
            append("Saved Function API: ")
            append(apiName.ifBlank { functionId }.take(80))
            append(". Call it only when it clearly matches the current goal; otherwise continue with ordinary UI actions. ")
            if (goal.isNotBlank()) {
                append("Current user goal: ")
                append(goal)
                append(". Fill arguments from the current user goal; pass only the argument value, not the whole sentence. ")
            }
            if (argumentNames.isNotBlank()) {
                append("Arguments: ")
                append(argumentNames)
                append(". ")
            }
            append(description.ifBlank { "Saved mobile workflow" }.take(config.recallDescriptionChars))
            if (steps.isNotBlank()) {
                append(" Steps: ")
                append(steps.take(config.recallStepSummaryChars))
            }
        }.take(config.recallToolDescriptionChars)
    }

    private fun sanitizeInputSchema(inputSchema: Map<String, Any?>): JsonObject {
        val raw = toJsonObject(inputSchema)
        if (raw.isEmpty()) return emptyObjectSchema()
        val type = raw["type"]?.jsonPrimitive?.contentOrNull?.trim()
        val properties = raw["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val required = raw["required"] as? JsonArray ?: JsonArray(emptyList())
        val cleanedProperties = JsonObject(properties.filterKeys { it != TOOL_TITLE_FIELD })
        val cleanedRequired = buildJsonArray {
            required.forEach { item ->
                val name = item.jsonPrimitive.contentOrNull?.trim()
                if (!name.isNullOrBlank() && name != TOOL_TITLE_FIELD) add(JsonPrimitive(name))
            }
        }
        return buildJsonObject {
            put("type", JsonPrimitive(type.takeUnless { it.isNullOrBlank() } ?: "object"))
            put("additionalProperties", JsonPrimitive(false))
            put("properties", cleanedProperties)
            if (cleanedRequired.isNotEmpty()) put("required", cleanedRequired)
        }
    }

    private fun emptyObjectSchema(): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("additionalProperties", JsonPrimitive(false))
        put("properties", JsonObject(emptyMap()))
    }

    private fun toJsonObject(value: Map<String, Any?>): JsonObject {
        if (value.isEmpty()) return JsonObject(emptyMap())
        return JsonObject(value.mapValues { (_, item) -> toJsonElement(item) })
    }

    private fun toJsonElement(value: Any?): JsonElement =
        when (value) {
            null -> JsonNull
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Map<*, *> -> buildJsonObject {
                value.forEach { (key, item) ->
                    if (key != null) put(key.toString(), toJsonElement(item))
                }
            }
            is Iterable<*> -> buildJsonArray {
                value.forEach { add(toJsonElement(it)) }
            }
            is Array<*> -> buildJsonArray {
                value.forEach { add(toJsonElement(it)) }
            }
            else -> JsonPrimitive(value.toString())
        }

    private fun elapsed(startedAtMs: Long): String =
        (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L).toString()

    private companion object {
        private const val TAG = "VlmFunctionRecall"
        private const val TOOL_TITLE_FIELD = "tool_title"
    }
}
