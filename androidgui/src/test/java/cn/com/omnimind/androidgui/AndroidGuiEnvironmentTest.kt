package cn.com.omnimind.androidgui

import cn.com.omnimind.baselib.runlog.Action
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidGuiEnvironmentTest {
    @Test
    fun accessibilityStatusDistinguishesDisabledConnectingAndReady() {
        val platform = FakePlatform()
        val environment = AndroidGuiEnvironment(null, platform)

        platform.accessibilityEnabled = false
        platform.ready = false
        assertEquals(AndroidGuiAccessibilityStatus.DISABLED, environment.accessibilityStatus())
        assertFalse(environment.isAccessibilityEnabled())
        assertFalse(environment.isReady())

        platform.accessibilityEnabled = true
        assertEquals(AndroidGuiAccessibilityStatus.CONNECTING, environment.accessibilityStatus())
        assertTrue(environment.isAccessibilityEnabled())
        assertFalse(environment.isReady())

        platform.ready = true
        assertEquals(AndroidGuiAccessibilityStatus.READY, environment.accessibilityStatus())
        assertTrue(environment.isReady())
    }

    @Test
    fun oneActionDispatchesExactlyOnce() = runBlocking {
        val platform = FakePlatform()
        val environment = AndroidGuiEnvironment(null, platform)

        val result = environment.act(Action("wait", mapOf("duration_ms" to 1)))

        assertTrue(result.success)
        assertEquals(1, platform.dispatchCount)
    }

    @Test
    fun awaitReadyWaitsForEnabledServiceConnection() = runBlocking {
        val platform = FakePlatform().apply { ready = false }
        val environment = AndroidGuiEnvironment(null, platform)

        val result = async { environment.awaitReady(timeoutMs = 500L) }
        delay(20L)
        platform.ready = true

        assertTrue(result.await())
    }

    private class FakePlatform : AndroidGuiPlatform {
        var dispatchCount = 0
        var accessibilityEnabled = true
        var ready = true

        override fun isAccessibilityEnabled(): Boolean = accessibilityEnabled

        override fun isReady(): Boolean = ready

        override fun displaySize(): Pair<Int, Int> = 1080 to 1920

        override suspend fun observe(captureScreenshot: Boolean): AndroidGuiPlatformState =
            AndroidGuiPlatformState("pkg", "activity", 1080, 1920, "<hierarchy/>")

        override suspend fun dispatch(action: Action): AndroidGuiActionResult {
            dispatchCount += 1
            return AndroidGuiActionResult(true, "ok")
        }

        override suspend fun inputTarget(x: Float?, y: Float?): AndroidGuiInputTarget? = null

        override suspend fun installedApplications(): Map<String, String> = emptyMap()

        override fun inputMethodTop(): Int? = null
    }
}
