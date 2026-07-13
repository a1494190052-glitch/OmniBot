package cn.com.omnimind.assists.task.vlmserver

import android.content.Context
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.assists.ManualOverlayGestureReplayResult
import cn.com.omnimind.assists.ManualOverlayTouchGesture
import cn.com.omnimind.assists.ManualInputTarget
import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
import cn.com.omnimind.baselib.runlog.OobActionSchema
import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

data class ManualVlmTraceResult(
    val actions: List<ManualVlmRecordedAction>,
    val summary: String,
    val diagnostics: Map<String, Any?> = emptyMap(),
) {
    val actionCount: Int get() = actions.size
}

data class ManualVlmRecordedAction(
    val actionName: String,
    val title: String,
    val params: Map<String, Any?>,
    val packageName: String?,
    val beforeXml: String?,
    val afterXml: String?,
    val beforeScreenshot: ManualVlmScreenshotRef? = null,
    val afterScreenshot: ManualVlmScreenshotRef? = null,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val summary: String,
    val eventContext: Map<String, Any?> = emptyMap(),
)

internal fun selectManualInputTargetAfterClick(
    before: ManualInputTarget?,
    after: ManualInputTarget?,
    clickedFocusedTarget: ManualInputTarget?,
): ManualInputTarget? = after?.takeIf {
    it != before || clickedFocusedTarget != null
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
) {
    private data class TapAnchor(
        val x: Float,
        val y: Float,
        val recordedAtMs: Long,
    )

    private val stateLock = Any()
    private val journal = ManualRecordingJournal()
    private val deviceOperator = AndroidDeviceOperator(null, context)
    private val actionExecutor = ActionExecutor(
        deviceOperator = deviceOperator,
        contextManager = UIContextManager(),
    )
    private val observationCapture = ManualObservationCapture(
        context = context,
        sessionLabel = sessionLabel,
        debugScreenshotsRequested = enableDebugScreenshots,
    )
    private val engine = ManualRecordingEngine(
        journal = journal,
        observe = { stage, action -> captureObservation(stage, action) },
        execute = { action ->
            actionExecutor.act(
                action = action.tool,
                args = action.args,
                source = action.source,
            )
        },
        settleBeforeAfterObservation = { action ->
            if (action.tool != OobActionSchema.TOOL_WAIT) delay(AFTER_ACTION_OBSERVATION_DELAY_MS)
        },
    )

    @Volatile private var isStarted = false
    @Volatile private var isPaused = false
    private var lastTapAnchor: TapAnchor? = null

    fun start(): Boolean {
        if (isStarted) return true
        if (!AssistsService.isInit() || !AccessibilityController.initController()) {
            OmniLog.w(TAG, "manual recorder unavailable: accessibility service is not ready")
            return false
        }
        synchronized(stateLock) {
            isStarted = true
            isPaused = false
            lastTapAnchor = null
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
            lastTapAnchor = null
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
                it.params["recording_backend"] == OVERLAY_TOUCH_SOURCE
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
        if (text.isEmpty() || !isRecording()) return false
        val anchor = activeTapAnchor()
        val args = linkedMapOf<String, Any?>(
            OobActionSchema.ARG_TEXT to text,
            OobActionSchema.ARG_TARGET_DESCRIPTION to (
                inputTarget?.description ?: anchor?.let { "上次点击位置" }
            ),
            OobActionSchema.ARG_X to (inputTarget?.x ?: anchor?.x),
            OobActionSchema.ARG_Y to (inputTarget?.y ?: anchor?.y),
            OobActionSchema.ARG_NODE_RESOURCE_ID to inputTarget?.nodeResourceId,
        ).filterValues { it != null }
        return engine.perform(
            command(
                tool = OobActionSchema.TOOL_INPUT_TEXT,
                args = args,
                title = "输入文本",
                summary = "输入文本：${text.take(MAX_TEXT_SUMMARY_CHARS)}",
                source = MANUAL_CONTROL_SOURCE,
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
                args = mapOf(OobActionSchema.ARG_TIME_MS to durationMs),
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
        val action = gesture.toCanonicalAction()
        val outcome = engine.perform(action) { dispatchResult ->
            onGestureDispatched(
                dispatchResult.success && gesture.actionName == OobActionSchema.TOOL_CLICK,
            )
        }
        if (outcome.recorded && gesture.actionName == OobActionSchema.TOOL_CLICK) {
            synchronized(stateLock) {
                lastTapAnchor = TapAnchor(
                    x = gesture.startX,
                    y = gesture.startY,
                    recordedAtMs = System.currentTimeMillis(),
                )
            }
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

    private fun ManualOverlayTouchGesture.toCanonicalAction(): ManualCanonicalAction {
        val coordinateArgs = linkedMapOf<String, Any?>(
            "coordinate_space" to "screen_absolute_px",
            "display_width" to displayWidth.takeIf { it > 0 },
            "display_height" to displayHeight.takeIf { it > 0 },
        )
        return when (actionName) {
            OobActionSchema.TOOL_CLICK -> command(
                tool = actionName,
                args = coordinateArgs + mapOf(
                    OobActionSchema.ARG_X to startX,
                    OobActionSchema.ARG_Y to startY,
                ),
                title = "点击 (${startX.toInt()}, ${startY.toInt()})",
                summary = "点击屏幕 (${startX.toInt()}, ${startY.toInt()})",
                source = OVERLAY_TOUCH_SOURCE,
                startedAtMs = startedAtMs,
            )

            OobActionSchema.TOOL_LONG_PRESS -> command(
                tool = actionName,
                args = coordinateArgs + mapOf(
                    OobActionSchema.ARG_X to startX,
                    OobActionSchema.ARG_Y to startY,
                    OobActionSchema.ARG_DURATION_MS to durationMs.coerceAtLeast(1L),
                ),
                title = "长按 (${startX.toInt()}, ${startY.toInt()})",
                summary = "长按屏幕 (${startX.toInt()}, ${startY.toInt()})",
                source = OVERLAY_TOUCH_SOURCE,
                startedAtMs = startedAtMs,
            )

            else -> command(
                tool = OobActionSchema.TOOL_SWIPE,
                args = coordinateArgs + linkedMapOf(
                    OobActionSchema.ARG_X1 to startX,
                    OobActionSchema.ARG_Y1 to startY,
                    OobActionSchema.ARG_X2 to endX,
                    OobActionSchema.ARG_Y2 to endY,
                    OobActionSchema.ARG_DURATION_MS to durationMs.coerceAtLeast(1L),
                    OobActionSchema.ARG_DIRECTION to direction,
                    OobActionSchema.ARG_DISTANCE to distancePx,
                ).filterValues { it != null },
                title = "${directionLabel(direction)}滑动",
                summary = "从 (${startX.toInt()}, ${startY.toInt()}) 滑动到 " +
                    "(${endX.toInt()}, ${endY.toInt()})",
                source = OVERLAY_TOUCH_SOURCE,
                startedAtMs = startedAtMs,
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
    ): ManualCanonicalAction = ManualCanonicalAction(
        tool = tool,
        args = args,
        title = title,
        summary = summary,
        source = source,
        startedAtMs = startedAtMs,
    )

    private fun captureObservation(
        stage: String,
        action: ManualCanonicalAction,
    ): ManualRecordingObservation {
        val xml = observationCapture.captureXml(stage).xml
        val screenshot = observationCapture.captureScreenshot(
            stage = stage,
            annotation = screenshotAnnotation(action),
        )
        return ManualRecordingObservation(
            xml = xml,
            screenshot = screenshot,
            packageName = runCatching { deviceOperator.currentPackageName() }.getOrNull(),
        )
    }

    private fun screenshotAnnotation(action: ManualCanonicalAction): ManualScreenshotAnnotation? {
        val x = (action.args[OobActionSchema.ARG_X] as? Number)?.toFloat()
        val y = (action.args[OobActionSchema.ARG_Y] as? Number)?.toFloat()
        if (x != null && y != null) {
            return ManualScreenshotAnnotation.point(action.tool, x, y)
        }
        val x1 = (action.args[OobActionSchema.ARG_X1] as? Number)?.toFloat() ?: return null
        val y1 = (action.args[OobActionSchema.ARG_Y1] as? Number)?.toFloat() ?: return null
        return ManualScreenshotAnnotation(
            actionName = action.tool,
            x = x1,
            y = y1,
            endX = (action.args[OobActionSchema.ARG_X2] as? Number)?.toFloat(),
            endY = (action.args[OobActionSchema.ARG_Y2] as? Number)?.toFloat(),
        )
    }

    private fun isRecording(): Boolean = isStarted && !isPaused

    private fun activeTapAnchor(): TapAnchor? = synchronized(stateLock) {
        lastTapAnchor?.takeIf {
            System.currentTimeMillis() - it.recordedAtMs <= TAP_ANCHOR_TTL_MS
        }
    }

    private fun buildDiagnostics(actions: List<ManualVlmRecordedAction>): Map<String, Any?> {
        val stats = engine.stats()
        val complete = stats.pending == 0 && stats.failed == 0 && stats.received == stats.committed
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
                "recording_backend_counts" to actions.mapNotNull {
                    it.params["recording_backend"]?.toString()
                }.groupingBy { it }.eachCount(),
                "completeness" to completeness,
                "guarantees_no_missing_clicks" to complete,
                "guarantee_scope" to "accepted_actions_while_process_alive",
                "process_crash_safe" to false,
                "received_action_count" to stats.received,
                "committed_action_count" to stats.committed,
                "failed_action_count" to stats.failed,
                "pending_action_count" to stats.pending,
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
        val sources = actions.mapNotNull { it.params["recording_backend"]?.toString() }.toSet()
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
        private const val AFTER_ACTION_OBSERVATION_DELAY_MS = 250L
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
