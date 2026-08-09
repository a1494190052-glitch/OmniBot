package cn.com.omnimind.assists.task.recording

import cn.com.omnimind.assists.ManualInputTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManualTraceRecorderTargetTest {
    private val before = ManualInputTarget("old", 10f, 10f)
    private val clicked = ManualInputTarget("clicked", 20f, 20f)

    @Test
    fun `clicked target wins when focus snapshot is temporarily unavailable`() {
        assertEquals(clicked, selectManualInputTargetAfterClick(before, null, clicked))
    }

    @Test
    fun `new focused target is selected after click`() {
        val after = ManualInputTarget("new", 30f, 30f)
        assertEquals(after, selectManualInputTargetAfterClick(before, after, null))
    }

    @Test
    fun `unchanged focus is not recorded as input target`() {
        assertNull(selectManualInputTargetAfterClick(before, before, null))
    }
}
