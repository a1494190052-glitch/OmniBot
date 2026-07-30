package cn.com.omnimind.bot.plugin

import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.agent.tool.handlers.ToolHandler
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OmniPluginPlatformTest {

    @Test
    fun `empty platform preserves an empty plugin session`() = runBlocking {
        val platform = platform()

        assertTrue(platform.list().isEmpty())
        platform.openSession().useSuspending { session ->
            assertTrue(session.toolDefinitions.isEmpty())
            assertTrue(session.toolHandlers.isEmpty())
        }
    }

    @Test
    fun `installed plugin contributes tools only while enabled`() = runBlocking {
        val provider = RecordingProvider("com.omnimind.test", "test_action")
        val platform = platform(provider)

        val installed = platform.install(provider.descriptor.id)
        assertTrue(installed.installed)
        assertFalse(installed.enabled)
        assertEquals(1, provider.installCount)

        platform.openSession().useSuspending { session ->
            assertTrue(session.toolDefinitions.isEmpty())
        }

        val enabled = platform.setEnabled(provider.descriptor.id, true)
        assertTrue(enabled.enabled)
        assertEquals(1, provider.enableCount)
        platform.openSession().useSuspending { session ->
            assertEquals(listOf("test_action"), session.toolDefinitions.map { it.name })
            assertEquals(setOf("test_action"), session.toolHandlers.single().toolNames)
        }
        assertEquals(2, provider.handlerDisposeCount)

        val disabled = platform.setEnabled(provider.descriptor.id, false)
        assertFalse(disabled.enabled)
        assertEquals(1, provider.disableCount)
        platform.openSession().useSuspending { session ->
            assertTrue(session.toolDefinitions.isEmpty())
        }
    }

    @Test
    fun `tool conflict rejects enable without replacing active plugin`() = runBlocking {
        val first = RecordingProvider("com.omnimind.first", "shared_action")
        val second = RecordingProvider("com.omnimind.second", "shared_action")
        val platform = platform(first, second)
        platform.install(first.descriptor.id)
        platform.install(second.descriptor.id)
        platform.setEnabled(first.descriptor.id, true)

        assertFailsWithMessage("shared_action") {
            platform.setEnabled(second.descriptor.id, true)
        }

        val states = platform.list().associateBy { it.descriptor.id }
        assertTrue(states.getValue(first.descriptor.id).enabled)
        assertFalse(states.getValue(second.descriptor.id).enabled)
        platform.openSession().useSuspending { session ->
            assertEquals(listOf("shared_action"), session.toolDefinitions.map { it.name })
        }
    }

    @Test
    fun `built in tool name is reserved`() = runBlocking {
        val provider = RecordingProvider("com.omnimind.conflict", "file_read")
        val platform = platform(provider, reservedToolNames = setOf("file_read"))
        platform.install(provider.descriptor.id)

        assertFailsWithMessage("file_read") {
            platform.setEnabled(provider.descriptor.id, true)
        }

        assertFalse(platform.list().single().enabled)
    }

    @Test
    fun `unsupported interface stays visible but cannot install`() = runBlocking {
        val provider = RecordingProvider(
            pluginId = "com.omnimind.future",
            toolName = "future_action",
            interfaceVersion = OmniPluginContract.CURRENT_INTERFACE_VERSION + 1
        )
        val platform = platform(provider)

        val state = platform.list().single()
        assertFalse(state.compatible)
        assertFalse(state.installed)
        assertFailsWithMessage("interface") {
            platform.install(provider.descriptor.id)
        }
    }

    @Test
    fun `enabled persisted plugin restores without reinstall`() = runBlocking {
        val provider = RecordingProvider("com.omnimind.persisted", "persisted_action")
        val store = RecordingStore(
            listOf(OmniPluginStoredState(provider.descriptor.id, enabled = true))
        )
        val platform = platform(provider, store = store)

        platform.openSession().useSuspending { session ->
            assertEquals(listOf("persisted_action"), session.toolDefinitions.map { it.name })
        }

        assertEquals(0, provider.installCount)
        assertEquals(1, provider.enableCount)
        assertTrue(platform.list().single().enabled)
    }

    private fun platform(
        vararg providers: OmniPluginProvider,
        store: OmniPluginStateStore = RecordingStore(),
        reservedToolNames: Set<String> = emptySet()
    ): OmniPluginPlatform {
        return OmniPluginPlatform(
            providerSource = { providers.toList() },
            stateStore = store,
            reservedToolNames = reservedToolNames
        )
    }

    private suspend fun <T : AutoCloseable> T.useSuspending(block: suspend (T) -> Unit) {
        try {
            block(this)
        } finally {
            if (this is OmniPluginSession) {
                closeSuspending()
            } else {
                close()
            }
        }
    }

    private suspend fun assertFailsWithMessage(
        messageFragment: String,
        block: suspend () -> Unit
    ) {
        try {
            block()
            fail("Expected failure containing $messageFragment")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains(messageFragment, ignoreCase = true))
        }
    }

    private class RecordingStore(
        initial: List<OmniPluginStoredState> = emptyList()
    ) : OmniPluginStateStore {
        private var states = initial

        override fun read(): List<OmniPluginStoredState> = states

        override fun write(states: List<OmniPluginStoredState>) {
            this.states = states
        }
    }

    private class RecordingProvider(
        pluginId: String,
        private val toolName: String,
        interfaceVersion: Int = OmniPluginContract.CURRENT_INTERFACE_VERSION
    ) : OmniPluginProvider {
        var installCount = 0
        var enableCount = 0
        var disableCount = 0
        var handlerDisposeCount = 0

        override val descriptor = OmniPluginDescriptor(
            id = pluginId,
            name = pluginId.substringAfterLast('.'),
            version = "1.0.0",
            interfaceVersion = interfaceVersion,
            description = "test plugin",
            publisher = "OmniMind"
        )

        override suspend fun install() {
            installCount += 1
        }

        override fun create(): OmniPlugin {
            return object : OmniPlugin {
                override suspend fun onEnable() {
                    enableCount += 1
                }

                override suspend fun onDisable() {
                    disableCount += 1
                }

                override fun contribution(): OmniPluginContribution {
                    return OmniPluginContribution(
                        toolGroups = listOf(
                            OmniPluginToolGroup(
                                definitions = listOf(
                                    OmniPluginToolDefinition(
                                        name = toolName,
                                        displayName = toolName,
                                        description = "test tool",
                                        parameters = buildJsonObject {
                                            put("type", "object")
                                            put("properties", JsonObject(emptyMap()))
                                        }
                                    )
                                ),
                                handlerFactory = {
                                    RecordingHandler(setOf(toolName)) {
                                        handlerDisposeCount += 1
                                    }
                                }
                            )
                        )
                    )
                }
            }
        }
    }

    private class RecordingHandler(
        override val toolNames: Set<String>,
        private val onDispose: () -> Unit
    ) : ToolHandler {
        override suspend fun execute(
            toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
            args: JsonObject,
            runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
            env: AgentExecutionEnvironment,
            callback: AgentCallback,
            toolHandle: AgentToolExecutionHandle
        ): ToolExecutionResult {
            error("not used")
        }

        override suspend fun dispose() {
            onDispose()
        }
    }
}
