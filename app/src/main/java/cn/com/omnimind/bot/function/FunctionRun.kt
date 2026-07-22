package cn.com.omnimind.bot.function

import cn.com.omnimind.assists.task.vlmserver.AndroidDeviceOperator
import cn.com.omnimind.assists.task.vlmserver.DeviceOperator
import cn.com.omnimind.assists.task.vlmserver.State
import cn.com.omnimind.bot.agent.AgentToolJson
import cn.com.omnimind.bot.agent.AgentToolNames
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.BrowserUseRequest
import cn.com.omnimind.bot.agent.LiveAgentBrowserSessionManager
import cn.com.omnimind.bot.agent.ManualToolStopCancellationException
import cn.com.omnimind.bot.agent.tool.handlers.SharedHelper
import cn.com.omnimind.baselib.runlog.OobActionSchema
import cn.com.omnimind.bot.omniflow.OmniFlowPythonRuntime
import cn.com.omnimind.bot.omniflow.OmniFlowReplayAdapter
import cn.com.omnimind.bot.function.FunctionJson.firstNonBlank
import cn.com.omnimind.bot.function.FunctionJson.intArg
import cn.com.omnimind.bot.function.FunctionJson.listArg
import cn.com.omnimind.bot.function.FunctionJson.longArg
import cn.com.omnimind.bot.function.FunctionJson.mapArg
import cn.com.omnimind.bot.runlog.ReplayHelper
import cn.com.omnimind.bot.runlog.argsForStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.atomic.AtomicLong

class FunctionRun(
    private val context: android.content.Context,
    private val helper: SharedHelper = SharedHelper(
        context,
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
        }
    ),
    private val deviceOperator: DeviceOperator = AndroidDeviceOperator(null, context),
    private val frontendSessionController: FunctionFrontendSessionController =
        FunctionFrontendSessionController(helper),
    private val runResultBuilder: FunctionRunResultBuilder =
        FunctionRunResultBuilder(),
) {
    private val omniFlowReplayAdapter = OmniFlowReplayAdapter(
        context = context,
        deviceOperator = deviceOperator,
    )
    /** Workspace-backed function store; injected by the Function layer on init. */
    var workspaceFunctionStore: cn.com.omnimind.bot.function.FunctionStore? =
        cn.com.omnimind.bot.function.FunctionStore(
            AgentWorkspaceManager.rootDirectory(context)
        )

    /** Returns the workspace-backed function spec so editable checker rules take effect. */
    private fun getSpec(functionId: String): Map<String, Any?>? =
        workspaceFunctionStore?.get(functionId)

    private fun timedReplayStepShouldSettle(stepResult: Map<String, Any?>): Boolean =
        stepResult["success"] == true &&
            FunctionSchema.isFunctionExecutor(stepResult["executor"]) &&
            stepResult["model_free"] == true

    suspend fun runFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val callTiming = FunctionTiming(
            source = "oob_function_call",
            requiredPhases = CALL_PHASES,
        )
        val request = args ?: emptyMap()
        val functionId = firstNonBlank(request["function_id"])
        val executionMode = firstNonBlank(request["execution_mode"])
            .ifBlank { "foreground" }

        var runPayload = callTiming.measureSuspend("execute_function_ms") {
            runFunction(
                functionId = functionId,
                arguments = mapArg(request["arguments"]),
                resumeFromStep = intArg(request["resume_from_step"], defaultValue = 0)
                    .coerceAtLeast(0),
                frontendRunId = firstNonBlank(request["frontend_run_id"]),
                frontendTaskId = firstNonBlank(request["frontend_task_id"]),
                frontendParent = firstNonBlank(request["frontend_parent"]),
            )
        }
        runPayload = normalizeIncompleteReplay(attachCallTiming(runPayload, callTiming))
        workspaceFunctionStore?.recordRun(
            functionId = functionId,
            success = runPayload["success"] == true,
            runId = runPayload["run_id"]?.toString(),
            runner = runPayload["runner"]?.toString(),
            stepCount = intArg(runPayload["step_count"], defaultValue = 0),
            errorMessage = runPayload["error_message"]?.toString()
        )
        FunctionRunLogRecorder.record(
            context = context,
            functionId = functionId,
            runPayload = runPayload,
        )
        return summarizeFunctionRun(
            functionId = functionId,
            executionMode = executionMode,
            runPayload = runPayload,
        )
    }

    private fun summarizeFunctionRun(
        functionId: String,
        executionMode: String,
        runPayload: Map<String, Any?>,
    ): Map<String, Any?> {
        val stepResults = listArg(runPayload["step_results"])
        val timing = mapArg(runPayload["timing"])
        val startedAtMs = longArg(timing["started_at_ms"], defaultValue = 0L)
        val finishedAtMs = longArg(timing["finished_at_ms"], defaultValue = 0L)
        val durationMs = longArg(
            timing["call_duration_ms"],
            timing["duration_ms"],
            timing["runner_duration_ms"],
            defaultValue = 0L,
        )
            .takeIf { it > 0L }
            ?: (finishedAtMs - startedAtMs).takeIf { startedAtMs > 0L && finishedAtMs >= startedAtMs }
            ?: stepResults.sumOf { raw ->
                longArg(mapArg(raw)["duration_ms"], defaultValue = 0L).coerceAtLeast(0L)
            }
        val successStepCount = intArg(
            runPayload["success_step_count"],
            defaultValue = stepResults.count { raw -> mapArg(raw)["success"] != false },
        )
        val stepCount = intArg(runPayload["step_count"], defaultValue = stepResults.size)
        val executionSummary = mapArg(runPayload["execution_summary"]).ifEmpty {
            linkedMapOf<String, Any?>(
                "success" to (runPayload["success"] == true),
                "function_id" to functionId,
                "steps" to successStepCount,
                "resolve_calls" to intArg(runPayload["resolve_calls"], defaultValue = 0),
                "model_calls" to intArg(runPayload["model_calls"], defaultValue = 0),
                "tokens" to intArg(runPayload["tokens"], runPayload["total_tokens"], defaultValue = 0),
                "elapsed_ms" to durationMs,
                "failure_reason" to runPayload["error_message"]?.toString()?.takeIf {
                    runPayload["success"] != true && it.isNotBlank()
                },
            ).filterValues { it != null }
        }
        val failedStepIndex = runPayload["failed_step_index"]
        val resumeFromStepResult = runPayload["resume_from_step"]
        val currentStepIndex = runPayload["current_step_index"]
            ?: failedStepIndex
            ?: stepResults.lastOrNull()?.let { mapArg(it)["step_index"] }
        val currentStepNumber = runPayload["current_step_number"]
            ?: when (currentStepIndex) {
                is Number -> currentStepIndex.toInt().plus(1)
                is String -> currentStepIndex.trim().toIntOrNull()?.plus(1)
                else -> null
            }
        return linkedMapOf<String, Any?>(
            "success" to (runPayload["success"] == true),
            "status" to if (runPayload["success"] == true) "succeeded" else "failed",
            "run_id" to runPayload["run_id"],
            "audit_run_id" to runPayload["audit_run_id"],
            "function_id" to functionId,
            "runner" to runPayload["runner"],
            "step_count" to stepCount,
            "active_step_count" to runPayload["active_step_count"],
            "success_step_count" to successStepCount,
            "completed_step_count" to (runPayload["completed_step_count"] ?: successStepCount),
            "actions_executed" to successStepCount,
            "execution_mode" to executionMode,
            "step_results" to stepResults,
            "started_at_ms" to startedAtMs.takeIf { it > 0L },
            "finished_at_ms" to finishedAtMs.takeIf { it > 0L },
            "duration_ms" to durationMs,
            "runner_duration_ms" to durationMs,
            "timing" to timing,
            "execution_summary" to executionSummary,
            "failed_step_index" to failedStepIndex,
            "resume_from_step" to resumeFromStepResult,
            "current_step_index" to currentStepIndex,
            "current_step_number" to currentStepNumber,
            "error_code" to runPayload["error_code"],
            "error_message" to runPayload["error_message"],
            "missing_required_arguments" to runPayload["missing_required_arguments"],
        ).filterValues { it != null }
    }

    private fun normalizeIncompleteReplay(payload: Map<String, Any?>): Map<String, Any?> {
        if (payload["success"] != true) return payload
        val stepResults = listArg(payload["step_results"])
        val stepCount = intArg(payload["step_count"], defaultValue = stepResults.size)
        val activeStepCount = intArg(payload["active_step_count"], defaultValue = stepCount)
        if (activeStepCount <= 0) return payload
        val successStepCount = intArg(
            payload["success_step_count"],
            defaultValue = stepResults.count { raw -> mapArg(raw)["success"] != false },
        )
        val resumeFromStep = intArg(payload["resume_from_step"], defaultValue = 0).coerceAtLeast(0)
        val completedStepCount = intArg(
            payload["completed_step_count"],
            defaultValue = (resumeFromStep + successStepCount).coerceAtMost(stepCount),
        )
        val completedAllActiveSteps = successStepCount >= activeStepCount &&
            completedStepCount >= (resumeFromStep + activeStepCount).coerceAtMost(stepCount)
        if (completedAllActiveSteps) return payload
        return linkedMapOf<String, Any?>().apply {
            putAll(payload)
            put("success", false)
            put("error_code", "OOB_FUNCTION_INCOMPLETE")
            put(
                "error_message",
                "Function replay finished before all active steps completed: " +
                    "$successStepCount/$activeStepCount"
            )
            put("incomplete_replay_normalized", true)
        }
    }

    suspend fun runFunction(
        functionId: String,
        arguments: Map<String, Any?>,
        resumeFromStep: Int = 0,
        frontendRunId: String = "",
        frontendTaskId: String = "",
        frontendParent: String = "",
        functionSpec: Map<String, Any?>? = null,
        preparedSpec: Map<String, Any?>? = null,
        callback: cn.com.omnimind.bot.agent.AgentCallback? = null,
        toolHandle: cn.com.omnimind.bot.agent.AgentToolExecutionHandle? = null,
        env: cn.com.omnimind.bot.agent.AgentExecutionEnvironment? = null,
        parentToolCallId: String? = null,
        toolName: String = functionId,
        callStack: List<String> = emptyList(),
    ): Map<String, Any?> = withContext(Dispatchers.Default) {
        val startupTiming = FunctionTiming(
            source = "oob_function_execute",
            requiredPhases = EXECUTION_PHASES,
        )
        val spec = startupTiming.measure("load_function_ms") {
            functionSpec ?: getSpec(functionId)
        }
            ?: return@withContext errorPayload(
                code = "OOB_FUNCTION_NOT_FOUND",
                message = "Function not found: $functionId",
                functionId = functionId
            ).let { attachExecutionTiming(it, startupTiming) }
        val materialization = if (preparedSpec == null) {
            startupTiming.measureSuspend("bind_function_args_ms") {
                OmniFlowPythonRuntime.materializeFunction(context, spec, arguments)
            }
        } else {
            null
        }
        val missing = listArg(materialization?.get("missing_arguments"))
            .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
        if (materialization?.get("success") == false) {
            val error = materialization["error"]?.toString().orEmpty()
            return@withContext errorPayload(
                code = if (missing.isNotEmpty()) {
                    "OOB_FUNCTION_ARGUMENTS_MISSING"
                } else {
                    "OOB_FUNCTION_MATERIALIZE_FAILED"
                },
                message = if (missing.isNotEmpty()) {
                    "Missing required arguments: ${missing.joinToString(", ")}"
                } else {
                    error.ifBlank { "OmniFlow could not materialize Function arguments" }
                },
                functionId = functionId
            ).let { attachExecutionTiming(it + linkedMapOf("missing_required_arguments" to missing), startupTiming) }
        }
        val specForRun = preparedSpec ?: mapArg(materialization?.get("function"))
        if (specForRun.isEmpty()) {
            return@withContext errorPayload(
                code = "OOB_FUNCTION_MATERIALIZE_FAILED",
                message = "OmniFlow returned an empty materialized Function",
                functionId = functionId,
            ).let { attachExecutionTiming(it, startupTiming) }
        }
        startupTiming.measure("bound_step_count_ms") {
            FunctionSchema.materializedSteps(specForRun).size
        }
        val payload = runCatching {
            startupTiming.measureSuspend("run_function_steps_ms") {
        val runStartedAtMs = System.currentTimeMillis()
        val timing = runResultBuilder.timing(runStartedAtMs)
        val normalizedFunctionId = functionId.trim()
        val auditRunId = nextRunId(runStartedAtMs)
        if (normalizedFunctionId.isNotEmpty() && normalizedFunctionId in callStack) {
            return@measureSuspend runResultBuilder.failedRun(
                functionId = functionId,
                spec = spec,
                auditRunId = auditRunId,
                startedAtMs = runStartedAtMs,
                errorCode = "OOB_FUNCTION_RECURSION",
                errorMessage = "Recursive OOB function call detected: " +
                    (callStack + normalizedFunctionId).joinToString(" -> ")
            )
        }
        if (callStack.size >= MAX_FUNCTION_CALL_DEPTH) {
            return@measureSuspend runResultBuilder.failedRun(
                functionId = functionId,
                spec = spec,
                auditRunId = auditRunId,
                startedAtMs = runStartedAtMs,
                errorCode = "OOB_FUNCTION_MAX_DEPTH",
                errorMessage = "Function call depth exceeds $MAX_FUNCTION_CALL_DEPTH"
            )
        }
        val activeCallStack = if (normalizedFunctionId.isNotEmpty()) {
            callStack + normalizedFunctionId
        } else {
            callStack
        }
        val steps = timing.measure("bound_steps_ms") { boundSteps(specForRun) }
        val normalizedResumeFromStep = resumeFromStep.coerceIn(0, steps.size)
        val activeSteps = steps.drop(normalizedResumeFromStep)

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
            failureReason = failureReason,
        )
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
        val checkerRules = checkerRulesForSpec(spec)

        val stepLoopStartedAt = System.nanoTime()
        timing.recordSinceStart("pre_step_loop_ms", stepLoopStartedAt)
        for ((relativeIndex, step) in activeSteps.withIndex()) {
            val stepStartedAtMs = System.currentTimeMillis()
            frontendSession?.throwIfStopRequested()
            toolHandle?.throwIfStopRequested()
            val index = normalizedResumeFromStep + relativeIndex
            val stepIndex = index + 1
            val stepId = "step_$stepIndex"
            val callableTool = FunctionSchema.actionTool(step)
            val stepTitle = callableTool.ifBlank { stepId }
            val functionExecutionTool = functionExecutionToolForStep(step, callableTool)
            val action = FunctionSchema.action(step)
            val executor = when {
                ReplayHelper.isUIStep(action) ||
                    FunctionSchema.isFunctionCallTool(functionExecutionTool) -> FunctionSchema.EXECUTOR_FUNCTION
                else -> FunctionSchema.EXECUTOR_TOOL
            }
            currentStepIndex = index
            currentStepId = stepId
            currentStepTool = callableTool
            currentStepExecutor = executor
            currentStepStartedAtMs = stepStartedAtMs
            frontendSession?.update("第 $stepIndex/${steps.size} 步 $stepTitle")
            if (isSkippedStep(step, callableTool)) {
                val skippedArgs = ReplayHelper.normalizeArgsMap(argsForStep(action))
                stepResults += linkedMapOf<String, Any?>(
                    "step_id" to stepId,
                    "step_index" to index,
                    "tool" to callableTool.ifEmpty { functionExecutionTool },
                    "executor" to FunctionSchema.EXECUTOR_FUNCTION,
                    "skipped" to true,
                    "success" to true,
                    "summary" to "Skipped observation-only replay step",
                    "started_at_ms" to stepStartedAtMs,
                    "finished_at_ms" to stepStartedAtMs,
                    "duration_ms" to 0L
                ).apply {
                    putAll(replayStepEvidence(step, skippedArgs))
                }
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
                FunctionSchema.isFunctionCallTool(functionExecutionTool) -> {
                    executeFunctionToolCallStep(
                        step = step,
                        stepId = stepId,
                        stepTitle = stepTitle,
                        callableTool = functionExecutionTool,
                        callback = callback,
                        toolHandle = toolHandle,
                        env = env,
                        parentToolCallId = parentToolCallId,
                        toolName = toolName,
                        callStack = activeCallStack,
                        frontendParent = frontendParent,
                    )
                }

                FunctionSchema.isBrowserReplayTool(callableTool) -> {
                    executeBrowserUseStep(
                        step = step,
                        stepId = stepId,
                        stepTitle = stepTitle,
                        env = env,
                    )
                }

                ReplayHelper.isUIStep(action) -> {
                    val actionName = ReplayHelper.actionNameForStep(action)
                    val normalizedArgs = ReplayHelper.normalizeArgsMap(argsForStep(action))
                    val evidence = replayStepEvidence(step, normalizedArgs)
                    try {
                        val result = omniFlowReplayAdapter.controlAct(
                            functionId = functionId,
                            sourceStateId = firstNonBlank(step["source_state_id"]),
                            action = actionName,
                            args = normalizedArgs,
                            rules = checkerRules,
                            stopRequested = replayStopRequested,
                        )
                        val runtimeEvidence = evidence + replayStateEvidence(result.beforeState, result.afterState)
                        if (result.success) {
                            linkedMapOf<String, Any?>(
                                "step_id" to stepId,
                                "tool" to actionName,
                                "executor" to FunctionSchema.EXECUTOR_FUNCTION,
                                "model_free" to true,
                                "success" to true,
                                "summary" to stepTitle.takeIf { it.isNotBlank() }.orEmpty(),
                                "diagnostics" to result.diagnostics.takeIf { it.isNotEmpty() },
                            ).apply {
                                putAll(runtimeEvidence)
                            }.filterValues { it != null }
                        } else {
                            val transfer = transferResult(result.diagnostics)
                            runResultBuilder.failureStep(
                                stepId = stepId,
                                tool = actionName,
                                executor = FunctionSchema.EXECUTOR_FUNCTION,
                                summary = result.message,
                                errorCode = result.diagnostics["local_action_error_code"]
                                    ?.takeIf { it.isNotBlank() }
                                    ?: "OOB_FUNCTION_ACTION_FAILED",
                                extras = linkedMapOf<String, Any?>().apply {
                                    putAll(runtimeEvidence)
                                    put(
                                        "diagnostics",
                                        result.diagnostics
                                            .minus("transfer")
                                            .takeIf { it.isNotEmpty() },
                                    )
                                    put("transfer", transfer.takeIf { it.isNotEmpty() })
                                },
                            )
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val executionError = e as? ReplayHelper.ExecutionException
                        val failReason = e.message ?: "Function step failed"
                        val transfer = transferResult(executionError?.diagnostics.orEmpty())
                        runResultBuilder.failureStep(
                            stepId = stepId,
                            tool = actionName,
                            executor = FunctionSchema.EXECUTOR_FUNCTION,
                            summary = failReason,
                            errorCode = executionError?.errorCode ?: "OOB_FUNCTION_STEP_FAILED",
                            extras = linkedMapOf<String, Any?>().apply {
                                putAll(evidence)
                                put(
                                    "diagnostics",
                                    executionError?.diagnostics
                                        ?.minus("transfer")
                                        ?.takeIf { it.isNotEmpty() },
                                )
                                put("transfer", transfer.takeIf { it.isNotEmpty() })
                            },
                        )
                    }
                }

                else -> handleUnclassifiedStep(
                    step, stepId, stepTitle, executor, callableTool,
                )
            }
            frontendSession?.throwIfStopRequested()
            toolHandle?.throwIfStopRequested()
            if (timedReplayStepShouldSettle(stepResult)) {
                delay(REPLAY_UI_STEP_SETTLE_DELAY_MS)
                frontendSession?.throwIfStopRequested()
                toolHandle?.throwIfStopRequested()
            }
            val stepFinishedAtMs = System.currentTimeMillis()
            val timedStepResult = LinkedHashMap<String, Any?>().apply {
                putAll(stepResult)
                putIfAbsent("step_index", index)
                putIfAbsent("started_at_ms", stepStartedAtMs)
                putIfAbsent("finished_at_ms", stepFinishedAtMs)
                putIfAbsent("duration_ms", (stepFinishedAtMs - stepStartedAtMs).coerceAtLeast(0))
            }
            stepResults += timedStepResult
            currentStepIndex = -1
            if (timedStepResult["success"] == false) {
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
        timing.recordElapsed("result_build_ms", resultBuildStartedAt)
        val runFinishedAtMs = System.currentTimeMillis()
        resultPayload["timing"] = timing.finish(runFinishedAtMs)
        runResultBuilder.withExecutionSummary(resultPayload)
        val allSuccess = resultPayload["success"] == true
        frontendFinishMessage = helper.localized(if (allSuccess) "任务已完成" else "任务执行失败")
        frontendCloseAfterMs = if (allSuccess) {
            FRONTEND_SUCCESS_POPUP_VISIBLE_MS
        } else {
            FRONTEND_TERMINAL_POPUP_VISIBLE_MS
        }
        frontendSession?.finish(frontendFinishMessage, closeAfterMs = frontendCloseAfterMs)
        frontendFinished = true
        return@measureSuspend resultPayload
        } catch (e: ManualToolStopCancellationException) {
            if (isUserCompletedReplay()) {
                frontendFinishMessage = helper.localized("任务已完成")
                frontendCloseAfterMs = FRONTEND_SUCCESS_POPUP_VISIBLE_MS
                val resultPayload = buildUserCompletedResult()
                resultPayload["timing"] = timing.finish()
                runResultBuilder.withExecutionSummary(resultPayload)
                frontendSession?.finish(frontendFinishMessage, closeAfterMs = frontendCloseAfterMs)
                frontendFinished = true
                return@measureSuspend resultPayload
            }
            frontendFinishMessage = helper.localized("任务已停止")
            frontendCloseAfterMs = FRONTEND_TERMINAL_POPUP_VISIBLE_MS
            if (currentStepIndex >= 0 && stepResults.none { it["step_index"] == currentStepIndex }) {
                val stoppedAtMs = System.currentTimeMillis()
                stepResults += LinkedHashMap<String, Any?>().apply {
                    putAll(
                        runResultBuilder.failureStep(
                            stepId = currentStepId.ifBlank { "step_${currentStepIndex + 1}" },
                            tool = currentStepTool.ifBlank { "?" },
                            executor = currentStepExecutor.ifBlank { FunctionSchema.EXECUTOR_FUNCTION },
                            summary = frontendFinishMessage,
                            errorCode = "OOB_FUNCTION_STOPPED",
                        )
                    )
                    put("step_index", currentStepIndex)
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
            runResultBuilder.withExecutionSummary(resultPayload)
            frontendSession?.finish(frontendFinishMessage, closeAfterMs = frontendCloseAfterMs)
            frontendFinished = true
            return@measureSuspend resultPayload
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (isUserCompletedReplay()) {
                frontendFinishMessage = helper.localized("任务已完成")
                frontendCloseAfterMs = FRONTEND_SUCCESS_POPUP_VISIBLE_MS
                val resultPayload = buildUserCompletedResult()
                resultPayload["timing"] = timing.finish()
                runResultBuilder.withExecutionSummary(resultPayload)
                frontendSession?.finish(frontendFinishMessage, closeAfterMs = frontendCloseAfterMs)
                frontendFinished = true
                return@measureSuspend resultPayload
            }
            if (frontendSession?.isStopRequested() == true || toolHandle?.isManualStopRequested() == true) {
                frontendFinishMessage = helper.localized("任务已停止")
                frontendCloseAfterMs = FRONTEND_TERMINAL_POPUP_VISIBLE_MS
                if (currentStepIndex >= 0 && stepResults.none { it["step_index"] == currentStepIndex }) {
                    val stoppedAtMs = System.currentTimeMillis()
                    stepResults += LinkedHashMap<String, Any?>().apply {
                        putAll(
                            runResultBuilder.failureStep(
                                stepId = currentStepId.ifBlank { "step_${currentStepIndex + 1}" },
                                tool = currentStepTool.ifBlank { "?" },
                                executor = currentStepExecutor.ifBlank { FunctionSchema.EXECUTOR_FUNCTION },
                                summary = frontendFinishMessage,
                                errorCode = "OOB_FUNCTION_STOPPED",
                            )
                        )
                        put("step_index", currentStepIndex)
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
                runResultBuilder.withExecutionSummary(resultPayload)
                frontendSession?.finish(frontendFinishMessage, closeAfterMs = frontendCloseAfterMs)
                frontendFinished = true
                return@measureSuspend resultPayload
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
        }.getOrElse { error ->
            errorPayload(
                code = "OOB_CALL_TOOL_FAILED",
                message = error.message.orEmpty(),
                functionId = functionId
            )
        }
        attachExecutionTiming(payload, startupTiming)
    }

    private suspend fun handleUnclassifiedStep(
        step: Map<String, Any?>,
        stepId: String,
        stepTitle: String,
        executor: String,
        callableTool: String,
    ): Map<String, Any?> {
        return runResultBuilder.failureStep(
            stepId = stepId,
            tool = callableTool.ifEmpty { "?" },
            executor = executor.ifEmpty { FunctionSchema.EXECUTOR_AGENT },
            summary = "No local handler for replay step: $stepTitle",
            errorCode = "OOB_FUNCTION_STEP_UNSUPPORTED",
        )
    }

    private suspend fun executeBrowserUseStep(
        step: Map<String, Any?>,
        stepId: String,
        stepTitle: String,
        env: cn.com.omnimind.bot.agent.AgentExecutionEnvironment?,
    ): Map<String, Any?> {
        val args = resolveStepArgs(step).toMutableMap()
        if (firstNonBlank(args["tool_title"]).isBlank()) {
            args["tool_title"] = stepTitle.ifBlank { "浏览器操作" }
        }
        return try {
            val requestJson = AgentToolJson.mapToJsonElement(args) as JsonObject
            val request = BrowserUseRequest.fromJson(requestJson)
            val agentRunId = env?.agentRunId ?: "function_replay_${System.currentTimeMillis()}"
            val workspace = env?.workspaceDescriptor
                ?: AgentWorkspaceManager(context).buildWorkspaceDescriptor(
                    conversationId = null,
                    agentRunId = agentRunId,
                )
            val engine = LiveAgentBrowserSessionManager.acquireEngine(
                context = context,
                workspaceManager = AgentWorkspaceManager(context),
                agentRunId = agentRunId,
                workspace = workspace,
            )
            val outcome = engine.execute(request)
            linkedMapOf<String, Any?>(
                "step_id" to stepId,
                "tool" to AgentToolNames.BROWSER_USE,
                "executor" to FunctionSchema.EXECUTOR_TOOL,
                "model_free" to true,
                "success" to true,
                "summary" to outcome.summaryText,
                "payload" to outcome.payload,
                "artifacts" to outcome.artifacts.map { it.toPayload() }.takeIf { it.isNotEmpty() },
                "actions" to outcome.actions.map { it.toPayload() }.takeIf { it.isNotEmpty() },
                "image_data_url" to outcome.imageDataUrl,
            ).filterValues { it != null }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            runResultBuilder.failureStep(
                stepId = stepId,
                tool = AgentToolNames.BROWSER_USE,
                executor = FunctionSchema.EXECUTOR_TOOL,
                summary = e.message ?: "browser_use step failed",
                errorCode = "OOB_BROWSER_USE_STEP_FAILED",
            )
        }
    }

    private suspend fun executeFunctionToolCallStep(
        step: Map<String, Any?>,
        stepId: String,
        stepTitle: String,
        callableTool: String,
        callback: cn.com.omnimind.bot.agent.AgentCallback?,
        toolHandle: cn.com.omnimind.bot.agent.AgentToolExecutionHandle?,
        env: cn.com.omnimind.bot.agent.AgentExecutionEnvironment?,
        parentToolCallId: String?,
        toolName: String,
        callStack: List<String>,
        frontendParent: String = "",
    ): Map<String, Any?> {
        val args = resolveStepArgs(step)
        val functionId = firstNonBlank(args["function_id"])
        if (functionId.isEmpty()) return runResultBuilder.failureStep(
            stepId = stepId, tool = callableTool.ifEmpty { OobActionSchema.TOOL_CALL_TOOL },
            executor = FunctionSchema.EXECUTOR_TOOL,
            summary = "$stepTitle missing function_id", errorCode = "OOB_FUNCTION_ID_MISSING",
        )
        return executeFunctionStepCall(
            step = step, stepId = stepId, stepTitle = stepTitle,
            callableTool = callableTool.ifEmpty { OobActionSchema.TOOL_CALL_TOOL },
            callback = callback, toolHandle = toolHandle, env = env,
            parentToolCallId = parentToolCallId, toolName = toolName,
            callStack = callStack,
            frontendParent = frontendParent,
        )
    }

    private suspend fun executeFunctionStepCall(
        step: Map<String, Any?>,
        stepId: String,
        stepTitle: String,
        callableTool: String,
        callback: cn.com.omnimind.bot.agent.AgentCallback?,
        toolHandle: cn.com.omnimind.bot.agent.AgentToolExecutionHandle?,
        env: cn.com.omnimind.bot.agent.AgentExecutionEnvironment?,
        parentToolCallId: String?,
        toolName: String,
        callStack: List<String>,
        frontendParent: String = "",
    ): Map<String, Any?> {
        val args = resolveStepArgs(step)
        val functionId = firstNonBlank(args["function_id"])
        val functionArguments = mapArg(args["arguments"])

        suspend fun emitStarted() {
        }
        suspend fun completeWithStep(result: Map<String, Any?>): Map<String, Any?> {
            return result
        }
        fun failStep(errorCode: String, summary: String, extras: Map<String, Any?> = emptyMap()) =
            runResultBuilder.failureStep(stepId = stepId, tool = callableTool.ifEmpty { OobActionSchema.TOOL_CALL_TOOL },
                executor = FunctionSchema.EXECUTOR_FUNCTION, summary = summary, errorCode = errorCode, extras = extras)

        emitStarted()
        if (functionId.isEmpty()) return completeWithStep(failStep("OOB_FUNCTION_ID_MISSING", "$stepTitle missing function_id"))
        val calledFunctionSpec = getSpec(functionId)
            ?: return completeWithStep(failStep("OOB_FUNCTION_NOT_FOUND", "Function not found: $functionId",
                mapOf("called_function_id" to functionId)))
        val materialization = OmniFlowPythonRuntime.materializeFunction(
            context,
            calledFunctionSpec,
            functionArguments,
        )
        val missing = listArg(materialization["missing_arguments"])
            .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
        if (materialization["success"] != true) {
            val error = materialization["error"]?.toString().orEmpty()
            return completeWithStep(failStep(
                if (missing.isNotEmpty()) "OOB_FUNCTION_ARGUMENTS_MISSING" else "OOB_FUNCTION_MATERIALIZE_FAILED",
                if (missing.isNotEmpty()) {
                    "Missing required arguments: ${missing.joinToString(", ")}"
                } else {
                    error.ifBlank { "OmniFlow could not materialize Function arguments" }
                },
                mapOf("called_function_id" to functionId, "missing_required_arguments" to missing),
            ))
        }
        val boundSpec = mapArg(materialization["function"])
        if (boundSpec.isEmpty()) {
            return completeWithStep(failStep(
                "OOB_FUNCTION_MATERIALIZE_FAILED",
                "OmniFlow returned an empty materialized Function",
                mapOf("called_function_id" to functionId),
            ))
        }
        val calledFunctionRun = runFunction(
            functionId = functionId,
            arguments = functionArguments,
            functionSpec = calledFunctionSpec,
            preparedSpec = boundSpec,
            callback = callback, toolHandle = toolHandle, env = env,
            parentToolCallId = "${parentToolCallId ?: toolName}_$stepId",
            toolName = functionId,
            callStack = callStack,
            frontendParent = frontendParent,
        )
        val success = calledFunctionRun["success"] == true
        val calledFunctionModelRequired = calledFunctionRun["model_required"] == true
        return completeWithStep(linkedMapOf<String, Any?>(
            "step_id" to stepId, "tool" to callableTool.ifEmpty { OobActionSchema.TOOL_CALL_TOOL },
            "executor" to FunctionSchema.EXECUTOR_FUNCTION, "model_free" to true, "success" to success,
            "model_required" to calledFunctionModelRequired.takeIf { it },
            "called_function_id" to functionId,
            "called_function_run_id" to calledFunctionRun["run_id"],
            "called_function_runner" to calledFunctionRun["runner"],
            "called_function_step_count" to calledFunctionRun["step_count"],
            "called_function_success_step_count" to calledFunctionRun["success_step_count"],
            "called_function_model_required" to calledFunctionModelRequired,
            "called_function_failed_step_index" to calledFunctionRun["failed_step_index"],
            "called_function_resume_from_step" to calledFunctionRun["resume_from_step"],
            "step_results" to calledFunctionRun["step_results"],
            "timing" to calledFunctionRun["timing"],
            "error_code" to calledFunctionRun["error_code"],
            "summary" to if (success) "复用指令执行完成：$functionId"
                else calledFunctionRun["error_message"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: "复用指令执行失败：$functionId",
        ).filterValues { it != null })
    }

    private fun checkerRulesForSpec(spec: Map<String, Any?>): List<Map<String, Any?>> =
        listArg(spec["checker_rules"])
            .mapNotNull { mapArg(it).takeIf(Map<String, Any?>::isNotEmpty) }

    private fun boundSteps(boundSpec: Map<String, Any?>): List<Map<String, Any?>> =
        FunctionSchema.materializedSteps(boundSpec)

    private fun attachExecutionTiming(
        payload: Map<String, Any?>,
        timing: FunctionTiming,
    ): Map<String, Any?> {
        val startupTiming = timing.finish()
        val startupPhaseMs = mapArg(startupTiming["phase_ms"])
        val existingTiming = mapArg(payload["timing"])
        val runnerPhaseMs = mapArg(existingTiming["phase_ms"])
        val mergedTiming = linkedMapOf<String, Any?>().apply {
            putAll(existingTiming)
            put("source", "oob_function_execute")
            put("started_at_ms", startupTiming["started_at_ms"])
            put("finished_at_ms", startupTiming["finished_at_ms"])
            put("duration_ms", startupTiming["duration_ms"])
            put("phase_ms", startupPhaseMs)
            if (runnerPhaseMs.isNotEmpty()) put("runner_phase_ms", runnerPhaseMs)
            put("startup_phase_ms", startupPhaseMs)
            put("startup_duration_ms", startupPhaseMs.values.sumOf { intArg(it, defaultValue = 0).toLong() })
        }
        return linkedMapOf<String, Any?>().apply {
            putAll(payload)
            put("timing", mergedTiming)
        }.let { runResultBuilder.withExecutionSummary(it) }
    }

    private fun attachCallTiming(
        payload: Map<String, Any?>,
        timing: FunctionTiming,
    ): Map<String, Any?> {
        val callTiming = timing.finish()
        val mergedTiming = linkedMapOf<String, Any?>().apply {
            putAll(mapArg(payload["timing"]))
            put("call_started_at_ms", callTiming["started_at_ms"])
            put("call_finished_at_ms", callTiming["finished_at_ms"])
            put("call_duration_ms", callTiming["duration_ms"])
            put("call_phase_ms", callTiming["phase_ms"])
        }
        return linkedMapOf<String, Any?>().apply {
            putAll(payload)
            put("timing", mergedTiming)
        }
    }

    private fun errorPayload(
        code: String,
        message: String,
        functionId: String = "",
    ): Map<String, Any?> = linkedMapOf(
        "success" to false,
        "error_code" to code,
        "error_message" to message,
        "function_id" to functionId,
    )

    private fun replayStepEvidence(
        step: Map<String, Any?>,
        normalizedArgs: Map<String, Any?>,
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "source_state_id" to firstNonBlank(step["source_state_id"]).takeIf(String::isNotBlank),
        "args" to normalizedArgs.takeIf { it.isNotEmpty() },
    ).filterValues { it != null }

    private fun replayStateEvidence(
        before: State?,
        after: State?,
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "before_state" to before?.toRunLogMap(),
        "after_state" to after?.toRunLogMap(),
    ).filterValues { it != null }

    private fun State.toRunLogMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "state_id" to stateId,
        "xml" to xml,
        "package_name" to packageName,
        "activity_name" to activityName,
        "display" to display?.let { linkedMapOf("width" to it.width, "height" to it.height) },
    ).filterValues { it != null }

    private fun transferResult(diagnostics: Map<String, Any?>): Map<String, Any?> {
        val raw = firstNonBlank(diagnostics["transfer"])
        if (raw.isEmpty()) return emptyMap()
        return runCatching {
            AgentToolJson.jsonObjectToMap(Json.parseToJsonElement(raw).jsonObject)
        }.getOrDefault(emptyMap())
    }

    private fun resolveStepArgs(step: Map<String, Any?>): Map<String, Any?> {
        return FunctionSchema.actionArgs(step)
    }

    private fun functionExecutionToolForStep(step: Map<String, Any?>, callableTool: String): String {
        return callableTool.takeIf(FunctionSchema::isFunctionCallTool).orEmpty()
    }

    private fun isSkippedStep(step: Map<String, Any?>, callableTool: String = FunctionSchema.actionTool(step)): Boolean {
        return callableTool.isNotBlank() && FunctionSchema.shouldSkipCapturedTool(callableTool)
    }

    private fun isFunctionExecutionStep(step: Map<String, Any?>): Boolean {
        val tool = functionExecutionToolForStep(step, FunctionSchema.actionTool(step))
        return when {
            FunctionSchema.isFunctionCallTool(tool) -> {
                val args = resolveStepArgs(step)
                firstNonBlank(args["function_id"]).isNotEmpty()
            }
            else -> false
        }
    }

    companion object {
        const val FUNCTION_DIRECT_RUNNER = "oob_function_direct_runner"
        const val FUNCTION_RUN_SOURCE = "oob_function_replay"
        private const val TAG = "FunctionRun"
        private const val MAX_FUNCTION_CALL_DEPTH = 8
        private const val REPLAY_UI_STEP_SETTLE_DELAY_MS = 1_000L
        private const val FRONTEND_SUCCESS_POPUP_VISIBLE_MS = 900L
        private const val FRONTEND_TERMINAL_POPUP_VISIBLE_MS = 2500L
        private val CALL_PHASES = listOf("execute_function_ms")
        private val EXECUTION_PHASES = listOf(
            "load_function_ms",
            "check_arguments_ms",
            "bind_function_args_ms",
            "bound_step_count_ms",
            "run_function_steps_ms",
        )
        private val RUN_SEQUENCE = AtomicLong(0)
    }

    private fun nextRunId(startedAtMs: Long): String =
        "function_run_${startedAtMs}_${RUN_SEQUENCE.incrementAndGet()}"
}
