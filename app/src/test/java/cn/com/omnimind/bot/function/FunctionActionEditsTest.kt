package cn.com.omnimind.bot.function

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FunctionActionEditsTest {
    @Test
    fun `action edits only change explicitly addressed actions`() {
        val spec = linkedMapOf<String, Any?>(
            "function_id" to "fn_input",
            "bindings" to emptyList<Map<String, Any?>>(),
            "steps" to listOf(
                step(0, "click", mapOf("x" to 500, "y" to 500)),
                step(1, "input_text", mapOf("text" to "A")),
                step(2, "input_text", mapOf("text" to "Ada")),
                step(3, "wait", mapOf("duration_ms" to 1000)),
            ),
        )

        val changes = FunctionActionEdits.apply(
            spec,
            listOf(
                mapOf(
                    "op" to "delete",
                    "index" to 1,
                    "expected_tool" to "input_text",
                    "reason" to "incremental input superseded by the next action",
                ),
                mapOf(
                    "op" to "replace_args",
                    "index" to 3,
                    "expected_tool" to "wait",
                    "args" to mapOf(
                        "duration_ms" to 5000,
                    ),
                ),
            ),
        )

        assertEquals(2, changes.size)
        assertEquals(
            listOf(
                step(0, "click", mapOf("x" to 500, "y" to 500)),
                step(1, "input_text", mapOf("text" to "Ada")),
                step(2, "wait", mapOf("duration_ms" to 5000L)),
            ),
            spec["steps"],
        )
    }

    @Test
    fun `action edits reject storage metadata`() {
        val spec = linkedMapOf<String, Any?>(
            "function_id" to "fn_input",
            "bindings" to emptyList<Map<String, Any?>>(),
            "steps" to listOf(
                step(0, "wait", mapOf("duration_ms" to 1000)),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            FunctionActionEdits.apply(
                spec,
                listOf(
                    mapOf(
                        "op" to "replace_args",
                        "index" to 0,
                        "expected_tool" to "wait",
                        "args" to mapOf("source_xml" to "<hierarchy />"),
                    ),
                ),
            )
        }
    }

    private fun step(index: Int, tool: String, args: Map<String, Any?>): Map<String, Any?> =
        linkedMapOf(
            "step_index" to index,
            "action" to linkedMapOf("tool" to tool, "args" to args),
        )
}
