package cn.com.omnimind.assists

/**
 * Tracks VLM UI lifetime at agent-run scope.
 *
 * Native VLM tasks can be split into multiple child tasks under one agent run.
 * The floating UI should stay alive until the agent run ends, not until a single
 * child VLM task reports its terminal state.
 */
object AgentVlmUiSession {
    data class EndResult(
        val wasActive: Boolean,
        val shouldFinishCompanion: Boolean,
        val stopRequested: Boolean,
        val completeRequested: Boolean
    )

    private data class SessionState(
        val runId: String,
        var hasVlmTask: Boolean = false,
        var shouldFinishCompanion: Boolean = false,
        var stopRequested: Boolean = false,
        var completeRequested: Boolean = false,
        var onStopRequested: (() -> Unit)? = null,
        var onCompleteRequested: (() -> Unit)? = null,
        val taskIds: LinkedHashSet<String> = linkedSetOf(),
        val activeTaskIds: LinkedHashSet<String> = linkedSetOf()
    )

    private val lock = Any()
    private val sessionsByRunId = linkedMapOf<String, SessionState>()
    private val runIdByTaskId = linkedMapOf<String, String>()

    fun registerRun(
        runId: String,
        onStopRequested: () -> Unit,
        onCompleteRequested: (() -> Unit)? = null
    ) {
        val normalizedRunId = runId.trim()
        if (normalizedRunId.isEmpty()) return
        synchronized(lock) {
            val state = sessionsByRunId.getOrPut(normalizedRunId) {
                SessionState(runId = normalizedRunId)
            }
            state.onStopRequested = onStopRequested
            state.onCompleteRequested = onCompleteRequested
        }
    }

    fun beginTask(runId: String?, taskId: String?) {
        val normalizedRunId = runId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val normalizedTaskId = taskId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        synchronized(lock) {
            val state = sessionsByRunId.getOrPut(normalizedRunId) {
                SessionState(runId = normalizedRunId)
            }
            state.hasVlmTask = true
            state.taskIds.add(normalizedTaskId)
            state.activeTaskIds.add(normalizedTaskId)
            runIdByTaskId[normalizedTaskId] = normalizedRunId
        }
    }

    fun endTask(taskId: String?) {
        val normalizedTaskId = taskId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        synchronized(lock) {
            val state = runIdByTaskId[normalizedTaskId]?.let { sessionsByRunId[it] } ?: return
            state.activeTaskIds.remove(normalizedTaskId)
            state.taskIds.remove(normalizedTaskId)
            runIdByTaskId.remove(normalizedTaskId)
        }
    }

    fun activeTaskIdsForRun(runId: String?): List<String> {
        val normalizedRunId = runId?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return synchronized(lock) {
            sessionsByRunId[normalizedRunId]?.activeTaskIds?.toList().orEmpty()
        }
    }

    fun activeRunIdsForTaskIds(taskIds: Collection<String>): List<String> {
        if (taskIds.isEmpty()) return emptyList()
        val normalizedTaskIds = taskIds
            .mapNotNull { it.trim().takeIf { taskId -> taskId.isNotEmpty() } }
            .toSet()
        if (normalizedTaskIds.isEmpty()) return emptyList()
        return synchronized(lock) {
            normalizedTaskIds
                .mapNotNull { taskId ->
                    val runId = runIdByTaskId[taskId] ?: return@mapNotNull null
                    runId.takeIf {
                        sessionsByRunId[it]?.activeTaskIds?.contains(taskId) == true
                    }
                }
                .distinct()
        }
    }

    fun activeRunIdForTask(taskId: String?): String? {
        val normalizedTaskId = taskId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return activeRunIdsForTaskIds(listOf(normalizedTaskId)).firstOrNull()
    }

    fun markTaskCompanionState(taskId: String?, isCompanionRunning: Boolean) {
        val normalizedTaskId = taskId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        synchronized(lock) {
            val state = runIdByTaskId[normalizedTaskId]?.let { sessionsByRunId[it] } ?: return
            if (!isCompanionRunning) {
                state.shouldFinishCompanion = true
            }
        }
    }

    fun shouldHoldUiForTask(taskId: String?): Boolean {
        val normalizedTaskId = taskId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return synchronized(lock) {
            val state = runIdByTaskId[normalizedTaskId]?.let { sessionsByRunId[it] }
            state?.hasVlmTask == true && !state.stopRequested && !state.completeRequested
        }
    }

    fun markTaskStopSuppressed(taskId: String?, isCompanionRunning: Boolean) {
        val normalizedTaskId = taskId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        synchronized(lock) {
            val state = runIdByTaskId[normalizedTaskId]?.let { sessionsByRunId[it] } ?: return
            if (!isCompanionRunning) {
                state.shouldFinishCompanion = true
            }
        }
    }

    fun hasActiveSession(): Boolean {
        return synchronized(lock) {
            sessionsByRunId.values.any { it.hasVlmTask && !it.stopRequested && !it.completeRequested }
        }
    }

    fun requestStopActiveSession(): Boolean {
        return requestStopSession(null)
    }

    fun requestStopSession(runOrTaskId: String?): Boolean {
        val normalizedId = runOrTaskId?.trim().orEmpty()
        val callbacks = synchronized(lock) {
            val candidates = resolveCandidateSessionsLocked(normalizedId)
            candidates
                .filter { it.hasVlmTask && !it.completeRequested && !it.stopRequested }
                .onEach { it.stopRequested = true }
                .mapNotNull { it.onStopRequested }
        }
        callbacks.forEach { runCatching { it.invoke() } }
        return callbacks.isNotEmpty()
    }

    fun requestCompleteActiveSession(): Boolean = requestCompleteSession(null)

    fun requestCompleteSession(runOrTaskId: String?): Boolean {
        val normalizedId = runOrTaskId?.trim().orEmpty()
        val callbacks = synchronized(lock) {
            resolveCandidateSessionsLocked(normalizedId)
                .filter { it.hasVlmTask && !it.stopRequested && !it.completeRequested }
                .onEach { it.completeRequested = true }
                .mapNotNull { it.onCompleteRequested }
        }
        callbacks.forEach { runCatching { it.invoke() } }
        return callbacks.isNotEmpty()
    }

    private fun resolveCandidateSessionsLocked(normalizedId: String): List<SessionState> {
        if (normalizedId.isEmpty()) {
            return sessionsByRunId.values.toList()
        }
        val runId = if (sessionsByRunId.containsKey(normalizedId)) {
            normalizedId
        } else {
            val ownerRunId = runIdByTaskId[normalizedId]
            ownerRunId?.takeIf { runId ->
                sessionsByRunId[runId]?.activeTaskIds?.contains(normalizedId) == true
            }
        }
        return runId?.let { sessionsByRunId[it] }?.let(::listOf).orEmpty()
    }

    fun endRun(runId: String): EndResult {
        val normalizedRunId = runId.trim()
        if (normalizedRunId.isEmpty()) {
            return EndResult(
                wasActive = false,
                shouldFinishCompanion = false,
                stopRequested = false,
                completeRequested = false
            )
        }
        return synchronized(lock) {
            val state = sessionsByRunId.remove(normalizedRunId)
                ?: return@synchronized EndResult(
                    wasActive = false,
                    shouldFinishCompanion = false,
                    stopRequested = false,
                    completeRequested = false
                )
            state.taskIds.forEach { runIdByTaskId.remove(it) }
            EndResult(
                wasActive = state.hasVlmTask,
                shouldFinishCompanion = state.shouldFinishCompanion,
                stopRequested = state.stopRequested,
                completeRequested = state.completeRequested
            )
        }
    }
}
