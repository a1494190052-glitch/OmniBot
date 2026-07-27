package cn.com.omnimind.bot.omniflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OmniFlowRunDiagnosticsTest {
    @Test
    fun `planner rejection history becomes runlog diagnostics`() {
        val rejectedCalls = listOf(
            mapOf(
                "turn_index" to 1,
                "tool" to "click",
                "error" to "canonical_action_arg_type_invalid:x",
                "arguments" to mapOf("x" to listOf(500), "y" to listOf(464)),
            ),
        )

        assertEquals(
            mapOf("planner" to mapOf("rejected_tool_calls" to rejectedCalls)),
            plannerRunLogDiagnostics(
                mapOf(
                    "planner_diagnostics" to mapOf(
                        "rejected_tool_calls" to rejectedCalls,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `missing planner diagnostics does not create empty runlog data`() {
        assertNull(plannerRunLogDiagnostics(emptyMap()))
        assertNull(plannerRunLogDiagnostics(mapOf("planner_diagnostics" to emptyMap<String, Any?>())))
    }
}
