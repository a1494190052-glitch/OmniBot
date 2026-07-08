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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
        if (score < SCORE_THRESHOLD) return null

        val functionId = firstNonBlank(candidateRaw["function_id"])
        if (functionId.isBlank()) return null

        val inputSchema = mapArg(candidateRaw["parameters"]).ifEmpty {
            mapArg(candidateRaw["inputSchema"]).ifEmpty { mapArg(candidateRaw["input_schema"]) }
        }

        // One LLM call: verify the match AND fill parameters in a single prompt.
        val result = verifyAndFill(goal, candidateRaw, inputSchema, streamClient) ?: return null
        if (!result.matches) {
            OmniLog.d(TAG, "Eager recall rejected by LLM: $functionId (score=$score)")
            return null
        }

        OmniLog.d(TAG, "Eager recall confirmed: $functionId (score=$score)")
        return FunctionRunAction(functionId = functionId, arguments = result.arguments)
    }

    private data class VerifyFillResult(val matches: Boolean, val arguments: JsonObject)

    private suspend fun verifyAndFill(
        goal: String,
        candidate: Map<String, Any?>,
        inputSchema: Map<String, Any?>,
        streamClient: VLMStreamClient,
    ): VerifyFillResult? {
        val functionName = firstNonBlank(candidate["name"], candidate["function_id"])
        val profile = mapArg(candidate["function_profile"])
        val purpose = firstNonBlank(profile["purpose"], profile["use_when"], candidate["description"])
        val useWhen = firstNonBlank(profile["use_when"], profile["reuse_when"], purpose)
        val properties = mapArg(inputSchema["properties"])
        val requiredParams = requiredParamNames(inputSchema)

        val paramSection = if (requiredParams.isNotEmpty()) buildString {
            append("\nParameters to extract if matches:\n")
            requiredParams.forEach { name ->
                val prop = mapArg(properties[name])
                val desc = firstNonBlank(prop["description"], prop["title"])
                append("- $name: ${desc.ifBlank { "value" }}\n")
            }
        } else ""

        val userContent = buildString {
            append("User goal: $goal\n\n")
            append("Saved workflow: $functionName")
            if (purpose.isNotBlank()) append("\nDescription: $purpose")
            if (useWhen.isNotBlank() && useWhen != purpose) append("\nUse when: $useWhen")
            append(paramSection)
            append("\nReturn JSON: {\"matches\": true/false, \"arguments\": {\"param\": \"value\"}}")
            append("\nIf matches is false, set arguments to {}.")
        }

        val model = VLMRuntimeConfigRegistry.get().primarySceneId
        val request = ChatCompletionRequest(
            model = model,
            messages = listOf(
                ChatCompletionMessage(
                    role = "system",
                    content = JsonPrimitive(
                        "Decide if a saved workflow handles the user goal. " +
                            "If yes, extract any required parameter values from the goal. " +
                            "Return only valid JSON with keys \"matches\" (boolean) and \"arguments\" (object)."
                    )
                ),
                ChatCompletionMessage(role = "user", content = JsonPrimitive(userContent))
            ),
            maxTokens = 256,
            temperature = 0.0,
        )

        val turn = runCatching { streamClient.streamTurn(request) }
            .onFailure { OmniLog.w(TAG, "verify+fill LLM call failed: ${it.message}") }
            .getOrNull() ?: return null

        return parseVerifyFillResponse(turn.turn.message.contentText(), requiredParams)
    }

    private fun parseVerifyFillResponse(text: String, requiredParams: List<String>): VerifyFillResult? {
        val raw = text.trim()
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val parsed = runCatching {
            Json { ignoreUnknownKeys = true }.parseToJsonElement(raw.substring(start, end + 1)).jsonObject
        }.onFailure { OmniLog.w(TAG, "verify+fill JSON parse failed: ${it.message}") }
            .getOrNull() ?: return null

        val matches = parsed["matches"]?.jsonPrimitive?.booleanOrNull ?: return null
        if (!matches) return VerifyFillResult(matches = false, arguments = JsonObject(emptyMap()))

        val argsElement = parsed["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        val missing = requiredParams.filter { it !in argsElement }
        if (missing.isNotEmpty()) {
            OmniLog.w(TAG, "verify+fill missing required params: $missing")
            return null
        }
        return VerifyFillResult(matches = true, arguments = argsElement)
    }

    private fun requiredParamNames(inputSchema: Map<String, Any?>): List<String> =
        listArg(inputSchema["required"])
            .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            .filter { it != "tool_title" }

    companion object {
        private const val TAG = "VlmRecallFunctionSelector"
        private const val SCORE_THRESHOLD = 0.85f
    }
}
