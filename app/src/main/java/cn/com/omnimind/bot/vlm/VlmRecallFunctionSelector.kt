package cn.com.omnimind.bot.vlm

import android.content.Context
import cn.com.omnimind.assists.task.vlmserver.FunctionRunAction
import cn.com.omnimind.assists.task.vlmserver.VLMRuntimeConfigRegistry
import cn.com.omnimind.assists.task.vlmserver.VLMRecallActionProvider
import cn.com.omnimind.assists.task.vlmserver.VLMStreamClient
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.contentText
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.function.FunctionService
import cn.com.omnimind.bot.runlog.firstNonBlank
import cn.com.omnimind.bot.runlog.listArg
import cn.com.omnimind.bot.runlog.mapArg
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

class VlmRecallFunctionSelector(private val context: Context) : VLMRecallActionProvider {

    override suspend fun selectAction(
        goal: String,
        packageName: String?,
        disableFunctionRecall: Boolean,
        streamClient: VLMStreamClient,
    ): FunctionRunAction? {
        if (disableFunctionRecall || goal.isBlank()) return null
        val config = VlmWorkspaceConfig.getInstance(context).get()
        if (!config.recallEnabled) return null

        val recall = runCatching {
            FunctionService(context).recall(
                mapOf("goal" to goal, "current_package" to packageName.orEmpty(), "k" to 1)
            )
        }.onFailure { OmniLog.w(TAG, "recall failed: ${it.message}") }
            .getOrNull() ?: return null

        val candidateRaw = mapArg(listArg(recall["candidates"]).firstOrNull())
        if (candidateRaw.isEmpty()) return null

        val score = candidateRaw["score"]?.toString()?.toFloatOrNull() ?: 0f
        if (score < HIGH_CONFIDENCE_THRESHOLD) return null

        val functionId = firstNonBlank(candidateRaw["function_id"])
        if (functionId.isBlank()) return null

        val inputSchema = mapArg(candidateRaw["parameters"]).ifEmpty {
            mapArg(candidateRaw["inputSchema"]).ifEmpty { mapArg(candidateRaw["input_schema"]) }
        }
        val requiredParams = requiredParamNames(inputSchema)

        val arguments = if (requiredParams.isEmpty()) {
            buildJsonObject {}
        } else {
            fillParams(goal, candidateRaw, inputSchema, requiredParams, streamClient) ?: return null
        }

        OmniLog.d(TAG, "Eager recall selected: $functionId (score=$score, requiredParams=$requiredParams)")
        return FunctionRunAction(functionId = functionId, arguments = arguments)
    }

    private suspend fun fillParams(
        goal: String,
        candidate: Map<String, Any?>,
        inputSchema: Map<String, Any?>,
        requiredParams: List<String>,
        streamClient: VLMStreamClient,
    ): JsonObject? {
        val functionName = firstNonBlank(candidate["name"], candidate["function_id"])
        val profile = mapArg(candidate["function_profile"])
        val purpose = firstNonBlank(profile["purpose"], profile["use_when"], candidate["description"])
        val properties = mapArg(inputSchema["properties"])

        val paramDetail = buildString {
            requiredParams.forEach { name ->
                val prop = mapArg(properties[name])
                val desc = firstNonBlank(prop["description"], prop["title"])
                append("- $name: ${desc.ifBlank { "value" }}\n")
            }
        }

        val userContent = buildString {
            append("User goal: $goal\n")
            append("Workflow: $functionName")
            if (purpose.isNotBlank()) append(" — $purpose")
            append("\nExtract these parameters:\n$paramDetail")
            append("Return JSON only, e.g. {\"param\": \"value\"}")
        }

        val model = VLMRuntimeConfigRegistry.get().primarySceneId
        val request = ChatCompletionRequest(
            model = model,
            messages = listOf(
                ChatCompletionMessage(
                    role = "system",
                    content = JsonPrimitive("Extract parameter values from the user goal. Return only valid JSON.")
                ),
                ChatCompletionMessage(role = "user", content = JsonPrimitive(userContent))
            ),
            maxTokens = 256,
            temperature = 0.0,
        )

        val turn = runCatching { streamClient.streamTurn(request) }
            .onFailure { OmniLog.w(TAG, "param fill LLM call failed: ${it.message}") }
            .getOrNull() ?: return null

        return parseJsonArguments(turn.turn.message.contentText(), requiredParams)
    }

    private fun parseJsonArguments(text: String, requiredParams: List<String>): JsonObject? {
        val raw = text.trim()
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val jsonText = raw.substring(start, end + 1)
        val parsed = runCatching {
            Json { ignoreUnknownKeys = true }.parseToJsonElement(jsonText).jsonObject
        }.onFailure { OmniLog.w(TAG, "param fill JSON parse failed: ${it.message}") }
            .getOrNull() ?: return null
        val missing = requiredParams.filter { it !in parsed }
        if (missing.isNotEmpty()) {
            OmniLog.w(TAG, "param fill missing required params: $missing")
            return null
        }
        return parsed
    }

    private fun requiredParamNames(inputSchema: Map<String, Any?>): List<String> =
        listArg(inputSchema["required"])
            .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            .filter { it != "tool_title" }

    companion object {
        private const val TAG = "VlmRecallFunctionSelector"
        private const val HIGH_CONFIDENCE_THRESHOLD = 0.85f
    }
}
