package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.assists.ManualInputTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManualInputTargetSelectionTest {
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

    private fun target(description: String, x: Float, y: Float) = ManualInputTarget(
        description = description,
        x = x,
        y = y,
    )
}
