package cn.com.omnimind.assists.task.vlmserver

import android.content.Context
import cn.com.omnimind.assists.TaskManager
import cn.com.omnimind.assists.api.bean.VlmTaskTerminalResult
import cn.com.omnimind.assists.api.bean.VlmTaskTerminalStatus
import cn.com.omnimind.assists.api.enums.TaskFinishType
import cn.com.omnimind.assists.api.enums.TaskType
import cn.com.omnimind.assists.api.interfaces.OnMessagePushListener
import cn.com.omnimind.assists.api.interfaces.TaskChangeListener
import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.assists.task.Task
import cn.com.omnimind.assists.api.eventapi.ExecutionTaskEventApi
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.baselib.http.Http429Exception
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
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
    override val taskManager: TaskManager
) : Task(taskChangeListener,taskManager), DeviceOperator {
    private val Tag = "VLMOperationTask"
    private companion object {
        private const val SUMMARY_GENERATION_TIMEOUT_MS = 20_000L
        private const val MAX_MANUAL_TRACE_MEMORY_ACTIONS = 12
        private const val MAX_MANUAL_TRACE_PARAM_CHARS = 240
        private const val MAX_MANUAL_TRACE_XML_CHARS = 6000
    }

    private lateinit var vlmOperationService: VLMOperationService
    private lateinit var androidDeviceOperator: AndroidDeviceOperator
    private lateinit var onTaskFinishListener: () -> Unit?
    
    /** 
     * 取消请求标记，用于在 delay 期间检查取消状态
     * 公开属性，供 ExecutionUIImpl 在 delay 循环中检查
     */
    @Volatile
    override var isCancellationRequested: Boolean = false
        private set
    
    private var executionRecordId: Long = -1L
    private var isSubTask: Boolean = false

    @Volatile
    private var pauseRequested: Boolean = false
    private lateinit var streamClient: VLMStreamClient

    private var taskContext: Context? = null
    private val terminalFinalized = AtomicBoolean(false)
    private val completedVlmStepCardIds = mutableSetOf<String>()

    private val userInputChannel = Channel<String>(Channel.Factory.UNLIMITED)
    private val userPauseChannel = Channel<Unit>(Channel.Factory.CONFLATED)
    private var manualTraceCardSeq: Int = 0
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
            onStepCompleted = { stepIndex, step, success, error ->
                handleVlmStepCompleted(stepIndex, step, success, error)
            },
            isSubTask = isSubTask,
            taskId = id,
            runId = id,
            taskScope = taskScope,
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
     * 用户主动暂停任务：不推送按钮卡片，直接切换小猫状态为"继续"
     */
    private suspend fun handleUserPause() {
        onTaskStop(TaskFinishType.USER_PAUSED, "")
        executionTaskEventApi?.onVlmTaskPaused(this)
        // 不推送按钮卡片，直接通知UI层切换小猫状态
        AccessibilityController.Companion.restoreKeyboard()
        if (onMessagePushListener != null) {
            try {
                onMessagePushListener.onVLMRequestUserInput("已接管控制，完成操作后点击继续")
            } catch (e: Exception) {
                OmniLog.e(Tag, "通知UI层失败: ${e.message}")
            }
        }
        val recorder = taskContext?.let { ManualVlmTraceRecorder(it, id) }
        val recorderStarted = recorder?.start() == true
        val manualTraceResult = try {
            userPauseChannel.receive() // 阻塞等待用户点击继续
            throwIfCancellationRequested("user_pause")
            recorder?.stop()
        } catch (e: Exception) {
            recorder?.stop()
            throw e
        }
        if (recorderStarted && manualTraceResult != null) {
            appendManualTrace(manualTraceResult)
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
        pauseRequested = true
    }

    /**
     * 从暂停状态恢复（公开方法，供UI调用）
     */
    fun resumeFromPause() {
        OmniLog.d(Tag, "收到继续请求")
        taskScope.launch {
            userPauseChannel.send(Unit)
        }
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
        if (isCancellationRequested) {
            throw CancellationException("任务已取消: $stage")
        }
    }

    private fun unblockWaitingReceivers() {
        userInputChannel.trySend("任务已取消")
        userPauseChannel.trySend(Unit)
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
                InternalRunLogStore.finishRun(
                    context = context,
                    runId = runId,
                    success = false,
                    doneReason = "cancelled",
                    errorMessage = message
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

    private fun finalizeUserCompletionAsync(message: String = "任务已完成") {
        val context = taskContext
        val runId = id
        if (!terminalFinalized.compareAndSet(false, true)) return
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
        val finishedStep = report.executionTrace.lastOrNull { it.action is FinishedAction }
        val fromResult = finishedStep?.result?.trim().orEmpty()
        if (fromResult.isNotEmpty()) return fromResult

        val fromAction = (finishedStep?.action as? FinishedAction)?.content?.trim().orEmpty()
        if (fromAction.isNotEmpty()) return fromAction

        val lastResult = report.executionTrace.asReversed()
            .mapNotNull { it.result?.trim()?.takeIf { value -> value.isNotEmpty() } }
            .firstOrNull()
        if (!lastResult.isNullOrEmpty()) return lastResult

        return "任务完成"
    }

    private fun appendInternalRunLog(context: Context, report: TaskExecutionReport) {
        report.executionTrace.forEachIndexed { index, step ->
            val cardId = vlmStepCardId(index)
            val stepSuccess = isReplayableStepSuccess(step)
            val card = buildInternalRunLogCard(
                index = index,
                step = step,
                status = if (stepSuccess) "success" else "error",
                successOverride = stepSuccess,
                errorMessage = if (stepSuccess) null else step.result
            )
            InternalRunLogStore.upsertCard(
                context = context,
                runId = id,
                cardId = cardId,
                card = card
            )
            if (completedVlmStepCardIds.add(cardId)) {
                notifyVlmToolEvent(
                    streamKind = "tool_completed",
                    index = index,
                    card = card,
                    step = step,
                    status = if (stepSuccess) "success" else "error",
                    success = stepSuccess,
                    errorMessage = if (stepSuccess) null else step.result
                )
            }
        }
        InternalRunLogStore.finishRun(
            context = context,
            runId = id,
            success = report.success,
            doneReason = report.doneReason ?: if (report.success) "finished" else "error",
            errorMessage = report.error
        )
    }

    private fun appendManualTrace(result: ManualVlmTraceResult) {
        if (result.actions.isEmpty()) return
        val context = taskContext ?: return
        val cards = result.actions.map { action ->
            manualTraceCardSeq += 1
            buildManualRunLogCard(manualTraceCardSeq, action)
        }
        InternalRunLogStore.appendCards(context, id, cards)
        if (this::vlmOperationService.isInitialized) {
            val memory = buildManualTraceMemory(result)
            if (memory.isNotBlank()) {
                vlmOperationService.addExternalMemory(memory)
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
            val params = action.params.entries
                .joinToString(", ") { (key, value) -> "$key=$value" }
                .take(MAX_MANUAL_TRACE_PARAM_CHARS)
            buildString {
                append("${index + 1}. ${action.actionName}: ")
                append(action.summary.ifBlank { action.title })
                if (params.isNotBlank()) append(" ($params)")
                action.packageName?.takeIf { it.isNotBlank() }?.let { append(" package=$it") }
            }
        }
        val omitted = result.actions.size - actions.size
        val finalAction = result.actions.lastOrNull()
        val finalPage = finalAction?.afterXml?.trim().orEmpty()
        return buildString {
            appendLine(result.summary.ifBlank {
                "用户在接管期间手动完成了 ${result.actionCount} 步操作。请基于当前屏幕继续执行原任务。"
            })
            appendLine("人工接管动作明细：")
            actionLines.forEach { appendLine(it) }
            if (omitted > 0) appendLine("... 另有 $omitted 步人工动作已记录在 RunLog。")
            finalAction?.packageName?.takeIf { it.isNotBlank() }?.let {
                appendLine("接管结束包名：$it")
            }
            if (finalPage.isNotBlank()) {
                appendLine("接管结束页面 XML（截断）：")
                appendLine(finalPage.take(MAX_MANUAL_TRACE_XML_CHARS))
            }
            append("下一步必须以当前截图和最新 Accessibility tree 为准，不要回退到接管前的页面。")
        }.trim()
    }

    private fun isReplayableStepSuccess(step: UIStep): Boolean {
        if (step.action is AbortAction) return false
        return step.result?.startsWith("执行失败") != true
    }

    private fun buildManualRunLogCard(
        index: Int,
        action: ManualVlmRecordedAction
    ): Map<String, Any?> {
        val cardId = "$id-manual-$index"
        val durationMs = (action.finishedAtMs - action.startedAtMs).coerceAtLeast(0L)
        val sourceContext = sourceContextForManualAction(action)
        return linkedMapOf(
            "card_id" to cardId,
            "tool_call_id" to cardId,
            "header" to linkedMapOf<String, Any?>(
                "step_index" to index,
                "title" to action.title,
                "tool_name" to action.actionName,
                "status" to "success",
                "success" to true,
                "duration_ms" to durationMs
            ),
            "step_index" to index,
            "title" to action.title,
            "summary" to action.summary,
            "tool_name" to action.actionName,
            "toolName" to action.actionName,
            "tool_type" to "manual_recording",
            "toolType" to "manual_recording",
            "status" to "success",
            "action_type" to action.actionName,
            "success" to true,
            "duration_ms" to durationMs,
            "started_at_ms" to action.startedAtMs,
            "finished_at_ms" to action.finishedAtMs,
            "package_name" to action.packageName,
            "recall_kind" to "manual_recording",
            "source" to "human_takeover",
            "event_context" to action.eventContext.takeIf { it.isNotEmpty() },
            "source_context" to sourceContext.takeIf { it.isNotEmpty() },
            "tool_call" to linkedMapOf(
                "id" to cardId,
                "name" to action.actionName,
                "arguments" to action.params
            ),
            "params" to action.params,
            "result" to linkedMapOf(
                "message" to action.summary,
                "summary" to action.summary,
                "source" to "human_takeover",
                "source_context" to sourceContext.takeIf { it.isNotEmpty() }
            ),
            "before" to linkedMapOf(
                "observation_xml" to action.beforeXml,
                "screenshot" to action.beforeScreenshot?.asMap(),
                "screenshot_path" to action.beforeScreenshot?.path,
                "package_name" to action.packageName
            ).filterValues { it != null },
            "after" to linkedMapOf(
                "observation_xml" to action.afterXml,
                "screenshot" to action.afterScreenshot?.asMap(),
                "screenshot_path" to action.afterScreenshot?.path,
                "summary" to action.summary,
                "package_name" to action.packageName
            ).filterValues { it != null }
        ).filterValues { it != null }
    }

    private fun sourceContextForManualAction(action: ManualVlmRecordedAction): Map<String, Any?> {
        val beforeXml = action.beforeXml?.takeIf { it.isNotBlank() } ?: return emptyMap()
        val recordingBackend = action.params["recording_backend"]?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: "accessibility_event"
        val actionMap = linkedMapOf<String, Any?>("tool" to action.actionName)
        action.params.forEach { (key, value) ->
            if (value != null) actionMap[key] = value
        }
        val dstCtx = linkedMapOf<String, Any?>(
            "page" to action.afterXml?.takeIf { it.isNotBlank() },
            "screenshot" to action.afterScreenshot?.asMap(),
            "screenshot_path" to action.afterScreenshot?.path,
            "package_name" to action.packageName
        ).filterValues { it != null && it.toString().isNotBlank() }
        return linkedMapOf(
            "src_ctx" to linkedMapOf(
                "page" to beforeXml,
                "screenshot" to action.beforeScreenshot?.asMap(),
                "screenshot_path" to action.beforeScreenshot?.path,
                "package_name" to action.packageName,
                "require_unique_action_signature" to false
            ).filterValues { it != null && it.toString().isNotBlank() },
            "dst_ctx" to dstCtx.takeIf { it.isNotEmpty() },
            "action" to actionMap,
            "_oob_meta" to linkedMapOf(
                "mode" to "manual_operation_recording",
                "recording_backend" to recordingBackend,
                "event_context" to action.eventContext.takeIf { it.isNotEmpty() }
            ).filterValues { it != null }
        ).filterValues { it != null }
    }

    private fun handleVlmStepStarted(index: Int, step: UIStep) {
        val context = taskContext ?: return
        val cardId = vlmStepCardId(index)
        val card = buildInternalRunLogCard(
            index = index,
            step = step,
            status = "running",
            successOverride = null
        )
        InternalRunLogStore.upsertCard(
            context = context,
            runId = id,
            cardId = cardId,
            card = card
        )
        notifyVlmToolEvent(
            streamKind = "tool_started",
            index = index,
            card = card,
            step = step,
            status = "running",
            success = null,
            errorMessage = null
        )
    }

    private fun handleVlmStepCompleted(
        index: Int,
        step: UIStep,
        success: Boolean,
        errorMessage: String?
    ) {
        val context = taskContext ?: return
        val status = if (success) "success" else "error"
        val cardId = vlmStepCardId(index)
        val card = buildInternalRunLogCard(
            index = index,
            step = step,
            status = status,
            successOverride = success,
            errorMessage = errorMessage
        )
        InternalRunLogStore.upsertCard(
            context = context,
            runId = id,
            cardId = cardId,
            card = card
        )
        completedVlmStepCardIds.add(cardId)
        notifyVlmToolEvent(
            streamKind = "tool_completed",
            index = index,
            card = card,
            step = step,
            status = status,
            success = success,
            errorMessage = errorMessage
        )
    }

    private fun buildInternalRunLogCard(
        index: Int,
        step: UIStep,
        status: String = "success",
        successOverride: Boolean? = null,
        errorMessage: String? = null
    ): Map<String, Any?> {
        val actionParams = actionParams(step.action)
        val durationMs = if (step.startedAtMs != null && step.finishedAtMs != null) {
            (step.finishedAtMs - step.startedAtMs).coerceAtLeast(0L)
        } else {
            null
        }
        val title = step.thought.trim().ifEmpty { actionTitle(step.action) }
        val success = successOverride ?: (step.action !is AbortAction)
        val cardId = vlmStepCardId(index)
        val tokenUsage = step.tokenUsage?.let(VLMTokenUsageMapper::toRunLogMap)
            ?.takeIf { it.isNotEmpty() }
        val tokenUsageAttempts = step.tokenUsageAttempts
            .map(VLMTokenUsageMapper::toRunLogMap)
            .filter { it.isNotEmpty() }
        val pageDiagnostics = step.pageDiagnostics.takeIf { it.isNotEmpty() }
        val postActionObservation = VLMPostActionObservation.summarize(step)
        val postActionObservationMap = postActionObservation?.toRunLogMap()
        val actionResultData = step.actionResultData?.toRunLogAny()
        val header = linkedMapOf<String, Any?>(
            "step_index" to index,
            "title" to title,
            "tool_name" to step.action.name,
            "status" to status,
            "success" to success
        )
        durationMs?.let { header["duration_ms"] = it }
        tokenUsage?.let {
            header["token_usage"] = it
            header["token_usage_total"] = it["total_tokens"]
        }
        return linkedMapOf(
            "card_id" to cardId,
            "tool_call_id" to cardId,
            "header" to header,
            "step_index" to index,
            "title" to title,
            "summary" to step.summary,
            "tool_name" to step.action.name,
            "toolName" to step.action.name,
            "tool_type" to "vlm",
            "toolType" to "vlm",
            "status" to status,
            "action_type" to step.action.name,
            "success" to success,
            "error_message" to errorMessage,
            "duration_ms" to durationMs,
            "started_at_ms" to step.startedAtMs,
            "finished_at_ms" to step.finishedAtMs,
            "package_name" to step.packageName,
            "page_diagnostics" to pageDiagnostics,
            "action_result_data" to actionResultData,
            "function_result" to actionResultData.takeIf { step.action is FunctionRunAction },
            "token_usage" to tokenUsage,
            "token_usage_attempts" to tokenUsageAttempts.takeIf { it.isNotEmpty() },
            "recall_kind" to "vlm_step",
            "tool_call" to linkedMapOf(
                "id" to cardId,
                "name" to step.action.name,
                "arguments" to actionParams
            ),
            "params" to actionParams,
            "result" to linkedMapOf<String, Any?>(
                "message" to step.result,
                "summary" to step.summary,
                "post_action_observation" to postActionObservationMap,
                "screen_changed" to postActionObservation?.screenChanged,
                "package_changed" to postActionObservation?.packageChanged,
                "after_visible_texts" to postActionObservation?.afterVisibleTexts?.takeIf { it.isNotEmpty() },
                "appeared_texts" to postActionObservation?.appearedTexts?.takeIf { it.isNotEmpty() },
                "disappeared_texts" to postActionObservation?.disappearedTexts?.takeIf { it.isNotEmpty() },
                "after_focused_editable" to postActionObservation?.afterFocusedEditable,
                "observation_summary" to postActionObservation?.summaryText,
                "data" to actionResultData,
                "action_result_data" to actionResultData,
                "function_result" to actionResultData.takeIf { step.action is FunctionRunAction },
                "page_diagnostics" to pageDiagnostics
            ).filterValues { it != null },
            "before" to linkedMapOf(
                "observation" to step.observation,
                "observation_xml" to step.observationXml,
                "package_name" to step.packageName
            ),
            "after" to linkedMapOf(
                "summary" to step.summary,
                "result" to step.result,
                "observation_xml" to step.afterObservationXml,
                "package_name" to (step.afterPackageName?.takeIf { it.isNotBlank() } ?: step.packageName)
            )
        )
    }

    private fun vlmStepCardId(index: Int): String = "$id-vlm-${index + 1}"

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

    private fun notifyVlmToolEvent(
        streamKind: String,
        index: Int,
        card: Map<String, Any?>,
        step: UIStep,
        status: String,
        success: Boolean?,
        errorMessage: String?
    ) {
        val actionParams = actionParams(step.action)
        val argsJson = runCatching { JSONObject(actionParams).toString() }
            .getOrDefault("{}")
        val resultJson = runCatching {
            val tokenUsage = step.tokenUsage?.let(VLMTokenUsageMapper::toRunLogMap)
                ?.takeIf { it.isNotEmpty() }
            val postActionObservation = VLMPostActionObservation.summarize(step)
            val postActionObservationMap = postActionObservation?.toRunLogMap()
            val pageDiagnostics = step.pageDiagnostics.takeIf { it.isNotEmpty() }
            val actionResultData = step.actionResultData?.toRunLogAny()
            JSONObject(
                linkedMapOf<String, Any?>(
                    "message" to step.result,
                    "summary" to step.summary,
                    "status" to status,
                    "success" to success,
                    "error_message" to errorMessage,
                    "package_name" to step.packageName,
                    "page_diagnostics" to pageDiagnostics,
                    "token_usage" to tokenUsage,
                    "token_usage_total" to tokenUsage?.get("total_tokens"),
                    "post_action_observation" to postActionObservationMap,
                    "screen_changed" to postActionObservation?.screenChanged,
                    "package_changed" to postActionObservation?.packageChanged,
                    "after_visible_texts" to postActionObservation?.afterVisibleTexts?.takeIf { it.isNotEmpty() },
                    "appeared_texts" to postActionObservation?.appearedTexts?.takeIf { it.isNotEmpty() },
                    "disappeared_texts" to postActionObservation?.disappearedTexts?.takeIf { it.isNotEmpty() },
                    "after_focused_editable" to postActionObservation?.afterFocusedEditable,
                    "observation_summary" to postActionObservation?.summaryText,
                    "data" to actionResultData,
                    "action_result_data" to actionResultData,
                    "function_result" to actionResultData.takeIf { step.action is FunctionRunAction },
                    "recall_kind" to "vlm_step"
                ).filterValues { it != null }
            ).toString()
        }.getOrDefault("{}")
        val tokenUsage = step.tokenUsage?.let(VLMTokenUsageMapper::toRunLogMap)
            ?.takeIf { it.isNotEmpty() }
        val pageDiagnostics = step.pageDiagnostics.takeIf { it.isNotEmpty() }
        val cardId = vlmStepCardId(index)
        val title = card["title"]?.toString()?.trim().orEmpty()
        val summary = listOf(step.summary, step.result, title)
            .firstOrNull { it?.trim()?.isNotEmpty() == true }
            ?.trim()
            .orEmpty()
        val payload = linkedMapOf<String, Any?>(
            "agentStreamKind" to streamKind,
            "taskId" to id,
            "runLogId" to id,
            "run_id" to id,
            "cardId" to cardId,
            "toolCallId" to cardId,
            "toolName" to step.action.name,
            "displayName" to step.action.name,
            "toolType" to "vlm",
            "toolTitle" to title,
            "status" to status,
            "summary" to summary,
            "progress" to summary,
            "argsJson" to argsJson,
            "args" to argsJson,
            "resultPreviewJson" to if (streamKind == "tool_completed") resultJson else "",
            "rawResultJson" to if (streamKind == "tool_completed") resultJson else "",
            "success" to success,
            "actionResultData" to step.actionResultData?.toString().takeIf { streamKind == "tool_completed" },
            "action_result_data" to step.actionResultData?.toString().takeIf { streamKind == "tool_completed" },
            "stepIndex" to index,
            "startedAtMs" to step.startedAtMs,
            "started_at_ms" to step.startedAtMs,
            "finishedAtMs" to step.finishedAtMs,
            "finished_at_ms" to step.finishedAtMs,
            "durationMs" to card["duration_ms"],
            "duration_ms" to card["duration_ms"],
            "pageDiagnostics" to pageDiagnostics,
            "page_diagnostics" to pageDiagnostics,
            "tokenUsage" to tokenUsage,
            "token_usage" to tokenUsage,
            "tokenUsageTotal" to tokenUsage?.get("total_tokens"),
            "token_usage_total" to tokenUsage?.get("total_tokens"),
            "spanKind" to "vlm_step",
            "span_kind" to "vlm_step",
            "parentSpanKind" to "vlm_task",
            "parent_span_kind" to "vlm_task",
            "recall_kind" to "vlm_step"
        ).filterValues { it != null }
        try {
            onMessagePushListener?.onVlmToolEvent(payload)
        } catch (e: Exception) {
            OmniLog.e(Tag, "通知VLM步骤事件失败: ${e.message}")
        }
    }

    private fun actionTitle(action: UIAction): String {
        return when (action) {
            is ClickAction -> "点击 ${action.targetDescription}"
            is InputTextAction -> "输入文本 ${action.targetDescription}"
            is SwipeAction -> "滚动 ${action.targetDescription}"
            is LongPressAction -> "长按 ${action.targetDescription}"
            is OpenAppAction -> "打开应用"
            is PressKeyAction -> when (action.key.lowercase()) {
                "home" -> "返回桌面"
                "back" -> "返回"
                "enter" -> "确认"
                else -> "按键 ${action.key}"
            }
            is WaitAction -> "等待"
            is GetStateAction -> "刷新页面状态"
            is FunctionRunAction -> "执行工具 ${action.functionId}"
            is RecordAction -> "记录信息"
            is FinishedAction -> "完成任务"
            is InfoAction -> "请求用户协助"
            is AbortAction -> "中止任务"
        }
    }

    private fun actionParams(action: UIAction): Map<String, Any?> {
        return when (action) {
            is ClickAction -> linkedMapOf(
                "target_description" to action.targetDescription,
                "x" to action.x,
                "y" to action.y
            )
            is InputTextAction -> linkedMapOf(
                "target_description" to action.targetDescription,
                "text" to action.text,
                "x" to action.x,
                "y" to action.y
            )
            is SwipeAction -> linkedMapOf(
                "target_description" to action.targetDescription,
                "x1" to action.x1,
                "y1" to action.y1,
                "x2" to action.x2,
                "y2" to action.y2,
                "duration_ms" to action.durationMs
            )
            is LongPressAction -> linkedMapOf(
                "target_description" to action.targetDescription,
                "x" to action.x,
                "y" to action.y
            )
            is OpenAppAction -> linkedMapOf("package_name" to action.packageName)
            is PressKeyAction -> linkedMapOf("key" to action.key)
            is WaitAction -> linkedMapOf(
                "time_s" to action.timeS,
                "duration_ms" to action.durationMs
            )
            is GetStateAction -> linkedMapOf("reason" to action.reason)
            is FunctionRunAction -> linkedMapOf(
                "function_id" to action.functionId,
                "arguments" to action.arguments.toRunLogAny()
            )
            is RecordAction -> linkedMapOf("content" to action.content)
            is FinishedAction -> linkedMapOf("content" to action.content)
            is InfoAction -> linkedMapOf("value" to action.value)
            is AbortAction -> linkedMapOf("value" to action.value)
        }
    }

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
        disableOmniFlowRecall: Boolean = false
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
                    disableOmniFlowRecall = disableOmniFlowRecall
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
                            .mapNotNull { (it.action as? FunctionRunAction)?.functionId }
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
                ?: (finishedFromTrace?.action as? FinishedAction)?.content.orEmpty()
            val prompt = PromptTemplate.summaryPrompt(goal)

            val modelToUse = "scene.vlm.summary"
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

    override suspend fun slideCoordinateWithContext(
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

    fun finishTask() {
        OmniLog.d(Tag, "Finishing VLM Operation Task")
        isCancellationRequested = true
        unblockWaitingReceivers()
        cancelRunningJob("任务已取消")
        finalizeCancellationAsync("任务已取消")
    }

    fun completeByUser(message: String = "任务已完成") {
        OmniLog.d(Tag, "Completing VLM Operation Task by user")
        isCancellationRequested = true
        unblockWaitingReceivers()
        cancelRunningJob(message)
        finalizeUserCompletionAsync(message)
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
