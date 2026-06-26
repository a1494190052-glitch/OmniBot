package cn.com.omnimind.bot.mcp

import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * MCP 任务管理器
 */
object McpTaskManager {
    private const val TAG = "[McpTaskManager]"
    
    // 活跃任务映射表
    private val activeTasks = ConcurrentHashMap<String, TaskState>()
    // Tracks tasks that already have a cleanup coroutine scheduled — prevents double 300s cleanup jobs
    private val scheduledCleanups = ConcurrentHashMap.newKeySet<String>()

    // 最大等待时间（毫秒）
    const val MAX_WAIT_TIME_MS = 120_000L  // 2分钟
    const val POLL_INTERVAL_MS = 500L      // 轮询间隔
    
    /**
     * 创建新任务
     */
    fun createTask(
        taskId: String,
        goal: String,
        status: TaskStatus = TaskStatus.RUNNING,
        needSummary: Boolean = false
    ): TaskState {
        val taskState = TaskState(
            taskId = taskId,
            goal = goal,
            status = status,
            needSummary = needSummary
        )
        activeTasks[taskId] = taskState
        return taskState
    }
    
    /**
     * 获取任务
     */
    fun getTask(taskId: String): TaskState? = activeTasks[taskId]

    /**
     * 获取所有活跃任务
     */
    fun getActiveTasks(): List<Map<String, Any?>> {
        return activeTasks.values.map { it.toResponseMap() }
    }
    
    /**
     * 移除任务
     */
    fun removeTask(taskId: String) {
        activeTasks.remove(taskId)
    }
    
    /**
     * 延迟清理任务（保留一段时间供查询）
     */
    fun scheduleTaskCleanup(taskId: String, scope: CoroutineScope, delayMs: Long = 300_000L) {
        if (!scheduledCleanups.add(taskId)) return // already scheduled; skip duplicate
        scope.launch {
            kotlinx.coroutines.delay(delayMs)
            activeTasks.remove(taskId)
            scheduledCleanups.remove(taskId)
            OmniLog.d(TAG, "Task $taskId cleaned up after delay")
        }
    }
    
    /**
     * 清理过期任务
     */
    fun cleanupExpiredTasks(maxAgeMs: Long = 600_000) {
        val now = System.currentTimeMillis()
        val expiredTaskIds = activeTasks.entries
            .filter { (_, state) ->
                (state.status == TaskStatus.FINISHED || state.status == TaskStatus.ERROR || state.status == TaskStatus.CANCELLED)
                    && (now - state.startTime) > maxAgeMs
            }
            .map { it.key }
        expiredTaskIds.forEach { taskId ->
            activeTasks.remove(taskId)
        }
    }
    
    /**
     * 更新任务为完成状态
     */
    fun markTaskFinished(taskId: String, message: String = "任务完成") {
        activeTasks[taskId]?.apply {
            status = TaskStatus.FINISHED
            this.message = message
        }
    }
    
    /**
     * 更新任务为等待输入状态
     */
    fun markTaskWaitingInput(taskId: String, question: String) {
        activeTasks[taskId]?.apply {
            status = TaskStatus.WAITING_INPUT
            waitingQuestion = question
            message = "等待用户输入"
            addChatMessage("[AGENT QUESTION] $question")
        }
    }
    
    /**
     * 更新任务为运行状态
     */
    fun markTaskRunning(taskId: String, message: String = "") {
        activeTasks[taskId]?.apply {
            status = TaskStatus.RUNNING
            waitingQuestion = null
            if (message.isNotBlank()) {
                this.message = message
            }
        }
    }
    
    /**
     * 更新任务为错误状态
     */
    fun markTaskError(taskId: String, error: String) {
        activeTasks[taskId]?.apply {
            status = TaskStatus.ERROR
            message = error
        }
    }
    
    /**
     * 更新任务为屏幕锁定状态
     */
    fun markTaskScreenLocked(taskId: String) {
        activeTasks[taskId]?.apply {
            status = TaskStatus.SCREEN_LOCKED
            message = "屏幕锁定，任务暂停"
            addChatMessage("[SYSTEM] Screen locked, task paused")
        }
    }

}
