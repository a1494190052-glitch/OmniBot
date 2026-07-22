package cn.com.omnimind.bot.omniflow

import android.content.Context
import android.os.SystemClock
import cn.com.omnimind.assists.task.vlmserver.DeviceOperator
import cn.com.omnimind.assists.task.vlmserver.OperationResult
import cn.com.omnimind.assists.task.vlmserver.State
import cn.com.omnimind.assists.task.vlmserver.StateDisplay
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.runlog.firstNonBlank
import cn.com.omnimind.bot.runlog.mapArg
import com.google.gson.Gson

internal class OmniFlowReplayAdapter(
    private val controlCall: suspend (
        Map<String, Any?>,
        (() -> Boolean)?,
    ) -> Map<String, Any?>,
    private val loadState: (String) -> Map<String, Any?> = { emptyMap() },
    private val captureTarget: suspend () -> Map<String, Any?> = { emptyMap() },
) {
    constructor(
        context: Context,
        deviceOperator: DeviceOperator,
    ) : this(
        controlCall = { payload, stopRequested ->
            OmniFlowPythonRuntime.call(
                context = context.applicationContext,
                operation = "control_act",
                payload = payload,
                hostCall = omniFlowAndroidHostCall(context, deviceOperator, stopRequested),
            )
        },
        loadState = { stateId ->
            InternalRunLogStore.statePayload(context.applicationContext, stateId)
        },
        captureTarget = {
            OmniFlowTransferScreenshotStore.capture(
                context.applicationContext,
                deviceOperator,
            )
        },
    )

    suspend fun controlAct(
        action: String,
        args: Map<String, Any?>,
        functionId: String = "",
        sourceStateId: String = "",
        rules: List<Map<String, Any?>> = emptyList(),
        state: State? = null,
        stopRequested: (() -> Boolean)? = null,
    ): OperationResult {
        val startedAt = SystemClock.elapsedRealtime()
        return runCatching {
            val response = controlCall(
                linkedMapOf<String, Any?>(
                    "action" to canonicalAction(action, args),
                    "function_id" to functionId.takeIf(String::isNotBlank),
                    "source_state_id" to sourceStateId.takeIf(String::isNotBlank),
                    "checker_rules" to rules.takeIf(List<Map<String, Any?>>::isNotEmpty),
                    "state" to state?.toBridgeState(),
                ).filterValues { it != null },
                stopRequested,
            )
            val transfer = mapArg(response["transfer"]).let { value ->
                if (value.isNotEmpty() && sourceStateId.isNotBlank()) {
                    attachImages(value, sourceStateId)
                } else {
                    value
                }
            }
            operationResult(response, action, startedAt, transfer)
        }.getOrElse { error ->
            OmniLog.w(TAG, "control_act failed: ${error.message}")
            OperationResult(
                success = false,
                message = error.message.orEmpty().ifBlank { "python_control_error" },
                diagnostics = linkedMapOf(
                    "runtime_source" to "omniflow_python",
                    "operation" to "control_act",
                    "duration_ms" to elapsed(startedAt).toString(),
                    "error_type" to error.javaClass.name,
                    "error_message" to error.message.orEmpty(),
                    "local_action_error_code" to "OOB_OMNIFLOW_CONTROL_FAILED",
                ),
            )
        }
    }

    private fun operationResult(
        response: Map<String, Any?>,
        expectedAction: String,
        startedAt: Long,
        transfer: Map<String, Any?>,
    ): OperationResult {
        val returnedAction = mapArg(response["action"])
        val result = mapArg(response["result"])
        val extra = mapArg(result["extra"])
        val success = response["success"] == true
        val error = firstNonBlank(response["error"], result["error"])
        val message = if (success) {
            firstNonBlank(extra["message"]).ifBlank { "Action completed" }
        } else {
            error.ifBlank { "omniflow_control_failed" }
        }
        return OperationResult(
            success = success,
            message = message,
            beforeState = state(response["before_state"]),
            afterState = state(response["after_state"]),
            diagnostics = linkedMapOf(
                "runtime_source" to "omniflow_python",
                "operation" to "control_act",
                "duration_ms" to elapsed(startedAt).toString(),
                "expected_action" to expectedAction,
                "executed_action" to firstNonBlank(returnedAction["tool"]),
                "error" to error,
                "local_action_error_code" to if (success) "" else "OOB_OMNIFLOW_CONTROL_FAILED",
                "transfer" to transfer.takeIf(Map<String, Any?>::isNotEmpty)?.let(Gson()::toJson).orEmpty(),
            ).filterValues(String::isNotBlank),
        )
    }

    private fun state(value: Any?): State? {
        val state = mapArg(value)
        val stateId = firstNonBlank(state["state_id"])
        if (stateId.isBlank()) return null
        val display = mapArg(state["display"])
        val width = (display["width"] as? Number)?.toInt()
        val height = (display["height"] as? Number)?.toInt()
        return State(
            stateId = stateId,
            xml = firstNonBlank(state["xml"]).takeIf(String::isNotBlank),
            packageName = firstNonBlank(state["package_name"]).takeIf(String::isNotBlank),
            activityName = firstNonBlank(state["activity_name"]).takeIf(String::isNotBlank),
            display = if (width != null && width > 0 && height != null && height > 0) {
                StateDisplay(width, height)
            } else {
                null
            },
        )
    }

    private suspend fun attachImages(
        transfer: Map<String, Any?>,
        sourceStateId: String,
    ): Map<String, Any?> {
        val source = mapArg(transfer["source"]).toMutableMap().apply {
            put("state_id", sourceStateId)
            val state = runCatching { loadState(sourceStateId) }.getOrDefault(emptyMap())
            firstNonBlank(state["screenshot_path"])
                .takeIf(String::isNotBlank)
                ?.let { put("screenshot_path", it) }
            if (!containsKey("display")) {
                mapArg(state["display"]).takeIf { it.isNotEmpty() }?.let {
                    put("display", it)
                }
            }
        }
        val target = mapArg(transfer["target"]).toMutableMap().apply {
            runCatching { captureTarget() }.getOrDefault(emptyMap()).forEach { (key, value) ->
                if (key == "display") putIfAbsent(key, value) else put(key, value)
            }
        }
        return linkedMapOf<String, Any?>().apply {
            putAll(transfer)
            put("source", source)
            put("target", target)
        }
    }

    private fun canonicalAction(action: String, args: Map<String, Any?>): Map<String, Any?> =
        linkedMapOf("tool" to action, "args" to args)

    private fun State.toBridgeState(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "state_id" to stateId,
        "xml" to xml,
        "package_name" to packageName,
        "activity_name" to activityName,
        "display" to display?.let {
            linkedMapOf("width" to it.width, "height" to it.height)
        },
    ).filterValues { it != null }

    private fun elapsed(startedAt: Long): Long =
        (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)

    companion object {
        private const val TAG = "OmniFlowReplayAdapter"
    }
}
