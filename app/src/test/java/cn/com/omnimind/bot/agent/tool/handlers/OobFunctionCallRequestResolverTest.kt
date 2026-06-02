package cn.com.omnimind.bot.agent.tool.handlers

import org.junit.Assert.assertEquals
import org.junit.Test

class OobFunctionCallRequestResolverTest {
    @Test
    fun `arguments are used for oob function run parameters`() {
        val request = OobFunctionCallRequestResolver().resolve(
            args = mapOf(
                "function_id" to "xiaohongshu_search",
                "arguments" to mapOf("keyword" to "彩票"),
            ),
            isKnownFunction = { it == "xiaohongshu_search" },
        )

        assertEquals("xiaohongshu_search", request.functionId)
        assertEquals(mapOf("keyword" to "彩票"), request.targetArgs)
    }
}
