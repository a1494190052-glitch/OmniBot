package cn.com.omnimind.assists

import android.content.Context
import cn.com.omnimind.assists.api.bean.TaskParams
import cn.com.omnimind.assists.task.TaskChangeImpl

class StateMachine {
    private var isInitialized = false
    private var taskManager: TaskManager? = null

    fun isInitialized(): Boolean {
        return isInitialized
    }

    fun init(context: Context) {
        taskManager = TaskManager(context, TaskChangeImpl())
        isInitialized = true
    }

    fun startTask(params: TaskParams) {
        taskManager?.createAndStartTask(params)
    }

    fun cancelChatTask(taskId: String? = null) {
        taskManager?.cancelChatTask(taskId)
    }
}
