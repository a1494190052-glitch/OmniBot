package cn.com.omnimind.bot.omniflow

import android.content.Context
import cn.com.omnimind.assists.runlog.OmniFlowRecordStepExecutor
import cn.com.omnimind.baselib.runlog.RunLogStepRecord
import cn.com.omnimind.bot.runlog.mapArg

internal fun omniFlowRecordStepExecutor(context: Context): OmniFlowRecordStepExecutor {
    val appContext = context.applicationContext
    return OmniFlowRecordStepExecutor { record ->
        val response = OmniFlowPythonRuntime.call(
            context = appContext,
            operation = "record_step",
            payload = record.step,
        )
        val step = mapArg(response["step"]).also {
            require(it.isNotEmpty()) { "omniflow_record_step_missing" }
        }
        RunLogStepRecord(step = step, states = record.states)
    }
}
