package cn.com.omnimind.bot.omniflow

import android.content.Context
import cn.com.omnimind.baselib.runlog.InternalRunLogStore

internal fun omniFlowRunLogHostCall(context: Context): OmniFlowPythonHostCall =
    omniFlowRunLogHostCall { runId ->
        InternalRunLogStore.timelinePayload(context.applicationContext, runId)
    }

internal fun omniFlowRunLogHostCall(
    loadRunLog: (String) -> Map<String, Any?>,
): OmniFlowPythonHostCall = OmniFlowPythonHostCall { method, payload ->
    require(method == "get_run_log") { "unsupported_host_call:$method" }
    val runId = payload["run_id"]?.toString()?.trim().orEmpty()
    require(runId.isNotEmpty()) { "run_id_required" }
    loadRunLog(runId)
}
