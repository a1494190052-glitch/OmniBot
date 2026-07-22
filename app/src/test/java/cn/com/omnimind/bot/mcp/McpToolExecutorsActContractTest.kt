package cn.com.omnimind.bot.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class McpToolExecutorsActContractTest {
    @Test
    fun `act accepts only canonical action object`() {
        val request = McpToolExecutors.normalizeActRequest(
            mapOf(
                "action" to mapOf(
                    "tool" to "click",
                    "args" to mapOf("x" to 900, "y" to 75),
                ),
            ),
        )

        assertEquals("click", request.tool)
        assertEquals(mapOf("x" to 900, "y" to 75), request.args)
    }

    @Test
    fun `act rejects coordinate space override`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            McpToolExecutors.normalizeActRequest(
                mapOf(
                    "action" to mapOf(
                        "tool" to "click",
                        "args" to mapOf("x" to 900, "y" to 75),
                    ),
                    "coordinate_space" to "absolute",
                ),
            )
        }

        assertEquals("act_unknown_fields:coordinate_space", error.message)
    }

    @Test
    fun `act rejects legacy action aliases`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            McpToolExecutors.normalizeActRequest(
                mapOf("action_type" to "tap", "x" to 100, "y" to 200),
            )
        }

        assertEquals("act_unknown_fields:action_type,x,y", error.message)
    }
}
