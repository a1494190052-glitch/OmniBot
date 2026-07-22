package cn.com.omnimind.assists.runlog

import cn.com.omnimind.baselib.runlog.RunLogStepRecord

fun interface OmniFlowRecordStepExecutor {
    suspend fun recordStep(record: RunLogStepRecord): RunLogStepRecord
}

object OmniFlowRecordStepExecutorRegistry {
    @Volatile
    private var executor: OmniFlowRecordStepExecutor? = null

    fun register(value: OmniFlowRecordStepExecutor) {
        executor = value
    }

    fun requireExecutor(): OmniFlowRecordStepExecutor =
        requireNotNull(executor) { "record_step_executor_not_registered" }
}
