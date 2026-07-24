package cn.com.omnimind.assists.task.scheduled

import cn.com.omnimind.assists.TaskManager
import cn.com.omnimind.assists.api.enums.TaskType
import cn.com.omnimind.assists.api.interfaces.OnMessagePushListener
import cn.com.omnimind.assists.api.interfaces.TaskChangeListener
import cn.com.omnimind.assists.api.eventapi.ExecutionTaskEventApi
import cn.com.omnimind.assists.task.vlmserver.VLMOperationTask
import cn.com.omnimind.assists.runlog.OmniFlowRecordStepExecutor

/**
 * 视觉模型执行任务
 */
class ScheduledVLMOperationTask(
    val scheduledTaskID: String,
    override val executionTaskEventApi: ExecutionTaskEventApi?,
    override val taskChangeListener: TaskChangeListener,
    private val onMessagePushListener: OnMessagePushListener? = null,
    private val needSummary: Boolean = false,
    taskManager: TaskManager,
    recordStepExecutor: OmniFlowRecordStepExecutor,
) : VLMOperationTask(
    executionTaskEventApi, taskChangeListener, onMessagePushListener, needSummary,
    taskManager, recordStepExecutor
) {
    override fun getTaskType(): TaskType {
        return TaskType.SCHEDULED_VLM_OPERATION_EXECUTION
    }

}
