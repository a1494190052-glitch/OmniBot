package cn.com.omnimind.assists.recording

import cn.com.omnimind.baselib.runlog.OobActionSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManualGestureRecognizerTest {
    private val overlayThresholds = ManualGestureThresholds.overlay(
        touchSlopPx = 20f,
        longPressTimeoutMs = 500L,
    )

    @Test
    fun shortStationaryTraceIsClick() {
        val gesture = ManualGestureRecognizer.recognize(
            trace(durationMs = 120L, endX = 108f, endY = 104f),
            overlayThresholds,
        )

        assertEquals(OobActionSchema.TOOL_CLICK, gesture?.actionName)
    }

    @Test
    fun longStationaryTraceIsLongPress() {
        val gesture = ManualGestureRecognizer.recognize(
            trace(durationMs = 700L, endX = 102f, endY = 101f),
            overlayThresholds,
        )

        assertEquals(OobActionSchema.TOOL_LONG_PRESS, gesture?.actionName)
    }

    @Test
    fun displacedTraceIsDirectionalSwipe() {
        val gesture = ManualGestureRecognizer.recognize(
            trace(durationMs = 320L, endX = 100f, endY = 20f),
            overlayThresholds,
        )

        assertEquals(OobActionSchema.TOOL_SWIPE, gesture?.actionName)
        assertEquals("up", gesture?.direction)
    }

    @Test
    fun rawThresholdGapRejectsAmbiguousMotion() {
        val gesture = ManualGestureRecognizer.recognize(
            trace(durationMs = 300L, endX = 145f, endY = 100f),
            ManualGestureThresholds.rawTouch(),
        )

        assertNull(gesture)
    }

    @Test
    fun rawLongStationaryTracePastMaximumIsRejected() {
        val gesture = ManualGestureRecognizer.recognize(
            trace(durationMs = 2_600L, endX = 100f, endY = 100f),
            ManualGestureThresholds.rawTouch(),
        )

        assertNull(gesture)
    }

    private fun trace(
        durationMs: Long,
        endX: Float,
        endY: Float,
    ): ManualPointerTrace = ManualPointerTrace(
        startX = 100f,
        startY = 100f,
        endX = endX,
        endY = endY,
        startedAtMs = 1_000L,
        finishedAtMs = 1_000L + durationMs,
    )
}
