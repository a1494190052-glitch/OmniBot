package cn.com.omnimind.bot.runlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OobActionCodecTest {
    @Test
    fun `accepts only canonical action names`() {
        assertEquals(OobActionCodec.ACTION_CLICK, OobActionCodec.canonicalActionForName("click"))
        assertEquals(OobActionCodec.ACTION_INPUT_TEXT, OobActionCodec.canonicalActionForName("input_text"))
        assertEquals(OobActionCodec.ACTION_SCROLL, OobActionCodec.canonicalActionForName("scroll"))
        assertEquals(OobActionCodec.ACTION_PRESS_BACK, OobActionCodec.canonicalActionForName("press_back"))
        assertEquals(OobActionCodec.ACTION_OPEN_APP, OobActionCodec.canonicalActionForName("open_app"))
        assertEquals(OobActionCodec.ACTION_FINISHED, OobActionCodec.canonicalActionForName("finished"))

        assertEquals(null, OobActionCodec.canonicalActionForName("tap"))
        assertEquals(null, OobActionCodec.canonicalActionForName("set_text"))
        assertEquals(null, OobActionCodec.canonicalActionForName("scroll_down"))
        assertEquals(null, OobActionCodec.canonicalActionForName("press_key"))
        assertEquals(null, OobActionCodec.canonicalActionForName("launch_app"))
        assertEquals(null, OobActionCodec.canonicalActionForName("done"))
    }

    @Test
    fun `exposes point target action family`() {
        assertEquals(
            setOf(OobActionCodec.ACTION_CLICK, OobActionCodec.ACTION_LONG_PRESS),
            OobActionCodec.pointTargetActions,
        )
        assertTrue(OobActionCodec.ACTION_INPUT_TEXT !in OobActionCodec.pointTargetActions)
    }

    @Test
    fun `classifies runtime action families without step roles`() {
        assertTrue(OobActionCodec.isUserFacingAction(OobActionCodec.ACTION_CLICK))
        assertTrue(OobActionCodec.isUserFacingAction(OobActionCodec.ACTION_INPUT_TEXT))
        assertFalse(OobActionCodec.isUserFacingAction(OobActionCodec.ACTION_OPEN_APP))

        assertTrue(OobActionCodec.isRouteAction(OobActionCodec.ACTION_OPEN_APP))
        assertTrue(OobActionCodec.isRouteAction(OobActionCodec.ACTION_PRESS_BACK))
        assertFalse(OobActionCodec.isRouteAction("click"))
        assertFalse(OobActionCodec.isRouteAction("press_key"))
    }

    @Test
    fun `back and home do not synthesize legacy key args`() {
        assertFalse(
            OobActionCodec.argsForStep(
                mapOf("tool" to "press_back", "args" to emptyMap<String, Any?>())
            ).containsKey("key"),
        )
        assertFalse(
            OobActionCodec.argsForStep(
                mapOf("tool" to "press_home", "args" to emptyMap<String, Any?>())
            ).containsKey("key"),
        )
    }

    @Test
    fun `redacts input text in action summaries`() {
        val summary = OobActionCodec.actionArgsSummary(
            actionType = "input_text",
            args = mapOf("text" to "secret value", "target_description" to "search box"),
            sourceAction = emptyMap(),
        )

        assertEquals("search box", summary["target_description"])
        assertEquals(true, summary["text_present"])
        assertEquals(12, summary["text_length"])
        assertTrue(!summary.containsKey("text"))
    }
}
