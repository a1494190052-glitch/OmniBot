package cn.com.omnimind.bot.omniflow

import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement

interface OmniFlowModelClient {
    suspend fun streamTurn(
        request: ChatCompletionRequest,
        onReasoningUpdate: (suspend (String) -> Unit)? = null,
    ): ChatCompletionTurn
}

class OmniFlowModelHost(
    private val modelClient: OmniFlowModelClient,
    private val onReasoningUpdate: suspend (String) -> Unit = {},
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    suspend fun modelTurn(payload: Map<String, Any?>): Map<String, Any?> {
        val requestedModel = firstText(payload["model"])
        require(requestedModel.isNotEmpty()) { "model_turn_model_required" }
        val request = json.decodeFromJsonElement<ChatCompletionRequest>(
            jsonValue(mapValue(payload["request"])),
        )
        require(request.model == requestedModel) { "model_turn_request_model_mismatch" }
        val turn = modelClient.streamTurn(
            request = request,
            onReasoningUpdate = { thinking ->
                thinking.trim().takeIf(String::isNotEmpty)?.let { onReasoningUpdate(it) }
            },
        )
        val resolvedModel = turn.resolvedModel?.trim().orEmpty().ifBlank { requestedModel }
        return linkedMapOf<String, Any?>(
            "requested_model" to requestedModel,
            "resolved_model" to resolvedModel,
            "tool_calls" to turn.message.toolCalls.orEmpty().map { toolCall ->
                linkedMapOf(
                    "id" to toolCall.id,
                    "type" to toolCall.type,
                    "function" to linkedMapOf(
                        "name" to toolCall.function.name,
                        "arguments" to toolCall.function.arguments,
                    ),
                )
            },
            "reasoning" to turn.reasoning.trim().takeIf(String::isNotEmpty),
            "finish_reason" to turn.finishReason,
            "usage" to usage(turn),
        ).filterValues { it != null }
    }

    private fun usage(turn: ChatCompletionTurn): Map<String, Any?>? {
        val usage = turn.usage ?: return null
        return linkedMapOf<String, Any?>(
            "prompt_tokens" to usage.promptTokens,
            "completion_tokens" to usage.completionTokens,
            "total_tokens" to usage.totalTokens,
            "prefill_tokens_per_second" to usage.prefillTokensPerSecond,
            "decode_tokens_per_second" to usage.decodeTokensPerSecond,
        ).filterValues { it != null }.takeIf(Map<String, Any?>::isNotEmpty)
    }

    companion object {
        suspend fun completeJson(payload: Map<String, Any?>): Map<String, Any?> {
            val request = ChatCompletionRequest(
                model = firstText(payload["model"], "scene.dispatch.model"),
                messages = listOf(
                    ChatCompletionMessage(
                        role = "user",
                        content = JsonPrimitive(firstText(payload["prompt"])),
                    ),
                ),
                maxCompletionTokens = intValue(payload["max_tokens"], defaultValue = 1800),
                temperature = (payload["temperature"] as? Number)?.toDouble() ?: 0.1,
                responseFormat = buildJsonObject {
                    put("type", JsonPrimitive("json_object"))
                },
            )
            val content = withTimeout(120_000L) {
                OmniFlowPlatformRegistry.require().completeJson(request)
            }
            check(content.isNotBlank()) { "model_completion_empty" }
            return mapOf("content" to content)
        }
    }
}
