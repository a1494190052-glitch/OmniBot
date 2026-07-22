package cn.com.omnimind.assists.task.vlmserver

import org.junit.Assert.assertEquals
import org.junit.Test

class VLMPreActionPageGuardTest {
    @Test
    fun `changed page requires replanning before dispatch`() {
        assertEquals(
            PreActionPageStatus.CHANGED,
            preActionPageStatus(
                requiresPreciseLocation = true,
                latestXml = "<hierarchy />",
                pageStable = false,
            ),
        )
    }

    @Test
    fun `non coordinate action does not require page stability`() {
        assertEquals(
            PreActionPageStatus.READY,
            preActionPageStatus(
                requiresPreciseLocation = false,
                latestXml = null,
                pageStable = false,
            ),
        )
    }
}
