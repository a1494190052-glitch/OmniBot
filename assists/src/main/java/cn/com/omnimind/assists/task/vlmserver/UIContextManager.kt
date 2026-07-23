package cn.com.omnimind.assists.task.vlmserver

/** UI上下文管理器，只保存当前轮仍需注入的轻量信息。 */

class UIContextManager {
    private companion object {
        const val MAX_KEY_MEMORY_ITEMS = 8
        const val MAX_KEY_MEMORY_CHARS = 480
    }

    /**
    * 初始化上下文
    */
    fun initializeContext(
        overallTask: String,
        installedApplications: Map<String, String> = emptyMap(),
        targetPackageName: String = "",
        maxSteps: Int? = null,
        currentStepGoal: String = overallTask,
        stepSkillGuidance: String = ""
    ): UIContext {
        return UIContext(
            overallTask = overallTask,
            currentStepGoal = currentStepGoal,
            stepSkillGuidance = stepSkillGuidance,
            installedApplications = installedApplications,
            targetPackageName = targetPackageName,
            keyMemory = emptyList(),
            maxSteps = maxSteps,
            stepsUsed = 0,
            stepsRemaining = maxSteps
        )
    }

    /**
     * 处理记录动作 - 添加关键记忆
     * 对应Python中的 RecordAction 处理逻辑
     */
    fun addKeyMemory(context: UIContext, memory: String): UIContext {
        val normalized = memory.trim().take(MAX_KEY_MEMORY_CHARS)
        if (normalized.isEmpty()) return context
        val memories = (context.keyMemory + normalized)
            .filter(String::isNotBlank)
            .distinct()
            .takeLast(MAX_KEY_MEMORY_ITEMS)
        return context.copy(
            keyMemory = memories
        )
    }

    fun addKeyMemories(context: UIContext, memories: List<String>): UIContext {
        return memories.fold(context) { current, memory -> addKeyMemory(current, memory) }
    }

    fun withTransientEvents(context: UIContext, events: List<VLMContextEvent>): UIContext {
        return context.copy(
            transientEvents = events,
            priorityEvent = null,
            priorityEventType = null,
            suggestCompletion = false,
        )
    }

    fun clearTransientEvents(context: UIContext): UIContext {
        return context.copy(
            transientEvents = emptyList(),
            priorityEvent = null,
            priorityEventType = null,
            suggestCompletion = false,
        )
    }

}
