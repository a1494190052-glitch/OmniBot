package cn.com.omnimind.accessibility.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotCaptureRetryPolicyTest {
    @Test
    fun internalErrorRetriesTwiceWithBackoff() {
        assertTrue(ScreenshotCaptureRetryPolicy.isRecoverable(1))
        assertEquals(250L, ScreenshotCaptureRetryPolicy.retryDelayMs(1, 0))
        assertEquals(500L, ScreenshotCaptureRetryPolicy.retryDelayMs(1, 1))
        assertNull(ScreenshotCaptureRetryPolicy.retryDelayMs(1, 2))
    }

    @Test
    fun throttlingErrorUsesTheSameRetryLimit() {
        assertTrue(ScreenshotCaptureRetryPolicy.isRecoverable(3))
        assertEquals(250L, ScreenshotCaptureRetryPolicy.retryDelayMs(3, 0))
        assertEquals(500L, ScreenshotCaptureRetryPolicy.retryDelayMs(3, 1))
        assertNull(ScreenshotCaptureRetryPolicy.retryDelayMs(3, 2))
    }

    @Test
    fun permanentErrorsAreNotRetried() {
        listOf(null, 2, 4, 5, 6).forEach { errorCode ->
            assertNull(ScreenshotCaptureRetryPolicy.retryDelayMs(errorCode, 0))
        }
    }
}
