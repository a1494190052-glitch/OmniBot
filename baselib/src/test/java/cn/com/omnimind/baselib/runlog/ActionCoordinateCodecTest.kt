package cn.com.omnimind.baselib.runlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ActionCoordinateCodecTest {
    @Test
    fun `converts screen pixels to canonical coordinates`() {
        assertEquals(
            mapOf("x" to 500L, "y" to 750L),
            ActionCoordinateCodec.toRelative(
                args = mapOf("x" to 540, "y" to 1800),
                displaySize = ActionCoordinateCodec.DisplaySize(1080.0, 2400.0),
            ),
        )
    }

    @Test
    fun `converts canonical coordinates to screen pixels`() {
        val converted = ActionCoordinateCodec.toScreenPixels(
            args = mapOf("x" to 500, "y" to 650),
            displaySize = ActionCoordinateCodec.DisplaySize(1440.0, 3168.0),
        )

        assertEquals(720L, converted["x"])
        assertEquals(2059.2, converted["y"] as Double, 0.0001)
    }

    @Test
    fun `rejects out of range canonical coordinates`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ActionCoordinateCodec.toScreenPixels(
                args = mapOf("x" to 1001, "y" to 500),
                displaySize = ActionCoordinateCodec.DisplaySize(1080.0, 2400.0),
            )
        }

        assertEquals("canonical_action_arg_range_invalid:x", error.message)
    }
}
