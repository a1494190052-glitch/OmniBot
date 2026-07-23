package cn.com.omnimind.bot.function

import android.content.Context
import cn.com.omnimind.assists.task.vlmserver.AndroidDeviceOperator
import cn.com.omnimind.assists.task.vlmserver.DeviceOperator
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.bot.agent.tool.handlers.SharedHelper
import cn.com.omnimind.bot.omniflow.OmniFlowPythonRuntime
import cn.com.omnimind.bot.omniflow.omniFlowAndroidHostCall
import cn.com.omnimind.bot.runlog.firstNonBlank
import cn.com.omnimind.bot.runlog.mapArg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicLong

/** Android lifecycle and device boundary for the Python-owned Function runtime. */
class FunctionRun(
    private val context: Context,
    private val helper: SharedHelper = SharedHelper(
        context,
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
        },
    ),
    private val deviceOperator: DeviceOperator = AndroidDeviceOperator(null, context),
    private val frontendSessionController: FunctionFrontendSessionController =
        FunctionFrontendSessionController(helper),
) {
    suspend fun runFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args.orEmpty()
        val functionId = firstNonBlank(request["function_id"])
        if (functionId.isBlank()) return failure("", "FUNCTION_ID_EMPTY", "function_id is required")

        val startedAtMs = System.currentTimeMillis()
        val runId = nextRunId(startedAtMs)
        val executionMode = firstNonBlank(request["execution_mode"]).ifBlank { "foreground" }
        val frontendSession = frontendSessionController.start(
            functionId = functionId,
            runId = runId,
            frontendRunId = firstNonBlank(request["frontend_run_id"]),
            frontendTaskId = firstNonBlank(request["frontend_task_id"]),
            frontendParent = firstNonBlank(request["frontend_parent"]),
        )
        InternalRunLogStore.beginRun(
            context = context,
            runId = runId,
            goal = firstNonBlank(request["goal"], functionId),
            source = FUNCTION_RUN_SOURCE,
            toolName = FUNCTION_DIRECT_RUNNER,
            operationDescription = "Function: $functionId",
            startedAtMs = startedAtMs,
        )

        var actionNumber = 0
        val payload = try {
            val runtimeResult = OmniFlowPythonRuntime.call(
                context = context,
                operation = "run",
                payload = mapOf(
                    "function_id" to functionId,
                    "arguments" to mapArg(request["arguments"]),
                    "run_id" to runId,
                    "execution_mode" to executionMode,
                    "started_at_ms" to startedAtMs,
                ),
                hostCall = omniFlowAndroidHostCall(
                    context = context,
                    deviceOperator = deviceOperator,
                    stopRequested = frontendSession::isStopRequested,
                    onAction = { action ->
                        actionNumber += 1
                        frontendSession.update(
                            "第 $actionNumber 步 ${firstNonBlank(action["tool"], "action")}",
                        )
                    },
                    onStep = { step ->
                        InternalRunLogStore.upsertStep(context, runId, step)
                    },
                ),
            )
            if (frontendSession.isStopRequested()) {
                transportFailure(
                    functionId,
                    runId,
                    startedAtMs,
                    executionMode,
                    "OOB_FUNCTION_STOPPED",
                    "任务已停止",
                )
            } else {
                require(firstNonBlank(runtimeResult["run_id"]) == runId) {
                    "function_run_id_mismatch"
                }
                runtimeResult
            }
        } catch (error: CancellationException) {
            val stopped = transportFailure(
                functionId,
                runId,
                startedAtMs,
                executionMode,
                "OOB_FUNCTION_STOPPED",
                "任务已停止",
            )
            withContext(NonCancellable) { finishRun(stopped, frontendSession) }
            throw error
        } catch (error: Exception) {
            transportFailure(
                functionId,
                runId,
                startedAtMs,
                executionMode,
                "OOB_FUNCTION_RUN_FAILED",
                error.message.orEmpty().ifBlank { error.javaClass.simpleName },
            )
        }
        finishRun(payload, frontendSession)
        return payload
    }

    private suspend fun finishRun(
        payload: Map<String, Any?>,
        frontendSession: FunctionFrontendSessionController.Session,
    ) {
        val success = payload["success"] == true
        InternalRunLogStore.finishRun(
            context = context,
            runId = firstNonBlank(payload["run_id"]),
            success = success,
            doneReason = when {
                success -> "function_completed"
                payload["error_code"] == "OOB_FUNCTION_STOPPED" -> "function_stopped"
                else -> "function_failed"
            },
            errorMessage = firstNonBlank(payload["error_message"]),
            finishedAtMs = (payload["finished_at_ms"] as? Number)?.toLong()
                ?: System.currentTimeMillis(),
            finalStateId = firstNonBlank(mapArg(payload["final_state"])["state_id"]),
        )
        frontendSession.finish(
            helper.localized(if (success) "任务已完成" else "任务执行失败"),
            closeAfterMs = if (success) SUCCESS_VISIBLE_MS else FAILURE_VISIBLE_MS,
        )
    }

    private fun transportFailure(
        functionId: String,
        runId: String,
        startedAtMs: Long,
        executionMode: String,
        errorCode: String,
        errorMessage: String,
    ): Map<String, Any?> {
        val finishedAtMs = System.currentTimeMillis()
        val steps = InternalRunLogStore.getRun(context, runId)?.steps.orEmpty()
        val completedStepCount = steps.size
        return linkedMapOf(
            "success" to false,
            "status" to "failed",
            "run_id" to runId,
            "function_id" to functionId,
            "source" to FUNCTION_RUN_SOURCE,
            "runner" to FUNCTION_DIRECT_RUNNER,
            "execution_mode" to executionMode,
            "step_count" to completedStepCount,
            "success_step_count" to steps.count { mapArg(it["result"])["success"] == true },
            "completed_step_count" to completedStepCount,
            "actions_executed" to completedStepCount,
            "steps" to steps,
            "started_at_ms" to startedAtMs,
            "finished_at_ms" to finishedAtMs,
            "duration_ms" to (finishedAtMs - startedAtMs).coerceAtLeast(0L),
            "error_code" to errorCode,
            "error_message" to errorMessage,
        )
    }

    private fun failure(functionId: String, code: String, message: String): Map<String, Any?> =
        linkedMapOf(
            "success" to false,
            "status" to "failed",
            "function_id" to functionId,
            "error_code" to code,
            "error_message" to message,
        )

    companion object {
        const val FUNCTION_DIRECT_RUNNER = "omniflow_python"
        const val FUNCTION_RUN_SOURCE = "oob_function_replay"
        private const val SUCCESS_VISIBLE_MS = 900L
        private const val FAILURE_VISIBLE_MS = 2500L
        private val RUN_SEQUENCE = AtomicLong(0)

        private fun nextRunId(startedAtMs: Long): String =
            "function_run_${startedAtMs}_${RUN_SEQUENCE.incrementAndGet()}"
    }
}
