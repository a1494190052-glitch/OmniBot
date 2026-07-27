package cn.com.omnimind.assists

import android.content.Context
import cn.com.omnimind.assists.api.bean.TaskParams
import cn.com.omnimind.assists.api.interfaces.TaskChangeListener
import cn.com.omnimind.assists.task.ChatTask
import cn.com.omnimind.baselib.util.OmniLog

class TaskManager(
    val context: Context,
    val taskChangeListener: TaskChangeListener
) {

    private val TAG = "[Assists] TaskManager"
    private val chatTasks: LinkedHashMap<String, ChatTask> = linkedMapOf()

    fun createAndStartTask(params: TaskParams) {
        when (params) {
            is TaskParams.ChatTaskParams -> createChatTaskAndStart(params)
        }
    }

    private fun createChatTaskAndStart(params: TaskParams.ChatTaskParams) {
        cleanupFinishedChatTasks()
        if (chatTasks[params.taskId]?.isRunning == true) {
            OmniLog.w(
                TAG, "ChatTask is not worked! taskId=${params.taskId} already running"
            )
            return
        }
        val chatTask = ChatTask(taskChangeListener,this)
        chatTasks[params.taskId] = chatTask
        chatTask.start(
            params.taskId,
            params.content,
            params.onMessagePush,
            params.provider,
            params.openClawConfig,
            params.modelOverride,
            params.reasoningEffort
        )
    }

    fun cancelChatTask(taskId: String? = null) {
        cleanupFinishedChatTasks()
        val targetChatTask = if (taskId.isNullOrBlank()) {
            chatTasks.values.lastOrNull { it.isRunning }
        } else {
            chatTasks[taskId]
        }
        if (targetChatTask?.isRunning == true) {
            targetChatTask.finishTask()
        }
    }

    fun unregisterChatTask(taskId: String) {
        chatTasks.remove(taskId)
    }

    private fun cleanupFinishedChatTasks() {
        val iterator = chatTasks.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!entry.value.isRunning) {
                iterator.remove()
            }
        }
    }
}
