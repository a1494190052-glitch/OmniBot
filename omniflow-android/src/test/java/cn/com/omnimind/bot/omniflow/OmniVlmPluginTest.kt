package cn.com.omnimind.bot.omniflow

import android.content.Context
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OmniVlmPluginTest {

    @Test
    fun `installed VLM routes online execution through run gui`() = runBlocking {
        val backend = RecordingBackend()
        val plugin = OmniVlmPlugin(backend)

        assertFailsWithMessage("not_installed") {
            plugin.setEnabled(true)
        }

        plugin.install(TestPlatform, enabled = false)
        assertFalse(plugin.isEnabled())

        plugin.setEnabled(true)
        assertTrue(plugin.isEnabled())
        val result = plugin.execute(
            context = TestContext,
            request = OmniVlmPlugin.Request(goal = " open settings ", runId = " run-1 "),
            modelClient = UnusedModelClient,
        )

        assertEquals("run_gui", backend.toolName)
        assertEquals("open settings", backend.goal)
        assertEquals("run-1", backend.runId)
        assertEquals(OmniVlmPlugin.MODEL_SCENE, backend.arguments["model"])
        assertEquals(true, result.payload["success"])

        plugin.uninstall()
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
        var toolName = ""
        var arguments: Map<String, Any?> = emptyMap()
        var goal = ""
        var runId = ""

        override fun configure(
            platform: OmniFlowPlatform,
            runtimeProvider: OmniFlowRuntimeProvider,
        ) = Unit

        override fun warmup(context: Context) = Unit

        override suspend fun shutdown() {
            shutdownCount += 1
        }

        override suspend fun execute(
            context: Context,
            toolName: String,
            arguments: Map<String, Any?>,
            goal: String,
            runId: String,
            modelClient: OmniFlowModelClient,
            hooks: OmniFlow.Hooks,
        ): OmniFlow.Result {
            this.toolName = toolName
            this.arguments = arguments
            this.goal = goal
            this.runId = runId
            return OmniFlow.Result(mapOf("success" to true), null)
        }

        override fun stop(runId: String): Boolean = false
    }

    private object UnusedModelClient : OmniFlowModelClient {
        override suspend fun streamTurn(
            request: ChatCompletionRequest,
            onReasoningUpdate: (suspend (String) -> Unit)?,
        ): ChatCompletionTurn = error("not used")
    }

    private object TestPlatform : OmniFlowPlatform {
        override suspend fun startProcess(
            context: Context,
            command: String,
            environment: Map<String, String>,
        ): Process = error("not used")

        override suspend fun ensurePython(context: Context, expectedVersion: String) = Unit

        override suspend fun resolveRuntimeSkill(
            context: Context,
            refresh: Boolean,
        ): OmniFlowSkillLocation = error("not used")

        override suspend fun bootstrapRuntimeSkill(
            context: Context,
            location: OmniFlowSkillLocation,
        ) = Unit

        override suspend fun reclaimRuntimeSkill(context: Context) = Unit

        override suspend fun completeJson(request: ChatCompletionRequest): String = error("not used")
    }

    private object TestContext : android.content.ContextWrapper(null)
}
