package cn.com.omnimind.baselib.runlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CanonicalActionConverterTest {
    @Test
    fun `unknown action arguments are rejected instead of ignored`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            CanonicalActionConverter.convert(
                tool = "click",
                args = mapOf(
                    "x" to 500,
                    "y" to 500,
                    "params" to mapOf("x" to 1),
                ),
            )
        }

        assertEquals("canonical_action_unknown_args:click:params", error.message)
    }

    @Test
    fun `keeps only persisted args from the action schema`() {
        val action = CanonicalActionConverter.convert(
            tool = "click",
            args = linkedMapOf(
                "target_description" to "Compose",
                "node_id" to "42",
                "node_resource_id" to "compose_button",
                "x" to 500,
                "y" to 250,
            ),
        )

        assertEquals(
            mapOf("tool" to "click", "args" to mapOf("x" to 500L, "y" to 250L)),
            action,
        )
    }

    @Test
    fun `normalizes executed screen coordinates once`() {
        val action = CanonicalActionConverter.convert(
            tool = "swipe",
            args = mapOf(
                "direction" to "up",
                "x1" to 540,
                "y1" to 1800,
                "x2" to 540,
                "y2" to 600,
                "duration_ms" to 300,
            ),
            coordinateSpace = CanonicalActionConverter.CoordinateSpace.SCREEN_ABSOLUTE_PX,
            displaySize = CanonicalActionConverter.DisplaySize(1080.0, 2400.0),
        )

        assertEquals(
            mapOf(
                "tool" to "swipe",
                "args" to mapOf(
                    "direction" to "up",
                    "x1" to 500L,
                    "y1" to 750L,
                    "x2" to 500L,
                    "y2" to 250L,
                    "duration_ms" to 300L,
                ),
            ),
            action,
        )
    }

    @Test
    fun `decodes canonical coordinates to screen pixels once`() {
        val args = CanonicalActionConverter.toScreenPixels(
            tool = "click",
            args = mapOf("x" to 500, "y" to 650),
            displaySize = CanonicalActionConverter.DisplaySize(1440.0, 3168.0),
        )

        assertEquals(mapOf("x" to 720L, "y" to 2059.2), args)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `screen coordinate conversion requires an explicit display`() {
        CanonicalActionConverter.convert(
            tool = "click",
            args = mapOf(
                "x" to 540,
                "y" to 1200,
            ),
            coordinateSpace = CanonicalActionConverter.CoordinateSpace.SCREEN_ABSOLUTE_PX,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects unknown tools`() {
        CanonicalActionConverter.convert("teleport", emptyMap())
    }
}
