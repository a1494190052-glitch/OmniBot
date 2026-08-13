package cn.com.omnimind.bot.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class AndroidDeviceMcpServerTest {
    @Test
    fun `public MCP surface contains only user-level OmniFlow tools`() {
        assertEquals(
            linkedSetOf(
                "run_gui",
                "run_function",
                "list_functions",
                "register_function",
            ),
            AndroidDeviceMcpServer.publicToolNames,
        )
        assertFalse(AndroidDeviceMcpServer.publicToolNames.any { it.startsWith("device_") })
    }

    @Test
    fun `tool call waits for plugin restoration before rejecting runtime`() = runBlocking {
        var enabled = false
        var initializationCount = 0

        AndroidDeviceMcpServer.ensureRuntimeEnabled(
            isEnabled = { enabled },
            initialize = {
                initializationCount += 1
                enabled = true
            },
        )

        assertTrue(enabled)
        assertEquals(1, initializationCount)
    }

    @Test
    fun `ready runtime skips plugin restoration`() = runBlocking {
        var initializationCount = 0

        AndroidDeviceMcpServer.ensureRuntimeEnabled(
            isEnabled = { true },
            initialize = { initializationCount += 1 },
        )

        assertEquals(0, initializationCount)
    }
}
