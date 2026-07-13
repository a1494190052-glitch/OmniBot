package cn.com.omnimind.assists.task.vlmserver

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ManualCanonicalAction(
    val tool: String,
    val args: Map<String, Any?>,
    val title: String,
    val summary: String,
    val source: String,
    val startedAtMs: Long,
)

internal data class ManualRecordingObservation(
    val xml: String? = null,
    val screenshot: ManualVlmScreenshotRef? = null,
    val packageName: String? = null,
)

internal data class ManualRecordingEngineStats(
    val received: Int,
    val committed: Int,
    val failed: Int,
    val pending: Int,
    val pendingSummary: String?,
)

internal data class ManualRecordingOutcome(
    val executed: Boolean,
    val recorded: Boolean,
    val operationResult: OperationResult,
)

internal class ManualRecordingEngine(
    private val journal: ManualRecordingJournal,
    private val observe: suspend (stage: String, action: ManualCanonicalAction) -> ManualRecordingObservation,
    private val execute: suspend (action: ManualCanonicalAction) -> OperationResult,
    private val settleBeforeAfterObservation: suspend (action: ManualCanonicalAction) -> Unit = {},
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val performMutex = Mutex()
    private val stateLock = Any()
    private var received = 0
    private var committed = 0
    private var failed = 0
    private var pending = 0
    private var pendingSummary: String? = null

    suspend fun perform(
        action: ManualCanonicalAction,
        onDispatched: suspend (OperationResult) -> Unit = {},
    ): ManualRecordingOutcome = performMutex.withLock {
        val sequence = synchronized(stateLock) {
            received += 1
            pending += 1
            pendingSummary = action.summary
            received
        }
        var recorded = false
        try {
            val before = safeObserve("${sequence}_before", action)
            val operationResult = try {
                execute(action)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                OperationResult(
                    success = false,
                    message = error.message.orEmpty().ifBlank { "${action.tool} execution failed" },
                )
            }
            try {
                onDispatched(operationResult)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                Unit
            }
            if (operationResult.success) {
                settleBeforeAfterObservation(action)
            }
            val after = safeObserve("${sequence}_after", action)
            if (operationResult.success) {
                journal.append(
                    ManualVlmRecordedAction(
                        actionName = action.tool,
                        title = action.title,
                        params = action.args + linkedMapOf(
                            "recording_backend" to action.source,
                            "action_source" to action.source,
                        ),
                        packageName = after.packageName ?: before.packageName,
                        beforeXml = before.xml,
                        afterXml = after.xml,
                        beforeScreenshot = before.screenshot,
                        afterScreenshot = after.screenshot,
                        startedAtMs = action.startedAtMs,
                        finishedAtMs = nowMs(),
                        summary = action.summary,
                        eventContext = linkedMapOf(
                            "schema_version" to "oob.manual_recording.event.v2",
                            "sequence" to sequence,
                            "source" to action.source,
                            "dispatch_status" to "completed",
                        ) + operationResult.diagnostics,
                    )
                )
                recorded = true
            }
            ManualRecordingOutcome(
                executed = operationResult.success,
                recorded = recorded,
                operationResult = operationResult,
            )
        } finally {
            synchronized(stateLock) {
                pending = (pending - 1).coerceAtLeast(0)
                if (recorded) committed += 1 else failed += 1
                pendingSummary = null
            }
        }
    }

    suspend fun awaitIdle() {
        performMutex.withLock { Unit }
    }

    fun stats(): ManualRecordingEngineStats = synchronized(stateLock) {
        ManualRecordingEngineStats(
            received = received,
            committed = committed,
            failed = failed,
            pending = pending,
            pendingSummary = pendingSummary,
        )
    }

    private suspend fun safeObserve(
        stage: String,
        action: ManualCanonicalAction,
    ): ManualRecordingObservation = try {
        observe(stage, action)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        ManualRecordingObservation()
    }
}
