package cn.com.omnimind.bot.manager

import cn.com.omnimind.baselib.runlog.InternalRunLogFinishEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RunLogFinishedEventPayloadTest {
    @Test
    fun payloadUsesCanonicalRunLogIdentity() {
        val payload = runLogFinishedEventPayload(
            InternalRunLogFinishEvent(
                runId = "child-vlm-run",
                goal = "搜索咖啡",
                source = "vlm",
                toolName = "vlm_task",
                operationDescription = "搜索咖啡",
                startedAtMs = 100L,
                finishedAtMs = 200L,
                success = true,
                doneReason = "finished",
                errorMessage = "",
                stepCount = 2,
            )
        )

        assertEquals(
            mapOf(
                "run_id" to "child-vlm-run",
                "source" to "vlm",
                "tool_name" to "vlm_task",
                "success" to true,
            ),
            payload,
        )
        assertFalse(payload.containsKey("taskId"))
        assertFalse(payload.containsKey("task_id"))
    }
}
