package cn.com.omnimind.bot.omniflow

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OmniVlmPluginTest {

    @Test
    fun `built in VLM lifecycle needs no downloaded runtime`() = runBlocking {
        val backend = RecordingBackend()
        val plugin = OmniVlmPlugin(backend)

        assertFailsWithMessage("not_installed") {
            plugin.setEnabled(true)
        }

        plugin.install(enabled = false)
        assertFalse(plugin.isEnabled())

        plugin.setEnabled(true)
        assertTrue(plugin.isEnabled())
        val result = plugin.execute(
            context = TestContext,
            request = OmniVlmPlugin.Request(goal = " open settings ", runId = " run-1 "),
            modelClient = UnusedModelClient,
        )

        assertEquals("open settings", backend.request?.goal)
        assertEquals("run-1", backend.request?.runId)
        assertEquals(true, result.payload["success"])

        plugin.setEnabled(false)
        assertFalse(plugin.isEnabled())
        assertEquals(1, backend.shutdownCount)
    }

    private suspend fun assertFailsWithMessage(
        messageFragment: String,
        block: suspend () -> Unit,
    ) {
        try {
            block()
            fail("Expected failure containing $messageFragment")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains(messageFragment, ignoreCase = true))
        }
    }

    private class RecordingBackend : OmniVlmBackend {
        var shutdownCount = 0
        var request: OmniVlmPlugin.Request? = null

        override suspend fun shutdown() {
            shutdownCount += 1
        }

        override suspend fun execute(
            context: Context,
            request: OmniVlmPlugin.Request,
            modelClient: OmniFlowModelClient,
            hooks: OmniVlmPlugin.Hooks,
        ): OmniVlmPlugin.Result {
            this.request = request
            return OmniVlmPlugin.Result(mapOf("success" to true), null)
        }

        override fun stop(runId: String): Boolean = false
    }

    private object UnusedModelClient : OmniFlowModelClient {
        override suspend fun streamTurn(
            request: cn.com.omnimind.baselib.llm.ChatCompletionRequest,
            onReasoningUpdate: (suspend (String) -> Unit)?,
        ): cn.com.omnimind.baselib.llm.ChatCompletionTurn = error("not used")
    }

    private object TestContext : android.content.ContextWrapper(null)
}
