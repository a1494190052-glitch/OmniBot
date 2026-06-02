package cn.com.omnimind.bot.agent.tool.handlers

import org.junit.Assert.assertEquals
import org.junit.Test

class OobFunctionCallRequestResolverTest {
    @Test
    fun `function parameters are preferred for oob function run arguments`() {
        val request = OobFunctionCallRequestResolver().resolve(
            args = mapOf(
                "function_id" to "xiaohongshu_search",
                "function_parameters" to mapOf("keyword" to "美食"),
                "arguments" to mapOf("keyword" to "ignored"),
            ),
            isKnownFunction = { it == "xiaohongshu_search" },
        )

        assertEquals("xiaohongshu_search", request.functionId)
        assertEquals(mapOf("keyword" to "美食"), request.targetArgs)
    }
}
