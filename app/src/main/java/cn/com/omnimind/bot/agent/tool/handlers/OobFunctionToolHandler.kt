package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.assists.task.vlmserver.ActionExecutor
import cn.com.omnimind.assists.task.vlmserver.AndroidDeviceOperator
import cn.com.omnimind.assists.task.vlmserver.DeviceOperator
import cn.com.omnimind.assists.task.vlmserver.UIContextManager
import cn.com.omnimind.baselib.runlog.OobActionSchema
import cn.com.omnimind.bot.agent.AgentToolJson
import cn.com.omnimind.bot.agent.AgentToolNames
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.BrowserUseRequest
import cn.com.omnimind.bot.agent.LiveAgentBrowserSessionManager
import cn.com.omnimind.bot.agent.ManualToolStopCancellationException
import cn.com.omnimind.bot.omniflow.OobFunctionJson.firstNonBlank
import cn.com.omnimind.bot.omniflow.OobFunctionJson.boolArgOrDefault
import cn.com.omnimind.bot.omniflow.OobFunctionJson.intArg
import cn.com.omnimind.bot.omniflow.OobFunctionJson.listArg
import cn.com.omnimind.bot.omniflow.OobFunctionJson.longArg
import cn.com.omnimind.bot.omniflow.OobFunctionJson.mapArg
import cn.com.omnimind.baselib.runlog.OobReusableFunctionStore
import cn.com.omnimind.bot.omniflow.OobFunctionArgumentBindingValidator
import cn.com.omnimind.bot.omniflow.OobFunctionToolNames
import cn.com.omnimind.bot.runlog.OobFunctionCallTiming
import cn.com.omnimind.bot.runlog.OobFunctionRunLogRecorder
import cn.com.omnimind.bot.runlog.OmniflowCheckerRule
import cn.com.omnimind.bot.omniflow.OobFunctionSchemaBuilder
import cn.com.omnimind.bot.runlog.OobFunctionManagementService
import cn.com.omnimind.bot.runlog.ReplayHelper
import cn.com.omnimind.bot.runlog.RunLogReplayPolicy
import cn.com.omnimind.bot.runlog.argsForStep
import cn.com.omnimind.bot.runlog.resolveActionName
import cn.com.omnimind.bot.mcp.VlmTaskRequest
import cn.com.omnimind.bot.vlm.VlmToolCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

data class OobFunctionRuntimeResolveRequest(
    val functionId: String,
    val goal: String,
    val failedStepIndex: Int,
    val failedStep: Map<String, Any?>,
    val failedRunPayload: Map<String, Any?>,
    val model: String = "",
)

fun interface OobFunctionRuntimeResolvePlanner {
    suspend fun plan(
        context: android.content.Context,
        request: OobFunctionRuntimeResolveRequest,
    ): Map<String, Any?>
}

private fun OobFunctionRuntimeResolveRequest.oneStepGuidance(): String = buildString {
    appendLine("OmniFlow runtime resolve for one failed replay step.")
    appendLine("Return only one ordinary UI action for the current failed Function step. Do not call a Function, do not finish the whole task, and do not re-plan the task.")
    appendLine("Allowed actions: click, input_text, swipe, press_key(back/home), open_app, wait.")
    appendLine("Failed step index: $failedStepIndex")
    appendLine("Failed step summary:")
    appendLine(
        linkedMapOf<String, Any?>(
            "success" to failedStep["success"],
            "runner" to failedStep["runner"],
            "step_count" to failedStep["step_count"],
            "success_step_count" to failedStep["success_step_count"],
            "failed_step_index" to failedStep["failed_step_index"],
            "error_code" to failedStep["error_code"],
            "error_message" to failedStep["error_message"],
        ).filterValues { it != null }.toString()
    )
}

class OobFunctionToolHandler(
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
    private val actionExecutor: ActionExecutor = ActionExecutor(deviceOperator, UIContextManager()),
    private val frontendSessionController: OobFunctionFrontendSessionController =
        OobFunctionFrontendSessionController(helper),
    private val runtimeResolveContextController: OobFunctionRuntimeResolveContextController =
        OobFunctionRuntimeResolveContextController(deviceOperator),
    private val functionCallCardPresenter: OobFunctionCallCardPresenter =
        OobFunctionCallCardPresenter(helper),
    private val runResultBuilder: OobFunctionRunResultBuilder =
        OobFunctionRunResultBuilder(),
    private val runtimeResolvePlanner: OobFunctionRuntimeResolvePlanner = OobFunctionRuntimeResolvePlanner { appContext, request ->
        coroutineScope {
            VlmToolCoordinator.parseOnlyNextAction(
                context = appContext,
                request = VlmTaskRequest(
                    goal = request.goal,
                    model = request.model.takeIf { it.isNotBlank() },
                    maxSteps = 1,
                    skipGoHome = true,
                    stepSkillGuidance = request.oneStepGuidance(),
                    disableOmniFlowRecall = true,
                ),
                scope = this,
            ).toPayload()
        }
    },
) : ToolHandler {
    private val checkerRuleJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    override val toolNames: Set<String> = setOf(
        RunLogReplayPolicy.TOOL_CALL_TOOL,
    )

    /** Workspace-backed function store; injected by the OmniFlow function layer on init. */
    var workspaceFunctionStore: cn.com.omnimind.bot.omniflow.WorkspaceFunctionStore? =
        cn.com.omnimind.bot.omniflow.WorkspaceFunctionStore(
            AgentWorkspaceManager.rootDirectory(context)
        )

    override fun canHandle(toolName: String): Boolean =
        RunLogReplayPolicy.isOmniflowToolCallTool(toolName) ||
            toolName in OobFunctionToolNames.profileTools ||
            runCatching {
                cn.com.omnimind.baselib.runlog.OobReusableFunctionStore.get(context, toolName) != null
            }.getOrDefault(false) ||
            workspaceFunctionStore?.canHandle(toolName) == true

    /** Returns the function spec from workspace first so editable checker rules take effect. */
    private fun getSpec(functionId: String): Map<String, Any?>? =
        workspaceFunctionStore?.get(functionId)
            ?: runCatching {
            cn.com.omnimind.baselib.runlog.OobReusableFunctionStore.get(context, functionId)
        }.getOrNull()

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
            return cn.com.omnimind.bot.agent.ToolExecutionResult.Error(
                toolName,
                "call_tool is an internal replay step. Use oob_function_run for reusable Function execution, or vlm_task for ordinary phone UI automation."
            )
        }
        return if (getSpec(toolName) != null) {
            cn.com.omnimind.bot.agent.ToolExecutionResult.Error(
                toolName,
                "Direct Function tool execution is disabled. Use oob_function_run in the omniflow profile for explicit reusable-command execution, or vlm_task for ordinary phone UI automation."
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
            val argsMap = helper.jsonObjectToMap(args).filterKeys { it != "tool_title" }
            val payload = if (toolName == OobFunctionToolNames.FUNCTION_RUN) {
                runFunction(argsMap)
            } else {
                val managementService = OobFunctionManagementService(
                    context = context,
                    deviceOperator = deviceOperator,
                    workspaceFunctionStore = store,
                )
                managementService.executeTool(toolName, argsMap)
            }
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
                helper.localized(e.message ?: "OmniFlow Function tool failed")
            )
        }
    }

    private fun lifecycleProgress(toolName: String): String = when (toolName) {
        OobFunctionToolNames.FUNCTION_UPDATE -> "正在更新复用指令"
        OobFunctionToolNames.FUNCTION_REGISTER -> "正在注册复用指令"
        OobFunctionToolNames.FUNCTION_RUN -> "正在执行复用指令"
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
            OobFunctionToolNames.FUNCTION_RUN -> {
                if (payload["success"] == true) {
                    "复用指令执行完成。"
                } else {
                    "复用指令执行失败。"
                }
            }
            OobFunctionToolNames.RUN_LOG_CONVERT -> "RunLog 转换完成。"
            OobFunctionToolNames.FUNCTION_GET -> "已读取复用指令。"
            OobFunctionToolNames.FUNCTION_LIST -> "已读取复用指令列表。"
            OobFunctionToolNames.RUN_LOG_GET -> "已读取 RunLog。"
            OobFunctionToolNames.RUN_LOG_LIST -> "已读取 RunLog 列表。"
            else -> "复用指令工具调用完成。"
        }
    }

    private fun timedReplayStepShouldSettle(stepResult: Map<String, Any?>): Boolean =
        stepResult["success"] == true &&
            stepResult["executor"] == RunLogReplayPolicy.EXECUTOR_OMNIFLOW &&
            stepResult["model_free"] == true

    suspend fun runFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val callTiming = OobFunctionCallTiming()
        val request = args ?: emptyMap()
        val functionId = firstNonBlank(request["function_id"], request["functionId"])
        val runtimeResolveGoal = firstNonBlank(
            request["runtime_resolve_goal"],
            request["runtimeResolveGoal"],
            request["goal"],
            request["query"],
            request["task"],
        )
        val runtimeResolveEnabled = runtimeResolveGoal.isNotBlank() &&
            boolArgOrDefault(
                request["allow_runtime_resolve"] ?: request["allowRuntimeResolve"],
                defaultValue = true
            )
        val runtimeResolveBudget = intArg(
            request["runtime_resolve_budget"],
            request["runtimeResolveBudget"],
            defaultValue = if (runtimeResolveEnabled) 1 else 0,
        ).coerceAtLeast(0)
        val executionMode = firstNonBlank(request["execution_mode"])
            .ifBlank { "foreground" }

        var runPayload = callTiming.measureSuspend("execute_function_ms") {
            runFunction(
                functionId = functionId,
                arguments = mapArg(request["arguments"]),
                resumeFromStep = intArg(request["resume_from_step"], defaultValue = 0)
                    .coerceAtLeast(0),
                frontendRunId = firstNonBlank(request["frontend_run_id"], request["frontendRunId"]),
                frontendTaskId = firstNonBlank(request["frontend_task_id"], request["frontendTaskId"]),
                frontendParent = firstNonBlank(request["frontend_parent"], request["frontendParent"]),
                runtimeResolveGoal = runtimeResolveGoal,
                runtimeResolveBudget = runtimeResolveBudget,
                runtimeResolveModel = firstNonBlank(
                    request["runtime_resolve_model"],
                    request["runtimeResolveModel"],
                    request["model"],
                ),
            )
        }
        runPayload = normalizeIncompleteReplay(callTiming.attachTo(runPayload))
        OobReusableFunctionStore.recordRun(
            context = context,
            functionId = functionId,
            success = runPayload["success"] == true,
            runId = runPayload["run_id"]?.toString(),
            runner = runPayload["runner"]?.toString(),
            stepCount = intArg(runPayload["step_count"], defaultValue = 0),
            errorMessage = runPayload["error_message"]?.toString()
        )
        OobFunctionRunLogRecorder.record(
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
            ?: stepResults.lastOrNull()?.let { mapArg(it)["index"] }
        val currentStepNumber = runPayload["current_step_number"]
            ?: when (currentStepIndex) {
                is Number -> currentStepIndex.toInt().plus(1)
                is String -> currentStepIndex.trim().toIntOrNull()?.plus(1)
                else -> null
            }
        return linkedMapOf<String, Any?>(
            "success" to (runPayload["success"] == true),
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
            "runtime_resolve_session_id" to runPayload["runtime_resolve_session_id"],
            "failed_step_index" to failedStepIndex,
            "resume_from_step" to resumeFromStepResult,
            "current_step_index" to currentStepIndex,
            "current_step_number" to currentStepNumber,
            "runtime_resolve_attempt" to runPayload["runtime_resolve_attempt"],
            "runtime_resolve_unavailable_reason" to runPayload["runtime_resolve_unavailable_reason"],
            "error_code" to runPayload["error_code"],
            "error_message" to runPayload["error_message"],
            "missing_required_arguments" to runPayload["missing_required_arguments"],
            "result" to runPayload
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
        runtimeResolveSessionId: String = "",
        runtimeResolveAttempt: Int = 0,
        frontendRunId: String = "",
        frontendTaskId: String = "",
        frontendParent: String = "",
        runtimeResolveGoal: String = "",
        runtimeResolveBudget: Int = 0,
        runtimeResolveModel: String = "",
        functionSpec: Map<String, Any?>? = null,
        preparedSpec: Map<String, Any?>? = null,
        argumentsValidated: Boolean = false,
        callback: cn.com.omnimind.bot.agent.AgentCallback? = null,
        toolHandle: cn.com.omnimind.bot.agent.AgentToolExecutionHandle? = null,
        env: cn.com.omnimind.bot.agent.AgentExecutionEnvironment? = null,
        parentToolCallId: String? = null,
        toolName: String = functionId,
        callStack: List<String> = emptyList(),
    ): Map<String, Any?> = withContext(Dispatchers.Default) {
        val startupTiming = FunctionExecutionTiming()
        val spec = startupTiming.measure("load_function_spec_ms") {
            functionSpec ?: getSpec(functionId)
        }
            ?: return@withContext errorPayload(
                code = "OOB_FUNCTION_NOT_FOUND",
                message = "OmniFlow function not found: $functionId",
                functionId = functionId
            ).let { attachExecutionTiming(it, startupTiming) }
        val missing = startupTiming.measure("check_arguments_ms") {
            if (preparedSpec != null || argumentsValidated) {
                emptyList()
            } else {
                OobReusableFunctionStore.missingRequiredArguments(spec, arguments)
            }
        }
        if (missing.isNotEmpty()) {
            return@withContext errorPayload(
                code = "OOB_FUNCTION_ARGUMENTS_MISSING",
                message = "Missing required arguments: ${missing.joinToString(", ")}",
                functionId = functionId
            ).let { attachExecutionTiming(it + linkedMapOf("missing_required_arguments" to missing), startupTiming) }
        }
        val specForRun = startupTiming.measure("bind_function_args_ms") {
            preparedSpec ?: OobReusableFunctionStore.materialize(spec, arguments)
        }
        val runtimeResolveEnabled = runtimeResolveGoal.isNotBlank() && runtimeResolveBudget > 0
        startupTiming.measure("bound_step_count_ms") {
            OobFunctionSchemaBuilder.materializedSteps(specForRun).size
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
        if (callStack.size >= MAX_OMNIFLOW_CALL_DEPTH) {
            return@measureSuspend runResultBuilder.failedRun(
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
        val steps = timing.measure("bound_steps_ms") { boundSteps(specForRun) }
        val bindingValidation = timing.measure("argument_binding_validation_ms") {
            OobFunctionArgumentBindingValidator.validate(specForRun)
        }
        if (!bindingValidation.success) {
            return@measureSuspend runResultBuilder.withRunnerTiming(
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
            OobFunctionArgumentBindingValidator.argumentSourcesByStepIndex(specForRun)
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
            runtimeResolveSessionId = runtimeResolveSessionId,
            runtimeResolveAttempt = runtimeResolveAttempt,
            failureReason = failureReason,
        ).also {
            it.putAll(OobFunctionArgumentBindingValidator.runtimeDiagnostics(specForRun))
        }
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
        val checkerBudget = ReplayHelper.CheckerTriggerBudget()

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
                val skippedArgs = ReplayHelper.normalizeArgsMap(argsForStep(step))
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
                        callStack = activeCallStack,
                        frontendParent = frontendParent,
                    )
                }

                RunLogReplayPolicy.isBrowserReplayTool(callableTool) -> {
                    executeBrowserUseStep(
                        step = step,
                        stepId = stepId,
                        stepTitle = stepTitle,
                        env = env,
                    )
                }

                ReplayHelper.isUIStep(step) -> {
                    val action = ReplayHelper.actionNameForStep(step)
                    val normalizedArgs = ReplayHelper.normalizeArgsMap(argsForStep(step))
                    val evidence = replayStepEvidence(step, normalizedArgs)
                    try {
                        val result = actionExecutor.act(
                            action = action,
                            args = normalizedArgs,
                            source = "function_replay",
                            check = replayCheckConfig(
                                step = step,
                                checkerRules = checkerRules,
                                checkerBudget = checkerBudget,
                                stopRequested = replayStopRequested,
                            ),
                        )
                        if (!result.success) {
                            throw ReplayHelper.ExecutionException(
                                errorCode = result.diagnostics["local_action_error_code"]?.takeIf { it.isNotBlank() }
                                    ?: "OOB_OMNIFLOW_ACTION_FAILED",
                                message = result.message,
                                diagnostics = result.diagnostics,
                            )
                        }
                        linkedMapOf<String, Any?>(
                            "step_id" to stepId,
                            "tool" to action,
                            "executor" to RunLogReplayPolicy.EXECUTOR_OMNIFLOW,
                            "model_free" to true,
                            "success" to true,
                            "summary" to stepTitle.takeIf { it.isNotBlank() }.orEmpty(),
                            "diagnostics" to result.diagnostics.takeIf { it.isNotEmpty() },
                        ).apply {
                            putAll(evidence)
                        }.filterValues { it != null }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val executionError = e as? ReplayHelper.ExecutionException
                        val failReason = e.message ?: "omniflow step failed"
                        val recovery = runtimeResolveContextController.refetchCurrentPageForFailedStep(failReason)
                        runResultBuilder.failureStep(
                            stepId = stepId,
                            tool = action,
                            executor = RunLogReplayPolicy.EXECUTOR_OMNIFLOW,
                            summary = failReason,
                            errorCode = executionError?.errorCode ?: "OOB_OMNIFLOW_STEP_FAILED",
                            extras = linkedMapOf<String, Any?>().apply {
                                putAll(evidence)
                                put("diagnostics", executionError?.diagnostics?.takeIf { it.isNotEmpty() })
                                put("recovery", recovery)
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
                putIfAbsent("index", index)
                putIfAbsent("started_at_ms", stepStartedAtMs)
                putIfAbsent("finished_at_ms", stepFinishedAtMs)
                putIfAbsent("duration_ms", (stepFinishedAtMs - stepStartedAtMs).coerceAtLeast(0))
                if (ReplayHelper.actionNameForStep(step) == "input_text") {
                    argumentSourcesByStepIndex[index]?.let { source ->
                        put("argument_source", source["argument_source"])
                        put("argument_binding", source)
                    }
                }
            }
            stepResults += timedStepResult
            currentStepIndex = -1
            if (timedStepResult["success"] == false) {
                if (!timedStepResult.containsKey("recovery")) {
                    timedStepResult["recovery"] = runtimeResolveContextController.refetchCurrentPageForFailedStep(
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
        val resolvedPayload = if (runtimeResolveEnabled && payload.requiresRuntimeResolve()) {
            startupTiming.measureSuspend("runtime_resolve_ms") {
                runRuntimeResolveAndResume(
                    initialPayload = payload,
                    functionId = functionId,
                    spec = spec,
                    preparedSpec = specForRun,
                    runtimeResolveGoal = runtimeResolveGoal,
                    runtimeResolveBudget = runtimeResolveBudget,
                    runtimeResolveModel = runtimeResolveModel,
                    frontendRunId = frontendRunId,
                    frontendTaskId = frontendTaskId,
                    frontendParent = frontendParent,
                )
            }
        } else {
            payload
        }
        attachExecutionTiming(resolvedPayload, startupTiming)
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
            executor = executor.ifEmpty { RunLogReplayPolicy.EXECUTOR_AGENT },
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
        if (firstNonBlank(args["tool_title"], args["toolTitle"]).isBlank()) {
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
                "executor" to RunLogReplayPolicy.EXECUTOR_TOOL,
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
                executor = RunLogReplayPolicy.EXECUTOR_TOOL,
                summary = e.message ?: "browser_use step failed",
                errorCode = "OOB_BROWSER_USE_STEP_FAILED",
            )
        }
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
            summary = "$stepTitle recursive call_tool is not allowed", errorCode = "OOB_CALL_TOOL_RECURSION",
        )
        return runResultBuilder.failureStep(
            stepId = stepId, tool = targetTool, executor = RunLogReplayPolicy.EXECUTOR_TOOL,
            summary = "Replay call_tool only supports Function ids: $targetTool",
            errorCode = "OOB_CALL_TOOL_TARGET_UNSUPPORTED",
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
        callStack: List<String>,
        frontendParent: String = "",
    ): Map<String, Any?> {
        val args = resolveStepArgs(step)
        val functionId = firstNonBlank(args["function_id"], step["function_id"])
        val functionArguments = mapArg(args["arguments"])
        val cardToolName = RunLogReplayPolicy.TOOL_CALL_TOOL
        val cardId = functionCallCardPresenter.cardId(parentToolCallId, toolName, stepId)
        val cardStartedAtMs = System.currentTimeMillis()

        suspend fun emitStarted() {
            callback?.onToolCardEvent("tool_started", functionCallCardPresenter.payload(
                cardId = cardId, toolName = cardToolName, stepTitle = stepTitle,
                functionId = functionId, callableTool = callableTool,
                functionArguments = functionArguments, status = "running", success = null,
                summary = functionCallCardPresenter.runningSummary(functionId),
                progress = stepTitle, startedAtMs = cardStartedAtMs, finishedAtMs = null, result = null,
            ))
        }
        suspend fun completeWithCard(result: Map<String, Any?>): Map<String, Any?> {
            val success = result["success"] != false
            callback?.onToolCardEvent("tool_completed", functionCallCardPresenter.payload(
                cardId = cardId, toolName = cardToolName, stepTitle = stepTitle,
                functionId = functionId, callableTool = callableTool,
                functionArguments = functionArguments, status = if (success) "success" else "error",
                success = success, summary = result["summary"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: functionCallCardPresenter.finishedSummary(functionId, success),
                progress = "", startedAtMs = cardStartedAtMs, finishedAtMs = System.currentTimeMillis(), result = result,
            ))
            return result
        }
        fun failStep(errorCode: String, summary: String, extras: Map<String, Any?> = emptyMap()) =
            runResultBuilder.failureStep(stepId = stepId, tool = callableTool.ifEmpty { RunLogReplayPolicy.TOOL_CALL_TOOL },
                executor = "omniflow_function", summary = summary, errorCode = errorCode, extras = extras)

        emitStarted()
        if (functionId.isEmpty()) return completeWithCard(failStep("OOB_FUNCTION_ID_MISSING", "$stepTitle missing function_id"))
        val calledFunctionSpec = getSpec(functionId)
            ?: return completeWithCard(failStep("OOB_FUNCTION_NOT_FOUND", "OmniFlow function not found: $functionId",
                mapOf("called_function_id" to functionId)))
        val missing = OobReusableFunctionStore.missingRequiredArguments(calledFunctionSpec, functionArguments)
        if (missing.isNotEmpty()) return completeWithCard(failStep("OOB_FUNCTION_ARGUMENTS_MISSING",
            "Missing required arguments: ${missing.joinToString(", ")}",
            mapOf("called_function_id" to functionId, "missing_required_arguments" to missing)))
        val boundSpec = OobReusableFunctionStore.materialize(calledFunctionSpec, functionArguments)
        val calledFunctionRun = runFunction(
            functionId = functionId,
            arguments = functionArguments,
            functionSpec = calledFunctionSpec,
            preparedSpec = boundSpec,
            argumentsValidated = true,
            callback = callback, toolHandle = toolHandle, env = env,
            parentToolCallId = "${parentToolCallId ?: toolName}_$stepId",
            toolName = functionId,
            callStack = callStack,
            frontendParent = frontendParent,
        )
        val success = calledFunctionRun["success"] == true
        val calledFunctionStepResults = listArg(calledFunctionRun["step_results"])
            .mapNotNull { mapArg(it).takeIf { mapped -> mapped.isNotEmpty() } }
        val calledFunctionRuntimeResolveRequired =
            calledFunctionRun["runtime_resolve_required"] == true ||
                calledFunctionRun["runtime_resolve_context"] != null ||
                calledFunctionStepResults.any {
                    it["runtime_resolve_required"] == true
                }
        val calledFunctionRuntimeResolveAvailable =
            calledFunctionRun["runtime_resolve_available"] == true ||
                calledFunctionStepResults.any {
                    it["runtime_resolve_required"] == true && it["runtime_resolve_available"] == true
                }
        val calledFunctionModelRequired =
            calledFunctionRun["model_required"] == true && !calledFunctionRuntimeResolveRequired
        return completeWithCard(linkedMapOf<String, Any?>(
            "step_id" to stepId, "tool" to callableTool.ifEmpty { RunLogReplayPolicy.TOOL_CALL_TOOL },
            "executor" to "omniflow_function", "model_free" to true, "success" to success,
            "model_required" to calledFunctionModelRequired.takeIf { it },
            "runtime_resolve_required" to calledFunctionRuntimeResolveRequired.takeIf { it },
            "runtime_resolve_available" to calledFunctionRuntimeResolveAvailable.takeIf {
                calledFunctionRuntimeResolveRequired
            },
            "called_function_id" to functionId,
            "called_function_run_id" to calledFunctionRun["run_id"],
            "called_function_runner" to calledFunctionRun["runner"],
            "called_function_step_count" to calledFunctionRun["step_count"],
            "called_function_success_step_count" to calledFunctionRun["success_step_count"],
            "called_function_model_required" to calledFunctionModelRequired,
            "called_function_runtime_resolve_required" to calledFunctionRuntimeResolveRequired,
            "called_function_runtime_resolve_available" to calledFunctionRuntimeResolveAvailable,
            "called_function_failed_step_index" to calledFunctionRun["failed_step_index"],
            "called_function_resume_from_step" to calledFunctionRun["resume_from_step"],
            "called_function_runtime_resolve_context" to calledFunctionRun["runtime_resolve_context"],
            "called_function_agent_prompt" to calledFunctionRun["agent_prompt"],
            "step_results" to calledFunctionRun["step_results"],
            "timing" to calledFunctionRun["timing"],
            "error_code" to calledFunctionRun["error_code"],
            "summary" to if (success) "复用指令执行完成：$functionId"
                else calledFunctionRun["error_message"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: "复用指令执行失败：$functionId",
        ).filterValues { it != null })
    }

    private fun replayCheckConfig(
        step: Map<String, Any?>,
        checkerRules: List<OmniflowCheckerRule>,
        checkerBudget: ReplayHelper.CheckerTriggerBudget,
        stopRequested: (() -> Boolean)?,
    ): ActionExecutor.ActCheckConfig =
        ActionExecutor.ActCheckConfig(
            step = step,
            stopRequested = {
                if (stopRequested?.invoke() == true) {
                    throw ManualToolStopCancellationException("OmniFlow execution stopped manually")
                }
                false
            },
            checker = { action, args ->
                val effects = ReplayHelper.runChecker(
                    deviceOperator = deviceOperator,
                    step = step,
                    action = action,
                    args = args,
                    checkerRules = checkerRules,
                    checkerBudget = checkerBudget,
                    stopRequested = stopRequested,
                )
                if (effects.isEmpty()) {
                    emptyMap()
                } else {
                    mapOf("effects" to effects)
                }
            },
            actionTransfer = { _, args ->
                val transfer = try {
                    ReplayHelper.remapStepArgs(step, deviceOperator)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ReplayHelper.StepArgsResult(
                        args = args,
                        meta = mapOf(
                            "applied" to false,
                            "reason" to "action_transfer_exception",
                            "error_message" to e.message.orEmpty(),
                        ),
                    )
                }
                val checkedTransfer = ReplayHelper.requireActionTransferApplied(transfer, args)
                ActionExecutor.ActArgsResult(
                    args = ReplayHelper.normalizeArgsMap(checkedTransfer.args ?: args),
                    diagnostics = checkedTransfer.meta,
                )
            },
        )

    private fun checkerRulesForSpec(spec: Map<String, Any?>): List<OmniflowCheckerRule> =
        OmniflowCheckerRule.fromSpec(spec) + workspaceCheckerRules()

    private fun workspaceCheckerRules(): List<OmniflowCheckerRule> {
        val file = File(AgentWorkspaceManager.rootDirectory(context), CHECKER_RULES_FILE)
        if (!file.isFile) {
            writeDefaultCheckerRules(file)
        }
        val raw = runCatching {
            AgentToolJson.jsonElementToAny(
                checkerRuleJson.parseToJsonElement(file.readText(Charsets.UTF_8))
            )
        }.getOrNull() ?: return emptyList()
        val rawRules = when (raw) {
            is Iterable<*> -> raw.toList()
            is Array<*> -> raw.toList()
            is Map<*, *> -> {
                val map = mapArg(raw)
                listArg(map["checker_rules"])
                    .ifEmpty { listArg(map["checkerRules"]) }
                    .ifEmpty { listArg(map["rules"]) }
            }
            else -> emptyList()
        }
        return rawRules.mapNotNull { value ->
            mapArg(value).takeIf { it.isNotEmpty() }?.let(OmniflowCheckerRule::fromMap)
        }
    }

    private fun writeDefaultCheckerRules(file: File) {
        runCatching {
            file.parentFile?.mkdirs()
            context.assets.open(CHECKER_RULES_ASSET).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    private suspend fun runRuntimeResolveAndResume(
        initialPayload: Map<String, Any?>,
        functionId: String,
        spec: Map<String, Any?>,
        preparedSpec: Map<String, Any?>,
        runtimeResolveGoal: String,
        runtimeResolveBudget: Int,
        runtimeResolveModel: String,
        frontendRunId: String,
        frontendTaskId: String,
        frontendParent: String,
    ): Map<String, Any?> {
        val failedStepIndex = intArg(initialPayload["failed_step_index"], defaultValue = -1)
        if (failedStepIndex < 0) {
            return initialPayload.withRuntimeResolveFailure("runtime_resolve_failed_step_missing")
        }
        val stepCount = intArg(initialPayload["step_count"], defaultValue = 0)
        if (failedStepIndex >= stepCount) {
            return initialPayload.withRuntimeResolveFailure("runtime_resolve_failed_step_out_of_range")
        }
        val initialSteps = stepResultsFromPayload(initialPayload)
        val failedStep = initialSteps.firstOrNull {
            intArg(it["index"], defaultValue = -1) == failedStepIndex
        } ?: initialSteps.getOrNull(failedStepIndex).orEmpty()
        val plannerPayload = runCatching {
            runtimeResolvePlanner.plan(
                context,
                OobFunctionRuntimeResolveRequest(
                    functionId = functionId,
                    goal = runtimeResolveGoal,
                    failedStepIndex = failedStepIndex,
                    failedStep = failedStep,
                    failedRunPayload = initialPayload,
                    model = runtimeResolveModel,
                )
            )
        }.getOrElse { error ->
            return initialPayload.withRuntimeResolveFailure(
                reason = "runtime_resolve_planner_error",
                details = mapOf("error_message" to error.message.orEmpty()),
                resolveAttempted = true,
            )
        }
        val action = runtimeResolveAction(plannerPayload)
            ?: return initialPayload.withRuntimeResolveFailure(
                reason = "runtime_resolve_invalid_action",
                details = mapOf("planner" to plannerPayload.compactPlannerDiagnostics()),
                resolveAttempted = true,
            )
        val stepId = "runtime_resolve_step_${failedStepIndex + 1}"
        val resolveStartedAtMs = System.currentTimeMillis()
        val resolveResult = runCatching {
            val actionResult = actionExecutor.act(
                action = action.first,
                args = ReplayHelper.normalizeArgsMap(action.second),
                source = "function_runtime_resolve",
            )
            linkedMapOf<String, Any?>(
                "success" to actionResult.success,
                "summary" to actionResult.message,
                "diagnostics" to actionResult.diagnostics.takeIf { it.isNotEmpty() },
            ).filterValues { it != null }
        }.getOrElse { error ->
            return initialPayload.withRuntimeResolveFailure(
                reason = "runtime_resolve_action_failed",
                details = mapOf(
                    "error_message" to error.message.orEmpty(),
                    "planner" to plannerPayload.compactPlannerDiagnostics(),
                    "action" to action.toDebugMap(),
                ),
                resolveAttempted = true,
            )
        }
        if (resolveResult["success"] == false) {
            return initialPayload.withRuntimeResolveFailure(
                reason = "runtime_resolve_action_failed",
                details = mapOf(
                    "planner" to plannerPayload.compactPlannerDiagnostics(),
                    "action" to action.toDebugMap(),
                    "result" to resolveResult,
                ),
                resolveAttempted = true,
            )
        }
        val resolveFinishedAtMs = System.currentTimeMillis()
        val resumeFromStep = failedStepIndex + 1
        val resumePayload = if (resumeFromStep >= stepCount.toInt()) {
            linkedMapOf<String, Any?>(
                "success" to true,
                "step_results" to emptyList<Map<String, Any?>>(),
                "success_step_count" to 0,
                "completed_step_count" to stepCount,
            )
        } else {
            runFunction(
                functionId = functionId,
                arguments = emptyMap(),
                functionSpec = spec,
                preparedSpec = preparedSpec,
                argumentsValidated = true,
                resumeFromStep = resumeFromStep,
                frontendRunId = frontendRunId,
                frontendTaskId = frontendTaskId,
                frontendParent = frontendParent,
            )
        }
        val resolveStepResult = linkedMapOf<String, Any?>().apply {
            putAll(resolveResult)
            put("step_id", stepId)
            put("index", failedStepIndex)
            put("executor", "omniflow_runtime_resolve")
            put("success", true)
            put("model_free", false)
            put("runtime_resolve_applied", true)
            put("runtime_resolve_available", true)
            put("planner", plannerPayload.compactPlannerDiagnostics())
            put("runtime_resolve_action", action.toDebugMap())
            put("resolved_failed_step", failedStep)
            put("started_at_ms", resolveStartedAtMs)
            put("finished_at_ms", resolveFinishedAtMs)
            put("duration_ms", (resolveFinishedAtMs - resolveStartedAtMs).coerceAtLeast(0))
        }
        return combineRuntimeResolvePayload(
            initialPayload = initialPayload,
            resumePayload = resumePayload,
            resolveStepResult = resolveStepResult,
            failedStepIndex = failedStepIndex,
            stepCount = stepCount,
            runtimeResolveBudget = runtimeResolveBudget,
        )
    }

    private fun combineRuntimeResolvePayload(
        initialPayload: Map<String, Any?>,
        resumePayload: Map<String, Any?>,
        resolveStepResult: Map<String, Any?>,
        failedStepIndex: Int,
        stepCount: Int,
        runtimeResolveBudget: Int,
    ): Map<String, Any?> {
        val prefix = stepResultsFromPayload(initialPayload).filter {
            intArg(it["index"], defaultValue = -1) < failedStepIndex
        }
        val suffix = stepResultsFromPayload(resumePayload)
        val rawCombinedSteps = prefix + resolveStepResult + suffix
        val success = resumePayload["success"] == true &&
            rawCombinedSteps.size >= stepCount &&
            rawCombinedSteps.none { it["success"] == false }
        val resumeFailureReason = if (success) null else "runtime_resolve_next_step_not_ready"
        val resumeAttempt = linkedMapOf<String, Any?>(
            "success" to true,
            "resume_success" to (resumePayload["success"] == true),
            "resume_from_step" to (failedStepIndex + 1),
            "failure_reason" to resumeFailureReason,
            "resume_error_code" to resumePayload["error_code"],
            "resume_error_message" to resumePayload["error_message"],
        ).filterValues { it != null }
        val combinedSteps = prefix + resolveStepResult.withRuntimeResolveStepPayload(
            reason = resumeFailureReason ?: "runtime_resolve_resumed",
            resumeAttempt = resumeAttempt,
        ) + suffix
        val successStepCount = combinedSteps.count { it["success"] != false }
        return linkedMapOf<String, Any?>().apply {
            putAll(resumePayload)
            put("success", success)
            put("function_id", initialPayload["function_id"] ?: resumePayload["function_id"])
            put("runner", "omniflow_function")
            put("execution_mode", "replay_with_runtime_resolve")
            put("source", initialPayload["source"] ?: resumePayload["source"])
            put("run_source", initialPayload["run_source"] ?: resumePayload["run_source"])
            put("step_count", stepCount)
            put("active_step_count", initialPayload["active_step_count"] ?: stepCount)
            put("success_step_count", successStepCount)
            put("completed_step_count", if (success) stepCount else successStepCount)
            put("resume_from_step", initialPayload["resume_from_step"] ?: 0)
            put("model_used", true)
            put("model_required", false)
            put("runtime_resolve_applied", true)
            put("resolve_calls", maxOf(
                1,
                intArg(initialPayload["resolve_calls"], resumePayload["resolve_calls"], defaultValue = 0),
            ))
            put("runtime_resolve_budget", runtimeResolveBudget)
            put("runtime_resolve_required", false)
            put("runtime_resolve_available", true)
            put("runtime_resolve_attempt", resumeAttempt)
            put("failed_step_index", if (success) null else resumePayload["failed_step_index"])
            put("current_step_index", if (success) stepCount - 1 else resumePayload["current_step_index"])
            put("current_step_number", if (success) stepCount else resumePayload["current_step_number"])
            put("error_code", if (success) null else "OOB_RUNTIME_RESOLVE_NEXT_STEP_NOT_READY")
            put("error_message", if (success) "" else resumeFailureReason)
            put("step_results", combinedSteps)
            put("initial_replay", initialPayload.compactRunDiagnostics())
            put("resume_replay", resumePayload.compactRunDiagnostics())
        }
    }

    private fun Map<String, Any?>.withRuntimeResolveFailure(
        reason: String,
        details: Map<String, Any?> = emptyMap(),
        resolveAttempted: Boolean = false,
    ): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
        putAll(this@withRuntimeResolveFailure)
        put("runner", "omniflow_function")
        put("execution_mode", "runtime_resolve_failed")
        put("runtime_resolve_attempt", mapOf("success" to false, "reason" to reason) + details)
        put("runtime_resolve_required", true)
        put("runtime_resolve_available", false)
        if (resolveAttempted) {
            val existing = intArg(
                this@withRuntimeResolveFailure["resolve_calls"],
                this@withRuntimeResolveFailure["runtime_resolve_calls"],
                defaultValue = 0,
            )
            put("resolve_calls", maxOf(1, existing))
        }
        putIfAbsent("error_code", "OOB_RUNTIME_RESOLVE_FAILED")
        put("error_message", reason)
    }

    private fun runtimeResolveAction(plannerPayload: Map<String, Any?>): Pair<String, Map<String, Any?>>? {
        val rawAction = mapArg(plannerPayload["action"]).ifEmpty {
            if (plannerPayload["tool"] != null || plannerPayload["action_type"] != null) plannerPayload else emptyMap()
        }
        if (rawAction.isEmpty()) return null
        if (containsHiddenFunctionCallField(rawAction)) return null
        val rawTool = firstNonBlank(rawAction["tool"], rawAction["name"], rawAction["action_type"])
        val normalizedTool = when (rawTool.trim().lowercase()) {
            "press_back" -> OobActionSchema.TOOL_PRESS_KEY
            "press_home" -> OobActionSchema.TOOL_PRESS_KEY
            else -> resolveActionName(rawTool) ?: rawTool.trim().lowercase()
        }
        if (normalizedTool == OobActionSchema.TOOL_FINISHED ||
            normalizedTool == OobActionSchema.TOOL_GET_STATE ||
            RunLogReplayPolicy.isOmniflowToolCallTool(normalizedTool)
        ) {
            return null
        }
        if (normalizedTool !in RUNTIME_RESOLVE_ALLOWED_ACTIONS) return null
        val args = linkedMapOf<String, Any?>().apply {
            putAll(rawAction)
            remove("tool")
            remove("name")
            remove("action_type")
            if (rawTool.equals("press_back", ignoreCase = true)) put("key", "back")
            if (rawTool.equals("press_home", ignoreCase = true)) put("key", "home")
        }
        if (normalizedTool == OobActionSchema.TOOL_PRESS_KEY) {
            val key = firstNonBlank(args["key"]).lowercase()
            if (key !in setOf("back", "home")) return null
            args["key"] = key
        }
        return normalizedTool to args
    }

    private fun containsHiddenFunctionCallField(value: Any?): Boolean {
        return when (value) {
            is Map<*, *> -> value.any { (rawKey, rawValue) ->
                val key = rawKey?.toString().orEmpty()
                if (key == "function_id" || key == "functionId") return@any true
                if (
                    key in HIDDEN_FUNCTION_TOOL_NAME_KEYS &&
                    firstNonBlank(rawValue).trim().lowercase() in HIDDEN_FUNCTION_TOOL_NAMES
                ) {
                    return@any true
                }
                containsHiddenFunctionCallField(rawValue)
            }
            is Iterable<*> -> value.any(::containsHiddenFunctionCallField)
            is Array<*> -> value.any(::containsHiddenFunctionCallField)
            else -> false
        }
    }

    private fun Map<String, Any?>.requiresRuntimeResolve(): Boolean {
        if (this["runtime_resolve_required"] == true) return true
        if (stepResultsFromPayload(this).any { it["runtime_resolve_required"] == true }) return true
        return this["success"] == false && intArg(this["failed_step_index"], defaultValue = -1) >= 0
    }

    private fun Map<String, Any?>.withRuntimeResolveStepPayload(
        reason: String,
        resumeAttempt: Map<String, Any?>,
    ): Map<String, Any?> {
        val runtimeResolvePayload = linkedMapOf<String, Any?>(
            "success" to (resumeAttempt["resume_success"] == true),
            "reason" to reason,
            "resume_success" to resumeAttempt["resume_success"],
            "resume_from_step" to resumeAttempt["resume_from_step"],
            "failure_reason" to resumeAttempt["failure_reason"],
            "planner" to this["planner"],
            "action" to this["runtime_resolve_action"],
        ).filterValues { it != null }
        return linkedMapOf<String, Any?>().apply {
            putAll(this@withRuntimeResolveStepPayload)
            put("runtime_resolve", runtimeResolvePayload)
        }
    }

    private fun stepResultsFromPayload(payload: Map<String, Any?>): List<Map<String, Any?>> =
        listArg(payload["step_results"]).mapNotNull { raw ->
            mapArg(raw).takeIf { it.isNotEmpty() }
        }

    private fun Map<String, Any?>.compactPlannerDiagnostics(): Map<String, Any?> = linkedMapOf(
        "success" to this["success"],
        "parsed" to this["parsed"],
        "tool_name" to this["tool_name"],
        "model" to this["model"],
        "error" to this["error"],
        "model_calls" to this["model_calls"],
        "prompt_tokens" to this["prompt_tokens"],
        "completion_tokens" to this["completion_tokens"],
        "total_tokens" to this["total_tokens"],
        "usage" to this["usage"],
        "token_usage" to this["token_usage"],
        "phase_ms" to this["phase_ms"],
    ).filterValues { it != null }

    private fun Map<String, Any?>.compactRunDiagnostics(): Map<String, Any?> = linkedMapOf(
        "success" to this["success"],
        "runner" to this["runner"],
        "step_count" to this["step_count"],
        "success_step_count" to this["success_step_count"],
        "failed_step_index" to this["failed_step_index"],
        "error_code" to this["error_code"],
        "error_message" to this["error_message"],
    ).filterValues { it != null }

    private fun Pair<String, Map<String, Any?>>.toDebugMap(): Map<String, Any?> =
        linkedMapOf("tool" to first, "args" to second)

    private fun boundSteps(boundSpec: Map<String, Any?>): List<Map<String, Any?>> =
        OobFunctionSchemaBuilder.materializedSteps(boundSpec)

    private fun attachExecutionTiming(
        payload: Map<String, Any?>,
        timing: FunctionExecutionTiming,
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

    private fun errorPayload(
        code: String,
        message: String,
        functionId: String = "",
    ): Map<String, Any?> = linkedMapOf(
        "success" to false,
        "error_code" to code,
        "error_message" to message,
        "function_id" to functionId,
        "function_kind" to "oob_reusable_function",
        "asset_state" to "native_local",
    )

    private class FunctionExecutionTiming {
        private val startedAtNanos = System.nanoTime()
        private val startedAtMs: Long = System.currentTimeMillis()
        private val phases = linkedMapOf<String, Long>()

        fun <T> measure(phaseName: String, block: () -> T): T {
            val phaseStartedAtNanos = System.nanoTime()
            return try {
                block()
            } finally {
                phases[phaseName] = elapsedMs(phaseStartedAtNanos)
            }
        }

        suspend fun <T> measureSuspend(phaseName: String, block: suspend () -> T): T {
            val phaseStartedAtNanos = System.nanoTime()
            return try {
                block()
            } finally {
                phases[phaseName] = elapsedMs(phaseStartedAtNanos)
            }
        }

        fun finish(): Map<String, Any?> {
            val finishedAtMs = System.currentTimeMillis()
            val completedPhases = linkedMapOf<String, Long>()
            listOf(
                "load_function_spec_ms",
                "check_arguments_ms",
                "bind_function_args_ms",
                "bound_step_count_ms",
                "run_function_steps_ms",
            ).forEach { phaseName ->
                completedPhases[phaseName] = phases[phaseName] ?: 0L
            }
            phases.forEach { (phaseName, durationMs) ->
                completedPhases.putIfAbsent(phaseName, durationMs)
            }
            return linkedMapOf(
                "source" to "oob_function_execute",
                "started_at_ms" to startedAtMs,
                "finished_at_ms" to finishedAtMs,
                "duration_ms" to elapsedMs(startedAtNanos),
                "phase_ms" to completedPhases,
            )
        }

        private fun elapsedMs(startedAtNanos: Long): Long =
            ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)
    }

    private data class CallRequest(val targetTool: String, val targetArgs: Map<String, Any?>, val functionId: String)

    private fun replayStepEvidence(
        step: Map<String, Any?>,
        normalizedArgs: Map<String, Any?>,
    ): Map<String, Any?> {
        val sourceContext = mapArg(step["source_context"])
            .ifEmpty { mapArg(mapArg(step["args"])["source_context"]) }
        val before = mapArg(sourceContext["src_ctx"])
            .ifEmpty { mapArg(sourceContext["before"]) }
            .ifEmpty { mapArg(step["before"]) }
        val after = mapArg(sourceContext["dst_ctx"])
            .ifEmpty { mapArg(sourceContext["after"]) }
            .ifEmpty { mapArg(step["after"]) }
        return linkedMapOf<String, Any?>(
            "args" to normalizedArgs.takeIf { it.isNotEmpty() },
            "source_context" to sourceContext.takeIf { it.isNotEmpty() },
            "before" to before.takeIf { it.isNotEmpty() },
            "after" to after.takeIf { it.isNotEmpty() },
            "screenshot_path" to screenshotPathFromObservation(before).takeIf { it.isNotBlank() },
        ).filterValues { it != null }
    }

    private fun screenshotPathFromObservation(observation: Map<String, Any?>): String {
        val screenshot = mapArg(observation["screenshot"])
        return firstNonBlank(
            observation["screenshot_path"],
            observation["screenshotPath"],
            observation["image_path"],
            observation["imagePath"],
            observation["path"],
            screenshot["path"],
            screenshot["screenshot_path"],
            screenshot["screenshotPath"],
            screenshot["absolute_path"],
            screenshot["absolutePath"],
            screenshot["relative_path"],
            screenshot["relativePath"],
        )
    }

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
        val names = listOf(callableTool, ReplayHelper.actionNameForStep(step))
        return names.any { it.isNotBlank() && RunLogReplayPolicy.shouldSkipTool(it) }
    }

    private fun isOmniflowExecutionStep(step: Map<String, Any?>): Boolean {
        val tool = omniflowExecutionToolForStep(step, step["tool"]?.toString()?.trim().orEmpty())
        return when {
            RunLogReplayPolicy.isOmniflowToolCallTool(tool) -> {
                val args = resolveStepArgs(step)
                firstNonBlank(args["function_id"], step["function_id"],
                    firstNonBlank(args["tool_name"], args["target_tool"]).takeIf { it.isNotEmpty() && getSpec(it) != null }
                ).isNotEmpty()
            }
            else -> false
        }
    }

    companion object {
        const val FUNCTION_DIRECT_RUNNER = "oob_function_direct_runner"
        const val FUNCTION_RUN_SOURCE = "oob_function_replay"
        private const val CHECKER_RULES_FILE = "checkers/checker_rules.json"
        private const val CHECKER_RULES_ASSET = "omniflow/checkers/checker_rules.json"
        private const val TAG = "OobFunctionToolHandler"
        private const val MAX_OMNIFLOW_CALL_DEPTH = 8
        private const val REPLAY_UI_STEP_SETTLE_DELAY_MS = 1_000L
        private const val FRONTEND_SUCCESS_POPUP_VISIBLE_MS = 900L
        private const val FRONTEND_TERMINAL_POPUP_VISIBLE_MS = 2500L
        private val RUN_SEQUENCE = AtomicLong(0)
        private val RUNTIME_RESOLVE_ALLOWED_ACTIONS: Set<String> = setOf(
            OobActionSchema.TOOL_CLICK,
            OobActionSchema.TOOL_INPUT_TEXT,
            OobActionSchema.TOOL_SWIPE,
            OobActionSchema.TOOL_PRESS_KEY,
            OobActionSchema.TOOL_OPEN_APP,
            OobActionSchema.TOOL_WAIT,
        )
        private val HIDDEN_FUNCTION_TOOL_NAME_KEYS: Set<String> = setOf(
            "tool",
            "tool_name",
            "toolName",
            "callable_tool",
            "action_type",
            "name",
        )
        private val HIDDEN_FUNCTION_TOOL_NAMES: Set<String> = setOf(
            "call_tool",
            "call_function",
            "execute_function",
            "run_function",
            "finished",
            OobActionSchema.TOOL_GET_STATE,
        )
    }

    private fun nextRunId(startedAtMs: Long): String =
        "omniflow_run_${startedAtMs}_${RUN_SEQUENCE.incrementAndGet()}"
}
