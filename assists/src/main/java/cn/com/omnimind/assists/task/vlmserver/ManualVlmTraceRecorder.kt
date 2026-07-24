package cn.com.omnimind.assists.task.vlmserver

import android.content.Context
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.assists.ManualOverlayGestureReplayResult
import cn.com.omnimind.assists.ManualOverlayTouchGesture
import cn.com.omnimind.assists.ManualInputTarget
import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
import cn.com.omnimind.baselib.runlog.ActionCoordinateCodec
import cn.com.omnimind.baselib.runlog.OobActionSchema
import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.coroutines.runBlocking

data class ManualVlmTraceResult(
    val actions: List<ManualVlmRecordedAction>,
    val summary: String,
    val diagnostics: Map<String, Any?> = emptyMap(),
) {
    val actionCount: Int get() = actions.size
}

data class ManualVlmRecordedAction(
    val action: Action,
    val title: String,
    val beforePackageName: String?,
    val afterPackageName: String?,
    val beforeXml: String?,
    val afterXml: String?,
    val beforeScreenshot: ManualVlmScreenshotRef? = null,
    val afterScreenshot: ManualVlmScreenshotRef? = null,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val summary: String,
    val eventContext: Map<String, Any?> = emptyMap(),
    val recordingBackend: String = "unknown",
    val displayWidth: Int = 0,
    val displayHeight: Int = 0,
    val evidenceComplete: Boolean = true,
    val evidenceError: String? = null,
)

internal fun selectManualInputTargetAfterClick(
    before: ManualInputTarget?,
    after: ManualInputTarget?,
    clickedFocusedTarget: ManualInputTarget?,
): ManualInputTarget? = after?.takeIf {
    it != before || clickedFocusedTarget != null
}

internal fun manualInputTextActionArgs(
    text: String,
    inputTarget: ManualInputTarget,
): Map<String, Any?> = linkedMapOf<String, Any?>(
    OobActionSchema.ARG_TARGET_DESCRIPTION to inputTarget.description,
    OobActionSchema.ARG_TEXT to text,
    OobActionSchema.ARG_X to inputTarget.x,
    OobActionSchema.ARG_Y to inputTarget.y,
    OobActionSchema.ARG_NODE_RESOURCE_ID to inputTarget.nodeResourceId,
).filterValues { it != null }

internal fun canonicalManualScreenAction(
    tool: String,
    args: Map<String, Any?>,
    displayWidth: Int,
    displayHeight: Int,
): Action {
    require(displayWidth > 0 && displayHeight > 0) { "manual_recording_display_required" }
    val canonicalArgs = ActionCoordinateCodec.toRelative(
        args = args,
        displaySize = ActionCoordinateCodec.DisplaySize(
            displayWidth.toDouble(),
            displayHeight.toDouble(),
        ),
    )
    return actionOf(tool, canonicalArgs)
}

data class ManualVlmScreenshotRef(
    val path: String,
    val relativePath: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val bytes: Long,
    val sha256: String,
    val capturedAtMs: Long,
    val captureStage: String,
    val annotation: Map<String, Any?> = emptyMap(),
) {
    fun asMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "schema_version" to "oob.runlog.screenshot_ref.v1",
        "kind" to "screenshot",
        "path" to path,
        "relative_path" to relativePath,
        "screenshot_path" to path,
        "mime_type" to mimeType,
        "width" to width,
        "height" to height,
        "bytes" to bytes,
        "sha256" to sha256,
        "captured_at_ms" to capturedAtMs,
        "capture_stage" to captureStage,
        "storage" to "app_private_files",
    ).apply {
        if (annotation.isNotEmpty()) put("annotation", annotation)
    }
}

internal data class ManualVlmTraceSnapshot(
    val isStarted: Boolean,
    val isPaused: Boolean,
    val actionCount: Int,
    val latestActionSummary: String?,
    val pendingActionSummary: String?,
    val accessibilityEventCount: Int,
    val rawTouchEnabled: Boolean,
    val rawTouchAvailable: Boolean,
    val overlayTouchRecordedCount: Int,
    val recordingBackend: String,
    val debugScreenshotsEnabled: Boolean,
    val debugScreenshotStoredCount: Int,
    val debugScreenshotFailedCount: Int,
    val debugScreenshotSkippedCount: Int,
) {
    fun asMap(): Map<String, Any?> = linkedMapOf(
        "is_started" to isStarted,
        "is_paused" to isPaused,
        "action_count" to actionCount,
        "latest_action_summary" to latestActionSummary,
        "pending_action_summary" to pendingActionSummary,
        "accessibility_event_count" to accessibilityEventCount,
        "raw_touch_enabled" to rawTouchEnabled,
        "raw_touch_available" to rawTouchAvailable,
        "overlay_touch_recorded_count" to overlayTouchRecordedCount,
        "recording_backend" to recordingBackend,
        "debug_screenshots_enabled" to debugScreenshotsEnabled,
        "debug_screenshot_stored_count" to debugScreenshotStoredCount,
        "debug_screenshot_failed_count" to debugScreenshotFailedCount,
        "debug_screenshot_skipped_count" to debugScreenshotSkippedCount,
    ).filterValues { it != null }
}

internal object ManualRecordingDiagnostics {
    const val COMPLETE_OVERLAY_TOUCH = "complete_overlay_touch"
    const val COMPLETE_MANUAL_CONTROL = "complete_manual_control"
    const val INCOMPLETE_OVERLAY_TOUCH = "incomplete_overlay_touch"
    const val COMPLETE_RAW_TOUCH = "complete_raw_touch"
    const val MISSING_RAW_TOUCH = "missing_raw_touch"
    const val RAW_TOUCH_INTERRUPTED = "raw_touch_interrupted"

    fun completeness(rawTouchAvailable: Boolean, rawTouchActiveAtStop: Boolean?): String = when {
        rawTouchAvailable && rawTouchActiveAtStop == true -> COMPLETE_RAW_TOUCH
        rawTouchAvailable -> RAW_TOUCH_INTERRUPTED
        else -> MISSING_RAW_TOUCH
    }

    fun guaranteesNoMissingClicks(
        rawTouchAvailable: Boolean,
        rawTouchActiveAtStop: Boolean?,
    ): Boolean = completeness(rawTouchAvailable, rawTouchActiveAtStop) == COMPLETE_RAW_TOUCH

    fun warningMessage(completeness: String): String? = when (completeness) {
        COMPLETE_OVERLAY_TOUCH, COMPLETE_RAW_TOUCH, COMPLETE_MANUAL_CONTROL -> null
        INCOMPLETE_OVERLAY_TOUCH -> "有动作执行失败，本次轨迹未全部提交"
        RAW_TOUCH_INTERRUPTED -> "raw touch 录制中断"
        else -> "raw touch 不可用"
    }

    fun guaranteesNoMissingClicks(diagnostics: Map<String, Any?>): Boolean {
        val manual = diagnostics["manual_recording"] as? Map<*, *> ?: return false
        return manual["guarantees_no_missing_clicks"] == true
    }

    fun warningMessage(diagnostics: Map<String, Any?>): String? {
        val manual = diagnostics["manual_recording"] as? Map<*, *> ?: return null
        return manual["warning_message"]?.toString()?.takeIf { it.isNotBlank() }
    }
}

class ManualVlmTraceRecorder(
    context: Context,
    private val sessionLabel: String,
    private val enableRawTouch: Boolean = false,
    private val enableDebugScreenshots: Boolean = false,
    private val onActionRecorded: (suspend (index: Int, action: ManualVlmRecordedAction) -> Unit)? = null,
) {
    private val stateLock = Any()
    private val journal = ManualRecordingJournal()
    private val deviceOperator = AndroidDeviceOperator(null, context)
    private val actionExecutor = ActionExecutor(deviceOperator)
    private val observationCapture = ManualObservationCapture(
        context = context,
        sessionLabel = sessionLabel,
        debugScreenshotsRequested = enableDebugScreenshots,
    )
    private val engine = ManualRecordingEngine(
        journal = journal,
        observe = { stage, command -> captureObservation(stage, command) },
        execute = { command ->
            actionExecutor.act(
                action = command.action.tool,
                args = command.action.argsMap(),
                source = command.source,
            )
        },
        onActionRecorded = { index, action -> onActionRecorded?.invoke(index, action) },
    )

    @Volatile private var isStarted = false
    @Volatile private var isPaused = false

    fun start(): Boolean {
        if (isStarted) return true
        if (!AssistsService.isInit() || !AccessibilityController.initController()) {
            OmniLog.w(TAG, "manual recorder unavailable: accessibility service is not ready")
            return false
        }
        synchronized(stateLock) {
            isStarted = true
            isPaused = false
        }
        OmniLog.i(
            TAG,
            "manual recorder started session=$sessionLabel source=explicit_actions raw_requested=$enableRawTouch",
        )
        return true
    }

    fun pause(): Boolean = synchronized(stateLock) {
        if (!isStarted) return false
        isPaused = true
        true
    }

    fun resume(): Boolean = synchronized(stateLock) {
        if (!isStarted) return false
        isPaused = false
        true
    }

    fun stop(): ManualVlmTraceResult {
        synchronized(stateLock) {
            isStarted = false
            isPaused = false
        }
        runBlocking { engine.awaitIdle() }
        val actions = journal.snapshot()
        return ManualVlmTraceResult(
            actions = actions,
            summary = journal.summary(MAX_SUMMARY_ACTIONS),
            diagnostics = buildDiagnostics(actions),
        )
    }

    internal fun snapshot(): ManualVlmTraceSnapshot {
        val engineStats = engine.stats()
        val observationStats = observationCapture.stats()
        val actions = journal.snapshot()
        return ManualVlmTraceSnapshot(
            isStarted = isStarted,
            isPaused = isPaused,
            actionCount = actions.size,
            latestActionSummary = engineStats.pendingSummary ?: actions.lastOrNull()?.summary,
            pendingActionSummary = engineStats.pendingSummary,
            accessibilityEventCount = 0,
            rawTouchEnabled = enableRawTouch,
            rawTouchAvailable = false,
            overlayTouchRecordedCount = actions.count {
                it.recordingBackend == OVERLAY_TOUCH_SOURCE
            },
            recordingBackend = recordingBackend(actions),
            debugScreenshotsEnabled = observationStats.screenshotsActive,
            debugScreenshotStoredCount = observationStats.screenshotStoredCount,
            debugScreenshotFailedCount = observationStats.screenshotFailedCount,
            debugScreenshotSkippedCount = observationStats.screenshotSkippedCount,
        )
    }

    suspend fun recordManualInputText(
        text: String,
        inputTarget: ManualInputTarget? = null,
    ): Boolean {
        if (text.isEmpty() || inputTarget == null || inputTarget.password || !isRecording()) {
            return false
        }
        return engine.perform(
            command(
                tool = OobActionSchema.TOOL_INPUT_TEXT,
                args = manualInputTextActionArgs(text, inputTarget),
                title = "输入文本",
                summary = "输入文本：${text.take(MAX_TEXT_SUMMARY_CHARS)}",
                source = MANUAL_CONTROL_SOURCE,
                screenCoordinates = true,
            )
        ).recorded
    }

    suspend fun recordManualPressKey(key: String): Boolean {
        val canonicalKey = key.trim().lowercase().takeIf { it in SUPPORTED_KEYS } ?: return false
        if (!isRecording()) return false
        return engine.perform(
            command(
                tool = OobActionSchema.TOOL_PRESS_KEY,
                args = mapOf(OobActionSchema.ARG_KEY to canonicalKey),
                title = "按键 $canonicalKey",
                summary = "按键：$canonicalKey",
                source = MANUAL_CONTROL_SOURCE,
            )
        ).recorded
    }

    suspend fun recordManualWait(durationMs: Long): Boolean {
        if (durationMs !in 1L..MAX_CANONICAL_WAIT_MS || !isRecording()) return false
        return engine.perform(
            command(
                tool = OobActionSchema.TOOL_WAIT,
                args = mapOf(OobActionSchema.ARG_DURATION_MS to durationMs),
                title = "等待 ${formatDuration(durationMs)}",
                summary = "等待 ${formatDuration(durationMs)}",
                source = MANUAL_CONTROL_SOURCE,
            )
        ).recorded
    }

    suspend fun recordOverlayGesture(
        gesture: ManualOverlayTouchGesture,
        onGestureDispatched: suspend (mayOpenIme: Boolean) -> Unit = {},
    ): ManualOverlayGestureReplayResult {
        if (!isRecording() || gesture.actionName !in SUPPORTED_GESTURES) {
            return ManualOverlayGestureReplayResult(executed = false, recorded = false)
        }
        val inputTargetBefore = if (gesture.actionName == OobActionSchema.TOOL_CLICK) {
            AccessibilityController.focusedInputTarget()
        } else {
            null
        }
        val command = gesture.toRecordingCommand()
        val outcome = engine.perform(command) { dispatchResult ->
            onGestureDispatched(
                dispatchResult.success && gesture.actionName == OobActionSchema.TOOL_CLICK,
            )
        }
        val inputTarget = if (outcome.recorded && gesture.actionName == OobActionSchema.TOOL_CLICK) {
            val inputTargetAfter = AccessibilityController.focusedInputTarget()
            val clickedFocusedTarget = AccessibilityController.focusedInputTargetAt(
                gesture.startX,
                gesture.startY,
            )
            selectManualInputTargetAfterClick(
                before = inputTargetBefore,
                after = inputTargetAfter,
                clickedFocusedTarget = clickedFocusedTarget,
            )
        } else {
            null
        }
        return ManualOverlayGestureReplayResult(
            executed = outcome.executed,
            recorded = outcome.recorded,
            mayOpenIme = outcome.executed && gesture.actionName == OobActionSchema.TOOL_CLICK,
            inputTarget = inputTarget,
        )
    }

    private fun ManualOverlayTouchGesture.toRecordingCommand(): ManualRecordingCommand {
        return when (actionName) {
            OobActionSchema.TOOL_CLICK -> command(
                tool = actionName,
                args = mapOf(
                    OobActionSchema.ARG_TARGET_DESCRIPTION to "屏幕坐标",
                    OobActionSchema.ARG_X to startX,
                    OobActionSchema.ARG_Y to startY,
                ),
                title = "点击 (${startX.toInt()}, ${startY.toInt()})",
                summary = "点击屏幕 (${startX.toInt()}, ${startY.toInt()})",
                source = OVERLAY_TOUCH_SOURCE,
                startedAtMs = startedAtMs,
                screenCoordinates = true,
                displayWidth = displayWidth,
                displayHeight = displayHeight,
            )

            OobActionSchema.TOOL_LONG_PRESS -> command(
                tool = actionName,
                args = mapOf(
                    OobActionSchema.ARG_TARGET_DESCRIPTION to "屏幕坐标",
                    OobActionSchema.ARG_X to startX,
                    OobActionSchema.ARG_Y to startY,
                    OobActionSchema.ARG_DURATION_MS to durationMs.coerceAtLeast(1L),
                ),
                title = "长按 (${startX.toInt()}, ${startY.toInt()})",
                summary = "长按屏幕 (${startX.toInt()}, ${startY.toInt()})",
                source = OVERLAY_TOUCH_SOURCE,
                startedAtMs = startedAtMs,
                screenCoordinates = true,
                displayWidth = displayWidth,
                displayHeight = displayHeight,
            )

            else -> command(
                tool = OobActionSchema.TOOL_SWIPE,
                args = linkedMapOf(
                    OobActionSchema.ARG_TARGET_DESCRIPTION to "屏幕区域",
                    OobActionSchema.ARG_X1 to startX,
                    OobActionSchema.ARG_Y1 to startY,
                    OobActionSchema.ARG_X2 to endX,
                    OobActionSchema.ARG_Y2 to endY,
                    OobActionSchema.ARG_DURATION_MS to durationMs.coerceAtLeast(1L),
                    OobActionSchema.ARG_DIRECTION to direction.orEmpty().ifBlank {
                        if (kotlin.math.abs(endX - startX) >= kotlin.math.abs(endY - startY)) {
                            if (endX >= startX) "right" else "left"
                        } else {
                            if (endY >= startY) "down" else "up"
                        }
                    },
                ),
                title = "${directionLabel(direction)}滑动",
                summary = "从 (${startX.toInt()}, ${startY.toInt()}) 滑动到 " +
                    "(${endX.toInt()}, ${endY.toInt()})",
                source = OVERLAY_TOUCH_SOURCE,
                startedAtMs = startedAtMs,
                screenCoordinates = true,
                displayWidth = displayWidth,
                displayHeight = displayHeight,
            )
        }
    }

    private fun command(
        tool: String,
        args: Map<String, Any?>,
        title: String,
        summary: String,
        source: String,
        startedAtMs: Long = System.currentTimeMillis(),
        screenCoordinates: Boolean = false,
        displayWidth: Int = deviceOperator.getDisplayWidth(),
        displayHeight: Int = deviceOperator.getDisplayHeight(),
    ): ManualRecordingCommand = ManualRecordingCommand(
        action = if (screenCoordinates) {
            val width = displayWidth.takeIf { it > 0 } ?: deviceOperator.getDisplayWidth()
            val height = displayHeight.takeIf { it > 0 } ?: deviceOperator.getDisplayHeight()
            canonicalManualScreenAction(
                tool = tool,
                args = args,
                displayWidth = width,
                displayHeight = height,
            )
        } else {
            actionOf(tool, args)
        },
        title = title,
        summary = summary,
        source = source,
        startedAtMs = startedAtMs,
    )

    private fun captureObservation(
        stage: String,
        command: ManualRecordingCommand,
    ): ManualRecordingObservation {
        val xml = observationCapture.captureXml(stage).xml
        val screenshot = observationCapture.captureScreenshot(
            stage = stage,
            annotation = screenshotAnnotation(command.action),
        )
        return ManualRecordingObservation(
            xml = xml,
            screenshot = screenshot,
            packageName = AccessibilityXml.packageName(xml)
                ?: runCatching { deviceOperator.currentPackageName() }.getOrNull(),
            displayWidth = deviceOperator.getDisplayWidth(),
            displayHeight = deviceOperator.getDisplayHeight(),
        )
    }

    private fun screenshotAnnotation(action: Action): ManualScreenshotAnnotation? {
        val args = runCatching {
            ActionCoordinateCodec.toScreenPixels(
                args = action.argsMap(),
                displaySize = ActionCoordinateCodec.DisplaySize(
                    deviceOperator.getDisplayWidth().toDouble(),
                    deviceOperator.getDisplayHeight().toDouble(),
                ),
            )
        }.getOrElse { action.argsMap() }
        val x = (args[OobActionSchema.ARG_X] as? Number)?.toFloat()
        val y = (args[OobActionSchema.ARG_Y] as? Number)?.toFloat()
        if (x != null && y != null) {
            return ManualScreenshotAnnotation.point(action.tool, x, y)
        }
        val x1 = (args[OobActionSchema.ARG_X1] as? Number)?.toFloat() ?: return null
        val y1 = (args[OobActionSchema.ARG_Y1] as? Number)?.toFloat() ?: return null
        return ManualScreenshotAnnotation(
            actionName = action.tool,
            x = x1,
            y = y1,
            endX = (args[OobActionSchema.ARG_X2] as? Number)?.toFloat(),
            endY = (args[OobActionSchema.ARG_Y2] as? Number)?.toFloat(),
        )
    }

    private fun isRecording(): Boolean = isStarted && !isPaused

    private fun buildDiagnostics(actions: List<ManualVlmRecordedAction>): Map<String, Any?> {
        val stats = engine.stats()
        val evidenceFailureCount = actions.count { !it.evidenceComplete }
        val complete = stats.pending == 0 &&
            stats.failed == 0 &&
            stats.received == stats.committed &&
            evidenceFailureCount == 0
        val backend = recordingBackend(actions)
        val completeness = when {
            !complete -> ManualRecordingDiagnostics.INCOMPLETE_OVERLAY_TOUCH
            backend == MANUAL_CONTROL_SOURCE -> ManualRecordingDiagnostics.COMPLETE_MANUAL_CONTROL
            else -> ManualRecordingDiagnostics.COMPLETE_OVERLAY_TOUCH
        }
        return linkedMapOf(
            "manual_recording" to linkedMapOf(
                "schema_version" to "oob.manual_recording.diagnostics.v2",
                "action_model" to "explicit_canonical_actions",
                "action_source" to backend,
                "recording_backend_counts" to actions.map {
                    it.recordingBackend
                }.groupingBy { it }.eachCount(),
                "completeness" to completeness,
                "guarantees_no_missing_clicks" to complete,
                "guarantee_scope" to "accepted_actions_while_process_alive",
                "process_crash_safe" to (onActionRecorded != null),
                "received_action_count" to stats.received,
                "committed_action_count" to stats.committed,
                "failed_action_count" to stats.failed,
                "pending_action_count" to stats.pending,
                "incomplete_state_count" to evidenceFailureCount,
                "a11_replay_actions_enabled" to false,
                "a11_role" to "observation_only",
                "raw_touch_enabled" to false,
                "raw_touch_requested" to enableRawTouch,
                "raw_touch_available" to false,
                "warning_message" to ManualRecordingDiagnostics.warningMessage(completeness),
            ).filterValues { it != null },
            "manual_recording_observation" to observationCapture.xmlDiagnostics(),
            "manual_recording_screenshots" to observationCapture.screenshotDiagnostics(),
        )
    }

    private fun recordingBackend(actions: List<ManualVlmRecordedAction>): String {
        val sources = actions.map(ManualVlmRecordedAction::recordingBackend).toSet()
        return when {
            sources.isEmpty() -> "explicit_actions"
            sources.size == 1 -> sources.first()
            else -> "mixed_explicit_actions"
        }
    }

    private fun directionLabel(direction: String?): String = when (direction) {
        "up" -> "向上"
        "down" -> "向下"
        "left" -> "向左"
        "right" -> "向右"
        else -> ""
    }

    private fun formatDuration(durationMs: Long): String = if (durationMs % 1_000L == 0L) {
        "${durationMs / 1_000L} 秒"
    } else {
        "${durationMs} 毫秒"
    }

    private companion object {
        private const val TAG = "ManualVlmTraceRecorder"
        private const val OVERLAY_TOUCH_SOURCE = "overlay_touch"
        private const val MANUAL_CONTROL_SOURCE = "manual_control"
        private const val TAP_ANCHOR_TTL_MS = 30_000L
        private const val MAX_TEXT_SUMMARY_CHARS = 80
        private const val MAX_SUMMARY_ACTIONS = 8
        private val SUPPORTED_KEYS = setOf("back", "home", "enter")
        private val SUPPORTED_GESTURES = setOf(
            OobActionSchema.TOOL_CLICK,
            OobActionSchema.TOOL_LONG_PRESS,
            OobActionSchema.TOOL_SWIPE,
        )
    }
}
