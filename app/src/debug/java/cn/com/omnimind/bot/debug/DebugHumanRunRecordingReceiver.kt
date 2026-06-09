package cn.com.omnimind.bot.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.assists.HumanTrajectoryLearningResult
import cn.com.omnimind.assists.HumanTrajectoryLearningSession
import cn.com.omnimind.assists.ManualRecordingRunLogRecovery
import cn.com.omnimind.assists.ManualOverlayTouchGesture
import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
import cn.com.omnimind.baselib.runlog.OobCanonicalActionSchema
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.util.AssistsUtil
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.math.pow
import kotlin.math.sqrt

class DebugHumanRunRecordingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val op = intent?.getStringExtra("op")?.trim()?.lowercase().orEmpty()
            .ifBlank { "status" }
        val description = intent?.getStringExtra("description")?.trim().orEmpty()
        val name = intent?.getStringExtra("name")?.trim().orEmpty()
            .ifBlank { description.ifBlank { "人工录制轨迹" } }
        val recoverRunId = stringExtra(intent, "runId") ?: stringExtra(intent, "run_id")
        val enableRawTouch = intent?.getBooleanExtra("enableRawTouch", false) == true ||
            intent?.getBooleanExtra("rawTouch", false) == true
        val enableDebugScreenshots = debugScreenshotsEnabled(intent)

        OmniLog.i(TAG, "received debug human recording op=$op")
        scope.launch {
            try {
                val payload = runCatching {
                    when (op) {
                        "start" -> startRecording(
                            appContext,
                            name,
                            description,
                            enableRawTouch,
                            enableDebugScreenshots
                        )
                        "pause" -> pauseRecording()
                        "resume" -> resumeRecording()
                        "gesture", "overlay_gesture" -> recordOverlayGesture(intent, recoverRunId)
                        "finish", "stop", "complete" -> finishRecording(appContext)
                        "recover", "recover_latest", "finish_latest" -> recoverRecording(appContext, recoverRunId)
                        "cancel" -> cancelRecording(appContext)
                        "status" -> statusPayload()
                        else -> errorPayload("UNKNOWN_OP", "Unsupported op: $op")
                    }
                }.getOrElse { error ->
                    OmniLog.e(TAG, "debug human run recording failed: ${error.fullMessage()}", error)
                    errorPayload(
                        code = "EXCEPTION",
                        message = error.fullMessage(),
                        extra = linkedMapOf(
                            "error_type" to error.javaClass.name,
                            "error_cause_chain" to error.causeChain()
                        )
                    )
                }
                writeJson(appContext, resultFileFor(op), payload)
                if (op != "status") {
                    writeJson(appContext, STATUS_FILE, statusPayload())
                }
                OmniLog.i(TAG, gson.toJson(payload))
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun startRecording(
        context: Context,
        name: String,
        description: String,
        enableRawTouch: Boolean,
        enableDebugScreenshots: Boolean
    ): Map<String, Any?> {
        OmniLog.i(TAG, "start: waiting for accessibility")
        waitForAccessibility(context)
        OmniLog.i(TAG, "start: accessibility ready")
        val startedAtMs = System.currentTimeMillis()
        OmniLog.i(TAG, "start: starting HumanTrajectoryLearningSession")
        val result = HumanTrajectoryLearningSession.start(
            context = context,
            name = name,
            description = description.ifBlank { name },
            enableRawTouch = enableRawTouch,
            enableDebugScreenshots = enableDebugScreenshots
        )
        activeResult = result
        activeStartedAtMs = startedAtMs
        activeName = name
        activeDescription = description.ifBlank { name }
        val status = HumanTrajectoryLearningSession.status().asMap()
        OmniLog.i(TAG, "start: session started action_count=${status["action_count"]}")
        return linkedMapOf(
            "success" to true,
            "phase" to "started",
            "recording_active" to true,
            "name" to activeName,
            "description" to activeDescription,
            "raw_touch_enabled" to enableRawTouch,
            "debug_screenshots_enabled" to enableDebugScreenshots,
            "started_at_ms" to startedAtMs,
            "status" to status,
            "source" to "oob_debug_human_run_recording"
        )
    }

    private suspend fun finishRecording(context: Context): Map<String, Any?> {
        val result = activeResult
            ?: return errorPayload("NO_ACTIVE_RECORDING", "No active human recording session")
        val completed = HumanTrajectoryLearningSession.completeActive()
        if (!completed) {
            activeResult = null
            return errorPayload("NO_ACTIVE_RECORDING", "No active human recording session")
        }
        return awaitResult(context, result, "finished")
    }

    private fun pauseRecording(): Map<String, Any?> {
        val paused = HumanTrajectoryLearningSession.pauseActive()
        val status = HumanTrajectoryLearningSession.status().asMap()
        return linkedMapOf(
            "success" to paused,
            "phase" to "paused",
            "recording_active" to status["recording_active"],
            "recording_paused" to status["recording_paused"],
            "status" to status,
            "error_code" to if (paused) null else "NO_ACTIVE_RECORDING",
            "error_message" to if (paused) null else "No active human recording session",
            "source" to "oob_debug_human_run_recording"
        ).filterValues { it != null }
    }

    private fun resumeRecording(): Map<String, Any?> {
        val resumed = HumanTrajectoryLearningSession.resumeActive()
        val status = HumanTrajectoryLearningSession.status().asMap()
        return linkedMapOf(
            "success" to resumed,
            "phase" to "recording",
            "recording_active" to status["recording_active"],
            "recording_paused" to status["recording_paused"],
            "status" to status,
            "error_code" to if (resumed) null else "NO_ACTIVE_RECORDING",
            "error_message" to if (resumed) null else "No active human recording session",
            "source" to "oob_debug_human_run_recording"
        ).filterValues { it != null }
    }

    private suspend fun recordOverlayGesture(intent: Intent?, requestedRunId: String?): Map<String, Any?> {
        if (!HumanTrajectoryLearningSession.isActive()) {
            return errorPayload("NO_ACTIVE_RECORDING", "No active human recording session")
        }
        val activeRunId = HumanTrajectoryLearningSession.activeRunId()
        if (!requestedRunId.isNullOrBlank() && requestedRunId != activeRunId) {
            return errorPayload(
                "RUN_ID_MISMATCH",
                "Active recording runId does not match requested runId",
                linkedMapOf(
                    "requested_run_id" to requestedRunId,
                    "active_run_id" to activeRunId
                )
            )
        }
        if (HumanTrajectoryLearningSession.isPaused()) {
            return errorPayload("RECORDING_PAUSED", "Resume the human recording before sending a gesture")
        }
        val actionName = normalizeGestureActionName(
            stringExtra(intent, "actionName")
            ?: stringExtra(intent, "action")
            ?: "click"
        )
        val startX = floatExtra(intent, "x1")
            ?: floatExtra(intent, "startX")
            ?: floatExtra(intent, "x")
            ?: return errorPayload("MISSING_GESTURE_X", "Gesture x/startX is required")
        val startY = floatExtra(intent, "y1")
            ?: floatExtra(intent, "startY")
            ?: floatExtra(intent, "y")
            ?: return errorPayload("MISSING_GESTURE_Y", "Gesture y/startY is required")
        val endX = floatExtra(intent, "x2")
            ?: floatExtra(intent, "endX")
            ?: startX
        val endY = floatExtra(intent, "y2")
            ?: floatExtra(intent, "endY")
            ?: startY
        val durationMs = longExtra(intent, "durationMs")
            ?: longExtra(intent, "duration")
            ?: if (actionName == OobCanonicalActionSchema.TOOL_SWIPE) 500L else 80L
        val distancePx = floatExtra(intent, "distancePx")
            ?: distance(startX, startY, endX, endY)
        val finishedAtMs = System.currentTimeMillis()
        val gesture = ManualOverlayTouchGesture(
            actionName = actionName,
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            durationMs = durationMs,
            distancePx = distancePx,
            direction = stringExtra(intent, "direction"),
            startedAtMs = finishedAtMs - durationMs.coerceAtLeast(0L),
            finishedAtMs = finishedAtMs,
            displayWidth = intExtra(intent, "displayWidth") ?: 0,
            displayHeight = intExtra(intent, "displayHeight") ?: 0
        )
        val replayResult = HumanTrajectoryLearningSession.recordOverlayGesture(gesture)
        delay(700L)
        val status = HumanTrajectoryLearningSession.status().asMap()
        val success = replayResult.executed && replayResult.recorded
        val errorCode = when {
            !replayResult.executed -> "GESTURE_NOT_EXECUTED"
            !replayResult.recorded -> "GESTURE_NOT_RECORDED"
            else -> null
        }
        val errorMessage = when {
            !replayResult.executed -> "Overlay gesture was not executed"
            !replayResult.recorded -> "Overlay gesture executed but was not recorded"
            else -> null
        }
        return linkedMapOf(
            "success" to success,
            "phase" to "gesture_recorded",
            "gesture" to linkedMapOf(
                "action_name" to actionName,
                "x1" to startX,
                "y1" to startY,
                "x2" to endX,
                "y2" to endY,
                "duration_ms" to durationMs,
                "distance_px" to distancePx
            ),
            "recording_active" to status["recording_active"],
            "recording_paused" to status["recording_paused"],
            "action_count" to status["action_count"],
            "latest_action_summary" to status["latest_action_summary"],
            "executed" to replayResult.executed,
            "recorded" to replayResult.recorded,
            "may_open_ime" to replayResult.mayOpenIme,
            "ignored_control" to replayResult.ignoredControl,
            "status" to status,
            "error_code" to errorCode,
            "error_message" to errorMessage,
            "source" to "oob_debug_human_run_recording"
        ).filterValues { it != null }
    }

    private suspend fun cancelRecording(context: Context): Map<String, Any?> {
        val result = activeResult
        val cancelled = HumanTrajectoryLearningSession.cancelActive("人工轨迹学习已取消")
        if (!cancelled || result == null) {
            activeResult = null
            return errorPayload("NO_ACTIVE_RECORDING", "No active human recording session")
        }
        return awaitResult(context, result, "cancelled")
    }

    private fun recoverRecording(context: Context, requestedRunId: String?): Map<String, Any?> {
        if (activeResult != null || HumanTrajectoryLearningSession.isActive()) {
            return errorPayload(
                "ACTIVE_RECORDING",
                "A live human recording session is active; finish it normally before recovery"
            )
        }
        val record = if (requestedRunId.isNullOrBlank()) {
            InternalRunLogStore.listRunRecords(context, limit = Int.MAX_VALUE)
                .filter { candidate -> candidate.source == "human_trajectory" && candidate.finishedAtMs == null }
                .maxByOrNull { candidate -> candidate.startedAtMs }
        } else {
            InternalRunLogStore.getRun(context, requestedRunId)
                ?.takeIf { candidate -> candidate.source == "human_trajectory" && candidate.finishedAtMs == null }
        }
            ?: return errorPayload("NO_RECOVERABLE_RECORDING", "No unfinished human recording RunLog found")
        val recovery = ManualRecordingRunLogRecovery.decisionFor(record)
            ?: return errorPayload("NO_RECOVERABLE_RECORDING", "No unfinished human recording RunLog found")
        val diagnostics = recovery.diagnostics
        if (diagnostics.isNotEmpty()) {
            InternalRunLogStore.updateDiagnostics(
                context = context,
                runId = record.runId,
                diagnostics = diagnostics
            )
        }
        InternalRunLogStore.finishRun(
            context = context,
            runId = record.runId,
            success = recovery.success,
            doneReason = recovery.doneReason,
            errorMessage = recovery.errorMessage
        )
        val runLog = InternalRunLogStore.timelinePayload(context, record.runId)
        return linkedMapOf(
            "success" to recovery.success,
            "phase" to "recovered",
            "recording_active" to false,
            "run_id" to record.runId,
            "name" to record.operationDescription,
            "description" to record.goal,
            "action_count" to recovery.replayableActionCount,
            "summary" to "Recovered unfinished human recording RunLog after app restart",
            "error_message" to recovery.errorMessage,
            "run_log" to runLog,
            "token_usage_total" to 0,
            "source" to "oob_debug_human_run_recording"
        ).filterValues { it != null }
    }

    private suspend fun awaitResult(
        context: Context,
        result: CompletableDeferred<HumanTrajectoryLearningResult>,
        phase: String
    ): Map<String, Any?> {
        val learningResult = withTimeoutOrNull(RESULT_TIMEOUT_MS) {
            result.await()
        } ?: run {
            activeResult = null
            return errorPayload("RESULT_TIMEOUT", "Timed out waiting for human recording result")
        }
        activeResult = null
        activeStartedAtMs = 0L
        activeName = ""
        activeDescription = ""
        val runLog = InternalRunLogStore.timelinePayload(context, learningResult.runId)
        return linkedMapOf(
            "success" to learningResult.success,
            "phase" to phase,
            "recording_active" to false,
            "run_id" to learningResult.runId,
            "name" to learningResult.name,
            "description" to learningResult.description,
            "action_count" to learningResult.actionCount,
            "summary" to learningResult.summary,
            "diagnostics" to learningResult.diagnostics.takeIf { it.isNotEmpty() },
            "error_message" to learningResult.errorMessage,
            "run_log" to runLog,
            "token_usage_total" to 0,
            "source" to "oob_debug_human_run_recording"
        )
    }

    private suspend fun waitForAccessibility(context: Context) {
        if (!AssistsUtil.Core.isInitialized()) {
            AssistsUtil.Core.initCore(context)
        }
        repeat(50) {
            if (AssistsService.instance != null && AccessibilityController.initController()) return
            delay(200L)
        }
        error("OOB accessibility service is not bound")
    }

    private fun statusPayload(): Map<String, Any?> {
        val status = HumanTrajectoryLearningSession.status().asMap()
        return linkedMapOf(
            "success" to true,
            "phase" to "status",
            "recording_active" to (
                activeResult != null ||
                    (status["recording_active"] as? Boolean == true)
                ),
            "recording_paused" to status["recording_paused"],
            "run_id" to status["run_id"],
            "name" to (status["name"] ?: activeName),
            "description" to (status["description"] ?: activeDescription),
            "started_at_ms" to (status["started_at_ms"] ?: activeStartedAtMs.takeIf { it > 0L }),
            "action_count" to status["action_count"],
            "latest_action_summary" to status["latest_action_summary"],
            "pending_action_summary" to status["pending_action_summary"],
            "recording_backend" to status["recording_backend"],
            "raw_touch_enabled" to status["raw_touch_enabled"],
            "raw_touch_available" to status["raw_touch_available"],
            "debug_screenshots_enabled" to status["debug_screenshots_enabled"],
            "debug_screenshot_stored_count" to status["debug_screenshot_stored_count"],
            "debug_screenshot_failed_count" to status["debug_screenshot_failed_count"],
            "debug_screenshot_skipped_count" to status["debug_screenshot_skipped_count"],
            "status" to status,
            "source" to "oob_debug_human_run_recording"
        ).filterValues { it != null }
    }

    private fun errorPayload(
        code: String,
        message: String,
        extra: Map<String, Any?> = emptyMap()
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "success" to false,
        "error_code" to code,
        "error_message" to message,
        "recording_active" to (activeResult != null || HumanTrajectoryLearningSession.isActive()),
        "source" to "oob_debug_human_run_recording"
    ).apply {
        putAll(extra)
    }

    private fun resultFileFor(op: String): String = when (op) {
        "start" -> START_FILE
        "status" -> STATUS_FILE
        "gesture", "overlay_gesture" -> GESTURE_FILE
        else -> RESULT_FILE
    }

    private fun normalizeGestureActionName(actionName: String): String {
        return when (val normalized = actionName.trim().lowercase()) {
            "tap" -> OobCanonicalActionSchema.TOOL_CLICK
            "swipe" -> OobCanonicalActionSchema.TOOL_SWIPE
            "long_click", "long-click", "longpress" -> OobCanonicalActionSchema.TOOL_LONG_PRESS
            else -> normalized
        }
    }

    private fun writeJson(context: Context, fileName: String, payload: Map<String, Any?>) {
        OmniLog.i(TAG, "writing $fileName")
        File(context.filesDir, fileName).writeText(gson.toJson(payload))
    }

    private fun stringExtra(intent: Intent?, key: String): String? =
        intent?.getStringExtra(key)?.trim()?.takeIf { it.isNotEmpty() }

    private fun floatExtra(intent: Intent?, key: String): Float? =
        stringExtra(intent, key)?.toFloatOrNull()

    private fun longExtra(intent: Intent?, key: String): Long? =
        stringExtra(intent, key)?.toLongOrNull()

    private fun intExtra(intent: Intent?, key: String): Int? =
        stringExtra(intent, key)?.toIntOrNull()

    private fun debugScreenshotsEnabled(intent: Intent?): Boolean {
        if (intent?.getBooleanExtra("disableDebugScreenshots", false) == true ||
            intent?.getBooleanExtra("skipScreenshots", false) == true
        ) {
            return false
        }
        val source = intent ?: return false
        if (!source.getBooleanExtra("keepScreenshots", true)) {
            return false
        }
        return source.getBooleanExtra("debugScreenshots", false) ||
            source.getBooleanExtra("enableDebugScreenshots", false) ||
            source.getBooleanExtra("recordDebugScreenshots", false)
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float =
        sqrt((x2 - x1).toDouble().pow(2.0) + (y2 - y1).toDouble().pow(2.0)).toFloat()

    private fun Throwable.fullMessage(): String {
        val parts = mutableListOf<String>()
        var current: Throwable? = this
        val seen = mutableSetOf<Throwable>()
        while (current != null && seen.add(current)) {
            parts += current.message?.takeIf(String::isNotBlank)
                ?.let { "${current.javaClass.name}: $it" }
                ?: current.javaClass.name
            current = current.cause
        }
        return parts.joinToString(" <- ")
    }

    private fun Throwable.causeChain(): List<Map<String, String>> {
        val output = mutableListOf<Map<String, String>>()
        var current: Throwable? = this
        val seen = mutableSetOf<Throwable>()
        while (current != null && seen.add(current)) {
            output += linkedMapOf(
                "type" to current.javaClass.name,
                "message" to current.message.orEmpty()
            )
            current = current.cause
        }
        return output
    }

    companion object {
        private const val TAG = "DebugHumanRunRecordingReceiver"
        const val START_FILE = "debug-human-run-recording-start.json"
        const val RESULT_FILE = "debug-human-run-recording-result.json"
        const val STATUS_FILE = "debug-human-run-recording-status.json"
        const val GESTURE_FILE = "debug-human-run-recording-gesture.json"
        private const val RESULT_TIMEOUT_MS = 15_000L
        private val gson = GsonBuilder().disableHtmlEscaping().create()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        @Volatile private var activeResult: CompletableDeferred<HumanTrajectoryLearningResult>? = null
        @Volatile private var activeStartedAtMs: Long = 0L
        @Volatile private var activeName: String = ""
        @Volatile private var activeDescription: String = ""
    }
}
