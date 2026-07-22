package cn.com.omnimind.assists.task.vlmserver

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ManualRecordingCommand(
    val action: Action,
    val title: String,
    val summary: String,
    val source: String,
    val startedAtMs: Long,
)

internal data class ManualRecordingObservation(
    val xml: String? = null,
    val screenshot: ManualVlmScreenshotRef? = null,
    val packageName: String? = null,
    val displayWidth: Int = 0,
    val displayHeight: Int = 0,
    val captureError: String? = null,
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
    private val observe: suspend (stage: String, command: ManualRecordingCommand) -> ManualRecordingObservation,
    private val execute: suspend (command: ManualRecordingCommand) -> OperationResult,
    private val onActionRecorded: suspend (index: Int, action: ManualVlmRecordedAction) -> Unit = { _, _ -> },
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
        command: ManualRecordingCommand,
        onDispatched: suspend (OperationResult) -> Unit = {},
    ): ManualRecordingOutcome = performMutex.withLock {
        val sequence = synchronized(stateLock) {
            received += 1
            pending += 1
            pendingSummary = command.summary
            received
        }
        var recorded = false
        try {
            val before = safeObserve("${sequence}_before", command)
            val operationResult = try {
                execute(command)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                OperationResult(
                    success = false,
                    message = error.message.orEmpty().ifBlank { "${command.action.tool} execution failed" },
                )
            }
            try {
                onDispatched(operationResult)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                Unit
            }
            val after = safeObserve(
                stage = "${sequence}_after",
                command = command,
                staleXml = before.xml,
                retryUnchanged = operationResult.success && command.action.tool in STATE_CHANGING_TOOLS,
            )
            if (operationResult.success) {
                val sourceStateRequired = command.action.tool in SOURCE_STATE_REQUIRED_TOOLS
                val evidenceComplete = !sourceStateRequired || !before.xml.isNullOrBlank()
                val action = ManualVlmRecordedAction(
                    action = command.action,
                    title = command.title,
                    beforePackageName = before.packageName,
                    afterPackageName = after.packageName,
                    beforeXml = before.xml,
                    afterXml = after.xml,
                    beforeScreenshot = before.screenshot,
                    afterScreenshot = after.screenshot,
                    startedAtMs = command.startedAtMs,
                    finishedAtMs = nowMs(),
                    summary = command.summary,
                    eventContext = linkedMapOf(
                        "schema_version" to "oob.manual_recording.event.v2",
                        "sequence" to sequence,
                        "source" to command.source,
                        "dispatch_status" to "completed",
                        "evidence_complete" to evidenceComplete,
                        "evidence_error" to before.captureError.takeUnless { evidenceComplete },
                    ).filterValues { it != null } + operationResult.diagnostics,
                    recordingBackend = command.source,
                    displayWidth = after.displayWidth.takeIf { it > 0 } ?: before.displayWidth,
                    displayHeight = after.displayHeight.takeIf { it > 0 } ?: before.displayHeight,
                    evidenceComplete = evidenceComplete,
                    evidenceError = before.captureError.takeUnless { evidenceComplete },
                )
                val index = journal.size()
                onActionRecorded(index, action)
                journal.append(action)
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
        command: ManualRecordingCommand,
        staleXml: String? = null,
        retryUnchanged: Boolean = false,
    ): ManualRecordingObservation {
        var latest = ManualRecordingObservation(captureError = "xml_unavailable")
        var latestWithXml: ManualRecordingObservation? = null
        repeat(OBSERVATION_ATTEMPTS) { attempt ->
            latest = try {
                observe(stage, command).let { observation ->
                    if (!observation.xml.isNullOrBlank()) observation
                    else observation.copy(captureError = observation.captureError ?: "xml_unavailable")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ManualRecordingObservation(
                    captureError = error.message.orEmpty().ifBlank {
                        "${error.javaClass.simpleName}:xml_capture_failed"
                    },
                )
            }
            val xml = latest.xml
            if (!xml.isNullOrBlank()) {
                latestWithXml = latest
                val unchanged = retryUnchanged && !staleXml.isNullOrBlank() && xml == staleXml
                if (!unchanged) return latest
            }
            if (attempt == OBSERVATION_ATTEMPTS - 1) {
                return latestWithXml ?: latest
            }
            delay(OBSERVATION_RETRY_DELAY_MS)
        }
        return latestWithXml ?: latest
    }

    private companion object {
        private const val OBSERVATION_ATTEMPTS = 5
        private const val OBSERVATION_RETRY_DELAY_MS = 100L
        private val SOURCE_STATE_REQUIRED_TOOLS = setOf(
            "click",
            "long_press",
            "input_text",
            "swipe",
        )
        private val STATE_CHANGING_TOOLS = SOURCE_STATE_REQUIRED_TOOLS + "press_key"
    }
}
