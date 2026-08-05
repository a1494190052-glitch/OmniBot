package cn.com.omnimind.bot.omniflow

import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionFunction
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionStreamOptions
import cn.com.omnimind.baselib.llm.ChatCompletionTool
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import cn.com.omnimind.baselib.util.ImageCompressor
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive

interface OmniFlowModelClient {
    suspend fun streamTurn(
        request: ChatCompletionRequest,
        onReasoningUpdate: (suspend (String) -> Unit)? = null,
    ): ChatCompletionTurn
}

class OmniFlowModelHost(
    private val modelClient: OmniFlowModelClient,
    private val imageCompressor: (String) -> String = ::compressVlmImage,
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
            request = request.copy(
                messages = request.messages.map { message ->
                    message.copy(content = compressImages(message.content))
                },
            ),
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

    suspend fun completeJson(
        payload: Map<String, Any?>,
        modelOverride: String? = null,
    ): Map<String, Any?> {
        val request = jsonCompletionRequest(
            payload = payload,
            model = modelOverride ?: firstText(payload["model"], "scene.dispatch.model"),
        )
        val turn = withTimeout(180_000L) {
            modelClient.streamTurn(request)
        }
        val content = submitJsonArguments(turn)
        return mapOf("content" to content)
    }

    private fun usage(turn: ChatCompletionTurn): Map<String, Any?>? {
        val usage = turn.usage ?: return null
        val promptDetails = usage.promptTokensDetails as? JsonObject
        val completionDetails = usage.completionTokensDetails as? JsonObject
        return linkedMapOf<String, Any?>(
            "prompt_tokens" to usage.promptTokens,
            "completion_tokens" to usage.completionTokens,
            "total_tokens" to usage.totalTokens,
            "reasoning_tokens" to completionDetails.intValue("reasoning_tokens"),
            "text_tokens" to completionDetails.intValue("text_tokens"),
            "image_tokens" to promptDetails.intValue("image_tokens"),
            "cached_tokens" to promptDetails.intValue("cached_tokens"),
            "prefill_tokens_per_second" to usage.prefillTokensPerSecond,
            "decode_tokens_per_second" to usage.decodeTokensPerSecond,
        ).filterValues { it != null }.takeIf(Map<String, Any?>::isNotEmpty)
    }

    private fun JsonObject?.intValue(key: String): Int? =
        this?.get(key)?.jsonPrimitive?.contentOrNull?.toIntOrNull()

    private fun compressImages(content: JsonElement?): JsonElement? {
        val blocks = content as? JsonArray ?: return content
        return JsonArray(
            blocks.map { item ->
                val block = item as? JsonObject ?: return@map item
                if (block["type"]?.jsonPrimitive?.contentOrNull != "image_url") {
                    return@map item
                }
                val imageUrl = block["image_url"] as? JsonObject ?: return@map item
                val url = imageUrl["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (!url.startsWith("data:image/")) return@map item
                JsonObject(
                    block + (
                        "image_url" to JsonObject(
                            imageUrl + ("url" to JsonPrimitive(imageCompressor(url))),
                        )
                    ),
                )
            },
        )
    }

    companion object {
        private fun compressVlmImage(value: String): String {
            val compressed = ImageCompressor.compressBase64Image(
                base64String = value,
                scale = 0.3f,
                quality = 70,
                bypassThreshold = 0L,
            ).base64
            val payload = compressed.substringAfter(",", "")
            return if (payload.isBlank()) value else "data:image/jpeg;base64,$payload"
        }

        suspend fun completeJson(
            payload: Map<String, Any?>,
            modelOverride: String? = null,
        ): Map<String, Any?> {
            val request = jsonCompletionRequest(
                payload = payload,
                model = modelOverride ?: firstText(payload["model"], "scene.dispatch.model"),
            )
            val content = withTimeout(180_000L) {
                OmniFlowPythonRuntime.completeJson(request)
            }
            check(content.isNotBlank()) { "model_completion_empty" }
            return mapOf("content" to content)
        }

        private fun jsonCompletionRequest(
            payload: Map<String, Any?>,
            model: String,
        ): ChatCompletionRequest =
            ChatCompletionRequest(
                model = model,
                messages = listOf(
                    ChatCompletionMessage(
                        role = "user",
                        content = JsonPrimitive(firstText(payload["prompt"])),
                    ),
                ),
                maxCompletionTokens = intValue(payload["max_tokens"], defaultValue = 1800),
                temperature = (payload["temperature"] as? Number)?.toDouble() ?: 0.1,
                stream = true,
                streamOptions = ChatCompletionStreamOptions(),
                tools = listOf(
                    ChatCompletionTool(
                        function = ChatCompletionFunction(
                            name = "submit_json",
                            description = "Submit the requested JSON object.",
                            parameters = buildJsonObject {
                                put("type", JsonPrimitive("object"))
                                put("additionalProperties", JsonPrimitive(true))
                            },
                        ),
                    ),
                ),
                toolChoice = JsonPrimitive("required"),
                parallelToolCalls = false,
            )

        private fun submitJsonArguments(turn: ChatCompletionTurn): String {
            val toolCall = turn.message.toolCalls.orEmpty().singleOrNull {
                it.function.name == "submit_json"
            } ?: error("model_completion_submit_json_required")
            return toolCall.function.arguments.trim().ifBlank {
                error("model_completion_submit_json_empty")
            }
        }
    }
}
