package cn.com.omnimind.bot.function

import android.content.Context
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.runlog.RunLogStepRecord
import cn.com.omnimind.bot.runlog.firstNonBlank
import cn.com.omnimind.bot.runlog.intArg
import cn.com.omnimind.bot.runlog.listArg
import cn.com.omnimind.bot.runlog.mapArg

/** Persists the canonical trace returned by the Python Function runtime. */
object FunctionRunLogRecorder {
    suspend fun record(
        context: Context,
        functionId: String,
        functionSpec: Map<String, Any?> = emptyMap(),
        runPayload: Map<String, Any?>,
    ): Map<String, Any?> {
        val runId = firstNonBlank(runPayload["run_id"])
        if (runId.isBlank()) {
            return error(runId, "OOB_FUNCTION_RUN_ID_EMPTY", "Function run payload has no run_id")
        }
        return runCatching {
            val steps = listArg(runPayload["steps"])
                .mapNotNull { mapArg(it).takeIf(Map<String, Any?>::isNotEmpty) }
            val startedAtMs = number(runPayload["started_at_ms"])
            val finishedAtMs = number(runPayload["finished_at_ms"])
            val success = runPayload["success"] == true
            val functionName = firstNonBlank(functionSpec["name"], runPayload["name"], functionId)
            val description = firstNonBlank(
                runPayload["description"],
                functionSpec["description"],
                functionName,
            )
            val runner = firstNonBlank(runPayload["runner"], FunctionRun.FUNCTION_DIRECT_RUNNER)
            InternalRunLogStore.beginRun(
                context = context,
                runId = runId,
                goal = description,
                source = "oob_function_execution",
                toolName = runner,
                operationDescription = "Function: $functionName",
                startedAtMs = startedAtMs,
            )
            steps.forEach { step ->
                InternalRunLogStore.upsertRecordedStep(
                    context = context,
                    runId = runId,
                    record = RunLogStepRecord(step = step, states = emptyList()),
                )
            }
            InternalRunLogStore.updateDiagnostics(
                context = context,
                runId = runId,
                diagnostics = mapOf(
                    "function_run" to linkedMapOf<String, Any?>(
                        "function_id" to functionId,
                        "function_name" to functionName,
                        "runner" to runner,
                        "step_count" to intArg(runPayload["step_count"], defaultValue = steps.size),
                        "executed_step_count" to steps.size,
                        "success_step_count" to steps.count {
                            mapArg(it["result"])["success"] == true
                        },
                        "duration_ms" to number(runPayload["duration_ms"]),
                        "runtime_source" to "omniflow_python",
                    ).filterValues { it != null },
                ),
            )
            InternalRunLogStore.finishRun(
                context = context,
                runId = runId,
                success = success,
                doneReason = if (success) "function_completed" else "function_failed",
                errorMessage = firstNonBlank(runPayload["error_message"]),
                finishedAtMs = finishedAtMs,
            )
            linkedMapOf(
                "success" to true,
                "run_id" to runId,
                "step_count" to steps.size,
                "status" to if (success) "succeeded" else "failed",
            )
        }.getOrElse { throwable ->
            error(
                runId,
                "OOB_FUNCTION_RUN_LOG_RECORD_FAILED",
                throwable.message.orEmpty(),
            )
        }
    }

    private fun number(vararg values: Any?): Long {
        values.forEach { value ->
            when (value) {
                is Number -> return value.toLong()
                is String -> value.trim().toLongOrNull()?.let { return it }
            }
        }
        return 0L
    }

    private fun error(runId: String, code: String, message: String): Map<String, Any?> =
        linkedMapOf(
            "success" to false,
            "run_id" to runId,
            "error_code" to code,
            "error_message" to message,
        )
}
