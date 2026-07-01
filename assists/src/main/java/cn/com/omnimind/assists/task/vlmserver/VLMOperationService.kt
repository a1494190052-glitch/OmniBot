package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
import cn.com.omnimind.baselib.http.Http429Exception
import cn.com.omnimind.baselib.llm.contentText
import cn.com.omnimind.baselib.runlog.OobActionSchema
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.baselib.util.exception.PrivacyBlockedException
import cn.com.omnimind.assists.util.TreeEditDistance
import cn.com.omnimind.assists.util.pollUntilReady
import cn.com.omnimind.baselib.util.ImageCompressor
import cn.com.omnimind.baselib.util.ImageQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToInt

/**
 * VLM操作服务 - 统一的UI自动化服务入口
 * 对应Python中的ExplorerExpert，提供完整的VLM驱动的UI操作能力
 */
class VLMOperationService(
    private val deviceOperator: DeviceOperator,
    private val streamClient: VLMStreamClient,
    private val onInfoAction: suspend (String) -> String,
    private val onPauseCheck: suspend () -> Unit = {},
    private val onStepStarted: suspend (Int, UIStep) -> Unit = { _, _ -> },
    private val onStepCompleted: suspend (Int, UIStep, Boolean, String?) -> Unit = { _, _, _, _ -> },
    private val isSubTask: Boolean = false,
    private val taskId: String = "",
    private val runId: String = "",
    private val taskScope: CoroutineScope? = null,
    private val functionRunExecutor: FunctionRunExecutor? = null,
) {
    private data class XmlHealth(
        val nodeCount: Int,
        val largestArea: Int,
        val hasAppSizedRoot: Boolean
    ) {
        val isUsable: Boolean
            get() = nodeCount >= MIN_USABLE_XML_NODE_COUNT ||
                largestArea >= MIN_USABLE_XML_AREA ||
                hasAppSizedRoot
    }

    private val Tag = "VLMOperationService"
    private val vlmClient = VLMClient()
    private val contextManager = UIContextManager()
    private val actionExecutor = ActionExecutor(deviceOperator, contextManager, functionRunExecutor)
    private val logJson = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        classDiscriminator = "action_type"
    }

    // Context Compactor Agent
    private val compactorAgent = CompactorAgent()

    // Loading Sprite Agent (赛博精灵加载状态生成器)
    private val loadingSpriteAgent = LoadingSpriteAgent()

    // Compactor 触发步数记录
    private val compactorTriggerSteps = mutableListOf<Int>()

    // 解析失败计数器
    private var parseFailureCount = 0
    private val maxParseFailures = 3
    private val externalMemories = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val conversationState = VLMConversationState()
    private var lastReasoningOverlay = ""
    private var lastReasoningOverlayAt = 0L
    private var lastUsableXml: String? = null
    private var disableFunctionRecallForCurrentTask = false

    // Priority event management
    private var priorityEvent: Triple<String, String, Boolean>? = null  // (message, type, suggestCompletion)

    private fun resetConversationState() {
        conversationState.clear()
        lastReasoningOverlay = ""
        lastReasoningOverlayAt = 0L
    }

    fun addExternalMemory(memory: String) {
        val trimmed = memory.trim()
        if (trimmed.isEmpty()) return
        externalMemories.add(trimmed)
    }

    /**
     * Add a priority event that will be displayed prominently in the next VLM prompt
     * @param message The event message
     * @param eventType The event type (e.g., "file_received")
     * @param suggestCompletion Whether to suggest VLM complete the task
     */
    fun addPriorityEvent(message: String, eventType: String, suggestCompletion: Boolean = false) {
        priorityEvent = Triple(message, eventType, suggestCompletion)
        OmniLog.i(Tag, "Added priority event: type=$eventType, suggestCompletion=$suggestCompletion, msg=$message")
    }

    private fun drainExternalMemories(context: UIContext): UIContext {
        var updatedContext = context

        // Drain regular external memories
        if (externalMemories.isNotEmpty()) {
            val drained = ArrayList<String>()
            while (true) {
                val item = externalMemories.poll() ?: break
                drained.add(item)
            }
            if (drained.isNotEmpty()) {
                updatedContext = updatedContext.copy(keyMemory = context.keyMemory + drained)
            }
        }

        // Drain priority event
        val event = priorityEvent
        if (event != null) {
            updatedContext = updatedContext.copy(
                priorityEvent = event.first,
                priorityEventType = event.second,
                suggestCompletion = event.third
            )
            priorityEvent = null  // Clear after consuming
            OmniLog.i(Tag, "Priority event consumed: ${event.second}")
        }

        return updatedContext
    }

    /**
     * 计算下一次 compactor 触发的步数
     * 触发间隔随着总步数增加而减少，从 12 步开始，逐渐减少到 5 步
     *
     * @param totalSteps 当前总步数
     * @param lastTriggerStep 上一次触发的步数
     * @return 下一次应该触发的步数，如果没有达到触发条件则返回 null
     */
    private fun getNextCompactorTriggerStep(totalSteps: Int, lastTriggerStep: Int): Int? {
        // 计算动态触发间隔
        // 策略：从 12 步开始，随着总步数增加，间隔逐渐减少到 5 步
        // - 第一次触发在第 12 步
        // - 之后间隔逐渐从 8 步减少到 5 步
        // - 公式：interval = max(5, 8 - (totalSteps / 25))

        val interval = maxOf(5, 8 - (totalSteps / 25))

        // 第一次触发在 12 步
        val firstTriggerStep = 12

        val nextTriggerStep = if (compactorTriggerSteps.isEmpty()) {
            firstTriggerStep
        } else {
            lastTriggerStep + interval
        }

        return if (totalSteps >= nextTriggerStep) nextTriggerStep else null
    }

    /**
     * 检查当前步骤是否应该触发 compactor
     * @param currentStep 当前步骤索引（0-based）
     * @param maxSteps 最大步数（保留参数兼容，当前未使用）
     * @return 是否应该触发
     */
    private fun shouldTriggerCompactor(currentStep: Int, maxSteps: Int?): Boolean {
        val totalSteps = currentStep + 1

        // 如果已经记录过这个触发点，则跳过
        if (totalSteps in compactorTriggerSteps) return false

        val lastTriggerStep = compactorTriggerSteps.lastOrNull() ?: 0
        val nextTriggerStep = getNextCompactorTriggerStep(totalSteps, lastTriggerStep)

        if (nextTriggerStep != null && totalSteps == nextTriggerStep) {
            compactorTriggerSteps.add(totalSteps)
            return true
        }

        return false
    }

    private suspend fun ensureTaskActive(stage: String) {
        currentCoroutineContext().ensureActive()
    }

    private suspend fun safePauseCheck(stage: String) {
        ensureTaskActive("before_pause_check_$stage")
        try {
            onPauseCheck()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OmniLog.e(Tag, "暂停检查异常($stage): ${e.message}")
        }
        ensureTaskActive("after_pause_check_$stage")
    }

    /**
     * 执行完整的任务流程
     */
    suspend fun executeTask(
        goal: String,
        installedApps: Map<String, String> = emptyMap(),
        model: String = VLMRuntimeConfigRegistry.get().primarySceneId,
        maxSteps: Int? = null,
        packageName: String? = null,
        skipGoHome: Boolean = false,
        summary: Boolean = false,
        currentStepGoal: String = goal,
        stepSkillGuidance: String = "",
        disableFunctionRecall: Boolean = false
    ): TaskExecutionReport {

        val normalizedMaxSteps = maxSteps?.takeIf { it > 0 }
            ?: VLMRuntimeConfigRegistry.get().defaultMaxSteps
        OmniLog.d(Tag, "executeTask - package_name: $packageName, skipGoHome: $skipGoHome")

        // 重置 Compactor 触发记录
        compactorTriggerSteps.clear()
        resetConversationState()
        disableFunctionRecallForCurrentTask = disableFunctionRecall
        ensureTaskActive("execute_task_start")

        // 任务开始执行时，先回到手机首页Home（除非 skipGoHome = true）
        if (!skipGoHome) {
            if (packageName != null && packageName.isNotEmpty()) {
                try {
                    ensureTaskActive("before_launch_application")
                    val launchResult = actionExecutor.act(
                        action = OobActionSchema.TOOL_OPEN_APP,
                        args = mapOf(OobActionSchema.ARG_PACKAGE_NAME to packageName),
                        source = "vlm_task_start",
                    )
                    if (launchResult.success) {
                        OmniLog.d(Tag, "成功拉起应用: $packageName")
                        pollUntilReady(intervalMs = 100L, timeoutMs = 3000L) {
                            AccessibilityController.Companion.getPackageName()?.takeIf { it == packageName }
                        }
                        ensureTaskActive("after_launch_application_delay")
                    } else {
                        OmniLog.e(Tag, "拉起应用失败: ${launchResult.message}")
                    }
                } catch (e: PrivacyBlockedException) {
                    // 隐私限制异常，继续抛出异常
                    OmniLog.e(Tag, "应用因隐私设置被阻止: ${e.message}")
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    OmniLog.e(Tag, "拉起应用异常: ${e.message}")
                }
            }
        } else {
            OmniLog.d(Tag, "skipGoHome=true，跳过回到桌面和启动应用的操作")
        }

        var context = contextManager.initializeContext(
            overallTask = goal,
            installedApplications = installedApps,
            targetPackageName = packageName.orEmpty(),
            maxSteps = normalizedMaxSteps,
            currentStepGoal = currentStepGoal,
            stepSkillGuidance = stepSkillGuidance
        )
        context = drainExternalMemories(context)
        val executionTrace = mutableListOf<UIStep>()
        var lastError: String? = null
        val summaryScreenshotList =
            mutableListOf<String>() //prepare for summary,screenshot before action
        // 内部传递用 scene.xxx 格式，不要提前解析
        var useModel = model

        // 预生成赛博精灵加载提示词（异步，不阻塞主流程）
        (taskScope ?: CoroutineScope(Dispatchers.IO)).launch {
            try {
                loadingSpriteAgent.prepareForTask(goal)
            } catch (e: Exception) {
                OmniLog.w(Tag, "预生成加载提示词失败: ${e.message}")
            }
        }

        var stepIndex = 0
        while (stepIndex < normalizedMaxSteps) {
            // 检查用户是否请求暂停（每一步执行前都检查）
            safePauseCheck("before_step_$stepIndex")
            context = drainExternalMemories(context)

            // === Context Compactor Logic (在超时计时之外执行) ===
            // 使用动态触发逻辑：随着步数增加，触发间隔逐渐从 12 步减少到 5 步
            if (shouldTriggerCompactor(stepIndex, normalizedMaxSteps)) {
                try {
                    OmniLog.i(Tag, "触发上下文压缩与纠错 (Trace size: ${context.trace.size})")

                    // 显示赛博精灵加载提示词（从预生成列表获取）
                    if (!isSubTask) {
                        val loadingPhrase = loadingSpriteAgent.getNextPhrase()
                        deviceOperator.showInfo(loadingPhrase)
                    }

                    // 截图用于 Compactor 分析
                    ensureTaskActive("before_compactor_screenshot_$stepIndex")
                    val compactorScreenshot = deviceOperator.captureScreenshot()
                    ensureTaskActive("after_compactor_screenshot_$stepIndex")

                    // 1. 调用 Compactor Agent
                    val compactorResult = compactorAgent.compact(
                        goal = context.activeGoal(),
                        currentScreenshot = compactorScreenshot,
                        trace = context.trace,
                        existingRunningSummary = context.runningSummary,
                        needSummary = summary
                    )
                    ensureTaskActive("after_compactor_$stepIndex")

                    // 2. 处理纠错建议
                    var newSummary = compactorResult.summary
                    if (compactorResult.needsCorrection && !compactorResult.correctionGuidance.isNullOrBlank()) {
                        OmniLog.w(Tag, "Compactor检测到错误，添加纠错建议: ${compactorResult.correctionGuidance}")
                        newSummary += "\n\n[Correction Guidance]: ${compactorResult.correctionGuidance}"
                    }

                    // 3. 更新 Context (替换 trace 为结构化信息)
                    context = context.copy(
                        runningSummary = newSummary,
                        currentState = compactorResult.currentState,
                        nextStepHint = compactorResult.nextStep ?: "",
                        completedMilestones = compactorResult.completedMilestones,
                        keyMemory = context.keyMemory + compactorResult.keyMemory,
                        trace = emptyList(),
                        stepsUsed = 0
                    )
                    OmniLog.i(Tag, "上下文压缩完成，Running Summary Updated.")

                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    OmniLog.e(Tag, "Context Compaction Failed: ${e.message}")
                    // 失败时不阻断流程，继续使用原有 Context
                }
            }
            // === Context Compactor Logic (End) ===

            val result = executeSingleStepWithTimeOut(context, useModel, summary, stepIndex)
            ensureTaskActive("after_single_step_$stepIndex")

            if (!result.success) {
                result.step?.let { failedStep ->
                    val stepJson =
                        runCatching { logJson.encodeToString(failedStep) }.getOrElse { failedStep.toString() }
                    OmniLog.d(Tag, "Step $stepIndex detail (failure): $stepJson")
                }
                OmniLog.e(
                    Tag,
                    "Step $stepIndex failed: ${result.error ?: "unknown error"}; step=${result.step?.action?.name}"
                )

                // 使用 result.context，并将当前 step 添加到 trace 中
                context = result.context
                if (result.step != null) {
                    context = updateContext(result.step, context)
                    executionTrace.add(result.step)
                    onStepCompleted(stepIndex, result.step, false, result.error)
                }
                lastError = result.error

                if (parseFailureCount >= maxParseFailures) {
                    return TaskExecutionReport(
                        success = false,
                        goal = goal,
                        totalSteps = stepIndex + 1,
                        executionTrace = executionTrace,
                        finalContext = context,
                        error = "VLM解析失败次数超过限制(${maxParseFailures}次)，任务终止"
                    )
                }

                if (result.step?.action?.isRecoverableDeviceAction() == true) {
                    if (result.step?.action is FunctionRunAction) {
                        disableFunctionRecallForCurrentTask = true
                    }
                    stepIndex++
                    continue
                } else {
                    break
                }
            }

            val step = result.step!!

            runCatching { logJson.encodeToString(step) }
                .onSuccess { OmniLog.d(Tag, "Step $stepIndex detail: $it") }
                .onFailure { OmniLog.w(Tag, "Step $stepIndex log encode failed: ${it.message}") }

            OmniLog.d(
                Tag,
                "Step $stepIndex success: action=${step.action.name} result=${step.result ?: "OK"}"
            )

            // 使用 result.context，并将当前 step 添加到 trace 中
            context = result.context
            context = updateContext(step, context)
            executionTrace.add(step)
            if (summary) {
                val resizedScreenshot = result.screenshot?.let {
                    // 已经是第二次压缩
                    ImageCompressor.compressBase64Image(it, ImageQuality.SUMMARY).base64
                }
                // 只在 screenshot 不为 null 时才添加
                val screenshotToAdd = resizedScreenshot ?: result.screenshot
                if (screenshotToAdd != null) {
                    summaryScreenshotList.add(screenshotToAdd)
                } else {
                    OmniLog.w(Tag, "Step ${stepIndex} screenshot is null, skipped adding to summary list")
                }
            }

            if (step.action is FinishedAction) {
                return TaskExecutionReport(
                    success = true,
                    goal = goal,
                    totalSteps = stepIndex + 1,
                    executionTrace = executionTrace,
                    finalContext = context,
                    error = null,
                    summaryScreenshotList = summaryScreenshotList
                )
            }
            if (step.action is InfoAction) {
                try {
                    //接管需要将任务执行时间清空

                    val userAnswer = onInfoAction(step.action.value)
                    ensureTaskActive("after_info_action_$stepIndex")

                    conversationState.updateLastRoundResult("用户回复：$userAnswer")
                    val userReplyStep = UIStep(
                        observation = "用户回复：$userAnswer",
                        thought = "收到用户回复，继续执行任务",
                        action = RecordAction(content = "用户回答了：$userAnswer"),
                        result = "已记录用户回复"
                    )
                    context = updateContext(userReplyStep, context)
                    executionTrace.add(userReplyStep)
                    stepIndex++
                    continue
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return TaskExecutionReport(
                        success = false,
                        goal = goal,
                        totalSteps = stepIndex + 1,
                        executionTrace = executionTrace,
                        finalContext = context,
                        error = "INFO动作处理失败: ${e.message}"
                    )
                }
            }
            if (step.action is AbortAction) {
                onStepCompleted(
                    stepIndex,
                    step,
                    false,
                    "任务终止: ${(step.action as AbortAction).value}"
                )
                return TaskExecutionReport(
                    success = false,
                    goal = goal,
                    totalSteps = stepIndex + 1,
                    executionTrace = executionTrace,
                    finalContext = context,
                    error = "任务终止: ${(step.action as AbortAction).value}"
                )
            }
            if (VLMFunctionRunCompletionPolicy.shouldFinishAfterSuccessfulFunction(step)) {
                return TaskExecutionReport(
                    success = true,
                    goal = goal,
                    totalSteps = stepIndex + 1,
                    executionTrace = executionTrace,
                    finalContext = context,
                    error = null,
                    summaryScreenshotList = summaryScreenshotList,
                    doneReason = "call_tool_completed"
                )
            }
            stepIndex++
        }

        val reachedMaxSteps = stepIndex >= normalizedMaxSteps
        return TaskExecutionReport(
            success = false,
            goal = goal,
            totalSteps = executionTrace.size,
            executionTrace = executionTrace,
            finalContext = context,
            error = lastError ?: if (reachedMaxSteps) "达到最大步数，任务未完成" else "任务未完成",
            summaryScreenshotList = summaryScreenshotList,
            doneReason = if (reachedMaxSteps) "max_steps_reached" else "error"
        )
    }

    private fun updateContext(step: UIStep, context: UIContext): UIContext {
        return if (step.action is RecordAction) {
            contextManager.addKeyMemory(
                contextManager.updateContext(context, step),
                step.action.content
            )
        } else {
            contextManager.updateContext(context, step)
        }
    }

    suspend fun executeSingleStepWithTimeOut(
        context: UIContext,
        useModel: String = VLMRuntimeConfigRegistry.get().primarySceneId,
        summary: Boolean,
        stepIndex: Int = 0
    ): VLMOperationResult {
        return try {
            withTimeout(SINGLE_STEP_TIMEOUT_MS) {
                executeSingleStep(context, useModel, summary, stepIndex)
            }
        } catch (e: TimeoutCancellationException) {
            VLMOperationResult(
                success = false,
                error = "单步执行超时 (${SINGLE_STEP_TIMEOUT_MS / 1000}s)",
                step = null,
                context = context
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: PrivacyBlockedException) {
            throw e
        } catch (e: Exception) {
            VLMOperationResult(
                success = false,
                error = "执行单步任务异常: ${e.message}",
                step = null,
                context = context
            )
        }
    }

    private suspend fun captureCurrentPageSnapshot(stage: String): VLMCurrentPageSnapshot {
        ensureTaskActive("before_screenshot_$stage")
        val (screenshot, currentXml) = coroutineScope {
            val s = async { deviceOperator.captureScreenshot() }
            val x = async { captureCurrentXml() }
            s.await() to x.await()
        }
        safePauseCheck("after_capture_$stage")
        return VLMCurrentPageSnapshot(
            packageName = AccessibilityController.Companion.getPackageName(),
            xml = currentXml,
            screenshotBase64 = screenshot,
            displayWidth = deviceOperator.getDisplayWidth(),
            displayHeight = deviceOperator.getDisplayHeight(),
            capturedAtMs = System.currentTimeMillis()
        )
    }

    suspend fun executeSingleStep(
        context: UIContext,
        model: String = VLMRuntimeConfigRegistry.get().primarySceneId,
        summary: Boolean,
        stepIndex: Int = 0
    ): VLMOperationResult {
        // 内部传递都用 scene.xxx 格式，只在需要判断模型类型或发送请求时才解析

        val maxRetries = 3
        // 创建可变的工作变量
        var _context = context
        val tokenUsageAttempts = mutableListOf<VLMTokenUsage>()
        var tokenUsageRequestIndex = 0

        fun usageAggregate(): VLMTokenUsage? = VLMTokenUsageMapper.aggregate(tokenUsageAttempts)

        fun usageAttemptsSnapshot(): List<VLMTokenUsage> = tokenUsageAttempts.toList()
        val phaseMs = linkedMapOf<String, Long>()

        fun markPhase(name: String, startedAtMs: Long) {
            phaseMs[name] = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
        }

        fun phaseDiagnostics(): Map<String, String> =
            phaseMs.mapValues { it.value.toString() }

        var stabilityAttempt = 0
        while (stabilityAttempt < maxRetries) {
            try {
                safePauseCheck("before_attempt_$stabilityAttempt")

                OmniLog.d(
                    Tag,
                    "executeSingleStep: stabilityAttempt=$stabilityAttempt, overallTask=${_context.overallTask}, currentStepGoal=${_context.activeGoal()}"
                )
                val observeStartedAt = System.currentTimeMillis()
                val pageSnapshot = captureCurrentPageSnapshot("attempt_$stabilityAttempt")
                markPhase("fresh_observe_ms", observeStartedAt)
                val screenshot = pageSnapshot.screenshotBase64
                val beforeXml = pageSnapshot.xml
                _context = _context.copy(
                    currentPageSummary = "",
                    firstStepGuidance = "",
                    pageDiagnostics = emptyMap(),
                    dynamicToolDefinitions = emptyList(),
                    displayWidth = pageSnapshot.displayWidth,
                    displayHeight = pageSnapshot.displayHeight,
                    currentPackageName = pageSnapshot.packageName.orEmpty(),
                )
                _context = _context.copy(pageDiagnostics = phaseDiagnostics())
                val stepRecallRequest = VLMRecallContextRequest(
                    context = _context,
                    currentXml = beforeXml,
                    currentPackageName = pageSnapshot.packageName,
                    screenshotBase64 = screenshot,
                    stepIndex = stepIndex,
                    snapshot = pageSnapshot,
                    disableFunctionRecall = disableFunctionRecallForCurrentTask,
                )
                val indexedEvidenceStartedAt = System.currentTimeMillis()
                _context = VLMIndexedPageContext.enrich(
                    context = _context,
                    currentXml = beforeXml,
                    displayWidth = pageSnapshot.displayWidth,
                    displayHeight = pageSnapshot.displayHeight
                )
                markPhase("indexed_evidence_ms", indexedEvidenceStartedAt)
                _context = _context.copy(pageDiagnostics = _context.pageDiagnostics + phaseDiagnostics())
                val recallContextStartedAt = System.currentTimeMillis()
                _context = VLMRecallContextProviderRegistry.enrich(
                    stepRecallRequest.copy(context = _context)
                )
                markPhase("recall_context_ms", recallContextStartedAt)
                _context = _context.copy(pageDiagnostics = _context.pageDiagnostics + phaseDiagnostics())
                // Note: Compactor 已移至 executeTask 主循环，在超时计时之外执行

                // Image-skip: 当 vlm_image_mode=auto 且 step>0 且页面有索引证据时跳过截图，减少 prompt tokens
                val runtimeConfig = VLMRuntimeConfigRegistry.get()
                val hasIndexedEvidence = _context.currentPageSummary.contains("OOB indexed evidence")
                val screenshotForLlm: String? = when {
                    runtimeConfig.imageMode == "auto" && stepIndex > 0 && hasIndexedEvidence -> null
                    else -> screenshot
                }
                OmniLog.d(Tag, "image_mode=${runtimeConfig.imageMode} stepIndex=$stepIndex hasIndexed=$hasIndexedEvidence useImage=${screenshotForLlm != null}")

                val maxToolCallRetries = 2
                var toolCallRetryCount = 0
                var retryState: VLMToolCallRetryState? = null
                var vlmResult: VLMResult? = null
                var sceneTurn: SceneChatCompletionTurn? = null
                var lastRequestEnvelope: VLMRequestEnvelope? = null
                var currentUserTextSnapshot = ""
                conversationState.updateStreamingReasoning("")
                lastReasoningOverlay = ""
                lastReasoningOverlayAt = 0L

                while (vlmResult == null) {
                    val requestBuildStartedAt = System.currentTimeMillis()
                    val requestEnvelope = vlmClient.buildUIOperationRequest(
                        context = _context,
                        screenshot = screenshotForLlm,
                        markedScreenshot = null,
                        conversationState = conversationState,
                        model = model,
                        retryState = retryState,
                        includeMarkedScreenshot = false
                    )
                    markPhase("request_build_ms", requestBuildStartedAt)
                    lastRequestEnvelope = requestEnvelope
                    currentUserTextSnapshot = requestEnvelope.currentUserText
                    _context = _context.copy(
                        pageDiagnostics = _context.pageDiagnostics +
                            phaseDiagnostics() +
                            _context.budgetDiagnostics() +
                            buildRequestEnvelopeDiagnostics(requestEnvelope)
                    )
                    OmniLog.i(
                        Tag,
                        "Dispatching VLM stream request: attempt=$stabilityAttempt toolRetry=$toolCallRetryCount scene=$model activeGoal=${_context.activeGoal()} traceSize=${_context.trace.size} historyRounds=${conversationState.roundCount()}"
                    )

                    safePauseCheck("before_http_${stabilityAttempt}_retry_$toolCallRetryCount")
                    val httpClientStartTime = System.currentTimeMillis()
                    val currentUsageAttemptIndex = ++tokenUsageRequestIndex
                    val streamedTurn = try {
                        streamClient.streamTurn(
                            request = requestEnvelope.request,
                            onReasoningUpdate = { reasoning ->
                                conversationState.updateStreamingReasoning(reasoning)
                                emitReasoningOverlay(reasoning)
                            }
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        markPhase("vlm_stream_ms", httpClientStartTime)
                        val streamFailure = VLMStreamFailureClassifier.classify(e)
                        val streamError = streamFailure.message
                        OmniLog.e(Tag, "VLM stream request failed: $streamError")
                        val failureStep = UIStep(
                            observation = "STREAM_ERROR",
                            thought = "VLM流式请求失败,忽略后面的action字段",
                            action = RecordAction(content = streamError),
                            result = "VLM流式请求失败",
                            tokenUsage = usageAggregate(),
                            tokenUsageAttempts = usageAttemptsSnapshot(),
                            pageDiagnostics = _context.pageDiagnostics +
                                phaseDiagnostics() +
                                streamFailure.diagnostics
                        )
                        return VLMOperationResult(
                            success = false,
                            error = streamError,
                            step = failureStep,
                            context = _context
                        )
                    }
                    markPhase("vlm_stream_ms", httpClientStartTime)
                    sceneTurn = streamedTurn
                    VLMTokenUsageMapper.fromTurn(
                        turn = streamedTurn,
                        attemptIndex = currentUsageAttemptIndex,
                        stabilityAttempt = stabilityAttempt,
                        toolRetryIndex = toolCallRetryCount
                    )?.let { usage ->
                        tokenUsageAttempts += usage
                        OmniLog.i(
                            Tag,
                            "VLM token usage: step=$stepIndex attempt=$currentUsageAttemptIndex " +
                                "prompt=${usage.promptTokens ?: 0} completion=${usage.completionTokens ?: 0} " +
                                "total=${usage.totalTokens ?: 0} cached=${usage.cachedTokens ?: 0}"
                        )
                    }
                    safePauseCheck("after_http_${stabilityAttempt}_retry_$toolCallRetryCount")
                    OmniLog.i(
                        "TimeRecord",
                        "VLM1-streamClient (stabilityAttempt:$stabilityAttempt toolRetry:$toolCallRetryCount) took ${System.currentTimeMillis() - httpClientStartTime} ms"
                    )
                    val rawResponsePreview = streamedTurn.turn.message.contentText()
                        .ifBlank { streamedTurn.turn.reasoning }
                        .take(4000)
                    OmniLog.d(
                        Tag,
                        "Raw VLM response: $rawResponsePreview"
                    )
                    OmniLog.d(
                        Tag,
                        "VLM stream finish_reason=${streamedTurn.turn.finishReason.orEmpty()} tool_calls=${streamedTurn.turn.message.toolCalls?.size ?: 0}"
                    )
                    val responseRoute = streamedTurn.route?.trim().orEmpty()
                    if (responseRoute.isNotEmpty()) {
                        OmniLog.i(Tag, "vlm_route=$responseRoute scene=$model")
                    }

                    // 解析链路统一由主场景解析器处理
                    val parseStartedAt = System.currentTimeMillis()
                    vlmResult = vlmClient.parseVLMResponse(
                        response = streamedTurn,
                        modelOrScene = model,
                        dynamicFunctionToolNames = requestEnvelope.dynamicFunctionToolNames,
                        dynamicFunctionToolMappings = requestEnvelope.dynamicFunctionToolMappings,
                    )
                    markPhase("parse_response_ms", parseStartedAt)
                    _context = _context.copy(pageDiagnostics = _context.pageDiagnostics + phaseDiagnostics())
                    safePauseCheck("after_parse_${stabilityAttempt}_retry_$toolCallRetryCount")

                    val parsedResult = vlmResult
                    if (
                        parsedResult.shouldRetryForToolCall &&
                        parsedResult.step == null &&
                        toolCallRetryCount < maxToolCallRetries
                    ) {
                        val thinkingText = buildThinkingOverlayText(parsedResult.thinking)
                        if (!isSubTask && thinkingText.isNotBlank()) {
                            emitReasoningOverlay(thinkingText)
                        }
                        toolCallRetryCount++
                        retryState = VLMToolCallRetryState(
                            retryIndex = toolCallRetryCount,
                            thinking = parsedResult.thinking ?: VLMThinkingContext(),
                            failureReason = parsedResult.error
                        )
                        val retryReason = parsedResult.error?.takeIf { it.isNotBlank() }
                            ?: "模型未返回标准 tool_calls"
                        OmniLog.w(
                            Tag,
                            "$retryReason，进入协议纠偏重试 $toolCallRetryCount/$maxToolCallRetries; finish_reason=${parsedResult.thinking?.finishReason.orEmpty()}"
                        )
                        vlmResult = null
                        continue
                    }

                    break
                }

                val resolvedVlmResult = vlmResult!!
                if (!resolvedVlmResult.success || resolvedVlmResult.step == null) {
                    parseFailureCount++
                    val resolvedError = resolveVlmFailureMessage(resolvedVlmResult)
                    OmniLog.e(
                        Tag,
                        "Parse VLM response failed (#$parseFailureCount): $resolvedError"
                    )
                    val finalThinkingText = buildThinkingOverlayText(resolvedVlmResult.thinking)
                    if (!isSubTask && finalThinkingText.isNotBlank()) {
                        emitReasoningOverlay(finalThinkingText)
                    }

                    val failureStep = UIStep(
                        observation = resolvedVlmResult.thinking?.observation?.ifBlank { "VLM响应解析失败" }
                            ?: "VLM响应解析失败",
                        thought = buildParseFailureThought(resolvedVlmResult),
                        action = RecordAction(content = "解析失败: $resolvedError"),
                        result = "解析失败，第${parseFailureCount}次失败",
                        tokenUsage = usageAggregate(),
                        tokenUsageAttempts = usageAttemptsSnapshot(),
                        pageDiagnostics = _context.pageDiagnostics + buildParseFailureDiagnostics(
                            sceneTurn = sceneTurn,
                            requestEnvelope = lastRequestEnvelope
                        )
                    )

                    return VLMOperationResult(
                        success = false,
                        error = resolvedError,
                        step = failureStep,
                        context = _context
                    )
                }

                var processedStep = resolvedVlmResult.step!!
                processedStep = normalizeOpenAppAction(processedStep, _context)

                when (processedStep.action) {
                    is ClickAction -> processedStep = updateActionWithCoordinates(
                        processedStep,
                        position = listOf(processedStep.action.x, processedStep.action.y)
                    )

                    is InputTextAction -> processedStep = updateActionWithCoordinates(
                        processedStep,
                        position = listOf(processedStep.action.x, processedStep.action.y)
                    )

                    is SwipeAction -> processedStep = updateActionWithCoordinates(
                        processedStep,
                        position = listOf(
                            processedStep.action.x1,
                            processedStep.action.y1,
                            processedStep.action.x2,
                            processedStep.action.y2
                        )
                    )

                    is LongPressAction -> processedStep = updateActionWithCoordinates(
                        processedStep,
                        position = listOf(processedStep.action.x, processedStep.action.y)
                    )

                    else -> {}
                }
                processedStep = groundIndexedActionTarget(
                    step = processedStep,
                    currentXml = beforeXml,
                    displayWidth = pageSnapshot.displayWidth,
                    displayHeight = pageSnapshot.displayHeight
                )

                var dispatchXml = beforeXml
                var dispatchPackageName =
                    pageSnapshot.packageName ?: AccessibilityController.Companion.getPackageName()
                if (needsPreciseLocation(processedStep.action)) {
                    val latestXml = captureCurrentXml()
                    if (!isPageStableByXml(beforeXml, latestXml) && !latestXml.isNullOrBlank()) {
                        OmniLog.d(
                            Tag,
                            "Pre-action page changed after VLM response; re-grounding ${processedStep.action.name} with latest XML instead of retrying"
                        )
                        dispatchXml = latestXml
                        dispatchPackageName = AccessibilityController.Companion.getPackageName()
                            ?: dispatchPackageName
                        processedStep = groundIndexedActionTarget(
                            step = processedStep,
                            currentXml = latestXml,
                            displayWidth = pageSnapshot.displayWidth,
                            displayHeight = pageSnapshot.displayHeight
                        )
                    }
                } else {
                    OmniLog.d(
                        Tag,
                        "Action ${processedStep.action.name} does not require precise location, skipping stability check"
                    )
                }

                safePauseCheck("before_action_${processedStep.action.name}_${stabilityAttempt}")
                ensureTaskActive("before_action_dispatch_${processedStep.action.name}_$stabilityAttempt")

                val actionStartedAtMs = System.currentTimeMillis()
                val beforePackageName = dispatchPackageName
                val runningStep = UIStep(
                    observation = processedStep.observation,
                    thought = processedStep.thought,
                    action = processedStep.action,
                    summary = processedStep.summary,
                    observationXml = dispatchXml,
                    packageName = beforePackageName,
                    startedAtMs = actionStartedAtMs,
                    tokenUsage = usageAggregate(),
                    tokenUsageAttempts = usageAttemptsSnapshot(),
                    pageDiagnostics = _context.pageDiagnostics
                )
                onStepStarted(stepIndex, runningStep)
                val actionDispatchStartedAt = System.currentTimeMillis()
                val executedStep = actionExecutor.act(
                    UIStep(
                        observation = processedStep.observation,
                        thought = processedStep.thought,
                        action = processedStep.action,
                        summary = processedStep.summary
                    ),
                    functionRunContext = VLMFunctionRunContext(
                        taskId = taskId,
                        runId = runId.ifBlank { taskId },
                    )
                )
                markPhase("action_dispatch_ms", actionDispatchStartedAt)
                val actionFinishedAtMs = System.currentTimeMillis()
                safePauseCheck("after_action_${processedStep.action.name}_${stabilityAttempt}")
                var finalStep = executedStep.copy(
                    summary = processedStep.summary,
                    observationXml = dispatchXml,
                    afterObservationXml = null,
                    actionResultData = executedStep.actionResultData,
                    packageName = beforePackageName,
                    afterPackageName = null,
                    startedAtMs = actionStartedAtMs,
                    finishedAtMs = actionFinishedAtMs,
                    tokenUsage = usageAggregate(),
                    tokenUsageAttempts = usageAttemptsSnapshot(),
                    pageDiagnostics = _context.pageDiagnostics + phaseDiagnostics() + executedStep.pageDiagnostics
                )
                if (processedStep.action is GetStateAction) {
                    finalStep = finalStep.copy(
                        result = buildGetStateResult(
                            reason = processedStep.action.reason,
                            currentXml = dispatchXml.orEmpty(),
                            packageName = beforePackageName,
                            activityName = runCatching {
                                AccessibilityController.Companion.getCurrentActivity()
                            }.getOrNull(),
                            installedApps = _context.installedApplications
                        ),
                        summary = processedStep.summary.ifBlank { "已刷新当前页面状态" }
                    )
                }
                OmniLog.d(
                    Tag,
                    "Execute action: ${finalStep.action.name}, result=${finalStep.result ?: "OK"}"
                )

                val actionSucceeded = finalStep.result?.startsWith(ACTION_FAILURE_PREFIX) != true
                if (finalStep.result?.contains("不支持的操作类型") == true) {
                    parseFailureCount++
                    onStepCompleted(
                        stepIndex,
                        finalStep,
                        false,
                        "不支持的操作类型: ${finalStep.result}"
                    )

                    return VLMOperationResult(
                        success = false,
                        error = "不支持的操作类型: ${finalStep.result}",
                        step = finalStep,
                        context = _context
                    )
                }

                parseFailureCount = 0
                onStepCompleted(
                    stepIndex,
                    finalStep,
                    actionSucceeded,
                    if (actionSucceeded) null else finalStep.result
                )
                sceneTurn?.let { completedTurn ->
                    conversationState.appendRound(
                        vlmClient.buildConversationRound(
                            currentUserText = currentUserTextSnapshot,
                            assistantTurn = completedTurn,
                            executedStep = finalStep
                        )
                    )
                }

                return VLMOperationResult(
                    success = true,
                    step = finalStep,
                    context = _context,
                    error = null,
                    screenshot = if (summary) screenshot else null
                )

            } catch (e: Http429Exception) {
                val failureStep = UIStep(
                    observation = "429",
                    thought = "服务端请求失败,忽略后面的action字段",
                    action = RecordAction(content = "服务端请求失败"),
                    result = "服务端请求失败",
                    tokenUsage = usageAggregate(),
                    tokenUsageAttempts = usageAttemptsSnapshot(),
                    pageDiagnostics = _context.pageDiagnostics
                )
                return VLMOperationResult(
                    success = false,
                    error = "!200",
                    step = failureStep,
                    context = _context
                )
            } catch (e: PrivacyBlockedException) {
                // 隐私限制异常，继续抛出异常
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                OmniLog.e(Tag, "执行异常，第${stabilityAttempt + 1}次重试: ${e.message}")
                if (stabilityAttempt >= maxRetries - 1) {
                    return VLMOperationResult(
                        success = false,
                        error = "操作执行异常: ${e.message}",
                        step = null,
                        context = _context
                    )
                }
                stabilityAttempt++  // 执行异常，增加重试计数
                delay(100L)
            }
        }

        return VLMOperationResult(
            success = false,
            error = "页面稳定性检测失败，达到最大重试次数",
            step = null,
            context = _context
        )
    }

    private fun buildThinkingOverlayText(thinking: VLMThinkingContext?): String {
        if (thinking == null) return ""
        val primary = thinking.reasoning.trim()
        if (primary.isBlank()) return ""
        return normalizeOverlayText(primary, maxLen = 320)
    }

    private suspend fun emitReasoningOverlay(reasoning: String) {
        if (isSubTask) return
        val normalized = normalizeOverlayText(reasoning, maxLen = 320)
        if (normalized.isBlank()) return
        val now = System.currentTimeMillis()
        val deltaLength = normalized.length - lastReasoningOverlay.length
        val shouldEmit = when {
            normalized == lastReasoningOverlay -> false
            lastReasoningOverlay.isBlank() -> true
            deltaLength >= 18 -> true
            normalized.endsWith("\n") || normalized.endsWith("。") || normalized.endsWith(".") -> true
            now - lastReasoningOverlayAt >= 350L -> true
            else -> false
        }
        if (!shouldEmit) return
        lastReasoningOverlay = normalized
        lastReasoningOverlayAt = now
        deviceOperator.showInfo(normalized)
    }

    private fun buildParseFailureThought(vlmResult: VLMResult): String {
        return if (vlmResult.shouldRetryForToolCall) {
            val thinking = buildThinkingOverlayText(vlmResult.thinking)
            val reason = vlmResult.error?.takeIf { it.isNotBlank() }
            if (thinking.isNotBlank()) {
                "模型连续多次未返回可解析的标准 tool_call。最后错误：${reason ?: "未知"}。最后一次思考：$thinking"
            } else {
                "模型连续多次未返回可解析的标准 tool_call。最后错误：${reason ?: "未知"}"
            }
        } else {
            "解析VLM返回的结构化响应时发生错误，可能是格式不正确或缺少必需字段,忽略后面的action字段"
        }
    }

    private fun resolveVlmFailureMessage(vlmResult: VLMResult): String {
        if (vlmResult.shouldRetryForToolCall) {
            val finishReasonSuffix = vlmResult.thinking?.finishReason
                ?.takeIf { it.isNotBlank() }
                ?.let { "（finish_reason=$it）" }
                .orEmpty()
            val reason = vlmResult.error?.takeIf { it.isNotBlank() }
                ?.let { "：$it" }
                .orEmpty()
            return "模型多次未返回可解析的标准 tool_call$finishReasonSuffix$reason"
        }
        return vlmResult.error ?: "VLM推理失败"
    }

    private fun buildParseFailureDiagnostics(
        sceneTurn: SceneChatCompletionTurn?,
        requestEnvelope: VLMRequestEnvelope?
    ): Map<String, String> {
        val diagnostics = linkedMapOf<String, String>()
        requestEnvelope?.let { envelope ->
            diagnostics += buildRequestEnvelopeDiagnostics(envelope)
        }
        sceneTurn?.let { turn ->
            diagnostics["vlm_response_route"] = turn.route.orEmpty()
            diagnostics["vlm_response_resolved_model"] = turn.resolvedModel
            diagnostics["vlm_stream_request_variant"] = turn.requestVariant.orEmpty()
            diagnostics["vlm_stream_request_had_tools"] = turn.requestHadTools?.toString().orEmpty()
            diagnostics["vlm_stream_request_tool_choice"] = turn.requestToolChoice.orEmpty()
            diagnostics["vlm_stream_request_parallel_tool_calls"] =
                turn.requestParallelToolCalls?.toString().orEmpty()
            diagnostics["vlm_response_finish_reason"] = turn.turn.finishReason.orEmpty()
            diagnostics["vlm_response_tool_call_count"] =
                (turn.turn.message.toolCalls?.size ?: 0).toString()
            diagnostics["vlm_response_tool_names"] = turn.turn.message.toolCalls
                .orEmpty()
                .map { it.function.name }
                .joinToString(",")
                .take(4000)
            val rawContent = turn.turn.message.contentText()
                .ifBlank { turn.turn.reasoning }
                .trim()
            if (rawContent.isNotBlank()) {
                diagnostics["vlm_response_raw_content_preview"] = rawContent.take(4000)
            }
        }
        return diagnostics
    }

    private fun buildRequestEnvelopeDiagnostics(envelope: VLMRequestEnvelope): Map<String, String> =
        linkedMapOf(
            "vlm_request_has_tools" to envelope.request.tools.isNotEmpty().toString(),
            "vlm_request_tool_choice" to envelope.request.toolChoice?.toString().orEmpty(),
            "vlm_request_parallel_tool_calls" to envelope.request.parallelToolCalls?.toString().orEmpty(),
            "vlm_request_tool_count" to envelope.toolNames.size.toString(),
            "vlm_request_tool_names" to envelope.toolNames.joinToString(",").take(4000),
            "vlm_request_default_tool_count" to envelope.defaultToolCount.toString(),
            "vlm_request_selected_base_tool_names" to envelope.selectedBaseToolNames.joinToString(",").take(4000),
            "vlm_request_dynamic_function_tool_count" to envelope.dynamicFunctionToolNames.size.toString(),
            "vlm_request_dynamic_function_tool_names" to
                envelope.dynamicFunctionToolNames.joinToString(",").take(4000),
            "vlm_request_dynamic_function_mapping_count" to envelope.dynamicFunctionToolMappings.size.toString(),
            "vlm_request_system_prompt_chars" to envelope.systemPromptChars.toString(),
            "vlm_request_current_user_text_chars" to envelope.currentUserTextChars.toString(),
        )

    private fun normalizeOverlayText(text: String, maxLen: Int): String {
        val normalized = text.replace("\r\n", "\n").trim()
        return if (normalized.length <= maxLen) normalized else "..." + normalized.takeLast(maxLen - 3)
    }

    private fun needsPreciseLocation(action: UIAction): Boolean {
        return when (action) {
            is ClickAction, is InputTextAction, is SwipeAction, is LongPressAction -> true
            else -> false
        }
    }

    private fun UIAction.isRecoverableDeviceAction(): Boolean {
        return when (this) {
            is ClickAction,
            is InputTextAction,
            is SwipeAction,
            is LongPressAction,
            is OpenAppAction,
            is PressKeyAction,
            is GetStateAction,
            is FunctionRunAction -> true
            else -> false
        }
    }

    private fun updateActionWithCoordinates(step: UIStep, position: List<Float>): UIStep {
        val encodedWidth = deviceOperator.getLastScreenshotWidth().coerceAtLeast(1)
        val encodedHeight = deviceOperator.getLastScreenshotHeight().coerceAtLeast(1)
        val displayWidth = deviceOperator.getDisplayWidth().coerceAtLeast(encodedWidth)
        val displayHeight = deviceOperator.getDisplayHeight().coerceAtLeast(encodedHeight)

        fun decodeAndLog(actionName: String): Pair<Float, Float> {
            val x = decodeVlmRelativeCoordinate(position[0], displayWidth).toFloat()
            val y = decodeVlmRelativeCoordinate(position[1], displayHeight).toFloat()
            OmniLog.d(Tag, "Coord decode($actionName): raw_relative_0_1000=(${position[0]}, ${position[1]}), encoded=${encodedWidth}x${encodedHeight}, mapped_screen_absolute=($x, $y), display=${displayWidth}x${displayHeight}")
            return x to y
        }

        val updatedAction = when (val action = step.action) {
            is ClickAction -> { val (x, y) = decodeAndLog("click"); action.copy(x = x, y = y) }
            is InputTextAction -> { val (x, y) = decodeAndLog("input_text"); action.copy(x = x, y = y) }
            is LongPressAction -> { val (x, y) = decodeAndLog("long_press"); action.copy(x = x, y = y) }
            is SwipeAction -> {
                val rawX1 = position.getOrNull(0) ?: 0f
                val rawY1 = position.getOrNull(1) ?: 0f
                val rawX2 = position.getOrNull(2) ?: 0f
                val rawY2 = position.getOrNull(3) ?: 0f
                val absoluteX1 = decodeVlmRelativeCoordinate(rawX1, displayWidth)
                val absoluteY1 = decodeVlmRelativeCoordinate(rawY1, displayHeight)
                val absoluteX2 = decodeVlmRelativeCoordinate(rawX2, displayWidth)
                val absoluteY2 = decodeVlmRelativeCoordinate(rawY2, displayHeight)
                val safeScroll = sanitizeScrollGestureCoordinates(
                    x1 = absoluteX1,
                    y1 = absoluteY1,
                    x2 = absoluteX2,
                    y2 = absoluteY2,
                    displayWidth = displayWidth,
                    displayHeight = displayHeight
                )
                OmniLog.d(Tag, "Coord decode(swipe): raw_relative_0_1000=($rawX1, $rawY1, $rawX2, $rawY2), encoded=${encodedWidth}x${encodedHeight}, mapped_screen_absolute=($absoluteX1, $absoluteY1, $absoluteX2, $absoluteY2), safe=(${safeScroll.x1}, ${safeScroll.y1}, ${safeScroll.x2}, ${safeScroll.y2}, adjusted=${safeScroll.adjusted}), display=${displayWidth}x${displayHeight}")
                action.copy(x1 = safeScroll.x1.toFloat(), y1 = safeScroll.y1.toFloat(), x2 = safeScroll.x2.toFloat(), y2 = safeScroll.y2.toFloat())
            }
            else -> action
        }

        return step.copy(action = updatedAction)
    }

    private fun decodeVlmRelativeCoordinate(value: Float, displaySize: Int): Int {
        val clamped = value.coerceIn(0f, VLM_RELATIVE_COORDINATE_MAX)
        return (clamped.toDouble() / VLM_RELATIVE_COORDINATE_MAX * displaySize).roundToInt()
            .coerceIn(0, displaySize)
    }

    private data class PointedActionFields(
        val nodeId: String?,
        val targetDescription: String,
        val preferEditable: Boolean,
        val x: Float,
        val y: Float,
    )

    private fun UIAction.pointedFields(): PointedActionFields? = when (this) {
        is ClickAction -> PointedActionFields(nodeId, targetDescription, false, x, y)
        is InputTextAction -> PointedActionFields(nodeId, targetDescription, true, x, y)
        is LongPressAction -> PointedActionFields(nodeId, targetDescription, false, x, y)
        else -> null
    }

    private fun UIAction.copyWithGroundedXY(target: VLMIndexedPageContext.IndexedTarget): UIAction = when (this) {
        is ClickAction -> copy(targetDescription = target.label.ifBlank { targetDescription }, x = target.centerX, y = target.centerY, nodeId = target.nodeId)
        is InputTextAction -> copy(targetDescription = target.label.ifBlank { targetDescription }, x = target.centerX, y = target.centerY, nodeId = target.nodeId)
        is LongPressAction -> copy(targetDescription = target.label.ifBlank { targetDescription }, x = target.centerX, y = target.centerY, nodeId = target.nodeId)
        else -> this
    }

    private fun groundIndexedActionTarget(
        step: UIStep,
        currentXml: String?,
        displayWidth: Int,
        displayHeight: Int
    ): UIStep {
        return when (val action = step.action) {
            is ClickAction, is InputTextAction, is LongPressAction -> {
                val fields = action.pointedFields() ?: return step
                val target = resolveIndexedElementTarget(
                    currentXml = currentXml,
                    displayWidth = displayWidth,
                    displayHeight = displayHeight,
                    nodeId = fields.nodeId,
                    targetDescription = fields.targetDescription,
                    preferEditable = fields.preferEditable,
                    actionName = action.name
                ) ?: return step
                OmniLog.i(Tag, "Indexed ${action.name} grounding applied: index=${target.index} label=${target.label} (${fields.x},${fields.y})->(${target.centerX},${target.centerY})")
                step.copy(
                    action = action.copyWithGroundedXY(target),
                    summary = appendRuntimeTag(step.summary, "indexed_element:${target.index}")
                )
            }

            is SwipeAction -> {
                val index = action.scrollableIndex ?: return step
                val target = VLMIndexedPageContext.scrollTarget(
                    currentXml = currentXml,
                    displayWidth = displayWidth,
                    displayHeight = displayHeight,
                    index = index,
                    direction = action.direction
                ) ?: run {
                    OmniLog.w(Tag, "Indexed swipe grounding failed: missing scrollable_index=$index")
                    return step
                }
                val safeScroll = sanitizeScrollGestureCoordinates(
                    x1 = target.x1.roundToInt(),
                    y1 = target.y1.roundToInt(),
                    x2 = target.x2.roundToInt(),
                    y2 = target.y2.roundToInt(),
                    displayWidth = displayWidth,
                    displayHeight = displayHeight
                )
                OmniLog.i(
                    Tag,
                    "Indexed swipe grounding applied: index=$index direction=${action.direction.orEmpty()} label=${target.label} -> (${safeScroll.x1},${safeScroll.y1},${safeScroll.x2},${safeScroll.y2})"
                )
                step.copy(
                    action = action.copy(
                        targetDescription = target.label.ifBlank { action.targetDescription },
                        x1 = safeScroll.x1.toFloat(),
                        y1 = safeScroll.y1.toFloat(),
                        x2 = safeScroll.x2.toFloat(),
                        y2 = safeScroll.y2.toFloat()
                    ),
                    summary = appendRuntimeTag(step.summary, "indexed_scroll:$index")
                )
            }

            else -> step
        }
    }

    private fun resolveIndexedElementTarget(
        currentXml: String?,
        displayWidth: Int,
        displayHeight: Int,
        nodeId: String?,
        targetDescription: String,
        preferEditable: Boolean,
        actionName: String
    ): VLMIndexedPageContext.IndexedTarget? {
        val targetByNodeId = VLMIndexedPageContext.elementTargetByNodeId(
            currentXml = currentXml,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            nodeId = nodeId
        )
        if (targetByNodeId != null) {
            return targetByNodeId
        }
        val target = VLMIndexedPageContext.uniqueElementTargetByDescription(
            currentXml = currentXml,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            targetDescription = targetDescription,
            preferEditable = preferEditable
        )
        if (target == null && targetDescription.isNotBlank()) {
            OmniLog.d(Tag, "Indexed $actionName text grounding skipped: no unique match for \"$targetDescription\"")
        }
        return target
    }

    private fun appendRuntimeTag(summary: String, tag: String): String {
        return buildString {
            append(summary)
            if (isNotBlank()) append(" ")
            append("[grounded:$tag]")
        }
    }

    private fun normalizeOpenAppAction(step: UIStep, context: UIContext): UIStep {
        val action = step.action
        if (action !is OpenAppAction) return step

        val resolvedPackage = resolvePackageName(action.packageName, context.installedApplications)
        return if (resolvedPackage != null && resolvedPackage != action.packageName) {
            OmniLog.d(Tag, "Resolved open_app value '${action.packageName}' -> '$resolvedPackage'")
            step.copy(action = action.copy(packageName = resolvedPackage))
        } else {
            step
        }
    }

    private fun resolvePackageName(
        nameOrPkg: String,
        installedApps: Map<String, String>
    ): String? {
        val input = nameOrPkg.trim()
        if (input.isEmpty()) return null

        // 直接匹配包名
        installedApps.keys.firstOrNull { it.equals(input, ignoreCase = true) }?.let { return it }

        // 精确匹配应用名
        installedApps.entries.firstOrNull {
            it.value.equals(
                input,
                ignoreCase = true
            )
        }?.key?.let { return it }

        val normalizedInput = normalizeName(input)
        installedApps.entries.firstOrNull { normalizeName(it.value) == normalizedInput }?.key?.let { return it }

        // 包名后缀或包含关系
        installedApps.keys.firstOrNull {
            normalizeName(it).endsWith(normalizedInput) || normalizedInput.endsWith(normalizeName(it))
        }?.let { return it }

        installedApps.entries.firstOrNull {
            val appNorm = normalizeName(it.value)
            appNorm.contains(normalizedInput) || normalizedInput.contains(appNorm)
        }?.key?.let { return it }

        return null
    }

    private fun normalizeName(input: String): String {
        return input.lowercase().replace(Regex("[^a-z0-9\u4e00-\u9fa5]+"), "")
    }

    /**
     * 获取当前屏幕的XML表示
     */
    private suspend fun captureCurrentXml(): String? {
        var bestCandidate: String? = null
        var bestHealth = XmlHealth(0, 0, false)
        repeat(XML_CAPTURE_MAX_ATTEMPTS) { attempt ->
            val current = try {
                AccessibilityController.getCaptureScreenShotXml(true)
            } catch (e: Exception) {
                OmniLog.w(Tag, "获取XML失败: ${e.message}")
                null
            }
            val health = xmlHealth(current)
            if (health.nodeCount > bestHealth.nodeCount || health.largestArea > bestHealth.largestArea) {
                bestCandidate = current
                bestHealth = health
            }
            if (current != null && health.isUsable) {
                lastUsableXml = current
                return current
            }
            if (attempt < XML_CAPTURE_MAX_ATTEMPTS - 1) {
                delay(50L)
            }
        }
        val fallback = lastUsableXml
        if (fallback != null && bestHealth.nodeCount <= MIN_TINY_XML_NODE_COUNT) {
            OmniLog.w(
                Tag,
                "Using last usable accessibility XML after tiny capture: nodes=${bestHealth.nodeCount}, area=${bestHealth.largestArea}"
            )
            return fallback
        }
        return bestCandidate
    }

    private fun xmlHealth(xml: String?): XmlHealth {
        if (xml.isNullOrBlank()) return XmlHealth(0, 0, false)
        val nodeCount = Regex("<node\\b").findAll(xml).count()
        var largestArea = 0
        var hasAppSizedRoot = false
        val screenArea = deviceOperator.getDisplayWidth().coerceAtLeast(1) *
            deviceOperator.getDisplayHeight().coerceAtLeast(1)
        Regex("""bounds="\[(\d+),(\d+)]\[(\d+),(\d+)]"""").findAll(xml).forEach { match ->
            val left = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return@forEach
            val top = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return@forEach
            val right = match.groupValues.getOrNull(3)?.toIntOrNull() ?: return@forEach
            val bottom = match.groupValues.getOrNull(4)?.toIntOrNull() ?: return@forEach
            val area = ((right - left).coerceAtLeast(0)) * ((bottom - top).coerceAtLeast(0))
            largestArea = maxOf(largestArea, area)
            if (area >= (screenArea * MIN_APP_XML_AREA_RATIO).toInt()) {
                hasAppSizedRoot = true
            }
        }
        return XmlHealth(nodeCount, largestArea, hasAppSizedRoot)
    }

    private fun isPageStableByXml(beforeXml: String?, afterXml: String?): Boolean {
        return try {
            if (beforeXml == null || afterXml == null) {
                OmniLog.d(Tag, "XML为空，默认认为不稳定")
                return false
            }

            val similarity = TreeEditDistance.getSimilarity(beforeXml, afterXml)
            val isStable = similarity >= 0.85

            OmniLog.d(Tag, "页面稳定性检测: 相似度=$similarity, 稳定=$isStable")
            isStable
        } catch (e: Exception) {
            OmniLog.w(Tag, "页面稳定性比较异常: ${e.message}")
            false
        }
    }

    private data class GetStatePageSummary(
        val visibleTexts: List<String> = emptyList(),
        val actionables: List<String> = emptyList(),
        val focusedEditable: String = "",
        val scrollableCount: Int = 0,
    )

    private fun buildGetStateResult(
        reason: String,
        currentXml: String,
        packageName: String?,
        activityName: String?,
        installedApps: Map<String, String>,
    ): String {
        val normalizedPackage = packageName?.trim().orEmpty()
        val appName = installedApps[normalizedPackage]?.trim().orEmpty()
        val page = summarizeGetStateXml(currentXml)
        return buildString {
            appendLine("get_state refreshed current page")
            reason.trim().takeIf { it.isNotEmpty() }?.let { appendLine("reason: $it") }
            appendLine("app_info:")
            appendLine("- package_name: ${normalizedPackage.ifBlank { "unknown" }}")
            if (appName.isNotBlank()) appendLine("- app_name: $appName")
            appendLine("- activity_name: ${activityName?.trim()?.takeIf { it.isNotEmpty() } ?: "unknown"}")
            appendLine("page:")
            if (page.visibleTexts.isNotEmpty()) {
                appendLine("- visible_texts: ${page.visibleTexts.joinToString(" / ")}")
            } else {
                appendLine("- visible_texts: none")
            }
            if (page.actionables.isNotEmpty()) {
                appendLine("- actionables: ${page.actionables.joinToString(" / ")}")
            } else {
                appendLine("- actionables: none")
            }
            if (page.focusedEditable.isNotBlank()) {
                appendLine("- focused_editable: ${page.focusedEditable}")
            }
            if (page.scrollableCount > 0) {
                appendLine("- scrollable_regions: ${page.scrollableCount}")
            }
            appendLine("diagnostics:")
            appendLine("- raw_xml_available: ${currentXml.isNotBlank()}")
            appendLine("- raw_xml_chars: ${currentXml.length}")
            appendLine("- raw_xml_model_visible: false")
        }.take(MAX_GET_STATE_RESULT_CHARS)
    }

    private fun summarizeGetStateXml(xml: String): GetStatePageSummary {
        if (xml.isBlank()) return GetStatePageSummary()
        val visibleTexts = linkedSetOf<String>()
        val actionables = linkedSetOf<String>()
        var focusedEditable = ""
        var scrollableCount = 0

        fun attr(element: Element, name: String): String =
            element.getAttribute(name)?.trim().orEmpty()

        fun boolAttr(element: Element, name: String): Boolean =
            attr(element, name).equals("true", ignoreCase = true)

        fun labelOf(element: Element): String {
            val text = attr(element, "text")
            val contentDesc = attr(element, "content-desc").ifBlank { attr(element, "contentDescription") }
            val resourceId = attr(element, "resource-id").ifBlank { attr(element, "viewIdResourceName") }
            val className = attr(element, "class").substringAfterLast('.')
            return listOf(text, contentDesc, resourceId.substringAfterLast('/'), className)
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
                .take(MAX_GET_STATE_LABEL_CHARS)
        }

        val document = runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isIgnoringComments = true
                isCoalescing = true
            }
            factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        }.getOrNull() ?: return GetStatePageSummary()

        val nodes = document.getElementsByTagName("node")
        val limit = minOf(nodes.length, MAX_GET_STATE_NODE_SCAN)
        for (index in 0 until limit) {
            val element = nodes.item(index) as? Element ?: continue
            val label = labelOf(element)
            if (label.isNotBlank() && visibleTexts.size < MAX_GET_STATE_VISIBLE_TEXTS) {
                visibleTexts += label
            }
            val actionable = boolAttr(element, "clickable") ||
                boolAttr(element, "focusable") ||
                boolAttr(element, "editable") ||
                boolAttr(element, "long-clickable") ||
                boolAttr(element, "scrollable")
            if (actionable && label.isNotBlank() && actionables.size < MAX_GET_STATE_ACTIONABLES) {
                actionables += label
            }
            if (boolAttr(element, "scrollable")) {
                scrollableCount += 1
            }
            if (focusedEditable.isBlank() && boolAttr(element, "focused") && boolAttr(element, "editable")) {
                focusedEditable = label
            }
        }
        return GetStatePageSummary(
            visibleTexts = visibleTexts.toList(),
            actionables = actionables.toList(),
            focusedEditable = focusedEditable,
            scrollableCount = scrollableCount,
        )
    }
}

private const val SINGLE_STEP_TIMEOUT_MS = 90_000L
private const val XML_CAPTURE_MAX_ATTEMPTS = 4
private const val MIN_USABLE_XML_NODE_COUNT = 3
private const val MIN_TINY_XML_NODE_COUNT = 1
private const val MIN_USABLE_XML_AREA = 180_000
private const val MIN_APP_XML_AREA_RATIO = 0.28
private const val MAX_GET_STATE_NODE_SCAN = 180
private const val MAX_GET_STATE_VISIBLE_TEXTS = 18
private const val MAX_GET_STATE_ACTIONABLES = 18
private const val MAX_GET_STATE_LABEL_CHARS = 80
private const val MAX_GET_STATE_RESULT_CHARS = 2200
private const val VLM_RELATIVE_COORDINATE_MAX = 1000f

data class VLMOperationResult(
    val success: Boolean,
    val step: UIStep?,
    val context: UIContext,
    val error: String?,
    val screenshot: String? = null,
)

data class TaskExecutionReport(
    val success: Boolean,
    val goal: String,
    val totalSteps: Int,
    val executionTrace: List<UIStep>,
    val finalContext: UIContext,
    val error: String?,
    val summaryScreenshotList: List<String>? = null,
    val doneReason: String? = null
)

internal data class SafeScrollCoordinates(
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int,
    val adjusted: Boolean
)

internal fun sanitizeScrollGestureCoordinates(
    x1: Int,
    y1: Int,
    x2: Int,
    y2: Int,
    displayWidth: Int,
    displayHeight: Int
): SafeScrollCoordinates {
    val width = displayWidth.coerceAtLeast(1)
    val height = displayHeight.coerceAtLeast(1)
    val horizontalInset = (width * 0.025f).roundToInt().coerceIn(8, 32)
    val topInset = (height * 0.04f).roundToInt().coerceAtLeast(48)
    val bottomInset = (height * 0.08f).roundToInt().coerceAtLeast(96)
    val minX = horizontalInset.coerceAtMost(width - 1)
    val maxX = (width - horizontalInset).coerceAtLeast(minX)
    val minY = topInset.coerceAtMost(height - 1)
    val maxY = (height - bottomInset).coerceAtLeast(minY)

    val safeX1 = x1.coerceIn(minX, maxX)
    val safeY1 = y1.coerceIn(minY, maxY)
    val safeX2 = x2.coerceIn(minX, maxX)
    val safeY2 = y2.coerceIn(minY, maxY)
    return SafeScrollCoordinates(
        x1 = safeX1,
        y1 = safeY1,
        x2 = safeX2,
        y2 = safeY2,
        adjusted = safeX1 != x1 || safeY1 != y1 || safeX2 != x2 || safeY2 != y2
    )
}
