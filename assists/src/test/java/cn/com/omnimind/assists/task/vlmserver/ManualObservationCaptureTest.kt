package cn.com.omnimind.assists.task.vlmserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ManualObservationCaptureTest {
    @Test
    fun captureXmlTracksSuccessfulObservation() {
        var nowMs = 10L
        val capture = ManualObservationCapture(
            sessionLabel = "test",
            debugScreenshotsRequested = false,
            xmlProvider = {
                nowMs = 17L
                "<hierarchy><node package=\"example.app\" text=\"Ready\" /></hierarchy>"
            },
            elapsedRealtimeMs = { nowMs },
        )

        val result = capture.captureXml("before_click")

        assertEquals(
            "<hierarchy><node package=\"example.app\" text=\"Ready\" /></hierarchy>",
            result.xml,
        )
        assertEquals(7L, result.durationMs)
        assertEquals(1, capture.stats().xmlCaptureSuccessCount)
        assertEquals("before_click", capture.stats().xmlCaptureLastReason)
    }

    @Test
    fun blankXmlIsCountedAsUnavailable() {
        val capture = ManualObservationCapture(
            sessionLabel = "test",
            debugScreenshotsRequested = false,
            xmlProvider = { "   " },
            elapsedRealtimeMs = { 20L },
        )

        val result = capture.captureXml("stop")

        assertNull(result.xml)
        assertEquals(1, capture.stats().xmlCaptureEmptyCount)
        assertFalse(capture.xmlDiagnostics().containsKey("timeout_ms"))
    }

    @Test
    fun rootOnlyXmlIsCountedAsUnavailable() {
        val capture = ManualObservationCapture(
            sessionLabel = "test",
            debugScreenshotsRequested = false,
            xmlProvider = {
                "<hierarchy><node class=\"android.widget.FrameLayout\" package=\"example.app\" /></hierarchy>"
            },
            elapsedRealtimeMs = { 20L },
        )

        val result = capture.captureXml("after_click")

        assertNull(result.xml)
        assertEquals(1, capture.stats().xmlCaptureEmptyCount)
    }

    @Test
    fun transitionTreeWithoutSemanticNodesIsCountedAsUnavailable() {
        val capture = ManualObservationCapture(
            sessionLabel = "test",
            debugScreenshotsRequested = false,
            xmlProvider = {
                "<hierarchy>" +
                    List(4) { "<node class=\"android.widget.FrameLayout\" />" }.joinToString("") +
                    "</hierarchy>"
            },
            elapsedRealtimeMs = { 20L },
        )

        val result = capture.captureXml("after_click")

        assertNull(result.xml)
        assertEquals(1, capture.stats().xmlCaptureEmptyCount)
    }

    @Test
    fun swipeAnnotationUsesCanonicalCoordinates() {
        val annotation = ManualScreenshotAnnotation(
            actionName = "swipe",
            x = 10f,
            y = 20f,
            endX = 30f,
            endY = 40f,
        ).asMap(appliedScale = 1f)

        assertEquals(10f, annotation["x1"])
        assertEquals(20f, annotation["y1"])
        assertEquals(30f, annotation["x2"])
        assertEquals(40f, annotation["y2"])
        assertFalse(annotation.containsKey("end_x"))
        assertFalse(annotation.containsKey("end_y"))
    }
}
