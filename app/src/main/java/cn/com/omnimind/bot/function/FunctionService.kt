package cn.com.omnimind.bot.function

import android.content.Context
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.bot.omniflow.OmniFlow
import cn.com.omnimind.bot.runlog.firstNonBlank
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/** Thin Android entry for Python-owned Function management. */
class FunctionService(
    private val context: Context,
) {
    private val channel by lazy { FunctionChannel(context, this) }

    suspend fun executeTool(
        name: String?,
        args: Map<String, Any?>?,
    ): Map<String, Any?> = runCatching {
        OmniFlow.call(
            context = context,
            operation = "function_tool",
            payload = mapOf(
                "tool" to name.orEmpty(),
                "args" to args.orEmpty(),
            ),
        )
    }.getOrElse { error ->
        errorPayload(
            "OOB_OMNIFLOW_FUNCTION_FAILED",
            error.message ?: "OmniFlow Function operation failed",
            firstNonBlank(args.orEmpty()["function_id"]),
        )
    }

    fun getRunLogState(args: Map<String, Any?>): Map<String, Any?> {
        val stateId = firstNonBlank(args["state_id"])
        if (stateId.isBlank()) return errorPayload("STATE_ID_EMPTY", "state_id is required")
        return InternalRunLogStore.statePayload(context, stateId).ifEmpty {
            errorPayload("STATE_NOT_FOUND", "RunLog state not found")
        }
    }

    fun handleChannelMethod(call: MethodCall, result: MethodChannel.Result) {
        channel.handle(call, result)
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

    companion object {
        private val channelMethods = setOf(
            "getInternalRunLogs",
            "getInternalRunLogTimeline",
            "getInternalRunLogState",
            "convertInternalRunLogToFunction",
            "startHumanTrajectoryLearning",
            "pauseHumanTrajectoryLearning",
            "resumeHumanTrajectoryLearning",
            "getHumanTrajectoryLearningStatus",
            "listFunctions",
            "getFunction",
            "registerFunction",
            "updateFunction",
            "deleteFunction",
            "runFunction",
        )

        fun isChannelMethod(method: String): Boolean = method in channelMethods
    }
}
