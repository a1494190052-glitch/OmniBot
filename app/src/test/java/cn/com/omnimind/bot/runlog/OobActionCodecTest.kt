package cn.com.omnimind.bot.runlog

import cn.com.omnimind.baselib.runlog.OobActionSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OobActionCodecTest {
    @Test
    fun `accepts only canonical action names`() {
        assertEquals(OobActionSchema.TOOL_CLICK, resolveActionName("click"))
        assertEquals(OobActionSchema.TOOL_INPUT_TEXT, resolveActionName("input_text"))
        assertEquals(OobActionSchema.TOOL_SWIPE, resolveActionName("swipe"))
        assertEquals(OobActionSchema.TOOL_PRESS_KEY, resolveActionName("press_key"))
        assertEquals(OobActionSchema.TOOL_OPEN_APP, resolveActionName("open_app"))
        assertEquals(OobActionSchema.TOOL_FINISHED, resolveActionName("finished"))

        assertEquals(null, resolveActionName("tap"))
        assertEquals(null, resolveActionName("set_text"))
        assertEquals(null, resolveActionName("launch_app"))
        assertEquals(null, resolveActionName("done"))
    }

    @Test
    fun `exposes point target action family`() {
        assertEquals(
            setOf(OobActionSchema.TOOL_CLICK, OobActionSchema.TOOL_LONG_PRESS),
            OobActionSchema.pointTargetToolNames,
        )
        assertTrue(OobActionSchema.TOOL_INPUT_TEXT !in OobActionSchema.pointTargetToolNames)
    }

    @Test
    fun `classifies runtime action families without step roles`() {
        assertTrue(isUserFacingAction(OobActionSchema.TOOL_CLICK))
        assertTrue(isUserFacingAction(OobActionSchema.TOOL_INPUT_TEXT))
        assertFalse(isUserFacingAction(OobActionSchema.TOOL_OPEN_APP))

        assertTrue(isRouteAction(OobActionSchema.TOOL_OPEN_APP))
        assertTrue(isRouteAction(OobActionSchema.TOOL_PRESS_KEY))
        assertFalse(isRouteAction("click"))
        assertTrue(isRouteAction("press_key"))
    }

    @Test
    fun `press key keeps explicit key arg`() {
        assertEquals(
            mapOf("key" to "back"),
            argsForStep(
                mapOf("tool" to "press_key", "args" to mapOf("key" to "back"))
            ),
        )
    }

    @Test
    fun `open app normalizes legacy packageName arg before canonical filtering`() {
        assertEquals(
            mapOf("package_name" to "com.example.app"),
            argsForStep(
                mapOf("tool" to "open_app", "args" to mapOf("packageName" to "com.example.app"))
            ),
        )
    }

    @Test
    fun `redacts input text in action summaries`() {
        val summary = actionArgsSummary(
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
