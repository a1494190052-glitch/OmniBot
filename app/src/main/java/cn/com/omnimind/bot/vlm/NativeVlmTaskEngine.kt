package cn.com.omnimind.bot.vlm

import cn.com.omnimind.assists.task.vlmserver.VlmTaskEngineExecutor
import cn.com.omnimind.assists.task.vlmserver.VlmTaskEngineHost
import cn.com.omnimind.assists.task.vlmserver.VlmTaskEngineRequest
import cn.com.omnimind.assists.task.vlmserver.VlmTaskEngineResult
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentConversationModePolicy
import cn.com.omnimind.bot.agent.AgentEventAdapter
import cn.com.omnimind.bot.agent.AgentLlmClient
import cn.com.omnimind.bot.agent.AgentOrchestrator
import cn.com.omnimind.bot.agent.AgentResult
import cn.com.omnimind.bot.agent.AgentRuntimeContextRepository
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.DefaultAgentExecutionEnvironment
import cn.com.omnimind.bot.agent.HttpAgentLlmClient
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.agent.WorkspaceMemoryService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal class NativeVlmTaskEngine(
    private val llmClientFactory: (CoroutineScope) -> AgentLlmClient = ::HttpAgentLlmClient,
) : VlmTaskEngineExecutor {
    override suspend fun execute(
        request: VlmTaskEngineRequest,
        host: VlmTaskEngineHost,
    ): VlmTaskEngineResult {
        VlmModelCapabilityGuard.requireSupported(request.model)
        val context = request.context.applicationContext
        val toolbox = AndroidGuiToolbox(
            context = context,
            config = AndroidGuiTaskConfig(
                runId = request.runId,
                goal = request.goal,
                model = request.model,
                maxSteps = request.maxSteps,
                packageName = request.packageName,
                stepSkillGuidance = request.stepSkillGuidance,
                disableFunctionRecall = request.disableFunctionRecall,
            ),
            host = host,
        )
        toolbox.prepare()
        val workspaceManager = AgentWorkspaceManager(context)
        val executionEnvironment = DefaultAgentExecutionEnvironment(
            agentRunId = request.runId,
            userMessage = request.goal,
            currentPackageName = request.packageName,
            runtimeContextRepository = AgentRuntimeContextRepository(context),
            workspaceDescriptor = workspaceManager.buildWorkspaceDescriptor(
                conversationId = null,
                agentRunId = request.runId,
                contextSegmentId = "vlm-${request.runId}",
            ),
            resolvedSkills = emptyList(),
            workspaceManager = workspaceManager,
            workspaceMemoryService = WorkspaceMemoryService(context, workspaceManager),
            conversationMode = AgentConversationModePolicy.NORMAL_MODE,
            reasoningEffort = "no",
        )
        val callback = GuiAgentCallback(host)
        val result = AgentOrchestrator(
            llmClient = llmClientFactory(CoroutineScope(currentCoroutineContext())),
            toolRegistry = toolbox,
            toolRouter = toolbox,
            eventAdapter = AgentEventAdapter(Json { explicitNulls = false }),
            model = request.model,
        ).run(
            AgentOrchestrator.Input(
                callback = callback,
                initialMessages = toolbox.initialMessages(),
                executionEnv = executionEnvironment,
                turnContextProvider = toolbox,
                turnObserver = toolbox,
                requestOptions = toolbox.requestOptions(),
            )
        )
        val terminal = toolbox.terminal
        if (terminal != null) {
            return VlmTaskEngineResult(
                success = terminal.success,
                error = terminal.content.takeUnless { terminal.success },
                doneReason = terminal.reason,
                finishedContent = terminal.content.takeIf { terminal.success },
                finalStateId = toolbox.finalStateId,
            )
        }
        val error = when (result) {
            is AgentResult.Error -> result.message
            is AgentResult.Success -> callback.lastError ?: "vlm_terminal_decision_required"
        }
        return VlmTaskEngineResult(
            success = false,
            error = error,
            doneReason = "error",
            finalStateId = toolbox.finalStateId,
        )
    }

    private class GuiAgentCallback(
        private val host: VlmTaskEngineHost,
    ) : AgentCallback {
        var lastError: String? = null
            private set

        override suspend fun onThinkingStart() = Unit

        override suspend fun onThinkingUpdate(thinking: String) {
            if (thinking.isNotBlank()) host.onModelTurn(mapOf("thinking" to thinking))
        }

        override suspend fun onToolCallStart(toolName: String, arguments: JsonObject) = Unit

        override suspend fun onToolCallProgress(
            toolName: String,
            progress: String,
            extras: Map<String, Any?>,
        ) = Unit

        override suspend fun onToolCallComplete(toolName: String, result: ToolExecutionResult) = Unit

        override suspend fun onChatMessage(message: String) = Unit

        override suspend fun onClarifyRequired(question: String, missingFields: List<String>?) = Unit

        override suspend fun onComplete(result: AgentResult) = Unit

        override suspend fun onError(error: String) {
            lastError = error
        }

        override suspend fun onPermissionRequired(missing: List<String>) = Unit
    }
}
