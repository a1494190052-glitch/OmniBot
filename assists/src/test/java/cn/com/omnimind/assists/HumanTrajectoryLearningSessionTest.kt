package cn.com.omnimind.assists

import android.content.Context
import android.content.ContextWrapper
import cn.com.omnimind.assists.task.vlmserver.ManualVlmRecordedAction
import cn.com.omnimind.assists.task.vlmserver.ManualVlmScreenshotRef
import cn.com.omnimind.assists.task.vlmserver.ManualRecordingDiagnostics
import cn.com.omnimind.baselib.runlog.InternalRunLogRecord
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HumanTrajectoryLearningSessionTest {
    @Test
    fun `manual recording inactive status uses shared schema`() {
        val status = HumanTrajectoryLearningSession.status()
        val payload = status.asMap()

        assertFalse(status.active)
        assertFalse(status.paused)
        assertEquals(false, payload["recording_active"])
        assertEquals(false, payload["recording_paused"])
        assertEquals(0, payload["action_count"])
        assertEquals("overlay_touch", payload["recording_backend"])
    }

    @Test
    fun `manual recording diagnostics require active raw touch for no-miss guarantee`() {
        val semanticOnly = ManualRecordingDiagnostics.completeness(
            rawTouchAvailable = false,
            rawTouchActiveAtStop = null,
        )
        val rawInterrupted = ManualRecordingDiagnostics.completeness(
            rawTouchAvailable = true,
            rawTouchActiveAtStop = false,
        )
        val rawComplete = ManualRecordingDiagnostics.completeness(
            rawTouchAvailable = true,
            rawTouchActiveAtStop = true,
        )

        assertEquals(null, ManualRecordingDiagnostics.warningMessage(ManualRecordingDiagnostics.COMPLETE_OVERLAY_TOUCH))
        assertEquals(ManualRecordingDiagnostics.MISSING_RAW_TOUCH, semanticOnly)
        assertEquals(ManualRecordingDiagnostics.RAW_TOUCH_INTERRUPTED, rawInterrupted)
        assertEquals(ManualRecordingDiagnostics.COMPLETE_RAW_TOUCH, rawComplete)
        assertFalse(ManualRecordingDiagnostics.guaranteesNoMissingClicks(false, null))
        assertFalse(ManualRecordingDiagnostics.guaranteesNoMissingClicks(true, false))
        assertTrue(ManualRecordingDiagnostics.guaranteesNoMissingClicks(true, true))
        assertTrue(ManualRecordingDiagnostics.warningMessage(semanticOnly)!!.contains("raw touch"))
    }

    @Test
    fun `manual recording run log card keeps replay context and timing`() {
        val beforeXml = "<hierarchy package=\"com.android.settings\"><node text=\"Bluetooth\" bounds=\"[0,0][100,100]\" /></hierarchy>"
        val afterXml = "<hierarchy package=\"com.android.settings\"><node text=\"Connected devices\" bounds=\"[0,0][100,100]\" /></hierarchy>"
        val beforeScreenshot = screenshotRef("before", "/tmp/run/screenshots/0001_before.jpg")
        val afterScreenshot = screenshotRef("after", "/tmp/run/screenshots/0002_after.jpg")
        val action = ManualVlmRecordedAction(
            actionName = "click",
            title = "人工点击 Bluetooth",
            params = linkedMapOf(
                "target_description" to "Bluetooth",
                "x" to 42f,
                "y" to 84f,
                "bounds" to "[0,0][100,100]",
                "recording_backend" to "overlay_touch",
                "target_resolution" to "overlay_touch_coordinate_xml_grounded",
            ),
            packageName = "com.android.settings",
            beforeXml = beforeXml,
            afterXml = afterXml,
            beforeScreenshot = beforeScreenshot,
            afterScreenshot = afterScreenshot,
            startedAtMs = 1000L,
            finishedAtMs = 1250L,
            summary = "人工点击 Bluetooth",
            eventContext = linkedMapOf(
                "event_type" to "OVERLAY_TOUCH_CLICK",
                "event_has_source" to false,
                "recording_backend" to "overlay_touch",
                "target_resolution" to "overlay_touch_coordinate_xml_grounded",
            ),
        )

        val card = buildRunLogCardForTest("human-test-run", 1, action)
        val params = card["params"] as Map<*, *>
        val before = card["before"] as Map<*, *>
        val after = card["after"] as Map<*, *>
        val sourceContext = card["source_context"] as Map<*, *>
        val srcCtx = sourceContext["src_ctx"] as Map<*, *>
        val dstCtx = sourceContext["dst_ctx"] as Map<*, *>
        val sourceAction = sourceContext["action"] as Map<*, *>
        val meta = sourceContext["_oob_meta"] as Map<*, *>

        assertEquals("manual_recording", card["recall_kind"])
        assertEquals(null, card["compile_kind"])
        assertEquals("human_trajectory", card["source"])
        assertEquals("com.android.settings", card["package_name"])
        assertEquals(250L, card["duration_ms"])
        assertEquals(1000L, card["started_at_ms"])
        assertEquals(1250L, card["finished_at_ms"])
        assertEquals(42f, params["x"])
        assertEquals(84f, params["y"])
        assertEquals(beforeXml, before["observation_xml"])
        assertEquals(afterXml, after["observation_xml"])
        assertEquals(beforeScreenshot.path, before["screenshot_path"])
        assertEquals(afterScreenshot.path, after["screenshot_path"])
        assertEquals(beforeXml, srcCtx["page"])
        assertEquals(afterXml, dstCtx["page"])
        assertEquals(beforeScreenshot.path, srcCtx["screenshot_path"])
        assertEquals(afterScreenshot.path, dstCtx["screenshot_path"])
        assertEquals(beforeScreenshot.path, (srcCtx["screenshot"] as Map<*, *>)["path"])
        assertEquals(afterScreenshot.path, (dstCtx["screenshot"] as Map<*, *>)["path"])
        assertEquals("click", sourceAction["tool"])
        assertEquals(42f, sourceAction["x"])
        assertEquals(84f, sourceAction["y"])
        assertEquals("manual_operation_recording", meta["mode"])
        assertEquals("overlay_touch", meta["recording_backend"])
        assertEquals("overlay_touch", meta["action_source"])
        assertEquals(action.eventContext, card["event_context"])
        assertEquals(action.eventContext, meta["event_context"])
        assertNotNull(card["tool_call"])
    }

    @Test
    fun `manual recording run log card keeps overlay touch source`() {
        val beforeXml = "<hierarchy package=\"com.android.settings\"><node text=\"Wi-Fi\" bounds=\"[0,0][100,100]\" /></hierarchy>"
        val afterXml = "<hierarchy package=\"com.android.settings\"><node text=\"Network\" bounds=\"[0,0][100,100]\" /></hierarchy>"
        val action = ManualVlmRecordedAction(
            actionName = "click",
            title = "人工点击 Wi-Fi",
            params = linkedMapOf(
                "target_description" to "Wi-Fi",
                "x" to 50f,
                "y" to 50f,
                "recording_backend" to "overlay_touch",
                "coordinate_space" to "screen_absolute_px",
                "execution_mode" to "synthetic_replay",
                "target_resolution" to "overlay_touch_coordinate_xml_grounded",
                "display_width" to 1080,
                "display_height" to 2400,
            ),
            packageName = "com.android.settings",
            beforeXml = beforeXml,
            afterXml = afterXml,
            startedAtMs = 2000L,
            finishedAtMs = 2100L,
            summary = "人工点击 Wi-Fi",
            eventContext = linkedMapOf(
                "event_type" to "OVERLAY_TOUCH_CLICK",
                "recording_backend" to "overlay_touch",
                "coordinate_space" to "screen_absolute_px",
                "execution_mode" to "synthetic_replay",
            ),
        )

        val card = buildRunLogCardForTest("human-overlay-run", 1, action)
        val params = card["params"] as Map<*, *>
        val sourceContext = card["source_context"] as Map<*, *>
        val sourceAction = sourceContext["action"] as Map<*, *>
        val meta = sourceContext["_oob_meta"] as Map<*, *>

        assertEquals("screen_absolute_px", params["coordinate_space"])
        assertEquals("synthetic_replay", params["execution_mode"])
        assertEquals("screen_absolute_px", sourceAction["coordinate_space"])
        assertEquals("synthetic_replay", sourceAction["execution_mode"])
        assertEquals("overlay_touch", meta["recording_backend"])
        assertEquals("overlay_touch", meta["action_source"])
        assertEquals(action.eventContext, meta["event_context"])
    }

    @Test
    fun `manual recording run log card stores xml artifacts when context is available`() {
        val context = TempFilesContext()
        try {
            val beforeXml = "<hierarchy package=\"com.android.settings\"><node text=\"Wi-Fi\" /></hierarchy>"
            val afterXml = "<hierarchy package=\"com.android.settings\"><node text=\"Network\" /></hierarchy>"
            val action = ManualVlmRecordedAction(
                actionName = "click",
                title = "人工点击 Wi-Fi",
                params = linkedMapOf(
                    "target_description" to "Wi-Fi",
                    "x" to 50f,
                    "y" to 50f,
                    "recording_backend" to "overlay_touch",
                ),
                packageName = "com.android.settings",
                beforeXml = beforeXml,
                afterXml = afterXml,
                startedAtMs = 2000L,
                finishedAtMs = 2100L,
                summary = "人工点击 Wi-Fi",
            )

            val card = buildRunLogCardForTest(context, "human-artifact-run", 1, action)
            val before = card["before"] as Map<*, *>
            val after = card["after"] as Map<*, *>
            val sourceContext = card["source_context"] as Map<*, *>
            val srcCtx = sourceContext["src_ctx"] as Map<*, *>
            val dstCtx = sourceContext["dst_ctx"] as Map<*, *>
            val beforePath = before["observation_xml_path"].toString()
            val afterPath = after["observation_xml_path"].toString()

            assertFalse(before.containsKey("observation_xml"))
            assertFalse(after.containsKey("observation_xml"))
            assertFalse(srcCtx.containsKey("page"))
            assertFalse(dstCtx.containsKey("page"))
            assertEquals(beforePath, srcCtx["xml_path"])
            assertEquals(afterPath, dstCtx["xml_path"])
            assertEquals(beforeXml.length, (before["observation_xml_chars"] as Number).toInt())
            assertEquals(afterXml.length, (after["observation_xml_chars"] as Number).toInt())
            assertEquals(beforeXml, File(beforePath).readText())
            assertEquals(afterXml, File(afterPath).readText())
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `unfinished human run log with replayable manual card recovers as success`() {
        val action = ManualVlmRecordedAction(
            actionName = "click",
            title = "人工点击 Wi-Fi",
            params = linkedMapOf(
                "target_description" to "Wi-Fi",
                "x" to 50f,
                "y" to 50f,
                "recording_backend" to "overlay_touch",
            ),
            packageName = "com.android.settings",
            beforeXml = "<hierarchy package=\"com.android.settings\" />",
            afterXml = null,
            startedAtMs = 2000L,
            finishedAtMs = 2100L,
            summary = "人工点击 Wi-Fi",
            eventContext = linkedMapOf("recording_backend" to "overlay_touch"),
        )
        val card = buildRunLogCardForTest("human-recovery-run", 1, action)
        val record = InternalRunLogRecord(
            runId = "human-recovery-run",
            source = ManualRecordingRunLogRecovery.HUMAN_TRAJECTORY_SOURCE,
            cards = listOf(card),
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
        assertEquals(1, manual["replayable_action_count"])
    }

    @Test
    fun `unfinished empty human run log recovers as empty recording failure`() {
        val record = InternalRunLogRecord(
            runId = "human-empty-run",
            source = ManualRecordingRunLogRecovery.HUMAN_TRAJECTORY_SOURCE,
            cards = emptyList(),
        )

        val decision = ManualRecordingRunLogRecovery.decisionFor(record)!!
        val manual = decision.diagnostics["manual_recording"] as Map<*, *>

        assertFalse(decision.success)
        assertEquals(ManualRecordingRunLogRecovery.DONE_REASON_EMPTY_AFTER_RESTART, decision.doneReason)
        assertEquals(ManualRecordingRunLogRecovery.EMPTY_RECORDING_ERROR, decision.errorMessage)
        assertEquals(0, decision.replayableActionCount)
        assertEquals(false, manual["recovered_after_restart"])
        assertEquals("none", manual["action_source"])
        assertEquals(ManualRecordingRunLogRecovery.EMPTY_RECORDING_ERROR, manual["error_message"])
    }

    @Test
    fun `non human unfinished run is not handled by manual recovery helper`() {
        val record = InternalRunLogRecord(
            runId = "agent-run",
            source = "internal_oob",
            cards = listOf(
                linkedMapOf(
                    "compile_kind" to "manual_recording",
                    "tool_name" to "click",
                )
            ),
        )

        assertEquals(null, ManualRecordingRunLogRecovery.decisionFor(record))
    }

    private fun screenshotRef(stage: String, path: String): ManualVlmScreenshotRef =
        ManualVlmScreenshotRef(
            path = path,
            relativePath = path.substringAfter("/tmp/"),
            mimeType = "image/jpeg",
            width = 630,
            height = 1400,
            bytes = 12345L,
            sha256 = "abc123",
            capturedAtMs = 999L,
            captureStage = stage,
        )

    @Suppress("UNCHECKED_CAST")
    private fun buildRunLogCardForTest(
        runId: String,
        index: Int,
        action: ManualVlmRecordedAction,
    ): Map<String, Any?> {
        val method = HumanTrajectoryLearningSession::class.java.getDeclaredMethod(
            "buildRunLogCard",
            String::class.java,
            Int::class.javaPrimitiveType,
            ManualVlmRecordedAction::class.java,
        )
        method.isAccessible = true
        return method.invoke(HumanTrajectoryLearningSession, runId, index, action) as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildRunLogCardForTest(
        context: Context,
        runId: String,
        index: Int,
        action: ManualVlmRecordedAction,
    ): Map<String, Any?> {
        val method = HumanTrajectoryLearningSession::class.java.getDeclaredMethod(
            "buildRunLogCard",
            Context::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
            ManualVlmRecordedAction::class.java,
        )
        method.isAccessible = true
        return method.invoke(HumanTrajectoryLearningSession, context, runId, index, action) as Map<String, Any?>
    }

    private class TempFilesContext : ContextWrapper(null) {
        val root: File = Files.createTempDirectory("manual-runlog-card-test").toFile()

        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = root
    }
}
