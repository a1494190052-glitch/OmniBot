package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.bot.agent.ManualToolStopCancellationException
import cn.com.omnimind.bot.omniflow.OobFunctionJson.firstNonBlank
import cn.com.omnimind.bot.omniflow.OobFunctionJson.listArg
import cn.com.omnimind.bot.omniflow.OobFunctionJson.mapArg
import cn.com.omnimind.bot.omniflow.language.OmniflowFunction
import cn.com.omnimind.bot.omniflow.language.OmniflowFunctionStore
import cn.com.omnimind.bot.omniflow.language.UIStep
import cn.com.omnimind.bot.omniflow.language.ParameterValue
import cn.com.omnimind.bot.omniflow.language.resolveArgPath
import cn.com.omnimind.bot.agent.AgentToolJson.mapToJsonElement
import cn.com.omnimind.baselib.runlog.OobReusableFunctionStore
import cn.com.omnimind.bot.omniflow.OobFunctionArgumentBindingValidator
import cn.com.omnimind.bot.omniflow.OobFunctionToolNames
import cn.com.omnimind.bot.runlog.OmniflowCheckerRule
import cn.com.omnimind.bot.omniflow.OobFunctionSchemaBuilder
import cn.com.omnimind.bot.runlog.OobActionCodec
import cn.com.omnimind.bot.runlog.OobOmniFlowToolkitService
import cn.com.omnimind.bot.runlog.OobUdegNodeStore
import cn.com.omnimind.bot.runlog.UIStepExecutor
import cn.com.omnimind.bot.runlog.RunLogReplayPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicLong

class OobFunctionToolHandler(
    private val context: android.content.Context,
    private val helper: SharedHelper,
    private val graphStepRunner: OobFunctionGraphStepRunner = OobFunctionGraphStepRunner(),
    private val frontendSessionController: OobFunctionFrontendSessionController =
        OobFunctionFrontendSessionController(helper),
    private val agentFallbackController: OobFunctionAgentFallbackController =
        OobFunctionAgentFallbackController(),
    private val nestedCallCardPresenter: OobFunctionNestedCallCardPresenter =
        OobFunctionNestedCallCardPresenter(helper),
    private val runResultBuilder: OobFunctionRunResultBuilder =
        OobFunctionRunResultBuilder(),
    private val toolDelegationExecutor: OobFunctionToolDelegationExecutor =
        OobFunctionToolDelegationExecutor(runResultBuilder),
    private val accessibilityPreflightGuard: OobFunctionAccessibilityPreflightGuard =
        OobFunctionAccessibilityPreflightGuard(runResultBuilder),
    private val goToNavigator: OobFunctionGoToNavigator =
        OobFunctionGoToNavigator(context, runResultBuilder),
) : ToolHandler {
    override val toolNames: Set<String> = setOf(
        RunLogReplayPolicy.TOOL_CALL_TOOL,
    )

    internal var router: cn.com.omnimind.bot.agent.AgentToolExecutor? = null

    /** Workspace-backed function store; injected by the OmniFlow function layer on init. */
    var workspaceFunctionStore: cn.com.omnimind.bot.omniflow.WorkspaceFunctionStore? = null

    override fun canHandle(toolName: String): Boolean =
        RunLogReplayPolicy.isOmniflowToolCallTool(toolName) ||
            toolName in OobFunctionToolNames.profileTools ||
            runCatching {
                cn.com.omnimind.baselib.runlog.OobReusableFunctionStore.get(context, toolName) != null
            }.getOrDefault(false) ||
            workspaceFunctionStore?.canHandle(toolName) == true

    /** Returns the function spec from SharedPreferences or workspace, whichever has it. */
    private fun getSpec(functionId: String): Map<String, Any?>? =
        runCatching {
            cn.com.omnimind.baselib.runlog.OobReusableFunctionStore.get(context, functionId)
        }.getOrNull()
            ?: workspaceFunctionStore?.get(functionId)

    fun canRunFullyWithOmniflow(materializedSpec: Map<String, Any?>): Boolean {
        val steps = materializedSteps(materializedSpec)
        return steps.isNotEmpty() && steps.all { step ->
            isSkippedStep(step) ||
                UIStepExecutor.isUIStep(step) ||
                isOmniflowExecutionStep(step)
        }
    }

    override suspend fun execute(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: cn.com.omnimind.bot.agent.AgentToolRegistry.RuntimeToolDescriptor,
        env: cn.com.omnimind.bot.agent.AgentExecutionEnvironment,
        callback: cn.com.omnimind.bot.agent.AgentCallback,
        toolHandle: cn.com.omnimind.bot.agent.AgentToolExecutionHandle
    ): cn.com.omnimind.bot.agent.ToolExecutionResult {
        val toolName = toolCall.function.name
        if (toolName in OobFunctionToolNames.profileTools) {
            return executeLifecycleTool(toolName, args, callback, toolHandle)
        }
        if (RunLogReplayPolicy.isOmniflowToolCallTool(toolName)) {
            return executeModelCallTool(toolCall, args, env, callback, toolHandle)
        }
        return if (getSpec(toolName) != null) {
            cn.com.omnimind.bot.agent.ToolExecutionResult.Error(
                toolName,
                "Direct Function tool execution is disabled. Use vlm_task so OmniFlow runtime recall can select, fill, and replay the Function."
            )
        } else {
            cn.com.omnimind.bot.agent.ToolExecutionResult.Error(
                toolName, "OOB function not found: $toolName"
            )
        }
    }

    private suspend fun executeLifecycleTool(
        toolName: String,
        args: JsonObject,
        callback: cn.com.omnimind.bot.agent.AgentCallback,
        toolHandle: cn.com.omnimind.bot.agent.AgentToolExecutionHandle,
    ): cn.com.omnimind.bot.agent.ToolExecutionResult {
        return try {
            helper.reportToolProgress(
                callback = callback,
                toolName = toolName,
                progress = lifecycleProgress(toolName),
                toolHandle = toolHandle,
            )
            val store = workspaceFunctionStore
                ?: cn.com.omnimind.bot.omniflow.WorkspaceFunctionStore(
                    cn.com.omnimind.bot.agent.AgentWorkspaceManager.rootDirectory(context)
                )
            val toolkit = OobOmniFlowToolkitService(context, store)
            val argsMap = helper.jsonObjectToMap(args).filterKeys { it != "tool_title" }
            val payload = toolkit.executeTool(toolName, argsMap)
            val payloadJson = helper.encodeLocalizedPayload(payload)
            cn.com.omnimind.bot.agent.ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = lifecycleSummary(toolName, payload),
                previewJson = payloadJson,
                rawResultJson = payloadJson,
                success = payload["success"] != false
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            cn.com.omnimind.bot.agent.ToolExecutionResult.Error(
                toolName,
                helper.localized(e.message ?: "OOB Function tool failed")
            )
        }
    }

    private fun lifecycleProgress(toolName: String): String = when (toolName) {
        OobFunctionToolNames.FUNCTION_UPDATE -> "正在更新复用指令"
        OobFunctionToolNames.FUNCTION_REGISTER -> "正在注册复用指令"
        OobFunctionToolNames.FUNCTION_GET -> "正在读取复用指令"
        OobFunctionToolNames.FUNCTION_LIST -> "正在读取复用指令列表"
        OobFunctionToolNames.RUN_LOG_GET -> "正在读取 RunLog"
        OobFunctionToolNames.RUN_LOG_LIST -> "正在读取 RunLog 列表"
        OobFunctionToolNames.RUN_LOG_CONVERT -> "正在转换 RunLog"
        OobFunctionToolNames.FUNCTION_DELETE -> "正在删除复用指令"
        OobFunctionToolNames.FUNCTION_CLEAR -> "正在清空复用指令"
        else -> "正在处理复用指令"
    }

    private fun lifecycleSummary(toolName: String, payload: Map<String, Any?>): String {
        val error = payload["error_message"]?.toString()?.trim().orEmpty()
        if (payload["success"] == false && error.isNotEmpty()) return error
        val message = payload["message"]?.toString()?.trim().orEmpty()
        if (message.isNotEmpty()) return message
        return when (toolName) {
            OobFunctionToolNames.FUNCTION_UPDATE -> {
                if (payload["needs_agent_analysis"] == true) {
                    "已读取 Function 和 RunLog，等待 agent 分析后再保存。"
                } else if (payload["saved"] == true || payload["changed"] == true) {
                    "复用指令已更新。"
                } else {
                    "复用指令更新检查完成。"
                }
            }
            OobFunctionToolNames.FUNCTION_REGISTER -> "复用指令已注册。"
            OobFunctionToolNames.RUN_LOG_CONVERT -> "RunLog 转换完成。"
            OobFunctionToolNames.FUNCTION_GET -> "已读取复用指令。"
            OobFunctionToolNames.FUNCTION_LIST -> "已读取复用指令列表。"
            OobFunctionToolNames.RUN_LOG_GET -> "已读取 RunLog。"
            OobFunctionToolNames.RUN_LOG_LIST -> "已读取 RunLog 列表。"
            else -> "复用指令工具调用完成。"
        }
    }

    private suspend fun executeModelCallTool(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        env: cn.com.omnimind.bot.agent.AgentExecutionEnvironment,
        callback: cn.com.omnimind.bot.agent.AgentCallback,
        toolHandle: cn.com.omnimind.bot.agent.AgentToolExecutionHandle,
    ): cn.com.omnimind.bot.agent.ToolExecutionResult {
        val toolName = toolCall.function.name
        val argsMap = helper.jsonObjectToMap(args)
        val callTool = resolveCallRequest(argsMap)
        val targetTool = callTool.targetTool
        val targetArgs = callTool.targetArgs
        val functionId = callTool.functionId

        if (functionId.isNotEmpty()) {
            return cn.com.omnimind.bot.agent.ToolExecutionResult.Error(
                toolName,
                "Direct call_tool(function_id) execution is disabled. Use vlm_task so OmniFlow runtime recall can select, fill, and replay the Function."
            )
        }

        if (targetTool.isEmpty()) {
            return cn.com.omnimind.bot.agent.ToolExecutionResult.Error(
                toolName, "call_tool requires tool_name or function_id"
            )
        }
        if (RunLogReplayPolicy.isOmniflowToolCallTool(targetTool)) {
            return cn.com.omnimind.bot.agent.ToolExecutionResult.Error(
                toolName, "Nested call_tool is not allowed"
            )
        }
        val delegatedRouter = router
            ?: return cn.com.omnimind.bot.agent.ToolExecutionResult.Error(
                toolName, "Tool router unavailable for $targetTool"
            )
        val targetArgsJson = mapToJsonElement(targetArgs) as? JsonObject
            ?: JsonObject(emptyMap())
        val syntheticCall = cn.com.omnimind.baselib.llm.AssistantToolCall(
            id = "${toolCall.id}_${targetTool}",
            type = "function",
            function = cn.com.omnimind.baselib.llm.AssistantToolCallFunction(
                name = targetTool,
                arguments = targetArgsJson.toString()
            )
        )
        val subDescriptor = cn.com.omnimind.bot.agent.AgentToolRegistry.RuntimeToolDescriptor(
            name = targetTool,
            displayName = targetTool,
            toolType = RunLogReplayPolicy.TOOL_CALL_TOOL
        )
        return delegatedRouter.execute(
            syntheticCall,
            targetArgsJson,
            subDescriptor,
            env,
            callback,
            toolHandle
        )
    }

    suspend fun runMaterializedFunction(
        functionId: String,
        spec: Map<String, Any?>,
        materializedSpec: Map<String, Any?>,
        callback: cn.com.omnimind.bot.agent.AgentCallback? = null,
        toolHandle: cn.com.omnimind.bot.agent.AgentToolExecutionHandle? = null,
        env: cn.com.omnimind.bot.agent.AgentExecutionEnvironment? = null,
        parentToolCallId: String? = null,
        toolName: String = functionId,
        allowAgentFallback: Boolean = false,
        allowToolDelegationWithoutRouter: Boolean = false,
        callStack: List<String> = emptyList(),
        resumeFromStep: Int = 0,
        fallbackSessionId: String = "",
        fallbackAttempt: Int = 0,
        frontendRunId: String = "",
        frontendTaskId: String = "",
        frontendParent: String = "",
    ): Map<String, Any?> {
        val runStartedAtMs = System.currentTimeMillis()
        val timing = runResultBuilder.timing(runStartedAtMs)
        val normalizedFunctionId = functionId.trim()
        val auditRunId = nextRunId(runStartedAtMs)
        if (normalizedFunctionId.isNotEmpty() && normalizedFunctionId in callStack) {
            return runResultBuilder.failedRun(
                functionId = functionId,
                spec = spec,
                auditRunId = auditRunId,
                startedAtMs = runStartedAtMs,
                errorCode = "OOB_FUNCTION_RECURSION",
                errorMessage = "Recursive OOB function call detected: " +
                    (callStack + normalizedFunctionId).joinToString(" -> ")
            )
        }
        if (callStack.size >= MAX_OMNIFLOW_CALL_DEPTH) {
            return runResultBuilder.failedRun(
                functionId = functionId,
                spec = spec,
                auditRunId = auditRunId,
                startedAtMs = runStartedAtMs,
                errorCode = "OOB_FUNCTION_MAX_DEPTH",
                errorMessage = "OOB function call depth exceeds $MAX_OMNIFLOW_CALL_DEPTH"
            )
        }
        val activeCallStack = if (normalizedFunctionId.isNotEmpty()) {
            callStack + normalizedFunctionId
        } else {
            callStack
        }
        val steps = timing.measure("materialized_steps_ms") { materializedSteps(materializedSpec) }
        val bindingValidation = timing.measure("argument_binding_validation_ms") {
            OobFunctionArgumentBindingValidator.validate(materializedSpec)
        }
        if (!bindingValidation.success) {
            return runResultBuilder.withRunnerTiming(
                runResultBuilder.failedRun(
                    functionId = functionId,
                    spec = spec,
                    auditRunId = auditRunId,
                    startedAtMs = runStartedAtMs,
                    errorCode = OobFunctionArgumentBindingValidator.ERROR_CODE,
                    errorMessage = bindingValidation.errorMessage,
                    extras = bindingValidation.diagnostics,
                ),
                timing.finish()
            )
        }
        val argumentSourcesByStepIndex =
            OobFunctionArgumentBindingValidator.argumentSourcesByStepIndex(materializedSpec)
        val normalizedResumeFromStep = resumeFromStep.coerceIn(0, steps.size)
        val activeSteps = if (normalizedResumeFromStep > 0) {
            steps.drop(normalizedResumeFromStep)
        } else {
            steps
        }
        val preflightFailure = timing.measure("accessibility_preflight_ms") {
            accessibilityPreflightGuard.failureIfBlocked(
                functionId = functionId,
                spec = spec,
                auditRunId = auditRunId,
                startedAtMs = runStartedAtMs,
                steps = activeSteps,
            )
        }
        preflightFailure?.let {
            return runResultBuilder.withRunnerTiming(it, timing.finish())
        }
        val sourceStartCheckStartedAt = System.nanoTime()
        val startPreparation = goToNavigator.navigate(
            functionId = functionId,
            spec = spec,
            auditRunId = auditRunId,
            startedAtMs = runStartedAtMs,
            activeSteps = activeSteps,
            allowAgentFallback = allowAgentFallback,
            allowToolDelegationWithoutRouter = allowToolDelegationWithoutRouter,
            callStack = activeCallStack,
            getSpec = ::getSpec,
            runFunction = { id, sp, mat ->
                runMaterializedFunction(
                    functionId = id,
                    spec = sp,
                    materializedSpec = mat,
                    allowAgentFallback = allowAgentFallback,
                    allowToolDelegationWithoutRouter = allowToolDelegationWithoutRouter,
                    callStack = activeCallStack,
                )
            },
        )
        timing.recordElapsed("source_start_check_ms", sourceStartCheckStartedAt)
        startPreparation.failure?.let {
            return runResultBuilder.withRunnerTiming(it, timing.finish())
        }

        val frontendSession = frontendSessionController.start(
            functionId = normalizedFunctionId.ifBlank { functionId },
            spec = spec,
            stepCount = activeSteps.size,
            toolHandle = toolHandle,
            callStack = callStack,
            fallbackRunIdProvider = { nextRunId(System.currentTimeMillis()) },
            frontendRunId = frontendRunId,
            frontendTaskId = frontendTaskId,
            frontendParent = frontendParent,
        )
        var frontendFinished = false
        val replayStopRequested = {
            frontendSession?.isStopRequested() == true ||
                toolHandle?.isManualStopRequested() == true
        }
        var frontendFinishMessage = helper.localized("任务已完成")
        var frontendCloseAfterMs = FRONTEND_TERMINAL_POPUP_VISIBLE_MS
        val stepResults = mutableListOf<Map<String, Any?>>()
        var delegatedToolUsed = false
        var modelRequired = false
        var failureReason: String? = null
        var currentStepIndex = -1
        var currentStepId = ""
        var currentStepTool = ""
        var currentStepExecutor = ""
        var currentStepStartedAtMs = 0L
        fun buildResult() = runResultBuilder.completedRun(
            functionId = functionId,
            spec = spec,
            auditRunId = auditRunId,
            steps = steps,
            activeSteps = activeSteps,
            stepResults = stepResults,
            normalizedResumeFromStep = normalizedResumeFromStep,
            fallbackSessionId = fallbackSessionId,
            fallbackAttempt = fallbackAttempt,
            modelRequired = modelRequired,
            delegatedToolUsed = delegatedToolUsed,
            allowAgentFallback = allowAgentFallback,
            failureReason = failureReason,
        ).also { it.putAll(OobFunctionArgumentBindingValidator.runtimeDiagnostics(materializedSpec)) }
        fun isUserCompletedReplay(): Boolean =
            frontendSession?.isUserFinishedRequested() == true &&
                stepResults.size >= activeSteps.size &&
                toolHandle?.isManualStopRequested() != true
        fun buildUserCompletedResult(): MutableMap<String, Any?> =
            buildResult().toMutableMap().apply {
                put("done_reason", "user_completed")
                put("completed_by_user", true)
                if (this["success"] == true) {
                    put("error_code", null)
                    put("error_message", "")
                }
            }
        try {
        // Checker rules from the Function spec (metadata.checker_rules).
        // These are layered on top of the global built-in rules inside the executor.
        val functionCheckerRules = OmniflowCheckerRule.fromSpec(spec)
        val checkerBudget = UIStepExecutor.CheckerTriggerBudget()

        val stepLoopStartedAt = System.nanoTime()
        timing.recordSinceStart("pre_step_loop_ms", stepLoopStartedAt)
        for ((relativeIndex, step) in activeSteps.withIndex()) {
            val stepStartedAtMs = System.currentTimeMillis()
            frontendSession?.throwIfStopRequested()
            toolHandle?.throwIfStopRequested()
            val index = normalizedResumeFromStep + relativeIndex
            val stepIndex = index + 1
            val stepId = step["id"]?.toString() ?: "step_$stepIndex"
            val stepTitle = step["title"]?.toString() ?: stepId
            val executor = step["executor"]?.toString()?.trim()?.lowercase().orEmpty()
                .ifEmpty { RunLogReplayPolicy.EXECUTOR_AGENT }
            val callableTool = step["tool"]?.toString()?.trim().orEmpty()
            val omniflowExecutionTool = omniflowExecutionToolForStep(step, callableTool)
            currentStepIndex = index
            currentStepId = stepId
            currentStepTool = callableTool
            currentStepExecutor = executor
            currentStepStartedAtMs = stepStartedAtMs
            frontendSession?.update("第 $stepIndex/${steps.size} 步 $stepTitle")
            if (isSkippedStep(step, callableTool)) {
                stepResults += linkedMapOf<String, Any?>(
                    "step_id" to stepId,
                    "index" to index,
                    "tool" to callableTool.ifEmpty { omniflowExecutionTool },
                    "executor" to RunLogReplayPolicy.EXECUTOR_OMNIFLOW,
                    "skipped" to true,
                    "success" to true,
                    "summary" to "Skipped observation-only replay step",
                    "started_at_ms" to stepStartedAtMs,
                    "finished_at_ms" to stepStartedAtMs,
                    "duration_ms" to 0L
                )
                currentStepIndex = -1
                continue
            }

            if (callback != null) {
                helper.reportToolProgress(
                    callback = callback,
                    toolName = toolName,
                    progress = "$stepIndex/${steps.size} $stepTitle",
                    toolHandle = toolHandle
                )
            }

            val stepResult: Map<String, Any?> = when {
                RunLogReplayPolicy.isOmniflowToolCallTool(omniflowExecutionTool) -> {
                    executeOmniflowToolCallStep(
                        step = step,
                        stepId = stepId,
                        stepTitle = stepTitle,
                        callableTool = omniflowExecutionTool,
                        callback = callback,
                        toolHandle = toolHandle,
                        env = env,
                        parentToolCallId = parentToolCallId,
                        toolName = toolName,
                        allowAgentFallback = allowAgentFallback,
                        allowToolDelegationWithoutRouter = allowToolDelegationWithoutRouter,
                        callStack = activeCallStack,
                        frontendParent = frontendParent,
                    ).also { result ->
                        if (result["model_required"] == true) modelRequired = true
                        if (result["delegated_tool_used"] == true) delegatedToolUsed = true
                    }
                }

                RunLogReplayPolicy.isOmniflowGraphTool(omniflowExecutionTool) -> {
                    graphStepRunner.execute(
                        step = step,
                        stepId = stepId,
                        stepTitle = stepTitle,
                        callableTool = omniflowExecutionTool,
                        checkerRules = functionCheckerRules,
                        checkerBudget = checkerBudget,
                        stopRequested = replayStopRequested,
                    )
                }

                UIStepExecutor.isUIStep(step) -> {
                    val preActionReadyWait = if (index > 0) {
                        UIStepExecutor.waitForReplayActionReady(
                            step = step,
                            recoveryStep = steps.getOrNull(index - 1),
                            stopRequested = replayStopRequested,
                        )
                    } else {
                        emptyMap()
                    }
                    val blockedResult = preActionReadyBlockedStep(
                        step = step,
                        stepId = stepId,
                        stepTitle = stepTitle,
                        tool = UIStepExecutor.actionNameForStep(step),
                        preActionReadyWait = preActionReadyWait,
                        allowAgentFallback = allowAgentFallback,
                    )
                    val nextStep = activeSteps.getOrNull(relativeIndex + 1)
                    val skipResult = skipStepIfNextStepAlreadyReady(
                        stepId = stepId,
                        stepTitle = stepTitle,
                        tool = UIStepExecutor.actionNameForStep(step),
                        preActionReadyWait = preActionReadyWait,
                        nextStep = nextStep,
                        stopRequested = replayStopRequested,
                    )
                    if (skipResult != null) {
                        withPreActionReadyWait(skipResult, preActionReadyWait)
                    } else if (blockedResult != null) {
                        withPreActionReadyWait(blockedResult, preActionReadyWait)
                    } else try {
                        val result = UIStepExecutor.execute(
                            step = step,
                            stepId = stepId,
                            stepTitle = stepTitle,
                            checkerRules = functionCheckerRules,
                            checkerBudget = checkerBudget,
                            stopRequested = replayStopRequested,
                        )
                        withPreActionReadyWait(result, preActionReadyWait)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val executionError = e as? UIStepExecutor.ExecutionException
                        val failReason = e.message ?: "omniflow step failed"
                        val postFailureSkip = skipStepIfNextStepAlreadyReady(
                            stepId = stepId,
                            stepTitle = stepTitle,
                            tool = UIStepExecutor.actionNameForStep(step),
                            preActionReadyWait = preActionReadyWait,
                            nextStep = nextStep,
                            stopRequested = replayStopRequested,
                        )
                        if (postFailureSkip != null) {
                            withPreActionReadyWait(
                                LinkedHashMap<String, Any?>().apply {
                                    putAll(postFailureSkip)
                                    put("failed_step_error_code", executionError?.errorCode)
                                    put("failed_step_reason", failReason)
                                    put(
                                        "skip_phase",
                                        "after_failed_step_next_replay_step_already_ready",
                                    )
                                    executionError?.diagnostics
                                        ?.takeIf { it.isNotEmpty() }
                                        ?.let { put("failed_step_diagnostics", it) }
                                },
                                preActionReadyWait,
                            )
                        } else {
                            val recovery = agentFallbackController.refetchCurrentPageForFailedStep(failReason)
                            if (allowAgentFallback) {
                            val fallbackResult = runResultBuilder.agentFallbackStep(
                                stepId = stepId,
                                tool = UIStepExecutor.actionNameForStep(step),
                                prompt = agentFallbackController.prompt(step, stepTitle, recovery),
                                summary = "OmniFlow step requires one-step online repair: $stepTitle",
                                extras = mapOf(
                                    "omniflow_fail_reason" to failReason,
                                    "failed_step_error_code" to executionError?.errorCode,
                                    "diagnostics" to executionError?.diagnostics?.takeIf { it.isNotEmpty() },
                                    "recovery" to recovery,
                                ),
                            )
                            withPreActionReadyWait(fallbackResult, preActionReadyWait)
                            } else {
                            val failureResult = runResultBuilder.failureStep(
                                stepId = stepId,
                                tool = UIStepExecutor.actionNameForStep(step),
                                executor = RunLogReplayPolicy.EXECUTOR_OMNIFLOW,
                                summary = failReason,
                                errorCode = executionError?.errorCode ?: "OOB_OMNIFLOW_STEP_FAILED",
                                extras = mapOf(
                                    "diagnostics" to executionError?.diagnostics?.takeIf { it.isNotEmpty() },
                                    "recovery" to recovery,
                                ),
                            )
                            withPreActionReadyWait(failureResult, preActionReadyWait)
                            }
                        }
                    }
                }

                executor == RunLogReplayPolicy.EXECUTOR_TOOL && callableTool.isNotEmpty() && router != null &&
                    env != null -> {
                    delegatedToolUsed = true
                    delegateStep(step, stepId, stepTitle, callableTool, env, callback, toolHandle, parentToolCallId, toolName, router!!)
                }

                executor == RunLogReplayPolicy.EXECUTOR_TOOL && callableTool.isNotEmpty() &&
                    !allowToolDelegationWithoutRouter -> {
                    val agentPrompt = agentFallbackController.prompt(step, stepTitle)
                    runResultBuilder.agentFallbackStep(
                        stepId = stepId,
                        tool = callableTool,
                        prompt = agentPrompt,
                        summary = "Tool step requires one-step online repair: $stepTitle",
                    )
                }

                else -> handleUnclassifiedStep(
                    step, stepId, stepTitle, executor, callableTool,
                    env, callback, toolHandle, parentToolCallId, toolName, allowAgentFallback,
                ).also { result ->
                    if (result["delegated"] == true) delegatedToolUsed = true
                    if (result["model_required"] == true) modelRequired = true
                }
            }
            frontendSession?.throwIfStopRequested()
            toolHandle?.throwIfStopRequested()
            val stepFinishedAtMs = System.currentTimeMillis()
            val timedStepResult = LinkedHashMap<String, Any?>().apply {
                putAll(stepResult)
                putIfAbsent("index", index)
                putIfAbsent("started_at_ms", stepStartedAtMs)
                putIfAbsent("finished_at_ms", stepFinishedAtMs)
                putIfAbsent("duration_ms", (stepFinishedAtMs - stepStartedAtMs).coerceAtLeast(0))
                if (UIStepExecutor.actionNameForStep(step) == "input_text") {
                    argumentSourcesByStepIndex[index]?.let { source ->
                        put("argument_source", source["argument_source"])
                        put("argument_binding", source)
                    }
                }
            }
            stepResults += timedStepResult
            currentStepIndex = -1
            if (timedStepResult["success"] == false) {
                if (timedStepResult["model_required"] == true) {
                    modelRequired = true
                }
                if (!timedStepResult.containsKey("recovery")) {
                    timedStepResult["recovery"] = agentFallbackController.refetchCurrentPageForFailedStep(
                        timedStepResult["summary"]?.toString() ?: "step failed"
                    )
                }
                failureReason = timedStepResult["summary"]?.toString()
                break
            }
        }
        timing.recordElapsed("step_loop_ms", stepLoopStartedAt)

        val resultBuildStartedAt = System.nanoTime()
        val resultPayload = if (isUserCompletedReplay()) {
            buildUserCompletedResult()
        } else {
            buildResult()
        }
        startPreparation.goToResult?.let { result ->
            resultPayload["pre_function_go_to"] = result
        }
        timing.recordElapsed("result_build_ms", resultBuildStartedAt)
        val runFinishedAtMs = System.currentTimeMillis()
        resultPayload["timing"] = timing.finish(runFinishedAtMs)
        val allSuccess = resultPayload["success"] == true
        frontendFinishMessage = helper.localized(if (allSuccess) "任务已完成" else "任务执行失败")
        frontendCloseAfterMs = if (allSuccess) {
            FRONTEND_SUCCESS_POPUP_VISIBLE_MS
        } else {
            FRONTEND_TERMINAL_POPUP_VISIBLE_MS
        }
        frontendSession?.finish(frontendFinishMessage, closeAfterMs = frontendCloseAfterMs)
        frontendFinished = true
        return resultPayload
        } catch (e: ManualToolStopCancellationException) {
            if (isUserCompletedReplay()) {
                frontendFinishMessage = helper.localized("任务已完成")
                frontendCloseAfterMs = FRONTEND_SUCCESS_POPUP_VISIBLE_MS
                val resultPayload = buildUserCompletedResult()
                resultPayload["timing"] = timing.finish()
                frontendSession?.finish(frontendFinishMessage, closeAfterMs = frontendCloseAfterMs)
                frontendFinished = true
                return resultPayload
            }
            frontendFinishMessage = helper.localized("任务已停止")
            frontendCloseAfterMs = FRONTEND_TERMINAL_POPUP_VISIBLE_MS
            if (currentStepIndex >= 0 && stepResults.none { it["index"] == currentStepIndex }) {
                val stoppedAtMs = System.currentTimeMillis()
                stepResults += LinkedHashMap<String, Any?>().apply {
                    putAll(
                        runResultBuilder.failureStep(
                            stepId = currentStepId.ifBlank { "step_${currentStepIndex + 1}" },
                            tool = currentStepTool.ifBlank { "?" },
                            executor = currentStepExecutor.ifBlank { RunLogReplayPolicy.EXECUTOR_OMNIFLOW },
                            summary = frontendFinishMessage,
                            errorCode = "OOB_FUNCTION_STOPPED",
                        )
                    )
                    put("index", currentStepIndex)
                    put("started_at_ms", currentStepStartedAtMs.takeIf { it > 0L } ?: stoppedAtMs)
                    put("finished_at_ms", stoppedAtMs)
                    put(
                        "duration_ms",
                        (stoppedAtMs - (currentStepStartedAtMs.takeIf { it > 0L } ?: stoppedAtMs))
                            .coerceAtLeast(0)
                    )
                }
            }
            failureReason = frontendFinishMessage
            val resultPayload = buildResult().toMutableMap()
            resultPayload["error_code"] = "OOB_FUNCTION_STOPPED"
            resultPayload["error_message"] = frontendFinishMessage
            resultPayload["timing"] = timing.finish()
            frontendSession?.finish(frontendFinishMessage, closeAfterMs = frontendCloseAfterMs)
            frontendFinished = true
            return resultPayload
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (isUserCompletedReplay()) {
                frontendFinishMessage = helper.localized("任务已完成")
                frontendCloseAfterMs = FRONTEND_SUCCESS_POPUP_VISIBLE_MS
                val resultPayload = buildUserCompletedResult()
                resultPayload["timing"] = timing.finish()
                frontendSession?.finish(frontendFinishMessage, closeAfterMs = frontendCloseAfterMs)
                frontendFinished = true
                return resultPayload
            }
            if (frontendSession?.isStopRequested() == true || toolHandle?.isManualStopRequested() == true) {
                frontendFinishMessage = helper.localized("任务已停止")
                frontendCloseAfterMs = FRONTEND_TERMINAL_POPUP_VISIBLE_MS
                if (currentStepIndex >= 0 && stepResults.none { it["index"] == currentStepIndex }) {
                    val stoppedAtMs = System.currentTimeMillis()
                    stepResults += LinkedHashMap<String, Any?>().apply {
                        putAll(
                            runResultBuilder.failureStep(
                                stepId = currentStepId.ifBlank { "step_${currentStepIndex + 1}" },
                                tool = currentStepTool.ifBlank { "?" },
                                executor = currentStepExecutor.ifBlank { RunLogReplayPolicy.EXECUTOR_OMNIFLOW },
                                summary = frontendFinishMessage,
                                errorCode = "OOB_FUNCTION_STOPPED",
                            )
                        )
                        put("index", currentStepIndex)
                        put("started_at_ms", currentStepStartedAtMs.takeIf { it > 0L } ?: stoppedAtMs)
                        put("finished_at_ms", stoppedAtMs)
                        put(
                            "duration_ms",
                            (stoppedAtMs - (currentStepStartedAtMs.takeIf { it > 0L } ?: stoppedAtMs))
                                .coerceAtLeast(0)
                        )
                    }
                }
                failureReason = frontendFinishMessage
                val resultPayload = buildResult().toMutableMap()
                resultPayload["error_code"] = "OOB_FUNCTION_STOPPED"
                resultPayload["error_message"] = frontendFinishMessage
                resultPayload["timing"] = timing.finish()
                frontendSession?.finish(frontendFinishMessage, closeAfterMs = frontendCloseAfterMs)
                frontendFinished = true
                return resultPayload
            }
            frontendFinishMessage = helper.localized("任务已停止")
            frontendCloseAfterMs = FRONTEND_TERMINAL_POPUP_VISIBLE_MS
            throw e
        } catch (e: Exception) {
            frontendFinishMessage = helper.localized("任务执行失败")
            frontendCloseAfterMs = FRONTEND_TERMINAL_POPUP_VISIBLE_MS
            throw e
        } finally {
            if (!frontendFinished) {
                frontendSession?.finish(frontendFinishMessage, closeAfterMs = frontendCloseAfterMs)
            }
        }
    }

    private suspend fun handleUnclassifiedStep(
        step: Map<String, Any?>,
        stepId: String,
        stepTitle: String,
        executor: String,
        callableTool: String,
        env: cn.com.omnimind.bot.agent.AgentExecutionEnvironment?,
        callback: cn.com.omnimind.bot.agent.AgentCallback?,
        toolHandle: cn.com.omnimind.bot.agent.AgentToolExecutionHandle?,
        parentToolCallId: String?,
        toolName: String,
        allowAgentFallback: Boolean,
    ): Map<String, Any?> {
        val agentTool = replayableAgentTool(step, callableTool)
        val routerRef = router
        if (!requiresAgentPlanning(step) &&
            agentTool.isNotEmpty() && routerRef != null && env != null
        ) {
            return delegateStep(step, stepId, stepTitle, agentTool, env, callback, toolHandle, parentToolCallId, toolName, routerRef)
                .also { result ->
                    (result as? LinkedHashMap<String, Any?>)?.put("executor", "agent_tool")
                    (result as? LinkedHashMap<String, Any?>)?.put("delegated", true)
                }
        }
        if (allowAgentFallback) {
            return runResultBuilder.agentFallbackStep(
                stepId = stepId,
                tool = agentTool.ifEmpty { "?" },
                prompt = agentFallbackController.prompt(step, stepTitle),
                summary = "One-step online repair required: $stepTitle",
            )
        }
        return runResultBuilder.failureStep(
            stepId = stepId,
            tool = agentTool.ifEmpty { callableTool.ifEmpty { "?" } },
            executor = executor.ifEmpty { RunLogReplayPolicy.EXECUTOR_AGENT },
            summary = "No local handler for replay step: $stepTitle",
            errorCode = "OOB_FUNCTION_STEP_UNSUPPORTED",
        )
    }

    private suspend fun delegateStep(
        step: Map<String, Any?>,
        stepId: String,
        stepTitle: String,
        callableTool: String,
        env: cn.com.omnimind.bot.agent.AgentExecutionEnvironment,
        callback: cn.com.omnimind.bot.agent.AgentCallback?,
        toolHandle: cn.com.omnimind.bot.agent.AgentToolExecutionHandle?,
        parentToolCallId: String?,
        toolName: String,
        router: cn.com.omnimind.bot.agent.AgentToolExecutor,
    ): Map<String, Any?> {
        val callId = "${parentToolCallId ?: toolName}_$stepId"
        return toolDelegationExecutor.execute(
            step = step,
            stepId = stepId,
            stepTitle = stepTitle,
            callableTool = callableTool,
            env = env,
            callback = callback ?: cn.com.omnimind.bot.agent.NoOpAgentCallback,
            toolHandle = toolHandle ?: cn.com.omnimind.bot.agent.NoOpAgentRunControl
                .beginToolExecution(callableTool, callId),
            syntheticCallId = callId,
            router = router,
        )
    }

    private suspend fun executeOmniflowToolCallStep(
        step: Map<String, Any?>,
        stepId: String,
        stepTitle: String,
        callableTool: String,
        callback: cn.com.omnimind.bot.agent.AgentCallback?,
        toolHandle: cn.com.omnimind.bot.agent.AgentToolExecutionHandle?,
        env: cn.com.omnimind.bot.agent.AgentExecutionEnvironment?,
        parentToolCallId: String?,
        toolName: String,
        allowAgentFallback: Boolean,
        allowToolDelegationWithoutRouter: Boolean,
        callStack: List<String>,
        frontendParent: String = "",
    ): Map<String, Any?> {
        val args = resolveStepArgs(step)
        val callTool = resolveCallRequest(args, step)
        val targetTool = callTool.targetTool
        val targetArgs = callTool.targetArgs
        val functionId = callTool.functionId
        if (functionId.isNotEmpty()) {
            val functionStep = LinkedHashMap<String, Any?>().apply {
                putAll(step)
                put("args", LinkedHashMap<String, Any?>().apply {
                    putAll(args); put("function_id", functionId); put("arguments", targetArgs)
                })
            }
            return executeOmniflowFunctionStep(
                step = functionStep, stepId = stepId, stepTitle = stepTitle,
                callableTool = callableTool.ifEmpty { RunLogReplayPolicy.TOOL_CALL_TOOL },
                callback = callback, toolHandle = toolHandle, env = env,
                parentToolCallId = parentToolCallId, toolName = toolName,
                allowAgentFallback = allowAgentFallback,
                allowToolDelegationWithoutRouter = allowToolDelegationWithoutRouter,
                callStack = callStack,
                frontendParent = frontendParent,
            )
        }
        if (targetTool.isEmpty()) return runResultBuilder.failureStep(
            stepId = stepId, tool = callableTool.ifEmpty { RunLogReplayPolicy.TOOL_CALL_TOOL },
            executor = RunLogReplayPolicy.EXECUTOR_TOOL,
            summary = "$stepTitle missing tool_name or function_id", errorCode = "OOB_CALL_TOOL_TARGET_MISSING",
        )
        if (RunLogReplayPolicy.isOmniflowToolCallTool(targetTool)) return runResultBuilder.failureStep(
            stepId = stepId, tool = callableTool.ifEmpty { RunLogReplayPolicy.TOOL_CALL_TOOL },
            executor = RunLogReplayPolicy.EXECUTOR_TOOL,
            summary = "$stepTitle nested call_tool is not allowed", errorCode = "OOB_CALL_TOOL_RECURSION",
        )
        val routerRef = router
        if (routerRef != null && env != null) {
            val delegatedStep = LinkedHashMap<String, Any?>().apply {
                putAll(step); put("tool", targetTool); put("args", targetArgs)
            }
            return LinkedHashMap<String, Any?>().apply {
                putAll(delegateStep(delegatedStep, stepId, stepTitle, targetTool, env, callback, toolHandle, parentToolCallId, toolName, routerRef))
                put("delegated_from", callableTool.ifEmpty { RunLogReplayPolicy.TOOL_CALL_TOOL })
                put("delegated_tool_used", true)
            }
        }
        if (allowAgentFallback && !allowToolDelegationWithoutRouter) {
            return runResultBuilder.agentFallbackStep(
                stepId = stepId, tool = targetTool,
                prompt = agentFallbackController.prompt(
                    LinkedHashMap<String, Any?>().apply { putAll(step); put("tool", targetTool); put("args", targetArgs) },
                    stepTitle
                ),
                summary = "call_tool step requires one-step online repair: $stepTitle",
            )
        }
        return runResultBuilder.failureStep(
            stepId = stepId, tool = targetTool, executor = RunLogReplayPolicy.EXECUTOR_TOOL,
            summary = "Tool router unavailable for $targetTool", errorCode = "OOB_CALL_TOOL_ROUTER_UNAVAILABLE",
        )
    }

    private suspend fun executeOmniflowFunctionStep(
        step: Map<String, Any?>,
        stepId: String,
        stepTitle: String,
        callableTool: String,
        callback: cn.com.omnimind.bot.agent.AgentCallback?,
        toolHandle: cn.com.omnimind.bot.agent.AgentToolExecutionHandle?,
        env: cn.com.omnimind.bot.agent.AgentExecutionEnvironment?,
        parentToolCallId: String?,
        toolName: String,
        allowAgentFallback: Boolean,
        allowToolDelegationWithoutRouter: Boolean,
        callStack: List<String>,
        frontendParent: String = "",
    ): Map<String, Any?> {
        val args = resolveStepArgs(step)
        val functionId = firstNonBlank(args["function_id"], step["function_id"])
        val nestedArguments = mapArg(args["arguments"])
        val cardToolName = RunLogReplayPolicy.TOOL_CALL_TOOL
        val cardId = nestedCallCardPresenter.cardId(parentToolCallId, toolName, stepId)
        val cardStartedAtMs = System.currentTimeMillis()

        suspend fun emitStarted() {
            callback?.onToolCardEvent("tool_started", nestedCallCardPresenter.payload(
                cardId = cardId, toolName = cardToolName, stepTitle = stepTitle,
                functionId = functionId, callableTool = callableTool,
                nestedArguments = nestedArguments, status = "running", success = null,
                summary = nestedCallCardPresenter.runningSummary(functionId),
                progress = stepTitle, startedAtMs = cardStartedAtMs, finishedAtMs = null, result = null,
            ))
        }
        suspend fun completeWithCard(result: Map<String, Any?>): Map<String, Any?> {
            val success = result["success"] != false
            callback?.onToolCardEvent("tool_completed", nestedCallCardPresenter.payload(
                cardId = cardId, toolName = cardToolName, stepTitle = stepTitle,
                functionId = functionId, callableTool = callableTool,
                nestedArguments = nestedArguments, status = if (success) "success" else "error",
                success = success, summary = result["summary"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: nestedCallCardPresenter.finishedSummary(functionId, success),
                progress = "", startedAtMs = cardStartedAtMs, finishedAtMs = System.currentTimeMillis(), result = result,
            ))
            return result
        }
        fun failStep(errorCode: String, summary: String, extras: Map<String, Any?> = emptyMap()) =
            runResultBuilder.failureStep(stepId = stepId, tool = callableTool.ifEmpty { RunLogReplayPolicy.TOOL_CALL_TOOL },
                executor = "omniflow_function", summary = summary, errorCode = errorCode, extras = extras)

        emitStarted()
        if (functionId.isEmpty()) return completeWithCard(failStep("OOB_FUNCTION_ID_MISSING", "$stepTitle missing function_id"))
        val nestedSpec = getSpec(functionId)
            ?: return completeWithCard(failStep("OOB_FUNCTION_NOT_FOUND", "OOB reusable function not found: $functionId",
                mapOf("nested_function_id" to functionId)))
        val missing = OobReusableFunctionStore.missingRequiredArguments(nestedSpec, nestedArguments)
        if (missing.isNotEmpty()) return completeWithCard(failStep("OOB_FUNCTION_ARGUMENTS_MISSING",
            "Missing required arguments: ${missing.joinToString(", ")}",
            mapOf("nested_function_id" to functionId, "missing_required_arguments" to missing)))
        val materialized = OobReusableFunctionStore.materialize(nestedSpec, nestedArguments)
        val nestedRun = runMaterializedFunction(
            functionId = functionId, spec = nestedSpec, materializedSpec = materialized,
            callback = callback, toolHandle = toolHandle, env = env,
            parentToolCallId = "${parentToolCallId ?: toolName}_$stepId",
            toolName = functionId, allowAgentFallback = allowAgentFallback,
            allowToolDelegationWithoutRouter = allowToolDelegationWithoutRouter, callStack = callStack,
            frontendParent = frontendParent,
        )
        val success = nestedRun["success"] == true
        val nestedStepResults = listArg(nestedRun["step_results"])
            .mapNotNull { mapArg(it).takeIf { mapped -> mapped.isNotEmpty() } }
        val nestedOnlineRepairRequired =
            nestedRun["online_repair_required"] == true ||
                nestedRun["fallback_context"] != null ||
                nestedStepResults.any { it["online_repair_required"] == true }
        val nestedOnlineRepairAvailable =
            nestedRun["online_repair_available"] == true ||
                nestedStepResults.any {
                    it["online_repair_required"] == true && it["online_repair_available"] == true
                }
        val nestedModelRequired = nestedRun["model_required"] == true && !nestedOnlineRepairRequired
        return completeWithCard(linkedMapOf<String, Any?>(
            "step_id" to stepId, "tool" to callableTool.ifEmpty { RunLogReplayPolicy.TOOL_CALL_TOOL },
            "executor" to "omniflow_function", "model_free" to true, "success" to success,
            "model_required" to nestedModelRequired.takeIf { it },
            "online_repair_required" to nestedOnlineRepairRequired.takeIf { it },
            "online_repair_available" to nestedOnlineRepairAvailable.takeIf { nestedOnlineRepairRequired },
            "nested_function_id" to functionId, "nested_run_id" to nestedRun["run_id"],
            "nested_runner" to nestedRun["runner"], "nested_step_count" to nestedRun["step_count"],
            "nested_success_step_count" to nestedRun["success_step_count"],
            "nested_model_required" to nestedModelRequired,
            "nested_online_repair_required" to nestedOnlineRepairRequired,
            "nested_online_repair_available" to nestedOnlineRepairAvailable,
            "nested_failed_step_index" to nestedRun["failed_step_index"],
            "nested_resume_from_step" to nestedRun["resume_from_step"],
            "nested_fallback_context" to nestedRun["fallback_context"],
            "nested_agent_prompt" to nestedRun["agent_prompt"],
            "step_results" to nestedRun["step_results"], "timing" to nestedRun["timing"],
            "error_code" to nestedRun["error_code"],
            "summary" to if (success) "复用指令执行完成：$functionId"
                else nestedRun["error_message"]?.toString()?.takeIf { it.isNotBlank() } ?: "复用指令执行失败：$functionId",
        ).filterValues { it != null })
    }

    // -----------------------------------------------------------------------
    // OmniFlow execution path
    // -----------------------------------------------------------------------

    suspend fun runFunction(
        fn: OmniflowFunction,
        startIndex: Int = 0,
        allowAgentFallback: Boolean = false,
        allowToolDelegationWithoutRouter: Boolean = false,
        callback: cn.com.omnimind.bot.agent.AgentCallback? = null,
        toolHandle: cn.com.omnimind.bot.agent.AgentToolExecutionHandle? = null,
        env: cn.com.omnimind.bot.agent.AgentExecutionEnvironment? = null,
        callStack: List<String> = emptyList(),
        frontendRunId: String = "",
        frontendTaskId: String = "",
        frontendParent: String = "",
    ): Map<String, Any?> {
        val runStartedAtMs = System.currentTimeMillis()
        val auditRunId = nextRunId(runStartedAtMs)
        val normalizedStartIndex = startIndex.coerceIn(0, fn.executableSteps.size)
        val activeSteps = fn.executableSteps.drop(normalizedStartIndex)
        val frontendSpec = mapOf(
            "name" to fn.name,
            "description" to fn.description,
        )
        val frontendSession = frontendSessionController.start(
            functionId = fn.id,
            spec = frontendSpec,
            stepCount = activeSteps.size,
            toolHandle = toolHandle,
            callStack = callStack,
            fallbackRunIdProvider = { auditRunId },
            frontendRunId = frontendRunId,
            frontendTaskId = frontendTaskId,
            frontendParent = frontendParent,
        )
        var frontendFinished = false
        val replayStopRequested = {
            frontendSession?.isStopRequested() == true ||
                toolHandle?.isManualStopRequested() == true
        }
        var frontendFinishMessage = helper.localized("任务已完成")
        var frontendCloseAfterMs = FRONTEND_TERMINAL_POPUP_VISIBLE_MS
        val activeCallStack = if (fn.id.isNotBlank()) callStack + fn.id else callStack
        val stepResults = mutableListOf<Map<String, Any?>>()
        var failureReason: String? = null
        var modelRequired = false
        var delegatedToolUsed = false
        var currentStepIndex = -1
        var currentStepId = ""
        var currentStepTool = ""
        var currentStepStartedAtMs = 0L

        // Synthetic step maps for pre-loop checks (preflight + goTo navigator)
        val syntheticActiveSteps = activeSteps.map { s ->
            linkedMapOf<String, Any?>(
                "id" to s.id, "title" to s.title, "tool" to s.toolName,
                "executor" to RunLogReplayPolicy.EXECUTOR_OMNIFLOW,
                "args" to s.arguments.mapValues { (_, v) -> (v as? ParameterValue.Literal)?.value },
                "source_context" to s.sourceContext,
            ).filterValues { it != null }
        }

        // Accessibility preflight
        accessibilityPreflightGuard.failureIfBlocked(
            functionId = fn.id, spec = frontendSpec, auditRunId = auditRunId,
            startedAtMs = runStartedAtMs, steps = syntheticActiveSteps,
        )?.let {
            frontendSession?.finish(helper.localized("任务执行失败"), closeAfterMs = FRONTEND_TERMINAL_POPUP_VISIBLE_MS)
            frontendFinished = true
            return it
        }

        // Source page navigation (go-to pre-execution)
        val startPreparation = goToNavigator.navigate(
            functionId = fn.id, spec = frontendSpec, auditRunId = auditRunId,
            startedAtMs = runStartedAtMs, activeSteps = syntheticActiveSteps,
            allowAgentFallback = allowAgentFallback,
            allowToolDelegationWithoutRouter = allowToolDelegationWithoutRouter,
            callStack = activeCallStack, getSpec = ::getSpec,
            runFunction = { id, sp, mat ->
                runMaterializedFunction(
                    functionId = id, spec = sp, materializedSpec = mat,
                    allowAgentFallback = allowAgentFallback,
                    allowToolDelegationWithoutRouter = allowToolDelegationWithoutRouter,
                    callStack = activeCallStack,
                )
            },
        )
        startPreparation.failure?.let {
            frontendSession?.finish(helper.localized("任务执行失败"), closeAfterMs = FRONTEND_TERMINAL_POPUP_VISIBLE_MS)
            frontendFinished = true
            return it
        }

        // Function-level checker rules from Function metadata
        val functionCheckerRules = fn.metadata.checkerRules.mapNotNull { r ->
            OmniflowCheckerRule.fromMap(
                mapOf("id" to r.id, "phase" to r.phase, "condition" to r.condition,
                    "action" to r.action, "enabled" to r.enabled, "params" to r.params)
            )
        }
        val checkerBudget = UIStepExecutor.CheckerTriggerBudget()

        fun buildResult(finishedAtMs: Long = System.currentTimeMillis()): LinkedHashMap<String, Any?> =
            LinkedHashMap<String, Any?>().apply {
                val successCount = stepResults.count { it["success"] != false }
                val failedStepIndex = stepResults.firstOrNull { it["success"] == false }?.get("index")
                val lastStepIndex = stepResults.lastOrNull()?.get("index")
                val allSuccess = stepResults.size == activeSteps.size &&
                    stepResults.none { it["success"] == false }
                val onlineRepairRequired = stepResults.any { it["online_repair_required"] == true }
                val onlineRepairAvailable = !onlineRepairRequired ||
                    stepResults.any {
                        it["online_repair_required"] == true && it["online_repair_available"] == true
                    }
                val currentResultStepIndex = when {
                    currentStepIndex >= 0 -> currentStepIndex
                    failedStepIndex != null -> failedStepIndex
                    lastStepIndex != null -> lastStepIndex
                    else -> null
                }
                put("success", allSuccess)
                put("function_id", fn.id)
                put(
                    "runner",
                    if (onlineRepairRequired) {
                        "oob_function_online_repair_required"
                    } else {
                        RunLogReplayPolicy.fixedReplayRunner
                    }
                )
                put("step_count", fn.executableSteps.size)
                put("active_step_count", activeSteps.size)
                put("success_step_count", successCount)
                put("completed_step_count", (normalizedStartIndex + successCount).coerceAtMost(fn.executableSteps.size))
                put("resume_from_step", normalizedStartIndex)
                put("failed_step_index", failedStepIndex)
                put("current_step_index", currentResultStepIndex)
                put("current_step_number", (currentResultStepIndex as? Number)?.toInt()?.plus(1))
                put("model_used", modelRequired)
                put("model_required", modelRequired)
                if (onlineRepairRequired) {
                    put("online_repair_required", true)
                    put("online_repair_available", onlineRepairAvailable)
                }
                put("delegated_tool_used", delegatedToolUsed)
                if (!allSuccess && failureReason == null) {
                    put("error_code", "OOB_FUNCTION_INCOMPLETE")
                    put(
                        "error_message",
                        "Function replay finished before all active steps completed: " +
                            "${stepResults.size}/${activeSteps.size}"
                    )
                } else {
                    put("error_message", failureReason ?: "")
                }
                put("step_results", stepResults)
                put("run_id", auditRunId)
                put("started_at_ms", runStartedAtMs)
                put("finished_at_ms", finishedAtMs)
                startPreparation.goToResult?.let { put("pre_function_go_to", it) }
            }
        fun isUserCompletedReplay(): Boolean =
            frontendSession?.isUserFinishedRequested() == true &&
                stepResults.size >= activeSteps.size &&
                toolHandle?.isManualStopRequested() != true
        fun buildUserCompletedResult(): LinkedHashMap<String, Any?> =
            buildResult().apply {
                put("done_reason", "user_completed")
                put("completed_by_user", true)
                if (this["success"] == true) {
                    put("error_code", null)
                    put("error_message", "")
                }
            }

        try {
            for ((relativeIdx, step) in activeSteps.withIndex()) {
                val absIdx = normalizedStartIndex + relativeIdx
                val stepStartedAtMs = System.currentTimeMillis()
                frontendSession?.throwIfStopRequested()
                toolHandle?.throwIfStopRequested()
                currentStepIndex = absIdx
                currentStepId = step.id
                currentStepTool = step.toolName
                currentStepStartedAtMs = stepStartedAtMs
                frontendSession?.update("第 ${absIdx + 1}/${fn.executableSteps.size} 步 ${step.title}")
                val resolvedArgs = step.resolveArguments(emptyMap())

                val stepResult: Map<String, Any?> = when {
                    // agent_call step: needs VLM if local dispatch unavailable
                    step.agentCallContext != null && !canDispatchLocally(step.toolName) -> {
                        if (allowAgentFallback) {
                            runResultBuilder.agentFallbackStep(
                                stepId = step.id,
                                tool = step.agentCallContext.originalTool,
                                prompt = step.agentCallContext.reason.ifBlank { step.title },
                                summary = "Agent step requires one-step online repair: ${step.title}",
                            )
                        } else {
                            runResultBuilder.failureStep(
                                stepId = step.id,
                                tool = step.agentCallContext.originalTool,
                                executor = RunLogReplayPolicy.EXECUTOR_AGENT,
                                summary = "Agent step cannot run locally: ${step.title}",
                                errorCode = "OOB_FUNCTION_STEP_UNSUPPORTED",
                            )
                        }
                    }
                    // Function call: recurse via Function store or fall back to Map path
                    OmniflowFunctionStore.contains(context, step.toolName) -> {
                        delegatedToolUsed = true
                        val nestedFn = OmniflowFunctionStore.get(context, step.toolName)
                            ?.materialize(resolvedArgs)
                        if (nestedFn != null) {
                            runFunction(
                                fn = nestedFn,
                                allowAgentFallback = allowAgentFallback,
                                allowToolDelegationWithoutRouter = allowToolDelegationWithoutRouter,
                                callback = callback,
                                toolHandle = toolHandle,
                                env = env,
                                callStack = activeCallStack,
                                frontendRunId = frontendRunId,
                                frontendTaskId = frontendTaskId,
                                frontendParent = frontendParent,
                            )
                        } else {
                            runResultBuilder.failureStep(
                                stepId = step.id, tool = step.toolName,
                                executor = RunLogReplayPolicy.EXECUTOR_OMNIFLOW,
                                summary = "Nested function not found: ${step.toolName}",
                                errorCode = "OOB_FUNCTION_NOT_FOUND",
                            )
                        }
                    }
                    // Omniflow primitive action: dispatch via UIStepExecutor
                    RunLogReplayPolicy.omniflowActions.contains(step.toolName) -> {
                        val syntheticStep = linkedMapOf<String, Any?>(
                            "id" to step.id,
                            "title" to step.title,
                            "tool" to step.toolName,
                            "executor" to RunLogReplayPolicy.EXECUTOR_OMNIFLOW,
                            "args" to resolvedArgs,
                            "source_context" to step.sourceContext,
                        )
                        val preActionReadyWait = if (absIdx > 0) {
                            val recoveryStep = fn.executableSteps.getOrNull(absIdx - 1)
                                ?.takeIf { RunLogReplayPolicy.omniflowActions.contains(it.toolName) }
                                ?.let { previous ->
                                    linkedMapOf<String, Any?>(
                                        "id" to previous.id,
                                        "title" to previous.title,
                                        "tool" to previous.toolName,
                                        "executor" to RunLogReplayPolicy.EXECUTOR_OMNIFLOW,
                                        "args" to previous.resolveArguments(emptyMap()),
                                        "source_context" to previous.sourceContext,
                                    )
                                }
                            UIStepExecutor.waitForReplayActionReady(
                                step = syntheticStep,
                                recoveryStep = recoveryStep,
                                stopRequested = replayStopRequested,
                            )
                        } else {
                            emptyMap()
                        }
                        val blockedResult = preActionReadyBlockedStep(
                            step = syntheticStep,
                            stepId = step.id,
                            stepTitle = step.title,
                            tool = step.toolName,
                            preActionReadyWait = preActionReadyWait,
                            allowAgentFallback = allowAgentFallback,
                        )
                        val nextSyntheticStep = fn.executableSteps.getOrNull(absIdx + 1)
                            ?.takeIf { RunLogReplayPolicy.omniflowActions.contains(it.toolName) }
                            ?.let { next ->
                                linkedMapOf<String, Any?>(
                                    "id" to next.id,
                                    "title" to next.title,
                                    "tool" to next.toolName,
                                    "executor" to RunLogReplayPolicy.EXECUTOR_OMNIFLOW,
                                    "args" to next.resolveArguments(emptyMap()),
                                    "source_context" to next.sourceContext,
                                )
                            }
                        val skipResult = skipStepIfNextStepAlreadyReady(
                            stepId = step.id,
                            stepTitle = step.title,
                            tool = step.toolName,
                            preActionReadyWait = preActionReadyWait,
                            nextStep = nextSyntheticStep,
                            stopRequested = replayStopRequested,
                        )
                        if (skipResult != null) {
                            withPreActionReadyWait(skipResult, preActionReadyWait)
                        } else if (blockedResult != null) {
                            withPreActionReadyWait(blockedResult, preActionReadyWait)
                        } else try {
                            val result = UIStepExecutor.execute(
                                step = syntheticStep,
                                stepId = step.id,
                                stepTitle = step.title,
                                checkerRules = functionCheckerRules + step.checkerRules.mapNotNull { r ->
                                    OmniflowCheckerRule.fromMap(
                                        mapOf("id" to r.id, "phase" to r.phase,
                                            "condition" to r.condition, "action" to r.action,
                                            "enabled" to r.enabled, "params" to r.params)
                                    )
                                },
                                checkerBudget = checkerBudget,
                                stopRequested = replayStopRequested,
                            )
                            withPreActionReadyWait(result, preActionReadyWait)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val failReason = e.message ?: "omniflow step failed"
                        val postFailureSkip = skipStepIfNextStepAlreadyReady(
                            stepId = step.id,
                            stepTitle = step.title,
                            tool = step.toolName,
                            preActionReadyWait = preActionReadyWait,
                            nextStep = nextSyntheticStep,
                            stopRequested = replayStopRequested,
                        )
                        if (postFailureSkip != null) {
                            withPreActionReadyWait(
                                LinkedHashMap<String, Any?>().apply {
                                    putAll(postFailureSkip)
                                    put("failed_step_reason", failReason)
                                    put(
                                        "skip_phase",
                                        "after_failed_step_next_replay_step_already_ready",
                                    )
                                },
                                preActionReadyWait,
                            )
                        } else if (allowAgentFallback) {
                                val fallbackResult = runResultBuilder.agentFallbackStep(
                                    stepId = step.id,
                                    tool = step.toolName,
                                    prompt = agentFallbackController.prompt(syntheticStep, step.title),
                                    summary = "OmniFlow step requires one-step online repair: ${step.title}",
                                )
                                withPreActionReadyWait(fallbackResult, preActionReadyWait)
                            } else {
                                val failureResult = runResultBuilder.failureStep(
                                    stepId = step.id, tool = step.toolName,
                                    executor = RunLogReplayPolicy.EXECUTOR_OMNIFLOW,
                                    summary = failReason,
                                    errorCode = "OOB_OMNIFLOW_STEP_FAILED",
                                )
                                withPreActionReadyWait(failureResult, preActionReadyWait)
                        }
                    }
                }
                    // Live tool: delegate via router
                    router != null && env != null -> {
                        delegatedToolUsed = true
                        delegateStep(
                            step = linkedMapOf("id" to step.id, "title" to step.title,
                                "tool" to step.toolName, "args" to resolvedArgs),
                            stepId = step.id,
                            stepTitle = step.title,
                            callableTool = step.toolName,
                            env = env,
                            callback = callback,
                            toolHandle = toolHandle,
                            parentToolCallId = null,
                            toolName = fn.id,
                            router = router!!,
                        )
                    }
                    allowAgentFallback -> {
                        runResultBuilder.agentFallbackStep(
                            stepId = step.id, tool = step.toolName,
                            prompt = step.title,
                            summary = "Step requires one-step online repair: ${step.title}",
                        )
                    }
                    else -> runResultBuilder.failureStep(
                        stepId = step.id, tool = step.toolName,
                        executor = RunLogReplayPolicy.EXECUTOR_OMNIFLOW,
                        summary = "No handler for tool: ${step.toolName}",
                        errorCode = "OOB_FUNCTION_STEP_UNSUPPORTED",
                    )
                }

                frontendSession?.throwIfStopRequested()
                toolHandle?.throwIfStopRequested()
                val finishedAtMs = System.currentTimeMillis()
                val timedResult = LinkedHashMap<String, Any?>().apply {
                    putAll(stepResult)
                    putIfAbsent("index", absIdx)
                    putIfAbsent("started_at_ms", stepStartedAtMs)
                    putIfAbsent("finished_at_ms", finishedAtMs)
                    putIfAbsent("duration_ms", (finishedAtMs - stepStartedAtMs).coerceAtLeast(0))
                }
                stepResults += timedResult
                currentStepIndex = -1
                if (timedResult["model_required"] == true) modelRequired = true
                if (timedResult["success"] == false) {
                    failureReason = timedResult["summary"]?.toString()
                    break
                }
            }

            val resultPayload = if (isUserCompletedReplay()) {
                buildUserCompletedResult()
            } else {
                buildResult()
            }
            val allSuccess = resultPayload["success"] == true
            frontendFinishMessage = helper.localized(if (allSuccess) "任务已完成" else "任务执行失败")
            frontendCloseAfterMs = if (allSuccess) {
                FRONTEND_SUCCESS_POPUP_VISIBLE_MS
            } else {
                FRONTEND_TERMINAL_POPUP_VISIBLE_MS
            }
            frontendSession?.finish(frontendFinishMessage, closeAfterMs = frontendCloseAfterMs)
            frontendFinished = true
            return resultPayload
        } catch (e: ManualToolStopCancellationException) {
            if (isUserCompletedReplay()) {
                frontendFinishMessage = helper.localized("任务已完成")
                frontendCloseAfterMs = FRONTEND_SUCCESS_POPUP_VISIBLE_MS
                val resultPayload = buildUserCompletedResult()
                frontendSession?.finish(frontendFinishMessage, closeAfterMs = frontendCloseAfterMs)
                frontendFinished = true
                return resultPayload
            }
            frontendFinishMessage = helper.localized("任务已停止")
            frontendCloseAfterMs = FRONTEND_TERMINAL_POPUP_VISIBLE_MS
            if (currentStepIndex >= 0 && stepResults.none { it["index"] == currentStepIndex }) {
                val stoppedAtMs = System.currentTimeMillis()
                stepResults += LinkedHashMap<String, Any?>().apply {
                    putAll(
                        runResultBuilder.failureStep(
                            stepId = currentStepId.ifBlank { "step_${currentStepIndex + 1}" },
                            tool = currentStepTool.ifBlank { "?" },
                            executor = RunLogReplayPolicy.EXECUTOR_OMNIFLOW,
                            summary = frontendFinishMessage,
                            errorCode = "OOB_FUNCTION_STOPPED",
                        )
                    )
                    put("index", currentStepIndex)
                    put("started_at_ms", currentStepStartedAtMs.takeIf { it > 0L } ?: stoppedAtMs)
                    put("finished_at_ms", stoppedAtMs)
                    put(
                        "duration_ms",
                        (stoppedAtMs - (currentStepStartedAtMs.takeIf { it > 0L } ?: stoppedAtMs))
                            .coerceAtLeast(0)
                    )
                }
            }
            failureReason = frontendFinishMessage
            val resultPayload = buildResult()
            resultPayload["error_code"] = "OOB_FUNCTION_STOPPED"
            resultPayload["error_message"] = frontendFinishMessage
            frontendSession?.finish(frontendFinishMessage, closeAfterMs = frontendCloseAfterMs)
            frontendFinished = true
            return resultPayload
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (isUserCompletedReplay()) {
                frontendFinishMessage = helper.localized("任务已完成")
                frontendCloseAfterMs = FRONTEND_SUCCESS_POPUP_VISIBLE_MS
                val resultPayload = buildUserCompletedResult()
                frontendSession?.finish(frontendFinishMessage, closeAfterMs = frontendCloseAfterMs)
                frontendFinished = true
                return resultPayload
            }
            if (frontendSession?.isStopRequested() == true || toolHandle?.isManualStopRequested() == true) {
                frontendFinishMessage = helper.localized("任务已停止")
                frontendCloseAfterMs = FRONTEND_TERMINAL_POPUP_VISIBLE_MS
                if (currentStepIndex >= 0 && stepResults.none { it["index"] == currentStepIndex }) {
                    val stoppedAtMs = System.currentTimeMillis()
                    stepResults += LinkedHashMap<String, Any?>().apply {
                        putAll(
                            runResultBuilder.failureStep(
                                stepId = currentStepId.ifBlank { "step_${currentStepIndex + 1}" },
                                tool = currentStepTool.ifBlank { "?" },
                                executor = RunLogReplayPolicy.EXECUTOR_OMNIFLOW,
                                summary = frontendFinishMessage,
                                errorCode = "OOB_FUNCTION_STOPPED",
                            )
                        )
                        put("index", currentStepIndex)
                        put("started_at_ms", currentStepStartedAtMs.takeIf { it > 0L } ?: stoppedAtMs)
                        put("finished_at_ms", stoppedAtMs)
                        put(
                            "duration_ms",
                            (stoppedAtMs - (currentStepStartedAtMs.takeIf { it > 0L } ?: stoppedAtMs))
                                .coerceAtLeast(0)
                        )
                    }
                }
                failureReason = frontendFinishMessage
                val resultPayload = buildResult()
                resultPayload["error_code"] = "OOB_FUNCTION_STOPPED"
                resultPayload["error_message"] = frontendFinishMessage
                frontendSession?.finish(frontendFinishMessage, closeAfterMs = frontendCloseAfterMs)
                frontendFinished = true
                return resultPayload
            }
            frontendFinishMessage = helper.localized("任务已停止")
            frontendCloseAfterMs = FRONTEND_TERMINAL_POPUP_VISIBLE_MS
            throw e
        } catch (e: Exception) {
            frontendFinishMessage = helper.localized("任务执行失败")
            frontendCloseAfterMs = FRONTEND_TERMINAL_POPUP_VISIBLE_MS
            throw e
        } finally {
            if (!frontendFinished) {
                frontendSession?.finish(frontendFinishMessage, closeAfterMs = frontendCloseAfterMs)
            }
        }
    }

    private fun canDispatchLocally(toolName: String): Boolean =
        RunLogReplayPolicy.omniflowActions.contains(toolName) ||
            OmniflowFunctionStore.contains(context, toolName) ||
            router != null

    private fun materializedSteps(materializedSpec: Map<String, Any?>): List<Map<String, Any?>> =
        OobFunctionSchemaBuilder.materializedSteps(materializedSpec)

    private data class CallRequest(val targetTool: String, val targetArgs: Map<String, Any?>, val functionId: String)

    private fun resolveStepArgs(step: Map<String, Any?>): Map<String, Any?> {
        val directArgs = mapArg(step["args"])
        val agentCall = mapArg(step["agent_call"])
        val agentArgs = mapArg(agentCall["args"])
        val originalArgs = mapArg(directArgs["original_args"]).ifEmpty { mapArg(directArgs["originalArgs"]) }
            .ifEmpty { mapArg(agentArgs["original_args"]) }.ifEmpty { mapArg(agentArgs["originalArgs"]) }
        val executionArgKeys = setOf("function_id","tool_name","target_tool","node_id","target_node_id",
            "edge_id","action_id","path","edges","utg","graph","arguments")
        val topLevelArgs = buildMap { for (key in executionArgKeys) if (step.containsKey(key)) put(key, step[key]) }
        return when {
            directArgs.any { (k, v) -> k in executionArgKeys && v != null } -> directArgs
            originalArgs.isNotEmpty() -> originalArgs
            topLevelArgs.isNotEmpty() -> topLevelArgs
            else -> directArgs
        }
    }

    private fun resolveCallRequest(args: Map<String, Any?>, step: Map<String, Any?> = emptyMap()): CallRequest {
        val targetTool = firstNonBlank(args["tool_name"], args["target_tool"], args["tool"], step["tool_name"], step["target_tool"])
        val targetArgs = mapArg(args["arguments"])
        val rawFunctionId = firstNonBlank(args["function_id"], step["function_id"])
        val functionId = firstNonBlank(
            rawFunctionId,
            if (RunLogReplayPolicy.isOmniflowToolCallTool(targetTool)) firstNonBlank(targetArgs["function_id"]) else null,
            targetTool.takeIf { it.isNotEmpty() && getSpec(it) != null },
        )
        return CallRequest(targetTool = targetTool, targetArgs = targetArgs, functionId = functionId)
    }

    // -----------------------------------------------------------------------
    // Inlined from OobFunctionStepClassifier
    // -----------------------------------------------------------------------

    private fun omniflowExecutionToolForStep(step: Map<String, Any?>, callableTool: String): String {
        val agentCall = mapArg(step["agent_call"])
        val agentArgs = mapArg(agentCall["args"])
        return listOf(callableTool, step["tool"], agentArgs["original_tool"], agentCall["original_tool"])
            .asSequence().map { it?.toString()?.trim().orEmpty() }
            .map { RunLogReplayPolicy.normalizeToolName(it) }
            .firstOrNull { it.isNotEmpty() && RunLogReplayPolicy.isOmniflowExecutionTool(it) }
            .orEmpty()
    }

    private fun isSkippedStep(step: Map<String, Any?>, callableTool: String = step["tool"]?.toString().orEmpty()): Boolean {
        val names = listOf(callableTool, UIStepExecutor.actionNameForStep(step))
        return names.any { it.isNotBlank() && RunLogReplayPolicy.shouldSkipTool(it) }
    }

    private fun isOmniflowExecutionStep(step: Map<String, Any?>): Boolean {
        val tool = omniflowExecutionToolForStep(step, step["tool"]?.toString()?.trim().orEmpty())
        return when {
            RunLogReplayPolicy.isOmniflowGraphTool(tool) -> true
            RunLogReplayPolicy.isOmniflowToolCallTool(tool) -> {
                val args = resolveStepArgs(step)
                firstNonBlank(args["function_id"], step["function_id"],
                    firstNonBlank(args["tool_name"], args["target_tool"]).takeIf { it.isNotEmpty() && getSpec(it) != null }
                ).isNotEmpty()
            }
            else -> false
        }
    }

    private fun replayableAgentTool(step: Map<String, Any?>, callableTool: String): String {
        val agentCall = mapArg(step["agent_call"])
        val agentArgs = mapArg(agentCall["args"])
        return listOf(agentArgs["original_tool"], step["tool"], callableTool, agentCall["tool"])
            .asSequence().map { it?.toString()?.trim().orEmpty() }
            .firstOrNull { it.isNotEmpty() && it != RunLogReplayPolicy.TOOL_AGENT_RUN }
            .orEmpty()
    }

    private fun requiresAgentPlanning(step: Map<String, Any?>): Boolean {
        val reason = mapArg(step["agent_call"])["reason"]?.toString() ?: step["reason"]?.toString() ?: ""
        return RunLogReplayPolicy.requiresAgentPlanningReason(reason)
    }

    private fun withPreActionReadyWait(
        result: Map<String, Any?>,
        preActionReadyWait: Map<String, Any?>,
    ): Map<String, Any?> {
        if (preActionReadyWait.isEmpty()) {
            return result
        }
        return LinkedHashMap<String, Any?>().apply {
            putAll(result)
            put("pre_action_ready_wait", preActionReadyWait)
        }
    }

    private suspend fun skipStepIfNextStepAlreadyReady(
        stepId: String,
        stepTitle: String,
        tool: String,
        preActionReadyWait: Map<String, Any?>,
        nextStep: Map<String, Any?>?,
        stopRequested: (() -> Boolean)?,
    ): Map<String, Any?>? {
        if (!isReplaySemanticTargetMissing(preActionReadyWait) || nextStep == null) {
            return null
        }
        if (!UIStepExecutor.isUIStep(nextStep)) {
            return null
        }
        val nextReady = UIStepExecutor.waitForReplayActionReady(
            step = nextStep,
            recoveryStep = null,
            stopRequested = stopRequested,
            settleDelayMs = 0L,
            targetTimeoutMs = 0L,
        )
        if (nextReady["status"] != "ready") {
            return null
        }
        return linkedMapOf<String, Any?>(
            "step_id" to stepId,
            "tool" to tool,
            "executor" to RunLogReplayPolicy.EXECUTOR_OMNIFLOW,
            "model_free" to true,
            "success" to true,
            "skipped" to true,
            "skip_reason" to "next_replay_step_already_ready",
            "summary" to "$stepTitle skipped because the next replay step is already ready",
            "next_action_ready_wait" to nextReady,
        )
    }

    private fun isReplaySemanticTargetMissing(preActionReadyWait: Map<String, Any?>): Boolean =
        preActionReadyWait["status"] == "timeout" &&
            preActionReadyWait["reason"] == "semantic_target_not_visible"

    private fun preActionReadyBlockedStep(
        step: Map<String, Any?>,
        stepId: String,
        stepTitle: String,
        tool: String,
        preActionReadyWait: Map<String, Any?>,
        allowAgentFallback: Boolean,
    ): Map<String, Any?>? {
        // Readiness is diagnostic only. The replay path must still go through the
        // normal checker/action-transfer/execute loop so source XML, anchors, and
        // coordinates get one unified chance to adapt to the live page.
        return null
    }

    private companion object {
        const val TAG = "OobFunctionToolHandler"
        const val MAX_OMNIFLOW_CALL_DEPTH = 8
        const val FRONTEND_SUCCESS_POPUP_VISIBLE_MS = 900L
        const val FRONTEND_TERMINAL_POPUP_VISIBLE_MS = 2500L
        val RUN_SEQUENCE = AtomicLong(0)
    }

    private fun nextRunId(startedAtMs: Long): String =
        "omniflow_run_${startedAtMs}_${RUN_SEQUENCE.incrementAndGet()}"
}
