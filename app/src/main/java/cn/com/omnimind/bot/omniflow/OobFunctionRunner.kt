package cn.com.omnimind.bot.omniflow

import android.content.Context
import cn.com.omnimind.baselib.runlog.OobCanonicalActionSchema
import cn.com.omnimind.baselib.runlog.OobReusableFunctionStore
import cn.com.omnimind.bot.agent.tool.handlers.OobFunctionToolHandler
import cn.com.omnimind.bot.agent.tool.handlers.SharedHelper
import cn.com.omnimind.bot.mcp.VlmTaskRequest
import cn.com.omnimind.bot.omniflow.language.OmniflowFunctionStore
import cn.com.omnimind.bot.runlog.OobActionCodec
import cn.com.omnimind.bot.runlog.RunLogReplayPolicy
import cn.com.omnimind.bot.runlog.UIStepExecutor
import cn.com.omnimind.bot.vlm.VlmToolCoordinator
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Runtime-owned small model resolve request.
 *
 * OmniFlow uses the same bounded resolve concept for Function argument filling
 * and for one failed replay step. This request is the failed-step form; legacy
 * wire payloads may still contain online_repair keys for compatibility.
 */
data class OobFunctionRuntimeResolveRequest(
    val functionId: String,
    val goal: String,
    val failedStepIndex: Int,
    val failedStep: Map<String, Any?>,
    val failedRunPayload: Map<String, Any?>,
    val model: String = "",
)

@Deprecated("Use OobFunctionRuntimeResolveRequest.")
typealias OobFunctionOnlineRepairRequest = OobFunctionRuntimeResolveRequest

fun interface OobFunctionRuntimeResolvePlanner {
    suspend fun plan(
        context: Context,
        request: OobFunctionRuntimeResolveRequest,
    ): Map<String, Any?>
}

@Deprecated("Use OobFunctionRuntimeResolvePlanner.")
typealias OobFunctionOnlineRepairPlanner = OobFunctionRuntimeResolvePlanner

private fun OobFunctionRuntimeResolveRequest.oneStepGuidance(): String = buildString {
    appendLine("OmniFlow runtime resolve for one failed replay step.")
    appendLine("Return only one ordinary UI action for the current failed Function step. Do not call a Function, do not finish the whole task, and do not re-plan the task.")
    appendLine("Allowed actions: click, input_text, swipe, press_key(back/home), open_app, wait.")
    appendLine("Function id: $functionId")
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

/**
 * Executes a registered OmniFlow Function through the local runner.
 */
class OobFunctionRunner(
    private val context: Context,
    private val workspaceFunctionStore: WorkspaceFunctionStore,
    private val functionRepository: OobFunctionRepository,
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
                    allowOmniFlowFunctionAutoExecute = false,
                ),
                scope = this,
            ).toPayload()
        }
    },
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    suspend fun execute(
        functionId: String,
        arguments: Map<String, Any?>,
        allowAgentFallback: Boolean,
        resumeFromStep: Int = 0,
        fallbackSessionId: String = "",
        fallbackAttempt: Int = 0,
        frontendRunId: String = "",
        frontendTaskId: String = "",
        frontendParent: String = "",
        onlineRepairGoal: String = "",
        onlineRepairBudget: Int = 0,
        onlineRepairModel: String = "",
        functionSpec: Map<String, Any?>? = null,
        materializedSpec: Map<String, Any?>? = null,
        argumentsValidated: Boolean = false,
    ): Map<String, Any?> = withContext(Dispatchers.Default) {
        val timing = FunctionExecutionTiming()
        val spec = timing.measure("load_function_spec_ms") {
            functionSpec ?: functionRepository.get(functionId)
        }
            ?: return@withContext errorPayload(
                code = "OOB_FUNCTION_NOT_FOUND",
                message = "OmniFlow function not found: $functionId",
                functionId = functionId
            ).let { attachExecutionTiming(it, timing) }
        val missing = timing.measure("check_arguments_ms") {
            if (argumentsValidated) emptyList() else OobReusableFunctionStore.missingRequiredArguments(spec, arguments)
        }
        if (missing.isNotEmpty()) {
            return@withContext errorPayload(
                code = "OOB_FUNCTION_ARGUMENTS_MISSING",
                message = "Missing required arguments: ${missing.joinToString(", ")}",
                functionId = functionId
            ).let { attachExecutionTiming(it + linkedMapOf("missing_required_arguments" to missing), timing) }
        }
        val materialized = timing.measure("materialize_function_ms") {
            materializedSpec ?: OobReusableFunctionStore.materialize(spec, arguments)
        }
        val runner = timing.measure("create_runner_ms") {
            OobFunctionToolHandler(
                context = context,
                helper = SharedHelper(context, json)
            ).apply {
                this.workspaceFunctionStore = workspaceFunctionStore
            }
        }
        val runtimeResolveEnabled = onlineRepairGoal.isNotBlank() && onlineRepairBudget > 0
        val typedPayload = runCatching {
            timing.measureSuspend("run_typed_function_ms") {
                runTypedFunctionIfAvailable(
                    runner = runner,
                    functionId = functionId,
                    arguments = arguments,
                    allowAgentFallback = allowAgentFallback,
                    resumeFromStep = resumeFromStep,
                    frontendRunId = frontendRunId,
                    frontendTaskId = frontendTaskId,
                    frontendParent = frontendParent,
                )
            }
        }.getOrElse { error ->
            return@withContext errorPayload(
                code = "OOB_CALL_TOOL_FAILED",
                message = error.message.orEmpty(),
                functionId = functionId
            ).let { attachExecutionTiming(it, timing) }
        }
        val payload = typedPayload ?: runCatching {
            timing.measureSuspend("run_materialized_function_ms") {
                runner.runMaterializedFunction(
                    functionId = functionId,
                    spec = spec,
                    materializedSpec = materialized,
                    allowAgentFallback = allowAgentFallback,
                    allowToolDelegationWithoutRouter = false,
                    resumeFromStep = resumeFromStep,
                    fallbackSessionId = fallbackSessionId,
                    fallbackAttempt = fallbackAttempt,
                    frontendRunId = frontendRunId,
                    frontendTaskId = frontendTaskId,
                    frontendParent = frontendParent,
                )
            }
        }.getOrElse { error ->
            errorPayload(
                code = "OOB_CALL_TOOL_FAILED",
                message = error.message.orEmpty(),
                functionId = functionId
            )
        }
        val resolvedPayload = if (runtimeResolveEnabled && payload.requiresRuntimeResolve()) {
            timing.measureSuspend("runtime_resolve_ms") {
                runRuntimeResolveAndResume(
                    initialPayload = payload,
                    runner = runner,
                    functionId = functionId,
                    spec = spec,
                    materializedSpec = materialized,
                    arguments = arguments,
                    onlineRepairGoal = onlineRepairGoal,
                    onlineRepairBudget = onlineRepairBudget,
                    onlineRepairModel = onlineRepairModel,
                    frontendRunId = frontendRunId,
                    frontendTaskId = frontendTaskId,
                    frontendParent = frontendParent,
                    typedFunctionAvailable = typedPayload != null,
                )
            }
        } else {
            payload
        }
        attachExecutionTiming(resolvedPayload, timing)
    }

    private suspend fun runTypedFunctionIfAvailable(
        runner: OobFunctionToolHandler,
        functionId: String,
        arguments: Map<String, Any?>,
        allowAgentFallback: Boolean,
        resumeFromStep: Int,
        frontendRunId: String,
        frontendTaskId: String,
        frontendParent: String,
    ): Map<String, Any?>? {
        val storedFunction = OmniflowFunctionStore.get(context, functionId) ?: return null
        val materialized = runCatching {
            storedFunction.materialize(stringArguments(arguments))
        }.getOrElse {
            return null
        }
        return runner.runFunction(
            fn = materialized,
            startIndex = resumeFromStep,
            allowAgentFallback = allowAgentFallback,
            allowToolDelegationWithoutRouter = false,
            frontendRunId = frontendRunId,
            frontendTaskId = frontendTaskId,
            frontendParent = frontendParent,
        )
    }

    private suspend fun runRuntimeResolveAndResume(
        initialPayload: Map<String, Any?>,
        runner: OobFunctionToolHandler,
        functionId: String,
        spec: Map<String, Any?>,
        materializedSpec: Map<String, Any?>,
        arguments: Map<String, Any?>,
        onlineRepairGoal: String,
        onlineRepairBudget: Int,
        onlineRepairModel: String,
        frontendRunId: String,
        frontendTaskId: String,
        frontendParent: String,
        typedFunctionAvailable: Boolean,
    ): Map<String, Any?> {
        val failedStepIndex = OobFunctionJson.intArg(initialPayload["failed_step_index"], defaultValue = -1)
        if (failedStepIndex < 0) {
            return initialPayload.withRuntimeResolveFailure(
                reason = "runtime_resolve_failed_step_missing",
            )
        }
        val stepCount = OobFunctionJson.intArg(initialPayload["step_count"], defaultValue = 0)
        if (failedStepIndex >= stepCount) {
            return initialPayload.withRuntimeResolveFailure(
                reason = "runtime_resolve_failed_step_out_of_range",
            )
        }
        val initialSteps = stepResultsFromPayload(initialPayload)
        val failedStep = initialSteps.firstOrNull {
            OobFunctionJson.intArg(it["index"], defaultValue = -1) == failedStepIndex
        } ?: initialSteps.getOrNull(failedStepIndex).orEmpty()
        val resolveRequest = OobFunctionRuntimeResolveRequest(
            functionId = functionId,
            goal = onlineRepairGoal,
            failedStepIndex = failedStepIndex,
            failedStep = failedStep,
            failedRunPayload = initialPayload,
            model = onlineRepairModel,
        )
        val plannerPayload = runCatching {
            runtimeResolvePlanner.plan(context, resolveRequest)
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
        val repairStep = linkedMapOf<String, Any?>(
            "id" to stepId,
            "title" to "Runtime resolve action for step ${failedStepIndex + 1}",
            "kind" to "omniflow_action",
            "executor" to RunLogReplayPolicy.EXECUTOR_OMNIFLOW,
            "model_free" to true,
            "scriptable" to true,
            "tool" to action.first,
            "callable_tool" to action.first,
            "args" to action.second,
        )
        val repairStartedAtMs = System.currentTimeMillis()
        val repairResult = runCatching {
            UIStepExecutor.execute(
                step = repairStep,
                stepId = stepId,
                stepTitle = "Runtime resolve action",
                checkerRules = emptyList(),
                checkerBudget = UIStepExecutor.CheckerTriggerBudget(),
                stopRequested = null,
            )
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
        if (repairResult["success"] == false) {
            return initialPayload.withRuntimeResolveFailure(
                reason = "runtime_resolve_action_failed",
                details = mapOf(
                    "planner" to plannerPayload.compactPlannerDiagnostics(),
                    "action" to action.toDebugMap(),
                    "result" to repairResult,
                ),
                resolveAttempted = true,
            )
        }
        val repairFinishedAtMs = System.currentTimeMillis()
        val resumeFromStep = failedStepIndex + 1
        val resumePayload = if (resumeFromStep >= stepCount) {
            linkedMapOf<String, Any?>(
                "success" to true,
                "step_results" to emptyList<Map<String, Any?>>(),
                "success_step_count" to 0,
                "completed_step_count" to stepCount,
            )
        } else {
            val typedResume = if (typedFunctionAvailable) {
                runTypedFunctionIfAvailable(
                    runner = runner,
                    functionId = functionId,
                    arguments = arguments,
                    allowAgentFallback = false,
                    resumeFromStep = resumeFromStep,
                    frontendRunId = frontendRunId,
                    frontendTaskId = frontendTaskId,
                    frontendParent = frontendParent,
                )
            } else {
                null
            }
            typedResume ?: runner.runMaterializedFunction(
                functionId = functionId,
                spec = spec,
                materializedSpec = materializedSpec,
                allowAgentFallback = false,
                allowToolDelegationWithoutRouter = false,
                resumeFromStep = resumeFromStep,
                frontendRunId = frontendRunId,
                frontendTaskId = frontendTaskId,
                frontendParent = frontendParent,
            )
        }
        val repairStepResult = linkedMapOf<String, Any?>().apply {
            putAll(repairResult)
            put("step_id", stepId)
            put("index", failedStepIndex)
            put("executor", "omniflow_runtime_resolve")
            put("success", true)
            put("model_free", false)
            put("runtime_resolve_applied", true)
            put("runtime_resolve_available", true)
            put("online_repair_applied", true)
            put("online_repair_available", true)
            put("planner", plannerPayload.compactPlannerDiagnostics())
            put("repair_action", action.toDebugMap())
            put("repaired_failed_step", failedStep)
            put("started_at_ms", repairStartedAtMs)
            put("finished_at_ms", repairFinishedAtMs)
            put("duration_ms", (repairFinishedAtMs - repairStartedAtMs).coerceAtLeast(0))
        }
        return combineRuntimeResolvePayload(
            initialPayload = initialPayload,
            resumePayload = resumePayload,
            repairStepResult = repairStepResult,
            failedStepIndex = failedStepIndex,
            stepCount = stepCount,
            onlineRepairBudget = onlineRepairBudget,
        )
    }

    private fun combineRuntimeResolvePayload(
        initialPayload: Map<String, Any?>,
        resumePayload: Map<String, Any?>,
        repairStepResult: Map<String, Any?>,
        failedStepIndex: Int,
        stepCount: Int,
        onlineRepairBudget: Int,
    ): Map<String, Any?> {
        val prefix = stepResultsFromPayload(initialPayload).filter {
            OobFunctionJson.intArg(it["index"], defaultValue = -1) < failedStepIndex
        }
        val suffix = stepResultsFromPayload(resumePayload)
        val rawCombinedSteps = prefix + repairStepResult + suffix
        val success = resumePayload["success"] == true &&
            rawCombinedSteps.size >= stepCount &&
            rawCombinedSteps.none { it["success"] == false }
        val resumeFailureReason = if (success) {
            null
        } else {
            "runtime_resolve_next_step_not_ready"
        }
        val repairReason = resumeFailureReason ?: "runtime_resolve_resumed"
        val resumeAttempt = linkedMapOf<String, Any?>(
            "success" to true,
            "resume_success" to (resumePayload["success"] == true),
            "resume_from_step" to (failedStepIndex + 1),
            "failure_reason" to resumeFailureReason,
            "resume_error_code" to resumePayload["error_code"],
            "resume_error_message" to resumePayload["error_message"],
        ).filterValues { it != null }
        val repairStepWithPayload = repairStepResult.withRuntimeResolveStepPayload(
            reason = repairReason,
            resumeAttempt = resumeAttempt,
        )
        val combinedSteps = prefix + repairStepWithPayload + suffix
        val successStepCount = combinedSteps.count { it["success"] != false }
        return linkedMapOf<String, Any?>().apply {
            putAll(resumePayload)
            put("success", success)
            put("function_id", initialPayload["function_id"] ?: resumePayload["function_id"])
            put("runner", "oob_function_offline_online_offline")
            put("execution_mode", "offline_online_offline")
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
            put("runtime_resolve_steps", 1)
            put("runtime_resolve_budget", onlineRepairBudget)
            put("runtime_resolve_required", false)
            put("runtime_resolve_available", true)
            put("runtime_resolve_attempt", resumeAttempt)
            put("online_repair_applied", true)
            put("online_repair_steps", 1)
            put("online_repair_budget", onlineRepairBudget)
            put("online_repair_required", false)
            put("online_repair_available", true)
            put("online_repair_attempt", resumeAttempt)
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
        put("runner", "oob_function_runtime_resolve_failed")
        put("execution_mode", "offline_online_failed")
        put("runtime_resolve_attempt", mapOf("success" to false, "reason" to reason) + details)
        put("runtime_resolve_required", true)
        put("runtime_resolve_available", false)
        put("online_repair_attempt", mapOf("success" to false, "reason" to reason) + details)
        put("online_repair_required", true)
        put("online_repair_available", false)
        if (resolveAttempted) {
            val existing = OobFunctionJson.intArg(
                this@withRuntimeResolveFailure["resolve_calls"],
                this@withRuntimeResolveFailure["runtime_resolve_calls"],
                defaultValue = 0,
            )
            put("resolve_calls", maxOf(1, existing))
        }
        putIfAbsent("error_code", "OOB_RUNTIME_RESOLVE_FAILED")
        put("error_message", reason)
    }

    private fun stepResultsFromPayload(payload: Map<String, Any?>): List<Map<String, Any?>> =
        OobFunctionJson.listArg(payload["step_results"]).mapNotNull { raw ->
            OobFunctionJson.mapArg(raw).takeIf { it.isNotEmpty() }
        }

    private fun runtimeResolveAction(plannerPayload: Map<String, Any?>): Pair<String, Map<String, Any?>>? {
        val rawAction = OobFunctionJson.mapArg(plannerPayload["action"]).ifEmpty {
            if (plannerPayload["tool"] != null || plannerPayload["action_type"] != null) plannerPayload else emptyMap()
        }
        if (rawAction.isEmpty()) return null
        if (containsHiddenFunctionCallField(rawAction)) return null
        val rawTool = OobFunctionJson.firstNonBlank(rawAction["tool"], rawAction["name"], rawAction["action_type"])
        val normalizedTool = when (rawTool.trim().lowercase()) {
            "press_back" -> OobActionCodec.ACTION_PRESS_KEY
            "press_home" -> OobActionCodec.ACTION_PRESS_KEY
            else -> OobActionCodec.canonicalActionForName(rawTool) ?: rawTool.trim().lowercase()
        }
        if (normalizedTool == OobActionCodec.ACTION_FINISHED ||
            normalizedTool == OobCanonicalActionSchema.TOOL_GET_STATE ||
            RunLogReplayPolicy.isOmniflowToolCallTool(normalizedTool)
        ) {
            return null
        }
        if (normalizedTool !in ONLINE_REPAIR_ALLOWED_ACTIONS) return null
        val args = linkedMapOf<String, Any?>().apply {
            putAll(rawAction)
            remove("tool")
            remove("name")
            remove("action_type")
            if (rawTool.equals("press_back", ignoreCase = true)) put("key", "back")
            if (rawTool.equals("press_home", ignoreCase = true)) put("key", "home")
        }
        if (normalizedTool == OobActionCodec.ACTION_PRESS_KEY) {
            val key = OobFunctionJson.firstNonBlank(args["key"]).lowercase()
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
                    OobFunctionJson.firstNonBlank(rawValue).trim().lowercase() in HIDDEN_FUNCTION_TOOL_NAMES
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

    private fun stringArguments(arguments: Map<String, Any?>): Map<String, String> =
        arguments.mapValues { (_, value) -> value?.toString().orEmpty() }

    private fun Map<String, Any?>.requiresRuntimeResolve(): Boolean {
        if (this["runtime_resolve_required"] == true) return true
        if (this["online_repair_required"] == true) return true
        if (stepResultsFromPayload(this).any {
                it["runtime_resolve_required"] == true || it["online_repair_required"] == true
            }
        ) return true
        return this["success"] == false &&
            OobFunctionJson.intArg(this["failed_step_index"], defaultValue = -1) >= 0
    }

    private fun Map<String, Any?>.withOmniFlowFallbackAliases(): Map<String, Any?> {
        val normalized = linkedMapOf<String, Any?>().apply { putAll(this@withOmniFlowFallbackAliases) }
        fun alias(primary: String, secondary: String, removeSecondary: Boolean = false) {
            if (normalized[primary] == null && normalized[secondary] != null) {
                normalized[primary] = normalized[secondary]
            }
            if (removeSecondary) normalized.remove(secondary)
        }
        alias("runtime_resolve_required", "online_fallback_required", removeSecondary = true)
        alias("runtime_resolve_available", "online_fallback_available", removeSecondary = true)
        alias("runtime_resolve_applied", "online_fallback_applied", removeSecondary = true)
        alias("runtime_resolve_steps", "online_fallback_steps", removeSecondary = true)
        alias("runtime_resolve_budget", "online_fallback_budget", removeSecondary = true)
        alias("runtime_resolve_attempt", "online_fallback_attempt", removeSecondary = true)
        alias("runtime_resolve_required", "online_repair_required")
        alias("runtime_resolve_available", "online_repair_available")
        alias("runtime_resolve_applied", "online_repair_applied")
        alias("runtime_resolve_steps", "online_repair_steps")
        alias("runtime_resolve_budget", "online_repair_budget")
        alias("runtime_resolve_attempt", "online_repair_attempt")
        alias("online_repair_required", "runtime_resolve_required")
        alias("online_repair_available", "runtime_resolve_available")
        alias("online_repair_applied", "runtime_resolve_applied")
        alias("online_repair_steps", "runtime_resolve_steps")
        alias("online_repair_budget", "runtime_resolve_budget")
        alias("online_repair_attempt", "runtime_resolve_attempt")

        val repairPayload = OobFunctionJson.mapArg(normalized["online_repair"])
        val fallbackPayload = OobFunctionJson.mapArg(normalized["online_fallback"])
        val runtimeResolvePayload = OobFunctionJson.mapArg(normalized["runtime_resolve"])
        if (fallbackPayload.isNotEmpty() && repairPayload.isEmpty()) {
            normalized["online_repair"] = fallbackPayload.withOnlineRepairReasonAlias()
        }
        val normalizedRepairPayload = OobFunctionJson.mapArg(normalized["online_repair"])
        if (runtimeResolvePayload.isEmpty() && normalizedRepairPayload.isNotEmpty()) {
            normalized["runtime_resolve"] = normalizedRepairPayload.withRuntimeResolveReasonAlias()
        } else if (runtimeResolvePayload.isNotEmpty() && normalizedRepairPayload.isEmpty()) {
            normalized["online_repair"] = runtimeResolvePayload
        }
        normalized.remove("online_fallback")

        val stepResults = OobFunctionJson.listArg(normalized["step_results"])
        if (stepResults.isNotEmpty()) {
            normalized["step_results"] = stepResults.map { raw ->
                val step = OobFunctionJson.mapArg(raw)
                if (step.isEmpty()) raw else step.withOmniFlowFallbackAliases()
            }
        }
        return normalized
    }

    private fun Map<String, Any?>.withRuntimeResolveStepPayload(
        reason: String,
        resumeAttempt: Map<String, Any?>,
    ): Map<String, Any?> {
        val repairPayload = linkedMapOf<String, Any?>(
            "success" to (resumeAttempt["resume_success"] == true),
            "reason" to reason,
            "resume_success" to resumeAttempt["resume_success"],
            "resume_from_step" to resumeAttempt["resume_from_step"],
            "failure_reason" to resumeAttempt["failure_reason"],
            "planner" to this["planner"],
            "action" to this["repair_action"],
        ).filterValues { it != null }
        return linkedMapOf<String, Any?>().apply {
            putAll(this@withRuntimeResolveStepPayload)
            put("runtime_resolve", repairPayload)
            put("online_repair", repairPayload)
        }
    }

    private fun Map<String, Any?>.withOnlineRepairReasonAlias(): Map<String, Any?> =
        linkedMapOf<String, Any?>().apply {
            putAll(this@withOnlineRepairReasonAlias)
            val reason = OobFunctionJson.firstNonBlank(this["reason"])
            val repairReason = when (reason) {
                "online_fallback_unavailable" -> "online_repair_unavailable"
                "online_fallback_budget_exhausted" -> "online_repair_budget_exhausted"
                "online_fallback_planner_error" -> "online_repair_planner_error"
                "online_fallback_invalid_action" -> "online_repair_invalid_action"
                "online_fallback_action_transfer_failed" -> "online_repair_action_transfer_failed"
                "online_fallback_act_error" -> "online_repair_act_error"
                "online_fallback_act_failed" -> "online_repair_act_failed"
                "online_fallback_resumed" -> "online_repair_resumed"
                else -> reason
            }
            if (repairReason.isNotBlank()) put("reason", repairReason)
        }

    private fun Map<String, Any?>.withRuntimeResolveReasonAlias(): Map<String, Any?> =
        linkedMapOf<String, Any?>().apply {
            putAll(this@withRuntimeResolveReasonAlias)
            val reason = OobFunctionJson.firstNonBlank(this["reason"])
            val runtimeReason = when (reason) {
                "online_fallback_unavailable",
                "online_repair_unavailable" -> "runtime_resolve_unavailable"
                "online_fallback_budget_exhausted",
                "online_repair_budget_exhausted" -> "runtime_resolve_budget_exhausted"
                "online_fallback_planner_error",
                "online_repair_planner_error" -> "runtime_resolve_planner_error"
                "online_fallback_invalid_action",
                "online_repair_invalid_action" -> "runtime_resolve_invalid_action"
                "online_fallback_action_transfer_failed",
                "online_repair_action_transfer_failed" -> "runtime_resolve_action_transfer_failed"
                "online_fallback_act_error",
                "online_repair_act_error" -> "runtime_resolve_act_error"
                "online_fallback_act_failed",
                "online_repair_act_failed" -> "runtime_resolve_act_failed"
                "online_fallback_resumed",
                "online_repair_resumed" -> "runtime_resolve_resumed"
                "online_repair_next_step_not_ready" -> "runtime_resolve_next_step_not_ready"
                else -> reason
            }
            if (runtimeReason.isNotBlank()) put("reason", runtimeReason)
        }

    private fun Map<String, Any?>.withExecutionSummary(): Map<String, Any?> {
        val normalized = linkedMapOf<String, Any?>().apply { putAll(this@withExecutionSummary) }
        val existingSummary = OobFunctionJson.mapArg(normalized["execution_summary"])
        val stepResults = stepResultsFromPayload(normalized)
        val runtimeResolveSteps = OobFunctionJson.intArg(
            // Legacy RunLog keys are accepted as input only. Public summaries
            // expose the unified `resolve_calls` metric instead.
            existingSummary["runtime_resolve_steps"],
            existingSummary["repair_steps"],
            existingSummary["online_fallback_steps"],
            existingSummary["online_repair_steps"],
            normalized["runtime_resolve_steps"],
            normalized["online_fallback_steps"],
            normalized["online_repair_steps"],
            defaultValue = stepResults.count { it.isRuntimeResolveStep() },
        )
        val legacyOfflineSteps = OobFunctionJson.intArg(
            existingSummary["offline_steps"],
            defaultValue = stepResults.count { it["success"] != false && !it.isRuntimeResolveStep() },
        )
        val steps = OobFunctionJson.intArg(
            normalized["success_step_count"],
            normalized["actions_executed"],
            normalized["completed_step_count"],
            existingSummary["steps"],
            normalized["steps"],
            defaultValue = legacyOfflineSteps + runtimeResolveSteps,
        )
        val resolveCalls = maxOf(
            runtimeResolveSteps,
            OobFunctionJson.intArg(
                existingSummary["resolve_calls"],
                normalized["resolve_calls"],
                normalized["runtime_resolve_calls"],
                defaultValue = 0,
            ),
        )
        val timing = OobFunctionJson.mapArg(normalized["timing"])
        val success = normalized["success"] == true
        val modelCalls = OobFunctionJson.longArg(
            existingSummary["model_calls"],
            normalized["model_calls"],
            defaultValue = 0L,
        ).takeIf { it > 0L } ?: normalized.inferredRuntimeResolvePlannerCalls(stepResults)
        val tokens = OobFunctionJson.longArg(
            existingSummary["tokens"],
            existingSummary["total_tokens"],
            normalized["tokens"],
            normalized["total_tokens"],
            defaultValue = normalized.tokenCountFromPlannerPayloads(stepResults, "total_tokens"),
        )
        normalized["execution_summary"] = linkedMapOf<String, Any?>(
            "success" to success,
            "function_id" to OobFunctionJson.firstNonBlank(normalized["function_id"]),
            "steps" to steps,
            "resolve_calls" to resolveCalls,
            "model_calls" to modelCalls,
            "tokens" to tokens,
            "elapsed_ms" to OobFunctionJson.longArg(
                existingSummary["elapsed_ms"],
                timing["duration_ms"],
                timing["call_duration_ms"],
                timing["runner_duration_ms"],
                defaultValue = 0L,
            ),
            "failure_reason" to if (success) null else OobFunctionJson.firstNonBlank(
                normalized["error_code"],
                normalized["error_message"],
            ).takeIf { it.isNotBlank() },
        ).filterValues { it != null }
        return normalized
    }

    private fun Map<String, Any?>.isRuntimeResolveStep(): Boolean =
        this["runtime_resolve_applied"] == true ||
        this["online_repair_applied"] == true ||
            this["online_fallback_applied"] == true ||
            this["executor"] == "omniflow_runtime_resolve" ||
            this["executor"] == "omniflow_online_repair"

    private fun Map<String, Any?>.inferredRuntimeResolvePlannerCalls(
        stepResults: List<Map<String, Any?>>,
    ): Long {
        if (OobFunctionJson.mapArg(this["runtime_resolve_attempt"]).isNotEmpty()) return 1L
        if (OobFunctionJson.mapArg(this["online_repair_attempt"]).isNotEmpty()) return 1L
        if (OobFunctionJson.mapArg(this["online_fallback_attempt"]).isNotEmpty()) return 1L
        return if (stepResults.any {
                it.isRuntimeResolveStep() && OobFunctionJson.mapArg(it["planner"]).isNotEmpty()
            }
        ) {
            1L
        } else {
            0L
        }
    }

    private fun Map<String, Any?>.tokenCountFromPlannerPayloads(
        stepResults: List<Map<String, Any?>>,
        key: String,
    ): Long {
        val candidates = mutableListOf<Map<String, Any?>>()
        candidates += this
        candidates += OobFunctionJson.mapArg(this["usage"])
        candidates += OobFunctionJson.mapArg(this["token_usage"])
        candidates += OobFunctionJson.mapArg(this["llm_usage"])
        candidates += OobFunctionJson.mapArg(this["execution_summary"])
        OobFunctionJson.mapArg(this["runtime_resolve_attempt"]).let { attempt ->
            candidates += attempt
            candidates += OobFunctionJson.mapArg(attempt["planner"])
        }
        OobFunctionJson.mapArg(this["online_repair_attempt"]).let { attempt ->
            candidates += attempt
            candidates += OobFunctionJson.mapArg(attempt["planner"])
        }
        OobFunctionJson.mapArg(this["online_fallback_attempt"]).let { attempt ->
            candidates += attempt
            candidates += OobFunctionJson.mapArg(attempt["planner"])
        }
        stepResults.forEach { step ->
            candidates += step
            OobFunctionJson.mapArg(step["planner"]).let { planner ->
                candidates += planner
                candidates += OobFunctionJson.mapArg(planner["usage"])
                candidates += OobFunctionJson.mapArg(planner["token_usage"])
                candidates += OobFunctionJson.mapArg(planner["llm_usage"])
            }
        }
        return candidates.firstNotNullOfOrNull { candidate ->
            OobFunctionJson.longArg(candidate[key], defaultValue = 0L).takeIf { it > 0L }
        } ?: 0L
    }

    private fun attachExecutionTiming(
        payload: Map<String, Any?>,
        timing: FunctionExecutionTiming,
    ): Map<String, Any?> {
        val toolkitTiming = timing.finish()
        val toolkitPhaseMs = OobFunctionJson.mapArg(toolkitTiming["phase_ms"])
        val existingTiming = OobFunctionJson.mapArg(payload["timing"])
        val runnerSource = existingTiming["source"]?.toString().orEmpty()
        val runnerPhaseMs = OobFunctionJson.mapArg(existingTiming["phase_ms"])
        val runnerStartedAtMs = OobFunctionJson.longArg(existingTiming["started_at_ms"], defaultValue = 0L)
        val runnerFinishedAtMs = OobFunctionJson.longArg(existingTiming["finished_at_ms"], defaultValue = 0L)
        val runnerDurationMs = OobFunctionJson.longArg(
            existingTiming["runner_duration_ms"],
            existingTiming["duration_ms"],
            defaultValue = 0L,
        )
        val startupPhaseMs = linkedMapOf<String, Any?>()
        listOf(
            "load_function_spec_ms",
            "check_arguments_ms",
            "materialize_function_ms",
            "create_runner_ms",
            "run_typed_function_ms",
        ).forEach { phaseName ->
            startupPhaseMs[phaseName] = OobFunctionJson.longArg(toolkitPhaseMs[phaseName], defaultValue = 0L)
        }
        if (runnerPhaseMs.isNotEmpty()) {
            startupPhaseMs["runner_pre_step_loop_ms"] =
                OobFunctionJson.longArg(runnerPhaseMs["pre_step_loop_ms"], defaultValue = 0L)
        }
        val startupDurationMs = startupPhaseMs.values.sumOf { OobFunctionJson.longArg(it, defaultValue = 0L) }
        val mergedTiming = linkedMapOf<String, Any?>().apply {
            putAll(existingTiming)
            if (runnerSource.isNotBlank()) put("runner_source", runnerSource)
            if (runnerStartedAtMs > 0L) put("runner_started_at_ms", runnerStartedAtMs)
            if (runnerFinishedAtMs > 0L) put("runner_finished_at_ms", runnerFinishedAtMs)
            if (runnerDurationMs > 0L) put("runner_duration_ms", runnerDurationMs)
            if (runnerPhaseMs.isNotEmpty()) put("runner_phase_ms", runnerPhaseMs)
            put("source", "oob_function_execute")
            put("started_at_ms", toolkitTiming["started_at_ms"])
            put("finished_at_ms", toolkitTiming["finished_at_ms"])
            put("duration_ms", toolkitTiming["duration_ms"])
            put("phase_ms", toolkitPhaseMs)
            put("startup_phase_ms", startupPhaseMs)
            put("startup_duration_ms", startupDurationMs)
        }
        return linkedMapOf<String, Any?>().apply {
            putAll(payload)
            put("timing", mergedTiming)
        }.withOmniFlowFallbackAliases().withExecutionSummary()
    }

    private fun errorPayload(
        code: String,
        message: String,
        functionId: String = ""
    ): Map<String, Any?> = linkedMapOf(
        "success" to false,
        "error_code" to code,
        "error_message" to message,
        "function_id" to functionId,
        "function_kind" to "oob_reusable_function",
        "asset_state" to "native_local"
    )

    private class FunctionExecutionTiming {
        private val startedAtNanos = System.nanoTime()
        val startedAtMs: Long = System.currentTimeMillis()
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
                "materialize_function_ms",
                "create_runner_ms",
                "run_typed_function_ms",
                "run_materialized_function_ms",
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

    private companion object {
        val ONLINE_REPAIR_ALLOWED_ACTIONS: Set<String> = setOf(
            OobActionCodec.ACTION_CLICK,
            OobActionCodec.ACTION_INPUT_TEXT,
            OobActionCodec.ACTION_SWIPE,
            OobActionCodec.ACTION_PRESS_KEY,
            OobActionCodec.ACTION_OPEN_APP,
            OobActionCodec.ACTION_WAIT,
        )
        val HIDDEN_FUNCTION_TOOL_NAME_KEYS: Set<String> = setOf(
            "tool",
            "tool_name",
            "toolName",
            "callable_tool",
            "action_type",
            "name",
        )
        val HIDDEN_FUNCTION_TOOL_NAMES: Set<String> = setOf(
            "call_tool",
            "call_function",
            "execute_function",
            "run_function",
            "finished",
            OobCanonicalActionSchema.TOOL_GET_STATE,
        )
    }
}
