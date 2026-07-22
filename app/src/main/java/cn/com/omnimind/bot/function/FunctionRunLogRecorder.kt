package cn.com.omnimind.bot.function

import android.content.Context
import cn.com.omnimind.baselib.runlog.CanonicalActionConverter
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.runlog.RunLogStepRecord
import cn.com.omnimind.bot.function.FunctionJson.firstNonBlank
import cn.com.omnimind.bot.function.FunctionJson.intArg
import cn.com.omnimind.bot.function.FunctionJson.listArg
import cn.com.omnimind.bot.function.FunctionJson.longArg
import cn.com.omnimind.bot.function.FunctionJson.mapArg

/**
 * Persists an executed Function payload as an InternalRunLog timeline.
 *
 * Function execution already returns structured `step_results`; this adapter
 * records them under the same `run_id` that the Function lifecycle stores as
 * `last_run`, so the UI can reopen the previous execution as a normal RunLog.
 */
object FunctionRunLogRecorder {
    fun record(
        context: Context,
        functionId: String,
        functionSpec: Map<String, Any?> = emptyMap(),
        runPayload: Map<String, Any?>,
    ): Map<String, Any?> {
        val runId = firstNonBlank(runPayload["run_id"])
        if (runId.isEmpty()) {
            return linkedMapOf(
                "success" to false,
                "error_code" to "OOB_FUNCTION_RUN_ID_EMPTY",
                "error_message" to "Function run payload does not include run_id"
            )
        }

        return runCatching {
            val timing = mapArg(runPayload["timing"])
            val startedAtMs = longArg(
                runPayload["started_at_ms"],
                timing["started_at_ms"],
                defaultValue = 0L,
            )
            val finishedAtMs = longArg(
                runPayload["finished_at_ms"],
                timing["finished_at_ms"],
                defaultValue = 0L,
            )
            val stepResults = listArg(runPayload["step_results"])
            val records = stepResults.mapIndexedNotNull { index, raw ->
                val step = mapArg(raw)
                if (step.isEmpty()) null else canonicalStepFromResult(
                    runId = runId,
                    functionId = functionId,
                    step = step,
                    fallbackIndex = index,
                )
            }
            val success = boolValue(runPayload["success"])
                ?: records.none { record ->
                    boolValue(mapArg(record.step["result"])["success"]) == false
                }
            val errorMessage = firstNonBlank(runPayload["error_message"])
            val functionName = firstNonBlank(
                functionSpec["name"],
                runPayload["name"],
                functionId,
            )
            val description = firstNonBlank(
                runPayload["description"],
                functionSpec["description"],
                functionName,
            )
            val runner = firstNonBlank(
                runPayload["runner"],
                "oob_function",
            )

            InternalRunLogStore.beginRun(
                context = context,
                runId = runId,
                goal = description,
                source = "oob_function_execution",
                toolName = runner,
                operationDescription = "Function: $functionName",
                startedAtMs = startedAtMs,
            )
            records.forEach { record ->
                InternalRunLogStore.upsertRecordedStep(
                    context = context,
                    runId = runId,
                    stepId = "",
                    record = record,
                )
            }
            InternalRunLogStore.updateDiagnostics(
                context = context,
                runId = runId,
                diagnostics = linkedMapOf(
                    "function_run" to linkedMapOf(
                        "function_id" to functionId,
                        "function_name" to functionName,
                        "runner" to runner,
                        "step_count" to intArg(
                            runPayload["step_count"],
                            defaultValue = records.size,
                        ),
                        "success_step_count" to intArg(
                            runPayload["success_step_count"],
                            defaultValue = records.count { record ->
                                boolValue(mapArg(record.step["result"])["success"]) != false
                            },
                        ),
                        "completed_step_count" to intArg(
                            runPayload["completed_step_count"],
                            defaultValue = records.size,
                        ),
                        "audit_run_id" to firstNonBlank(runPayload["audit_run_id"], runId),
                        "timing" to timing.takeIf { it.isNotEmpty() },
                    ).filterValues { it != null }
                )
            )
            InternalRunLogStore.finishRun(
                context = context,
                runId = runId,
                success = success,
                doneReason = if (success) "function_completed" else "function_failed",
                errorMessage = errorMessage,
                finishedAtMs = finishedAtMs,
            )
            linkedMapOf(
                "success" to true,
                "run_id" to runId,
                "step_count" to records.size,
                "status" to if (success) "succeeded" else "failed",
            )
        }.getOrElse { error ->
            linkedMapOf(
                "success" to false,
                "run_id" to runId,
                "error_code" to "OOB_FUNCTION_RUN_LOG_RECORD_FAILED",
                "error_message" to error.message.orEmpty(),
            )
        }
    }

    internal fun canonicalStepFromResult(
        runId: String,
        functionId: String,
        step: Map<String, Any?>,
        fallbackIndex: Int,
    ): RunLogStepRecord {
        val toolName = firstNonBlank(step["tool"])
        require(toolName.isNotEmpty()) { "function_step_tool_required" }
        val title = firstNonBlank(
            step["title"],
            step["summary"],
            step["description"],
            step["error_message"],
            toolName,
        )
        val stepIndex = intArg(
            step["step_index"],
            defaultValue = fallbackIndex,
        )
        val startedAtMs = longArg(
            step["started_at_ms"],
            defaultValue = 0L,
        )
        val finishedAtMs = longArg(
            step["finished_at_ms"],
            defaultValue = 0L,
        )
        val durationMs = longArg(
            step["duration_ms"],
            defaultValue = 0L,
        ).takeIf { it > 0L }
            ?: (finishedAtMs - startedAtMs).takeIf { startedAtMs > 0L && finishedAtMs >= startedAtMs }
        val success = boolValue(step["success"]) ?: true
        val executor = firstNonBlank(step["executor"])
        val recallKind = firstNonBlank(
            step["compile_kind"],
            if (FunctionSchema.isFunctionExecutor(executor)) "hit" else executor,
        )
        val args = mapArg(step["args"])
        val canonicalAction = CanonicalActionConverter.convert(
            tool = toolName,
            args = args,
            replayableOnly = true,
        )
        val beforeState = mapArg(step["before_state"])
        val afterState = mapArg(step["after_state"])
        val beforeStateId = firstNonBlank(
            beforeState["state_id"],
            "${runId}_step_${stepIndex}_before",
        )
        val afterStateId = firstNonBlank(
            afterState["state_id"],
            beforeStateId,
        )
        val error = firstNonBlank(step["error_message"], step["error_code"])
            .takeIf { it.isNotEmpty() }
        val result = linkedMapOf<String, Any?>(
            "success" to success,
            "error" to error,
        ).filterValues { it != null }

        val canonicalStep = linkedMapOf<String, Any?>().apply {
            put("step_index", stepIndex)
            put("before_state_id", beforeStateId)
            put(
                "action",
                canonicalAction,
            )
            put("result", result)
            put("after_state_id", afterStateId)
            put(
                "diagnostics",
                linkedMapOf<String, Any?>(
                    "function_id" to functionId,
                    "source" to "oob_function_execution",
                    "runner" to firstNonBlank(step["executor"], "function"),
                    "summary" to title.takeIf { it.isNotEmpty() },
                    "error_code" to step["error_code"],
                    "called_function_run_id" to step["called_function_run_id"],
                    "details" to mapArg(step["diagnostics"]).takeIf { it.isNotEmpty() },
                    "transfer" to mapArg(step["transfer"]).takeIf { it.isNotEmpty() },
                    "compile_kind" to recallKind.takeIf { it.isNotEmpty() },
                    "source_state_id" to firstNonBlank(step["source_state_id"])
                        .takeIf { it.isNotEmpty() },
                    "duration_ms" to durationMs,
                    "started_at_ms" to startedAtMs.takeIf { it > 0L },
                    "finished_at_ms" to finishedAtMs.takeIf { it > 0L },
                ).filterValues { it != null }
            )
        }
        return RunLogStepRecord(
            step = canonicalStep,
            states = listOf(beforeState, afterState).filter { it.isNotEmpty() },
        )
    }

    private fun boolValue(value: Any?): Boolean? =
        when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> when (value.trim().lowercase()) {
                "true", "1", "yes", "y", "on", "success" -> true
                "false", "0", "no", "n", "off", "error", "failed", "failure" -> false
                else -> null
            }
            else -> null
        }

}
