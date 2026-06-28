package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.bot.omniflow.OobFunctionJson.listArg
import cn.com.omnimind.bot.omniflow.OobFunctionJson.mapArg

/**
 * Builds Function run and step result payloads. The handler owns execution order;
 * this builder owns the stable result schema exposed to tools, RunLogs, and UI.
 */
class OobFunctionRunResultBuilder {
    fun timing(startedAtMs: Long): Timing = Timing(startedAtMs)

    fun failureStep(
        stepId: String,
        tool: String,
        executor: String,
        summary: String,
        errorCode: String,
        extras: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "step_id" to stepId,
        "tool" to tool,
        "executor" to executor,
        "model_free" to true,
        "success" to false,
        "error_code" to errorCode,
        "summary" to summary,
    ).apply {
        putAll(extras)
    }

    fun runtimeResolveRequiredStep(
        stepId: String,
        tool: String,
        prompt: String,
        summary: String,
        extras: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "step_id" to stepId,
        "tool" to tool,
        "executor" to "omniflow_runtime_resolve",
        "model_free" to true,
        "success" to false,
        "runtime_resolve_required" to true,
        "runtime_resolve_available" to false,
        "error_code" to "OOB_RUNTIME_RESOLVE_UNAVAILABLE",
        "runtime_resolve_reason" to prompt.takeIf { it.isNotBlank() },
        "summary" to summary,
    ).apply {
        putAll(extras)
    }.filterValues { it != null }

    fun failedRun(
        functionId: String,
        spec: Map<String, Any?>,
        auditRunId: String,
        startedAtMs: Long,
        errorCode: String,
        errorMessage: String,
        extras: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> {
        val finishedAtMs = System.currentTimeMillis()
        return linkedMapOf<String, Any?>(
            "success" to false,
            "run_id" to auditRunId,
            "audit_run_id" to auditRunId,
            "function_id" to (spec["function_id"] ?: functionId),
            "description" to spec["description"]?.toString().orEmpty(),
            "source" to OobFunctionToolHandler.FUNCTION_RUN_SOURCE,
            "run_source" to OobFunctionToolHandler.FUNCTION_RUN_SOURCE,
            "runner" to OobFunctionToolHandler.FUNCTION_DIRECT_RUNNER,
            "step_count" to stepCountFromSpec(spec),
            "success_step_count" to 0,
            "model_used" to false,
            "model_required" to false,
            "delegated_tool_used" to false,
            "error_code" to errorCode,
            "error_message" to errorMessage,
            "timing" to linkedMapOf(
                "source" to "call_tool_runner",
                "started_at_ms" to startedAtMs,
                "finished_at_ms" to finishedAtMs,
                "duration_ms" to (finishedAtMs - startedAtMs).coerceAtLeast(0),
                "runner_duration_ms" to (finishedAtMs - startedAtMs).coerceAtLeast(0)
            ),
            "step_results" to emptyList<Map<String, Any?>>()
        ).apply {
            putAll(extras)
            ensureFailureProgressFields(this, spec)
            withExecutionSummary(this)
        }
    }

    fun completedRun(
        functionId: String,
        spec: Map<String, Any?>,
        auditRunId: String,
        steps: List<Map<String, Any?>>,
        activeSteps: List<Map<String, Any?>>,
        stepResults: List<Map<String, Any?>>,
        normalizedResumeFromStep: Int,
        runtimeResolveSessionId: String,
        runtimeResolveAttempt: Int,
        modelRequired: Boolean,
        delegatedToolUsed: Boolean,
        allowAgentFallback: Boolean,
        failureReason: String?,
    ): LinkedHashMap<String, Any?> {
        val successCount = stepResults.count { it["success"] != false }
        val allSuccess = stepResults.size == activeSteps.size && stepResults.none { it["success"] == false }
        val runtimeResolveRequired = stepResults.any {
            it["runtime_resolve_required"] == true
        }
        val runtimeResolveAvailable = !runtimeResolveRequired ||
            stepResults.any {
                it["runtime_resolve_required"] == true && it["runtime_resolve_available"] == true
            }
        val failedStepIndex = stepResults.firstOrNull { it["success"] == false }?.get("index")
        val lastStepIndex = stepResults.lastOrNull()?.get("index")
        val currentStepIndex = failedStepIndex ?: lastStepIndex
        val currentStepNumber = (currentStepIndex as? Number)?.toInt()?.plus(1)
        return linkedMapOf(
            "success" to allSuccess,
            "run_id" to auditRunId,
            "audit_run_id" to auditRunId,
            "function_id" to (spec["function_id"] ?: functionId),
            "description" to spec["description"]?.toString().orEmpty(),
            "source" to OobFunctionToolHandler.FUNCTION_RUN_SOURCE,
            "run_source" to OobFunctionToolHandler.FUNCTION_RUN_SOURCE,
            "runner" to when {
                runtimeResolveRequired -> "oob_function_runtime_resolve_required"
                delegatedToolUsed -> "oob_function_mixed_runner"
                else -> OobFunctionToolHandler.FUNCTION_DIRECT_RUNNER
            },
            "replay_mode" to "function_direct_run",
            "step_count" to steps.size,
            "active_step_count" to activeSteps.size,
            "success_step_count" to successCount,
            "completed_step_count" to (normalizedResumeFromStep + successCount).coerceAtMost(steps.size),
            "resume_from_step" to normalizedResumeFromStep,
            "runtime_resolve_session_id" to runtimeResolveSessionId.takeIf { it.isNotBlank() },
            "runtime_resolve_attempt" to runtimeResolveAttempt.takeIf { it > 0 },
            "model_used" to false,
            "model_required" to modelRequired,
            "runtime_resolve_required" to runtimeResolveRequired.takeIf { it },
            "runtime_resolve_available" to runtimeResolveAvailable.takeIf { runtimeResolveRequired },
            "delegated_tool_used" to delegatedToolUsed,
            "failed_step_index" to failedStepIndex,
            "current_step_index" to currentStepIndex,
            "current_step_number" to currentStepNumber,
            "error_code" to stepResults.firstOrNull { it["success"] == false }?.get("error_code"),
            "error_message" to failureReason,
            "step_results" to stepResults
        )
    }

    fun withRunnerTiming(
        payload: Map<String, Any?>,
        timing: Map<String, Any?>,
    ): Map<String, Any?> {
        val existingTiming = mapArg(payload["timing"])
        val mergedTiming = linkedMapOf<String, Any?>().apply {
            putAll(existingTiming)
            putAll(timing)
            val phaseMs = linkedMapOf<String, Any?>().apply {
                putAll(mapArg(existingTiming["phase_ms"]))
                putAll(mapArg(timing["phase_ms"]))
            }
            if (phaseMs.isNotEmpty()) put("phase_ms", phaseMs)
        }
        val mergedPayload = linkedMapOf<String, Any?>().apply {
            putAll(payload)
            put("timing", mergedTiming)
        }
        return withExecutionSummary(mergedPayload)
    }

    fun withExecutionSummary(payload: MutableMap<String, Any?>): MutableMap<String, Any?> {
        payload["execution_summary"] = compactExecutionSummary(payload)
        return payload
    }

    class Timing(
        private val startedAtMs: Long,
    ) {
        private val startedAtNanos = System.nanoTime()
        private val phases = linkedMapOf<String, Long>()

        fun <T> measure(phaseName: String, block: () -> T): T {
            val phaseStartedAtNanos = System.nanoTime()
            return try {
                block()
            } finally {
                recordElapsed(phaseName, phaseStartedAtNanos)
            }
        }

        fun recordElapsed(phaseName: String, phaseStartedAtNanos: Long) {
            phases[phaseName] = elapsedMs(phaseStartedAtNanos)
        }

        fun recordSinceStart(phaseName: String, endedAtNanos: Long = System.nanoTime()) {
            phases[phaseName] = ((endedAtNanos - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)
        }

        fun finish(finishedAtMs: Long = System.currentTimeMillis()): Map<String, Any?> {
            val completedPhases = linkedMapOf<String, Long>()
            listOf(
                "materialized_steps_ms",
                "pre_step_loop_ms",
                "step_loop_ms",
                "result_build_ms",
            ).forEach { phaseName ->
                completedPhases[phaseName] = phases[phaseName] ?: 0L
            }
            phases.forEach { (phaseName, durationMs) ->
                completedPhases.putIfAbsent(phaseName, durationMs)
            }
            val durationMs = (finishedAtMs - startedAtMs).coerceAtLeast(0)
            return linkedMapOf(
                "source" to "call_tool_runner",
                "started_at_ms" to startedAtMs,
                "finished_at_ms" to finishedAtMs,
                "duration_ms" to durationMs,
                "runner_duration_ms" to durationMs,
                "phase_ms" to completedPhases,
            )
        }

        private fun elapsedMs(startedAtNanos: Long): Long =
            ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)
    }

    private fun ensureFailureProgressFields(payload: LinkedHashMap<String, Any?>, spec: Map<String, Any?>) {
        val stepResults = listArg(payload["step_results"])
            .mapNotNull { raw -> mapArg(raw).takeIf { it.isNotEmpty() } }
        val failedStepIndex = firstPresentIndex(
            payload["failed_step_index"],
            payload["blocked_step_index"],
            firstFailedStepIndex(stepResults),
        )
        if (payload["failed_step_index"] == null && failedStepIndex != null) {
            payload["failed_step_index"] = failedStepIndex
        }
        val currentStepIndex = firstPresentIndex(
            payload["current_step_index"],
            failedStepIndex,
            payload["blocked_step_index"],
            lastStepIndex(stepResults),
        )
        if (payload["current_step_index"] == null && currentStepIndex != null) {
            payload["current_step_index"] = currentStepIndex
        }
        val currentStepNumber = positiveInt(payload["current_step_number"])
        if (currentStepNumber == null && currentStepIndex != null && currentStepIndex >= 0) {
            payload["current_step_number"] = currentStepIndex + 1
        }
        val existingStepCount = positiveInt(payload["step_count"])
        if (existingStepCount == null) {
            val derivedStepCount = listOf(
                stepResults.size.takeIf { it > 0 },
                stepCountFromSpec(spec).takeIf { it > 0 },
            ).firstOrNull { it != null }
            if (derivedStepCount != null) payload["step_count"] = derivedStepCount
        }
    }

    private fun compactExecutionSummary(payload: Map<String, Any?>): Map<String, Any?> {
        val timing = mapArg(payload["timing"])
        val stepResults = listArg(payload["step_results"])
            .mapNotNull { raw -> mapArg(raw).takeIf { it.isNotEmpty() } }
        val success = payload["success"] == true
        val steps = listOf(
            positiveInt(payload["actions_executed"]),
            positiveInt(payload["completed_step_count"]),
            positiveInt(payload["success_step_count"]),
            stepResults.count { it["success"] != false }.takeIf { it > 0 },
        ).firstOrNull { it != null } ?: 0
        val resolveCalls = listOf(
            positiveInt(payload["resolve_calls"]),
            stepResults.count { step ->
                step["runtime_resolve_executed"] == true ||
                    step["runtime_resolve_applied"] == true ||
                    step["executor"] == "omniflow_runtime_resolve" ||
                    mapArg(step["runtime_resolve"])["success"] == true
            }.takeIf { it > 0 },
        ).firstOrNull { it != null } ?: 0
        val elapsedMs = listOf(
            nonNegativeLong(timing["call_duration_ms"]),
            nonNegativeLong(timing["duration_ms"]),
            nonNegativeLong(timing["runner_duration_ms"]),
            nonNegativeLong(payload["duration_ms"]),
            elapsedFromBounds(payload["started_at_ms"], payload["finished_at_ms"]),
        ).firstOrNull { it != null } ?: 0L
        return linkedMapOf<String, Any?>(
            "success" to success,
            "steps" to steps,
            "resolve_calls" to resolveCalls,
            "model_calls" to (positiveInt(payload["model_calls"]) ?: 0),
            "tokens" to (positiveInt(payload["tokens"]) ?: positiveInt(payload["total_tokens"]) ?: 0),
            "elapsed_ms" to elapsedMs,
            "failure_reason" to payload["error_message"]?.toString()?.takeIf {
                !success && it.isNotBlank()
            },
        ).filterValues { it != null }
    }

    private fun firstFailedStepIndex(stepResults: List<Map<String, Any?>>): Int? =
        stepResults.firstOrNull { it["success"] == false }?.let(::stepIndex)

    private fun lastStepIndex(stepResults: List<Map<String, Any?>>): Int? =
        stepResults.lastOrNull()?.let(::stepIndex)

    private fun stepIndex(step: Map<String, Any?>): Int? =
        firstPresentIndex(step["index"], step["step_index"], step["current_step_index"])

    private fun firstPresentIndex(vararg values: Any?): Int? {
        values.forEach { value ->
            val parsed = nonNegativeInt(value)
            if (parsed != null) return parsed
        }
        return null
    }

    private fun positiveInt(value: Any?): Int? =
        intValue(value)?.takeIf { it > 0 }

    private fun nonNegativeInt(value: Any?): Int? =
        intValue(value)?.takeIf { it >= 0 }

    private fun intValue(value: Any?): Int? =
        when (value) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }

    private fun nonNegativeLong(value: Any?): Long? =
        longValue(value)?.takeIf { it >= 0L }

    private fun longValue(value: Any?): Long? =
        when (value) {
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> null
        }

    private fun elapsedFromBounds(startedAtMs: Any?, finishedAtMs: Any?): Long? {
        val started = nonNegativeLong(startedAtMs) ?: return null
        val finished = nonNegativeLong(finishedAtMs) ?: return null
        return (finished - started).coerceAtLeast(0L)
    }

    private fun stepCountFromSpec(spec: Map<String, Any?>): Int {
        val execution = mapArg(spec["execution"])
        val explicit = positiveInt(execution["step_count"]) ?: positiveInt(spec["step_count"])
        if (explicit != null) return explicit
        return listArg(execution["steps"]).size.takeIf { it > 0 } ?: 0
    }

}
