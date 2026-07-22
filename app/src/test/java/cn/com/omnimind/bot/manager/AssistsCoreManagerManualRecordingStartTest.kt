package cn.com.omnimind.bot.manager

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AssistsCoreManagerManualRecordingStartTest {
    @Test
    fun `manual recording starts idle until the user presses start`() {
        val methodSource = managerSource()
            .substringAfter("fun startHumanTrajectoryLearning(")
            .substringBefore("fun pauseHumanTrajectoryLearning(")

        val pauseIndex = methodSource.indexOf("HumanTrajectoryLearningSession.pauseActive()")
        val overlayIndex = methodSource.indexOf("ManualRecordingControlOverlay.show(")

        assertTrue("The recorder must be paused before showing its controls", pauseIndex >= 0)
        assertTrue("The recorder must be paused before showing its controls", pauseIndex < overlayIndex)
        assertTrue(
            "The controls must initially show the ready state",
            methodSource.contains("state = ManualRecordingControlOverlay.State.READY")
        )
        assertFalse(
            "Starting the flow must not immediately activate recording",
            methodSource.contains("ManualRecordingControlOverlay.markRecording()")
        )
    }

    @Test
    fun `manual recording overlay keeps the new session owner`() {
        val showSource = projectSource(
            "uikit/src/main/java/cn/com/omnimind/uikit/loader/ManualRecordingControlOverlay.kt",
        )
            .substringAfter("fun show(")
            .substringBefore("fun markRecording(")
        val dismissIndex = showSource.indexOf("dismissLocked()")
        val bindIndex = showSource.lastIndexOf("sessionRunId = runId")

        assertTrue("A stale overlay must be dismissed before binding the new run", dismissIndex >= 0)
        assertTrue(
            "Dismissing the stale overlay must not clear the new run owner",
            dismissIndex < bindIndex,
        )
    }

    private fun managerSource(): String {
        return projectSource(
            "app/src/main/java/cn/com/omnimind/bot/manager/AssistsCoreManager.kt",
        )
    }

    private fun projectSource(path: String): String {
        var current = File(System.getProperty("user.dir")).absoluteFile
        while (!current.resolve("settings.gradle.kts").isFile) {
            current = current.parentFile ?: error("Could not locate project root")
        }
        return current.resolve(path).readText()
    }
}
