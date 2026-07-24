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
    private val recordStepExecutor: OmniFlowRecordStepExecutor,
) : Task(taskChangeListener, taskManager) {
    private val Tag = "VLMOperationTask"
    private companion object {
        private const val SUMMARY_GENERATION_TIMEOUT_MS = 20_000L
        private const val CANCELLATION_CAPTURE_TIMEOUT_MS = 1_500L
        private const val MAX_MANUAL_TRACE_MEMORY_ACTIONS = 12
        private const val MAX_MANUAL_TRACE_PARAM_CHARS = 240
        private const val MAX_EXTERNAL_EVENTS = 32
    }

    private lateinit var androidDeviceOperator: AndroidDeviceOperator
    private lateinit var onTaskFinishListener: () -> Unit?
    
    @Volatile
    private var _isCancellationRequested: Boolean = false
    val isCancellationRequested: Boolean
        get() = _isCancellationRequested
    
    private var executionRecordId: Long = -1L
    @Volatile
    private var pauseRequested: Boolean = false
    private lateinit var externalEngineExecutor: VlmTaskEngineExecutor
    private val externalEventLock = Any()
    private val pendingExternalEvents = ArrayDeque<Map<String, Any?>>()

    private var taskContext: Context? = null
    private val terminalFinalized = AtomicBoolean(false)
    private val userInputChannel = Channel<String>(Channel.Factory.UNLIMITED)
    private val manualTakeoverController = ManualTakeoverController()
    private val summarySheetReadyChannel = Channel<Unit>(Channel.Factory.CONFLATED)

    private var goal: String? = null
    private var taskStartTime = 0L

    fun appendExternalMemory(memory: String): Boolean {
        val trimmed = memory.trim()
        if (trimmed.isEmpty()) return false
        enqueueExternalEvent(
            linkedMapOf(
                "type" to "external_memory",
                "text" to trimmed,
                "source" to "user",
            )
        )
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
        enqueueExternalEvent(
            linkedMapOf(
                "type" to eventType.trim().ifBlank { "priority_event" },
                "text" to trimmed,
                "source" to "runtime",
                "suggest_completion" to suggestCompletion,
            )
        )
        return true
    }

    override suspend fun onTaskCreated() {
        super.onTaskCreated()
        androidDeviceOperator = AndroidDeviceOperator(executionTaskEventApi, taskContext)
        externalEngineExecutor = VlmTaskEngineRegistry.require()
    }

    private fun enqueueExternalEvent(event: Map<String, Any?>) {
        synchronized(externalEventLock) {
            pendingExternalEvents.addLast(event)
            while (pendingExternalEvents.size > MAX_EXTERNAL_EVENTS) {
                pendingExternalEvents.removeFirst()
            }
        }
    }

    private fun consumeExternalEvents(): List<Map<String, Any?>> = synchronized(externalEventLock) {
        if (pendingExternalEvents.isEmpty()) return@synchronized emptyList()
        pendingExternalEvents.toList().also { pendingExternalEvents.clear() }
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
     * 检查并处理用户暂停请求。
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
        cancelScope.launch {
            if (context != null && runId.isNotBlank()) {
                val finalStateId = appendUserCancelledRunLog(
                    context = context,
                    runId = runId,
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

    private fun terminalContent(result: VlmTaskEngineResult): String =
        result.finishedContent?.trim().orEmpty().ifBlank {
            if (result.success) "任务完成" else result.error.orEmpty().ifBlank { "任务执行失败" }
        }

    private suspend fun appendInternalRunLog(context: Context, result: VlmTaskEngineResult) {
        InternalRunLogStore.finishRun(
            context = context,
            runId = id,
            success = result.success,
            doneReason = result.doneReason ?: if (result.success) "finished" else "error",
            errorMessage = result.error,
            finalStateId = result.finalStateId,
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
        val memory = buildManualTraceMemory(result)
        if (memory.isNotBlank()) appendExternalMemory(memory)
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

    private suspend fun appendUserCancelledRunLog(
        context: Context,
        runId: String,
        message: String,
    ): String {
        val cancelledAtMs = System.currentTimeMillis()
        val cancellationState = captureCancellationState(
            runId = runId,
            fallback = null,
            capturedAtMs = cancelledAtMs,
        )
        InternalRunLogStore.persistState(context, cancellationState.toRunLogMap())
        val terminalStepId = "$runId-vlm-user-cancelled"
        val terminalStepIndex = InternalRunLogStore.getRun(context, runId)?.steps?.size ?: 0
        runCatching {
            val record = recordStepExecutor.recordStep(
                RunLogStepRecord(
                    step = linkedMapOf(
                        "step_index" to terminalStepIndex,
                        "before_state_id" to cancellationState.stateId,
                        "action" to linkedMapOf(
                            "tool" to "abort",
                            "args" to linkedMapOf("value" to message),
                        ),
                        "result" to linkedMapOf(
                            "success" to false,
                            "error" to message,
                        ),
                        "after_state_id" to cancellationState.stateId,
                        "metadata" to linkedMapOf(
                            "step_id" to terminalStepId,
                            "status" to "failed",
                            "source" to "user_cancelled",
                            "summary" to "用户取消任务",
                            "thinking" to "",
                        ),
                    ),
                    states = listOf(cancellationState.toRunLogMap()),
                ),
            )
            InternalRunLogStore.upsertRecordedStep(context, runId, record)
        }.onFailure { error ->
            OmniLog.e(Tag, "VLM cancellation terminal step write failed: ${error.message}")
        }
        return cancellationState.stateId
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
                val resolvedModel = model ?: VLMRuntimeConfigRegistry.get().primarySceneId
                val engineResult = executeExternalEngine(
                    engine = externalEngineExecutor,
                    context = context,
                    goal = goal,
                    model = resolvedModel,
                    maxSteps = maxSteps,
                    packageName = packageName,
                    skipGoHome = skipGoHome,
                    stepSkillGuidance = stepSkillGuidance,
                    disableFunctionRecall = disableFunctionRecall,
                )
                OmniLog.d(Tag, "VLM Operation Task Finished: $engineResult")
                throwIfCancellationRequested("task_report_ready")
                val finishType = if (engineResult.success) TaskFinishType.FINISH else TaskFinishType.ERROR
                val finishMessage = engineResult.error.orEmpty()
                OmniLog.i(
                    Tag,
                    "VLM task terminal state: finishType=$finishType success=${engineResult.success} error=${engineResult.error.orEmpty()}"
                )

                appendInternalRunLog(context, engineResult)

                finalizeTerminalOnce {
                    if (engineResult.success) {
                        val content = terminalContent(engineResult)
                        notifyTerminalResult(
                            VlmTaskTerminalResult(
                                status = VlmTaskTerminalStatus.FINISHED,
                                message = content,
                                finishedContent = content,
                                summaryText = null,
                                needSummary = shouldSummary,
                                summaryUnavailable = true
                            )
                        )
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
                    }
                    onTaskStop(finishType, finishMessage)
                    onTaskDestroy()
                }

                if (shouldSummary) {
                    cancelScope.launch {
                        val screenshots = runCatching {
                            listOf(androidDeviceOperator.captureScreenshot())
                        }.getOrNull()
                        pushSummary(
                            goal = goal,
                            traceSummary = terminalContent(engineResult),
                            screenshots = screenshots,
                        )
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
                val errorMessage = e.message.orEmpty().ifBlank { "请求过于频繁" }
                finalizeTerminalOnce {
                    InternalRunLogStore.finishRun(
                        context = context,
                        runId = id,
                        success = false,
                        doneReason = "error",
                        errorMessage = errorMessage
                    )
                    notifyTerminalResult(
                        VlmTaskTerminalResult(
                            status = VlmTaskTerminalStatus.ERROR,
                            message = errorMessage,
                            errorMessage = errorMessage,
                            needSummary = needSummary
                        )
                    )
                    onTaskStop(TaskFinishType.ERROR, errorMessage)
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

    private suspend fun executeExternalEngine(
        engine: VlmTaskEngineExecutor,
        context: Context,
        goal: String,
        model: String,
        maxSteps: Int?,
        packageName: String?,
        skipGoHome: Boolean,
        stepSkillGuidance: String,
        disableFunctionRecall: Boolean,
    ): VlmTaskEngineResult {
        prepareExternalEngineStart(packageName, skipGoHome)
        val result = engine.execute(
            request = VlmTaskEngineRequest(
                context = context.applicationContext,
                runId = id,
                goal = goal,
                model = model,
                maxSteps = maxSteps,
                packageName = packageName,
                stepSkillGuidance = stepSkillGuidance,
                disableFunctionRecall = disableFunctionRecall,
            ),
            host = object : VlmTaskEngineHost {
                override val deviceOperator: DeviceOperator = androidDeviceOperator

                override suspend fun beforeStep() {
                    throwIfCancellationRequested("external_engine_step")
                    checkAndHandlePause()
                }

                override fun consumeExternalEvents(): List<Map<String, Any?>> =
                    this@VLMOperationTask.consumeExternalEvents()

                override suspend fun requestUserInput(question: String): String =
                    handleInfoAction(question)

                override suspend fun onModelTurn(metadata: Map<String, Any?>) {
                    val message = firstNonBlank(
                        metadata["thinking"],
                        metadata["summary"],
                    )
                    if (message.isNotBlank()) {
                        androidDeviceOperator.showInfo(message)
                    }
                }

                override suspend fun onActionStarted(
                    action: Map<String, Any?>,
                    metadata: Map<String, Any?>,
                ) {
                    val stepIndex = InternalRunLogStore.getRun(context, id)?.steps?.size ?: 0
                    onMessagePushListener?.onVlmStepProgress(
                        VlmStepProgress(
                            runId = id,
                            stepIndex = stepIndex,
                            status = "running",
                            thinking = firstNonBlank(metadata["thinking"]),
                            summary = firstNonBlank(metadata["summary"]),
                            action = action,
                            result = null,
                            error = null,
                        )
                    )
                }

                override suspend fun recordStep(step: Map<String, Any?>) {
                    val nextStepIndex = InternalRunLogStore.getRun(context, id)?.steps?.size ?: 0
                    val canonicalStep = linkedMapOf<String, Any?>().apply {
                        putAll(step)
                        put("step_index", nextStepIndex)
                    }
                    InternalRunLogStore.upsertStep(context, id, canonicalStep)
                    val resultMap = canonicalStep["result"].asStringMap()
                    val metadata = canonicalStep["metadata"].asStringMap()
                    val success = resultMap["success"] == true
                    onMessagePushListener?.onVlmStepProgress(
                        VlmStepProgress(
                            runId = id,
                            stepIndex = nextStepIndex,
                            status = if (success) "succeeded" else "failed",
                            thinking = firstNonBlank(metadata["thinking"]),
                            summary = firstNonBlank(metadata["summary"]),
                            action = canonicalStep["action"].asStringMap(),
                            result = resultMap,
                            error = firstNonBlank(resultMap["error"]).takeIf(String::isNotBlank),
                        )
                    )
                }
            },
        )
        return result
    }

    private suspend fun prepareExternalEngineStart(
        packageName: String?,
        skipGoHome: Boolean,
    ) {
        val targetPackage = packageName?.trim()?.takeIf(String::isNotEmpty) ?: return
        if (skipGoHome) return
        throwIfCancellationRequested("external_engine_before_launch_application")
        checkAndHandlePause()
        val launch = androidDeviceOperator.launchApplication(targetPackage)
        if (!launch.success) {
            OmniLog.e(Tag, "拉起应用失败: ${launch.message}")
            return
        }
        repeat(30) {
            throwIfCancellationRequested("external_engine_wait_application")
            if (androidDeviceOperator.currentPackageName() == targetPackage) return
            delay(100L)
        }
    }

    private fun firstNonBlank(vararg values: Any?): String =
        values.firstNotNullOfOrNull { value ->
            value?.toString()?.trim()?.takeIf(String::isNotEmpty)
        }.orEmpty()

    private fun Any?.asStringMap(): Map<String, Any?> {
        val source = this as? Map<*, *> ?: return emptyMap()
        return source.entries.associateTo(linkedMapOf()) { (key, value) ->
            key?.toString().orEmpty() to value
        }
    }

    private fun Any?.asInt(): Int? = when (this) {
        is Number -> toInt()
        is String -> toIntOrNull()
        else -> null
    }

    override suspend fun onTaskStarted() {
        executionTaskEventApi?.onReadyStartVLMTask(this)
        super.onTaskStarted()
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

    private suspend fun pushSummary(
        goal: String,
        traceSummary: String,
        screenshots: List<String>?,
    ): SummaryPushResult {
        val listener = onMessagePushListener ?: return SummaryPushResult(summaryUnavailable = true)
        var summaryTaskId: String? = null
        var summaryStarted = false

        try {
            val prompt = PromptTemplate.summaryPrompt(goal)

            val modelToUse = "scene.compactor.context"
            val summaryImages = screenshots ?: return SummaryPushResult(summaryUnavailable = true)
            val vlmPayload = AgentRequest.Payload.VLMChatPayload(
                model = modelToUse, text = prompt, images = summaryImages
            )

            val summaryText = withTimeoutOrNull(SUMMARY_GENERATION_TIMEOUT_MS) {
                // 1. 等待主聊天页面准备就绪的回调
                OmniLog.d(Tag, "等待主聊天页面准备就绪通知...")
                summarySheetReadyChannel.receive()
                throwIfCancellationRequested("summary_sheet_ready")
                OmniLog.d(Tag, "主聊天页面已准备就绪，开始推送总结...")

                // 2. 先推送"总结开始"消息，让前端显示"总结中"状态
                val currentSummaryTaskId = "vlm-summary-${System.currentTimeMillis()}"
                summaryTaskId = currentSummaryTaskId
                summaryStarted = true
                listener.onChatMessage(currentSummaryTaskId, "", "summary_start")
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
            val currentSummaryTaskId = summaryTaskId
                ?: return SummaryPushResult(summaryUnavailable = true)
            val payload = JSONObject().apply { put("text", summaryText) }.toString()
            listener.onChatMessage(currentSummaryTaskId, payload, null)

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
                    messageId = currentSummaryTaskId,
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
                    val finishedSummaryTaskId = summaryTaskId
                    if (finishedSummaryTaskId != null) {
                        listener.onChatMessageEnd(finishedSummaryTaskId)
                    }
                } catch (e: Exception) {
                    OmniLog.e(Tag, "pushSummary end callback error: ${e.message}")
                }
            }
        }
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
