package cn.com.omnimind.bot.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAutomationPermissionGateTest {
    @Test
    fun `reports both permissions when automation is unavailable`() {
        val result = AndroidAutomationPermissionGate.evaluate(
            accessibilityEnabled = false,
            overlayEnabled = false,
        )

        assertFalse(result.granted)
        assertEquals(listOf("accessibility", "overlay"), result.missingIds)
        assertEquals(listOf("无障碍权限", "悬浮窗权限"), result.displayNames)
        assertEquals("OOB_ACCESSIBILITY_REQUIRED", result.errorCode)
        assertEquals(
            "请先开启无障碍权限和悬浮窗权限，视觉执行才能操作界面并显示任务控制条。",
            result.message,
        )
    }

    @Test
    fun `reports accessibility when only accessibility is unavailable`() {
        val result = AndroidAutomationPermissionGate.evaluate(
            accessibilityEnabled = false,
            overlayEnabled = true,
        )

        assertFalse(result.granted)
        assertEquals(listOf("accessibility"), result.missingIds)
        assertEquals(listOf("无障碍权限"), result.displayNames)
        assertEquals("OOB_ACCESSIBILITY_REQUIRED", result.errorCode)
    }

    @Test
    fun `reports overlay when only overlay is unavailable`() {
        val result = AndroidAutomationPermissionGate.evaluate(
            accessibilityEnabled = true,
            overlayEnabled = false,
        )

        assertFalse(result.granted)
        assertEquals(listOf("overlay"), result.missingIds)
        assertEquals(listOf("悬浮窗权限"), result.displayNames)
        assertEquals("OOB_PERMISSION_REQUIRED", result.errorCode)
    }

    @Test
    fun `grants automation when both permissions are available`() {
        val result = AndroidAutomationPermissionGate.evaluate(
            accessibilityEnabled = true,
            overlayEnabled = true,
        )

        assertTrue(result.granted)
        assertTrue(result.missingIds.isEmpty())
        assertTrue(result.displayNames.isEmpty())
        assertEquals("", result.message)
        result.requireGranted()
    }
}
