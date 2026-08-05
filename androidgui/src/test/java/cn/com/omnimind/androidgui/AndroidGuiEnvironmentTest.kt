package cn.com.omnimind.androidgui

import cn.com.omnimind.baselib.runlog.Action
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidGuiEnvironmentTest {
    @Test
    fun `screen capture waits for transient accessibility reconnect`() = runBlocking {
        val platform = ReconnectingPlatform()
        val environment = AndroidGuiEnvironment(appContext = null, platform = platform)
        launch {
            delay(100L)
            platform.ready = true
        }

        val snapshot = environment.captureScreenSnapshot()

        assertEquals("com.android.settings", snapshot.packageName)
        assertEquals(1, platform.observeCalls)
    }

    @Test
    fun `action waits for transient accessibility reconnect instead of restarting task`() = runBlocking {
        val platform = ReconnectingPlatform()
        val environment = AndroidGuiEnvironment(appContext = null, platform = platform)
        launch {
            delay(100L)
            platform.ready = true
        }

        val result = environment.act(Action(tool = "wait", args = mapOf("duration_ms" to 0)))

        assertTrue(result.success)
        assertEquals(1, platform.dispatchCalls)
        assertEquals(0, platform.observeCalls)
        assertEquals("runtime_delegated", result.diagnostics["state_stabilization"])
    }

    private class ReconnectingPlatform : AndroidGuiPlatform {
        @Volatile
        var ready: Boolean = false
        var observeCalls: Int = 0
        var dispatchCalls: Int = 0

        override fun isAccessibilityEnabled(): Boolean = true

        override fun isReady(): Boolean = ready

        override fun displaySize(): Pair<Int, Int> = 1080 to 2400

        override fun screenshotExcludesOverlays(): Boolean = true

        override suspend fun observe(captureScreenshot: Boolean): AndroidGuiPlatformState {
            check(ready) { "android_gui_accessibility_not_ready" }
            observeCalls += 1
            return AndroidGuiPlatformState(
                packageName = "com.android.settings",
                activityName = "Settings",
                displayWidth = 1080,
                displayHeight = 2400,
                xml = "<hierarchy />",
            )
        }

        override suspend fun dispatch(action: Action): AndroidGuiActionResult {
            check(ready) { "android_gui_accessibility_not_ready" }
            dispatchCalls += 1
            return AndroidGuiActionResult(success = true, message = "ok")
        }

        override suspend fun inputTarget(
            x: Float?,
            y: Float?,
        ): AndroidGuiInputTarget? = null

        override suspend fun installedApplications(): Map<String, String> = emptyMap()

        override fun inputMethodTop(): Int? = null
    }
}
