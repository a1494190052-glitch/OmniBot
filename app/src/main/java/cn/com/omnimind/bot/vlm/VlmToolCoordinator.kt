package cn.com.omnimind.bot.vlm

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import cn.com.omnimind.accessibility.util.ScreenStateUtil
import cn.com.omnimind.assists.api.bean.VlmTaskTerminalResult
import cn.com.omnimind.assists.api.interfaces.OnMessagePushListener
import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.assists.util.pollUntilReady
import cn.com.omnimind.assists.util.TreeEditDistance
import cn.com.omnimind.assists.task.vlmserver.AbortAction
import cn.com.omnimind.assists.task.vlmserver.ClickAction
import cn.com.omnimind.assists.task.vlmserver.FeedbackAction
import cn.com.omnimind.assists.task.vlmserver.FinishedAction
import cn.com.omnimind.assists.task.vlmserver.FunctionRunAction
import cn.com.omnimind.assists.task.vlmserver.GetStateAction
import cn.com.omnimind.assists.task.vlmserver.HttpVLMStreamClient
import cn.com.omnimind.assists.task.vlmserver.InfoAction
import cn.com.omnimind.assists.task.vlmserver.InputTextAction
import cn.com.omnimind.assists.task.vlmserver.LongPressAction
import cn.com.omnimind.assists.task.vlmserver.OpenAppAction
import cn.com.omnimind.assists.task.vlmserver.PressKeyAction
import cn.com.omnimind.assists.task.vlmserver.RecordAction
import cn.com.omnimind.assists.task.vlmserver.RequireUserChoiceAction
import cn.com.omnimind.assists.task.vlmserver.RequireUserConfirmationAction
import cn.com.omnimind.assists.task.vlmserver.SwipeAction
import cn.com.omnimind.assists.task.vlmserver.UIAction
import cn.com.omnimind.assists.task.vlmserver.UIContext
import cn.com.omnimind.assists.task.vlmserver.VLMClient
import cn.com.omnimind.assists.task.vlmserver.VLMConversationState
import cn.com.omnimind.assists.task.vlmserver.VLMCurrentPageSnapshot
import cn.com.omnimind.assists.task.vlmserver.VLMFirstStepOptimizer
import cn.com.omnimind.assists.task.vlmserver.VLMIndexedPageContext
import cn.com.omnimind.assists.task.vlmserver.VLMPageContextProviderRegistry
import cn.com.omnimind.assists.task.vlmserver.VLMPageContextRequest
import cn.com.omnimind.assists.task.vlmserver.VLMRecallContextProviderRegistry
import cn.com.omnimind.assists.task.vlmserver.VLMStreamClient
import cn.com.omnimind.assists.task.vlmserver.WaitAction
import cn.com.omnimind.baselib.util.ImageQuality
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.mcp.McpTaskManager
import cn.com.omnimind.bot.mcp.PendingOmniFlowFunctionCall
import cn.com.omnimind.bot.mcp.TaskState
import cn.com.omnimind.bot.mcp.TaskStatus
import cn.com.omnimind.bot.mcp.VlmTaskRequest
import cn.com.omnimind.bot.runlog.OobOmniFlowToolkitService
import cn.com.omnimind.bot.util.AssistsUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.json.JSONObject
import java.util.UUID

enum class VlmToolOutcomeStatus {
    FINISHED,
    WAITING_INPUT,
    SCREEN_LOCKED,
    ERROR,
    TIMEOUT,
    CANCELLED
}

data class VlmToolOutcome(
    val taskId: String,
    val goal: String,
    val status: VlmToolOutcomeStatus,
    val message: String,
    val needSummary: Boolean,
    val finishedContent: String? = null,
    val summaryText: String? = null,
    val waitingQuestion: String? = null,
    val errorMessage: String? = null,
    val feedback: String? = null,
    val summaryUnavailable: Boolean = false,
    val recentActivity: List<String> = emptyList(),
    val executionRoute: String = "",
    val errorCode: String? = null,
    val missingPermissions: List<String> = emptyList(),
    val omniflowRecall: Map<String, Any?>? = null,
    val omniflowExecutionSummary: Map<String, Any?>? = null,
) {
    fun toPayload(): Map<String, Any?> = linkedMapOf(
        "taskId" to taskId,
        "goal" to goal,
        "status" to status.name,
        "message" to message,
        "needSummary" to needSummary,
        "finishedContent" to finishedContent,
        "summary" to summaryText,
        "waitingQuestion" to waitingQuestion,
        "errorMessage" to errorMessage,
        "feedback" to feedback,
        "summaryUnavailable" to summaryUnavailable,
        "recentActivity" to recentActivity,
        "executionRoute" to executionRoute,
        "errorCode" to errorCode,
        "missingPermissions" to missingPermissions,
        "omniflowRecall" to omniflowRecall,
        "omniflowExecutionSummary" to omniflowExecutionSummary,
    )
}

data class VlmParseOnlyResult(
    val success: Boolean,
    val model: String,
    val packageName: String?,
    val xmlChars: Int,
    val screenshotIncluded: Boolean,
    val promptChars: Int,
    val parsed: Boolean,
    val toolName: String?,
    val action: Map<String, Any?>?,
    val error: String?,
    val finishReason: String?,
    val rawContentPreview: String,
    val reasoningPreview: String,
    val observationPreview: String,
    val thoughtPreview: String,
    val summaryPreview: String,
    val toolNames: List<String>,
    val dynamicFunctionToolNames: List<String>,
    val requestVariant: String?,
    val requestHadTools: Boolean?,
    val requestToolChoice: String?,
    val requestParallelToolCalls: Boolean?,
    val currentUserTextPreview: String,
    val pageDiagnostics: Map<String, String>,
    val phaseMs: Map<String, Long>,
) {
    fun toPayload(): Map<String, Any?> = linkedMapOf(
        "success" to success,
        "parse_only" to true,
        "executed" to false,
        "model" to model,
        "package_name" to packageName,
        "xml_chars" to xmlChars,
        "screenshot_included" to screenshotIncluded,
        "prompt_chars" to promptChars,
        "parsed" to parsed,
        "tool_name" to toolName,
        "action" to action,
        "error" to error,
        "finish_reason" to finishReason,
        "raw_content_preview" to rawContentPreview,
        "reasoning_preview" to reasoningPreview,
        "observation_preview" to observationPreview,
        "thought_preview" to thoughtPreview,
        "summary_preview" to summaryPreview,
        "tool_names" to toolNames,
        "dynamic_function_tool_names" to dynamicFunctionToolNames,
        "request_variant" to requestVariant,
        "request_had_tools" to requestHadTools,
        "request_tool_choice" to requestToolChoice,
        "request_parallel_tool_calls" to requestParallelToolCalls,
        "current_user_text_preview" to currentUserTextPreview,
        "page_diagnostics" to pageDiagnostics,
        "phase_ms" to phaseMs,
        "content" to listOf(
            mapOf(
                "type" to "text",
                "text" to buildString {
                    append("VLM parse-only completed. executed=false")
                    toolName?.let { append("\ntool_name: $it") }
                    error?.let { append("\nerror: $it") }
                    finishReason?.let { append("\nfinish_reason: $it") }
                    rawContentPreview.takeIf { it.isNotBlank() }?.let {
                        append("\nraw_content_preview: ")
                        append(it)
                    }
                }
            )
        ),
    )
}

typealias VlmToolProgressReporter = suspend (progress: String, extras: Map<String, Any?>) -> Unit

data class VlmFunctionRuntimeSelectionDecision(
    val allowed: Boolean,
    val reason: String,
    val functionId: String? = null,
)

data class RuntimeResolveResult(
    val arguments: Map<String, Any?> = emptyMap(),
    val missingRequiredArguments: List<String> = emptyList(),
    val reason: String = "",
    val resolveCalls: Int = 0,
) {
    companion object {
        fun failed(reason: String, missingRequiredArguments: List<String> = emptyList()): RuntimeResolveResult =
            RuntimeResolveResult(
                missingRequiredArguments = missingRequiredArguments,
                reason = reason,
            )
    }
}

typealias RuntimeResolveProvider = suspend (
    goal: String,
    candidate: Map<String, Any?>,
    recallGuidance: VlmRecallGuidance,
) -> RuntimeResolveResult

object VlmToolCoordinator {
    private const val TAG = "[VlmToolCoordinator]"
    private const val MIN_WAIT_TIMEOUT_MS = 30_000L
    private const val MAX_WAIT_TIMEOUT_MS = 600_000L
    private const val DEFAULT_MAX_STEPS = 12
    private const val MAX_MAX_STEPS = 64
    private const val DRY_RUN_PROMPT_PREVIEW_CHARS = 6000
    private const val GENERIC_ARGUMENT_NAME = "value"
    private const val RECALL_DECISION_MODEL = "scene.dispatch.model"
    private val argumentJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val INTERNAL_FUNCTION_PARAM_NAMES = setOf(
        "package_name", "package", "target_description", "target",
        "selector", "node_id", "node_resource_id", "element_index", "scrollable_index",
        "x", "y", "x1", "y1", "x2", "y2", "bounds", "clear", "duration_ms",
    )
    internal const val RUNTIME_SELECTION_AUTO_EXECUTE_DISABLED = "auto_execute_disabled"
    internal const val RUNTIME_SELECTION_NO_STRICT_HIT = "no_strict_hit"
    internal const val RUNTIME_SELECTION_STRICT_HIT = "strict_hit"

    private val mainHandler = Handler(Looper.getMainLooper())

    fun hasPendingOmniFlowFunctionCall(taskId: String?): Boolean {
        val normalizedTaskId = taskId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return McpTaskManager.getTask(normalizedTaskId)?.pendingOmniFlowFunctionCall != null
    }

    suspend fun executeNewTask(
        context: Context,
        request: VlmTaskRequest,
        scope: CoroutineScope,
        taskIdOverride: String = UUID.randomUUID().toString(),
        returnOnWaitingInput: Boolean = true,
        progressReporter: VlmToolProgressReporter = { _, _ -> }
    ): VlmToolOutcome = withContext(Dispatchers.IO) {
        val taskId = taskIdOverride.trim().takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()
        val needSummary = request.needSummary == true
        val boundedRequest = request.copy(maxSteps = resolveMaxSteps(request.maxSteps))

        val missingPermissions = missingAutomationPermissions(context)
        if (missingPermissions.isNotEmpty()) {
            val taskState = McpTaskManager.createTask(
                taskId = taskId,
                goal = boundedRequest.goal,
                status = TaskStatus.ERROR,
                needSummary = needSummary
            )
            val errorCode = automationPermissionErrorCode(missingPermissions)
            val message = automationPermissionMessage(missingPermissions)
            taskState.message = message
            taskState.errorCode = errorCode
            taskState.missingPermissions = missingPermissions
            taskState.addChatMessage("[SYSTEM] Automation permission required: ${missingPermissions.joinToString(",")}")
            taskState.markStateChanged()
            emitProgress(
                progressReporter,
                taskId,
                taskState.status,
                "权限缺失",
                mapOf(
                    "summary" to message,
                    "errorCode" to errorCode,
                    "missingPermissions" to missingPermissions,
                )
            )
            McpTaskManager.scheduleTaskCleanup(taskId, scope)
            return@withContext taskState.toOutcome(
                status = VlmToolOutcomeStatus.ERROR,
                message = message,
                errorMessage = message,
                errorCode = errorCode,
                missingPermissions = missingPermissions,
            )
        }

        if (!ScreenStateUtil.isOperable()) {
            val taskState = McpTaskManager.createTask(
                taskId = taskId,
                goal = boundedRequest.goal,
                status = TaskStatus.SCREEN_LOCKED,
                needSummary = needSummary
            )
            taskState.vlmRequest = boundedRequest
            taskState.message = "屏幕锁定，等待解锁"
            taskState.addChatMessage("[SYSTEM] Screen locked, waiting for unlock...")
            emitProgress(
                progressReporter,
                taskId,
                taskState.status,
                "等待解锁",
                mapOf("summary" to "等待用户解锁设备")
            )
            return@withContext taskState.toOutcome(
                status = VlmToolOutcomeStatus.SCREEN_LOCKED,
                message = buildScreenLockedPrompt(taskState, isInitial = true)
            )
        }

        val taskState = McpTaskManager.createTask(
            taskId = taskId,
            goal = boundedRequest.goal,
            status = TaskStatus.RUNNING,
            needSummary = needSummary
        )
        taskState.vlmRequest = boundedRequest
        taskState.message = "任务启动中"

        emitProgress(
            progressReporter,
            taskId,
            taskState.status,
            "启动中",
            mapOf("summary" to "正在启动视觉执行任务")
        )

        val (recallGuidance, recallBaseRequest) = buildRecallGuidanceAfterOptionalPrelaunch(
            context = context,
            request = boundedRequest,
        )
        taskState.omniflowRecall = recallGuidance.payload.takeIf { it.isNotEmpty() }
        taskState.vlmRequest = recallBaseRequest
        if (recallGuidance.guidance.isNotBlank()) {
            taskState.executionRoute = "vlm_with_omniflow_recall:${recallGuidance.decision}"
            taskState.markStateChanged()
            emitProgress(
                progressReporter,
                taskId,
                taskState.status,
                    "OmniFlow 召回",
                    mapOf(
                    "summary" to "已完成 fresh observe 与 Function recall；命中时由本地 runtime resolve/replay 接管，否则继续普通 VLM 执行",
                    "omniflowRecallDecision" to recallGuidance.decision,
                    "omniflowRecall" to recallGuidance.payload,
                )
            )
        }
        tryExecuteRecallHitIfAllowed(
            request = boundedRequest,
            taskState = taskState,
            recallGuidance = recallGuidance,
            progressReporter = progressReporter,
            runFunction = { functionId, arguments ->
                OobOmniFlowToolkitService(context).runFunction(
                    linkedMapOf(
                        "function_id" to functionId,
                        "goal" to boundedRequest.goal,
                        "arguments" to arguments,
                        "frontend_run_id" to taskId,
                        "frontend_task_id" to taskId,
                        "frontend_parent" to "vlm_task",
                    )
                )
            },
        )?.let { return@withContext it }
        val executionRequest = taskState.vlmRequest ?: recallBaseRequest

        val startResult = startVlmTaskInternal(
            context,
            executionRequest,
            taskId,
            taskState,
            scope,
            progressReporter
        )
        if (startResult.isFailure) {
            val error = startResult.exceptionOrNull()?.message ?: "Unknown error"
            taskState.status = TaskStatus.ERROR
            taskState.message = error
            taskState.markStateChanged()
            McpTaskManager.scheduleTaskCleanup(taskId, scope)
            emitProgress(
                progressReporter,
                taskId,
                taskState.status,
                "执行失败",
                mapOf("summary" to error)
            )
            return@withContext taskState.toOutcome(
                status = VlmToolOutcomeStatus.ERROR,
                message = error,
                errorMessage = error
            )
        }

        if (needSummary) {
            val notified = notifySummarySheetReadyWithRetry()
            OmniLog.d(TAG, "Summary sheet ready notify(taskId=$taskId) => $notified")
        }

        emitProgress(
            progressReporter,
            taskId,
            taskState.status,
            "执行中",
            mapOf("summary" to "视觉任务执行中")
        )
        return@withContext awaitTask(
            taskId = taskId,
            goal = boundedRequest.goal,
            progressReporter = progressReporter,
            waitTimeoutMs = executionRequest.waitTimeoutMs,
            returnOnWaitingInput = returnOnWaitingInput
        )
    }

    suspend fun tryExecuteRecallHitOnly(
        context: Context,
        request: VlmTaskRequest,
        scope: CoroutineScope,
        taskIdOverride: String = UUID.randomUUID().toString(),
        progressReporter: VlmToolProgressReporter = { _, _ -> },
    ): VlmToolOutcome? = withContext(Dispatchers.IO) {
        if (request.disableOmniFlowRecall || !request.allowOmniFlowFunctionAutoExecute) {
            return@withContext null
        }
        val taskId = taskIdOverride.trim().takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()
        val needSummary = request.needSummary == true
        val boundedRequest = request.copy(maxSteps = resolveMaxSteps(request.maxSteps))
        val missingPermissions = missingAutomationPermissions(context)
        if (missingPermissions.isNotEmpty() || !ScreenStateUtil.isOperable()) {
            return@withContext null
        }
        val (recallGuidance, recallBaseRequest) = buildRecallGuidanceAfterOptionalPrelaunch(
            context = context,
            request = boundedRequest,
        )
        if (recallGuidance.directHitFunctionId.isNullOrBlank()) {
            return@withContext null
        }

        val taskState = McpTaskManager.createTask(
            taskId = taskId,
            goal = boundedRequest.goal,
            status = TaskStatus.RUNNING,
            needSummary = needSummary,
        )
        taskState.vlmRequest = recallBaseRequest
        taskState.omniflowRecall = recallGuidance.payload.takeIf { it.isNotEmpty() }
        taskState.executionRoute = "omniflow_recall_hit:${recallGuidance.decision}"
        taskState.message = "命中 OmniFlow Function"
        taskState.markStateChanged()
        emitProgress(
            progressReporter,
            taskId,
            taskState.status,
            "召回命中",
            mapOf(
                "summary" to "OmniFlow recall 命中；由本地 runtime resolve/replay 执行 Function",
                "omniflowRecallDecision" to recallGuidance.decision,
                "omniflowRecall" to recallGuidance.payload,
            ),
        )
        tryExecuteRecallHitIfAllowed(
            request = boundedRequest,
            taskState = taskState,
            recallGuidance = recallGuidance,
            progressReporter = progressReporter,
            runFunction = { functionId, arguments ->
                OobOmniFlowToolkitService(context).runFunction(
                    linkedMapOf(
                        "function_id" to functionId,
                        "goal" to boundedRequest.goal,
                        "arguments" to arguments,
                        "frontend_run_id" to taskId,
                        "frontend_task_id" to taskId,
                        "frontend_parent" to "vlm_task",
                    )
                )
            },
        )?.let { outcome ->
            if (outcome.status != VlmToolOutcomeStatus.WAITING_INPUT) {
                McpTaskManager.scheduleTaskCleanup(taskId, scope)
            }
            return@withContext outcome
        }
        McpTaskManager.scheduleTaskCleanup(taskId, scope)
        null
    }

    fun cancelTask(
        taskId: String,
        scope: CoroutineScope? = null,
        message: String = "任务已取消"
    ) {
        val normalizedTaskId = taskId.trim()
        if (normalizedTaskId.isEmpty()) return
        val state = McpTaskManager.getTask(normalizedTaskId)
        if (state != null && state.status !in setOf(TaskStatus.FINISHED, TaskStatus.ERROR, TaskStatus.CANCELLED)) {
            state.status = TaskStatus.CANCELLED
            state.message = message
            state.pendingOmniFlowFunctionCall = null
            state.addChatMessage("[SYSTEM] VLM task cancelled")
            state.markStateChanged()
        }
        McpTaskManager.clearPendingOmniFlowClarifyTask(normalizedTaskId)
        runCatching {
            AssistsUtil.Core.cancelRunningTask(normalizedTaskId)
        }.onFailure {
            OmniLog.w(TAG, "cancel VLM task failed taskId=$normalizedTaskId error=${it.message}")
        }
        if (state != null && scope != null) {
            McpTaskManager.scheduleTaskCleanup(normalizedTaskId, scope)
        }
    }

    fun completeTask(
        taskId: String,
        scope: CoroutineScope? = null,
        message: String = "任务已完成"
    ): Boolean {
        val normalizedTaskId = taskId.trim()
        if (normalizedTaskId.isEmpty()) return false
        val state = McpTaskManager.getTask(normalizedTaskId)
        val canComplete = state != null &&
            state.status !in setOf(TaskStatus.FINISHED, TaskStatus.ERROR, TaskStatus.CANCELLED)
        if (canComplete) {
            state.status = TaskStatus.FINISHED
            state.message = message
            state.finishedContent = message
            state.pendingOmniFlowFunctionCall = null
            state.addChatMessage("[SYSTEM] VLM task completed by user")
            state.markStateChanged()
            McpTaskManager.clearPendingOmniFlowClarifyTask(normalizedTaskId)
        }
        val nativeCompleted = runCatching {
            AssistsUtil.Core.completeRunningTask(normalizedTaskId, message)
        }.onFailure {
            OmniLog.w(TAG, "complete VLM task failed taskId=$normalizedTaskId error=${it.message}")
        }.getOrDefault(false)
        if (state != null && scope != null) {
            McpTaskManager.scheduleTaskCleanup(normalizedTaskId, scope)
        }
        return canComplete || nativeCompleted
    }

    suspend fun parseOnlyNextAction(
        context: Context,
        request: VlmTaskRequest,
        scope: CoroutineScope,
        streamClient: VLMStreamClient = HttpVLMStreamClient(scope),
    ): VlmParseOnlyResult = withContext(Dispatchers.IO) {
        val phaseStartedAt = System.currentTimeMillis()
        val phaseMs = linkedMapOf<String, Long>()
        fun markPhase(name: String, startedAt: Long) {
            phaseMs[name] = System.currentTimeMillis() - startedAt
        }

        val captureStartedAt = System.currentTimeMillis()
        val snapshot = captureParseOnlySnapshot(context)
        markPhase("read_current_page_ms", captureStartedAt)
        val baseContext = UIContext(
            overallTask = request.goal,
            currentStepGoal = request.goal,
            stepSkillGuidance = request.stepSkillGuidance,
            targetPackageName = request.packageName.orEmpty(),
            currentPackageName = snapshot.packageName.orEmpty(),
            displayWidth = snapshot.displayWidth,
            displayHeight = snapshot.displayHeight,
            maxSteps = resolveMaxSteps(request.maxSteps),
            stepsUsed = 0,
        )
        val result = parseOnlyNextAction(
            context = baseContext,
            snapshot = snapshot,
            model = request.model ?: "scene.vlm.operation.primary",
            streamClient = streamClient,
            disableOmniFlowRecall = request.disableOmniFlowRecall,
            phaseMs = phaseMs,
        )
        phaseMs["duration_ms"] = System.currentTimeMillis() - phaseStartedAt
        result.copy(phaseMs = phaseMs.toMap())
    }

    internal suspend fun parseOnlyNextAction(
        context: UIContext,
        snapshot: VLMCurrentPageSnapshot,
        model: String = "scene.vlm.operation.primary",
        streamClient: VLMStreamClient,
        conversationState: VLMConversationState = VLMConversationState(),
        vlmClient: VLMClient = VLMClient(),
        disableOmniFlowRecall: Boolean = false,
        phaseMs: MutableMap<String, Long> = linkedMapOf(),
    ): VlmParseOnlyResult {
        suspend fun <T> timed(name: String, block: suspend () -> T): T {
            val startedAt = System.currentTimeMillis()
            return block().also { phaseMs[name] = System.currentTimeMillis() - startedAt }
        }

        var workingContext = context.copy(
            currentPageSummary = "",
            firstStepGuidance = "",
            pageDiagnostics = emptyMap(),
            dynamicToolDefinitions = emptyList(),
            displayWidth = snapshot.displayWidth,
            displayHeight = snapshot.displayHeight
        )
        workingContext = timed("first_step_optimizer_ms") {
            VLMFirstStepOptimizer.enrichContext(
                context = workingContext,
                currentXml = snapshot.xml,
                currentPackageName = snapshot.packageName,
                stepIndex = 0,
            )
        }
        val pageRequest = VLMPageContextRequest(
            context = workingContext,
            currentXml = snapshot.xml,
            currentPackageName = snapshot.packageName,
            screenshotBase64 = snapshot.screenshotBase64,
            stepIndex = 0,
            snapshot = snapshot,
            disableOmniFlowRecall = disableOmniFlowRecall,
        )
        workingContext = timed("page_context_ms") {
            VLMPageContextProviderRegistry.enrich(pageRequest)
        }
        workingContext = timed("function_recall_ms") {
            VLMRecallContextProviderRegistry.enrich(pageRequest.copy(context = workingContext))
        }
        workingContext = timed("indexed_evidence_ms") {
            VLMIndexedPageContext.enrich(
                context = workingContext,
                currentXml = snapshot.xml,
                displayWidth = snapshot.displayWidth,
                displayHeight = snapshot.displayHeight,
            )
        }
        val requestEnvelope = timed("build_request_ms") {
            vlmClient.buildUIOperationRequest(
                context = workingContext,
                screenshot = snapshot.screenshotBase64,
                markedScreenshot = null,
                conversationState = conversationState,
                model = model,
                includeMarkedScreenshot = false,
            )
        }
        val turn = timed("vlm_stream_ms") {
            streamClient.streamTurn(requestEnvelope.request)
        }
        val parsed = timed("parse_response_ms") {
            vlmClient.parseVLMResponse(
                response = turn,
                modelOrScene = model,
                dynamicFunctionToolNames = requestEnvelope.dynamicFunctionToolNames,
            )
        }
        val action = parsed.step?.action
        val thinking = parsed.thinking
        return VlmParseOnlyResult(
            success = parsed.success,
            model = model,
            packageName = snapshot.packageName,
            xmlChars = snapshot.xml?.length ?: 0,
            screenshotIncluded = !snapshot.screenshotBase64.isNullOrBlank(),
            promptChars = requestEnvelope.currentUserText.length,
            parsed = parsed.success && action != null,
            toolName = action?.name,
            action = action?.toDebugMap(),
            error = parsed.error,
            finishReason = thinking?.finishReason,
            rawContentPreview = thinking?.rawContent.orEmpty().take(4000),
            reasoningPreview = thinking?.reasoning.orEmpty().take(4000),
            observationPreview = thinking?.observation.orEmpty().take(1000),
            thoughtPreview = thinking?.thought.orEmpty().take(2000),
            summaryPreview = thinking?.summary.orEmpty().take(1000),
            toolNames = requestEnvelope.toolNames,
            dynamicFunctionToolNames = requestEnvelope.dynamicFunctionToolNames.toList(),
            requestVariant = turn.requestVariant,
            requestHadTools = turn.requestHadTools,
            requestToolChoice = turn.requestToolChoice,
            requestParallelToolCalls = turn.requestParallelToolCalls,
            currentUserTextPreview = requestEnvelope.currentUserText.take(DRY_RUN_PROMPT_PREVIEW_CHARS),
            pageDiagnostics = recalledFunctionDiagnostics(requestEnvelope.dynamicFunctionToolNames) +
                workingContext.pageDiagnostics + linkedMapOf(
                "vlm_stream_request_variant" to turn.requestVariant.orEmpty(),
                "vlm_stream_request_had_tools" to turn.requestHadTools?.toString().orEmpty(),
                "vlm_stream_request_tool_choice" to turn.requestToolChoice.orEmpty(),
                "vlm_stream_request_parallel_tool_calls" to turn.requestParallelToolCalls?.toString().orEmpty(),
            ),
            phaseMs = phaseMs.toMap(),
        )
    }

    suspend fun waitForTask(
        taskId: String,
        goal: String,
        progressReporter: VlmToolProgressReporter = { _, _ -> }
    ): VlmToolOutcome = withContext(Dispatchers.IO) {
        val request = McpTaskManager.getTask(taskId)?.vlmRequest
        awaitTask(
            taskId = taskId,
            goal = goal,
            progressReporter = progressReporter,
            waitTimeoutMs = request?.waitTimeoutMs,
        )
    }

    suspend fun resumeAfterUnlock(
        context: Context,
        taskId: String,
        taskState: TaskState,
        scope: CoroutineScope,
        progressReporter: VlmToolProgressReporter = { _, _ -> }
    ): VlmToolOutcome = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        emitProgress(
            progressReporter,
            taskId,
            TaskStatus.SCREEN_LOCKED,
            "等待解锁",
            mapOf("summary" to "等待用户解锁设备")
        )
        val waitTimeoutMs = resolveWaitTimeoutMs(taskState.vlmRequest?.waitTimeoutMs)
        while (System.currentTimeMillis() - startTime < waitTimeoutMs) {
            if (ScreenStateUtil.isOperable()) {
                taskState.addChatMessage("[SYSTEM] Screen unlocked, starting task...")
                taskState.status = TaskStatus.RUNNING
                taskState.message = "屏幕已解锁，任务启动中"
                val missingPermissions = missingAutomationPermissions(context)
                if (missingPermissions.isNotEmpty()) {
                    val errorCode = automationPermissionErrorCode(missingPermissions)
                    val message = automationPermissionMessage(missingPermissions)
                    taskState.status = TaskStatus.ERROR
                    taskState.message = message
                    taskState.errorCode = errorCode
                    taskState.missingPermissions = missingPermissions
                    taskState.addChatMessage("[SYSTEM] Automation permission required after unlock: ${missingPermissions.joinToString(",")}")
                    taskState.markStateChanged()
                    McpTaskManager.scheduleTaskCleanup(taskId, scope)
                    emitProgress(
                        progressReporter,
                        taskId,
                        taskState.status,
                        "权限缺失",
                        mapOf(
                            "summary" to message,
                            "errorCode" to errorCode,
                            "missingPermissions" to missingPermissions,
                        )
                    )
                    return@withContext taskState.toOutcome(
                        status = VlmToolOutcomeStatus.ERROR,
                        message = message,
                        errorMessage = message,
                        errorCode = errorCode,
                        missingPermissions = missingPermissions,
                    )
                }
                val request = taskState.vlmRequest ?: VlmTaskRequest(
                    goal = taskState.goal,
                    needSummary = taskState.needSummary
                )
                val boundedRequest = request.copy(maxSteps = resolveMaxSteps(request.maxSteps))
                taskState.vlmRequest = boundedRequest
                val (recallGuidance, recallBaseRequest) = buildRecallGuidanceAfterOptionalPrelaunch(
                    context = context,
                    request = boundedRequest,
                )
                taskState.omniflowRecall = recallGuidance.payload.takeIf { it.isNotEmpty() }
                taskState.vlmRequest = recallBaseRequest
                if (recallGuidance.guidance.isNotBlank()) {
                    taskState.executionRoute = "vlm_with_omniflow_recall:${recallGuidance.decision}"
                    taskState.markStateChanged()
                }
                val executionRequest = taskState.vlmRequest ?: recallBaseRequest
                val startResult = startVlmTaskInternal(
                    context,
                    executionRequest,
                    taskId,
                    taskState,
                    scope,
                    progressReporter
                )
                if (startResult.isFailure) {
                    val error = startResult.exceptionOrNull()?.message ?: "Unknown error"
                    taskState.status = TaskStatus.ERROR
                    taskState.message = error
                    taskState.markStateChanged()
                    McpTaskManager.scheduleTaskCleanup(taskId, scope)
                    return@withContext taskState.toOutcome(
                        status = VlmToolOutcomeStatus.ERROR,
                        message = error,
                        errorMessage = error
                    )
                }
                if (taskState.needSummary) {
                    val notified = notifySummarySheetReadyWithRetry()
                    OmniLog.d(TAG, "Summary sheet ready notify after unlock(taskId=$taskId) => $notified")
                }
                emitProgress(
                    progressReporter,
                    taskId,
                    TaskStatus.RUNNING,
                    "执行中",
                    mapOf("summary" to "视觉任务执行中")
                )
                return@withContext awaitTask(
                    taskId = taskId,
                    goal = taskState.goal,
                    progressReporter = progressReporter,
                    waitTimeoutMs = executionRequest.waitTimeoutMs
                )
            }
            delay(McpTaskManager.POLL_INTERVAL_MS)
        }

        return@withContext taskState.toOutcome(
            status = VlmToolOutcomeStatus.TIMEOUT,
            message = "屏幕未在等待时间内解锁，请用户解锁后重试。"
        )
    }

    private suspend fun awaitTask(
        taskId: String,
        goal: String,
        progressReporter: VlmToolProgressReporter,
        waitTimeoutMs: Long? = null,
        returnOnWaitingInput: Boolean = true,
    ): VlmToolOutcome {
        val startWaitTime = System.currentTimeMillis()
        val resolvedWaitTimeoutMs = resolveWaitTimeoutMs(waitTimeoutMs)
        var lastScreenState = ScreenStateUtil.isOperable()
        var lastProgress = ""

        while (System.currentTimeMillis() - startWaitTime < resolvedWaitTimeoutMs) {
            val state = McpTaskManager.getTask(taskId)
                ?: return VlmToolOutcome(
                    taskId = taskId,
                    goal = goal,
                    status = VlmToolOutcomeStatus.ERROR,
                    message = "Task not found: $taskId",
                    needSummary = false,
                    errorMessage = "Task not found: $taskId"
                )

            val currentScreenState = ScreenStateUtil.isOperable()
            if (!currentScreenState && lastScreenState && state.status == TaskStatus.RUNNING) {
                state.status = TaskStatus.SCREEN_LOCKED
                state.message = "屏幕锁定，等待解锁"
                state.addChatMessage("[SYSTEM] Screen locked, waiting for unlock...")
                state.markStateChanged()
            } else if (currentScreenState && !lastScreenState && state.status == TaskStatus.SCREEN_LOCKED) {
                state.status = TaskStatus.RUNNING
                state.message = "屏幕解锁，任务继续"
                state.addChatMessage("[SYSTEM] Screen unlocked, task resuming")
                state.markStateChanged()
            }
            lastScreenState = currentScreenState

            val progress = when (state.status) {
                TaskStatus.RUNNING -> when {
                    state.message.contains("总结", ignoreCase = false) -> "总结生成中"
                    else -> "执行中"
                }
                TaskStatus.WAITING_INPUT -> "等待用户输入"
                TaskStatus.SCREEN_LOCKED -> "等待解锁"
                TaskStatus.FINISHED -> "已完成"
                TaskStatus.ERROR -> "执行失败"
                TaskStatus.CANCELLED -> "已取消"
                TaskStatus.USER_PAUSED -> "等待用户继续"
            }
            if (progress != lastProgress) {
                emitProgress(
                    progressReporter,
                    taskId,
                    state.status,
                    progress,
                    mapOf(
                        "summary" to state.message.ifBlank { progress },
                        "waitingQuestion" to state.waitingQuestion,
                        "finishedContent" to state.finishedContent,
                        "summaryUnavailable" to state.summaryUnavailable
                    )
                )
                lastProgress = progress
            }

            when (state.status) {
                TaskStatus.FINISHED -> {
                    return state.toOutcome(VlmToolOutcomeStatus.FINISHED)
                }
                TaskStatus.ERROR -> {
                    return state.toOutcome(
                        status = VlmToolOutcomeStatus.ERROR,
                        message = state.message.ifBlank { "任务执行失败" },
                        errorMessage = state.message.ifBlank { "任务执行失败" }
                    )
                }
                TaskStatus.CANCELLED -> {
                    return state.toOutcome(
                        status = VlmToolOutcomeStatus.CANCELLED,
                        message = state.message.ifBlank { "任务已取消" },
                        errorMessage = state.message.ifBlank { "任务已取消" }
                    )
                }
                TaskStatus.WAITING_INPUT, TaskStatus.USER_PAUSED -> {
                    if (returnOnWaitingInput) {
                        return state.toOutcome(
                            status = VlmToolOutcomeStatus.WAITING_INPUT,
                            message = state.waitingQuestion ?: state.message.ifBlank { "请提供继续执行所需的信息。" },
                            waitingQuestion = state.waitingQuestion ?: state.message
                        )
                    }
                    delay(McpTaskManager.POLL_INTERVAL_MS)
                }
                TaskStatus.SCREEN_LOCKED -> {
                    return state.toOutcome(
                        status = VlmToolOutcomeStatus.SCREEN_LOCKED,
                        message = buildScreenLockedPrompt(state, isInitial = false)
                    )
                }
                TaskStatus.RUNNING -> delay(McpTaskManager.POLL_INTERVAL_MS)
            }
        }

        val state = McpTaskManager.getTask(taskId)
        if (state?.status == TaskStatus.FINISHED) {
            return state.toOutcome(VlmToolOutcomeStatus.FINISHED)
        }
        val timeoutState = state ?: TaskState(taskId = taskId, goal = goal, status = TaskStatus.RUNNING)
        if (timeoutState.status !in setOf(TaskStatus.FINISHED, TaskStatus.ERROR, TaskStatus.CANCELLED)) {
            cancelTask(taskId, message = "任务等待超时，已停止设备端视觉执行")
        }
        return timeoutState.toOutcome(
            status = VlmToolOutcomeStatus.TIMEOUT,
            message = "任务在等待时间内仍未结束，已停止设备端视觉执行。"
        )
    }

    internal fun resolveWaitTimeoutMs(requestedWaitTimeoutMs: Long?): Long {
        val requested = requestedWaitTimeoutMs?.takeIf { it > 0L }
            ?: return MAX_WAIT_TIMEOUT_MS
        return requested.coerceIn(MIN_WAIT_TIMEOUT_MS, MAX_WAIT_TIMEOUT_MS)
    }

    internal fun resolveMaxSteps(requestedMaxSteps: Int?): Int {
        val requested = requestedMaxSteps?.takeIf { it > 0 } ?: return DEFAULT_MAX_STEPS
        return requested.coerceIn(1, MAX_MAX_STEPS)
    }

    internal fun missingAutomationPermissions(context: Context): List<String> {
        val missing = mutableListOf<String>()
        if (!AssistsUtil.Core.isAccessibilityServiceEnabled()) {
            missing += "accessibility"
        }
        if (!Settings.canDrawOverlays(context)) {
            missing += "overlay"
        }
        return missing
    }

    private fun automationPermissionErrorCode(missingPermissions: List<String>): String =
        if ("accessibility" in missingPermissions) {
            "OOB_ACCESSIBILITY_REQUIRED"
        } else {
            "OOB_PERMISSION_REQUIRED"
        }

    private fun automationPermissionMessage(missingPermissions: List<String>): String {
        val missing = missingPermissions.toSet()
        return when {
            "accessibility" in missing && "overlay" in missing ->
                "请先开启无障碍权限和悬浮窗权限，视觉执行才能点击、滑动、输入并显示执行状态。"
            "accessibility" in missing ->
                "请先开启无障碍权限，视觉执行才能点击、滑动和输入。"
            "overlay" in missing ->
                "请先开启悬浮窗权限，视觉执行才能显示执行状态。"
            else ->
                "请先开启必要权限后再执行视觉任务。"
        }
    }

    private suspend fun startVlmTaskInternal(
        context: Context,
        payload: VlmTaskRequest,
        taskId: String,
        taskState: TaskState,
        scope: CoroutineScope,
        progressReporter: VlmToolProgressReporter
    ): Result<Unit> {
        val deferred = CompletableDeferred<Result<Unit>>()
        mainHandler.post {
            scope.launch(Dispatchers.Main) {
                try {
                    AssistsUtil.Core.createVLMOperationTask(
                        context = context,
                        goal = payload.goal,
                        model = payload.model,
                        maxSteps = payload.maxSteps,
                        packageName = payload.packageName,
                        onMessagePushListener = buildListener(
                            taskId,
                            taskState,
                            scope,
                            progressReporter
                        ),
                        needSummary = payload.needSummary ?: false,
                        skipGoHome = payload.skipGoHome,
                        stepSkillGuidance = payload.stepSkillGuidance,
                        taskId = taskId,
                        disableOmniFlowRecall = payload.disableOmniFlowRecall,
                    )
                    deferred.complete(Result.success(Unit))
                } catch (e: Exception) {
                    taskState.status = TaskStatus.ERROR
                    taskState.message = e.message ?: "Unknown error"
                    taskState.markStateChanged()
                    deferred.complete(Result.failure(e))
                }
            }
        }
        return deferred.await()
    }

    private fun buildListener(
        taskId: String,
        taskState: TaskState,
        scope: CoroutineScope,
        progressReporter: VlmToolProgressReporter
    ): OnMessagePushListener {
        return object : OnMessagePushListener {
            override suspend fun onChatMessage(taskID: String, content: String, type: String?) {
                if (type == "summary_start" || isSummaryMessage(taskID)) {
                    taskState.message = if (type == "summary_start") "总结生成中" else taskState.message
                    val summary = extractSummaryText(content) ?: content
                    if (summary.isNotBlank()) {
                        taskState.updateSummary(summary)
                        taskState.message = "总结已生成"
                    }
                    taskState.markStateChanged()
                    return
                }
                if (content.isNotBlank()) {
                    taskState.addChatMessage(content)
                    taskState.markStateChanged()
                }
            }

            override suspend fun onChatMessageEnd(taskID: String) {
                if (isSummaryMessage(taskID) && taskState.needSummary && taskState.summaryText.isNullOrBlank()) {
                    taskState.summaryUnavailable = true
                    taskState.markStateChanged()
                }
            }

            override fun onTaskFinish() {
                McpTaskManager.scheduleTaskCleanup(taskId, scope)
            }

            override fun onVLMTaskFinish() {
                McpTaskManager.scheduleTaskCleanup(taskId, scope)
            }

            override fun onVLMRequestUserInput(question: String) {
                taskState.status = TaskStatus.WAITING_INPUT
                taskState.waitingQuestion = question
                taskState.message = "等待用户输入"
                taskState.addChatMessage("[AGENT QUESTION] $question")
                taskState.markStateChanged()
            }

            override fun onVlmToolEvent(event: Map<String, Any?>) {
                val summary = listOf(
                    event["summary"],
                    event["progress"],
                    event["toolTitle"],
                    event["toolName"]
                ).firstNotNullOfOrNull { raw ->
                    raw?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                }.orEmpty()
                if (summary.isNotBlank()) {
                    if (taskState.status == TaskStatus.WAITING_INPUT || taskState.status == TaskStatus.USER_PAUSED) {
                        taskState.status = TaskStatus.RUNNING
                    }
                    taskState.message = summary
                    taskState.markStateChanged()
                }
                scope.launch {
                    progressReporter(
                        summary.ifBlank { "视觉任务执行中" },
                        linkedMapOf<String, Any?>().apply {
                            putAll(event)
                            put(
                                "agentStreamKind",
                                event["agentStreamKind"]?.toString()
                                    ?: event["kind"]?.toString()
                                    ?: "tool_progress"
                            )
                            put("vlmTaskId", taskId)
                            put("runLogId", event["runLogId"] ?: taskId)
                        }
                    )
                }
            }

            override fun onVlmTaskResult(result: VlmTaskTerminalResult) {
                taskState.applyTerminalResult(result)
                if (result.status != cn.com.omnimind.assists.api.bean.VlmTaskTerminalStatus.WAITING_INPUT) {
                    McpTaskManager.scheduleTaskCleanup(taskId, scope)
                }
            }
        }
    }

    internal suspend fun buildRecallGuidanceAfterOptionalPrelaunch(
        context: Context,
        request: VlmTaskRequest,
    ): Pair<VlmRecallGuidance, VlmTaskRequest> {
        val targetPackage = request.packageName?.trim().orEmpty()
        if (request.disableOmniFlowRecall) {
            return VlmRecallGuidance(
                decision = "disabled",
                guidance = "",
                payload = mapOf(
                    "success" to false,
                    "recall_disabled" to true,
                    "reason" to "request_disable_omniflow_recall",
                ),
            ) to request
        }
        val shouldPrelaunchForRecall = targetPackage.isNotEmpty() && !request.skipGoHome
        val observedRequest = if (shouldPrelaunchForRecall) {
            val launched = runCatching {
                AccessibilityController.launchApplication(targetPackage) { _, _ -> }
            }.onFailure { error ->
                OmniLog.w(TAG, "Recall prelaunch failed target=$targetPackage error=${error.message}")
            }.isSuccess
            if (launched) {
                pollUntilReady(intervalMs = 300L, timeoutMs = 2000L) {
                    runCatching { AccessibilityController.getCaptureScreenShotXml(true) }.getOrNull()
                        ?.trim()?.takeIf { it.isNotEmpty() }
                }
                request.copy(skipGoHome = true)
            } else {
                request
            }
        } else {
            request
        }
        val observation = waitForRecallObservation()
        val currentPackage = observation.packageName
        val guidance = VlmRecallGuidanceBuilder.build(
            context = context,
            goal = request.goal,
            targetPackageName = request.packageName,
            currentPackageName = currentPackage,
            currentXml = observation.xml,
            allowDirectExecutionDecision = true,
        )
        return guidance to observedRequest
    }

    private data class RecallObservation(
        val packageName: String?,
        val xml: String?,
    )

    private suspend fun waitForRecallObservation(): RecallObservation {
        var lastPackage: String? = null
        var previousXml: String? = null
        val stableXml = pollUntilReady(intervalMs = 300L, timeoutMs = 3000L) {
            val pkg = runCatching { AccessibilityController.getPackageName() }.getOrNull()
                ?.trim()?.takeIf { it.isNotEmpty() }
            if (pkg != null) lastPackage = pkg
            val current = runCatching { AccessibilityController.getCaptureScreenShotXml(true) }.getOrNull()
                ?.trim()?.takeIf { it.isNotEmpty() }
            val prev = previousXml
            previousXml = current
            if (current != null && prev != null) {
                val similarity = runCatching { TreeEditDistance.getSimilarity(prev, current) }.getOrDefault(0f)
                current.takeIf { similarity >= 0.85 }
            } else null
        }
        val currentPackage = runCatching { AccessibilityController.getPackageName() }.getOrNull()
        return RecallObservation(currentPackage ?: lastPackage, stableXml ?: previousXml)
    }

    private suspend fun emitProgress(
        reporter: VlmToolProgressReporter,
        taskId: String,
        status: TaskStatus,
        progress: String,
        extras: Map<String, Any?> = emptyMap()
    ) {
        reporter(
            progress,
            linkedMapOf(
                "taskId" to taskId,
                "status" to status.name,
                "summary" to progress
            ) + extras
        )
    }

    internal suspend fun tryExecuteRecallHit(
        taskState: TaskState,
        goal: String,
        recallGuidance: VlmRecallGuidance,
        progressReporter: VlmToolProgressReporter,
        runFunction: suspend (String, Map<String, Any?>) -> Map<String, Any?>,
        resolveProvider: RuntimeResolveProvider? = null,
    ): VlmToolOutcome? {
        val candidate = executableRecallCandidate(recallGuidance) ?: return null
        val functionId = candidate["function_id"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val argumentResolve = runCatching {
            if (resolveProvider != null) {
                resolveProvider(goal, candidate, recallGuidance)
            } else {
                resolveRecallFunctionArgumentsWithSmallModel(goal, candidate, recallGuidance)
            }
        }.getOrElse { error ->
            RuntimeResolveResult.failed(
                reason = "runtime_resolve_model_failed:${error.message.orEmpty()}",
                missingRequiredArguments = requiredFunctionArgumentNames(candidate),
            ).copy(resolveCalls = 1)
        }
        if (argumentResolve.missingRequiredArguments.isNotEmpty()) {
            val pending = PendingOmniFlowFunctionCall(
                functionId = functionId,
                goal = goal,
                arguments = argumentResolve.arguments,
                requiredArgumentNames = argumentResolve.missingRequiredArguments,
                allArgumentNames = functionArgumentNames(candidate),
            )
            taskState.pendingOmniFlowFunctionCall = pending
            taskState.status = TaskStatus.WAITING_INPUT
            taskState.waitingQuestion = buildOmniFlowArgumentQuestion(pending)
            taskState.message = "等待 OmniFlow Function 参数"
            taskState.executionRoute = "omniflow_lookup_waiting_arguments:$functionId"
            taskState.addChatMessage("[AGENT QUESTION] ${taskState.waitingQuestion}")
            taskState.markStateChanged()
            emitProgress(
                progressReporter,
                taskState.taskId,
                taskState.status,
                "等待参数",
                mapOf(
                    "summary" to "召回复用指令命中，但需要补充参数",
                    "function_id" to functionId,
                    "arguments" to argumentResolve.arguments,
                    "missingArguments" to argumentResolve.missingRequiredArguments,
                    "runtimeResolveReason" to argumentResolve.reason,
                )
            )
            return taskState.toOutcome(
                status = VlmToolOutcomeStatus.WAITING_INPUT,
                message = taskState.waitingQuestion ?: "请补充 OmniFlow Function 参数。",
                waitingQuestion = taskState.waitingQuestion,
            )
        }
        val functionArguments = argumentResolve.arguments
        if (recallHitRequiresArguments(recallGuidance)) {
            taskState.addChatMessage(
                "[SYSTEM] OmniFlow recall hit $functionId requires arguments; runtime resolve returned fields before Function execution: ${functionArguments.keys.joinToString(",")}"
            )
        }
        emitProgress(
            progressReporter,
            taskState.taskId,
            taskState.status,
            "召回执行",
            mapOf(
                "summary" to "命中可直接执行的 OmniFlow Function",
                "omniflowRecallDecision" to recallGuidance.decision,
                "function_id" to functionId,
                "arguments" to functionArguments,
                "runtimeResolveReason" to argumentResolve.reason,
            )
        )
        val result = runCatching { runFunction(functionId, functionArguments) }.getOrElse { error ->
            linkedMapOf<String, Any?>(
                "success" to false,
                "error" to error.message.orEmpty(),
                "error_type" to error.javaClass.name,
            )
        }.withRuntimeResolveCalls(argumentResolve.resolveCalls)
        taskState.omniflowExecutionSummary = compactOmniFlowExecutionSummary(result)
        val success = result["success"] == true
        if (!success) {
            val reason = recallFallbackReason(result)
            taskState.status = TaskStatus.ERROR
            taskState.message = "召回复用指令执行失败: $reason"
            taskState.errorCode = firstNonBlank(result["error_code"], result["errorCode"]).ifBlank {
                "OMNIFLOW_FUNCTION_FAILED"
            }
            taskState.pendingOmniFlowFunctionCall = null
            McpTaskManager.clearPendingOmniFlowClarifyTask(taskState.taskId)
            taskState.executionRoute = "omniflow_recall_failed:$functionId"
            taskState.addChatMessage(
                "[SYSTEM] OmniFlow recall hit $functionId failed in local replay: $reason. " +
                    "Runtime resolve for a failed replay step must stay inside the Function runner; the normal VLM will not reselect or call hidden Functions."
            )
            taskState.markStateChanged()
            emitProgress(
                progressReporter,
                taskState.taskId,
                taskState.status,
                "执行失败",
                mapOf(
                    "summary" to taskState.message,
                    "function_id" to functionId,
                    "arguments" to functionArguments,
                    "omniflowExecutionSummary" to taskState.omniflowExecutionSummary,
                )
            )
            return taskState.toOutcome(
                status = VlmToolOutcomeStatus.ERROR,
                message = taskState.message,
                errorMessage = taskState.message,
                errorCode = taskState.errorCode,
            )
        }

        val runId = result["run_id"]?.toString()?.trim().orEmpty()
        val actionsExecuted = result["actions_executed"]?.toString()?.trim().orEmpty()
        val message = buildString {
            append("已通过召回复用指令完成: ")
            append(functionId)
            if (runId.isNotEmpty()) append(" (run_id=$runId)")
        }
        taskState.status = TaskStatus.FINISHED
        taskState.message = message
        taskState.finishedContent = message
        taskState.summaryText = listOfNotNull(
            "OmniFlow recall hit executed successfully.",
            "function_id=$functionId",
            runId.takeIf { it.isNotEmpty() }?.let { "run_id=$it" },
            actionsExecuted.takeIf { it.isNotEmpty() }?.let { "actions_executed=$it" },
        ).joinToString("\n")
        taskState.executionRoute = "omniflow_recall_hit:$functionId"
        taskState.pendingOmniFlowFunctionCall = null
        McpTaskManager.clearPendingOmniFlowClarifyTask(taskState.taskId)
        taskState.addChatMessage("[SYSTEM] $message")
        taskState.markStateChanged()
        emitProgress(
            progressReporter,
            taskState.taskId,
            taskState.status,
            "执行完成",
            mapOf(
                "summary" to message,
                "function_id" to functionId,
                "arguments" to functionArguments,
                "omniflowExecutionSummary" to taskState.omniflowExecutionSummary,
            )
        )
        return taskState.toOutcome(VlmToolOutcomeStatus.FINISHED)
    }

    suspend fun executePendingOmniFlowFunctionCall(
        taskState: TaskState,
        reply: String,
        progressReporter: VlmToolProgressReporter = { _, _ -> },
        runFunction: suspend (String, Map<String, Any?>) -> Map<String, Any?>,
    ): VlmToolOutcome? {
        val pending = taskState.pendingOmniFlowFunctionCall ?: return null
        val replyArguments = fillMissingArgumentsFromReply(pending, reply)
        val arguments = pending.arguments + replyArguments
        val missing = missingPendingArgumentNames(pending, arguments)
        if (missing.isNotEmpty()) {
            val nextPending = pending.copy(arguments = arguments, requiredArgumentNames = missing)
            taskState.pendingOmniFlowFunctionCall = nextPending
            taskState.status = TaskStatus.WAITING_INPUT
            taskState.waitingQuestion = buildOmniFlowArgumentQuestion(nextPending)
            taskState.message = "等待 OmniFlow Function 参数"
            taskState.addChatMessage("[AGENT QUESTION] ${taskState.waitingQuestion}")
            taskState.markStateChanged()
            emitProgress(
                progressReporter,
                taskState.taskId,
                taskState.status,
                "等待参数",
                mapOf(
                    "summary" to "仍缺少 OmniFlow Function 参数",
                    "function_id" to pending.functionId,
                    "arguments" to arguments,
                    "missingArguments" to missing,
                )
            )
            return taskState.toOutcome(
                status = VlmToolOutcomeStatus.WAITING_INPUT,
                message = taskState.waitingQuestion ?: "请补充 OmniFlow Function 参数。",
                waitingQuestion = taskState.waitingQuestion,
            )
        }

        taskState.status = TaskStatus.RUNNING
        taskState.waitingQuestion = null
        taskState.message = "执行召回复用指令"
        taskState.addChatMessage("User replied: $reply")
        taskState.markStateChanged()
        emitProgress(
            progressReporter,
            taskState.taskId,
            taskState.status,
            "召回执行",
            mapOf(
                "summary" to "参数已确认，直接执行召回复用指令",
                "function_id" to pending.functionId,
                "arguments" to arguments,
            )
        )
        val result = runCatching { runFunction(pending.functionId, arguments) }.getOrElse { error ->
            linkedMapOf<String, Any?>(
                "success" to false,
                "error" to error.message.orEmpty(),
                "error_type" to error.javaClass.name,
            )
        }
        taskState.omniflowExecutionSummary = compactOmniFlowExecutionSummary(result)
        val success = result["success"] == true
        if (!success) {
            val reason = recallFallbackReason(result)
            taskState.status = TaskStatus.ERROR
            taskState.message = "召回复用指令执行失败: $reason"
            taskState.errorCode = firstNonBlank(result["error_code"], result["errorCode"]).ifBlank {
                "OMNIFLOW_FUNCTION_FAILED"
            }
            taskState.pendingOmniFlowFunctionCall = null
            McpTaskManager.clearPendingOmniFlowClarifyTask(taskState.taskId)
            taskState.executionRoute = "omniflow_lookup_failed:${pending.functionId}"
            taskState.addChatMessage("[SYSTEM] OmniFlow Function ${pending.functionId} failed after parameter confirmation: $reason")
            taskState.markStateChanged()
            emitProgress(
                progressReporter,
                taskState.taskId,
                taskState.status,
                "执行失败",
                mapOf(
                    "summary" to taskState.message,
                    "function_id" to pending.functionId,
                    "arguments" to arguments,
                    "omniflowExecutionSummary" to taskState.omniflowExecutionSummary,
                )
            )
            return taskState.toOutcome(
                status = VlmToolOutcomeStatus.ERROR,
                message = taskState.message,
                errorMessage = taskState.message,
                errorCode = taskState.errorCode,
            )
        }

        val runId = result["run_id"]?.toString()?.trim().orEmpty()
        val actionsExecuted = result["actions_executed"]?.toString()?.trim().orEmpty()
        val message = buildString {
            append("已通过召回复用指令完成: ")
            append(pending.functionId)
            if (runId.isNotEmpty()) append(" (run_id=$runId)")
        }
        taskState.status = TaskStatus.FINISHED
        taskState.message = message
        taskState.finishedContent = message
        taskState.summaryText = listOfNotNull(
            "OmniFlow recall hit executed successfully.",
            "function_id=${pending.functionId}",
            runId.takeIf { it.isNotEmpty() }?.let { "run_id=$it" },
            actionsExecuted.takeIf { it.isNotEmpty() }?.let { "actions_executed=$it" },
        ).joinToString("\n")
        taskState.pendingOmniFlowFunctionCall = null
        McpTaskManager.clearPendingOmniFlowClarifyTask(taskState.taskId)
        taskState.executionRoute = "omniflow_recall_hit:${pending.functionId}"
        taskState.addChatMessage("[SYSTEM] $message")
        taskState.markStateChanged()
        emitProgress(
            progressReporter,
            taskState.taskId,
            taskState.status,
            "执行完成",
            mapOf(
                "summary" to message,
                "function_id" to pending.functionId,
                "arguments" to arguments,
                "omniflowExecutionSummary" to taskState.omniflowExecutionSummary,
            )
        )
        return taskState.toOutcome(VlmToolOutcomeStatus.FINISHED)
    }

    internal suspend fun tryExecuteRecallHitIfAllowed(
        request: VlmTaskRequest,
        taskState: TaskState,
        recallGuidance: VlmRecallGuidance,
        progressReporter: VlmToolProgressReporter,
        runFunction: suspend (String, Map<String, Any?>) -> Map<String, Any?>,
        resolveProvider: RuntimeResolveProvider? = null,
    ): VlmToolOutcome? {
        val selection = evaluateFunctionRuntimeSelection(request, recallGuidance)
        if (!selection.allowed) {
            return null
        }
        return tryExecuteRecallHit(
            taskState = taskState,
            goal = request.goal,
            recallGuidance = recallGuidance,
            progressReporter = progressReporter,
            runFunction = runFunction,
            resolveProvider = resolveProvider,
        )
    }

    internal fun evaluateFunctionRuntimeSelection(
        request: VlmTaskRequest,
        recallGuidance: VlmRecallGuidance,
    ): VlmFunctionRuntimeSelectionDecision {
        val hit = mapValue(recallGuidance.payload["hit"])
        val candidateFunctionId = firstNonBlank(
            recallGuidance.directHitFunctionId,
            hit["function_id"],
            recallGuidance.payload["function_id"],
        ).takeIf { it.isNotBlank() }
        if (!request.allowOmniFlowFunctionAutoExecute) {
            return VlmFunctionRuntimeSelectionDecision(
                allowed = false,
                reason = RUNTIME_SELECTION_AUTO_EXECUTE_DISABLED,
                functionId = candidateFunctionId,
            )
        }
        val strictFunctionId = recallGuidance.directHitFunctionId?.trim()?.takeIf { it.isNotEmpty() }
            ?: return VlmFunctionRuntimeSelectionDecision(
                allowed = false,
                reason = RUNTIME_SELECTION_NO_STRICT_HIT,
                functionId = candidateFunctionId,
            )
        return VlmFunctionRuntimeSelectionDecision(
            allowed = true,
            reason = RUNTIME_SELECTION_STRICT_HIT,
            functionId = strictFunctionId,
        )
    }

    private suspend fun resolveRecallFunctionArgumentsWithSmallModel(
        goal: String,
        candidate: Map<String, Any?>,
        recallGuidance: VlmRecallGuidance,
    ): RuntimeResolveResult {
        // Same bounded JSON capability as current-step action resolve. It
        // counts as a resolve call, but not as an executed UI action.
        val allNames = functionArgumentNames(candidate)
        val requiredNames = requiredFunctionArgumentNames(candidate).ifEmpty {
            if (candidateRequiresArguments(candidate)) allNames.take(1) else emptyList()
        }
        if (allNames.isEmpty() && !candidateRequiresArguments(candidate)) {
            return RuntimeResolveResult(reason = "no_public_arguments")
        }
        if (candidateRequiresArguments(candidate) && allNames.isEmpty()) {
            return RuntimeResolveResult(
                arguments = emptyMap(),
                missingRequiredArguments = listOf(GENERIC_ARGUMENT_NAME),
                reason = "argument_schema_missing",
            )
        }
        val raw = HttpController.postLLMRequest(
            RECALL_DECISION_MODEL,
            buildRecallFunctionArgumentResolvePrompt(
                goal = goal,
                candidate = candidate,
                recallGuidance = recallGuidance,
            ),
            responseJsonObject = true,
        ).message
        val resolved = parseRecallFunctionArgumentResolve(raw)
            ?: return RuntimeResolveResult.failed(
                reason = "runtime_resolve_model_unparseable",
                missingRequiredArguments = requiredNames,
            ).copy(resolveCalls = 1)
        val arguments = if (allNames.isEmpty()) {
            emptyMap()
        } else {
            resolved.arguments.filterKeys { it in allNames }
        }
        val missing = requiredNames.filter { name -> isBlankArgumentValue(arguments[name]) }
        return if (missing.isEmpty()) {
            RuntimeResolveResult(
                arguments = arguments,
                reason = resolved.reason.ifBlank { "runtime_resolve_model_completed" },
                resolveCalls = 1,
            )
        } else {
            RuntimeResolveResult(
                arguments = arguments,
                missingRequiredArguments = missing,
                reason = resolved.reason.ifBlank { "missing_required_arguments" },
                resolveCalls = 1,
            )
        }
    }

    private fun buildRecallFunctionArgumentResolvePrompt(
        goal: String,
        candidate: Map<String, Any?>,
        recallGuidance: VlmRecallGuidance,
    ): String = buildString {
        appendLine("OmniFlow runtime already selected this Function for local replay.")
        appendLine("Do not decide whether to use the Function. Do not reject it.")
        appendLine("Return only one JSON object with keys:")
        appendLine("""{"arguments":{},"missing_required_arguments":[],"reason":"short reason"}""")
        appendLine("If parameters are needed and can be inferred from the goal, put them in arguments.")
        appendLine("If required parameters cannot be inferred, list their public names in missing_required_arguments; the app will ask the user.")
        appendLine("Resolve only public business parameters from input_schema. Never expose or invent internal fields like package_name, target_description, x, y, selector, node_id, or resource_id.")
        appendLine()
        appendLine("User goal:")
        appendLine(goal.take(1000))
        appendLine()
        appendLine("Recall decision=${recallGuidance.decision}")
        appendLine("Function candidate:")
        appendLine(candidateForDecisionPrompt(candidate))
    }

    private fun candidateForDecisionPrompt(candidate: Map<String, Any?>): String =
        linkedMapOf<String, Any?>(
            "name" to candidate["name"],
            "title" to candidate["title"],
            "description" to candidate["description"],
            "score" to candidate["score"],
            "reason" to candidate["reason"],
            "input_schema" to (
                mapValue(candidate["input_schema"]).ifEmpty { mapValue(candidate["inputSchema"]) }
                    .takeIf { it.isNotEmpty() }
                ),
            "requires_arguments" to candidate["requires_arguments"],
            "step_summaries" to listValue(candidate["step_summaries"]).take(5).takeIf { it.isNotEmpty() },
        )
            .filterValues { it != null }
            .entries
            .joinToString("\n") { (key, value) -> "$key=$value" }

    private fun parseRecallFunctionArgumentResolve(raw: String): RuntimeResolveResult? {
        val resolveMap = extractJsonObjectMap(raw).takeIf { it.isNotEmpty() } ?: return null
        val arguments = mapValue(resolveMap["arguments"])
        val reason = firstNonBlank(
            resolveMap["reason"],
            resolveMap["message"],
        )
        val missing = listValue(
            resolveMap["missing_required_arguments"]
                ?: resolveMap["missingRequiredArguments"]
        )
            .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
        return RuntimeResolveResult(
            arguments = arguments,
            missingRequiredArguments = missing,
            reason = reason,
        )
    }

    private fun extractJsonObjectMap(raw: String): Map<String, Any?> {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return emptyMap()
        val candidates = listOf(
            trimmed,
            stripJsonFence(trimmed),
            Regex("""\{[\s\S]*}""").find(trimmed)?.value.orEmpty(),
        )
        candidates.forEach { candidate ->
            if (candidate.isBlank()) return@forEach
            runCatching {
                mapValue((argumentJson.parseToJsonElement(candidate) as? JsonObject)?.toPlainAny())
            }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }
        return emptyMap()
    }

    private fun stripJsonFence(text: String): String {
        val trimmed = text.trim()
        return Regex(
            pattern = "^```(?:json)?\\s*([\\s\\S]*?)\\s*```$",
            options = setOf(RegexOption.IGNORE_CASE),
        ).matchEntire(trimmed)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?: trimmed
    }

    private fun boolValue(raw: Any?): Boolean =
        when (raw) {
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            is String -> raw.trim().lowercase() in setOf("true", "1", "yes", "y", "use", "execute", "accept", "accepted")
            else -> false
        }

    private fun executableRecallCandidate(recallGuidance: VlmRecallGuidance): Map<String, Any?>? {
        val strictId = recallGuidance.directHitFunctionId?.trim().orEmpty()
        if (strictId.isEmpty()) return null
        val hit = mapValue(recallGuidance.payload["hit"])
        if (hit["function_id"]?.toString()?.trim() == strictId) return hit
        listValue(recallGuidance.payload["candidates"])
            .mapNotNull { mapValue(it).takeIf { candidate -> candidate.isNotEmpty() } }
            .firstOrNull { it["function_id"]?.toString()?.trim() == strictId }
            ?.let { return it }
        return linkedMapOf(
            "function_id" to strictId,
            "score" to 1.0,
            "strict_direct_hit" to true,
        )
    }

    private fun functionArgumentNames(candidate: Map<String, Any?>): List<String> {
        val schema = mapValue(candidate["inputSchema"]).ifEmpty { mapValue(candidate["input_schema"]) }
        return mapValue(schema["properties"]).keys
            .map { it.trim() }
            .filter { it.isNotEmpty() && !isInternalFunctionParamName(it) }
            .distinct()
    }

    private fun requiredFunctionArgumentNames(candidate: Map<String, Any?>): List<String> {
        val schema = mapValue(candidate["inputSchema"]).ifEmpty { mapValue(candidate["input_schema"]) }
        val knownNames = functionArgumentNames(candidate).toSet()
        return listValue(schema["required"])
            .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            .filterNot(::isInternalFunctionParamName)
            .filter { knownNames.isEmpty() || it in knownNames }
            .distinct()
    }

    private fun fillMissingArgumentsFromReply(
        pending: PendingOmniFlowFunctionCall,
        reply: String,
    ): Map<String, Any?> {
        val parsed = parseArgumentObject(reply)
        if (parsed.isNotEmpty()) {
            val allowed = pending.allArgumentNames.toSet()
            if (allowed.isEmpty()) return parsed
            val filtered = parsed.filterKeys { it in allowed }
            if (filtered.isNotEmpty()) return filtered
            if (pending.requiredArgumentNames.size == 1 && parsed.size == 1) {
                return mapOf(pending.requiredArgumentNames.single() to parsed.values.first())
            }
        }
        val trimmed = reply.trim()
        if (trimmed.isBlank()) return emptyMap()
        if (pending.requiredArgumentNames.size == 1) {
            return mapOf(pending.requiredArgumentNames.single() to trimmed)
        }
        return emptyMap()
    }

    private fun missingPendingArgumentNames(
        pending: PendingOmniFlowFunctionCall,
        arguments: Map<String, Any?>,
    ): List<String> =
        pending.requiredArgumentNames.filter { name ->
            if (name == GENERIC_ARGUMENT_NAME && pending.allArgumentNames.isEmpty()) {
                arguments.isEmpty()
            } else {
                isBlankArgumentValue(arguments[name])
            }
        }

    private fun parseArgumentObject(text: String): Map<String, Any?> {
        val trimmed = text.trim()
        val jsonText = when {
            trimmed.startsWith("{") && trimmed.endsWith("}") -> trimmed
            else -> Regex("""\{.*}""").find(trimmed)?.value.orEmpty()
        }
        if (jsonText.isNotBlank()) {
            runCatching {
                mapValue((argumentJson.parseToJsonElement(jsonText) as? JsonObject)?.toPlainAny())
            }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }
        val pairs = Regex("""([A-Za-z_][A-Za-z0-9_-]*)\s*[:=：]\s*([^,，\n]+)""")
            .findAll(trimmed)
            .mapNotNull { match ->
                val key = match.groupValues.getOrNull(1)?.trim().orEmpty()
                val value = match.groupValues.getOrNull(2)?.trim()?.trim('"', '\'', '“', '”').orEmpty()
                if (key.isNotBlank() && value.isNotBlank()) key to value else null
            }
            .toMap()
        return pairs
    }

    private fun buildOmniFlowArgumentQuestion(
        pending: PendingOmniFlowFunctionCall,
    ): String {
        val missing = pending.requiredArgumentNames.filter { it != GENERIC_ARGUMENT_NAME }
        return if (missing.isEmpty()) {
            "命中 OmniFlow 复用指令，需要补充参数。请直接回复参数 JSON，例如 {\"keyword\":\"猫猫\"}。"
        } else if (missing.size == 1) {
            "命中 OmniFlow 复用指令，请补充参数 `${missing.single()}`。可直接回复值，或回复 JSON。"
        } else {
            "命中 OmniFlow 复用指令，请补充参数：${missing.joinToString(", ")}。请用 JSON 回复。"
        }
    }

    private fun isBlankArgumentValue(value: Any?): Boolean =
        value == null || value.toString().trim().isBlank()

    private fun buildFunctionToolResultGuidance(
        arguments: Map<String, Any?>,
        result: Map<String, Any?>,
    ): String {
        val toolResult = linkedMapOf<String, Any?>(
            "success" to (result["success"] == true),
            "result" to (
                result["result"]
                    ?: result["message"]
                    ?: result["summary"]
                    ?: "Function replay executed."
                ),
        )
        return buildString {
            appendLine("OmniFlow runtime already selected and executed one Function replay before this VLM turn.")
            appendLine("Treat this as the previous tool result, not as automatic task completion.")
            appendLine("Continue from the fresh page observation. If the user goal is now complete, call finished; otherwise choose the next tool.")
            appendLine("Do not call the same Function again unless the fresh page clearly shows it is still needed.")
            appendLine(
                JSONObject(
                    linkedMapOf(
                        "arguments" to arguments,
                        "tool_result" to toolResult,
                    )
                ).toString()
            )
        }
    }

    private fun JSONObject.toMap(): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        keys().forEach { key ->
            result[key] = when (val value = opt(key)) {
                is JSONObject -> value.toMap()
                org.json.JSONObject.NULL -> null
                else -> value
            }
        }
        return result
    }

    private fun mapValue(raw: Any?): Map<String, Any?> =
        (raw as? Map<*, *>)?.entries
            ?.associate { (key, value) -> key.toString() to value }
            ?: emptyMap()

    private fun listValue(raw: Any?): List<Any?> =
        when (raw) {
            is List<*> -> raw
            else -> emptyList()
        }

    private fun recallHitRequiresArguments(recallGuidance: VlmRecallGuidance): Boolean {
        val hit = mapValue(recallGuidance.payload["hit"])
        return candidateRequiresArguments(hit) || candidateRequiresArguments(recallGuidance.payload)
    }

    private fun candidateRequiresArguments(candidate: Map<String, Any?>): Boolean {
        if (candidate.isEmpty()) return false
        val raw = candidate["requires_arguments"] ?: candidate["requiresArguments"]
        if (raw != null) {
            return when (raw) {
                is Boolean -> raw
                is Number -> raw.toInt() != 0
                is String -> raw.trim().lowercase() in setOf("true", "1", "yes", "y")
                else -> false
            }
        }
        val schema = mapValue(candidate["inputSchema"]).ifEmpty { mapValue(candidate["input_schema"]) }
        if (schema.isEmpty()) return false
        val required = listValue(schema["required"])
            .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            .filterNot(::isInternalFunctionParamName)
        if (required.isNotEmpty()) return true
        return mapValue(schema["properties"]).keys.any { !isInternalFunctionParamName(it) }
    }

    private fun isInternalFunctionParamName(name: String): Boolean =
        name.trim().replace(Regex("""([a-z0-9])([A-Z])"""), "$1_$2")
            .replace(Regex("""[^A-Za-z0-9]+"""), "_")
            .trim('_').lowercase() in INTERNAL_FUNCTION_PARAM_NAMES

    private fun firstNonBlank(vararg values: Any?): String =
        values.firstNotNullOfOrNull { value ->
            value?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }.orEmpty()

    private fun recallFallbackReason(result: Map<String, Any?>): String =
        listOf(
            result["error"],
            result["error_message"],
            result["phase"],
        ).firstNotNullOfOrNull { value ->
            value?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        } ?: "unknown"

    private fun UIAction.toDebugMap(): Map<String, Any?> =
        when (this) {
            is ClickAction -> linkedMapOf(
                "tool" to name,
                "target_description" to targetDescription,
                "node_id" to nodeId,
                "x" to x,
                "y" to y,
            )
            is InputTextAction -> linkedMapOf(
                "tool" to name,
                "target_description" to targetDescription,
                "text" to text,
                "node_id" to nodeId,
                "x" to x,
                "y" to y,
            )
            is SwipeAction -> linkedMapOf(
                "tool" to name,
                "target_description" to targetDescription,
                "scrollable_index" to scrollableIndex,
                "direction" to direction,
                "x1" to x1,
                "y1" to y1,
                "x2" to x2,
                "y2" to y2,
                "duration_ms" to durationMs,
            )
            is LongPressAction -> linkedMapOf(
                "tool" to name,
                "target_description" to targetDescription,
                "node_id" to nodeId,
                "x" to x,
                "y" to y,
            )
            is OpenAppAction -> linkedMapOf("tool" to name, "package_name" to packageName)
            is PressKeyAction -> linkedMapOf("tool" to name, "key" to key)
            is GetStateAction -> linkedMapOf("tool" to name, "reason" to reason)
            is FunctionRunAction -> linkedMapOf(
                "tool" to name,
                "function_id" to functionId,
                "arguments" to arguments.toPlainAny(),
            )
            is FinishedAction -> linkedMapOf("tool" to name, "content" to content)
            is InfoAction -> linkedMapOf("tool" to name, "value" to value)
            is FeedbackAction -> linkedMapOf("tool" to name, "value" to value)
            is AbortAction -> linkedMapOf("tool" to name, "value" to value)
            is RequireUserChoiceAction -> linkedMapOf("tool" to name, "options" to options, "prompt" to prompt)
            is RequireUserConfirmationAction -> linkedMapOf("tool" to name, "prompt" to prompt)
            is WaitAction -> linkedMapOf("tool" to name, "duration_ms" to durationMs)
            is RecordAction -> linkedMapOf("tool" to name, "content" to content)
        }.filterValues { it != null }

    private fun recalledFunctionDiagnostics(functionNames: Collection<String>): Map<String, String> {
        val names = functionNames.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
        if (names.isEmpty()) return emptyMap()
        return linkedMapOf(
            "omniflow_recalled_function_count" to names.size.toString(),
            "omniflow_recalled_function_ids" to names.joinToString(","),
        )
    }

    private fun JsonElement.toPlainAny(): Any? =
        when (this) {
            is JsonNull -> null
            is JsonObject -> entries.associate { (key, value) -> key to value.toPlainAny() }
            is JsonArray -> map { it.toPlainAny() }
            is JsonPrimitive -> {
                if (isString) {
                    contentOrNull
                } else {
                    booleanOrNull
                        ?: longOrNull
                        ?: doubleOrNull
                        ?: contentOrNull
                }
            }
        }

    private suspend fun captureParseOnlySnapshot(context: Context): VLMCurrentPageSnapshot {
        val capturedAtMs = System.currentTimeMillis()
        val packageName = runCatching { AccessibilityController.getPackageName().orEmpty() }
            .getOrDefault("")
            .ifBlank { null }
        val xml = runCatching { AccessibilityController.getCaptureScreenShotXml(true).orEmpty() }
            .getOrDefault("")
            .ifBlank { null }
        val screenshotPayload = runCatching {
            AccessibilityController.captureScreenshotImage(
                isBitmap = false,
                isBase64 = true,
                isFile = false,
                isFilterOverlay = true,
                compressQuality = ImageQuality.MEDIUM,
            )
        }.getOrNull()
        val screenshot = screenshotPayload
            ?.imageBase64
            ?.takeIf { screenshotPayload.isSuccess && it.isNotBlank() }
            ?.let(::ensureJpegDataUri)
        val displayMetrics = context.resources.displayMetrics
        return VLMCurrentPageSnapshot(
            packageName = packageName,
            xml = xml,
            screenshotBase64 = screenshot,
            displayWidth = maxOf(screenshotPayload?.originalWidth ?: 0, displayMetrics.widthPixels)
                .coerceAtLeast(1),
            displayHeight = maxOf(screenshotPayload?.originalHeight ?: 0, displayMetrics.heightPixels)
                .coerceAtLeast(1),
            capturedAtMs = capturedAtMs,
        )
    }

    private fun ensureJpegDataUri(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.startsWith("data:image/", ignoreCase = true)) {
            trimmed
        } else {
            "data:image/jpeg;base64,$trimmed"
        }
    }

    private fun TaskState.toOutcome(
        status: VlmToolOutcomeStatus,
        message: String = this.message,
        waitingQuestion: String? = this.waitingQuestion,
        errorMessage: String? = null,
        errorCode: String? = this.errorCode,
        missingPermissions: List<String> = this.missingPermissions,
    ): VlmToolOutcome {
        return VlmToolOutcome(
            taskId = taskId,
            goal = goal,
            status = status,
            message = message.ifBlank {
                waitingQuestion
                    ?: finishedContent
                    ?: errorMessage
                    ?: "任务状态: ${this.status.name}"
            },
            needSummary = needSummary,
            finishedContent = finishedContent,
            summaryText = summaryText,
            waitingQuestion = waitingQuestion,
            errorMessage = errorMessage,
            feedback = feedback,
            summaryUnavailable = summaryUnavailable,
            recentActivity = chatMessages.takeLast(5),
            executionRoute = executionRoute,
            errorCode = errorCode,
            missingPermissions = missingPermissions,
            omniflowRecall = omniflowRecall,
            omniflowExecutionSummary = omniflowExecutionSummary,
        )
    }

    private fun compactOmniFlowExecutionSummary(result: Map<String, Any?>): Map<String, Any?>? {
        val summary = mapValue(result["execution_summary"])
        val source = if (summary.isNotEmpty()) summary else result
        val success = source["success"] ?: result["success"]
        val compact = linkedMapOf<String, Any?>(
            "success" to success,
            "steps" to firstPresent(source, result, "steps", "step_count", "actions_executed"),
            "resolve_calls" to firstPresent(
                source,
                result,
                "resolve_calls",
                "runtime_resolve_calls",
            ),
            "model_calls" to firstPresent(source, result, "model_calls"),
            "tokens" to firstPresent(source, result, "tokens", "total_tokens"),
            "elapsed_ms" to firstPresent(source, result, "elapsed_ms", "duration_ms"),
            "failure_reason" to if (success == true) {
                null
            } else {
                firstNonBlank(
                    source["failure_reason"],
                    result["failure_reason"],
                    result["error_code"],
                    result["errorCode"],
                    result["error_message"],
                    result["error"],
                ).takeIf { it.isNotBlank() }
            },
        ).filterValues { it != null && it.toString().isNotBlank() }
        return compact.takeIf { it.isNotEmpty() }
    }

    private fun firstPresent(
        primary: Map<String, Any?>,
        secondary: Map<String, Any?>,
        vararg keys: String,
    ): Any? {
        keys.forEach { key ->
            primary[key]?.let { return it }
        }
        keys.forEach { key ->
            secondary[key]?.let { return it }
        }
        return null
    }

    private fun Map<String, Any?>.withRuntimeResolveCalls(extraResolveCalls: Int): Map<String, Any?> {
        if (extraResolveCalls <= 0) return this
        val normalized = linkedMapOf<String, Any?>().apply { putAll(this@withRuntimeResolveCalls) }
        val existingTopLevel = numberValue(normalized["resolve_calls"])
            ?: numberValue(normalized["runtime_resolve_calls"])
            ?: 0
        val summary = mapValue(normalized["execution_summary"]).toMutableMap()
        val existingSummary = numberValue(summary["resolve_calls"])
            ?: numberValue(summary["runtime_resolve_calls"])
            ?: 0
        val resolveCalls = maxOf(existingTopLevel, existingSummary) + extraResolveCalls
        normalized["resolve_calls"] = resolveCalls
        if (summary.isNotEmpty()) {
            summary["resolve_calls"] = resolveCalls
            normalized["execution_summary"] = summary
        }
        return normalized
    }

    private fun numberValue(value: Any?): Int? =
        when (value) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }

    private fun isSummaryMessage(taskId: String): Boolean {
        return taskId.lowercase().startsWith("vlm-summary-")
    }

    private fun extractSummaryText(content: String): String? {
        val trimmed = content.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return null
        }
        return try {
            val json = JSONObject(trimmed)
            json.optString("text", "").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildScreenLockedPrompt(state: TaskState, isInitial: Boolean): String {
        return if (isInitial) {
            """设备当前处于锁屏或熄屏状态，VLM 任务暂时无法开始。请先让用户解锁手机，然后重新继续任务。""".trimIndent()
        } else {
            """设备在执行过程中进入锁屏或熄屏状态。请先让用户解锁手机，然后继续当前任务。""".trimIndent()
        }
    }

    private suspend fun notifySummarySheetReadyWithRetry(): Boolean {
        var notified = AssistsUtil.Core.notifySummarySheetReady()
        if (notified) return true
        repeat(3) {
            delay(300L)
            notified = AssistsUtil.Core.notifySummarySheetReady()
            if (notified) return true
        }
        return false
    }

}
