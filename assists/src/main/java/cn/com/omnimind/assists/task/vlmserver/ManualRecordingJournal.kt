package cn.com.omnimind.assists.task.vlmserver

internal class ManualRecordingJournal {
    private val lock = Any()
    private val actions = mutableListOf<ManualVlmRecordedAction>()

    fun append(action: ManualVlmRecordedAction): Int = synchronized(lock) {
        actions += action
        actions.size
    }

    fun size(): Int = synchronized(lock) { actions.size }

    fun lastOrNull(): ManualVlmRecordedAction? = synchronized(lock) {
        actions.lastOrNull()
    }

    fun snapshot(): List<ManualVlmRecordedAction> = synchronized(lock) {
        actions.toList()
    }

    fun summary(maxActions: Int = 8): String {
        val snapshot = snapshot()
        if (snapshot.isEmpty()) return ""
        val actionSummary = snapshot.take(maxActions).joinToString("；") { action ->
            action.summary.ifBlank { action.title }
        }
        val suffix = if (snapshot.size > maxActions) "；..." else ""
        return "用户在接管期间手动完成了 ${snapshot.size} 步操作：$actionSummary$suffix。请基于当前屏幕继续执行原任务。"
    }
}
