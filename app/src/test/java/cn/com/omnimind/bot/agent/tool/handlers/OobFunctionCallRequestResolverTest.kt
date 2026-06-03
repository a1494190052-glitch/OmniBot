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

    @Test
    fun `function id and arguments are canonical function call shape`() {
        val request = OobFunctionCallRequestResolver().resolve(
            args = mapOf(
                "tool_title" to "小红书搜索猫猫",
                "function_id" to "oob_fn_human_trajectory_fd980fdf",
                "arguments" to mapOf("input_text_3" to "猫猫"),
            ),
            isKnownFunction = { it == "oob_fn_human_trajectory_fd980fdf" },
        )

        assertEquals("oob_fn_human_trajectory_fd980fdf", request.functionId)
        assertEquals(mapOf("input_text_3" to "猫猫"), request.targetArgs)
    }
}
