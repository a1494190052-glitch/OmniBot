package cn.com.omnimind.assists.task.vlmserver

import org.junit.Assert.assertEquals
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
                "<hierarchy package=\"example.app\"/>"
            },
            elapsedRealtimeMs = { nowMs },
        )

        val result = capture.captureXml("before_click")

        assertEquals("<hierarchy package=\"example.app\"/>", result.xml)
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
        assertEquals(1, capture.stats().xmlCaptureTimeoutOrEmptyCount)
        assertEquals(300L, capture.xmlDiagnostics()["timeout_ms"])
    }
}
