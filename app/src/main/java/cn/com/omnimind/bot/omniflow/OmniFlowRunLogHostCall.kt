package cn.com.omnimind.bot.omniflow

import android.content.Context
import cn.com.omnimind.assists.task.vlmserver.ActionExecutor
import cn.com.omnimind.assists.task.vlmserver.DeviceOperator
import cn.com.omnimind.assists.task.vlmserver.UIContextManager
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.runlog.OobActionSchema
import cn.com.omnimind.bot.runlog.ReplayHelper

internal fun omniFlowRunLogHostCall(context: Context): OmniFlowPythonHostCall =
    omniFlowRunLogHostCall(
        loadRunLog = { runId ->
            InternalRunLogStore.timelinePayload(context.applicationContext, runId)
        },
        loadState = { stateId ->
            InternalRunLogStore.statePayload(context.applicationContext, stateId)
        },
    )

internal fun omniFlowRunLogHostCall(
    loadRunLog: (String) -> Map<String, Any?>,
    loadState: (String) -> Map<String, Any?> = { emptyMap() },
): OmniFlowPythonHostCall = OmniFlowPythonHostCall { method, payload ->
    when (method) {
        "get_run_log" -> {
            val runId = payload["run_id"]?.toString()?.trim().orEmpty()
            require(runId.isNotEmpty()) { "run_id_required" }
            loadRunLog(runId)
        }
        "get_state" -> {
            val stateId = payload["state_id"]?.toString()?.trim().orEmpty()
            require(stateId.isNotEmpty()) { "state_id_required" }
            loadState(stateId).also { require(it.isNotEmpty()) { "state_not_found:$stateId" } }
        }
        else -> error("unsupported_host_call:$method")
    }
}

internal fun omniFlowAndroidHostCall(
    context: Context,
    deviceOperator: DeviceOperator,
    stopRequested: (() -> Boolean)? = null,
): OmniFlowPythonHostCall {
    val appContext = context.applicationContext
    val actionExecutor = ActionExecutor(deviceOperator, UIContextManager())
    return omniFlowAndroidHostCall(
        loadRunLog = { runId -> InternalRunLogStore.timelinePayload(appContext, runId) },
        loadState = { stateId -> InternalRunLogStore.statePayload(appContext, stateId) },
        observe = {
            val snapshot = ReplayHelper.readBackendSnapshot(deviceOperator)
            OmniFlowState.build(
                xml = snapshot.xml,
                packageName = snapshot.rawPackage,
                activityName = snapshot.activityName,
                displayWidth = deviceOperator.getDisplayWidth(),
                displayHeight = deviceOperator.getDisplayHeight(),
            )
        },
        act = { action, state ->
            val tool = action["tool"]?.toString()?.trim().orEmpty()
            require(OobActionSchema.canonicalToolName(tool) == tool) {
                "canonical_action_tool_invalid:$tool"
            }
            val args = stringMap(action["args"])
            val result = actionExecutor.act(
                action = tool,
                args = args,
                source = "omniflow_host",
                stopRequested = stopRequested,
            )
            linkedMapOf<String, Any?>(
                "success" to result.success,
                "error" to result.message.takeUnless { result.success },
                "extra" to linkedMapOf(
                    "message" to result.message,
                    "diagnostics" to result.diagnostics,
                ),
            )
        },
    )
}

internal fun omniFlowAndroidHostCall(
    loadRunLog: (String) -> Map<String, Any?>,
    loadState: (String) -> Map<String, Any?>,
    observe: suspend () -> Map<String, Any?>,
    act: suspend (Map<String, Any?>, Map<String, Any?>) -> Map<String, Any?>,
): OmniFlowPythonHostCall {
    return OmniFlowPythonHostCall { method, payload ->
        when (method) {
            "observe" -> OmniFlowState.normalize(observe())
            "act" -> {
                val action = stringMap(payload["action"])
                require(action.isNotEmpty()) { "host_action_required" }
                val rawState = stringMap(payload["state"])
                require(rawState["state_id"]?.toString()?.isNotBlank() == true) {
                    "host_action_state_required"
                }
                act(action, OmniFlowState.normalize(rawState))
            }
            "get_run_log" -> {
                val runId = payload["run_id"]?.toString()?.trim().orEmpty()
                require(runId.isNotEmpty()) { "run_id_required" }
                loadRunLog(runId)
            }
            "get_state" -> {
                val stateId = payload["state_id"]?.toString()?.trim().orEmpty()
                require(stateId.isNotEmpty()) { "state_id_required" }
                loadState(stateId).also { require(it.isNotEmpty()) { "state_not_found:$stateId" } }
            }
            else -> error("unsupported_host_call:$method")
        }
    }
}

private fun stringMap(value: Any?): Map<String, Any?> {
    val map = value as? Map<*, *> ?: return emptyMap()
    return linkedMapOf<String, Any?>().apply {
        map.forEach { (key, item) ->
            require(key is String) { "canonical_action_args_invalid" }
            put(key, item)
        }
    }
}
