package cn.com.omnimind.assists.controller.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class HttpControllerReasoningEffortTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `openai compatible body maps max effort to xhigh`() {
        val payload = HttpController.applyOpenAiCompatibleReasoningEffort(
            """
                {
                  "model": "DeepSeek-V4-Pro",
                  "messages": [{"role": "user", "content": "hello"}],
                  "reasoning_effort": "max"
                }
            """.trimIndent()
        )

        val root = json.parseToJsonElement(payload).jsonObject
        assertEquals("xhigh", root["reasoning_effort"]?.jsonPrimitive?.content)
    }
}
