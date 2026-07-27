package cn.com.omnimind.bot.function

import android.content.Context
import cn.com.omnimind.bot.omniflow.OmniFlow
import cn.com.omnimind.bot.runlog.firstNonBlank
import cn.com.omnimind.bot.runlog.mapArg
import java.util.concurrent.atomic.AtomicLong

/** Android lifecycle and device boundary for the Python-owned Function runtime. */
class FunctionRun(
    private val context: Context,
) {
    suspend fun runFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args.orEmpty()
        val functionId = firstNonBlank(request["function_id"])
        if (functionId.isBlank()) return failure("", "FUNCTION_ID_EMPTY", "function_id is required")

        val startedAtMs = System.currentTimeMillis()
        val runId = nextRunId(startedAtMs)
        val executionMode = firstNonBlank(request["execution_mode"]).ifBlank { "foreground" }
        return OmniFlow.run(
            context = context,
            request = OmniFlow.Run(
                id = runId,
                goal = firstNonBlank(request["goal"], functionId),
                source = FUNCTION_RUN_SOURCE,
                toolName = FUNCTION_DIRECT_RUNNER,
                input = mapOf(
                    "function_id" to functionId,
                    "arguments" to mapArg(request["arguments"]),
                    "execution_mode" to executionMode,
                    "started_at_ms" to startedAtMs,
                ),
                title = firstNonBlank(request["goal"]).ifBlank {
                    "复用指令：${functionLabel(functionId)}"
                },
                operationDescription = "Function: $functionId",
                startedAtMs = startedAtMs,
                cancelledDoneReason = "function_stopped",
                stoppedErrorCode = "OOB_FUNCTION_STOPPED",
                failedErrorCode = "OOB_FUNCTION_RUN_FAILED",
            ),
        ).payload
    }

    private fun functionLabel(functionId: String): String =
        functionId.replace(Regex("[_\\s-]+"), " ").trim().take(32).ifBlank { "复用指令" }

    private fun failure(functionId: String, code: String, message: String): Map<String, Any?> =
        linkedMapOf(
            "success" to false,
            "status" to "failed",
            "function_id" to functionId,
            "source" to FUNCTION_RUN_SOURCE,
            "runner" to FUNCTION_DIRECT_RUNNER,
            "error_code" to code,
            "error_message" to message,
        )

    companion object {
        const val FUNCTION_DIRECT_RUNNER = "omniflow_python"
        const val FUNCTION_RUN_SOURCE = "oob_function_replay"
        private val RUN_SEQUENCE = AtomicLong(0)

        private fun nextRunId(startedAtMs: Long): String =
            "function_run_${startedAtMs}_${RUN_SEQUENCE.incrementAndGet()}"
    }
}
