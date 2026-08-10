package cn.com.omnimind.bot.plugin

import cn.com.omnimind.bot.agent.tool.handlers.ToolHandler
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class OmniPluginPlatform(
    private val providerSource: () -> List<OmniPluginProvider>,
    private val stateStore: OmniPluginStateStore,
    private val reservedToolNames: Set<String>
) {
    private data class ActivePlugin(
        val plugin: OmniPlugin,
        val contribution: OmniPluginContribution
    )

    private val mutex = Mutex()
    private val storedStates = linkedMapOf<String, OmniPluginStoredState>()
    private val activePlugins = linkedMapOf<String, ActivePlugin>()
    private val errors = linkedMapOf<String, String>()
    private var initialized = false

    suspend fun list(): List<OmniPluginState> = mutex.withLock {
        ensureInitialized()
        providers().map(::stateFor)
    }

    suspend fun install(pluginId: String): OmniPluginState = mutex.withLock {
        ensureInitialized()
        val provider = requireProvider(pluginId)
        requireCompatible(provider.descriptor)
        storedStates[pluginId]?.let { return@withLock stateFor(provider) }

        provider.install()
        val nextState = OmniPluginStoredState(pluginId = pluginId, enabled = false)
        try {
            persist(storedStates.values + nextState)
        } catch (error: Throwable) {
            runCatching { provider.uninstall() }
            throw error
        }
        storedStates[pluginId] = nextState
        errors.remove(pluginId)
        stateFor(provider)
    }

    suspend fun update(pluginId: String): OmniPluginState = mutex.withLock {
        ensureInitialized()
        val provider = requireProvider(pluginId)
        requireCompatible(provider.descriptor)
        val current = storedStates[pluginId]
            ?: throw IllegalArgumentException("Plugin $pluginId is not installed")
        val active = activePlugins.remove(pluginId)
        if (current.enabled) active?.plugin?.onDisable()
        try {
            provider.update()
            if (current.enabled) activePlugins[pluginId] = activate(provider)
            errors.remove(pluginId)
        } catch (error: Throwable) {
            if (current.enabled) {
                runCatching { active?.plugin?.onEnable() }
                if (active != null) activePlugins[pluginId] = active
            }
            throw error
        }
        stateFor(provider)
    }

    suspend fun setEnabled(pluginId: String, enabled: Boolean): OmniPluginState = mutex.withLock {
        ensureInitialized()
        val provider = requireProvider(pluginId)
        requireCompatible(provider.descriptor)
        val current = storedStates[pluginId]
            ?: throw IllegalArgumentException("Plugin $pluginId is not installed")
        if (current.enabled == enabled) return@withLock stateFor(provider)

        if (enabled) {
            val active = activate(provider)
            val nextState = current.copy(enabled = true)
            try {
                persist(storedStates.values.map { if (it.pluginId == pluginId) nextState else it })
            } catch (error: Throwable) {
                runCatching { active.plugin.onDisable() }
                throw error
            }
            activePlugins[pluginId] = active
            storedStates[pluginId] = nextState
            errors.remove(pluginId)
        } else {
            val active = activePlugins[pluginId]
            active?.plugin?.onDisable()
            val nextState = current.copy(enabled = false)
            try {
                persist(storedStates.values.map { if (it.pluginId == pluginId) nextState else it })
            } catch (error: Throwable) {
                runCatching { active?.plugin?.onEnable() }
                throw error
            }
            activePlugins.remove(pluginId)
            storedStates[pluginId] = nextState
            errors.remove(pluginId)
        }
        stateFor(provider)
    }

    suspend fun uninstall(pluginId: String) = mutex.withLock {
        ensureInitialized()
        val provider = requireProvider(pluginId)
        val current = storedStates[pluginId] ?: return@withLock
        val active = activePlugins[pluginId]
        active?.plugin?.onDisable()
        val nextStates = storedStates.values.filterNot { it.pluginId == pluginId }
        try {
            persist(nextStates)
        } catch (error: Throwable) {
            if (current.enabled) runCatching { active?.plugin?.onEnable() }
            throw error
        }
        activePlugins.remove(pluginId)
        storedStates.remove(pluginId)
        errors.remove(pluginId)
        provider.uninstall()
    }

    suspend fun openSession(): OmniPluginSession = mutex.withLock {
        ensureInitialized()
        val definitions = mutableListOf<OmniPluginToolDefinition>()
        val handlers = mutableListOf<ToolHandler>()
        val failedPluginIds = mutableListOf<String>()

        activePlugins.forEach { (pluginId, active) ->
            val pluginHandlers = mutableListOf<ToolHandler>()
            try {
                active.contribution.toolGroups.forEach { group ->
                    val handler = group.handlerFactory()
                    validateHandler(group, handler)
                    pluginHandlers += handler
                }
                definitions += active.contribution.toolGroups.flatMap { group ->
                    group.definitions.map { definition ->
                        definition.copy(ownerPluginId = pluginId)
                    }
                }
                handlers += pluginHandlers
            } catch (error: Throwable) {
                pluginHandlers.asReversed().forEach { handler ->
                    runCatching { handler.dispose() }
                }
                errors[pluginId] = error.message ?: error.javaClass.simpleName
                failedPluginIds += pluginId
            }
        }

        failedPluginIds.forEach { pluginId ->
            val active = activePlugins.remove(pluginId) ?: return@forEach
            runCatching { active.plugin.onDisable() }
            storedStates[pluginId]?.let { storedStates[pluginId] = it.copy(enabled = false) }
        }
        if (failedPluginIds.isNotEmpty()) {
            runCatching { persist(storedStates.values) }
        }
        OmniPluginSession(toolDefinitions = definitions, toolHandlers = handlers)
    }

    private suspend fun ensureInitialized() {
        if (initialized) return
        val restored = runCatching { stateStore.read() }.getOrDefault(emptyList())
        restored.forEach { state -> storedStates[state.pluginId] = state }
        initialized = true

        val providerMap = providers().associateBy { it.descriptor.id }
        restored.filter { it.enabled }.forEach { state ->
            val provider = providerMap[state.pluginId]
            if (provider == null) {
                storedStates[state.pluginId] = state.copy(enabled = false)
                return@forEach
            }
            runCatching {
                requireCompatible(provider.descriptor)
                activePlugins[state.pluginId] = activate(provider)
            }.onFailure { error ->
                errors[state.pluginId] = error.message ?: error.javaClass.simpleName
                storedStates[state.pluginId] = state.copy(enabled = false)
            }
        }
        if (storedStates.values.any { restoredState ->
                restored.firstOrNull { it.pluginId == restoredState.pluginId } != restoredState
            }
        ) {
            runCatching { persist(storedStates.values) }
        }
    }

    private suspend fun activate(provider: OmniPluginProvider): ActivePlugin {
        val plugin = provider.create()
        val contribution = plugin.contribution()
        validateContribution(provider.descriptor.id, contribution)
        contribution.toolGroups.forEach { group ->
            val probe = group.handlerFactory()
            try {
                validateHandler(group, probe)
            } finally {
                runCatching { probe.dispose() }
            }
        }
        try {
            plugin.onEnable()
        } catch (error: Throwable) {
            runCatching { plugin.onDisable() }
            throw error
        }
        return ActivePlugin(plugin = plugin, contribution = contribution)
    }

    private fun validateContribution(
        pluginId: String,
        contribution: OmniPluginContribution
    ) {
        val names = contribution.toolGroups.flatMap { group -> group.definitions.map { it.name } }
        require(names.size == names.toSet().size) {
            "Plugin $pluginId declares duplicate tool names"
        }
        names.forEach { name ->
            require(TOOL_NAME.matches(name)) {
                "Plugin $pluginId declares invalid tool name: $name"
            }
            require(name !in reservedToolNames) {
                "Plugin $pluginId conflicts with reserved tool: $name"
            }
            val owner = activePlugins.entries.firstOrNull { (_, active) ->
                active.contribution.toolGroups.any { group ->
                    group.definitions.any { it.name == name }
                }
            }?.key
            require(owner == null || owner == pluginId) {
                "Plugin $pluginId tool $name conflicts with plugin $owner"
            }
        }
        contribution.toolGroups.forEach { group ->
            require(group.definitions.isNotEmpty()) {
                "Plugin $pluginId contains an empty tool group"
            }
        }
    }

    private fun validateHandler(group: OmniPluginToolGroup, handler: ToolHandler) {
        val expected = group.definitions.mapTo(linkedSetOf()) { it.name }
        require(handler.toolNames == expected) {
            "Plugin handler tools ${handler.toolNames} do not match definitions $expected"
        }
    }

    private fun stateFor(provider: OmniPluginProvider): OmniPluginState {
        val descriptor = provider.descriptor
        val stored = storedStates[descriptor.id]
        val compatible = descriptor.interfaceVersion == OmniPluginContract.CURRENT_INTERFACE_VERSION
        return OmniPluginState(
            descriptor = descriptor,
            installed = stored != null,
            enabled = stored?.enabled == true && activePlugins.containsKey(descriptor.id),
            compatible = compatible,
            errorMessage = errors[descriptor.id] ?: if (!compatible) {
                "Requires plugin interface ${descriptor.interfaceVersion}; host supports ${OmniPluginContract.CURRENT_INTERFACE_VERSION}"
            } else {
                null
            }
        )
    }

    private fun providers(): List<OmniPluginProvider> {
        val providers = providerSource().sortedBy { it.descriptor.id }
        val duplicateId = providers.groupBy { it.descriptor.id }
            .entries.firstOrNull { it.value.size > 1 }?.key
        require(duplicateId == null) { "Duplicate plugin provider id: $duplicateId" }
        providers.forEach { validateDescriptor(it.descriptor) }
        return providers
    }

    private fun requireProvider(pluginId: String): OmniPluginProvider {
        return providers().firstOrNull { it.descriptor.id == pluginId }
            ?: throw IllegalArgumentException("Unknown plugin: $pluginId")
    }

    private fun requireCompatible(descriptor: OmniPluginDescriptor) {
        require(descriptor.interfaceVersion == OmniPluginContract.CURRENT_INTERFACE_VERSION) {
            "Plugin ${descriptor.id} requires interface ${descriptor.interfaceVersion}; host supports ${OmniPluginContract.CURRENT_INTERFACE_VERSION}"
        }
    }

    private fun validateDescriptor(descriptor: OmniPluginDescriptor) {
        require(PLUGIN_ID.matches(descriptor.id)) { "Invalid plugin id: ${descriptor.id}" }
        require(descriptor.name.isNotBlank()) { "Plugin ${descriptor.id} has no name" }
        require(descriptor.version.isNotBlank()) { "Plugin ${descriptor.id} has no version" }
        require(descriptor.publisher.isNotBlank()) { "Plugin ${descriptor.id} has no publisher" }
        require(descriptor.downloadSizeBytes >= 0) {
            "Plugin ${descriptor.id} has a negative download size"
        }
    }

    private fun persist(states: Collection<OmniPluginStoredState>) {
        stateStore.write(states.sortedBy { it.pluginId })
    }

    private companion object {
        val PLUGIN_ID = Regex("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)+$")
        val TOOL_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")
    }
}
