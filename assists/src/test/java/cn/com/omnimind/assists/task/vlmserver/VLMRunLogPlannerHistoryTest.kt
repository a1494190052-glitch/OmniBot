package cn.com.omnimind.assists.task.vlmserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VLMRunLogPlannerHistoryTest {
    @Test
    fun `planner history includes every completed runlog action`() {
        val steps = (0 until 12).map { index ->
            runLogStep(
                index = index,
                tool = "open_app",
                args = mapOf("package_name" to "com.example.$index"),
                success = index % 2 == 0,
                screenChanged = index % 3 == 0,
            )
        }

        val summary = VLMRunLogPlannerHistory.render(steps)

        assertTrue(summary.contains("#1 open_app"))
        assertTrue(summary.contains("com.example.0"))
        assertTrue(summary.contains("#12 open_app"))
        assertTrue(summary.contains("com.example.11"))
    }

    @Test
    fun `third identical failed action replans before dispatch`() {
        val candidate = actionOf("open_app", mapOf("package_name" to "com.meituan"))
        val steps = listOf(
            runLogStep(0, "open_app", mapOf("package_name" to "com.meituan"), false, false),
            runLogStep(1, "open_app", mapOf("package_name" to "com.meituan"), false, false),
        )

        assertEquals(
            RepeatedActionDecision.REPLAN,
            VLMRunLogPlannerHistory.evaluateRepeatedAction(steps, candidate),
        )
    }

    @Test
    fun `repeating an action already rejected by guard stops task`() {
        val candidate = actionOf("open_app", mapOf("package_name" to "com.meituan"))
        val steps = listOf(
            runLogStep(0, "open_app", mapOf("package_name" to "com.meituan"), false, false),
            runLogStep(1, "open_app", mapOf("package_name" to "com.meituan"), false, false),
            runLogStep(
                index = 2,
                tool = "open_app",
                args = mapOf("package_name" to "com.meituan"),
                success = false,
                screenChanged = false,
                failureKind = "repeated_action_blocked",
            ),
        )

        assertEquals(
            RepeatedActionDecision.STOP,
            VLMRunLogPlannerHistory.evaluateRepeatedAction(steps, candidate),
        )
    }

    @Test
    fun `repeated swipe remains allowed while screen keeps changing`() {
        val args = mapOf(
            "x1" to 500,
            "y1" to 800,
            "x2" to 500,
            "y2" to 200,
        )
        val candidate = actionOf("swipe", args)
        val steps = listOf(
            runLogStep(0, "swipe", args, true, true),
            runLogStep(1, "swipe", args, true, true),
        )

        assertEquals(
            RepeatedActionDecision.ALLOW,
            VLMRunLogPlannerHistory.evaluateRepeatedAction(steps, candidate),
        )
    }

    private fun runLogStep(
        index: Int,
        tool: String,
        args: Map<String, Any?>,
        success: Boolean,
        screenChanged: Boolean,
        failureKind: String? = null,
    ): Map<String, Any?> = linkedMapOf(
        "step_index" to index,
        "before_state_id" to "before-$index",
        "action" to linkedMapOf(
            "tool" to tool,
            "args" to args,
        ),
        "result" to linkedMapOf(
            "success" to success,
            "error" to if (success) null else "failed-$index",
        ).filterValues { it != null },
        "after_state_id" to "after-$index",
        "metadata" to linkedMapOf(
            "message" to if (success) "ok-$index" else "failed-$index",
            "post_action_observation" to mapOf("screen_changed" to screenChanged),
            "failure" to failureKind?.let { mapOf("kind" to it) },
        ).filterValues { it != null },
    )
}
