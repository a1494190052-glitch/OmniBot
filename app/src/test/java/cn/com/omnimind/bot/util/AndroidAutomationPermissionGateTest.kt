package cn.com.omnimind.bot.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAutomationPermissionGateTest {
    @Test
    fun `reports one canonical result when both automation permissions are missing`() {
        val result = AndroidAutomationPermissionGate.evaluate(
            accessibilityEnabled = false,
            overlayEnabled = false,
        )

        assertFalse(result.granted)
        assertEquals(listOf("accessibility", "overlay"), result.missingIds)
        assertEquals(listOf("无障碍权限", "悬浮窗权限"), result.displayNames)
        assertEquals("OOB_ACCESSIBILITY_REQUIRED", result.errorCode)
        assertEquals(
            "请先开启无障碍权限和悬浮窗权限，视觉执行才能点击、滑动、输入并显示执行状态。",
            result.message,
        )
    }

    @Test
    fun `reports accessibility when only accessibility permission is missing`() {
        val result = AndroidAutomationPermissionGate.evaluate(
            accessibilityEnabled = false,
            overlayEnabled = true,
        )

        assertFalse(result.granted)
        assertEquals(listOf("accessibility"), result.missingIds)
        assertEquals(listOf("无障碍权限"), result.displayNames)
        assertEquals("OOB_ACCESSIBILITY_REQUIRED", result.errorCode)
        assertEquals(
            "请先开启无障碍权限，视觉执行才能点击、滑动和输入。",
            result.message,
        )
    }

    @Test
    fun `reports overlay when only overlay permission is missing`() {
        val result = AndroidAutomationPermissionGate.evaluate(
            accessibilityEnabled = true,
            overlayEnabled = false,
        )

        assertFalse(result.granted)
        assertEquals(listOf("overlay"), result.missingIds)
        assertEquals(listOf("悬浮窗权限"), result.displayNames)
        assertEquals("OOB_PERMISSION_REQUIRED", result.errorCode)
        assertEquals(
            "请先开启悬浮窗权限，视觉执行才能显示执行状态。",
            result.message,
        )
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
