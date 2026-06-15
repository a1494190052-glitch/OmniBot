package cn.com.omnimind.bot.runlog

import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OobFunctionRunLogRecorderTest {
    @Test
    fun `records function step results as a finished internal run log`() {
        val context = OobOmniFlowLoopAcceptanceTest.TempFilesContext()
        try {
            val runId = "omniflow_run_recorder_${System.nanoTime()}"
            val startedAtMs = 1_700_000_000_000L
            val finishedAtMs = startedAtMs + 1_200L

            val record = OobFunctionRunLogRecorder.record(
                context = context,
                functionId = "fn_open_settings",
                functionSpec = linkedMapOf(
                    "function_id" to "fn_open_settings",
                    "name" to "Open Settings",
                    "description" to "Open Android Settings",
                ),
                runPayload = linkedMapOf(
                    "success" to true,
                    "run_id" to runId,
                    "audit_run_id" to runId,
                    "runner" to "fixed_replay",
                    "step_count" to 2,
                    "success_step_count" to 2,
                    "timing" to linkedMapOf(
                        "started_at_ms" to startedAtMs,
                        "finished_at_ms" to finishedAtMs,
                    ),
                    "step_results" to listOf(
	                        linkedMapOf(
	                            "step_id" to "open_app",
	                            "index" to 0,
	                            "tool" to "open_app",
	                            "executor" to "omniflow",
	                            "summary" to "Open Settings app",
	                            "success" to true,
	                            "started_at_ms" to startedAtMs,
                            "finished_at_ms" to startedAtMs + 300L,
                        ),
                        linkedMapOf(
                            "step_id" to "finished",
                            "index" to 1,
                            "tool" to "finished",
                            "summary" to "Settings opened",
                            "success" to true,
                            "started_at_ms" to startedAtMs + 300L,
                            "finished_at_ms" to finishedAtMs,
                        ),
                    ),
                ),
            )

            assertEquals(true, record["success"])

            val timeline = InternalRunLogStore.timelinePayload(context, runId)
            assertEquals(true, timeline["success"])
            assertEquals(true, timeline["run_finished"])
            assertEquals(true, timeline["run_success"])
            assertEquals("success", timeline["run_status"])
            assertEquals(startedAtMs, timeline["started_at_ms"])
            assertEquals(finishedAtMs, timeline["finished_at_ms"])

            val cards = timeline["cards"] as List<*>
            assertEquals(2, cards.size)
            val first = cards.first() as Map<*, *>
            assertEquals("open_app", first["tool_name"])
	            assertEquals("Open Settings app", first["summary"])
	            assertEquals("success", first["status"])
	            assertEquals("hit", first["recall_kind"])
	            assertEquals("hit", first["compile_kind"])
	            val header = first["header"] as Map<*, *>
	            assertEquals("hit", header["recall_kind"])
	            assertEquals("hit", header["compile_kind"])
	            assertTrue((first["duration_ms"] as Number).toLong() >= 300L)
        } finally {
            context.root.deleteRecursively()
        }
    }
}
