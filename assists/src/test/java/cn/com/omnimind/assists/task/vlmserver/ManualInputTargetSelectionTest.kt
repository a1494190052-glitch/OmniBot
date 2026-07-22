package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.assists.ManualInputTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ManualInputTargetSelectionTest {
    @Test
    fun manualScreenClickBecomesCanonicalAtCaptureBoundary() {
        val action = canonicalManualScreenAction(
            tool = "click",
            args = mapOf(
                "target_description" to "屏幕坐标",
                "x" to 972,
                "y" to 144,
            ),
            displayWidth = 1080,
            displayHeight = 1920,
        )

        assertEquals(900.0, (action.argsMap().getValue("x") as Number).toDouble(), 0.001)
        assertEquals(75.0, (action.argsMap().getValue("y") as Number).toDouble(), 0.001)
        assertFalse(action.argsMap().containsKey("display_width"))
        assertFalse(action.argsMap().containsKey("display_height"))
    }

    @Test
    fun selectsNewFocusedTargetAfterNavigation() {
        val target = target("search", 400f, 100f)

        assertEquals(target, selectManualInputTargetAfterClick(null, target, null))
    }

    @Test
    fun selectsExistingFocusedTargetWhenItWasClicked() {
        val target = target("message", 360f, 900f)

        assertEquals(target, selectManualInputTargetAfterClick(target, target, target))
    }

    @Test
    fun ignoresUnchangedFocusedTargetWhenAnotherControlWasClicked() {
        val target = target("message", 360f, 900f)

        assertNull(selectManualInputTargetAfterClick(target, target, null))
    }

    @Test
    fun manualInputTextCarriesTheSelectedTargetToExecution() {
        val target = ManualInputTarget(
            description = "message",
            x = 360f,
            y = 900f,
            nodeResourceId = "message_input",
        )

        assertEquals(
            mapOf(
                "target_description" to "message",
                "text" to "hello",
                "x" to 360f,
                "y" to 900f,
                "node_resource_id" to "message_input",
            ),
            manualInputTextActionArgs("hello", target),
        )
    }

    private fun target(description: String, x: Float, y: Float) = ManualInputTarget(
        description = description,
        x = x,
        y = y,
    )
}
