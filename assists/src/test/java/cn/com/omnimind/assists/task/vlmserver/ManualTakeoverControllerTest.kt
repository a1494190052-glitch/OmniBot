package cn.com.omnimind.assists.task.vlmserver

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualTakeoverControllerTest {
    @Test
    fun continueResumesThePausedVlmTask() = runBlocking {
        val controller = ManualTakeoverController()

        controller.request()

        assertTrue(controller.resume())
        assertEquals(ManualTakeoverResolution.Continue, controller.awaitResolution())
        assertFalse(controller.isActive)
    }

    @Test
    fun completeEndsTheTakeoverAsSuccess() = runBlocking {
        val controller = ManualTakeoverController()

        controller.request()

        assertTrue(controller.complete("任务已完成"))
        assertEquals(
            ManualTakeoverResolution.Complete("任务已完成"),
            controller.awaitResolution(),
        )
        assertFalse(controller.isActive)
    }

    @Test
    fun completionIsRejectedOutsideManualTakeover() {
        val controller = ManualTakeoverController()

        assertFalse(controller.complete("任务已完成"))
    }

    @Test
    fun cancelRemainsDistinctFromSuccessfulCompletion() = runBlocking {
        val controller = ManualTakeoverController()

        controller.request()
        controller.cancel()

        assertEquals(ManualTakeoverResolution.Cancel, controller.awaitResolution())
        assertFalse(controller.isActive)
    }
}
