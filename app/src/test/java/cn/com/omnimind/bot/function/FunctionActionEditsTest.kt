package cn.com.omnimind.bot.function

import org.junit.Assert.assertEquals
import org.junit.Test

class FunctionActionEditsTest {
    @Test
    fun `action edits only change explicitly addressed actions`() {
        val spec = linkedMapOf<String, Any?>(
            "function_id" to "fn_input",
            "actions" to listOf(
                action("click", mapOf("x" to 500, "y" to 500)),
                action("input_text", mapOf("text" to "A")),
                action("input_text", mapOf("text" to "Ada")),
                action("wait", mapOf("time_s" to 1)),
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
                        "time_s" to 5,
                        "source_xml" to "<hierarchy />",
                    ),
                ),
            ),
        )

        assertEquals(2, changes.size)
        assertEquals(
            listOf(
                action("click", mapOf("x" to 500, "y" to 500)),
                action("input_text", mapOf("text" to "Ada")),
                action("wait", mapOf("time_s" to 5)),
            ),
            spec["actions"],
        )
    }

    private fun action(tool: String, args: Map<String, Any?>): Map<String, Any?> =
        linkedMapOf("tool" to tool, "args" to args)
}
