package cn.com.omnimind.bot.omniflow

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniFlowToolChannelManualRecordingTest {
    @Test
    fun manualRecordingWaitsInReadyStateBeforeCapturingTouches() {
        val source = projectSource(
            "app/src/main/java/cn/com/omnimind/bot/omniflow/OmniFlowToolChannel.kt",
        )
        val method = source
            .substringAfter("private fun startHumanTrajectoryLearning(")
            .substringBefore("private fun humanTrajectoryStatusPayload(")
        val pauseIndex = method.indexOf("HumanTrajectoryLearningSession.pauseActive()")
        val overlayIndex = method.indexOf("ManualRecordingControlOverlay.show(")

        assertTrue(pauseIndex >= 0)
        assertTrue(pauseIndex < overlayIndex)
        assertTrue(method.contains("state = ManualRecordingControlOverlay.State.READY"))
    }

    private fun projectSource(path: String): String {
        var current = File(System.getProperty("user.dir")).absoluteFile
        while (!current.resolve("settings.gradle.kts").isFile) {
            current = current.parentFile ?: error("Could not locate project root")
        }
        return current.resolve(path).readText()
    }
}
