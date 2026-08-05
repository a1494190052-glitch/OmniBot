package cn.com.omnimind.bot.omniflow

import cn.com.omnimind.baselib.runlog.State
import java.nio.file.Files
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OmniFlowStateHostTest {
    @Test
    fun `host observation includes screenshot bytes only when requested`() {
        val screenshot = Files.createTempFile("omniflow-state", ".png").toFile()
        screenshot.writeBytes(byteArrayOf(1, 2, 3, 4))
        val state = State.create(
            packageName = "com.example",
            activityName = "ExampleActivity",
            displayWidth = 1080,
            displayHeight = 2400,
            xml = "<hierarchy />",
            screenshotPath = screenshot.absolutePath,
        )

        assertEquals(
            Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4)),
            state.asHostMap(includeImage = true)["image_base64"],
        )
        assertFalse(state.asHostMap(includeImage = false).containsKey("image_base64"))
        screenshot.delete()
    }
}
