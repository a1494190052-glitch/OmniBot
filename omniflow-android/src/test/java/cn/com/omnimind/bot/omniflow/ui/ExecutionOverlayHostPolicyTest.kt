package cn.com.omnimind.bot.omniflow.ui

import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExecutionOverlayHostPolicyTest {
    @Test
    fun `accessibility overlay is preferred on protected pages`() {
        val host = ExecutionOverlayHostPolicy.resolve(
            accessibilityServiceAvailable = true,
            applicationOverlayAllowed = true,
        )

        assertEquals(
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            host?.windowType,
        )
    }

    @Test
    fun `application overlay remains available as fallback`() {
        val host = ExecutionOverlayHostPolicy.resolve(
            accessibilityServiceAvailable = false,
            applicationOverlayAllowed = true,
        )

        assertEquals(
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            host?.windowType,
        )
    }

    @Test
    fun `overlay is unavailable without either host`() {
        assertNull(
            ExecutionOverlayHostPolicy.resolve(
                accessibilityServiceAvailable = false,
                applicationOverlayAllowed = false,
            ),
        )
    }
}
