package cn.com.omnimind.bot.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentWorkspaceContextPathTest {
    @Test
    fun contextWorkspacePathUsesSanitizedRuntimeSegment() {
        assertEquals(
            ".omnibot/contexts/run_1-new",
            workspaceContextRelativePath(" run/1-new ")
        )
        assertEquals(
            ".omnibot/contexts/isolated",
            workspaceContextRelativePath("..")
        )
    }
}
