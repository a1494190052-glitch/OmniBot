package cn.com.omnimind.assists.task.vlmserver

import android.content.Context
import cn.com.omnimind.assists.TaskManager
import cn.com.omnimind.assists.api.bean.VlmTaskTerminalResult
import cn.com.omnimind.assists.api.bean.VlmTaskTerminalStatus
import cn.com.omnimind.assists.api.enums.TaskFinishType
import cn.com.omnimind.assists.api.enums.TaskType
import cn.com.omnimind.assists.api.interfaces.OnMessagePushListener
import cn.com.omnimind.assists.api.interfaces.VlmStepProgress
import cn.com.omnimind.assists.api.interfaces.TaskChangeListener
import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.assists.runlog.OmniFlowRecordStepExecutor
import cn.com.omnimind.assists.task.Task
import cn.com.omnimind.assists.api.eventapi.ExecutionTaskEventApi
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.baselib.http.Http429Exception
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.runlog.RunLogStepRecord
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.baselib.util.exception.PrivacyBlockedException
import cn.com.omnimind.omniintelligence.models.AgentRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 视觉模型执行任务
 */
open class VLMOperationTask(
    open val executionTaskEventApi: ExecutionTaskEventApi?,
    override val taskChangeListener: TaskChangeListener,
    private val onMessagePushListener: OnMessagePushListener? = null,
    private val needSummary: Boolean = false,
    override val taskManager: TaskManager,
    private val functionRunExecutor: FunctionRunExecutor? = null,
    private val controlActExecutorFactory: ControlActExecutorFactory,
    private val recordStepExecutor: OmniFlowRecordStepExecutor,
) : Task(taskChangeListener,taskManager), DeviceOperator {
    private val Tag = "VLMOperationTask"
    private companion object {
        private const val SUMMARY_GENERATION_TIMEOUT_MS = 20_000L
        private const val CANCELLATION_CAPTURE_TIMEOUT_MS = 1_500L
        private const val MAX_MANUAL_TRACE_MEMORY_ACTIONS = 12
        private const val MAX_MANUAL_TRACE_PARAM_CHARS = 240
    }

    private lateinit var vlmOperationService: VLMOperationService
    private lateinit var androidDeviceOperator: AndroidDeviceOperator
    private lateinit var onTaskFinishListener: () -> Unit?
    
    @Volatile
    private var _isCancellationRequested: Boolean = false
    val isCancellationRequested: Boolean
        get() = _isCancellationRequested
    
    private var executionRecordId: Long = -1L
    private var isSubTask: Boolean = false

    @Volatile
    private var pauseRequested: Boolean = false
    private lateinit var streamClient: VLMStreamClient

    private var taskContext: Context? = null
    private val terminalFinalized = AtomicBoolean(false)
    private val runLogStateLock = Any()
    private val pendingVlmRunLogSteps = linkedMapOf<String, PendingVlmRunLogStep>()
    private val runLogStepIndexById = linkedMapOf<String, Int>()
    @Volatile
    private var inFlightVlmRunLogStep: PendingVlmRunLogStep? = null

    private val userInputChannel = Channel<String>(Channel.Factory.UNLIMITED)
    private val manualTakeoverController = ManualTakeoverController()
    private val summarySheetReadyChannel = Channel<Unit>(Channel.Factory.CONFLATED)

    private var goal: String? = null
    private var taskStartTime = 0L

    fun appendExternalMemory(memory: String): Boolean {
        val trimmed = memory.trim()
        if (trimmed.isEmpty()) return false
        if (!this::vlmOperationService.isInitialized) return false
        vlmOperationService.addExternalMemory(trimmed)
        return true
    }

    /**
     * Append a priority event to the VLM task
     * @param memory The event message
     * @param eventType The event type (e.g., "file_received")
     * @param suggestCompletion Whether to suggest VLM complete the task
     */
    fun appendPriorityEvent(memory: String, eventType: String, suggestCompletion: Boolean = false): Boolean {
        val trimmed = memory.trim()
        if (trimmed.isEmpty()) return false
        if (!this::vlmOperationService.isInitialized) return false
        vlmOperationService.addPriorityEvent(trimmed, eventType, suggestCompletion)
        return true
    }

    override suspend fun onTaskCreated() {
        super.onTaskCreated()
        streamClient = HttpVLMStreamClient(scope = taskScope)
        vlmOperationService = VLMOperationService(
            this,
            streamClient,
            onInfoAction = { question ->
                handleInfoAction(question)
            },
            onPauseCheck = {
                checkAndHandlePause()
            },
            onStepStarted = { stepIndex, step ->
                handleVlmStepStarted(stepIndex, step)
            },
            onStepWaitingUser = { stepIndex, step ->
                handleVlmStepWaitingUser(stepIndex, step)
            },
            onStepCompleted = { stepIndex, step, success, error ->
                handleVlmStepCompleted(stepIndex, step, success, error)
            },
            isSubTask = isSubTask,
            taskId = id,
            runId = id,
            runLogStepsProvider = {
                taskContext?.let { context ->
                    InternalRunLogStore.getRun(context, id)?.steps
                }.orEmpty()
            },
            functionRunExecutor = functionRunExecutor,
            controlActExecutor = controlActExecutorFactory.create(this),
        )
        androidDeviceOperator = AndroidDeviceOperator(executionTaskEventApi, taskContext)
    }

    /**
     * 处理INFO动作：小猫显示提示信息，用户在当前页面操作，操作完成后点击小猫继续
     */
    private suspend fun handleInfoAction(question: String): String {
        OmniLog.d(Tag, "INFO动作触发，向用户推送问题：$question")
        var mQuestion = if (question.isNotEmpty()) {
            "\n${question}"
        } else {
            question
        }
        val infoMessage = "小万需要你的帮助：$mQuestion"
        AccessibilityController.restoreKeyboard()

        onTaskStop(TaskFinishType.WAITING_INPUT, infoMessage)
        notifyTerminalResult(
            VlmTaskTerminalResult(
                status = VlmTaskTerminalStatus.WAITING_INPUT,
                message = infoMessage,
                needSummary = needSummary,
                waitingQuestion = infoMessage
            )
        )

        if (onMessagePushListener != null) {
            try {
                onMessagePushListener.onVLMRequestUserInput(infoMessage)
                OmniLog.d(Tag, "已通知Flutter层")
            } catch (e: Exception) {
                OmniLog.e(Tag, "通知UI层失败: ${e.message}")
            }
        }

        OmniLog.d(Tag, "等待用户完成操作并点击继续...")
        val userConfirmation = userInputChannel.receive()
        throwIfCancellationRequested("info_action")
        OmniLog.d(Tag, "收到用户确认：$userConfirmation")

        AccessibilityController.hideKeyboard()
        onTaskStarted()
        taskStartTime = System.currentTimeMillis()
        return "用户已完成操作：$userConfirmation"
    }

    /**
     * 接收用户回复（公开方法，供外部调用）
     */
    fun provideUserInput(input: String) {
        OmniLog.d(Tag, "接收用户输入：$input")
        taskScope.launch {
            userInputChannel.send(input)
        }
    }

    /**
     * 检查并处理用户暂停请求（VLMOperationService每步执行前调用）
     */
    private suspend fun checkAndHandlePause() {
        throwIfCancellationRequested("pause_check")
        if (pauseRequested) {
            OmniLog.d(Tag, "检测到用户暂停请求，进入暂停状态")
            pauseRequested = false // 重置标志
            handleUserPause()
        }
    }

    /**
     * 用户主动暂停任务，等待继续、完成或取消操作。
     */
    private suspend fun handleUserPause() {
        onTaskStop(TaskFinishType.USER_PAUSED, "")
        executionTaskEventApi?.onVlmTaskPaused(this)
        // 不推送按钮卡片，直接通知UI层切换小猫状态
        AccessibilityController.Companion.restoreKeyboard()
        if (onMessagePushListener != null) {
            try {
                onMessagePushListener.onVLMRequestUserInput("已接管控制，请选择继续、完成或取消")
            } catch (e: Exception) {
                OmniLog.e(Tag, "通知UI层失败: ${e.message}")
            }
        }
        val recorder = taskContext?.let { ManualVlmTraceRecorder(it, id) }
        val recorderStarted = recorder?.start() == true
        val resolution: ManualTakeoverResolution
        val manualTraceResult = try {
            resolution = manualTakeoverController.awaitResolution()
            throwIfCancellationRequested("user_pause")
            recorder?.stop()
        } catch (e: Exception) {
            recorder?.stop()
            throw e
        }
        if (recorderStarted && manualTraceResult != null) {
            appendManualTrace(manualTraceResult)
        }
        if (resolution is ManualTakeoverResolution.Complete) {
            throw UserCompletedTaskException(resolution.message)
        }
        AccessibilityController.Companion.hideKeyboard()
        onTaskStarted()
        taskStartTime = System.currentTimeMillis()
    }

    /**
     * 请求暂停任务（公开方法，供UI调用）
     */
    fun requestPause() {
        OmniLog.d(Tag, "收到暂停请求")
        manualTakeoverController.request()
        pauseRequested = true
    }

    /**
     * 从暂停状态恢复（公开方法，供UI调用）
     */
    fun resumeFromPause() {
        OmniLog.d(Tag, "收到继续请求")
        manualTakeoverController.resume()
    }

    fun completeManualTakeover(message: String = "任务已完成"): Boolean {
        OmniLog.d(Tag, "收到人工接管完成请求")
        return manualTakeoverController.complete(message)
    }

    /**
     * 通知总结Sheet已准备就绪（公开方法，供外部调用）
     * ChatBotSheet加载上下文后会调用此方法
     */
    fun notifySummarySheetReady() {
        OmniLog.d(Tag, "收到总结Sheet准备就绪通知")
        taskScope.launch {
            summarySheetReadyChannel.send(Unit)
        }
    }

    private fun notifyTerminalResult(result: VlmTaskTerminalResult) {
        try {
            onMessagePushListener?.onVlmTaskResult(result)
        } catch (e: Exception) {
            OmniLog.e(Tag, "通知VLM终态结果失败: ${e.message}")
        }
    }

    private fun throwIfCancellationRequested(stage: String) {
        if (_isCancellationRequested) {
            throw CancellationException("任务已取消: $stage")
        }
    }

    private fun unblockWaitingReceivers() {
        userInputChannel.trySend("任务已取消")
        manualTakeoverController.cancel()
        summarySheetReadyChannel.trySend(Unit)
    }

    private fun cancelRunningJob(message: String) {
        val cause = CancellationException(message)
        runCatching { taskJob.cancel(cause) }
        runCatching { taskScope.cancel(cause) }
    }

    private fun finalizeCancellationAsync(message: String = "任务已取消") {
        val context = taskContext
        val runId = id
        if (!terminalFinalized.compareAndSet(false, true)) return
        val inFlightStep = synchronized(runLogStateLock) {
            inFlightVlmRunLogStep.also { inFlightVlmRunLogStep = null }
        }
        cancelScope.launch {
            if (context != null && runId.isNotBlank()) {
                val finalStateId = appendUserCancelledRunLog(
                    context = context,
                    runId = runId,
                    inFlightStep = inFlightStep,
                    message = message,
                )
                InternalRunLogStore.finishRun(
                    context = context,
                    runId = runId,
                    success = false,
                    doneReason = "user_cancelled",
                    errorMessage = message,
                    finalStateId = finalStateId,
                )
            }
            notifyTerminalResult(
                VlmTaskTerminalResult(
                    status = VlmTaskTerminalStatus.CANCELLED,
                    message = message,
                    errorMessage = message,
                    needSummary = needSummary
                )
            )
            onTaskStop(TaskFinishType.CANCEL, message)
            onTaskDestroy()
        }
    }

    private fun launchUserCompletionFinalization(message: String = "任务已完成") {
        val context = taskContext
        val runId = id
        cancelScope.launch {
            if (context != null && runId.isNotBlank()) {
                InternalRunLogStore.finishRun(
                    context = context,
                    runId = runId,
                    success = true,
                    doneReason = "user_completed"
                )
            }
            notifyTerminalResult(
                VlmTaskTerminalResult(
                    status = VlmTaskTerminalStatus.FINISHED,
                    message = message,
                    finishedContent = message,
                    needSummary = needSummary
                )
            )
            onTaskStop(TaskFinishType.FINISH, message)
            onTaskDestroy()
        }
    }

    private suspend fun finalizeTerminalOnce(block: suspend () -> Unit) {
        if (terminalFinalized.compareAndSet(false, true)) {
            block()
        }
    }

    private fun extractFinishedContent(report: TaskExecutionReport): String {
        val finishedStep = report.executionTrace.lastOrNull { it.action is FinishedDecision }
        val fromResult = finishedStep?.result?.trim().orEmpty()
        if (fromResult.isNotEmpty()) return fromResult

        val fromAction = (finishedStep?.action as? FinishedDecision)?.content?.trim().orEmpty()
        if (fromAction.isNotEmpty()) return fromAction

        val lastResult = report.executionTrace.asReversed()
            .mapNotNull { it.result?.trim()?.takeIf { value -> value.isNotEmpty() } }
            .firstOrNull()
        if (!lastResult.isNullOrEmpty()) return lastResult

        return "任务完成"
    }

    private suspend fun appendInternalRunLog(context: Context, report: TaskExecutionReport) {
        val pending = synchronized(runLogStateLock) {
            pendingVlmRunLogSteps.values.toList()
        }
        for (entry in pending) {
            try {
                val runLogStep = buildInternalRunLogStep(
                    index = entry.stepIndex,
                    stepId = entry.stepId,
                    step = entry.step,
                    successOverride = entry.success,
                    errorMessage = entry.errorMessage,
                ) ?: continue
                InternalRunLogStore.upsertRecordedStep(
                    context = context,
                    runId = id,
                    record = runLogStep,
                )
                synchronized(runLogStateLock) {
                    pendingVlmRunLogSteps.remove(entry.stepId)
                }
            } catch (error: Exception) {
                OmniLog.e(Tag, "VLM RunLog final retry failed for ${entry.stepId}: ${error.message}")
                break
            }
        }
        val finalStateId = report.executionTrace.lastOrNull()?.let { step ->
            (step.afterState ?: step.beforeState)?.toRunLogMap()
        }?.let { state -> InternalRunLogStore.persistState(context, state) }
        InternalRunLogStore.finishRun(
            context = context,
            runId = id,
            success = report.success,
            doneReason = report.doneReason ?: if (report.success) "finished" else "error",
            errorMessage = report.error,
            finalStateId = finalStateId,
        )
    }

    private suspend fun appendManualTrace(result: ManualVlmTraceResult) {
        if (result.actions.isEmpty()) return
        val context = taskContext ?: return
        val firstStepIndex = InternalRunLogStore.getRun(context, id)?.steps?.size ?: 0
        val steps = buildList {
            result.actions.forEachIndexed { offset, action ->
                val index = firstStepIndex + offset
                add(
                    ManualRunLogStepRecorder.record(
                        index = index,
                        stepId = "$id-manual-$index",
                        action = action,
                        source = "human_takeover",
                        executor = recordStepExecutor,
                    )
                )
            }
        }
        InternalRunLogStore.appendRecordedSteps(context, id, steps)
        if (this::vlmOperationService.isInitialized) {
            val memory = buildManualTraceMemory(result)
            if (memory.isNotBlank()) {
                vlmOperationService.addPriorityEvent(
                    message = memory,
                    eventType = "human_takeover",
                    suggestCompletion = false
                )
            }
        }
        OmniLog.d(Tag, "已记录人工接管轨迹：${result.actionCount}步")
    }

    private fun buildManualTraceMemory(result: ManualVlmTraceResult): String {
        if (result.actions.isEmpty()) return result.summary
        val actions = result.actions.take(MAX_MANUAL_TRACE_MEMORY_ACTIONS)
        val actionLines = actions.mapIndexed { index, action ->
            val params = action.action.argsMap().entries
                .joinToString(", ") { (key, value) -> "$key=$value" }
                .take(MAX_MANUAL_TRACE_PARAM_CHARS)
            buildString {
                append("${index + 1}. ${action.action.tool}: ")
                append(action.summary.ifBlank { action.title })
                if (params.isNotBlank()) append(" ($params)")
                action.afterPackageName?.takeIf { it.isNotBlank() }
                    ?.let { append(" package=$it") }
            }
        }
        val omitted = result.actions.size - actions.size
        return buildString {
            appendLine(result.summary.ifBlank {
                "用户在接管期间手动完成了 ${result.actionCount} 步操作。请基于当前屏幕继续执行原任务。"
            })
            appendLine("人工接管动作明细：")
            actionLines.forEach { appendLine(it) }
            if (omitted > 0) appendLine("... 另有 $omitted 步人工动作已记录在 RunLog。")
            result.actions.lastOrNull()?.afterPackageName?.takeIf { it.isNotBlank() }?.let {
                appendLine("接管结束包名：$it")
            }
            append("下一步必须以当前截图和最新 Accessibility tree 为准，不要回退到接管前的页面。")
        }.trim()
    }

    private suspend fun handleVlmStepCompleted(
        index: Int,
        step: UIStep,
        success: Boolean,
        errorMessage: String?
    ) {
        val context = taskContext ?: return
        val status = if (success) "succeeded" else "failed"
        val stepId = vlmStepId(index)
        val runningEntry = synchronized(runLogStateLock) {
            inFlightVlmRunLogStep?.takeIf { it.stepId == stepId }
        }
        val runLogIndex = runningEntry?.stepIndex ?: runLogStepIndex(context, stepId)
        val pending = PendingVlmRunLogStep(
            stepId = stepId,
            stepIndex = runLogIndex,
            step = step,
            success = success,
            errorMessage = errorMessage,
        )
        synchronized(runLogStateLock) {
            if (inFlightVlmRunLogStep?.stepId == stepId) {
                inFlightVlmRunLogStep = null
            }
            pendingVlmRunLogSteps[stepId] = pending
        }
        try {
            val runLogStep = buildInternalRunLogStep(
                index = runLogIndex,
                stepId = stepId,
                step = step,
                successOverride = success,
                errorMessage = errorMessage
            )
            if (runLogStep != null) {
                InternalRunLogStore.upsertRecordedStep(
                    context = context,
                    runId = id,
                    record = runLogStep,
                )
            }
            synchronized(runLogStateLock) {
                pendingVlmRunLogSteps.remove(stepId)
            }
            publishVlmStepProgress(index, step, status, errorMessage)
        } catch (error: Exception) {
            OmniLog.e(Tag, "VLM RunLog write failed for $stepId: ${error.message}")
        }
    }

    private suspend fun handleVlmStepStarted(index: Int, step: UIStep) {
        taskContext?.let { context ->
            val stepId = vlmStepId(index)
            val pending = PendingVlmRunLogStep(
                stepId = stepId,
                stepIndex = runLogStepIndex(context, stepId),
                step = step,
                success = false,
                errorMessage = null,
            )
            synchronized(runLogStateLock) {
                inFlightVlmRunLogStep = pending
            }
        }
        publishVlmStepProgress(index, step, "running", null)
    }

    private suspend fun handleVlmStepWaitingUser(index: Int, step: UIStep) {
        publishVlmStepProgress(index, step, "waiting_user", null)
    }

    private suspend fun buildInternalRunLogStep(
        index: Int,
        stepId: String,
        step: UIStep,
        successOverride: Boolean? = null,
        errorMessage: String? = null,
        source: String = "vlm",
    ): RunLogStepRecord? {
        val semantics = resolveVlmRunLogStepSemantics(step, successOverride)
        val action = step.action.toRunLogAction() ?: return null
        val durationMs = if (step.startedAtMs != null && step.finishedAtMs != null) {
            (step.finishedAtMs - step.startedAtMs).coerceAtLeast(0L)
        } else {
            null
        }
        val success = semantics.success
        val tokenUsage = step.tokenUsage?.let(VLMTokenUsageMapper::toRunLogMap)
            ?.takeIf { it.isNotEmpty() }
        val tokenUsageAttempts = step.tokenUsageAttempts
            .map(VLMTokenUsageMapper::toRunLogMap)
            .filter { it.isNotEmpty() }
        val pageDiagnostics = step.pageDiagnostics.takeIf { it.isNotEmpty() }
        val postActionObservation = VLMPostActionObservation.summarize(step)
        val postActionObservationMap = postActionObservation?.toRunLogMap()
        val actionResultData = step.actionResultData?.toRunLogAny()
        val beforeState = step.beforeState ?: return null
        val afterState = step.afterState ?: return null
        val states = listOfNotNull(beforeState, afterState)
            .distinctBy(State::stateId)
            .map { state -> state.toRunLogMap() }
        val result = linkedMapOf<String, Any?>(
            "success" to success,
            "error" to errorMessage?.takeIf(String::isNotBlank),
        ).filterValues { it != null }
        return recordStepExecutor.recordStep(
            RunLogStepRecord(
                step = linkedMapOf(
                    "step_index" to index,
                    "before_state_id" to beforeState.stateId,
                    "action" to action,
                    "result" to result,
                    "after_state_id" to afterState.stateId,
                    "metadata" to linkedMapOf(
                        "step_id" to stepId,
                        "status" to if (success) "succeeded" else "failed",
                        "duration_ms" to durationMs,
                        "started_at_ms" to step.startedAtMs,
                        "finished_at_ms" to step.finishedAtMs,
                        "source" to source,
                        "message" to step.result,
                        "summary" to step.summary.takeIf(String::isNotBlank),
                        "thinking" to step.thought.trim().takeIf { it.isNotEmpty() },
                        "page_diagnostics" to pageDiagnostics,
                        "token_usage" to tokenUsage,
                        "token_usage_attempts" to tokenUsageAttempts.takeIf { it.isNotEmpty() },
                        "post_action_observation" to postActionObservationMap,
                        "action_result_data" to actionResultData,
                        "failure" to step.failure?.toRunLogMap(),
                    ).filterValues { it != null },
                ),
                states = states,
            ),
        )
    }

    private fun VLMCommand.toRunLogAction(): Map<String, Any?>? = when (this) {
        is Action -> linkedMapOf("tool" to tool, "args" to argsMap())
        is Observe -> linkedMapOf("tool" to "get_state", "args" to mapOf("reason" to reason))
        is FunctionInvocation -> linkedMapOf(
            "tool" to "call_tool",
            "args" to linkedMapOf(
                "function_id" to functionId,
                "arguments" to arguments.toRunLogAny(),
            ),
        )
        is FinishedDecision -> linkedMapOf("tool" to "finished", "args" to mapOf("content" to content))
        is InfoDecision -> linkedMapOf("tool" to "info", "args" to mapOf("value" to value))
        is AbortDecision -> linkedMapOf("tool" to "abort", "args" to mapOf("value" to value))
        is RecordMemory -> null
    }

    private suspend fun publishVlmStepProgress(
        index: Int,
        step: UIStep,
        status: String,
        errorMessage: String?,
    ) {
        try {
            onMessagePushListener?.onVlmStepProgress(
                VlmStepProgress(
                    runId = id,
                    stepIndex = index,
                    status = status,
                    thinking = step.thought.trim(),
                    summary = step.summary.trim(),
                    action = step.action.toRunLogAction(),
                    result = if (status == "running") null else {
                        linkedMapOf<String, Any?>(
                            "success" to (status == "succeeded"),
                            "error" to errorMessage?.takeIf(String::isNotBlank),
                        ).filterValues { it != null }
                    },
                    error = errorMessage,
                ),
            )
        } catch (error: Exception) {
            OmniLog.w(Tag, "VLM step progress notification failed: ${error.message}")
        }
    }

    private fun runLogStepIndex(context: Context, stepId: String, runId: String = id): Int {
        return synchronized(runLogStateLock) {
            runLogStepIndexById.getOrPut(stepId) {
                val persistedCount = InternalRunLogStore.getRun(context, runId)?.steps?.size ?: 0
                val reservedCount = runLogStepIndexById.values.maxOrNull()?.plus(1) ?: 0
                maxOf(persistedCount, reservedCount)
            }
        }
    }

    private fun vlmStepId(index: Int): String = "$id-vlm-${index + 1}"

    private suspend fun appendUserCancelledRunLog(
        context: Context,
        runId: String,
        inFlightStep: PendingVlmRunLogStep?,
        message: String,
    ): String {
        val cancelledAtMs = System.currentTimeMillis()
        val cancellationState = captureCancellationState(
            runId = runId,
            fallback = inFlightStep?.step?.beforeState,
            capturedAtMs = cancelledAtMs,
        )
        InternalRunLogStore.persistState(context, cancellationState.toRunLogMap())
        retryPendingVlmRunLogSteps(context, runId)

        inFlightStep?.let { entry ->
            val cancelledStep = entry.step.copy(
                result = message,
                afterState = cancellationState,
                finishedAtMs = cancelledAtMs,
                failure = VLMFailureDiagnostics(
                    kind = "user_cancelled",
                    message = message,
                ),
            )
            runCatching {
                buildInternalRunLogStep(
                    index = entry.stepIndex,
                    stepId = entry.stepId,
                    step = cancelledStep,
                    successOverride = false,
                    errorMessage = message,
                )?.let { record ->
                    InternalRunLogStore.upsertRecordedStep(context, runId, record)
                }
            }.onFailure { error ->
                OmniLog.e(Tag, "VLM cancelled step write failed for ${entry.stepId}: ${error.message}")
            }
        }

        val terminalStepId = "$runId-vlm-user-cancelled"
        val terminalStepIndex = runLogStepIndex(context, terminalStepId, runId)
        val terminalStep = buildUserCancelledTerminalStep(
            state = cancellationState,
            message = message,
            timestampMs = cancelledAtMs,
        )
        runCatching {
            buildInternalRunLogStep(
                index = terminalStepIndex,
                stepId = terminalStepId,
                step = terminalStep,
                successOverride = false,
                errorMessage = message,
                source = "user_cancelled",
            )?.let { record ->
                InternalRunLogStore.upsertRecordedStep(context, runId, record)
            }
        }.onFailure { error ->
            OmniLog.e(Tag, "VLM cancellation terminal step write failed: ${error.message}")
        }
        return cancellationState.stateId
    }

    private suspend fun retryPendingVlmRunLogSteps(context: Context, runId: String) {
        val pending = synchronized(runLogStateLock) {
            pendingVlmRunLogSteps.values.toList()
        }
        pending.forEach { entry ->
            runCatching {
                buildInternalRunLogStep(
                    index = entry.stepIndex,
                    stepId = entry.stepId,
                    step = entry.step,
                    successOverride = entry.success,
                    errorMessage = entry.errorMessage,
                )?.let { record ->
                    InternalRunLogStore.upsertRecordedStep(context, runId, record)
                    synchronized(runLogStateLock) {
                        pendingVlmRunLogSteps.remove(entry.stepId)
                    }
                }
            }.onFailure { error ->
                OmniLog.e(Tag, "VLM pending step retry failed for ${entry.stepId}: ${error.message}")
            }
        }
    }

    private suspend fun captureCancellationState(
        runId: String,
        fallback: State?,
        capturedAtMs: Long,
    ): State {
        val screenshot = if (this::androidDeviceOperator.isInitialized) {
            withTimeoutOrNull(CANCELLATION_CAPTURE_TIMEOUT_MS) {
                runCatching { androidDeviceOperator.captureScreenshot() }.getOrNull()
            }
        } else {
            null
        }
        val xml = if (this::androidDeviceOperator.isInitialized) {
            runCatching { androidDeviceOperator.currentXml() }.getOrNull()
        } else {
            null
        }
        val packageName = if (this::androidDeviceOperator.isInitialized) {
            runCatching { androidDeviceOperator.currentPackageName() }.getOrNull()
        } else {
            null
        }
        val activityName = if (this::androidDeviceOperator.isInitialized) {
            runCatching { androidDeviceOperator.currentActivityName() }.getOrNull()
        } else {
            null
        }
        val display = if (this::androidDeviceOperator.isInitialized) {
            val width = runCatching { androidDeviceOperator.getDisplayWidth() }.getOrDefault(0)
            val height = runCatching { androidDeviceOperator.getDisplayHeight() }.getOrDefault(0)
            if (width > 0 && height > 0) StateDisplay(width, height) else null
        } else {
            null
        }
        return State(
            stateId = "$runId-vlm-user-cancelled-$capturedAtMs",
            xml = xml?.takeIf(String::isNotBlank) ?: fallback?.xml,
            packageName = packageName?.takeIf(String::isNotBlank) ?: fallback?.packageName,
            activityName = activityName?.takeIf(String::isNotBlank) ?: fallback?.activityName,
            display = display ?: fallback?.display,
            screenshotBase64 = screenshot?.takeIf(String::isNotBlank) ?: fallback?.screenshotBase64,
        )
    }

    private fun VLMPostActionObservation.Summary.toRunLogMap(): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "summary" to summaryText,
            "screen_changed" to screenChanged,
            "package_changed" to packageChanged,
            "before_package" to beforePackageName,
            "after_package" to afterPackageName,
            "after_visible_texts" to afterVisibleTexts.takeIf { it.isNotEmpty() },
            "appeared_texts" to appearedTexts.takeIf { it.isNotEmpty() },
            "disappeared_texts" to disappearedTexts.takeIf { it.isNotEmpty() },
            "after_focused_editable" to afterFocusedEditable
        ).filterValues { it != null }

    private fun State.toRunLogMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "state_id" to stateId,
        "xml" to xml,
        "package_name" to packageName,
        "activity_name" to activityName,
        "screenshot_base64" to screenshotBase64,
        "display" to display?.let {
            linkedMapOf("width" to it.width, "height" to it.height)
        },
    ).filterValues { it != null }

    private fun VLMFailureDiagnostics.toRunLogMap(): Map<String, Any?> =
        linkedMapOf(
            "kind" to kind,
            "message" to message,
            "tool_call_failures" to toolCallFailures.map { failure ->
                linkedMapOf(
                    "code" to failure.code,
                    "tool_name" to failure.toolName,
                    "required_fields" to failure.requiredFields,
                    "provided_fields" to failure.providedFields,
                    "argument_types" to failure.argumentTypes,
                    "missing_fields" to failure.missingFields,
                    "safe_arguments_preview" to failure.safeArgumentsPreview,
                    "message" to failure.message,
                ).filterValues { it != null }
            },
        )

    private fun JsonElement.toRunLogAny(): Any? =
        when (this) {
            is JsonNull -> null
            is JsonObject -> entries.associate { (key, value) -> key to value.toRunLogAny() }
            is JsonArray -> map { it.toRunLogAny() }
            is JsonPrimitive -> {
                if (isString) {
                    contentOrNull
                } else {
                    booleanOrNull ?: longOrNull ?: doubleOrNull ?: contentOrNull
                }
            }
        }

    fun start(
        context: Context,
        goal: String,
        model: String?,
        maxSteps: Int?,
        packageName: String?,
        onTaskFinishListener: () -> Unit,
        skipGoHome: Boolean = false,  // 是否跳过回到主页，从当前页面开始执行
        stepSkillGuidance: String = "",
        disableFunctionRecall: Boolean = false
    ) {
        this.goal = goal;
        this.taskContext = context
        this.onTaskFinishListener = onTaskFinishListener
        super.start {
            AccessibilityController.Companion.hideKeyboard()
            val currentPackageName = packageName ?: (AccessibilityController.Companion.getPackageName() ?: "")
            val installedApps = AccessibilityController.Companion.mapInstalledApplications()
            InternalRunLogStore.beginRun(
                context = context,
                runId = id,
                goal = goal,
                source = "vlm",
                toolName = "vlm_task",
                operationDescription = goal
            )

            executionRecordId = DatabaseHelper.saveExecutionRecord(
                context,
                goal,
                currentPackageName,
                "vlm",
                if (needSummary) "${System.currentTimeMillis()}" else goal,
                null,
                if (needSummary) "summary" else "vlm"
            )
            OmniLog.d(Tag, "VLM Operation Task Is Running ! skipGoHome=$skipGoHome")
            try {
                taskStartTime = System.currentTimeMillis()
                val shouldSummary = needSummary
                val taskExecutionReport = vlmOperationService.executeTask(
                    goal = goal,
                    installedApps = installedApps,
                    model = model ?: VLMRuntimeConfigRegistry.get().primarySceneId,
                    maxSteps = maxSteps,
                    packageName = packageName,
                    skipGoHome = skipGoHome,
                    summary = shouldSummary,
                    currentStepGoal = goal,
                    stepSkillGuidance = stepSkillGuidance,
                    disableFunctionRecall = disableFunctionRecall
                )
                OmniLog.d(Tag, "VLM Operation Task Finished: $taskExecutionReport")
                throwIfCancellationRequested("task_report_ready")
                val finishType = when {
                    taskExecutionReport.success -> TaskFinishType.FINISH
                    else -> TaskFinishType.ERROR
                }
                val finishMessage = taskExecutionReport.error.orEmpty()
                OmniLog.i(
                    Tag,
                    "VLM task terminal state: finishType=$finishType success=${taskExecutionReport.success} error=${taskExecutionReport.error.orEmpty()}"
                )

                appendInternalRunLog(context, taskExecutionReport)

                finalizeTerminalOnce {
                    if (taskExecutionReport.success) {
                        notifyTerminalResult(
                            VlmTaskTerminalResult(
                                status = VlmTaskTerminalStatus.FINISHED,
                                message = extractFinishedContent(taskExecutionReport),
                                finishedContent = extractFinishedContent(taskExecutionReport),
                                summaryText = null,
                                needSummary = shouldSummary,
                                summaryUnavailable = true
                            )
                        )
                        val executedFunctionId = taskExecutionReport.executionTrace
                            .mapNotNull { (it.action as? FunctionInvocation)?.functionId }
                            .lastOrNull()
                        cancelScope.launch {
                            kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                                VLMPostTaskHookRegistry.notify(
                                    goal = goal ?: "",
                                    packageName = taskExecutionReport.finalContext.targetPackageName
                                        .takeIf { it.isNotBlank() },
                                    executedFunctionId = executedFunctionId,
                                    success = true,
                                    executionTrace = taskExecutionReport.executionTrace,
                                )
                            }
                        }
                    } else {
                        val errorMessage = finishMessage.ifBlank { "任务执行失败" }
                        notifyTerminalResult(
                            VlmTaskTerminalResult(
                                status = VlmTaskTerminalStatus.ERROR,
                                message = errorMessage,
                                finishedContent = null,
                                summaryText = null,
                                errorMessage = errorMessage,
                                needSummary = shouldSummary,
                                summaryUnavailable = true
                            )
                        )
                        cancelScope.launch {
                            kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                                VLMPostTaskHookRegistry.notify(
                                    goal = goal ?: "",
                                    packageName = taskExecutionReport.finalContext.targetPackageName
                                        .takeIf { it.isNotBlank() },
                                    executedFunctionId = null,
                                    success = false,
                                    executionTrace = taskExecutionReport.executionTrace,
                                )
                            }
                        }
                    }
                    onTaskStop(finishType, finishMessage)
                    onTaskDestroy()
                }

                if (shouldSummary && taskExecutionReport.summaryScreenshotList != null) {
                    cancelScope.launch {
                        pushSummary(goal = goal, model = model, report = taskExecutionReport)
                    }
                }
            } catch (e: PrivacyBlockedException) {
                finalizeTerminalOnce {
                    InternalRunLogStore.finishRun(
                        context = context,
                        runId = id,
                        success = false,
                        doneReason = "error",
                        errorMessage = e.message ?: "应用未授权，已被隐私设置限制"
                    )
                    notifyTerminalResult(
                        VlmTaskTerminalResult(
                            status = VlmTaskTerminalStatus.ERROR,
                            message = e.message ?: "应用未授权，已被隐私设置限制",
                            errorMessage = e.message ?: "应用未授权，已被隐私设置限制",
                            needSummary = needSummary
                        )
                    )
                    onTaskStop(TaskFinishType.ERROR, e.message ?: "应用未授权，已被隐私设置限制")
                    onTaskDestroy()
                }
            } catch (e: Http429Exception) {
                finalizeTerminalOnce {
                    InternalRunLogStore.finishRun(
                        context = context,
                        runId = id,
                        success = false,
                        doneReason = "error",
                        errorMessage = e.message ?: "请求过于频繁"
                    )
                    notifyTerminalResult(
                        VlmTaskTerminalResult(
                            status = VlmTaskTerminalStatus.ERROR,
                            message = e.message ?: "请求过于频繁",
                            errorMessage = e.message ?: "请求过于频繁",
                            needSummary = needSummary
                        )
                    )
                    onTaskStop(TaskFinishType.ERROR, e.message)
                    onTaskDestroy()
                }
            } catch (e: CancellationException) {
                OmniLog.i(Tag, "VLM Operation Task cancelled")
                finalizeCancellationAsync(e.message ?: "任务已取消")
            } catch (e: UserCompletedTaskException) {
                if (terminalFinalized.compareAndSet(false, true)) {
                    launchUserCompletionFinalization(e.completionMessage)
                }
            } catch (e: Exception) {
                OmniLog.e(Tag, "VLM Operation Task Error: ${e.message}")
                finalizeTerminalOnce {
                    InternalRunLogStore.finishRun(
                        context = context,
                        runId = id,
                        success = false,
                        doneReason = "error",
                        errorMessage = e.message ?: "任务执行异常"
                    )
                    notifyTerminalResult(
                        VlmTaskTerminalResult(
                            status = VlmTaskTerminalStatus.ERROR,
                            message = e.message ?: "任务执行异常",
                            errorMessage = e.message ?: "任务执行异常",
                            needSummary = needSummary
                        )
                    )
                    onTaskStop(TaskFinishType.ERROR, e.message ?: "任务执行异常")
                    onTaskDestroy()
                }
            }

        }
    }

    override suspend fun onTaskStarted() {
        if (!isSubTask) {
            executionTaskEventApi?.onReadyStartVLMTask(this)
        }
        super.onTaskStarted()
    }

    /**
     * 专门用于sequence执行的启动方法，完全不操作UI状态
     */
    fun startAsSequenceSubTask(
        goal: String,
        model: String?,
        maxSteps: Int?,
        onTaskFinishListener: () -> Unit
    ) {
        this.onTaskFinishListener = onTaskFinishListener
        this.isSubTask = true  // 标记为子任务
        this.taskContext = BaseApplication.instance

        super.start {
            taskStartTime = System.currentTimeMillis()
            AccessibilityController.Companion.hideKeyboard()
            val installedApps = AccessibilityController.Companion.mapInstalledApplications()
            OmniLog.d(Tag, "VLM Operation Sequence Sub Task Is Running !")
            try {
                val report = vlmOperationService.executeTask(
                    goal = goal,
                    installedApps = installedApps,
                    model = model ?: VLMRuntimeConfigRegistry.get().primarySceneId,
                    maxSteps = maxSteps,
                    skipGoHome = true  // 作为子任务执行时，不回退到桌面
                )
                OmniLog.d(Tag, "VLM Operation Sequence Sub Task Finished")
                onTaskStop(
                    if (report.success) TaskFinishType.FINISH else TaskFinishType.ERROR,
                    report.error.orEmpty()
                )
                onTaskDestroy()
            } catch (e: PrivacyBlockedException) {
                onTaskStop(TaskFinishType.ERROR, e.message ?: "应用未授权，已被隐私设置限制")
                onTaskDestroy()
            } catch (e: CancellationException) {
                finalizeCancellationAsync(e.message ?: "任务已取消")
            } catch (e: Exception) {
                onTaskStop(TaskFinishType.ERROR, e.message ?: "任务执行异常")
                onTaskDestroy()
            }
        }
    }

    override suspend fun onTaskStop(finishType: TaskFinishType, message: String) {
        super.onTaskStop(finishType, message)
        // 更新执行记录的状态
        if (finishType != TaskFinishType.WAITING_INPUT && finishType != TaskFinishType.USER_PAUSED && taskContext != null) {
            DatabaseHelper.updateExecutionRecordStatus(executionRecordId, finishType.toExecutionRecordStatus())
        }
    }

    private fun TaskFinishType.toExecutionRecordStatus(): String = when (this) {
        TaskFinishType.FINISH -> "success"
        TaskFinishType.ERROR -> "failed"
        TaskFinishType.CANCEL -> "cancelled"
        TaskFinishType.WAITING_INPUT -> "waiting"
        TaskFinishType.USER_PAUSED -> "paused"
    }

    private data class SummaryPushResult(
        val summaryText: String? = null,
        val summaryUnavailable: Boolean = false
    )

    private suspend fun pushSummary(goal: String, model: String?, report: TaskExecutionReport): SummaryPushResult {
        val listener = onMessagePushListener ?: return SummaryPushResult(summaryUnavailable = true)
        var summaryTaskId: String? = null
        var summaryStarted = false

        try {
            val finishedFromTrace = report.executionTrace.lastOrNull { it.action.name == "finished" }
            val traceSummary = finishedFromTrace?.result
                ?: (finishedFromTrace?.action as? FinishedDecision)?.content.orEmpty()
            val prompt = PromptTemplate.summaryPrompt(goal)

            val modelToUse = "scene.compactor.context"
            val vlmPayload = AgentRequest.Payload.VLMChatPayload(
                model = modelToUse, text = prompt, images = report.summaryScreenshotList!!
            )

            val summaryText = withTimeoutOrNull(SUMMARY_GENERATION_TIMEOUT_MS) {
                // 1. 等待主聊天页面准备就绪的回调
                OmniLog.d(Tag, "等待主聊天页面准备就绪通知...")
                summarySheetReadyChannel.receive()
                throwIfCancellationRequested("summary_sheet_ready")
                OmniLog.d(Tag, "主聊天页面已准备就绪，开始推送总结...")

                // 2. 先推送"总结开始"消息，让前端显示"总结中"状态
                summaryTaskId = "vlm-summary-${System.currentTimeMillis()}"
                summaryStarted = true
                listener.onChatMessage(summaryTaskId!!, "", "summary_start")
                OmniLog.d(Tag, "已推送 summary_start，前端应显示'总结中'状态")

                // 3. 调用VLM API获取总结（这一步可能需要较长时间）
                OmniLog.d(Tag, "开始调用VLM API生成总结...")
                val response = HttpController.postVLMRequest(vlmPayload)
                response.message.ifBlank { traceSummary }
            }

            if (summaryText == null) {
                OmniLog.w(Tag, "pushSummary timeout after ${SUMMARY_GENERATION_TIMEOUT_MS}ms")
                return SummaryPushResult(summaryUnavailable = true)
            }

            if (summaryText.isBlank()) {
                OmniLog.w(Tag, "pushSummary: empty summaryText, skip.")
                return SummaryPushResult(summaryUnavailable = true)
            }
            OmniLog.d(Tag, "VLM API返回总结内容，长度: ${summaryText.length}")

            // 4. 推送总结消息内容
            val payload = JSONObject().apply { put("text", summaryText) }.toString()
            listener.onChatMessage(summaryTaskId!!, payload, null)

            // 5. 更新执行记录的总结内容（使用记录 ID 精确更新，避免覆盖历史记录）
            if (executionRecordId > 0) {
                DatabaseHelper.updateExecutionRecordContentById(
                    id = executionRecordId,
                    content = summaryText
                )
                OmniLog.d(Tag, "总结已更新到数据库 (id=$executionRecordId)")
            } else {
                OmniLog.w(Tag, "无效的记录ID (id=$executionRecordId)，跳过总结更新")
            }

            // 6. 保存到Message表，包含在聊天上下文中
            if (summaryText.isNotBlank()) {
                DatabaseHelper.insertTaskResultMessage(
                    messageId = summaryTaskId!!,
                    taskType = "vlm_summary",
                    content = summaryText,
                    executionRecordId = executionRecordId,
                    metadata = mapOf("goal" to goal)
                )
                OmniLog.d(Tag, "VLM总结已保存到Message表")
            }
            return SummaryPushResult(summaryText = summaryText)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OmniLog.e(Tag, "pushSummary error: ${e.message}")
            return SummaryPushResult(summaryUnavailable = true)
        } finally {
            if (summaryStarted && summaryTaskId != null) {
                try {
                    listener.onChatMessageEnd(summaryTaskId!!)
                } catch (e: Exception) {
                    OmniLog.e(Tag, "pushSummary end callback error: ${e.message}")
                }
            }
        }
    }

    override suspend fun clickCoordinate(x: Float, y: Float): OperationResult {
        return androidDeviceOperator.clickCoordinate(x, y)
    }

    override suspend fun longClickCoordinate(x: Float, y: Float, duration: Long): OperationResult {
        return androidDeviceOperator.longClickCoordinate(x, y, duration)
    }

    override suspend fun inputText(text: String): OperationResult {
        return androidDeviceOperator.inputText(text)
    }

    override suspend fun pressHotKey(key: String): OperationResult {
        return androidDeviceOperator.pressHotKey(key)
    }

    suspend fun inputTextViaShell(text: String): OperationResult {
        return androidDeviceOperator.inputTextViaShell(text)
    }

    override suspend fun copyToClipboard(text: String): OperationResult {
        return androidDeviceOperator.copyToClipboard(text)
    }

    override suspend fun getClipboard(): String? {
        return androidDeviceOperator.getClipboard()
    }

    override suspend fun slideCoordinate(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        duration: Long
    ): OperationResult {
        return androidDeviceOperator.slideCoordinate(x1, y1, x2, y2, duration)
    }

    suspend fun slideCoordinateWithContext(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        duration: Long,
        targetDescription: String,
    ): OperationResult {
        return androidDeviceOperator.slideCoordinateWithContext(
            x1 = x1,
            y1 = y1,
            x2 = x2,
            y2 = y2,
            duration = duration,
            targetDescription = targetDescription,
        )
    }

    override suspend fun goHome(): OperationResult {
        return androidDeviceOperator.goHome()
    }

    override suspend fun goBack(): OperationResult {
        return androidDeviceOperator.goBack()
    }

    override suspend fun launchApplication(packageName: String): OperationResult {
        return androidDeviceOperator.launchApplication(packageName)
    }

    override suspend fun captureScreenshot(): String {
        return androidDeviceOperator.captureScreenshot()
    }

    override fun getLastScreenshotWidth(): Int {
        return androidDeviceOperator.getLastScreenshotWidth()
    }

    override fun getLastScreenshotHeight(): Int {
        return androidDeviceOperator.getLastScreenshotHeight()
    }

    override fun getDisplayWidth(): Int {
        return androidDeviceOperator.getDisplayWidth()
    }

    override fun getDisplayHeight(): Int {
        return androidDeviceOperator.getDisplayHeight()
    }

    override suspend fun showInfo(message: String) {
        androidDeviceOperator.showInfo(message)
    }

    override fun isReady(): Boolean {
        return androidDeviceOperator.isReady()
    }

    override fun currentXml(): String? {
        return androidDeviceOperator.currentXml()
    }

    override fun currentPackageName(): String? {
        return androidDeviceOperator.currentPackageName()
    }

    override fun currentActivityName(): String? {
        return androidDeviceOperator.currentActivityName()
    }

    override suspend fun hideKeyboard(): OperationResult {
        return androidDeviceOperator.hideKeyboard()
    }

    fun finishTask() {
        OmniLog.d(Tag, "Finishing VLM Operation Task")
        _isCancellationRequested = true
        unblockWaitingReceivers()
        cancelRunningJob("任务已取消")
        finalizeCancellationAsync("任务已取消")
    }

    fun completeByUser(message: String = "任务已完成") {
        OmniLog.d(Tag, "Completing VLM Operation Task by user")
        if (completeManualTakeover(message)) return
        if (!terminalFinalized.compareAndSet(false, true)) return
        _isCancellationRequested = true
        unblockWaitingReceivers()
        cancelRunningJob(message)
        launchUserCompletionFinalization(message)
    }

    fun cancelTask() {
        finishTask()
    }

    override suspend fun onTaskDestroy() {
        AccessibilityController.Companion.restoreKeyboard()
        if (this::onTaskFinishListener.isInitialized) {
            runCatching { onTaskFinishListener.invoke() }
                .onFailure { OmniLog.e(Tag, "onTaskFinishListener failed: ${it.message}") }
        }
        super.onTaskDestroy()
    }

    override fun getTaskType(): TaskType {
        return TaskType.VLM_OPERATION_EXECUTION
    }
}

private class UserCompletedTaskException(
    val completionMessage: String,
) : Exception(completionMessage)

internal data class VLMRunLogStepSemantics(
    val success: Boolean,
    val toolName: String,
    val toolType: String,
    val actionType: String?,
    val recallKind: String,
    val hasNativeToolCall: Boolean,
)

private data class PendingVlmRunLogStep(
    val stepId: String,
    val stepIndex: Int,
    val step: UIStep,
    val success: Boolean,
    val errorMessage: String?,
)

internal fun buildUserCancelledTerminalStep(
    state: State,
    message: String,
    timestampMs: Long,
): UIStep = UIStep(
    observation = "用户取消了任务",
    thought = "",
    action = AbortDecision(message),
    result = message,
    summary = "用户取消任务",
    beforeState = state,
    afterState = state,
    startedAtMs = timestampMs,
    finishedAtMs = timestampMs,
    failure = VLMFailureDiagnostics(
        kind = "user_cancelled",
        message = message,
    ),
)

internal fun resolveVlmRunLogStepSemantics(
    step: UIStep,
    successOverride: Boolean? = null,
): VLMRunLogStepSemantics {
    val isMemoryRecord = step.action is RecordMemory
    val isDiagnosticFailure = step.failure != null
    val intrinsicSuccess = !isDiagnosticFailure &&
        step.action !is AbortDecision &&
        step.result?.startsWith(ACTION_FAILURE_PREFIX) != true
    val success = if (isDiagnosticFailure) false else successOverride ?: intrinsicSuccess
    return VLMRunLogStepSemantics(
        success = success,
        toolName = when {
            isDiagnosticFailure -> step.failure.kind.ifBlank { "vlm_failure" }
            isMemoryRecord -> "memory_record"
            else -> step.action.name
        },
        toolType = when {
            isDiagnosticFailure -> "vlm_diagnostic"
            isMemoryRecord -> "vlm_memory"
            else -> "vlm"
        },
        actionType = step.action.name.takeUnless { isMemoryRecord || isDiagnosticFailure },
        recallKind = when {
            isDiagnosticFailure -> "vlm_diagnostic"
            isMemoryRecord -> "vlm_memory"
            else -> "vlm_step"
        },
        hasNativeToolCall = !isMemoryRecord && !isDiagnosticFailure,
    )
}
