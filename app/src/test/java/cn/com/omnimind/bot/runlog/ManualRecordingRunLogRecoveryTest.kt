package cn.com.omnimind.bot.runlog

import cn.com.omnimind.assists.ManualRecordingRunLogRecovery
import cn.com.omnimind.baselib.runlog.InternalRunLogRecord
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualRecordingRunLogRecoveryTest {
    @Test
    fun `unfinished human run with replayable action recovers as successful runlog`() {
        val record = InternalRunLogRecord(
            runId = "human-recovery-run",
            source = ManualRecordingRunLogRecovery.HUMAN_TRAJECTORY_SOURCE,
            cards = listOf(manualActionCard(action = "click", backend = "overlay_touch"))
        )

        val decision = ManualRecordingRunLogRecovery.decisionFor(record)!!
        val manual = decision.diagnostics["manual_recording"] as Map<*, *>

        assertTrue(decision.success)
        assertEquals(ManualRecordingRunLogRecovery.DONE_REASON_RECOVERED_AFTER_RESTART, decision.doneReason)
        assertEquals(null, decision.errorMessage)
        assertEquals(1, decision.replayableActionCount)
        assertEquals(true, manual["recovered_after_restart"])
        assertEquals("overlay_touch", manual["action_source"])
        assertEquals(true, manual["guarantees_no_missing_clicks"])
    }

    @Test
    fun `unfinished human run without replayable actions becomes empty recording failure`() {
        val record = InternalRunLogRecord(
            runId = "human-empty-run",
            source = ManualRecordingRunLogRecovery.HUMAN_TRAJECTORY_SOURCE,
            cards = emptyList()
        )

        val decision = ManualRecordingRunLogRecovery.decisionFor(record)!!
        val manual = decision.diagnostics["manual_recording"] as Map<*, *>

        assertFalse(decision.success)
        assertEquals(ManualRecordingRunLogRecovery.DONE_REASON_EMPTY_AFTER_RESTART, decision.doneReason)
        assertEquals(ManualRecordingRunLogRecovery.EMPTY_RECORDING_ERROR, decision.errorMessage)
        assertEquals(0, decision.replayableActionCount)
        assertEquals(false, manual["recovered_after_restart"])
        assertEquals(ManualRecordingRunLogRecovery.EMPTY_RECORDING_ERROR, manual["error_message"])
    }

    @Test
    fun `non human unfinished run is left for orphan cleanup`() {
        val record = InternalRunLogRecord(
            runId = "agent-run",
            source = "internal_oob",
            cards = listOf(manualActionCard(action = "click", backend = "overlay_touch"))
        )

        assertEquals(null, ManualRecordingRunLogRecovery.decisionFor(record))
    }

    @Test
    fun `debug screenshots default off and require explicit opt in`() {
        listOf(
            readSource("app/src/debug/java/cn/com/omnimind/bot/debug/DebugHumanRunRecordingReceiver.kt"),
            readSource("app/src/debug/java/cn/com/omnimind/bot/debug/DebugManualTraceReceiver.kt")
        ).forEach { source ->
            assertTrue(source.contains("intent ?: return false"))
            assertTrue(source.contains("getBooleanExtra(\"disableDebugScreenshots\", false)"))
            assertTrue(source.contains("getBooleanExtra(\"skipScreenshots\", false)"))
            assertTrue(source.contains("!source.getBooleanExtra(\"keepScreenshots\", true)"))
            assertTrue(source.contains("source.getBooleanExtra(\"debugScreenshots\", false)"))
            assertTrue(source.contains("source.getBooleanExtra(\"enableDebugScreenshots\", false)"))
            assertTrue(source.contains("source.getBooleanExtra(\"recordDebugScreenshots\", false)"))
            assertFalse(source.contains("getBooleanExtra(\"debugScreenshots\", true)"))
        }
    }

    @Test
    fun `manual screenshot capture is isolated from action recording path`() {
        val source = readSource(
            "assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/ManualVlmTraceRecorder.kt"
        )

        assertTrue(source.contains("catch (error: OutOfMemoryError)"))
        assertTrue(source.contains("disabled_after_oom"))
        assertTrue(source.contains("recordDebugScreenshotFailure("))
        assertTrue(source.contains("\"last_error_type\" to debugScreenshotLastErrorType"))
        assertTrue(source.contains("if (debugScreenshotsActive()) null else lastScreenshotSnapshot"))
    }

    @Test
    fun `idle consolidation recovers human runlogs before orphaning non human runs`() {
        val source = readSource("app/src/main/java/cn/com/omnimind/bot/manager/AssistsCoreManager.kt")

        assertTrue(source.contains("ManualRecordingRunLogRecovery.decisionFor(record)"))
        assertTrue(source.contains("success = recovery.success"))
        assertTrue(source.contains("doneReason = recovery.doneReason"))
        assertTrue(source.contains("errorMessage = recovery.errorMessage"))
        assertTrue(source.contains("doneReason = \"orphaned\""))
    }

    private fun manualActionCard(action: String, backend: String): Map<String, Any?> =
        linkedMapOf(
            "compile_kind" to "manual_recording",
            "tool_type" to "manual_recording",
            "tool_name" to action,
            "params" to linkedMapOf("recording_backend" to backend)
        )

    private fun readSource(relativePath: String): String {
        val candidates = listOf(
            Paths.get(relativePath),
            Paths.get("..").resolve(relativePath)
        )
        val path = candidates.firstOrNull { Files.exists(it) }
            ?: error("Missing source file: $relativePath from ${Paths.get("").toAbsolutePath()}")
        return String(Files.readAllBytes(path))
    }
}
