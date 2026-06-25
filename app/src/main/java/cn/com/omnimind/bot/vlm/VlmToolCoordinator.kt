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
import cn.com.omnimind.assists.task.vlmserver.AbortAction
import cn.com.omnimind.assists.task.vlmserver.ClickAction
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
import cn.com.omnimind.assists.task.vlmserver.SwipeAction
import cn.com.omnimind.assists.task.vlmserver.UIAction
import cn.com.omnimind.assists.task.vlmserver.UIContext
import cn.com.omnimind.assists.task.vlmserver.budgetDiagnostics
import cn.com.omnimind.assists.task.vlmserver.VLMClient
import cn.com.omnimind.assists.task.vlmserver.VLMConversationState
import cn.com.omnimind.assists.task.vlmserver.VLMCurrentPageSnapshot
import cn.com.omnimind.assists.task.vlmserver.VLMIndexedPageContext
import cn.com.omnimind.assists.task.vlmserver.VLMRecallContextProviderRegistry
import cn.com.omnimind.assists.task.vlmserver.VLMRecallContextRequest
import cn.com.omnimind.assists.task.vlmserver.VLMStreamClient
import cn.com.omnimind.assists.task.vlmserver.WaitAction
import cn.com.omnimind.baselib.util.ImageQuality
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.mcp.McpTaskManager
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

object VlmToolCoordinator {
    private const val TAG = "[VlmToolCoordinator]"
    private const val MAX_MAX_STEPS = 64

    private val mainHandler = Handler(Looper.getMainLooper())

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
        val config = loadConfig(context)
        val boundedRequest = request.withRuntimeDefaults(config)

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

        val executionRequest = prepareFastStartupRequest(boundedRequest, taskState)

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
            returnOnWaitingInput = returnOnWaitingInput,
            config = config,
        )
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
            state.addChatMessage("[SYSTEM] VLM task cancelled")
            state.markStateChanged()
        }
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
            state.addChatMessage("[SYSTEM] VLM task completed by user")
            state.markStateChanged()
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
        val config = loadConfig(context)
        val boundedRequest = request.withRuntimeDefaults(config)
        val snapshot = captureParseOnlySnapshot(context)
        markPhase("read_current_page_ms", captureStartedAt)
        val baseContext = UIContext(
            overallTask = boundedRequest.goal,
            currentStepGoal = boundedRequest.goal,
            stepSkillGuidance = boundedRequest.stepSkillGuidance,
            targetPackageName = boundedRequest.packageName.orEmpty(),
            currentPackageName = snapshot.packageName.orEmpty(),
            displayWidth = snapshot.displayWidth,
            displayHeight = snapshot.displayHeight,
            maxSteps = boundedRequest.maxSteps ?: config.vlmDefaultMaxSteps,
            stepsUsed = 0,
        )
        val result = parseOnlyNextAction(
            context = baseContext,
            snapshot = snapshot,
            config = config,
            model = boundedRequest.model ?: config.primaryModel,
            streamClient = streamClient,
            disableOmniFlowRecall = boundedRequest.disableOmniFlowRecall,
            phaseMs = phaseMs,
        )
        phaseMs["duration_ms"] = System.currentTimeMillis() - phaseStartedAt
        result.copy(phaseMs = phaseMs.toMap())
    }

    internal suspend fun parseOnlyNextAction(
        context: UIContext,
        snapshot: VLMCurrentPageSnapshot,
        model: String = VlmWorkspaceConfig.defaultSnapshot().primaryModel,
        streamClient: VLMStreamClient,
        conversationState: VLMConversationState = VLMConversationState(),
        vlmClient: VLMClient = VLMClient(),
        disableOmniFlowRecall: Boolean = false,
        phaseMs: MutableMap<String, Long> = linkedMapOf(),
        config: VlmWorkspaceConfig.Snapshot = VlmWorkspaceConfig.defaultSnapshot(),
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
            displayHeight = snapshot.displayHeight,
            currentPackageName = snapshot.packageName.orEmpty(),
        )
        workingContext = timed("indexed_evidence_ms") {
            VLMIndexedPageContext.enrich(
                context = workingContext,
                currentXml = snapshot.xml,
                displayWidth = snapshot.displayWidth,
                displayHeight = snapshot.displayHeight,
            )
        }
        workingContext = timed("recall_context_ms") {
            VLMRecallContextProviderRegistry.enrich(
                VLMRecallContextRequest(
                    context = workingContext,
                    currentXml = snapshot.xml,
                    currentPackageName = snapshot.packageName,
                    screenshotBase64 = snapshot.screenshotBase64,
                    stepIndex = 0,
                    snapshot = snapshot,
                    disableOmniFlowRecall = disableOmniFlowRecall,
                )
            )
        }
        val contextBudgetDiagnostics = workingContext.budgetDiagnostics()
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
                dynamicFunctionToolMappings = requestEnvelope.dynamicFunctionToolMappings,
            )
        }
        val action = parsed.step?.action
        val thinking = parsed.thinking
        val responseContentPreview = thinking?.rawContent
            .orEmpty()
            .ifBlank { thinking?.reasoning.orEmpty() }
            .trim()
            .take(4000)
        val requestDiagnostics = linkedMapOf(
            "vlm_request_has_tools" to requestEnvelope.request.tools.isNotEmpty().toString(),
            "vlm_request_tool_choice" to requestEnvelope.request.toolChoice?.toString().orEmpty(),
            "vlm_request_parallel_tool_calls" to requestEnvelope.request.parallelToolCalls?.toString().orEmpty(),
            "vlm_request_tool_count" to requestEnvelope.toolNames.size.toString(),
            "vlm_request_tool_names" to requestEnvelope.toolNames.joinToString(",").take(4000),
            "vlm_request_default_tool_count" to requestEnvelope.defaultToolCount.toString(),
            "vlm_request_selected_base_tool_names" to requestEnvelope.selectedBaseToolNames.joinToString(",").take(4000),
            "vlm_request_dynamic_function_tool_count" to requestEnvelope.dynamicFunctionToolNames.size.toString(),
            "vlm_request_dynamic_function_tool_names" to requestEnvelope.dynamicFunctionToolNames.joinToString(",").take(4000),
            "vlm_request_dynamic_function_mapping_count" to requestEnvelope.dynamicFunctionToolMappings.size.toString(),
            "vlm_request_system_prompt_chars" to requestEnvelope.systemPromptChars.toString(),
            "vlm_request_current_user_text_chars" to requestEnvelope.currentUserTextChars.toString(),
        )
        val responseDiagnostics = linkedMapOf(
            "vlm_stream_request_variant" to turn.requestVariant.orEmpty(),
            "vlm_stream_request_had_tools" to turn.requestHadTools?.toString().orEmpty(),
            "vlm_stream_request_tool_choice" to turn.requestToolChoice.orEmpty(),
            "vlm_stream_request_parallel_tool_calls" to turn.requestParallelToolCalls?.toString().orEmpty(),
            "vlm_response_route" to turn.route.orEmpty(),
            "vlm_response_resolved_model" to turn.resolvedModel,
            "vlm_response_finish_reason" to turn.turn.finishReason.orEmpty(),
            "vlm_response_tool_call_count" to (turn.turn.message.toolCalls?.size ?: 0).toString(),
            "vlm_response_tool_names" to turn.turn.message.toolCalls
                .orEmpty()
                .map { it.function.name }
                .joinToString(",")
                .take(4000),
        ).apply {
            if (responseContentPreview.isNotBlank()) {
                this["vlm_response_raw_content_preview"] = responseContentPreview
            }
        }
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
            currentUserTextPreview = requestEnvelope.currentUserText
                .take(config.vlmDryRunPromptPreviewChars),
            pageDiagnostics = recalledFunctionDiagnostics(requestEnvelope.dynamicFunctionToolNames) +
                workingContext.pageDiagnostics +
                contextBudgetDiagnostics +
                requestDiagnostics +
                responseDiagnostics,
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
        val config = loadConfig(context)
        val startTime = System.currentTimeMillis()
        emitProgress(
            progressReporter,
            taskId,
            TaskStatus.SCREEN_LOCKED,
            "等待解锁",
            mapOf("summary" to "等待用户解锁设备")
        )
        val waitTimeoutMs = resolveWaitTimeoutMs(taskState.vlmRequest?.waitTimeoutMs, config)
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
                val boundedRequest = request.withRuntimeDefaults(config)
                taskState.vlmRequest = boundedRequest
                val executionRequest = prepareFastStartupRequest(boundedRequest, taskState)
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
                    waitTimeoutMs = executionRequest.waitTimeoutMs,
                    config = config,
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
        config: VlmWorkspaceConfig.Snapshot = VlmWorkspaceConfig.defaultSnapshot(),
    ): VlmToolOutcome {
        val startWaitTime = System.currentTimeMillis()
        val resolvedWaitTimeoutMs = resolveWaitTimeoutMs(waitTimeoutMs, config)
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

    internal fun resolveWaitTimeoutMs(
        requestedWaitTimeoutMs: Long?,
        config: VlmWorkspaceConfig.Snapshot = VlmWorkspaceConfig.defaultSnapshot(),
    ): Long {
        val requested = requestedWaitTimeoutMs?.takeIf { it > 0L }
            ?: return config.vlmMaxWaitTimeoutMs
        return requested.coerceIn(config.vlmMinWaitTimeoutMs, config.vlmMaxWaitTimeoutMs)
    }

    internal fun resolveMaxSteps(
        requestedMaxSteps: Int?,
        config: VlmWorkspaceConfig.Snapshot = VlmWorkspaceConfig.defaultSnapshot(),
    ): Int {
        val requested = requestedMaxSteps?.takeIf { it > 0 }
            ?: return config.vlmDefaultMaxSteps.coerceIn(1, MAX_MAX_STEPS)
        return requested.coerceIn(1, MAX_MAX_STEPS)
    }

    private fun loadConfig(context: Context): VlmWorkspaceConfig.Snapshot =
        VlmWorkspaceConfig.getInstance(context)
            .also { it.initialize() }
            .get()

    private fun VlmTaskRequest.withRuntimeDefaults(
        config: VlmWorkspaceConfig.Snapshot
    ): VlmTaskRequest = copy(
        model = model?.trim()?.takeIf { it.isNotEmpty() } ?: config.primaryModel,
        maxSteps = resolveMaxSteps(maxSteps, config),
        waitTimeoutMs = resolveWaitTimeoutMs(waitTimeoutMs, config),
    )

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

    internal fun prepareFastStartupRequest(
        request: VlmTaskRequest,
        taskState: TaskState,
    ): VlmTaskRequest {
        taskState.vlmRequest = request
        taskState.omniflowRecall = startupDeferredRecallPayload(request)
        taskState.executionRoute = "vlm"
        taskState.markStateChanged()
        return request
    }

    private fun startupDeferredRecallPayload(request: VlmTaskRequest): Map<String, Any?> =
        linkedMapOf(
            "success" to false,
            "decision" to if (request.disableOmniFlowRecall) "disabled" else "deferred",
            "pre_run_recall_skipped" to true,
            "reason" to if (request.disableOmniFlowRecall) {
                "request_disable_omniflow_recall"
            } else {
                "startup_fast_path_deferred_to_vlm_step"
            },
        )

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
            is AbortAction -> linkedMapOf("tool" to name, "value" to value)
            is WaitAction -> linkedMapOf("tool" to name, "duration_ms" to durationMs)
            is RecordAction -> linkedMapOf("tool" to name, "content" to content)
        }.filterValues { it != null }

    private fun recalledFunctionDiagnostics(functionNames: Collection<String>): Map<String, String> {
        val names = functionNames.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
        if (names.isEmpty()) return emptyMap()
        return linkedMapOf(
            "omniflow_recalled_function_count" to names.size.toString(),
            "omniflow_recalled_function_tool_names" to names.joinToString(","),
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
