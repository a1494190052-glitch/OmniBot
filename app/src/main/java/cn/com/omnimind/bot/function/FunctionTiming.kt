package cn.com.omnimind.bot.function

internal class FunctionTiming(
    private val source: String,
    private val requiredPhases: List<String>,
    private val startedAtMs: Long = System.currentTimeMillis(),
    private val includeRunnerDuration: Boolean = false,
) {
    private val startedAtNanos = System.nanoTime()
    private val phases = linkedMapOf<String, Long>()

    fun <T> measure(phaseName: String, block: () -> T): T {
        val phaseStartedAtNanos = System.nanoTime()
        return try {
            block()
        } finally {
            recordElapsed(phaseName, phaseStartedAtNanos)
        }
    }

    suspend fun <T> measureSuspend(phaseName: String, block: suspend () -> T): T {
        val phaseStartedAtNanos = System.nanoTime()
        return try {
            block()
        } finally {
            recordElapsed(phaseName, phaseStartedAtNanos)
        }
    }

    fun recordElapsed(phaseName: String, phaseStartedAtNanos: Long) {
        phases[phaseName] = elapsedMs(phaseStartedAtNanos)
    }

    fun recordSinceStart(phaseName: String, endedAtNanos: Long = System.nanoTime()) {
        phases[phaseName] = ((endedAtNanos - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)
    }

    fun finish(finishedAtMs: Long = System.currentTimeMillis()): Map<String, Any?> {
        val completedPhases = linkedMapOf<String, Long>()
        requiredPhases.forEach { phaseName ->
            completedPhases[phaseName] = phases[phaseName] ?: 0L
        }
        phases.forEach { (phaseName, durationMs) ->
            completedPhases.putIfAbsent(phaseName, durationMs)
        }
        val durationMs = (finishedAtMs - startedAtMs).coerceAtLeast(0L)
        return linkedMapOf<String, Any?>(
            "source" to source,
            "started_at_ms" to startedAtMs,
            "finished_at_ms" to finishedAtMs,
            "duration_ms" to durationMs,
            "runner_duration_ms" to durationMs.takeIf { includeRunnerDuration },
            "phase_ms" to completedPhases,
        ).filterValues { it != null }
    }

    private fun elapsedMs(startedAtNanos: Long): Long =
        ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)
}
