package cn.com.omnimind.bot.omniflow

import cn.com.omnimind.assists.runlog.OmniFlowRecordStepExecutor
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.runlog.RunLogStepRecord

internal fun omniFlowRecordStepExecutor(): OmniFlowRecordStepExecutor {
    return OmniFlowRecordStepExecutor { record ->
        RunLogStepRecord(
            step = InternalRunLogStore.canonicalStep(record.step),
            states = record.states,
        )
    }
}
