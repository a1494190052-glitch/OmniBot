package cn.com.omnimind.bot.omniflow

import cn.com.omnimind.baselib.runlog.actionOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VlmCoordinatesTest {

    @Test
    fun `raw values below one thousand are still converted unconditionally`() {
        val converted = VlmCoordinates.toCanonicalArgs(
            toolName = "click",
            rawArgs = mapOf("x" to 500, "y" to 750),
            display = VlmCoordinates.DisplaySize(width = 2000, height = 3000),
        )

        assertEquals(250L, converted["x"])
        assertEquals(250L, converted["y"])
    }

    @Test
    fun `recent canonical actions are exposed to VLM as raw pixels`() {
        val raw = VlmCoordinates.toRawArgs(
            action = actionOf("swipe", mapOf(
                "direction" to "up",
                "x1" to 500,
                "y1" to 800,
                "x2" to 500,
                "y2" to 200,
            )),
            display = VlmCoordinates.DisplaySize(width = 1440, height = 3200),
        )

        assertEquals(720L, raw["x1"])
        assertEquals(2560L, raw["y1"])
        assertEquals(640L, raw["y2"])
    }

    @Test
    fun `raw coordinates outside the current display fail`() {
        try {
            VlmCoordinates.toCanonicalArgs(
                toolName = "click",
                rawArgs = mapOf("x" to 1080, "y" to 100),
                display = VlmCoordinates.DisplaySize(width = 1080, height = 2400),
            )
            fail("Expected raw coordinate range failure")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("out_of_range"))
        }
    }
}
