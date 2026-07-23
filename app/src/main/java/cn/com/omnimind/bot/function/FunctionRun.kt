package cn.com.omnimind.bot.function

import android.content.Context
import cn.com.omnimind.assists.task.vlmserver.AndroidDeviceOperator
import cn.com.omnimind.assists.task.vlmserver.DeviceOperator
import cn.com.omnimind.bot.agent.tool.handlers.SharedHelper
import cn.com.omnimind.bot.omniflow.OmniFlowPythonRuntime
import cn.com.omnimind.bot.omniflow.omniFlowAndroidHostCall
import cn.com.omnimind.bot.runlog.firstNonBlank
import cn.com.omnimind.bot.runlog.intArg
import cn.com.omnimind.bot.runlog.listArg
import cn.com.omnimind.bot.runlog.mapArg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicLong

/** Thin Android adapter around the Python-owned Function runtime. */
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

        val spec = FunctionService(context).getFunction(mapOf("function_id" to functionId))
        if (spec["schema_version"] != "omniflow.function.v2") return spec

        val arguments = mapArg(request["arguments"])
        val expectedSteps = listArg(spec["steps"]).size
        val startedAtMs = System.currentTimeMillis()
        val runId = nextRunId(startedAtMs)
        val trace = mutableListOf<Map<String, Any?>>()
        var actionNumber = 0
        val frontendSession = frontendSessionController.start(
            functionId = functionId,
            spec = spec,
            stepCount = expectedSteps,
            toolHandle = null,
            callStack = emptyList(),
            fallbackRunIdProvider = { runId },
            frontendRunId = firstNonBlank(request["frontend_run_id"]),
            frontendTaskId = firstNonBlank(request["frontend_task_id"]),
            frontendParent = firstNonBlank(request["frontend_parent"]),
        )
        var payload: Map<String, Any?>
        try {
            val runtime = OmniFlowPythonRuntime.call(
                context = context,
                operation = "run",
                payload = mapOf(
                    "function_id" to functionId,
                    "arguments" to arguments,
                ),
                hostCall = omniFlowAndroidHostCall(
                    context = context,
                    deviceOperator = deviceOperator,
                    stopRequested = { frontendSession?.isStopRequested() == true },
                    onAction = { action ->
                        actionNumber += 1
                        if (frontendSession?.isStopRequested() != true) {
                            val tool = firstNonBlank(action["tool"], "action")
                            frontendSession?.update(
                                "第 ${actionNumber.coerceAtMost(expectedSteps)}/$expectedSteps 步 $tool",
                            )
                        }
                    },
                    onStep = { step ->
                        trace += step
                    },
                ),
            )
            val returnedTrace = listArg(mapArg(runtime["detail"])["trace"])
                .mapNotNull { mapArg(it).takeIf(Map<String, Any?>::isNotEmpty) }
            if (returnedTrace.isNotEmpty()) {
                trace.clear()
                trace.addAll(returnedTrace)
            }
            payload = resultPayload(
                functionId = functionId,
                spec = spec,
                runtime = runtime,
                trace = trace,
                runId = runId,
                startedAtMs = startedAtMs,
                executionMode = firstNonBlank(request["execution_mode"]).ifBlank { "foreground" },
                stopped = frontendSession?.isStopRequested() == true,
            )
        } catch (error: CancellationException) {
            payload = resultPayload(
                functionId = functionId,
                spec = spec,
                runtime = mapOf("success" to false, "error" to "cancelled"),
                trace = trace,
                runId = runId,
                startedAtMs = startedAtMs,
                executionMode = firstNonBlank(request["execution_mode"]).ifBlank { "foreground" },
                stopped = true,
            )
            withContext(NonCancellable) {
                finishRun(functionId, spec, payload, frontendSession)
            }
            throw error
        } catch (error: Exception) {
            payload = resultPayload(
                functionId = functionId,
                spec = spec,
                runtime = mapOf(
                    "success" to false,
                    "error" to error.message.orEmpty().ifBlank { error.javaClass.simpleName },
                ),
                trace = trace,
                runId = runId,
                startedAtMs = startedAtMs,
                executionMode = firstNonBlank(request["execution_mode"]).ifBlank { "foreground" },
                stopped = frontendSession?.isStopRequested() == true,
            )
        }
        finishRun(functionId, spec, payload, frontendSession)
        return payload
    }

    private suspend fun finishRun(
        functionId: String,
        spec: Map<String, Any?>,
        payload: Map<String, Any?>,
        frontendSession: FunctionFrontendSessionController.Session?,
    ) {
        FunctionRunLogRecorder.record(
            context = context,
            functionId = functionId,
            functionSpec = spec,
            runPayload = payload,
        )
        val success = payload["success"] == true
        frontendSession?.finish(
            helper.localized(if (success) "任务已完成" else "任务执行失败"),
            closeAfterMs = if (success) SUCCESS_VISIBLE_MS else FAILURE_VISIBLE_MS,
        )
    }

    private fun resultPayload(
        functionId: String,
        spec: Map<String, Any?>,
        runtime: Map<String, Any?>,
        trace: List<Map<String, Any?>>,
        runId: String,
        startedAtMs: Long,
        executionMode: String,
        stopped: Boolean,
    ): Map<String, Any?> {
        val finishedAtMs = System.currentTimeMillis()
        val durationMs = (finishedAtMs - startedAtMs).coerceAtLeast(0L)
        val success = runtime["success"] == true && !stopped
        val successfulSteps = trace.count { mapArg(it["result"])["success"] == true }
        val failedStepIndex = trace.indexOfFirst { mapArg(it["result"])["success"] != true }
            .takeIf { it >= 0 }
        val runtimeError = firstNonBlank(runtime["error"])
        val errorCode = when {
            success -> null
            stopped -> "OOB_FUNCTION_STOPPED"
            runtimeError.startsWith("function_arguments_invalid:missing:") ->
                "OOB_FUNCTION_ARGUMENTS_MISSING"
            else -> "OOB_FUNCTION_RUN_FAILED"
        }
        val errorMessage = when {
            success -> null
            stopped -> "任务已停止"
            else -> runtimeError.ifBlank { "Function execution failed" }
        }
        val actionsExecuted = intArg(runtime["actions_executed"], defaultValue = successfulSteps)
        return linkedMapOf<String, Any?>(
            "success" to success,
            "status" to if (success) "succeeded" else "failed",
            "run_id" to runId,
            "function_id" to functionId,
            "name" to spec["name"],
            "description" to spec["description"],
            "source" to FUNCTION_RUN_SOURCE,
            "runner" to FUNCTION_DIRECT_RUNNER,
            "execution_mode" to executionMode,
            "step_count" to listArg(spec["steps"]).size,
            "success_step_count" to successfulSteps,
            "completed_step_count" to trace.size,
            "actions_executed" to actionsExecuted,
            "steps" to trace,
            "failed_step_index" to failedStepIndex,
            "current_step_index" to (failedStepIndex ?: trace.lastIndex.takeIf { it >= 0 }),
            "current_step_number" to (failedStepIndex ?: trace.lastIndex.takeIf { it >= 0 })
                ?.plus(1),
            "started_at_ms" to startedAtMs,
            "finished_at_ms" to finishedAtMs,
            "duration_ms" to durationMs,
            "error_code" to errorCode,
            "error_message" to errorMessage,
            "missing_required_arguments" to runtimeError
                .removePrefix("function_arguments_invalid:missing:")
                .split(',')
                .filter(String::isNotBlank)
                .takeIf { runtimeError.startsWith("function_arguments_invalid:missing:") },
        ).filterValues { it != null }
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
