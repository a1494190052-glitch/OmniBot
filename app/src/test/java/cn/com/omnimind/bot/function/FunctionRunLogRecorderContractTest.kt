package cn.com.omnimind.bot.function

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class FunctionRunLogRecorderContractTest {
    @Test
    fun `canonical function result becomes one canonical runlog step`() {
        val step = FunctionRunLogRecorder.canonicalStepFromResult(
            runId = "run-1",
            functionId = "search",
            step = mapOf(
                "step_index" to 0,
                "tool" to "click",
                "args" to mapOf("x" to 500, "y" to 600),
                "success" to true,
                "before_state" to mapOf("state_id" to "state-before", "xml" to "<before />"),
                "after_state" to mapOf("state_id" to "state-after", "xml" to "<after />"),
                "started_at_ms" to 100L,
                "finished_at_ms" to 150L,
            ),
            fallbackIndex = 9,
        )

        assertEquals(0, step.step["step_index"])
        assertEquals("state-before", step.step["before_state_id"])
        assertEquals("state-after", step.step["after_state_id"])
        assertEquals(
            mapOf("tool" to "click", "args" to mapOf("x" to 500L, "y" to 600L)),
            step.step["action"],
        )
        assertEquals(true, (step.step["result"] as Map<*, *>)["success"])
        assertEquals(listOf("state-before", "state-after"), step.states.map { it["state_id"] })
        assertFalse(step.step.containsKey("before_state"))
        assertFalse(step.step.containsKey("after_state"))
        assertFalse(step.step.containsKey("cards"))
        assertFalse(step.step.containsKey("tool_call"))
    }

    @Test
    fun `legacy aliases do not enter canonical function runlog`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            FunctionRunLogRecorder.canonicalStepFromResult(
                runId = "run-1",
                functionId = "search",
                step = mapOf(
                    "stepIndex" to 7,
                    "action_type" to "click",
                    "params" to mapOf("x" to 500, "y" to 600),
                    "success" to true,
                ),
                fallbackIndex = 1,
            )
        }

        assertEquals("function_step_tool_required", error.message)
    }
}
