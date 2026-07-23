package cn.com.omnimind.baselib.runlog

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class InternalRunLogStoreCanonicalStepTest {
    @Test
    fun canonicalStepAcceptsOnlyFiveTruthFieldsAndMetadata() {
        val step = InternalRunLogStore.canonicalStep(
            mapOf(
                "step_index" to 0,
                "before_state_id" to "before",
                "action" to mapOf("tool" to "wait", "args" to mapOf("duration_ms" to 1000)),
                "result" to mapOf("success" to true),
                "after_state_id" to "after",
                "metadata" to mapOf("summary" to "wait"),
            ),
        )

        assertEquals(
            setOf(
                "step_index",
                "before_state_id",
                "action",
                "result",
                "after_state_id",
                "metadata",
            ),
            step.keys,
        )
    }

    @Test
    fun canonicalStepRejectsTopLevelExtensions() {
        assertThrows(IllegalArgumentException::class.java) {
            InternalRunLogStore.canonicalStep(
                mapOf(
                    "step_index" to 0,
                    "before_state_id" to "before",
                    "action" to mapOf("tool" to "wait", "args" to emptyMap<String, Any?>()),
                    "result" to mapOf("success" to true),
                    "after_state_id" to "after",
                    "summary" to "legacy",
                ),
            )
        }
    }

    @Test
    fun canonicalJsonIntegersSurviveGenericGsonMaps() {
        val normalized = InternalRunLogStore.sanitizeMap(
            mapOf(
                "step_index" to 0.0,
                "before_state" to mapOf(
                    "state_id" to "state-0",
                    "display" to mapOf(
                        "width" to 1080.0,
                        "height" to 2400.0,
                    ),
                ),
                "action" to mapOf(
                    "tool" to "wait",
                    "args" to mapOf("duration_ms" to 500.0),
                ),
                "score" to 0.5,
            )
        )

        assertEquals(0L, normalized["step_index"])
        val display = (normalized["before_state"] as Map<*, *>)["display"] as Map<*, *>
        assertEquals(1080L, display["width"])
        assertEquals(2400L, display["height"])
        assertEquals(
            500L,
            ((normalized["action"] as Map<*, *>)["args"] as Map<*, *>)["duration_ms"],
        )
        assertEquals(0.5, normalized["score"])
    }

    @Test
    fun runLogRecordSerializesAsCanonicalTruth() {
        val json = Gson().toJson(
            CanonicalRunLogRecord(
                runId = "run-1",
                goal = "search",
                status = "succeeded",
                success = true,
                finishedAtMs = 200L,
                diagnostics = mapOf("source" to "vlm"),
            )
        )

        assertTrue(json.contains("\"schema_version\":\"omniflow.canonical_run_log.v1\""))
        assertTrue(json.contains("\"run_id\":\"run-1\""))
        assertTrue(json.contains("\"finished_at_ms\":200"))
        assertFalse(json.contains("\"runId\""))
        assertFalse(json.contains("\"finishedAtMs\""))
        assertFalse(json.contains("event_log_path"))
    }
}
