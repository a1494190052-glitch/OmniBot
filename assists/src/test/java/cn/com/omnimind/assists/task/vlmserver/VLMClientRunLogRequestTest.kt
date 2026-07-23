package cn.com.omnimind.assists.task.vlmserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VLMClientRunLogRequestTest {
    @Test
    fun `request contains only system and current screenshot turn with runlog history`() {
        val runLogSteps = listOf(
            linkedMapOf(
                "step_index" to 0,
                "before_state_id" to "before-0",
                "action" to mapOf(
                    "tool" to "open_app",
                    "args" to mapOf("package_name" to "com.example.app"),
                ),
                "result" to mapOf("success" to false, "error" to "launch failed"),
                "after_state_id" to "after-0",
                "metadata" to mapOf(
                    "post_action_observation" to mapOf("screen_changed" to false),
                ),
            ),
        )
        var receivedRunLogSteps: List<Map<String, Any?>> = emptyList()
        val client = VLMClient(
            systemPromptBuilder = { "system" },
            turnPromptBuilder = { context, steps, _ ->
                receivedRunLogSteps = steps
                "task=${context.overallTask}\n${VLMRunLogPlannerHistory.render(steps)}"
            },
            requestLogger = {},
        )

        val envelope = client.buildUIOperationRequest(
            context = UIContext(overallTask = "open example"),
            screenshot = "c2NyZWVuc2hvdA==",
            runLogSteps = runLogSteps,
        )

        assertEquals(runLogSteps, receivedRunLogSteps)
        assertEquals(2, envelope.request.messages.size)
        val currentTurn = envelope.request.messages.last().content.toString()
        assertTrue(currentTurn.contains("open_app"))
        assertTrue(currentTurn.contains("com.example.app"))
        assertTrue(currentTurn.contains("data:image/png;base64,c2NyZWVuc2hvdA=="))
    }
}
