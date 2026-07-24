package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import cn.com.omnimind.baselib.llm.ChatCompletionUsage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class VLMTokenUsageMapperTest {
    @Test
    fun imageTokensArePreservedInRunLogUsage() {
        val usage = VLMTokenUsageMapper.fromTurn(
            turn = ChatCompletionTurn(
                message = ChatCompletionMessage(role = "assistant"),
                usage = ChatCompletionUsage(
                    promptTokens = 3160,
                    completionTokens = 38,
                    totalTokens = 3198,
                    promptTokensDetails = JsonObject(
                        mapOf(
                            "text_tokens" to JsonPrimitive(2512),
                            "image_tokens" to JsonPrimitive(648),
                        )
                    ),
                ),
            ),
            resolvedModel = "scene.vlm.operation.primary",
            attemptIndex = 1,
            stabilityAttempt = 0,
            toolRetryIndex = 0,
        )

        assertEquals(648, usage?.imageTokens)
        assertEquals(648, usage?.let(VLMTokenUsageMapper::toRunLogMap)?.get("image_tokens"))
    }
}
