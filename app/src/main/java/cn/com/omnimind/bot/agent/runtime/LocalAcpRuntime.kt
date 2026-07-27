@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package cn.com.omnimind.bot.agent.runtime

import android.content.Context
import android.util.Base64
import android.util.Log
import cn.com.omnimind.bot.BuildConfig
import cn.com.omnimind.bot.agent.AgentWorkspaceAttachmentSupport
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import com.ai.assistance.operit.terminal.TerminalManager
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.FileSystemCapability
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionKind
import com.agentclientprotocol.model.PlanCapabilities
import com.agentclientprotocol.model.PlanVariant
import com.agentclientprotocol.model.ReadTextFileResponse
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionConfigOption
import com.agentclientprotocol.model.SessionConfigOptionCategory
import com.agentclientprotocol.model.SessionConfigOptionValue
import com.agentclientprotocol.model.SessionConfigSelectOptions
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.WriteTextFileResponse
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.rpc.MethodName
import com.agentclientprotocol.transport.StdioTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class LocalAcpRuntime(
    context: Context,
    private val scope: CoroutineScope,
    private val bindingRepository: AgentSessionBindingRepository,
    private val profileStore: AcpAgentProfileStore,
    private val prepareLaunchEnvironment: suspend (AcpAgentProfile) -> Map<String, String>,
    private val onMessage: suspend (Map<String, Any?>) -> Unit
) {
    private val appContext = context.applicationContext
    private val connectMutex = Mutex()
    private val sessionMutex = Mutex()
    private val workspaceManager = AgentWorkspaceManager(appContext)
    private val sessions = ConcurrentHashMap<String, ClientSession>()
    private val sessionCwds = ConcurrentHashMap<String, String>()
    private val activeTurnIds = ConcurrentHashMap<String, String>()
    private val promptJobs = ConcurrentHashMap<String, Job>()
    private val pendingPermissions =
        ConcurrentHashMap<String, PendingPermissionRequest>()
    private val sessionPermissionBehaviors =
        ConcurrentHashMap<String, AcpPermissionBehavior>()

    @Volatile
    private var connection: AcpProcessConnection? = null

    @Volatile
    private var protocol: Protocol? = null

    @Volatile
    private var client: Client? = null

    @Volatile
    private var agentInfo: AgentInfo? = null

    @Volatile
    private var activeProfile: AcpAgentProfile? = null

    @Volatile
    private var catalogSessionId: String? = null

    val isConnected: Boolean
        get() = connection?.isRunning == true && client != null && agentInfo != null

    fun activeAgentId(): String = (activeProfile ?: profileStore.selected()).id

    fun activeAgentName(): String = (activeProfile ?: profileStore.selected()).name

    fun protocolVersion(): Int? = agentInfo?.protocolVersion

    fun agentVersion(): String? = agentInfo?.implementation?.version

    suspend fun connect(
        profile: AcpAgentProfile = profileStore.selected()
    ) = connectMutex.withLock {
        require(profile.enabled) { "ACP agent ${profile.name} is disabled." }
        if (isConnected && activeProfile?.id == profile.id) {
            return@withLock
        }
        disconnectLocked()
        workspaceManager.ensureRuntimeDirectories()
        val baseEnvironment = try {
            prepareLaunchEnvironment(profile).also {
                requireLaunchCommand(profile)
            }
        } catch (error: Throwable) {
            val wrapped = wrapInitializationError(profile, error)
            profileStore.saveHealth(profile.id, failedAgentHealth(wrapped))
            throw wrapped
        }
        val nextConnection = AcpProcessConnection(
            context = appContext,
            scope = scope,
            profile = profile,
            environment = baseEnvironment + profile.environment
        )
        val transport = StdioTransport(
            parentScope = scope,
            ioDispatcher = Dispatchers.IO,
            input = nextConnection.input,
            output = nextConnection::writeLine,
            name = "omnibot-acp-${profile.id}"
        )
        val nextProtocol = Protocol(scope, transport)
        val nextClient = Client(nextProtocol)
        try {
            nextConnection.start()
            nextProtocol.start()
            val initialized = initializeAgent(
                client = nextClient,
                connection = nextConnection,
                clientInfo = ClientInfo(
                    capabilities = ClientCapabilities(
                        fs = FileSystemCapability(
                            readTextFile = true,
                            writeTextFile = true
                        ),
                        terminal = false,
                        planCapabilities = PlanCapabilities()
                    ),
                    implementation = Implementation(
                        name = "omnibot-app",
                        version = BuildConfig.VERSION_NAME,
                        title = "OmnibotApp"
                    )
                )
            )
            connection = nextConnection
            protocol = nextProtocol
            client = nextClient
            agentInfo = initialized
            activeProfile = profile
            profileStore.select(profile.id)
            profileStore.saveHealth(
                profile.id,
                AcpAgentHealth(
                    status = AcpAgentHealth.STATUS_ONLINE,
                    installed = true,
                    checkedAt = System.currentTimeMillis(),
                    capabilities = capabilitiesPayload(initialized)
                )
            )
        } catch (error: Throwable) {
            nextProtocol.close()
            val diagnostics = nextConnection.diagnosticSummary()
            nextConnection.close()
            val failure = if (
                error is TimeoutCancellationException &&
                diagnostics.isNotBlank()
            ) {
                IllegalStateException(
                    "ACP initialize timed out after ${INITIALIZE_TIMEOUT_MS / 1_000}s. " +
                        diagnostics,
                    error
                )
            } else {
                error
            }
            val wrapped = wrapInitializationError(
                profile,
                failure
            )
            profileStore.saveHealth(
                profile.id,
                failedAgentHealth(wrapped)
            )
            throw wrapped
        }
    }

    private suspend fun requireLaunchCommand(profile: AcpAgentProfile) {
        val result = TerminalManager.getInstance(appContext).executeHiddenCommand(
            command = "$MANAGED_NPM_PATH_PREFIX " +
                "command -v ${shellQuoteAcp(profile.command)} >/dev/null 2>&1",
            executorKey = "acp-launch-command-${profile.id}",
            timeoutMs = COMMAND_PROBE_TIMEOUT_MS
        )
        if (!result.isOk || result.exitCode != 0) {
            throw IllegalStateException(
                "ACP launch command not found: ${profile.command}. " +
                    "Open Agent mode settings to configure the command or install its adapter."
            )
        }
    }

    private suspend fun initializeAgent(
        client: Client,
        connection: AcpProcessConnection,
        clientInfo: ClientInfo
    ): AgentInfo = withTimeout(INITIALIZE_TIMEOUT_MS) {
        coroutineScope {
            val initialize = async { client.initialize(clientInfo) }
            select {
                initialize.onAwait { it }
                connection.exitSignal.onAwait { exitCode ->
                    initialize.cancel()
                    throw IllegalStateException(
                        connection.exitDescription(exitCode)
                    )
                }
            }
        }
    }

    private fun wrapInitializationError(
        profile: AcpAgentProfile,
        error: Throwable
    ): IllegalStateException {
        if (
            error is IllegalStateException &&
            error.message?.startsWith("Failed to initialize ACP agent ") == true
        ) {
            return error
        }
        return IllegalStateException(
            "Failed to initialize ACP agent ${profile.name}: " +
                (error.message ?: error.javaClass.simpleName),
            error
        )
    }

    suspend fun disconnect() = connectMutex.withLock {
        disconnectLocked()
    }

    private suspend fun disconnectLocked() {
        promptJobs.values.toList().forEach { it.cancelAndJoin() }
        promptJobs.clear()
        pendingPermissions.values.forEach { it.response.complete(null) }
        pendingPermissions.clear()
        sessions.clear()
        sessionCwds.clear()
        sessionPermissionBehaviors.clear()
        catalogSessionId = null
        activeTurnIds.clear()
        protocol?.close()
        protocol = null
        client = null
        agentInfo = null
        activeProfile = null
        connection?.close()
        connection = null
    }

    fun statusPayload(): Map<String, Any?> {
        val selected = activeProfile ?: profileStore.selected()
        return linkedMapOf(
            "protocol" to "acp",
            "protocolVersion" to agentInfo?.protocolVersion,
            "activeAgentId" to selected.id,
            "activeAgentName" to selected.name,
            "agentImplementation" to agentInfo?.implementation?.let {
                linkedMapOf(
                    "name" to it.name,
                    "title" to it.title,
                    "version" to it.version
                )
            },
            "capabilities" to capabilitiesPayload(agentInfo)
        )
    }

    private suspend fun agentsPayload(refreshAvailability: Boolean = true): Map<String, Any?> {
        if (refreshAvailability) {
            refreshAgentAvailability()
        }
        val selectedId = profileStore.selected().id
        return linkedMapOf(
            "selectedAgentId" to selectedId,
            "agents" to profileStore.list().map {
                it.toPayload(
                    selected = it.id == selectedId,
                    health = profileStore.health(it.id)
                )
            }
        )
    }

    suspend fun handleMethod(method: String, args: Map<String, Any?>): Any? {
        return when (method) {
            "agent/list" -> agentsPayload()
            "agent/refresh" -> agentsPayload(refreshAvailability = true)
            "agent/select" -> selectAgent(args.stringValue("agentId").orEmpty())
            "agent/save" -> saveAgent(args)
            "agent/delete" -> deleteAgent(args.stringValue("agentId").orEmpty())
            "agent/test" -> testAgent(args.stringValue("agentId"))
            "thread/start" -> startThread(args)
            "thread/resume" -> resumeThread(args)
            "thread/read" -> readThread(args)
            "thread/list", "thread/loaded/list" -> listThreads(args)
            "thread/archive" -> archiveThread(args, true)
            "thread/unarchive" -> archiveThread(args, false)
            "thread/name/set" -> setThreadName(args)
            "model/list" -> listModels(args)
            "config/read" -> readRunConfig(args)
            "collaborationMode/list" -> listCollaborationModes(args)
            "turn/start" -> startTurn(args)
            "turn/steer" -> steerTurn(args)
            "turn/interrupt" -> interruptTurn(args)
            "review/start" -> startReview(args)
            "respondToServerRequest" -> respondToPermission(args)
            else -> throw UnsupportedOperationException(
                "ACP agent does not expose the legacy method '$method'."
            )
        }
    }

    private suspend fun selectAgent(id: String): Map<String, Any?> {
        val selected = profileStore.select(id)
        if (isConnected && activeProfile?.id != selected.id) {
            disconnect()
        }
        return agentsPayload(refreshAvailability = false)
    }

    private suspend fun saveAgent(args: Map<String, Any?>): Map<String, Any?> {
        val profileMap = args.mapValue("agent").ifEmpty { args }
        val saved = profileStore.save(
            AcpAgentProfile(
                id = profileMap.stringValue("id").orEmpty(),
                name = profileMap.stringValue("name").orEmpty(),
                command = profileMap.stringValue("command").orEmpty(),
                arguments = profileMap.stringList("arguments"),
                environment = profileMap.stringMap("environment"),
                enabled = profileMap["enabled"] != false
            )
        )
        if (activeProfile?.id == saved.id) {
            disconnect()
        }
        return linkedMapOf(
            "agent" to saved.toPayload(
                selected = profileStore.selected().id == saved.id,
                health = profileStore.health(saved.id)
            ),
            "catalog" to agentsPayload(refreshAvailability = false)
        )
    }

    private suspend fun deleteAgent(id: String): Map<String, Any?> {
        if (activeProfile?.id == id) {
            disconnect()
        }
        profileStore.delete(id)
        return agentsPayload(refreshAvailability = false)
    }

    private suspend fun testAgent(id: String?): Map<String, Any?> {
        val profile = profileStore.list().firstOrNull { it.id == id }
            ?: profileStore.selected()
        val wasSelected = profileStore.selected()
        val wasConnected = isConnected
        return runCatching {
            connect(profile = profile)
            linkedMapOf(
                "ok" to true,
                "agent" to profile.toPayload(
                    selected = profile.id == profileStore.selected().id,
                    health = profileStore.health(profile.id)
                ),
                "protocolVersion" to protocolVersion(),
                "implementation" to statusPayload()["agentImplementation"],
                "capabilities" to statusPayload()["capabilities"]
            )
        }.getOrElse { error ->
            val health = failedAgentHealth(error)
            profileStore.saveHealth(profile.id, health)
            linkedMapOf(
                "ok" to false,
                "agent" to profile.toPayload(false, health),
                "status" to health.status,
                "error" to (error.message ?: error.javaClass.simpleName)
            )
        }.also {
            if (profile.id != wasSelected.id) {
                disconnect()
                profileStore.select(wasSelected.id)
                if (wasConnected) {
                    connect(profile = wasSelected)
                }
            }
        }
    }

    private suspend fun refreshAgentAvailability() {
        val profiles = profileStore.list()
        if (profiles.isEmpty()) return
        val command = MANAGED_NPM_PATH_PREFIX + "\n" + profiles.flatMap { profile ->
            val id = shellQuoteAcp(profile.id)
            val runtime = AcpAgentProfileStore.officialRuntime(profile)
            buildList {
                add("launch" to profile.command)
                runtime?.discoveryCommand
                    ?.takeIf { it != profile.command }
                    ?.let { add("discovery" to it) }
            }.map { (kind, rawCommand) ->
                val executable = shellQuoteAcp(rawCommand)
                "if command -v $executable >/dev/null 2>&1; then " +
                    "printf '__OMNI_ACP_AGENT__\\t%s\\t%s\\t1\\n' $id '$kind'; else " +
                    "printf '__OMNI_ACP_AGENT__\\t%s\\t%s\\t0\\n' $id '$kind'; fi"
            }
        }.joinToString("\n")
        val availabilityById = runCatching {
            TerminalManager.getInstance(appContext).executeHiddenCommand(
                command = command,
                executorKey = "acp-agent-catalog-probe",
                timeoutMs = 15_000L
            ).output.lineSequence().mapNotNull { line ->
                val parts = line.trim().split('\t')
                if (parts.size == 4 && parts[0] == "__OMNI_ACP_AGENT__") {
                    Triple(parts[1], parts[2], parts[3] == "1")
                } else {
                    null
                }
            }.groupBy { it.first }
        }.getOrDefault(emptyMap())
        val checkedAt = System.currentTimeMillis()
        profiles.forEach { profile ->
            val availability = availabilityById[profile.id].orEmpty()
                .associate { it.second to it.third }
            val runtime = AcpAgentProfileStore.officialRuntime(profile)
            val launchInstalled = availability["launch"] == true
            val discoveryInstalled = availability["discovery"] == true
            val installed = launchInstalled || discoveryInstalled
            val previous = profileStore.health(profile.id)
            val next = when {
                !profile.enabled -> previous.copy(
                    status = AcpAgentHealth.STATUS_OFFLINE,
                    installed = installed,
                    error = "Agent is disabled."
                )
                !installed -> AcpAgentHealth(
                    status = AcpAgentHealth.STATUS_MISSING,
                    installed = false,
                    error = "Agent command not found: " +
                        (runtime?.discoveryCommand ?: profile.command),
                    checkedAt = checkedAt
                )
                !launchInstalled && runtime?.managedAdapterPackage != null ->
                    AcpAgentHealth(
                        status = AcpAgentHealth.STATUS_UNCHECKED,
                        installed = true,
                        error = "ACP adapter will be prepared during Initialize.",
                        checkedAt = checkedAt
                    )
                previous.installed != true ||
                    previous.status == AcpAgentHealth.STATUS_MISSING -> AcpAgentHealth(
                    status = AcpAgentHealth.STATUS_UNCHECKED,
                    installed = true,
                    checkedAt = checkedAt
                )
                else -> previous.copy(installed = true, checkedAt = checkedAt)
            }
            profileStore.saveHealth(profile.id, next)
        }
    }

    private fun failedAgentHealth(error: Throwable): AcpAgentHealth {
        val message = error.message ?: error.javaClass.simpleName
        val normalized = message.lowercase()
        val missing = "not found" in normalized ||
            "code 127" in normalized ||
            "no such file" in normalized
        return AcpAgentHealth(
            status = if (missing) {
                AcpAgentHealth.STATUS_MISSING
            } else {
                AcpAgentHealth.STATUS_OFFLINE
            },
            installed = !missing,
            error = message,
            checkedAt = System.currentTimeMillis()
        )
    }

    private suspend fun startThread(args: Map<String, Any?>): Map<String, Any?> =
        sessionMutex.withLock {
            val cwd = normalizeCwd(args.stringValue("cwd"))
            val catalogSession = catalogSessionId
                ?.let(sessions::get)
                ?.takeIf { sessionCwds[it.sessionId.value] == cwd }
            val session = catalogSession ?: requireClient().newSession(
                SessionCreationParameters(cwd, emptyList()),
                operationsFactory()
            ).also { registerSession(it, cwd) }
            if (catalogSession != null) {
                catalogSessionId = null
            }
            profileStore.bindSession(session.sessionId.value, activeAgentId())
            applyRunConfig(session, args)
            val conversationId = bindingRepository.ensureBinding(
                threadId = session.sessionId.value,
                conversationId = args.longValue("conversationId"),
                cwd = cwd
            )
            emit(
                method = "thread/started",
                threadId = session.sessionId.value,
                params = mapOf(
                    "thread" to mapOf("id" to session.sessionId.value, "cwd" to cwd)
                )
            )
            sessionPayload(session, conversationId)
        }

    private suspend fun resumeThread(args: Map<String, Any?>): Map<String, Any?> =
        sessionMutex.withLock {
            val threadId = resolveThreadId(args)
            val expectedAgentId = profileStore.agentIdForSession(threadId)
            require(expectedAgentId == null || expectedAgentId == activeAgentId()) {
                "ACP session $threadId belongs to agent $expectedAgentId, not ${activeAgentId()}."
            }
            sessions[threadId]?.let {
                return@withLock sessionPayload(
                    it,
                    bindingRepository.getBindingByThreadId(threadId)?.conversationId
                )
            }
            val capabilities = requireAgentInfo().capabilities
            val cwd = normalizeCwd(
                args.stringValue("cwd")
                    ?: bindingRepository.getBindingByThreadId(threadId)?.cwd
            )
            val parameters = SessionCreationParameters(cwd, emptyList())
            val restored = when {
                capabilities.sessionCapabilities.resume != null ->
                    requireClient().resumeSession(
                        SessionId(threadId),
                        parameters,
                        operationsFactory()
                    )
                capabilities.loadSession ->
                    requireClient().loadSession(
                        SessionId(threadId),
                        parameters,
                        operationsFactory()
                    )
                else -> throw UnsupportedOperationException(
                    "The selected ACP agent did not advertise session resume or loadSession."
                )
            }
            registerSession(restored, cwd)
            profileStore.bindSession(restored.sessionId.value, activeAgentId())
            val conversationId = bindingRepository.ensureBinding(
                threadId = threadId,
                conversationId = args.longValue("conversationId"),
                cwd = cwd
            )
            sessionPayload(restored, conversationId)
        }

    private suspend fun readThread(args: Map<String, Any?>): Map<String, Any?> {
        val response = resumeThread(args)
        return LinkedHashMap(response).apply {
            put("active", activeTurnIds.containsKey(response["threadId"]?.toString()))
            activeTurnIds[response["threadId"]?.toString()]?.let {
                put("activeTurnId", it)
                put("turnId", it)
            }
        }
    }

    private suspend fun listThreads(args: Map<String, Any?>): Map<String, Any?> {
        val limit = (args["limit"] as? Number)?.toInt()?.coerceIn(1, 200) ?: 50
        val capabilities = requireAgentInfo().capabilities
        val entries = if (capabilities.sessionCapabilities.list != null) {
            requireClient().listSessions(
                cwd = args.stringValue("cwd")
            ).take(limit).toList().map { session ->
                profileStore.bindSession(session.sessionId.value, activeAgentId())
                bindingRepository.ensureBinding(
                    threadId = session.sessionId.value,
                    cwd = session.cwd,
                    title = session.title
                )
                linkedMapOf(
                    "id" to session.sessionId.value,
                    "threadId" to session.sessionId.value,
                    "cwd" to session.cwd,
                    "title" to session.title,
                    "updatedAt" to session.updatedAt,
                    "agentId" to activeAgentId(),
                    "agentName" to activeAgentName()
                )
            }
        } else {
            sessions.values
                .filterNot { it.sessionId.value == catalogSessionId }
                .take(limit)
                .map { session ->
                linkedMapOf(
                    "id" to session.sessionId.value,
                    "threadId" to session.sessionId.value,
                    "cwd" to sessionCwds[session.sessionId.value],
                    "agentId" to activeAgentId(),
                    "agentName" to activeAgentName()
                )
            }
        }
        return mapOf("threads" to entries, "data" to entries, "nextCursor" to null)
    }

    private suspend fun archiveThread(
        args: Map<String, Any?>,
        archived: Boolean
    ): Map<String, Any?> {
        val threadId = resolveThreadId(args)
        if (archived && requireAgentInfo().capabilities.sessionCapabilities.close != null) {
            sessions.remove(threadId)?.close()
            sessionCwds.remove(threadId)
            sessionPermissionBehaviors.remove(threadId)
        }
        bindingRepository.setArchived(threadId, archived)
        emit(
            method = if (archived) "thread/archived" else "thread/unarchived",
            threadId = threadId,
            params = mapOf("threadId" to threadId)
        )
        return mapOf(
            "ok" to true,
            "threadId" to threadId,
            "conversationId" to bindingRepository.getBindingByThreadId(threadId)?.conversationId
        )
    }

    private suspend fun setThreadName(args: Map<String, Any?>): Map<String, Any?> {
        val threadId = resolveThreadId(args)
        val name = args.stringValue("name").orEmpty()
        bindingRepository.updateTitle(threadId, name)
        emit(
            method = "thread/name/updated",
            threadId = threadId,
            params = mapOf("threadId" to threadId, "name" to name)
        )
        return mapOf(
            "ok" to true,
            "threadId" to threadId,
            "conversationId" to bindingRepository.getBindingByThreadId(threadId)?.conversationId
        )
    }

    private suspend fun listModels(args: Map<String, Any?>): Map<String, Any?> {
        val session = ensureCatalogSession(args)
        val modelOption = sessionConfigOptions(session).firstOrNull {
            it.id.value == "model" || it.category == SessionConfigOptionCategory.MODEL
        } as? SessionConfigOption.Select
        val options = modelOption?.flatOptions().orEmpty()
        val legacyModels = if (options.isEmpty() && session.modelsSupported) {
            session.availableModels.map {
                linkedMapOf(
                    "id" to it.modelId.value,
                    "model" to it.modelId.value,
                    "displayName" to it.name,
                    "description" to it.description
                )
            }
        } else {
            options.map {
                linkedMapOf(
                    "id" to it.value.value,
                    "model" to it.value.value,
                    "displayName" to it.name,
                    "description" to it.description
                )
            }
        }
        val effortOption = sessionConfigOptions(session).firstOrNull {
            it.id.value == "reasoning_effort" ||
                it.category == SessionConfigOptionCategory.THOUGHT_LEVEL
        } as? SessionConfigOption.Select
        return linkedMapOf(
            "models" to legacyModels,
            "currentModelId" to (
                modelOption?.currentValue?.value
                    ?: if (session.modelsSupported) session.currentModel.value.value else null
                ),
            "reasoningEfforts" to effortOption?.flatOptions()?.map { it.value.value }.orEmpty(),
            "currentReasoningEffort" to effortOption?.currentValue?.value,
            "configOptions" to sessionConfigOptions(session).map(::configOptionPayload)
        )
    }

    private suspend fun readRunConfig(args: Map<String, Any?>): Map<String, Any?> {
        val session = ensureCatalogSession(args)
        val options = sessionConfigOptions(session)
        fun current(id: String, category: SessionConfigOptionCategory? = null): Any? {
            return options.firstOrNull { it.id.value == id || it.category == category }
                ?.currentValuePayload()
        }
        return linkedMapOf(
            "model" to current("model", SessionConfigOptionCategory.MODEL),
            "reasoning_effort" to current(
                "reasoning_effort",
                SessionConfigOptionCategory.THOUGHT_LEVEL
            ),
            "collaborationMode" to current("collaboration_mode"),
            "mode" to current("mode", SessionConfigOptionCategory.MODE),
            "configOptions" to options.map(::configOptionPayload)
        )
    }

    private suspend fun listCollaborationModes(
        args: Map<String, Any?>
    ): Map<String, Any?> {
        val session = ensureCatalogSession(args)
        val option = sessionConfigOptions(session)
            .firstOrNull { it.id.value == "collaboration_mode" }
            as? SessionConfigOption.Select
        return mapOf(
            "collaborationModes" to option?.flatOptions()?.map {
                mapOf(
                    "id" to it.value.value,
                    "name" to it.name,
                    "description" to it.description
                )
            }.orEmpty(),
            "currentMode" to option?.currentValue?.value
        )
    }

    private suspend fun startTurn(args: Map<String, Any?>): Map<String, Any?> {
        val session = ensureSessionForTurn(args)
        val threadId = session.sessionId.value
        applyRunConfig(session, args)
        if (promptJobs[threadId]?.isActive == true) {
            throw IllegalStateException("ACP session $threadId already has an active turn.")
        }
        val turnId = UUID.randomUUID().toString()
        val blocks = buildPromptBlocks(args, turnId)
        activeTurnIds[threadId] = turnId
        emit(
            method = "turn/started",
            threadId = threadId,
            turnId = turnId,
            params = mapOf(
                "threadId" to threadId,
                "turn" to mapOf("id" to turnId)
            )
        )
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                session.prompt(blocks).collect { event ->
                    when (event) {
                        is Event.SessionUpdateEvent ->
                            handleSessionUpdate(threadId, turnId, event.update)
                        is Event.PromptResponseEvent -> {
                            emit(
                                method = "turn/completed",
                                threadId = threadId,
                                turnId = turnId,
                                params = mapOf(
                                    "threadId" to threadId,
                                    "turn" to mapOf(
                                        "id" to turnId,
                                        "status" to event.response.stopReason.name.lowercase()
                                    )
                                )
                            )
                        }
                    }
                }
            } catch (error: Throwable) {
                Log.e(TAG, "ACP prompt failed", error)
                emit(
                    method = "turn/failed",
                    threadId = threadId,
                    turnId = turnId,
                    params = mapOf(
                        "threadId" to threadId,
                        "turnId" to turnId,
                        "error" to (error.message ?: error.javaClass.simpleName),
                        "willRetry" to false
                    )
                )
            } finally {
                activeTurnIds.remove(threadId, turnId)
                promptJobs.remove(threadId)
            }
        }
        promptJobs[threadId] = job
        job.start()
        return linkedMapOf(
            "threadId" to threadId,
            "turnId" to turnId,
            "conversationId" to bindingRepository.getBindingByThreadId(threadId)?.conversationId
        )
    }

    private suspend fun startReview(args: Map<String, Any?>): Map<String, Any?> {
        return startTurn(args + mapOf("text" to "/review"))
    }

    private suspend fun steerTurn(args: Map<String, Any?>): Map<String, Any?> {
        val threadId = resolveThreadId(args)
        val capabilities = capabilitiesPayload(requireAgentInfo())
        if (capabilities["steering"] != true) {
            throw UnsupportedOperationException(
                "The selected ACP agent did not advertise steering support."
            )
        }
        val text = args.stringValue("text")
            ?: throw IllegalArgumentException("text is required")
        val response = requireProtocol().sendRequestRaw(
            MethodName("session/steer"),
            buildJsonObject {
                put("sessionId", threadId)
                put("prompt", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    })
                })
            },
            SessionId(threadId)
        )
        return mapOf(
            "ok" to true,
            "threadId" to threadId,
            "turnId" to activeTurnIds[threadId],
            "result" to response.toString()
        )
    }

    private suspend fun interruptTurn(args: Map<String, Any?>): Map<String, Any?> {
        val threadId = resolveThreadId(args)
        val session = sessions[threadId]
            ?: throw IllegalArgumentException("ACP session is not loaded: $threadId")
        session.cancel()
        val turnId = activeTurnIds.remove(threadId)
        return mapOf(
            "ok" to true,
            "threadId" to threadId,
            "turnId" to turnId,
            "conversationId" to bindingRepository.getBindingByThreadId(threadId)?.conversationId
        )
    }

    private fun respondToPermission(args: Map<String, Any?>): Map<String, Any?> {
        val requestId = args["requestId"]?.toString()
            ?: throw IllegalArgumentException("requestId is required")
        val pending = pendingPermissions.remove(requestId)
            ?: throw IllegalArgumentException("Unknown ACP permission request: $requestId")
        val response = args.mapValue("response")
        val accepted = response.stringValue("decision")?.lowercase() == "accept"
        val selected = pending.options.firstOrNull { option ->
            if (accepted) {
                option.kind == PermissionOptionKind.ALLOW_ONCE
            } else {
                option.kind == PermissionOptionKind.REJECT_ONCE
            }
        } ?: pending.options.firstOrNull { option ->
            if (accepted) {
                option.kind == PermissionOptionKind.ALLOW_ALWAYS
            } else {
                option.kind == PermissionOptionKind.REJECT_ALWAYS
            }
        }
        pending.response.complete(selected)
        return mapOf("ok" to true)
    }

    private suspend fun ensureSessionForTurn(args: Map<String, Any?>): ClientSession {
        val explicitThreadId = args.stringValue("threadId")
        if (!explicitThreadId.isNullOrBlank()) {
            return sessions[explicitThreadId] ?: run {
                resumeThread(args)
                sessions[explicitThreadId]
                    ?: throw IllegalStateException("Failed to restore ACP session.")
            }
        }
        val conversationId = args.longValue("conversationId")
        val binding = if (conversationId != null) {
            bindingRepository.getBindingByConversationId(conversationId)
        } else {
            null
        }
        if (binding != null) {
            val bindingAgentId = profileStore.agentIdForSession(binding.threadId)
                ?: AcpAgentProfileStore.DEFAULT_CODEX_AGENT_ID
            if (bindingAgentId == activeAgentId()) {
                return sessions[binding.threadId] ?: run {
                    resumeThread(args + mapOf("threadId" to binding.threadId))
                    sessions[binding.threadId]
                        ?: throw IllegalStateException("Failed to restore ACP session.")
                }
            }
        }
        val created = startThread(args)
        return sessions[created["threadId"]?.toString()]
            ?: throw IllegalStateException("Failed to create ACP session.")
    }

    private suspend fun ensureCatalogSession(args: Map<String, Any?>): ClientSession {
        catalogSessionId?.let(sessions::get)?.let { return it }
        sessions.values
            .firstOrNull { bindingRepository.getBindingByThreadId(it.sessionId.value) != null }
            ?.let { return it }
        return sessionMutex.withLock {
            catalogSessionId?.let(sessions::get)?.let {
                return@withLock it
            }
            val cwd = normalizeCwd(args.stringValue("cwd"))
            requireClient().newSession(
                SessionCreationParameters(cwd, emptyList()),
                operationsFactory()
            ).also { session ->
                registerSession(session, cwd)
                catalogSessionId = session.sessionId.value
            }
        }
    }

    private suspend fun applyRunConfig(
        session: ClientSession,
        args: Map<String, Any?>
    ) {
        sessionPermissionBehaviors[session.sessionId.value] =
            resolveAcpPermissionBehavior(args)
        val requested = linkedMapOf<String, Any?>(
            "model" to args.stringValue("model"),
            "reasoning_effort" to (
                args.stringValue("effort") ?: args.stringValue("reasoningEffort")
                ),
            "collaboration_mode" to args.stringValue("collaborationMode"),
            "mode" to resolveAgentMode(args)
        )
        val options = sessionConfigOptions(session)
        requested.forEach { (requestedId, value) ->
            if (value == null) return@forEach
            val option = options.firstOrNull {
                it.id.value == requestedId ||
                    (
                        requestedId == "model" &&
                            it.category == SessionConfigOptionCategory.MODEL
                        ) ||
                    (
                        requestedId == "reasoning_effort" &&
                            it.category == SessionConfigOptionCategory.THOUGHT_LEVEL
                        ) ||
                    (
                        requestedId == "mode" &&
                            it.category == SessionConfigOptionCategory.MODE
                        )
            }
            when (option) {
                is SessionConfigOption.Select -> {
                    val stringValue = value.toString()
                    if (option.flatOptions().any { it.value.value == stringValue } &&
                        option.currentValue.value != stringValue
                    ) {
                        session.setConfigOption(
                            option.id,
                            SessionConfigOptionValue.StringValue(stringValue)
                        )
                    }
                }
                is SessionConfigOption.BooleanOption -> {
                    val boolValue = value as? Boolean ?: return@forEach
                    if (option.currentValue != boolValue) {
                        session.setConfigOption(
                            option.id,
                            SessionConfigOptionValue.BoolValue(boolValue)
                        )
                    }
                }
                null -> {
                    if (requestedId == "model" && session.modelsSupported) {
                        val model = session.availableModels.firstOrNull {
                            it.modelId.value == value.toString()
                        }
                        if (model != null) {
                            session.setModel(model.modelId)
                        }
                    } else if (requestedId == "mode" && session.modesSupported) {
                        val mode = session.availableModes.firstOrNull {
                            it.id.value == value.toString()
                        }
                        if (mode != null) {
                            session.setMode(SessionModeId(mode.id.value))
                        }
                    }
                }
            }
        }
    }

    private fun resolveAgentMode(args: Map<String, Any?>): String? {
        val approval = args.stringValue("approvalPolicy")?.lowercase()
        val sandbox = args.mapValue("sandboxPolicy")
        val sandboxType = sandbox.stringValue("type")?.lowercase()
        return when {
            approval == "never" || sandboxType == "dangerfullaccess" ->
                "agent-full-access"
            sandboxType == "readonly" -> "read-only"
            approval != null || sandboxType != null -> "agent"
            else -> null
        }
    }

    private fun buildPromptBlocks(
        args: Map<String, Any?>,
        turnId: String
    ): List<ContentBlock> {
        val capabilities = requireAgentInfo().capabilities.promptCapabilities
        val blocks = mutableListOf<ContentBlock>()
        val text = args.stringValue("text").orEmpty()
        if (text.isNotEmpty()) {
            blocks += ContentBlock.Text(text)
        }
        val rawAttachments = args.listOfMaps("attachments")
        val attachments = AgentWorkspaceAttachmentSupport.prepareAttachmentsForRuntime(
            context = appContext,
            taskId = turnId,
            rawAttachments = rawAttachments
        )
        attachments.forEach { attachment ->
            if (attachment["sendToModel"] == false) {
                return@forEach
            }
            val name = attachment.stringValue("name")
                ?: attachment.stringValue("fileName")
                ?: "attachment"
            val mimeType = attachment.stringValue("mimeType")
                ?: "application/octet-stream"
            val shellPath = attachment.stringValue("promptPath")
                ?: attachment.stringValue("workspacePath")
                ?: attachment.stringValue("path")
                ?: return@forEach
            val androidPath = attachment.stringValue("path")?.let(::File)
            val isImage = attachment["isImage"] == true ||
                mimeType.startsWith("image/", ignoreCase = true)
            if (isImage && capabilities.image && androidPath?.isFile == true) {
                val encoded = Base64.encodeToString(
                    androidPath.readBytes(),
                    Base64.NO_WRAP
                )
                blocks += ContentBlock.Image(
                    data = encoded,
                    mimeType = mimeType,
                    uri = "file://$shellPath"
                )
            } else {
                blocks += ContentBlock.ResourceLink(
                    name = name,
                    uri = "file://$shellPath",
                    mimeType = mimeType,
                    size = (attachment["size"] as? Number)?.toLong()
                )
            }
        }
        if (blocks.isEmpty()) {
            blocks += ContentBlock.Text("")
        }
        return blocks
    }

    private fun operationsFactory() =
        com.agentclientprotocol.client.ClientOperationsFactory { sessionId, _ ->
            AcpClientOperations(sessionId.value)
        }

    private inner class AcpClientOperations(
        private val threadId: String
    ) : ClientSessionOperations {
        override suspend fun requestPermissions(
            toolCall: SessionUpdate.ToolCallUpdate,
            permissions: List<PermissionOption>,
            _meta: JsonElement?
        ): RequestPermissionResponse {
            if (
                sessionPermissionBehaviors[threadId] ==
                AcpPermissionBehavior.ALLOW_WITHOUT_PROMPT
            ) {
                val selected = permissions.firstOrNull {
                    it.kind == PermissionOptionKind.ALLOW_ALWAYS
                } ?: permissions.firstOrNull {
                    it.kind == PermissionOptionKind.ALLOW_ONCE
                }
                return RequestPermissionResponse(
                    outcome = selected?.let {
                        RequestPermissionOutcome.Selected(it.optionId)
                    } ?: RequestPermissionOutcome.Cancelled
                )
            }
            val requestId = UUID.randomUUID().toString()
            val pending = PendingPermissionRequest(
                options = permissions,
                response = CompletableDeferred()
            )
            pendingPermissions[requestId] = pending
            emit(
                method = "item/started",
                threadId = threadId,
                turnId = activeTurnIds[threadId],
                params = mapOf(
                    "requestId" to requestId,
                    "item" to mapOf(
                        "id" to toolCall.toolCallId.value,
                        "type" to "requestApproval",
                        "title" to (toolCall.title ?: "Permission required"),
                        "detail" to permissions.joinToString("\n") { it.name },
                        "permissionOptions" to permissions.map {
                            mapOf(
                                "id" to it.optionId.value,
                                "name" to it.name,
                                "kind" to it.kind.name.lowercase()
                            )
                        }
                    )
                )
            )
            val selected = pending.response.await()
            return RequestPermissionResponse(
                outcome = selected?.let {
                    RequestPermissionOutcome.Selected(it.optionId)
                } ?: RequestPermissionOutcome.Cancelled
            )
        }

        override suspend fun notify(
            notification: SessionUpdate,
            _meta: JsonElement?
        ) {
            handleSessionUpdate(
                threadId = threadId,
                turnId = activeTurnIds[threadId],
                update = notification
            )
        }

        override suspend fun fsReadTextFile(
            path: String,
            line: UInt?,
            limit: UInt?,
            _meta: JsonElement?
        ): ReadTextFileResponse = withContext(Dispatchers.IO) {
            val file = resolveWorkspaceFile(path)
            require(file.isFile) { "File does not exist: $path" }
            val content = if (line == null && limit == null) {
                file.readText()
            } else {
                val start = ((line ?: 1u).toLong() - 1L).coerceAtLeast(0L).toInt()
                val count = limit?.toLong()?.coerceAtMost(MAX_FILE_LINES.toLong())?.toInt()
                    ?: MAX_FILE_LINES
                file.useLines { lines ->
                    lines.drop(start).take(count).joinToString("\n")
                }
            }
            ReadTextFileResponse(content)
        }

        override suspend fun fsWriteTextFile(
            path: String,
            content: String,
            _meta: JsonElement?
        ): WriteTextFileResponse = withContext(Dispatchers.IO) {
            val file = resolveWorkspaceFile(path)
            file.parentFile?.mkdirs()
            file.writeText(content)
            WriteTextFileResponse()
        }
    }

    private suspend fun handleSessionUpdate(
        threadId: String,
        turnId: String?,
        update: SessionUpdate
    ) {
        when (update) {
            is SessionUpdate.AgentMessageChunk -> emit(
                method = "item/agentMessage/delta",
                threadId = threadId,
                turnId = turnId,
                params = mapOf(
                    "itemId" to (update.messageId?.value ?: "$threadId-agent"),
                    "delta" to update.content.textPayload()
                )
            )
            is SessionUpdate.AgentThoughtChunk -> emit(
                method = "item/reasoning/delta",
                threadId = threadId,
                turnId = turnId,
                params = mapOf(
                    "itemId" to (update.messageId?.value ?: "$threadId-reasoning"),
                    "delta" to update.content.textPayload()
                )
            )
            is SessionUpdate.ToolCall -> emit(
                method = "item/started",
                threadId = threadId,
                turnId = turnId,
                params = mapOf("item" to toolPayload(update))
            )
            is SessionUpdate.ToolCallUpdate -> emit(
                method = if (
                    update.status == ToolCallStatus.COMPLETED ||
                    update.status == ToolCallStatus.FAILED
                ) {
                    "item/completed"
                } else {
                    "item/updated"
                },
                threadId = threadId,
                turnId = turnId,
                params = mapOf("item" to toolPayload(update))
            )
            is SessionUpdate.PlanUpdate -> emit(
                method = "turn/plan/updated",
                threadId = threadId,
                turnId = turnId,
                params = mapOf(
                    "plan" to update.entries.joinToString("\n") {
                        "- [${it.status.name.lowercase()}] ${it.content}"
                    },
                    "entries" to update.entries.map {
                        mapOf(
                            "content" to it.content,
                            "priority" to it.priority.name.lowercase(),
                            "status" to it.status.name.lowercase()
                        )
                    }
                )
            )
            is SessionUpdate.PlanUpdateV2 -> emit(
                method = "turn/plan/updated",
                threadId = threadId,
                turnId = turnId,
                params = when (val plan = update.plan) {
                    is PlanVariant.Items -> mapOf(
                        "id" to plan.id,
                        "plan" to plan.entries.joinToString("\n") { it.content }
                    )
                    is PlanVariant.Markdown -> mapOf("id" to plan.id, "plan" to plan.content)
                    is PlanVariant.File -> mapOf("id" to plan.id, "plan" to plan.uri)
                }
            )
            is SessionUpdate.PlanRemoved -> emit(
                method = "turn/plan/updated",
                threadId = threadId,
                turnId = turnId,
                params = mapOf("id" to update.id, "plan" to "")
            )
            is SessionUpdate.CurrentModeUpdate -> emit(
                method = "thread/settings/updated",
                threadId = threadId,
                turnId = turnId,
                params = mapOf(
                    "threadId" to threadId,
                    "collaborationMode" to update.currentModeId.value
                )
            )
            is SessionUpdate.ConfigOptionUpdate -> emit(
                method = "acp/configOptions/updated",
                threadId = threadId,
                turnId = turnId,
                params = mapOf(
                    "threadId" to threadId,
                    "configOptions" to update.configOptions.map(::configOptionPayload)
                )
            )
            is SessionUpdate.SessionInfoUpdate -> {
                if (!update.title.isNullOrBlank()) {
                    bindingRepository.updateTitle(threadId, update.title)
                    emit(
                        method = "thread/name/updated",
                        threadId = threadId,
                        turnId = turnId,
                        params = mapOf(
                            "threadId" to threadId,
                            "name" to update.title
                        )
                    )
                }
            }
            is SessionUpdate.UsageUpdate -> emit(
                method = "acp/usage/updated",
                threadId = threadId,
                turnId = turnId,
                params = mapOf(
                    "used" to update.used,
                    "size" to update.size,
                    "cost" to update.cost?.amount,
                    "currency" to update.cost?.currency
                )
            )
            is SessionUpdate.AvailableCommandsUpdate -> emit(
                method = "acp/commands/updated",
                threadId = threadId,
                turnId = turnId,
                params = mapOf(
                    "commands" to update.availableCommands.map {
                        mapOf("name" to it.name, "description" to it.description)
                    }
                )
            )
            is SessionUpdate.UnknownSessionUpdate -> emit(
                method = "acp/sessionUpdate/unknown",
                threadId = threadId,
                turnId = turnId,
                params = mapOf(
                    "sessionUpdate" to update.sessionUpdateType,
                    "raw" to update.rawJson.toString()
                )
            )
            is SessionUpdate.UserMessageChunk -> Unit
        }
    }

    private suspend fun emit(
        method: String,
        threadId: String?,
        turnId: String? = null,
        params: Map<String, Any?> = emptyMap()
    ) {
        onMessage(
            linkedMapOf(
                "method" to method,
                "params" to LinkedHashMap(params).apply {
                    if (!threadId.isNullOrBlank()) putIfAbsent("threadId", threadId)
                    if (!turnId.isNullOrBlank()) putIfAbsent("turnId", turnId)
                }
            )
        )
    }

    private fun registerSession(session: ClientSession, cwd: String) {
        sessions[session.sessionId.value] = session
        sessionCwds[session.sessionId.value] = cwd
    }

    private fun sessionPayload(
        session: ClientSession,
        conversationId: Long?
    ): Map<String, Any?> = linkedMapOf(
        "threadId" to session.sessionId.value,
        "id" to session.sessionId.value,
        "conversationId" to conversationId,
        "cwd" to sessionCwds[session.sessionId.value],
        "agentId" to activeAgentId(),
        "agentName" to activeAgentName(),
        "active" to activeTurnIds.containsKey(session.sessionId.value),
        "activeTurnId" to activeTurnIds[session.sessionId.value],
        "configOptions" to sessionConfigOptions(session).map(::configOptionPayload)
    )

    private fun sessionConfigOptions(session: ClientSession): List<SessionConfigOption> {
        return if (session.configOptionsSupported) {
            session.configOptions.value
        } else {
            emptyList()
        }
    }

    private fun configOptionPayload(option: SessionConfigOption): Map<String, Any?> {
        val base = linkedMapOf<String, Any?>(
            "id" to option.id.value,
            "name" to option.name,
            "description" to option.description,
            "category" to option.category?.value,
            "currentValue" to option.currentValuePayload()
        )
        when (option) {
            is SessionConfigOption.Select -> {
                base["type"] = "select"
                base["options"] = option.flatOptions().map {
                    mapOf(
                        "value" to it.value.value,
                        "name" to it.name,
                        "description" to it.description
                    )
                }
            }
            is SessionConfigOption.BooleanOption -> {
                base["type"] = "boolean"
            }
        }
        return base
    }

    private fun resolveWorkspaceFile(path: String): File {
        val shellPath = when {
            path == AgentWorkspaceManager.SHELL_ROOT_PATH ||
                path.startsWith("${AgentWorkspaceManager.SHELL_ROOT_PATH}/") -> path
            path.startsWith("file://${AgentWorkspaceManager.SHELL_ROOT_PATH}") ->
                path.removePrefix("file://")
            path.startsWith("/") -> throw IllegalArgumentException(
                "ACP filesystem access is limited to /workspace."
            )
            else -> "${AgentWorkspaceManager.SHELL_ROOT_PATH}/${path.trimStart('/')}"
        }
        val file = workspaceManager.androidPathForShell(shellPath)?.canonicalFile
            ?: throw IllegalArgumentException("Invalid workspace path: $path")
        val root = AgentWorkspaceManager.rootDirectory(appContext).canonicalFile
        require(file.path == root.path || file.path.startsWith(root.path + File.separator)) {
            "ACP filesystem access is limited to /workspace."
        }
        return file
    }

    private suspend fun resolveThreadId(args: Map<String, Any?>): String {
        args.stringValue("threadId")?.let { return it }
        val conversationId = args.longValue("conversationId")
            ?: throw IllegalArgumentException("threadId or conversationId is required")
        return bindingRepository.getBindingByConversationId(conversationId)?.threadId
            ?: throw IllegalArgumentException(
            "No ACP session is bound to conversation $conversationId"
        )
    }

    private fun normalizeCwd(value: String?): String {
        val cwd = value?.trim().orEmpty().ifBlank {
            AgentRuntimeDefaults.DEFAULT_WORKSPACE_CWD
        }
        require(
            cwd == AgentWorkspaceManager.SHELL_ROOT_PATH ||
                cwd.startsWith("${AgentWorkspaceManager.SHELL_ROOT_PATH}/")
        ) {
            "Local ACP cwd must stay inside ${AgentWorkspaceManager.SHELL_ROOT_PATH}."
        }
        return cwd
    }

    private fun requireClient(): Client = client
        ?: throw IllegalStateException("ACP agent is not connected.")

    private fun requireProtocol(): Protocol = protocol
        ?: throw IllegalStateException("ACP agent is not connected.")

    private fun requireAgentInfo(): AgentInfo = agentInfo
        ?: throw IllegalStateException("ACP agent is not initialized.")

    private data class PendingPermissionRequest(
        val options: List<PermissionOption>,
        val response: CompletableDeferred<PermissionOption?>
    )

    companion object {
        private const val TAG = "LocalAcpRuntime"
        private const val INITIALIZE_TIMEOUT_MS = 90_000L
        private const val COMMAND_PROBE_TIMEOUT_MS = 20_000L
        private const val MAX_FILE_LINES = 20_000
    }
}

internal enum class AcpPermissionBehavior {
    ASK_USER,
    ALLOW_WITHOUT_PROMPT
}

internal fun resolveAcpPermissionBehavior(
    args: Map<String, Any?>
): AcpPermissionBehavior {
    val approvalPolicy = args.stringValue("approvalPolicy")
        ?.lowercase()
        ?.replace("-", "")
        ?.replace("_", "")
    val sandboxType = args.mapValue("sandboxPolicy")
        .stringValue("type")
        ?.lowercase()
        ?.replace("-", "")
        ?.replace("_", "")
    return if (approvalPolicy == "never" || sandboxType == "dangerfullaccess") {
        AcpPermissionBehavior.ALLOW_WITHOUT_PROMPT
    } else {
        AcpPermissionBehavior.ASK_USER
    }
}

private class AcpProcessConnection(
    private val context: Context,
    private val scope: CoroutineScope,
    private val profile: AcpAgentProfile,
    private val environment: Map<String, String>
) {
    private val inputChannel = Channel<String>(Channel.UNLIMITED)
    private val writeMutex = Mutex()
    private val stderrLock = Any()
    private val stderrTail = ArrayDeque<String>()
    private var process: Process? = null
    private var stderrJob: Job? = null
    private var waitJob: Job? = null
    private var readerJob: Job? = null
    private var writer: OutputStreamWriter? = null

    @Volatile
    private var closing = false

    val input: Flow<String> = inputChannel.receiveAsFlow()
    val exitSignal = CompletableDeferred<Int?>()
    val isRunning: Boolean
        get() = process?.isAlive == true

    suspend fun start() {
        if (isRunning) return
        closing = false
        val command = buildString {
            append(MANAGED_NPM_PATH_PREFIX)
            append(' ')
            append("exec ")
            append(shellQuoteAcp(profile.command))
            profile.arguments.forEach {
                append(' ')
                append(shellQuoteAcp(it))
            }
        }
        val started = TerminalManager.getInstance(context).startLongLivedAlpineProcess(
            command = command,
            executorKey = "acp-agent-${profile.id}",
            redirectErrorStream = false,
            extraEnvironment = environment
        )
        process = started
        writer = OutputStreamWriter(started.outputStream, StandardCharsets.UTF_8)
        readerJob = scope.launch {
            try {
                lineFlow(started).collect { inputChannel.send(it) }
            } catch (error: IOException) {
                handleStreamReadFailure(
                    streamName = "stdout",
                    error = error,
                    started = started,
                    terminateProcess = true
                )
            }
        }
        stderrJob = scope.launch(Dispatchers.IO) {
            try {
                started.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotBlank()) {
                            appendDiagnostic(line)
                            Log.d("LocalAcpRuntime", "[${profile.name}] $line")
                        }
                    }
                }
            } catch (error: IOException) {
                handleStreamReadFailure(
                    streamName = "stderr",
                    error = error,
                    started = started,
                    terminateProcess = false
                )
            }
        }
        waitJob = scope.launch(Dispatchers.IO) {
            val exitCode = runCatching { started.waitFor() }.getOrNull()
            exitSignal.complete(exitCode)
            if (process === started) {
                process = null
                inputChannel.close(
                    IllegalStateException(
                        "ACP agent ${profile.name} exited with code $exitCode."
                    )
                )
            }
        }
    }

    private fun appendDiagnostic(message: String) {
        synchronized(stderrLock) {
            stderrTail.addLast(message)
            while (
                stderrTail.size > MAX_STDERR_LINES ||
                stderrTail.sumOf(String::length) > MAX_STDERR_CHARS
            ) {
                stderrTail.removeFirstOrNull()
            }
        }
    }

    private fun handleStreamReadFailure(
        streamName: String,
        error: IOException,
        started: Process,
        terminateProcess: Boolean
    ) {
        if (
            shouldSuppressAcpStreamReadFailure(
                closing = closing,
                currentProcess = process === started,
                processAlive = started.isAlive
            )
        ) {
            return
        }
        val detail = "$streamName reader failed: " +
            (error.message ?: error.javaClass.simpleName)
        appendDiagnostic(detail)
        Log.w("LocalAcpRuntime", "[${profile.name}] $detail", error)
        if (terminateProcess) {
            exitSignal.complete(null)
            runCatching { started.destroy() }
        }
    }

    fun diagnosticSummary(): String {
        val stderr = synchronized(stderrLock) {
            stderrTail.joinToString("\n").trim()
        }
        return if (stderr.isBlank()) {
            ""
        } else {
            "Adapter stderr: ${stderr.takeLast(MAX_STDERR_CHARS)}"
        }
    }

    fun exitDescription(exitCode: Int?): String {
        val summary = diagnosticSummary()
        return buildString {
            append("ACP process exited before initialize completed")
            if (exitCode != null) {
                append(" with code ")
                append(exitCode)
            }
            if (summary.isNotBlank()) {
                append(". ")
                append(summary)
            }
        }
    }

    suspend fun writeLine(line: String) {
        writeMutex.withLock {
            val output = writer
                ?: throw IllegalStateException("ACP agent stdin is closed.")
            withContext(Dispatchers.IO) {
                output.write(line)
                output.write("\n")
                output.flush()
            }
        }
    }

    suspend fun close() {
        closing = true
        val current = process
        process = null
        readerJob?.cancel()
        stderrJob?.cancel()
        waitJob?.cancel()
        runCatching { writer?.close() }
        writer = null
        runCatching { current?.inputStream?.close() }
        runCatching { current?.errorStream?.close() }
        runCatching { current?.destroy() }
        readerJob?.cancelAndJoin()
        stderrJob?.cancelAndJoin()
        waitJob?.cancelAndJoin()
        readerJob = null
        stderrJob = null
        waitJob = null
        inputChannel.close()
    }

    private fun lineFlow(process: Process): Flow<String> = flow {
        process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isNotBlank()) {
                    emit(line)
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private const val MAX_STDERR_LINES = 60
        private const val MAX_STDERR_CHARS = 6_000
    }
}

private fun capabilitiesPayload(info: AgentInfo?): Map<String, Any?> {
    val capabilities = info?.capabilities
    val steering = info?._meta
        ?.runCatching {
            jsonObject["steering"]?.jsonObject?.get("supported")?.jsonPrimitive?.content
        }
        ?.getOrNull()
        ?.toBooleanStrictOrNull() == true
    return linkedMapOf(
        "loadSession" to (capabilities?.loadSession == true),
        "prompt" to linkedMapOf(
            "audio" to (capabilities?.promptCapabilities?.audio == true),
            "image" to (capabilities?.promptCapabilities?.image == true),
            "embeddedContext" to (
                capabilities?.promptCapabilities?.embeddedContext == true
                )
        ),
        "mcp" to linkedMapOf(
            "http" to (capabilities?.mcpCapabilities?.http == true),
            "sse" to (capabilities?.mcpCapabilities?.sse == true)
        ),
        "session" to linkedMapOf(
            "list" to (capabilities?.sessionCapabilities?.list != null),
            "fork" to (capabilities?.sessionCapabilities?.fork != null),
            "resume" to (capabilities?.sessionCapabilities?.resume != null),
            "close" to (capabilities?.sessionCapabilities?.close != null),
            "additionalDirectories" to (
                capabilities?.sessionCapabilities?.additionalDirectories != null
                )
        ),
        "auth" to linkedMapOf(
            "methods" to info?.authMethods?.map {
                mapOf("id" to it.id.value, "name" to it.name)
            }.orEmpty(),
            "logout" to (capabilities?.auth?.logout != null),
            "providers" to (capabilities?.providers != null)
        ),
        "steering" to steering
    )
}

private const val MANAGED_NPM_PATH_PREFIX =
    "PATH=\"/root/.npm-global/bin:\$PATH\"; export PATH;"

internal fun shouldSuppressAcpStreamReadFailure(
    closing: Boolean,
    currentProcess: Boolean,
    processAlive: Boolean
): Boolean = closing || !currentProcess || !processAlive

private fun SessionConfigOption.Select.flatOptions() = when (val value = options) {
    is SessionConfigSelectOptions.Flat -> value.options
    is SessionConfigSelectOptions.Grouped -> value.groups.flatMap { it.options }
}

private fun SessionConfigOption.currentValuePayload(): Any? = when (this) {
    is SessionConfigOption.Select -> currentValue.value
    is SessionConfigOption.BooleanOption -> currentValue
}

private fun ContentBlock.textPayload(): String = when (this) {
    is ContentBlock.Text -> text
    is ContentBlock.ResourceLink -> title ?: name
    is ContentBlock.Image -> uri ?: ""
    is ContentBlock.Audio -> ""
    is ContentBlock.Resource -> resource.toString()
}

private fun toolPayload(update: SessionUpdate.ToolCall): Map<String, Any?> =
    linkedMapOf(
        "id" to update.toolCallId.value,
        "type" to acpToolItemType(update.kind?.name),
        "title" to update.title,
        "status" to update.status?.name?.lowercase(),
        "content" to update.content.toolContentPayload(),
        "locations" to update.locations.map {
            mapOf("path" to it.path, "line" to it.line?.toLong())
        },
        "rawInput" to update.rawInput?.toString(),
        "rawOutput" to update.rawOutput?.toString()
    )

private fun toolPayload(update: SessionUpdate.ToolCallUpdate): Map<String, Any?> =
    linkedMapOf(
        "id" to update.toolCallId.value,
        "type" to acpToolItemType(update.kind?.name),
        "title" to update.title,
        "status" to update.status?.name?.lowercase(),
        "content" to update.content?.toolContentPayload(),
        "locations" to update.locations?.map {
            mapOf("path" to it.path, "line" to it.line?.toLong())
        },
        "rawInput" to update.rawInput?.toString(),
        "rawOutput" to update.rawOutput?.toString()
    )

private fun acpToolItemType(kind: String?): String = when (kind) {
    "EXECUTE" -> "commandExecution"
    "EDIT", "DELETE", "MOVE" -> "fileChange"
    "SEARCH", "FETCH" -> "webSearch"
    "THINK" -> "plan"
    else -> "tool"
}

private fun List<ToolCallContent>.toolContentPayload(): List<Map<String, Any?>> = map {
    when (it) {
        is ToolCallContent.Content -> mapOf(
            "type" to "content",
            "text" to it.content.textPayload()
        )
        is ToolCallContent.Diff -> mapOf(
            "type" to "diff",
            "path" to it.path,
            "oldText" to it.oldText,
            "newText" to it.newText
        )
        is ToolCallContent.Terminal -> mapOf(
            "type" to "terminal",
            "terminalId" to it.terminalId
        )
    }
}

private fun Map<String, Any?>.mapValue(key: String): Map<String, Any?> {
    val raw = this[key] as? Map<*, *> ?: return emptyMap()
    return raw.entries.associate { (mapKey, value) -> mapKey.toString() to value }
}

private fun Map<String, Any?>.stringValue(key: String): String? =
    this[key]?.toString()?.trim()?.takeIf(String::isNotEmpty)

private fun Map<String, Any?>.longValue(key: String): Long? = when (val value = this[key]) {
    is Number -> value.toLong()
    else -> value?.toString()?.toLongOrNull()
}

private fun Map<String, Any?>.stringList(key: String): List<String> =
    (this[key] as? List<*>)?.mapNotNull {
        it?.toString()?.trim()?.takeIf(String::isNotEmpty)
    }.orEmpty()

private fun Map<String, Any?>.stringMap(key: String): Map<String, String> =
    (this[key] as? Map<*, *>)?.entries?.mapNotNull { (mapKey, value) ->
        val keyText = mapKey?.toString()?.trim().orEmpty()
        if (keyText.isEmpty()) null else keyText to value?.toString().orEmpty()
    }?.toMap().orEmpty()

private fun Map<String, Any?>.listOfMaps(key: String): List<Map<String, Any?>> =
    (this[key] as? List<*>)?.mapNotNull { item ->
        (item as? Map<*, *>)?.entries?.associate { (mapKey, value) ->
            mapKey.toString() to value
        }
    }.orEmpty()

private fun shellQuoteAcp(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"
