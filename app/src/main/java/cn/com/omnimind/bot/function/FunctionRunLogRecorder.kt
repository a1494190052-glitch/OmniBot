package cn.com.omnimind.bot.function

import android.content.Context
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
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
        val runId = firstNonBlank(
            runPayload["run_id"],
            runPayload["runId"],
            runPayload["audit_run_id"],
            runPayload["auditRunId"],
        )
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
                runPayload["startedAtMs"],
                timing["started_at_ms"],
                timing["startedAtMs"],
                timing["runner_started_at_ms"],
                defaultValue = 0L,
            )
            val finishedAtMs = longArg(
                runPayload["finished_at_ms"],
                runPayload["finishedAtMs"],
                timing["finished_at_ms"],
                timing["finishedAtMs"],
                timing["runner_finished_at_ms"],
                defaultValue = 0L,
            )
            val stepResults = listArg(runPayload["step_results"])
            val cards = stepResults.mapIndexedNotNull { index, raw ->
                val step = mapArg(raw)
                if (step.isEmpty()) null else cardFromStep(
                    runId = runId,
                    functionId = functionId,
                    step = step,
                    fallbackIndex = index,
                )
            }
            val success = boolValue(runPayload["success"])
                ?: cards.none { boolValue(it["success"]) == false }
            val errorMessage = firstNonBlank(
                runPayload["error_message"],
                runPayload["errorMessage"],
            )
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
            cards.forEach { card ->
                InternalRunLogStore.upsertCard(
                    context = context,
                    runId = runId,
                    cardId = card["card_id"]?.toString().orEmpty(),
                    card = card,
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
                            runPayload["stepCount"],
                            defaultValue = cards.size,
                        ),
                        "success_step_count" to intArg(
                            runPayload["success_step_count"],
                            runPayload["successStepCount"],
                            defaultValue = cards.count { boolValue(it["success"]) != false },
                        ),
                        "completed_step_count" to intArg(
                            runPayload["completed_step_count"],
                            runPayload["completedStepCount"],
                            defaultValue = cards.size,
                        ),
                        "audit_run_id" to firstNonBlank(
                            runPayload["audit_run_id"],
                            runPayload["auditRunId"],
                            runId,
                        ),
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
                "card_count" to cards.size,
                "run_finished" to true,
                "run_success" to success,
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

    private fun cardFromStep(
        runId: String,
        functionId: String,
        step: Map<String, Any?>,
        fallbackIndex: Int,
    ): Map<String, Any?> {
        val stepId = firstNonBlank(
            step["step_id"],
            step["stepId"],
            step["id"],
            "step_${fallbackIndex + 1}",
        )
        val cardId = firstNonBlank(
            step["card_id"],
            step["cardId"],
            step["tool_call_id"],
            step["toolCallId"],
            "${runId}_$stepId",
        )
        val toolName = firstNonBlank(
            step["tool_name"],
            step["toolName"],
            step["tool"],
            step["action_type"],
            step["actionType"],
            step["executor"],
        )
        val title = firstNonBlank(
            step["title"],
            step["summary"],
            step["description"],
            step["error_message"],
            toolName,
        )
        val stepIndex = intArg(
            step["step_index"],
            step["stepIndex"],
            step["index"],
            defaultValue = fallbackIndex,
        )
        val startedAtMs = longArg(
            step["started_at_ms"],
            step["startedAtMs"],
            defaultValue = 0L,
        )
        val finishedAtMs = longArg(
            step["finished_at_ms"],
            step["finishedAtMs"],
            defaultValue = 0L,
        )
        val durationMs = longArg(
            step["duration_ms"],
            step["durationMs"],
            step["elapsed_ms"],
            step["elapsedMs"],
            defaultValue = 0L,
        ).takeIf { it > 0L }
            ?: (finishedAtMs - startedAtMs).takeIf { startedAtMs > 0L && finishedAtMs >= startedAtMs }
        val success = boolValue(step["success"]) ?: true
        val executor = firstNonBlank(step["executor"])
        val recallKind = firstNonBlank(
            step["recall_kind"],
            step["recallKind"],
            step["compile_kind"],
            step["compileKind"],
            if (FunctionSchema.isFunctionExecutor(executor)) "hit" else executor,
        )
        val args = firstPresent(
            step["arguments"],
            step["args"],
            step["params"],
            step["input"],
        )
        val result = linkedMapOf<String, Any?>(
            "success" to success,
            "summary" to title,
            "error_code" to firstPresent(step["error_code"], step["errorCode"]),
            "error_message" to firstPresent(step["error_message"], step["errorMessage"]),
            "called_function_run_id" to firstPresent(
                step["called_function_run_id"],
                step["calledFunctionRunId"],
                step["nested_run_id"],
                step["nestedRunId"],
            ),
            "step_results" to firstPresent(step["step_results"], step["stepResults"]),
        ).filterValues { it != null }

        return linkedMapOf<String, Any?>().apply {
            putAll(step)
            put("card_id", cardId)
            put("tool_call_id", firstNonBlank(step["tool_call_id"], step["toolCallId"], cardId))
            put("step_id", stepId)
            put("step_index", stepIndex)
            put("function_id", functionId)
            put("source", "oob_function_execution")
            put("run_source", "oob_function_execution")
            if (toolName.isNotEmpty()) put("tool_name", toolName)
            if (title.isNotEmpty()) {
                put("title", title)
                put("summary", title)
            }
            if (recallKind.isNotEmpty()) {
                put("recall_kind", recallKind)
            }
            put("success", success)
            put("status", if (success) "success" else "error")
            if (startedAtMs > 0L) put("started_at_ms", startedAtMs)
            if (finishedAtMs > 0L) put("finished_at_ms", finishedAtMs)
            if (durationMs != null) put("duration_ms", durationMs)
            put(
                "header",
                linkedMapOf<String, Any?>(
                    "step_index" to stepIndex,
                    "title" to title.takeIf { it.isNotEmpty() },
                    "tool_name" to toolName.takeIf { it.isNotEmpty() },
                    "recall_kind" to recallKind.takeIf { it.isNotEmpty() },
                    "success" to success,
                    "status" to if (success) "success" else "error",
                    "duration_ms" to durationMs,
                ).filterValues { it != null }
            )
            put(
                "tool_call",
                linkedMapOf<String, Any?>(
                    "id" to cardId,
                    "name" to toolName.takeIf { it.isNotEmpty() },
                    "arguments" to args,
                ).filterValues { it != null }
            )
            if (result.isNotEmpty()) {
                putIfAbsent("result", result)
            }
        }
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

    private fun firstPresent(vararg values: Any?): Any? =
        values.firstOrNull { value ->
            when (value) {
                null -> false
                is String -> value.trim().isNotEmpty()
                is Map<*, *> -> value.isNotEmpty()
                is Iterable<*> -> value.any()
                is Array<*> -> value.isNotEmpty()
                else -> true
            }
        }
}
