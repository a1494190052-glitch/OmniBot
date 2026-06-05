package cn.com.omnimind.bot.vlm

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import cn.com.omnimind.accessibility.util.ScreenStateUtil
import cn.com.omnimind.assists.api.bean.VlmTaskTerminalResult
import cn.com.omnimind.assists.api.interfaces.OnMessagePushListener
import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
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
import cn.com.omnimind.assists.task.vlmserver.PressBackAction
import cn.com.omnimind.assists.task.vlmserver.PressHomeAction
import cn.com.omnimind.assists.task.vlmserver.RecordAction
import cn.com.omnimind.assists.task.vlmserver.RequireUserChoiceAction
import cn.com.omnimind.assists.task.vlmserver.RequireUserConfirmationAction
import cn.com.omnimind.assists.task.vlmserver.ScrollAction
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
import cn.com.omnimind.bot.mcp.TaskState
import cn.com.omnimind.bot.mcp.TaskStatus
import cn.com.omnimind.bot.mcp.VlmTaskRequest
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
                }
            )
        ),
    )
}

typealias VlmToolProgressReporter = suspend (progress: String, extras: Map<String, Any?>) -> Unit

data class VlmFunctionCacheGateDecision(
    val allowed: Boolean,
    val reason: String,
    val functionId: String? = null,
)

object VlmToolCoordinator {
    private const val TAG = "[VlmToolCoordinator]"
    private const val SUMMARY_WAIT_GRACE_MS = 20_000L
    private const val PRELAUNCH_OBSERVE_DELAY_MS = 700L
    private const val RECALL_OBSERVE_ATTEMPTS = 8
    private const val RECALL_OBSERVE_INTERVAL_MS = 250L
    private const val MIN_WAIT_TIMEOUT_MS = 30_000L
    private const val MAX_WAIT_TIMEOUT_MS = 600_000L
    private const val DEFAULT_MAX_STEPS = 12
    private const val MAX_MAX_STEPS = 64
    private const val DRY_RUN_PROMPT_PREVIEW_CHARS = 6000
    internal const val CACHE_GATE_AUTO_EXECUTE_DISABLED = "auto_execute_disabled"
    internal const val CACHE_GATE_REQUIRES_ARGUMENTS = "requires_arguments"
    internal const val CACHE_GATE_NO_STRICT_HIT = "no_strict_hit"
    internal const val CACHE_GATE_STRICT_HIT = "strict_hit_no_arguments"

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
                    "召回增强",
                    mapOf(
                    "summary" to "已准备 OmniFlow 诊断；在线 VLM 每轮 fresh observe 后注入 Function recall 与 UDEG page skill",
                    "omniflowRecallDecision" to recallGuidance.decision,
                    "omniflowRecall" to recallGuidance.payload,
                )
            )
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
            dynamicToolDefinitions = emptyList()
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
            currentUserTextPreview = requestEnvelope.currentUserText.take(DRY_RUN_PROMPT_PREVIEW_CHARS),
            pageDiagnostics = workingContext.pageDiagnostics,
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
        var summaryWaitStart: Long? = null
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
                    if (state.needSummary && state.summaryText.isNullOrBlank() && !state.summaryUnavailable) {
                        if (summaryWaitStart == null) {
                            summaryWaitStart = System.currentTimeMillis()
                        }
                        if (System.currentTimeMillis() - summaryWaitStart < SUMMARY_WAIT_GRACE_MS) {
                            delay(McpTaskManager.POLL_INTERVAL_MS)
                            continue
                        }
                        state.summaryUnavailable = true
                        state.markStateChanged()
                    }
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
                delay(PRELAUNCH_OBSERVE_DELAY_MS)
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
            allowDirectExecutionDecision = false,
        )
        return guidance to observedRequest
    }

    private data class RecallObservation(
        val packageName: String?,
        val xml: String?,
    )

    private suspend fun waitForRecallObservation(): RecallObservation {
        var lastPackage: String? = null
        var lastXml: String? = null
        repeat(RECALL_OBSERVE_ATTEMPTS) { attempt ->
            val currentPackage = runCatching { AccessibilityController.getPackageName() }.getOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            val currentXml = runCatching { AccessibilityController.getCaptureScreenShotXml(true) }.getOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            if (currentPackage != null) lastPackage = currentPackage
            if (currentXml != null) lastXml = currentXml
            if (currentXml != null) {
                return RecallObservation(currentPackage, currentXml)
            }
            if (attempt < RECALL_OBSERVE_ATTEMPTS - 1) {
                delay(RECALL_OBSERVE_INTERVAL_MS)
            }
        }
        return RecallObservation(lastPackage, lastXml)
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
        runFunction: suspend (String) -> Map<String, Any?>,
    ): VlmToolOutcome? {
        val functionId = recallGuidance.directHitFunctionId?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        if (recallHitRequiresArguments(recallGuidance)) {
            taskState.addChatMessage(
                "[SYSTEM] OmniFlow recall hit $functionId requires arguments; skipping local auto-execute and letting VLM choose the recalled tool with arguments."
            )
            return null
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
            )
        )
        val result = runCatching { runFunction(functionId) }.getOrElse { error ->
            linkedMapOf<String, Any?>(
                "success" to false,
                "fallback" to true,
                "error" to error.message.orEmpty(),
                "error_type" to error.javaClass.name,
            )
        }
        val success = result["success"] == true && result["fallback"] != true
        if (!success) {
            val fallbackGuidance = buildRecallFallbackGuidance(
                functionId = functionId,
                result = result
            )
            if (fallbackGuidance.isNotBlank()) {
                val currentRequest = taskState.vlmRequest ?: VlmTaskRequest(
                    goal = goal,
                    needSummary = taskState.needSummary
                )
                taskState.vlmRequest = currentRequest.withRecallGuidance(fallbackGuidance)
            }
            taskState.executionRoute = "vlm_with_omniflow_recall_fallback:${recallGuidance.decision}"
            taskState.addChatMessage(
                "[SYSTEM] OmniFlow recall hit $functionId could not complete locally; continuing with VLM. " +
                    "reason=${recallFallbackReason(result)}; latest page recovery injected=${fallbackGuidance.isNotBlank()}"
            )
            taskState.markStateChanged()
            emitProgress(
                progressReporter,
                taskState.taskId,
                taskState.status,
                "召回回退",
                mapOf(
                    "summary" to "召回 Function 未能本地完成，继续视觉执行",
                    "function_id" to functionId,
                    "omniflowRecallResult" to result,
                    "omniflowRecallFallbackGuidanceInjected" to fallbackGuidance.isNotBlank(),
                )
            )
            return null
        }

        val runId = result["run_id"]?.toString()?.trim().orEmpty()
        val actionsExecuted = result["actions_executed"]?.toString()?.trim().orEmpty()
        val message = buildString {
            append("已通过召回 Function 完成: ")
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
                "omniflowRecallResult" to result,
            )
        )
        return taskState.toOutcome(VlmToolOutcomeStatus.FINISHED)
    }

    internal suspend fun tryExecuteRecallHitIfAllowed(
        request: VlmTaskRequest,
        taskState: TaskState,
        recallGuidance: VlmRecallGuidance,
        progressReporter: VlmToolProgressReporter,
        runFunction: suspend (String) -> Map<String, Any?>,
    ): VlmToolOutcome? {
        val gate = evaluateFunctionCacheGate(request, recallGuidance)
        if (!gate.allowed) {
            if (gate.reason == CACHE_GATE_REQUIRES_ARGUMENTS && !gate.functionId.isNullOrBlank()) {
                taskState.addChatMessage(
                    "[SYSTEM] OmniFlow recall hit ${gate.functionId} requires arguments; " +
                        "skipping local auto-execute and letting VLM choose the recalled tool with arguments."
                )
            }
            return null
        }
        return tryExecuteRecallHit(
            taskState = taskState,
            goal = request.goal,
            recallGuidance = recallGuidance,
            progressReporter = progressReporter,
            runFunction = runFunction,
        )
    }

    internal fun evaluateFunctionCacheGate(
        request: VlmTaskRequest,
        recallGuidance: VlmRecallGuidance,
    ): VlmFunctionCacheGateDecision {
        val hit = mapValue(recallGuidance.payload["hit"])
        val candidateFunctionId = firstNonBlank(
            recallGuidance.directHitFunctionId,
            hit["function_id"],
            recallGuidance.payload["function_id"],
        ).takeIf { it.isNotBlank() }
        if (!request.allowOmniFlowFunctionAutoExecute) {
            return VlmFunctionCacheGateDecision(
                allowed = false,
                reason = CACHE_GATE_AUTO_EXECUTE_DISABLED,
                functionId = candidateFunctionId,
            )
        }
        if (candidateFunctionId != null && recallHitRequiresArguments(recallGuidance)) {
            return VlmFunctionCacheGateDecision(
                allowed = false,
                reason = CACHE_GATE_REQUIRES_ARGUMENTS,
                functionId = candidateFunctionId,
            )
        }
        val strictFunctionId = recallGuidance.directHitFunctionId?.trim()?.takeIf { it.isNotEmpty() }
            ?: return VlmFunctionCacheGateDecision(
                allowed = false,
                reason = CACHE_GATE_NO_STRICT_HIT,
                functionId = candidateFunctionId,
            )
        return VlmFunctionCacheGateDecision(
            allowed = true,
            reason = CACHE_GATE_STRICT_HIT,
            functionId = strictFunctionId,
        )
    }

    private fun buildRecallFallbackGuidance(
        functionId: String,
        result: Map<String, Any?>,
    ): String {
        val failedStep = findFailedRecallStep(result)
        val recovery = findRecoveryPayload(result)
        val packageName = firstNonBlank(recovery?.get("effective_package"), recovery?.get("package_name"))
        val activityName = firstNonBlank(recovery?.get("activity_name"))
        val reason = recallFallbackReason(result)
        return buildString {
            append("OmniFlow 本地重放未完成，下一步必须回到 VLM 视觉执行。")
            append("\n失败 Function：").append(functionId)
            append("\n失败原因：").append(reason)
            if (failedStep != null) {
                append("\n失败步骤：")
                append(firstNonBlank(failedStep["step_id"], failedStep["index"], failedStep["tool"]).ifBlank { "unknown" })
                firstNonBlank(failedStep["tool"]).takeIf { it.isNotBlank() }?.let {
                    append(" tool=").append(it)
                }
                firstNonBlank(failedStep["summary"], failedStep["error_message"], failedStep["omniflow_fail_reason"])
                    .takeIf { it.isNotBlank() }
                    ?.let { append(" summary=").append(it.take(500)) }
            }
            if (packageName.isNotBlank()) append("\n失败后重新获取的当前包名：").append(packageName)
            if (activityName.isNotBlank()) append("\n失败后重新获取的 Activity：").append(activityName)
            val xmlChars = recovery?.get("observation_xml")?.toString()?.length ?: 0
            append("\n失败后页面已重新观测：raw_xml_available=").append(xmlChars > 0)
            append(" raw_xml_chars=").append(xmlChars)
            append(" raw_xml_model_visible=false")
            append("\n请基于最新截图和 compact indexed evidence 重新判断下一步，不要复用失败重放里的旧坐标、旧页面或旧节点。")
        }
    }

    private fun findFailedRecallStep(result: Map<String, Any?>): Map<String, Any?>? =
        findFailedStepInAny(result)

    private fun findFailedStepInAny(raw: Any?): Map<String, Any?>? {
        val map = mapValue(raw)
        if (map.isEmpty()) return null
        val nested = listOf("step_results", "steps", "path")
            .asSequence()
            .flatMap { key -> listValue(map[key]).asReversed().asSequence() }
            .mapNotNull { findFailedStepInAny(it) }
            .firstOrNull()
        if (nested != null) return nested
        listOf("oob_result", "result", "nested_run")
            .asSequence()
            .mapNotNull { key -> findFailedStepInAny(map[key]) }
            .firstOrNull()
            ?.let { return it }
        return map.takeIf { it["success"] == false }
    }

    private fun findRecoveryPayload(result: Map<String, Any?>): Map<String, Any?>? {
        val failedStepRecovery = mapValue(findFailedRecallStep(result)?.get("recovery"))
            .takeIf { it.isNotEmpty() }
        if (failedStepRecovery != null) return failedStepRecovery
        return findRecoveryInAny(result)
    }

    private fun findRecoveryInAny(raw: Any?): Map<String, Any?>? {
        val map = mapValue(raw)
        if (map.isEmpty()) return null
        mapValue(map["recovery"]).takeIf { it.isNotEmpty() }?.let { return it }
        listOf("step_results", "steps", "path")
            .asSequence()
            .flatMap { key -> listValue(map[key]).asReversed().asSequence() }
            .mapNotNull { findRecoveryInAny(it) }
            .firstOrNull()
            ?.let { return it }
        return listOf("oob_result", "result", "nested_run")
            .asSequence()
            .mapNotNull { key -> findRecoveryInAny(map[key]) }
            .firstOrNull()
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
        val explicit = when (raw) {
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            is String -> raw.trim().lowercase() in setOf("true", "1", "yes", "y")
            else -> false
        }
        if (explicit) return true
        val schema = mapValue(candidate["inputSchema"]).ifEmpty { mapValue(candidate["input_schema"]) }
        if (schema.isEmpty()) return false
        val required = listValue(schema["required"])
            .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
        if (required.isNotEmpty()) return true
        return mapValue(schema["properties"]).isNotEmpty()
    }

    private fun firstNonBlank(vararg values: Any?): String =
        values.firstNotNullOfOrNull { value ->
            value?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }.orEmpty()

    private fun recallFallbackReason(result: Map<String, Any?>): String =
        listOf(
            result["error"],
            result["error_message"],
            (result["control"] as? Map<*, *>)?.get("fallback_reason"),
            result["phase"],
        ).firstNotNullOfOrNull { value ->
            value?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        } ?: "unknown"

    private fun UIAction.toDebugMap(): Map<String, Any?> =
        when (this) {
            is ClickAction -> linkedMapOf(
                "tool" to name,
                "target_description" to targetDescription,
                "element_index" to elementIndex,
                "node_id" to nodeId,
                "x" to x,
                "y" to y,
            )
            is InputTextAction -> linkedMapOf(
                "tool" to name,
                "target_description" to targetDescription,
                "text" to text,
                "element_index" to elementIndex,
                "node_id" to nodeId,
                "x" to x,
                "y" to y,
            )
            is ScrollAction -> linkedMapOf(
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
                "element_index" to elementIndex,
                "node_id" to nodeId,
                "x" to x,
                "y" to y,
            )
            is OpenAppAction -> linkedMapOf("tool" to name, "package_name" to packageName)
            is PressHomeAction -> linkedMapOf("tool" to name)
            is PressBackAction -> linkedMapOf("tool" to name)
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
        )
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

    private fun VlmTaskRequest.withRecallGuidance(guidance: String): VlmTaskRequest {
        val trimmed = guidance.trim()
        if (trimmed.isEmpty()) return this
        val existing = stepSkillGuidance.trim()
        return copy(
            stepSkillGuidance = listOf(existing, trimmed)
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
        )
    }
}
