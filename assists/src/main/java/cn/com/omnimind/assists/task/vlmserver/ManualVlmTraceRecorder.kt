package cn.com.omnimind.assists.task.vlmserver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import cn.com.omnimind.accessibility.action.OmniGestureDispatchTimeoutException
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.accessibility.service.AssistsServiceListener
import cn.com.omnimind.assists.ManualOverlayGestureReplayResult
import cn.com.omnimind.assists.ManualOverlayTouchGesture
import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
import cn.com.omnimind.baselib.runlog.OobActionSchema
import cn.com.omnimind.baselib.util.ImageQuality
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.omniintelligence.models.ScrollDirection
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class ManualVlmTraceResult(
    val actions: List<ManualVlmRecordedAction>,
    val summary: String,
    val diagnostics: Map<String, Any?> = emptyMap()
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
    val eventContext: Map<String, Any?> = emptyMap()
)

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
    val annotation: Map<String, Any?> = emptyMap()
) {
    fun asMap(): Map<String, Any?> =
        linkedMapOf<String, Any?>(
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
            "storage" to "app_private_files"
        ).apply {
            if (annotation.isNotEmpty()) {
                put("annotation", annotation)
            }
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
    val debugScreenshotSkippedCount: Int
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
        "debug_screenshot_skipped_count" to debugScreenshotSkippedCount
    ).filterValues { it != null }
}

internal object ManualRecordingDiagnostics {
    const val COMPLETE_OVERLAY_TOUCH = "complete_overlay_touch"
    const val INCOMPLETE_OVERLAY_TOUCH = "incomplete_overlay_touch"
    const val COMPLETE_RAW_TOUCH = "complete_raw_touch"
    const val MISSING_RAW_TOUCH = "missing_raw_touch"
    const val RAW_TOUCH_INTERRUPTED = "raw_touch_interrupted"

    @Deprecated("Manual recording requires concrete touch capture; use MISSING_RAW_TOUCH.")
    const val PARTIAL_SEMANTIC_ONLY = MISSING_RAW_TOUCH

    @Deprecated("Manual recording requires concrete touch capture; use RAW_TOUCH_INTERRUPTED.")
    const val PARTIAL_RAW_TOUCH_INTERRUPTED = RAW_TOUCH_INTERRUPTED

    fun completeness(rawTouchAvailable: Boolean, rawTouchActiveAtStop: Boolean?): String {
        return when {
            rawTouchAvailable && rawTouchActiveAtStop == true -> COMPLETE_RAW_TOUCH
            rawTouchAvailable -> RAW_TOUCH_INTERRUPTED
            else -> MISSING_RAW_TOUCH
        }
    }

    fun guaranteesNoMissingClicks(rawTouchAvailable: Boolean, rawTouchActiveAtStop: Boolean?): Boolean {
        return completeness(rawTouchAvailable, rawTouchActiveAtStop) == COMPLETE_RAW_TOUCH
    }

    fun warningMessage(completeness: String): String? = when (completeness) {
        COMPLETE_OVERLAY_TOUCH -> null
        INCOMPLETE_OVERLAY_TOUCH -> "overlay touch 录制未全部落盘，本次轨迹可能遗漏点击/滑动"
        COMPLETE_RAW_TOUCH -> null
        RAW_TOUCH_INTERRUPTED -> "raw touch 录制中断，本次轨迹可能遗漏点击/滑动"
        else -> "raw touch 不可用，且没有真实触摸动作锚点，本次轨迹可能遗漏点击/滑动"
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

/**
 * Records user actions during VLM takeover from concrete touch streams.
 *
 * Accessibility is evidence only: it provides XML/window observations and text
 * content for an input that was anchored by a real overlay/raw touch. A11-only
 * click, long-click, focus, and swipe events are intentionally not replayable
 * actions except for a narrow post-input App button fallback, because generic
 * A11-only actions can be incomplete and can race with overlay replay.
 */
class ManualVlmTraceRecorder(
    private val context: Context,
    private val sessionLabel: String,
    private val enableRawTouch: Boolean = false,
    private val enableDebugScreenshots: Boolean = false,
    private val onActionRecorded: ((Int, ManualVlmRecordedAction) -> Unit)? = null
) {
    private val ownPackageName = context.packageName
    private val recordingLock = java.lang.Object()
    private val accessibilityEventDrainLock = java.lang.Object()
    private val accessibilityEventScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val actionPersistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val accessibilityEventJobs = AtomicInteger(0)
    private val overlayGestureMutex = Mutex()
    private val recordedActions = mutableListOf<ManualVlmRecordedAction>()
    private val rawGestureBeforeXml = mutableMapOf<Long, String?>()
    private val rawGestureBeforeScreenshot = mutableMapOf<Long, ManualVlmScreenshotRef?>()
    private var textInputAnchor: TextInputAnchor? = null
    private var pendingText: PendingTextAction? = null
    private var postInputClickWindow: PostInputClickWindow? = null
    private var rawTouchRecorder: ManualRawTouchRecorder? = null
    private var rawTouchStatus: ManualRawTouchStatus? = null
    private var lastDiscreteSignature: String = ""
    private var lastDiscreteAtMs: Long = 0L
    private var lastXmlSnapshot: String? = null
    private var lastScreenshotSnapshot: ManualVlmScreenshotRef? = null
    private var screenshotSkippedCount: Int = 0
    private var screenshotStoredCount: Int = 0
    private var screenshotFailedCount: Int = 0
    private var debugScreenshotDisabledReason: String? = null
    private var debugScreenshotLastErrorType: String? = null
    private var screenshotSequence: Int = 0
    private var xmlCaptureCount: Int = 0
    private var xmlCaptureSuccessCount: Int = 0
    private var xmlCaptureTimeoutOrEmptyCount: Int = 0
    private var xmlCaptureTotalMs: Long = 0L
    private var xmlCaptureMaxMs: Long = 0L
    private var xmlCaptureLastMs: Long = 0L
    private var xmlCaptureLastReason: String = ""
    private var accessibilityEventCount: Int = 0
    private var accessibilityIgnoredPackageCount: Int = 0
    private val accessibilityEventTypeCounts = linkedMapOf<String, Int>()
    private var unanchoredTextChangedRecordedCount: Int = 0
    private var unanchoredTextChangedSuppressedCount: Int = 0
    private var rawGestureStartedCount: Int = 0
    private var rawGestureFinishedCount: Int = 0
    private var rawGestureRecordedCount: Int = 0
    private var rawGestureIgnoredControlCount: Int = 0
    private var rawGeteventLineCount: Int = 0
    private var rawGeteventDroppedLineCount: Int = 0
    private val rawGeteventRecentLines = ArrayDeque<Map<String, Any?>>()
    private var rawTouchActiveAtStop: Boolean? = null
    private var overlayGestureStartedCount: Int = 0
    private var overlayGestureRecordedCount: Int = 0
    private var overlayGestureIgnoredControlCount: Int = 0
    private var overlayGestureFailedCount: Int = 0
    private var overlayCoordinateReplayCount: Int = 0
    private var overlayCoordinateReplayFailedCount: Int = 0
    private var overlayNodeReplayFallbackCount: Int = 0
    private var overlayNodeReplayFallbackFailedCount: Int = 0
    private var overlayGestureActiveCount: Int = 0
    private var overlayPostRecordTimeoutCount: Int = 0
    private var overlayKeyboardClickSuppressedCount: Int = 0
    private var imeSubmitRecordedCount: Int = 0
    private var manualControlRecordedCount: Int = 0
    private var manualControlTextChangedSuppressedCount: Int = 0
    private var manualControlTextChangeSuppressUntilMs: Long = 0L
    private var manualControlTextChangeSuppressText: String? = null
    private var actionPersistCallbackQueuedCount: Int = 0
    private var actionPersistCallbackFailedCount: Int = 0
    private var actionPersistCallbackLastErrorType: String? = null
    private var actionPersistCallbackLastErrorMessage: String? = null
    private var suppressedSemanticActionEventCount: Int = 0
    private var suppressedNonRawActionCount: Int = 0
    private var postInputA11ActionRecordedCount: Int = 0
    private var postInputA11ActionSuppressedCount: Int = 0
    private var windowTransitionEventCount: Int = 0
    private var accessibilityEventDrainTimeoutCount: Int = 0
    @Volatile private var isStarted = false
    @Volatile private var isPaused = false
    @Volatile private var lastUsablePackageName: String? = null

    private val listener = object : AssistsServiceListener {
        override fun onAccessibilityEvent(event: AccessibilityEvent) {
            handleAccessibilityEvent(event)
        }
    }

    fun start(): Boolean {
        if (isStarted) return true
        if (!AssistsService.isInit()) {
            OmniLog.w(TAG, "manual trace recorder skipped: accessibility service is not ready")
            return false
        }
        if (!AccessibilityController.initController()) {
            OmniLog.w(TAG, "manual trace recorder skipped: accessibility controller is not ready")
            return false
        }
        isStarted = true
        isPaused = false
        OmniLog.i(TAG, "manual trace recorder start: capture xml session=$sessionLabel")
        lastXmlSnapshot = captureCurrentXmlSafe("start")
        lastScreenshotSnapshot = null
        OmniLog.i(TAG, "manual trace recorder start: preseed text anchor session=$sessionLabel")
        textInputAnchor = preSeedFocusedTextInputAnchorFrom(lastXmlSnapshot, lastScreenshotSnapshot)
        if (enableRawTouch) {
            OmniLog.i(TAG, "manual trace recorder start: start raw touch session=$sessionLabel")
            startRawTouchRecorder()
        } else {
            rawTouchStatus = ManualRawTouchStatus(
                available = false,
                backend = RAW_TOUCH_BACKEND,
                errorCode = "raw_touch_disabled",
                errorMessage = "Raw getevent recording is disabled; using overlay touch actions with Accessibility evidence"
            )
        }
        OmniLog.i(TAG, "manual trace recorder start: add listener session=$sessionLabel")
        AssistsService.addListener(listener)
        OmniLog.d(TAG, "manual trace recorder started: $sessionLabel rawTouch=$enableRawTouch")
        return true
    }

    /**
     * Pauses event collection while keeping the learning session alive.
     *
     * @return True when the recorder is active and is now paused.
     */
    fun pause(): Boolean {
        if (!isStarted) return false
        if (isPaused) return true
        awaitOverlayRecordJobs("pause")
        awaitAccessibilityEventJobs("pause")
        val currentXml = captureCurrentXmlSafe("pause")
        val currentScreenshot = null
        materializePendingTextFromXml(
            xml = currentXml,
            screenshot = currentScreenshot,
            resolutionSuffix = "focused_xml_pause"
        )
        flushPendingText(currentXml)
        postInputClickWindow = null
        lastXmlSnapshot = currentXml
        lastScreenshotSnapshot = currentScreenshot
        isPaused = true
        rawTouchRecorder?.pause()
        OmniLog.d(TAG, "manual trace recorder paused: $sessionLabel")
        return true
    }

    /**
     * Resumes event collection and refreshes the current screen baseline.
     *
     * @return True when the recorder is active and is now recording.
     */
    fun resume(): Boolean {
        if (!isStarted) return false
        if (!isPaused) return true
        lastXmlSnapshot = captureCurrentXmlSafe("resume")
        lastScreenshotSnapshot = null
        postInputClickWindow = null
        lastDiscreteSignature = ""
        lastDiscreteAtMs = 0L
        textInputAnchor = preSeedFocusedTextInputAnchorFrom(lastXmlSnapshot, lastScreenshotSnapshot)
        isPaused = false
        rawTouchRecorder?.resume()
        OmniLog.d(TAG, "manual trace recorder resumed: $sessionLabel")
        return true
    }

    fun stop(): ManualVlmTraceResult {
        awaitOverlayRecordJobs("stop")
        if (isStarted) {
            AssistsService.removeListener(listener)
            awaitAccessibilityEventJobs("stop")
            isStarted = false
        }
        stopRawTouchRecorder()
        isPaused = false
        val stopXml = currentXmlForTextFallback("stop") ?: lastXmlSnapshot
        materializePendingTextFromXml(
            xml = stopXml,
            screenshot = lastScreenshotSnapshot,
            resolutionSuffix = "focused_xml_stop"
        )
        flushPendingText(stopXml)
        postInputClickWindow = null
        val summary = buildSummary(recordedActions)
        OmniLog.d(TAG, "manual trace recorder stopped: $sessionLabel actions=${recordedActions.size}")
        return ManualVlmTraceResult(
            actions = recordedActions.toList(),
            summary = summary,
            diagnostics = buildDiagnostics()
        )
    }

    internal fun snapshot(): ManualVlmTraceSnapshot = synchronized(recordingLock) {
        val pendingSummary = when {
            pendingText != null -> "正在输入：${pendingText?.label.orEmpty()}"
            else -> null
        }
        ManualVlmTraceSnapshot(
            isStarted = isStarted,
            isPaused = isPaused,
            actionCount = recordedActions.size,
            latestActionSummary = pendingSummary ?: recordedActions.lastOrNull()?.summary,
            pendingActionSummary = pendingSummary,
            accessibilityEventCount = accessibilityEventCount,
            rawTouchEnabled = enableRawTouch,
            rawTouchAvailable = rawTouchStatus?.available == true,
            overlayTouchRecordedCount = overlayGestureRecordedCount,
            recordingBackend = recordingBackendForStatus(),
            debugScreenshotsEnabled = debugScreenshotsActive(),
            debugScreenshotStoredCount = screenshotStoredCount,
            debugScreenshotFailedCount = screenshotFailedCount,
            debugScreenshotSkippedCount = screenshotSkippedCount
        )
    }

    fun hasActiveTextInputAnchor(): Boolean = synchronized(recordingLock) {
        if (pendingText != null) return@synchronized true
        val anchor = textInputAnchor
        if (anchor != null) {
            val freshAnchor = System.currentTimeMillis() - anchor.finishedAtMs <=
                TEXT_INPUT_ANCHOR_ACTIVE_TTL_MS
            if (freshAnchor) {
                return@synchronized true
            }
        }
        hasFocusedTextInputTargetInXml(lastXmlSnapshot)
    }

    fun prepareImeSubmitRecording(): Boolean {
        val shouldTryFocusedXml = synchronized(recordingLock) {
            if (!isStarted || isPaused) return false
            pendingText == null
        }
        val fallbackXml = if (shouldTryFocusedXml) {
            currentXmlForTextFallback("ime_submit")
        } else {
            null
        }
        return synchronized(recordingLock) {
            if (!isStarted || isPaused) return@synchronized false
            if (pendingText == null && !fallbackXml.isNullOrBlank()) {
                materializePendingTextFromXml(
                    xml = fallbackXml,
                    screenshot = lastScreenshotSnapshot,
                    resolutionSuffix = "focused_xml_ime_submit"
                )
            }
            flushPendingText(fallbackXml ?: lastXmlSnapshot)
            true
        }
    }

    suspend fun recordImeSubmitGesture(gesture: ManualOverlayTouchGesture): Boolean {
        val shouldTryFocusedXml = synchronized(recordingLock) {
            if (!isStarted || isPaused) return false
            pendingText == null
        }
        val fallbackXml = if (shouldTryFocusedXml) {
            currentXmlForTextFallback("ime_submit")
        } else {
            null
        }
        val beforeXml = fallbackXml ?: synchronized(recordingLock) { lastXmlSnapshot }
        val startedAtMs = System.currentTimeMillis()
        val target = synchronized(recordingLock) {
            if (!isStarted || isPaused) return false
            if (pendingText == null && !fallbackXml.isNullOrBlank()) {
                materializePendingTextFromXml(
                    xml = fallbackXml,
                    screenshot = lastScreenshotSnapshot,
                    resolutionSuffix = "focused_xml_ime_submit"
                )
            }
            flushPendingText(beforeXml)
            clearPostInputClickWindowLocked()
            manualInputTextTargetFor(beforeXml, "")
        }
        val dispatchOutcome = runCatching {
            if (target != null) {
                AccessibilityController.pressImeEnterToBestNode(
                    targetDescription = target.label,
                    x = target.bounds.centerX().toFloat(),
                    y = target.bounds.centerY().toFloat(),
                    nodeResourceId = target.resourceId.orEmpty()
                )
            } else {
                AccessibilityController.pressHotKey("ENTER")
            }
            OverlayDispatchOutcome.completed()
        }.getOrElse { error ->
            OverlayDispatchOutcome.fromError(error)
        }
        if (dispatchOutcome.executed) {
            delay(MANUAL_CONTROL_AFTER_ACTION_CAPTURE_DELAY_MS)
        }
        val afterXml = if (dispatchOutcome.executed) {
            currentXmlForTextFallback("ime_submit_after")
        } else {
            null
        }
        return synchronized(recordingLock) {
            if (!isStarted || isPaused) return@synchronized false
            lastXmlSnapshot = afterXml ?: beforeXml ?: lastXmlSnapshot
            if (!dispatchOutcome.executed) {
                OmniLog.w(
                    TAG,
                    "manual ime submit dispatch ${dispatchOutcome.status}: ${dispatchOutcome.errorMessage}"
                )
                return@synchronized false
            }
            appendImeSubmitGesture(
                gesture = gesture,
                beforeXml = beforeXml,
                afterXml = afterXml,
                startedAtMs = startedAtMs,
                finishedAtMs = System.currentTimeMillis(),
                dispatchOutcome = dispatchOutcome
            )
            imeSubmitRecordedCount += 1
            true
        }
    }

    suspend fun recordManualInputText(text: String): Boolean {
        val safeText = normalizeInputTextContent(text)
        if (safeText.isBlank()) return false
        val fallbackXml = synchronized(recordingLock) {
            if (!isStarted || isPaused) return false
            lastXmlSnapshot
        } ?: currentXmlForTextFallback("manual_input_text")
        val startedAtMs = System.currentTimeMillis()
        val target = synchronized(recordingLock) {
            if (!isStarted || isPaused) return@synchronized null
            flushPendingText(fallbackXml ?: lastXmlSnapshot)
            clearPostInputClickWindowLocked()
            manualInputTextTargetFor(fallbackXml ?: lastXmlSnapshot, safeText)
        }
        synchronized(recordingLock) {
            setManualControlTextChangeSuppressionLocked(safeText)
        }
        val dispatchOutcome = runCatching {
            if (target != null) {
                AccessibilityController.inputTextToBestNode(
                    text = safeText,
                    targetDescription = target.label,
                    x = target.bounds.centerX().toFloat(),
                    y = target.bounds.centerY().toFloat(),
                    nodeResourceId = target.resourceId.orEmpty()
                )
            } else {
                AccessibilityController.inputTextToFocusedNode(safeText)
            }
            OverlayDispatchOutcome.completed()
        }.getOrElse { error ->
            OverlayDispatchOutcome.fromError(error)
        }
        if (dispatchOutcome.executed) {
            delay(MANUAL_CONTROL_AFTER_ACTION_CAPTURE_DELAY_MS)
        }
        val afterXml = if (dispatchOutcome.executed) {
            currentXmlForTextFallback("manual_input_text_after")
        } else {
            null
        }
        return synchronized(recordingLock) {
            if (!isStarted || isPaused) return@synchronized false
            lastXmlSnapshot = afterXml ?: fallbackXml ?: lastXmlSnapshot
            if (!dispatchOutcome.executed) {
                OmniLog.w(
                    TAG,
                    "manual input_text dispatch ${dispatchOutcome.status}: ${dispatchOutcome.errorMessage}"
                )
                return@synchronized false
            }
            val resolvedTarget = target
                ?: manualInputTextTargetFor(afterXml, safeText)
                ?: manualFallbackTextTarget(fallbackXml ?: afterXml, safeText)
            appendManualInputText(
                text = safeText,
                target = resolvedTarget,
                beforeXml = fallbackXml,
                afterXml = afterXml,
                startedAtMs = startedAtMs,
                finishedAtMs = System.currentTimeMillis(),
                dispatchOutcome = dispatchOutcome
            )
            manualControlRecordedCount += 1
            true
        }
    }

    suspend fun recordManualPressKey(key: String): Boolean {
        val canonicalKey = normalizeManualPressKey(key) ?: return false
        val fallbackXml = synchronized(recordingLock) {
            if (!isStarted || isPaused) return false
            lastXmlSnapshot
        } ?: currentXmlForTextFallback("manual_press_key")
        val startedAtMs = System.currentTimeMillis()
        val target = synchronized(recordingLock) {
            if (!isStarted || isPaused) return@synchronized null
            materializePendingTextFromXml(
                xml = fallbackXml,
                screenshot = lastScreenshotSnapshot,
                resolutionSuffix = "manual_press_key_before"
            )
            flushPendingText(fallbackXml ?: lastXmlSnapshot)
            clearPostInputClickWindowLocked()
            manualInputTextTargetFor(fallbackXml ?: lastXmlSnapshot, "")
        }
        val dispatchOutcome = runCatching {
            if (canonicalKey == "enter" && target != null) {
                AccessibilityController.pressImeEnterToBestNode(
                    targetDescription = target.label,
                    x = target.bounds.centerX().toFloat(),
                    y = target.bounds.centerY().toFloat(),
                    nodeResourceId = target.resourceId.orEmpty()
                )
            } else {
                AccessibilityController.pressHotKey(canonicalKey.uppercase())
            }
            OverlayDispatchOutcome.completed()
        }.getOrElse { error ->
            OverlayDispatchOutcome.fromError(error)
        }
        if (dispatchOutcome.executed) {
            delay(MANUAL_CONTROL_AFTER_ACTION_CAPTURE_DELAY_MS)
        }
        val afterXml = if (dispatchOutcome.executed) {
            currentXmlForTextFallback("manual_press_key_after")
        } else {
            null
        }
        return synchronized(recordingLock) {
            if (!isStarted || isPaused) return@synchronized false
            lastXmlSnapshot = afterXml ?: fallbackXml ?: lastXmlSnapshot
            if (!dispatchOutcome.executed) {
                OmniLog.w(
                    TAG,
                    "manual press_key dispatch ${dispatchOutcome.status}: ${dispatchOutcome.errorMessage}"
                )
                return@synchronized false
            }
            appendManualPressKey(
                key = canonicalKey,
                target = target,
                beforeXml = fallbackXml,
                afterXml = afterXml,
                startedAtMs = startedAtMs,
                finishedAtMs = System.currentTimeMillis(),
                dispatchOutcome = dispatchOutcome
            )
            manualControlRecordedCount += 1
            true
        }
    }

    suspend fun recordOverlayGesture(
        gesture: ManualOverlayTouchGesture,
        onGestureDispatched: suspend (mayOpenIme: Boolean) -> Unit = {}
    ): ManualOverlayGestureReplayResult = overlayGestureMutex.withLock {
        recordOverlayGestureSerial(gesture, onGestureDispatched)
    }

    private suspend fun recordOverlayGestureSerial(
        gesture: ManualOverlayTouchGesture,
        onGestureDispatched: suspend (mayOpenIme: Boolean) -> Unit
    ): ManualOverlayGestureReplayResult {
        val operationId = synchronized(recordingLock) {
            if (!isStarted || isPaused) return ManualOverlayGestureReplayResult(executed = false)
            overlayGestureStartedCount += 1
            overlayOperationId(gesture, overlayGestureStartedCount)
        }
        // captureCurrentXml() is a blocking binder call (service.windows + window.root).
        // It has no internal timeout and can hang indefinitely during UI transitions.
        // Run on a separate IO thread so the timeout can free the processing coroutine
        // even if the underlying binder call is still waiting.
        val beforeXmlCapture = captureCurrentXmlTimed("${operationId}_before")
        val beforeXml = beforeXmlCapture.xml
        val beforeScreenshot = captureCurrentScreenshotRef(
            stage = "${operationId}_before",
            annotation = ScreenshotAnnotation.forGesture(gesture)
        ) ?: synchronized(recordingLock) {
            if (debugScreenshotsActive()) null else lastScreenshotSnapshot
        }
        val mayOpenIme = gesture.actionName == OobActionSchema.TOOL_CLICK &&
            overlayClickMayOpenIme(beforeXml, gesture.startX, gesture.startY)
        val touchX = if (gesture.actionName == OobActionSchema.TOOL_SWIPE) {
            (gesture.startX + gesture.endX) / 2f
        } else {
            gesture.startX
        }
        val touchY = if (gesture.actionName == OobActionSchema.TOOL_SWIPE) {
            (gesture.startY + gesture.endY) / 2f
        } else {
            gesture.startY
        }
        if (coordinateHitsIgnoredTarget(beforeXml, touchX, touchY)) {
            synchronized(recordingLock) {
                overlayGestureIgnoredControlCount += 1
            }
            OmniLog.d(TAG, "manual overlay touch ignored OOB/control gesture")
            return ManualOverlayGestureReplayResult(
                executed = false,
                mayOpenIme = false,
                ignoredControl = true
            )
        }

        synchronized(recordingLock) {
            overlayGestureActiveCount += 1
        }
        try {
            val dispatchOutcome = try {
                performOverlayGesture(gesture)
                OverlayDispatchOutcome.completed()
            } catch (error: Throwable) {
                if (error is CancellationException && !currentCoroutineContext().isActive) {
                    throw error
                }
                synchronized(recordingLock) { overlayGestureFailedCount += 1 }
                val outcome = OverlayDispatchOutcome.fromError(error)
                OmniLog.w(
                    TAG,
                    "manual overlay touch dispatch ${outcome.status}: ${outcome.errorMessage}"
                )
                outcome
            } finally {
                try {
                    onGestureDispatched(mayOpenIme)
                } catch (error: Exception) {
                    OmniLog.w(TAG, "manual overlay dispatch callback failed: ${error.message}")
                }
            }

            val recorded = synchronized(recordingLock) {
                if (!isStarted || isPaused) return@synchronized false
                materializePendingTextFromXml(
                    xml = beforeXml,
                    screenshot = beforeScreenshot,
                    resolutionSuffix = "focused_xml_before_next_touch"
                )
                val currentTextAnchorId = if (gesture.actionName == OobActionSchema.TOOL_CLICK) {
                    overlayTextAnchorId(gesture)
                } else {
                    null
                }
                if (gesture.actionName == OobActionSchema.TOOL_CLICK) {
                    rememberTextInputAnchorFromRealTouch(
                        beforeXml = beforeXml,
                        beforeScreenshot = beforeScreenshot,
                        x = gesture.startX,
                        y = gesture.startY,
                        backend = OVERLAY_TOUCH_BACKEND,
                        anchorId = currentTextAnchorId.orEmpty(),
                        startedAtMs = gesture.startedAtMs,
                        finishedAtMs = gesture.finishedAtMs
                    )
                } else {
                    clearTextInputAnchor()
                }
                flushPendingTextUnlessAnchoredTo(currentTextAnchorId, beforeXml)
                clearPostInputClickWindowLocked()
                when (gesture.actionName) {
                    OobActionSchema.TOOL_CLICK, OobActionSchema.TOOL_LONG_PRESS -> appendOverlayClickGesture(
                        gesture = gesture,
                        beforeXml = beforeXml,
                        beforeScreenshot = beforeScreenshot,
                        operationId = operationId,
                        dispatchOutcome = dispatchOutcome,
                        beforeXmlCaptureMs = beforeXmlCapture.durationMs
                    )
                    OobActionSchema.TOOL_SWIPE -> appendOverlaySwipeGesture(
                        gesture = gesture,
                        beforeXml = beforeXml,
                        beforeScreenshot = beforeScreenshot,
                        operationId = operationId,
                        dispatchOutcome = dispatchOutcome,
                        beforeXmlCaptureMs = beforeXmlCapture.durationMs
                    )
                    else -> {
                        overlayGestureFailedCount += 1
                        OmniLog.w(TAG, "manual overlay touch ignored unknown action=${gesture.actionName}")
                        return@synchronized false
                    }
                }
                overlayGestureRecordedCount += 1
                lastXmlSnapshot = beforeXml
                lastScreenshotSnapshot = beforeScreenshot ?: lastScreenshotSnapshot
                true
            }
            return ManualOverlayGestureReplayResult(
                executed = dispatchOutcome.executed,
                recorded = recorded,
                mayOpenIme = mayOpenIme
            )
        } finally {
            synchronized(recordingLock) {
                decrementOverlayGestureActiveLocked()
            }
        }
    }

    private fun overlayOperationId(
        gesture: ManualOverlayTouchGesture,
        sequence: Int
    ): String = "overlay_${gesture.startedAtMs}_$sequence"

    private data class OverlayDispatchOutcome(
        val status: String,
        val executed: Boolean,
        val errorCode: String? = null,
        val errorMessage: String? = null
    ) {
        companion object {
            fun completed(): OverlayDispatchOutcome = OverlayDispatchOutcome(
                status = DISPATCH_STATUS_COMPLETED,
                executed = true
            )

            fun fromError(error: Throwable): OverlayDispatchOutcome {
                val timeout = error is TimeoutCancellationException ||
                    error is OmniGestureDispatchTimeoutException ||
                    error.message.orEmpty().contains("dispatch_timeout", ignoreCase = true) ||
                    error.message.orEmpty().contains("Timed out", ignoreCase = true)
                val cancelled = error.message.orEmpty().contains("cancel", ignoreCase = true)
                val status = when {
                    timeout -> DISPATCH_STATUS_TIMEOUT
                    cancelled -> DISPATCH_STATUS_CANCELLED
                    else -> DISPATCH_STATUS_FAILED
                }
                val code = when (status) {
                    DISPATCH_STATUS_TIMEOUT -> "dispatch_timeout"
                    DISPATCH_STATUS_CANCELLED -> "dispatch_cancelled"
                    else -> "dispatch_failed"
                }
                return OverlayDispatchOutcome(
                    status = status,
                    executed = false,
                    errorCode = code,
                    errorMessage = error.message?.take(MAX_ERROR_MESSAGE_LENGTH)
                        ?: error::class.java.simpleName
                )
            }
        }
    }

    private fun overlayDispatchDiagnostics(
        operationId: String,
        beforeXml: String?,
        dispatchOutcome: OverlayDispatchOutcome,
        beforeXmlCaptureMs: Long?
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "operation_id" to operationId,
        "dispatch_status" to dispatchOutcome.status,
        "before_xml_present" to !beforeXml.isNullOrBlank(),
        "before_xml_capture_ms" to beforeXmlCaptureMs,
        "error_code" to dispatchOutcome.errorCode,
        "error_message" to dispatchOutcome.errorMessage
    ).filterValues { it != null }

    private fun awaitOverlayRecordJobs(reason: String = "manual_recording") {
        val deadlineMs = SystemClock.uptimeMillis() + OVERLAY_RECORD_DRAIN_TIMEOUT_MS
        synchronized(recordingLock) {
            while (overlayGestureActiveCount > 0) {
                val remainingMs = deadlineMs - SystemClock.uptimeMillis()
                if (remainingMs <= 0L) {
                    overlayPostRecordTimeoutCount += 1
                    OmniLog.w(
                        TAG,
                        "manual overlay drain timeout reason=$reason active=$overlayGestureActiveCount"
                    )
                    overlayGestureActiveCount = 0
                    recordingLock.notifyAll()
                    return
                }
                try {
                    recordingLock.wait(min(OVERLAY_RECORD_DRAIN_POLL_MS, remainingMs))
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    overlayPostRecordTimeoutCount += 1
                    OmniLog.w(TAG, "manual overlay drain interrupted reason=$reason")
                    overlayGestureActiveCount = 0
                    recordingLock.notifyAll()
                    return
                }
            }
        }
    }

    private fun awaitAccessibilityEventJobs(reason: String = "manual_recording") {
        val deadlineMs = SystemClock.uptimeMillis() + ACCESSIBILITY_EVENT_DRAIN_TIMEOUT_MS
        synchronized(accessibilityEventDrainLock) {
            while (accessibilityEventJobs.get() > 0) {
                val remainingMs = deadlineMs - SystemClock.uptimeMillis()
                if (remainingMs <= 0L) {
                    synchronized(recordingLock) {
                        accessibilityEventDrainTimeoutCount += 1
                    }
                    OmniLog.w(
                        TAG,
                        "manual accessibility event drain timeout reason=$reason active=${accessibilityEventJobs.get()}"
                    )
                    return
                }
                try {
                    accessibilityEventDrainLock.wait(min(ACCESSIBILITY_EVENT_DRAIN_POLL_MS, remainingMs))
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    synchronized(recordingLock) {
                        accessibilityEventDrainTimeoutCount += 1
                    }
                    OmniLog.w(TAG, "manual accessibility event drain interrupted reason=$reason")
                    return
                }
            }
        }
    }

    private fun decrementOverlayGestureActiveLocked() {
        overlayGestureActiveCount = (overlayGestureActiveCount - 1).coerceAtLeast(0)
        if (overlayGestureActiveCount == 0) {
            recordingLock.notifyAll()
        }
    }

    private fun handleAccessibilityEvent(event: AccessibilityEvent) {
        if (!isStarted) return
        val sourceSnapshot = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> sourceSnapshotFromEvent(event)
            else -> null
        }
        val eventCopy = AccessibilityEvent.obtain(event)
        accessibilityEventJobs.incrementAndGet()
        accessibilityEventScope.launch {
            try {
                synchronized(recordingLock) {
                    handleAccessibilityEventLocked(eventCopy, sourceSnapshot)
                }
            } catch (error: Throwable) {
                OmniLog.w(TAG, "manual accessibility event handling failed: ${error.message}")
            } finally {
                runCatching { eventCopy.recycle() }
                if (accessibilityEventJobs.decrementAndGet() == 0) {
                    synchronized(accessibilityEventDrainLock) {
                        accessibilityEventDrainLock.notifyAll()
                    }
                }
            }
        }
    }

    private fun handleAccessibilityEventLocked(
        event: AccessibilityEvent,
        sourceSnapshot: AccessibilitySourceSnapshot?
    ) {
        if (!isStarted) return
        if (isPaused) return
        val packageName = event.packageName?.toString()
        accessibilityEventCount += 1
        incrementCount(accessibilityEventTypeCounts, eventTypeName(event.eventType))
        if (shouldIgnorePackage(packageName)) {
            accessibilityIgnoredPackageCount += 1
            return
        }
        rememberUsablePackageNameLocked(packageName)
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                recordTextChanged(event, packageName, lastXmlSnapshot, lastScreenshotSnapshot, sourceSnapshot)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                windowTransitionEventCount += 1
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                val nowMs = System.currentTimeMillis()
                if (hasPostInputActionWindowLocked(nowMs)) {
                    recordPostInputActionLocked(event, packageName, nowMs, sourceSnapshot)
                } else {
                    suppressA11OnlyActionEvent(event)
                }
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> suppressA11OnlyActionEvent(event)
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> suppressA11OnlyActionEvent(event)
            else -> Unit
        }
    }

    private fun suppressSemanticActionEvent(event: AccessibilityEvent) {
        suppressedSemanticActionEventCount += 1
    }

    private fun suppressA11OnlyActionEvent(event: AccessibilityEvent) {
        suppressedNonRawActionCount += 1
        suppressSemanticActionEvent(event)
    }

    private fun hasPostInputActionWindowLocked(nowMs: Long): Boolean {
        if (pendingText != null) return true
        return activePostInputClickWindowLocked(nowMs) != null
    }

    private fun activePostInputClickWindowLocked(nowMs: Long): PostInputClickWindow? {
        val window = postInputClickWindow ?: return null
        if (nowMs <= window.expiresAtMs) return window
        postInputClickWindow = null
        return null
    }

    private fun clearPostInputClickWindowLocked() {
        postInputClickWindow = null
    }

    private fun recordPostInputActionLocked(
        event: AccessibilityEvent,
        packageName: String?,
        nowMs: Long,
        source: AccessibilitySourceSnapshot?
    ) {
        val pendingInput = pendingText
        val activeWindow = activePostInputClickWindowLocked(nowMs)
        if (pendingInput == null && activeWindow == null) {
            suppressA11OnlyActionEvent(event)
            return
        }
        val beforeXml = lastXmlSnapshot
        val bounds = source?.bounds
        if (source == null || bounds == null || bounds.isEmpty) {
            flushPendingText(beforeXml)
            postInputA11ActionSuppressedCount += 1
            suppressA11OnlyActionEvent(event)
            return
        }
        if (!isPostInputSourceFromExpectedApp(source, packageName, pendingInput, activeWindow) ||
            isPostInputSourceOnInputField(bounds, pendingInput, activeWindow)
        ) {
            flushPendingText(beforeXml)
            postInputA11ActionSuppressedCount += 1
            suppressA11OnlyActionEvent(event)
            return
        }
        val x = bounds.centerX().toFloat()
        val y = bounds.centerY().toFloat()
        val label = source.bestLabel().ifBlank { "按钮" }
        val actionName = if (event.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
            OobActionSchema.TOOL_LONG_PRESS
        } else {
            OobActionSchema.TOOL_CLICK
        }
        val title = if (actionName == OobActionSchema.TOOL_LONG_PRESS) "人工长按 $label" else "人工点击 $label"
        flushPendingText(beforeXml)
        clearPostInputClickWindowLocked()
        val resolvedPackageName = source.packageName ?: packageName
        val target = ManualEventTarget(
            label = label,
            bounds = Rect(bounds),
            packageName = resolvedPackageName,
            className = source.className,
            resourceId = source.viewIdResourceName,
            text = source.text,
            contentDescription = source.contentDescription,
            stableKey = source.stableKey(bounds),
            resolution = "event_source_post_input"
        )
        val params = linkedMapOf<String, Any?>(
            "target_description" to label,
            "x" to x,
            "y" to y,
            "bounds" to boundsString(bounds),
            "node_class" to source.className,
            "node_resource_id" to source.viewIdResourceName,
            "node_text" to source.text,
            "node_content_description" to source.contentDescription,
            "recording_backend" to A11Y_POST_INPUT_BACKEND,
            "coordinate_space" to SCREEN_ABSOLUTE_COORDINATE_SPACE,
            "execution_mode" to A11Y_ANCHORED_EXECUTION_MODE
        ).filterValues { it != null }
        appendRecordedAction(ManualVlmRecordedAction(
            actionName = actionName,
            title = title,
            params = params,
            packageName = resolvedPackageName,
            beforeXml = beforeXml,
            afterXml = null,
            beforeScreenshot = null,
            afterScreenshot = null,
            startedAtMs = nowMs,
            finishedAtMs = nowMs,
            summary = title,
            eventContext = eventContextFor(event, target, source)
        ))
        postInputA11ActionRecordedCount += 1
    }

    private fun isPostInputSourceFromExpectedApp(
        source: AccessibilitySourceSnapshot,
        eventPackageName: String?,
        pendingInput: PendingTextAction?,
        activeWindow: PostInputClickWindow?
    ): Boolean {
        val expectedPackage = firstNonBlank(
            pendingInput?.packageName,
            activeWindow?.inputPackageName
        ).ifBlank { null }
        if (expectedPackage == null) return true
        val sourcePackage = firstNonBlank(source.packageName, eventPackageName).ifBlank { null }
            ?: return true
        return sourcePackage == expectedPackage
    }

    private fun isPostInputSourceOnInputField(
        bounds: Rect,
        pendingInput: PendingTextAction?,
        activeWindow: PostInputClickWindow?
    ): Boolean {
        val anchorBounds = textInputAnchor?.target?.bounds
        return anchorBounds == bounds ||
            pendingInput?.bounds == bounds ||
            activeWindow?.inputBounds == bounds ||
            sourceBoundsContainsInput(bounds, pendingInput, activeWindow)
    }

    private fun sourceBoundsContainsInput(
        bounds: Rect,
        pendingInput: PendingTextAction?,
        activeWindow: PostInputClickWindow?
    ): Boolean {
        val pendingBounds = pendingInput?.bounds
        val activeBounds = activeWindow?.inputBounds
        return (pendingBounds != null && bounds.contains(pendingBounds)) ||
            (activeBounds != null && bounds.contains(activeBounds))
    }

    private fun startRawTouchRecorder(): Boolean {
        val metrics = context.resources.displayMetrics
        val recorder = ManualRawTouchRecorder(
            context = context,
            displayWidth = metrics.widthPixels.coerceAtLeast(1),
            displayHeight = metrics.heightPixels.coerceAtLeast(1),
            onGestureStarted = ::rememberRawGestureStart,
            onGestureFinished = ::recordRawTouchGesture,
            onRawEventLine = ::recordRawGeteventLine
        )
        val status = recorder.start()
        rawTouchStatus = status
        if (status.available) {
            rawTouchRecorder = recorder
            OmniLog.d(TAG, "manual trace raw touch enabled: ${status.asMap()}")
            return true
        } else {
            rawTouchRecorder = null
            OmniLog.w(TAG, "manual trace raw touch unavailable: ${status.asMap()}")
            return false
        }
    }

    private fun stopRawTouchRecorder() {
        rawTouchActiveAtStop = rawTouchRecorder?.isActive()
        val status = rawTouchRecorder?.stop()
        if (status != null) {
            rawTouchStatus = status
        }
        rawTouchRecorder = null
        rawGestureBeforeXml.clear()
        rawGestureBeforeScreenshot.clear()
    }

    private fun rememberRawGestureStart(start: ManualRawTouchStart) {
        val cachedXml = synchronized(recordingLock) {
            if (!isStarted || isPaused) return
            rawGestureStartedCount += 1
            lastXmlSnapshot
        }
        val beforeXml = cachedXml ?: captureCurrentXmlSafe("raw_${start.gestureId}_start_before")
        val beforeScreenshot = captureCurrentScreenshotRef(
            stage = "raw_${start.gestureId}_before",
            annotation = ScreenshotAnnotation.point(
                actionName = "touch_start",
                x = start.x,
                y = start.y
            )
        ) ?: synchronized(recordingLock) {
            if (debugScreenshotsActive()) null else lastScreenshotSnapshot
        }
        synchronized(recordingLock) {
            if (!isStarted || isPaused) return
            rawGestureBeforeXml[start.gestureId] = beforeXml
            rawGestureBeforeScreenshot[start.gestureId] = beforeScreenshot
            rememberTextInputAnchorFromRealTouch(
                beforeXml = beforeXml,
                beforeScreenshot = beforeScreenshot,
                x = start.x,
                y = start.y,
                backend = RAW_TOUCH_BACKEND,
                anchorId = rawTextAnchorId(start.gestureId),
                startedAtMs = start.startedAtMs,
                finishedAtMs = start.startedAtMs
            )
        }
    }

    private fun recordRawGeteventLine(eventLine: ManualRawTouchEventLine) {
        synchronized(recordingLock) {
            if (!isStarted || isPaused) return
            rawGeteventLineCount += 1
            if (rawGeteventRecentLines.size >= MAX_RAW_GETEVENT_RECENT_LINES) {
                rawGeteventRecentLines.removeFirst()
                rawGeteventDroppedLineCount += 1
            }
            rawGeteventRecentLines.addLast(eventLine.asMap())
        }
    }

    private fun recordRawTouchGesture(gesture: ManualRawTouchGesture) {
        if (!isStarted || isPaused) return
        synchronized(recordingLock) {
            rawGestureFinishedCount += 1
        }
        val cachedBeforeXml = synchronized(recordingLock) {
            rawGestureBeforeXml.remove(gesture.gestureId) ?: lastXmlSnapshot
        }
        val beforeXml = cachedBeforeXml ?: captureCurrentXmlSafe("raw_${gesture.gestureId}_finish_before")
        val cachedBeforeScreenshot = synchronized(recordingLock) {
            rawGestureBeforeScreenshot.remove(gesture.gestureId)
        }
        val beforeScreenshot = cachedBeforeScreenshot ?: captureCurrentScreenshotRef(
            stage = "raw_${gesture.gestureId}_before",
            annotation = ScreenshotAnnotation.forRawGesture(gesture)
        ) ?: synchronized(recordingLock) {
            if (debugScreenshotsActive()) null else lastScreenshotSnapshot
        }
        val touchX = if (gesture.actionName == OobActionSchema.TOOL_SWIPE) gesture.startX else (gesture.startX + gesture.endX) / 2f
        val touchY = if (gesture.actionName == OobActionSchema.TOOL_SWIPE) gesture.startY else (gesture.startY + gesture.endY) / 2f
        if (coordinateHitsIgnoredTarget(beforeXml, touchX, touchY)) {
            synchronized(recordingLock) {
                rawGestureIgnoredControlCount += 1
            }
            OmniLog.d(TAG, "manual raw touch ignored OOB/control gesture")
            return
        }
        synchronized(recordingLock) {
            if (!isStarted || isPaused) return
            materializePendingTextFromXml(
                xml = beforeXml,
                screenshot = beforeScreenshot,
                resolutionSuffix = "focused_xml_before_next_touch"
            )
            val currentTextAnchorId = if (gesture.actionName == OobActionSchema.TOOL_CLICK) {
                rawTextAnchorId(gesture.gestureId)
            } else {
                null
            }
            if (gesture.actionName != OobActionSchema.TOOL_CLICK) {
                clearTextInputAnchor()
            }
            flushPendingTextUnlessAnchoredTo(currentTextAnchorId, beforeXml)
            clearPostInputClickWindowLocked()
            when (gesture.actionName) {
                OobActionSchema.TOOL_CLICK, OobActionSchema.TOOL_LONG_PRESS -> appendRawClickGesture(gesture, beforeXml, beforeScreenshot)
                OobActionSchema.TOOL_SWIPE -> appendRawSwipeGesture(gesture, beforeXml, beforeScreenshot)
            }
            rawGestureRecordedCount += 1
            lastXmlSnapshot = beforeXml ?: lastXmlSnapshot
            lastScreenshotSnapshot = beforeScreenshot ?: lastScreenshotSnapshot
        }
    }

    private fun appendRawClickGesture(
        gesture: ManualRawTouchGesture,
        beforeXml: String?,
        beforeScreenshot: ManualVlmScreenshotRef?
    ) {
        val x = ((gesture.startX + gesture.endX) / 2f)
        val y = ((gesture.startY + gesture.endY) / 2f)
        val target = targetAtCoordinateFromXml(
            xml = beforeXml,
            x = x,
            y = y,
            fallbackPackageName = packageNameFromXml(beforeXml),
            preferScrollable = false
        ) ?: coordinateOnlyTarget(beforeXml, x, y, "raw_touch_coordinate_only")
        val label = target.label.ifBlank { "屏幕坐标 ${x.toInt()},${y.toInt()}" }
        val recordedActionName = gesture.actionName
        val title = when (recordedActionName) {
            OobActionSchema.TOOL_LONG_PRESS -> "人工长按 $label"
            else -> "人工点击 $label"
        }
        val params = linkedMapOf<String, Any?>(
            "target_description" to label,
            "x" to x,
            "y" to y,
            "raw_x" to ((gesture.rawStartX + gesture.rawEndX) / 2),
            "raw_y" to ((gesture.rawStartY + gesture.rawEndY) / 2),
            "bounds" to boundsString(target.bounds),
            "node_class" to target.className,
            "node_resource_id" to target.resourceId,
            "node_text" to target.text,
            "node_content_description" to target.contentDescription,
            "duration_ms" to gesture.durationMs.takeIf { recordedActionName == OobActionSchema.TOOL_LONG_PRESS },
            "gesture_duration_ms" to gesture.durationMs,
            "gesture_distance_px" to gesture.distancePx,
            "gesture_point_count" to gesture.pointCount,
            "recording_backend" to gesture.backend,
            "target_resolution" to target.resolution
        ).filterValues { it != null }
        appendRecordedAction(
            ManualVlmRecordedAction(
                actionName = recordedActionName,
                title = title,
                params = params,
                packageName = target.packageName,
                beforeXml = beforeXml,
                afterXml = null,
                beforeScreenshot = beforeScreenshot,
                afterScreenshot = null,
                startedAtMs = gesture.startedAtMs,
                finishedAtMs = gesture.finishedAtMs,
                summary = title,
                eventContext = rawEventContextFor(gesture, target)
            )
        )
    }

    private fun appendRawSwipeGesture(
        gesture: ManualRawTouchGesture,
        beforeXml: String?,
        beforeScreenshot: ManualVlmScreenshotRef?
    ) {
        val midX = (gesture.startX + gesture.endX) / 2f
        val midY = (gesture.startY + gesture.endY) / 2f
        val target = targetAtCoordinateFromXml(
            xml = beforeXml,
            x = midX,
            y = midY,
            fallbackPackageName = packageNameFromXml(beforeXml),
            preferScrollable = true
        ) ?: coordinateOnlyTarget(beforeXml, midX, midY, "raw_touch_coordinate_only")
        val direction = rawSwipeDirection(gesture)
        val label = target.label.ifBlank { "当前页面" }
        val title = "人工滑动 $label"
        val params = linkedMapOf<String, Any?>(
            "target_description" to label,
            "x1" to gesture.startX,
            "y1" to gesture.startY,
            "x2" to gesture.endX,
            "y2" to gesture.endY,
            "raw_x1" to gesture.rawStartX,
            "raw_y1" to gesture.rawStartY,
            "raw_x2" to gesture.rawEndX,
            "raw_y2" to gesture.rawEndY,
            "duration_ms" to gesture.durationMs.coerceAtLeast(120L),
            "gesture_distance_px" to gesture.distancePx,
            "gesture_point_count" to gesture.pointCount,
            "direction" to direction,
            "bounds" to boundsString(target.bounds),
            "node_class" to target.className,
            "node_resource_id" to target.resourceId,
            "recording_backend" to gesture.backend,
            "target_resolution" to target.resolution
        ).filterValues { it != null }
        appendRecordedAction(
            ManualVlmRecordedAction(
                actionName = OobActionSchema.TOOL_SWIPE,
                title = title,
                params = params,
                packageName = target.packageName,
                beforeXml = beforeXml,
                afterXml = null,
                beforeScreenshot = beforeScreenshot,
                afterScreenshot = null,
                startedAtMs = gesture.startedAtMs,
                finishedAtMs = gesture.finishedAtMs,
                summary = title,
                eventContext = rawEventContextFor(gesture, target)
            )
        )
    }

    private suspend fun performOverlayGesture(gesture: ManualOverlayTouchGesture) {
        when (gesture.actionName) {
            OobActionSchema.TOOL_CLICK -> performOverlayClickGesture(gesture)
            OobActionSchema.TOOL_LONG_PRESS -> AccessibilityController.longClickCoordinate(
                gesture.startX,
                gesture.startY,
                gesture.durationMs.coerceAtLeast(OVERLAY_LONG_PRESS_MIN_DURATION_MS)
            )
            OobActionSchema.TOOL_SWIPE -> {
                val direction = overlaySwipeDirection(gesture)
                AccessibilityController.scrollCoordinate(
                    x = gesture.startX,
                    y = gesture.startY,
                    direction = direction,
                    distance = gesture.distancePx.coerceAtLeast(OVERLAY_SWIPE_MIN_DISTANCE_PX),
                    duration = gesture.durationMs.coerceAtLeast(OVERLAY_SWIPE_MIN_DURATION_MS)
                )
            }
            else -> throw IllegalArgumentException("Unsupported overlay gesture: ${gesture.actionName}")
        }
    }

    private suspend fun performOverlayClickGesture(gesture: ManualOverlayTouchGesture) {
        val coordinateResult = runCatching {
            AccessibilityController.clickCoordinate(
                gesture.startX,
                gesture.startY,
                timeoutMs = OVERLAY_CLICK_REPLAY_TIMEOUT_MS
            )
        }
        if (coordinateResult.isSuccess) {
            synchronized(recordingLock) {
                overlayCoordinateReplayCount += 1
            }
            return
        }

        synchronized(recordingLock) {
            overlayCoordinateReplayFailedCount += 1
        }
        val coordinateError = coordinateResult.exceptionOrNull()
        OmniLog.w(TAG, "overlay click coordinate replay failed: ${coordinateError?.message}")
        throw coordinateError ?: IllegalStateException("Overlay click replay failed")
    }

    private fun appendOverlayClickGesture(
        gesture: ManualOverlayTouchGesture,
        beforeXml: String?,
        beforeScreenshot: ManualVlmScreenshotRef?,
        operationId: String,
        dispatchOutcome: OverlayDispatchOutcome,
        beforeXmlCaptureMs: Long?
    ) {
        val x = gesture.startX
        val y = gesture.startY
        val target = targetAtCoordinateFromXml(
            xml = beforeXml,
            x = x,
            y = y,
            fallbackPackageName = packageNameFromXml(beforeXml),
            preferScrollable = false
        )?.asOverlayTarget() ?: coordinateOnlyTarget(beforeXml, x, y, "overlay_touch_coordinate_only")
        val label = target.label.ifBlank { "屏幕坐标 ${x.toInt()},${y.toInt()}" }
        val recordedActionName = gesture.actionName
        val title = when (recordedActionName) {
            OobActionSchema.TOOL_LONG_PRESS -> "人工长按 $label"
            else -> "人工点击 $label"
        }
        val params = (linkedMapOf<String, Any?>(
            "target_description" to label,
            "x" to x,
            "y" to y,
            "bounds" to boundsString(target.bounds),
            "node_class" to target.className,
            "node_resource_id" to target.resourceId,
            "node_text" to target.text,
            "node_content_description" to target.contentDescription,
            "duration_ms" to gesture.durationMs.takeIf { recordedActionName == OobActionSchema.TOOL_LONG_PRESS },
            "gesture_duration_ms" to gesture.durationMs,
            "gesture_distance_px" to gesture.distancePx,
            "recording_backend" to OVERLAY_TOUCH_BACKEND,
            "coordinate_space" to SCREEN_ABSOLUTE_COORDINATE_SPACE,
            "execution_mode" to SYNTHETIC_REPLAY_EXECUTION_MODE,
            "target_resolution" to target.resolution,
            "display_width" to gesture.displayWidth.takeIf { it > 0 },
            "display_height" to gesture.displayHeight.takeIf { it > 0 }
                    ) + overlayDispatchDiagnostics(
                        operationId,
                        beforeXml,
                        dispatchOutcome,
                        beforeXmlCaptureMs
                    )).filterValues { it != null }
        // Package name from XML is null for SurfaceView/WebView apps (no accessible nodes).
        // Fall back to the accessibility service's current window package so that the
        // compiled Function step carries a valid src_ctx.package_name for the checker.
        val resolvedPackageName = resolvedActionPackageName(target, beforeXml)
        appendRecordedAction(
            ManualVlmRecordedAction(
                actionName = recordedActionName,
                title = title,
                params = params,
                packageName = resolvedPackageName,
                beforeXml = beforeXml,
                afterXml = null,
                beforeScreenshot = beforeScreenshot,
                afterScreenshot = null,
                startedAtMs = gesture.startedAtMs,
                finishedAtMs = gesture.finishedAtMs,
                summary = title,
                eventContext = overlayEventContextFor(
                    gesture,
                    target,
                    operationId,
                    dispatchOutcome,
                    beforeXml,
                    beforeXmlCaptureMs
                )
            )
        )
    }

    private fun appendOverlaySwipeGesture(
        gesture: ManualOverlayTouchGesture,
        beforeXml: String?,
        beforeScreenshot: ManualVlmScreenshotRef?,
        operationId: String,
        dispatchOutcome: OverlayDispatchOutcome,
        beforeXmlCaptureMs: Long?
    ) {
        val midX = (gesture.startX + gesture.endX) / 2f
        val midY = (gesture.startY + gesture.endY) / 2f
        val target = targetAtCoordinateFromXml(
            xml = beforeXml,
            x = midX,
            y = midY,
            fallbackPackageName = packageNameFromXml(beforeXml),
            preferScrollable = true
        )?.asOverlayTarget() ?: coordinateOnlyTarget(beforeXml, midX, midY, "overlay_touch_coordinate_only")
        val direction = overlaySwipeDirectionName(gesture)
        val label = target.label.ifBlank { "当前页面" }
        val title = "人工滑动 $label"
        val params = (linkedMapOf<String, Any?>(
            "target_description" to label,
            "x1" to gesture.startX,
            "y1" to gesture.startY,
            "x2" to gesture.endX,
            "y2" to gesture.endY,
            "duration_ms" to gesture.durationMs.coerceAtLeast(OVERLAY_SWIPE_MIN_DURATION_MS),
            "gesture_duration_ms" to gesture.durationMs,
            "gesture_distance_px" to gesture.distancePx,
            "direction" to direction,
            "bounds" to boundsString(target.bounds),
            "node_class" to target.className,
            "node_resource_id" to target.resourceId,
            "recording_backend" to OVERLAY_TOUCH_BACKEND,
            "coordinate_space" to SCREEN_ABSOLUTE_COORDINATE_SPACE,
            "execution_mode" to SYNTHETIC_REPLAY_EXECUTION_MODE,
            "target_resolution" to target.resolution,
            "display_width" to gesture.displayWidth.takeIf { it > 0 },
            "display_height" to gesture.displayHeight.takeIf { it > 0 }
        ) + overlayDispatchDiagnostics(
            operationId,
            beforeXml,
            dispatchOutcome,
            beforeXmlCaptureMs = beforeXmlCaptureMs
        )).filterValues { it != null }
        val resolvedPackageName = resolvedActionPackageName(target, beforeXml)
        appendRecordedAction(
            ManualVlmRecordedAction(
                actionName = OobActionSchema.TOOL_SWIPE,
                title = title,
                params = params,
                packageName = resolvedPackageName,
                beforeXml = beforeXml,
                afterXml = null,
                beforeScreenshot = beforeScreenshot,
                afterScreenshot = null,
                startedAtMs = gesture.startedAtMs,
                finishedAtMs = gesture.finishedAtMs,
                summary = title,
                eventContext = overlayEventContextFor(
                    gesture,
                    target,
                    operationId,
                    dispatchOutcome,
                    beforeXml,
                    beforeXmlCaptureMs
                )
            )
        )
    }

    private fun appendImeSubmitGesture(
        gesture: ManualOverlayTouchGesture,
        beforeXml: String?,
        afterXml: String?,
        startedAtMs: Long,
        finishedAtMs: Long,
        dispatchOutcome: OverlayDispatchOutcome
    ) {
        val title = "人工按键盘提交键"
        val packageName = fallbackActionPackageName(beforeXml)
        val params = linkedMapOf<String, Any?>(
            "key" to "enter",
            "target_description" to "键盘提交键",
            "recording_backend" to IME_SUBMIT_BACKEND,
            "execution_mode" to IME_ACTION_EXECUTION_MODE,
            "dispatch_status" to dispatchOutcome.status
        ).filterValues { it != null }
        appendRecordedAction(
            ManualVlmRecordedAction(
                actionName = OobActionSchema.TOOL_PRESS_KEY,
                title = title,
                params = params,
                packageName = packageName,
                beforeXml = beforeXml,
                afterXml = afterXml,
                beforeScreenshot = null,
                afterScreenshot = null,
                startedAtMs = startedAtMs,
                finishedAtMs = finishedAtMs,
                summary = title,
                eventContext = imeSubmitEventContextFor(gesture, packageName)
            )
        )
    }

    private fun appendManualInputText(
        text: String,
        target: ManualEventTarget,
        beforeXml: String?,
        afterXml: String?,
        startedAtMs: Long,
        finishedAtMs: Long,
        dispatchOutcome: OverlayDispatchOutcome
    ) {
        val title = if (text == REDACTED_TEXT) {
            "手动补录敏感文本"
        } else {
            "手动补录输入文本"
        }
        val packageName = resolvedActionPackageName(target, beforeXml)
        val params = linkedMapOf<String, Any?>(
            "target_description" to target.label.ifBlank { "输入框" },
            "text" to text,
            "x" to target.bounds.centerX().toFloat(),
            "y" to target.bounds.centerY().toFloat(),
            "bounds" to boundsString(target.bounds),
            "node_class" to target.className,
            "node_resource_id" to target.resourceId,
            "node_text" to target.text,
            "node_content_description" to target.contentDescription,
            "recording_backend" to MANUAL_CONTROL_BACKEND,
            "target_resolution" to target.resolution,
            "coordinate_space" to SCREEN_ABSOLUTE_COORDINATE_SPACE,
            "execution_mode" to A11Y_ANCHORED_EXECUTION_MODE,
            "dispatch_status" to dispatchOutcome.status
        ).filterValues { it != null }
        appendRecordedAction(
            ManualVlmRecordedAction(
                actionName = OobActionSchema.TOOL_INPUT_TEXT,
                title = title,
                params = params,
                packageName = packageName,
                beforeXml = beforeXml,
                afterXml = afterXml,
                beforeScreenshot = null,
                afterScreenshot = null,
                startedAtMs = startedAtMs,
                finishedAtMs = finishedAtMs,
                summary = if (text == REDACTED_TEXT) title else "$title：${text.take(MAX_SUMMARY_TEXT)}",
                eventContext = manualControlEventContextFor(
                    eventType = MANUAL_CONTROL_INPUT_EVENT_TYPE,
                    tool = OobActionSchema.TOOL_INPUT_TEXT,
                    target = target,
                    key = null,
                    dispatchOutcome = dispatchOutcome
                )
            )
        )
        rememberPostInputClickWindowLocked(
            PendingTextAction(
                nodeKey = target.stableKey,
                anchorId = "manual_control:${finishedAtMs}",
                packageName = packageName,
                label = target.label.ifBlank { "输入框" },
                text = text,
                bounds = target.bounds,
                className = target.className,
                resourceId = target.resourceId,
                resolution = target.resolution,
                recordingBackend = MANUAL_CONTROL_BACKEND,
                beforeXml = beforeXml,
                beforeScreenshot = null,
                startedAtMs = startedAtMs,
                updatedAtMs = finishedAtMs,
                eventContext = emptyMap()
            )
        )
    }

    private fun appendManualPressKey(
        key: String,
        target: ManualEventTarget?,
        beforeXml: String?,
        afterXml: String?,
        startedAtMs: Long,
        finishedAtMs: Long,
        dispatchOutcome: OverlayDispatchOutcome
    ) {
        val title = "手动补录按键 $key"
        val packageName = target?.let { resolvedActionPackageName(it, beforeXml) }
            ?: fallbackActionPackageName(beforeXml)
        val params = linkedMapOf<String, Any?>(
            "key" to key,
            "target_description" to target?.label?.ifBlank { "按键" },
            "x" to target?.bounds?.centerX()?.toFloat(),
            "y" to target?.bounds?.centerY()?.toFloat(),
            "bounds" to target?.bounds?.let(::boundsString),
            "node_class" to target?.className,
            "node_resource_id" to target?.resourceId,
            "recording_backend" to MANUAL_CONTROL_BACKEND,
            "execution_mode" to MANUAL_CONTROL_EXECUTION_MODE,
            "dispatch_status" to dispatchOutcome.status
        ).filterValues { it != null }
        appendRecordedAction(
            ManualVlmRecordedAction(
                actionName = OobActionSchema.TOOL_PRESS_KEY,
                title = title,
                params = params,
                packageName = packageName,
                beforeXml = beforeXml,
                afterXml = afterXml,
                beforeScreenshot = null,
                afterScreenshot = null,
                startedAtMs = startedAtMs,
                finishedAtMs = finishedAtMs,
                summary = title,
                eventContext = manualControlEventContextFor(
                    eventType = MANUAL_CONTROL_PRESS_KEY_EVENT_TYPE,
                    tool = OobActionSchema.TOOL_PRESS_KEY,
                    target = target,
                    key = key,
                    dispatchOutcome = dispatchOutcome
                )
            )
        )
    }

    private fun recordTextChanged(
        event: AccessibilityEvent,
        packageName: String?,
        beforeXml: String?,
        beforeScreenshot: ManualVlmScreenshotRef?,
        source: AccessibilitySourceSnapshot?
    ) {
        val now = System.currentTimeMillis()
        val text = normalizeInputTextContent(
            event.text.joinToString("").ifBlank { source?.text.orEmpty() }
        )
        val safeText = if (source?.isPassword == true) {
            REDACTED_TEXT
        } else {
            text
        }
        if (safeText.isBlank()) return
        if (shouldSuppressManualControlTextChangeLocked(safeText, now)) return
        val anchor = textInputAnchor
        if (anchor == null) {
            recordUnanchoredTextChanged(
                event = event,
                packageName = packageName,
                beforeXml = beforeXml,
                beforeScreenshot = beforeScreenshot,
                source = source,
                safeText = safeText,
                now = now
            )
            return
        }
        if (!hasTextChangedFromAnchor(anchor, safeText)) return
        val sourceTarget = source?.toTextTarget(packageName)
        val target = textReplayTargetFromAnchor(
            anchor = anchor,
            sourceTarget = sourceTarget,
            inputText = safeText,
            resolutionSuffix = "real_touch_text_anchor"
        )
        val bounds = target.bounds
        val key = target.stableKey
        val existingPending = pendingText
        if (existingPending != null && existingPending.nodeKey != key) {
            flushPendingText(beforeXml ?: lastXmlSnapshot)
        }
        val sameNodePending = pendingText?.takeIf { it.nodeKey == key }
        val pendingBeforeXml = sameNodePending?.beforeXml ?: beforeXml
        val pendingBeforeScreenshot = sameNodePending?.beforeScreenshot ?: beforeScreenshot

        suppressRecentOverlayTextChangeClickLocked(now, anchor, target)
        pendingText = PendingTextAction(
            nodeKey = key,
            anchorId = anchor.id,
            packageName = target.packageName ?: packageName,
            label = target.label.ifBlank { "输入框" },
            text = safeText,
            bounds = bounds,
            className = target.className,
            resourceId = target.resourceId,
            resolution = target.resolution,
            recordingBackend = textInputBackendFor(anchor.backend),
            beforeXml = sameNodePending?.beforeXml ?: anchor.beforeXml ?: pendingBeforeXml,
            beforeScreenshot = sameNodePending?.beforeScreenshot ?: anchor.beforeScreenshot ?: pendingBeforeScreenshot,
            startedAtMs = sameNodePending?.startedAtMs ?: anchor.startedAtMs,
            updatedAtMs = now,
            eventContext = textInputEventContextFor(event, target, source, anchor)
        )
        clearPostInputClickWindowLocked()
        lastXmlSnapshot = beforeXml ?: lastXmlSnapshot
        lastScreenshotSnapshot = beforeScreenshot ?: lastScreenshotSnapshot
    }

    private fun recordUnanchoredTextChanged(
        event: AccessibilityEvent,
        packageName: String?,
        beforeXml: String?,
        beforeScreenshot: ManualVlmScreenshotRef?,
        source: AccessibilitySourceSnapshot?,
        safeText: String,
        now: Long
    ) {
        val target = unanchoredTextReplayTarget(
            packageName = packageName,
            xml = beforeXml,
            source = source,
            inputText = safeText
        )
        if (shouldIgnoreTarget(
                packageName = target.packageName,
                label = target.label,
                resourceId = target.resourceId
            )
        ) {
            unanchoredTextChangedSuppressedCount += 1
            suppressA11OnlyActionEvent(event)
            return
        }
        val key = target.stableKey
        val existingPending = pendingText
        if (existingPending != null && existingPending.nodeKey != key) {
            flushPendingText(existingPending.beforeXml ?: lastXmlSnapshot)
        }
        val sameNodePending = pendingText?.takeIf { it.nodeKey == key }
        pendingText = PendingTextAction(
            nodeKey = key,
            anchorId = sameNodePending?.anchorId ?: textEventAnchorId(target, now),
            packageName = target.packageName ?: packageName,
            label = target.label.ifBlank { "输入框" },
            text = safeText,
            bounds = target.bounds,
            className = target.className,
            resourceId = target.resourceId,
            resolution = target.resolution,
            recordingBackend = sameNodePending?.recordingBackend ?: A11Y_TEXT_EVENT_BACKEND,
            beforeXml = sameNodePending?.beforeXml ?: beforeXml,
            beforeScreenshot = sameNodePending?.beforeScreenshot ?: beforeScreenshot,
            startedAtMs = sameNodePending?.startedAtMs ?: eventWallTime(event.eventTime, now),
            updatedAtMs = now,
            eventContext = sameNodePending?.eventContext ?: unanchoredTextInputEventContextFor(
                event = event,
                target = target,
                sourceSnapshot = source
            )
        )
        unanchoredTextChangedRecordedCount += 1
        clearPostInputClickWindowLocked()
        lastXmlSnapshot = beforeXml ?: lastXmlSnapshot
        lastScreenshotSnapshot = beforeScreenshot ?: lastScreenshotSnapshot
    }

    private fun materializePendingTextFromXml(
        xml: String?,
        screenshot: ManualVlmScreenshotRef?,
        resolutionSuffix: String
    ): Boolean {
        if (xml.isNullOrBlank()) return false
        val anchor = textInputAnchor ?: return false
        val sourceCandidate = currentTextInputCandidateFromXml(xml, anchor) ?: return false
        val rawText = sourceCandidate.text.orEmpty()
        val safeText = if (sourceCandidate.password) {
            REDACTED_TEXT
        } else {
            normalizeInputTextContent(rawText)
        }
        if (safeText.isBlank()) return false
        if (!hasTextChangedFromAnchor(anchor, safeText)) return false
        val sourceTarget = sourceCandidate.toManualTarget(
            fallbackPackageName = packageNameFromXml(xml),
            resolution = "focused_xml_text"
        )
        val target = textReplayTargetFromAnchor(
            anchor = anchor,
            sourceTarget = sourceTarget,
            inputText = safeText,
            resolutionSuffix = resolutionSuffix
        )
        val now = System.currentTimeMillis()
        val key = target.stableKey
        val existingPending = pendingText
        if (existingPending != null && existingPending.nodeKey != key) {
            flushPendingText(existingPending.beforeXml ?: lastXmlSnapshot)
        }
        val sameNodePending = pendingText?.takeIf { it.nodeKey == key }
        suppressRecentOverlayTextChangeClickLocked(now, anchor, target)
        pendingText = PendingTextAction(
            nodeKey = key,
            anchorId = sameNodePending?.anchorId ?: anchor.id,
            packageName = target.packageName ?: packageNameFromXml(xml),
            label = target.label.ifBlank { "输入框" },
            text = safeText,
            bounds = target.bounds,
            className = target.className,
            resourceId = target.resourceId,
            resolution = target.resolution,
            recordingBackend = sameNodePending?.recordingBackend
                ?: textInputBackendFor(anchor.backend),
            beforeXml = sameNodePending?.beforeXml ?: anchor.beforeXml ?: xml,
            beforeScreenshot = sameNodePending?.beforeScreenshot ?: anchor.beforeScreenshot ?: screenshot,
            startedAtMs = sameNodePending?.startedAtMs ?: anchor.startedAtMs,
            updatedAtMs = now,
            eventContext = sameNodePending?.eventContext ?: xmlTextInputEventContextFor(
                target = target,
                sourceCandidate = sourceCandidate,
                anchor = anchor,
                rawText = rawText,
                resolutionSuffix = resolutionSuffix
            )
        )
        lastXmlSnapshot = xml
        lastScreenshotSnapshot = screenshot ?: lastScreenshotSnapshot
        return true
    }

    private fun suppressRecentOverlayTextChangeClickLocked(
        nowMs: Long,
        anchor: TextInputAnchor?,
        target: ManualEventTarget
    ) {
        val index = recordedActions.lastIndex
        if (index < 0) return
        val action = recordedActions[index]
        if (action.actionName != OobActionSchema.TOOL_CLICK) return
        if (action.params["recording_backend"]?.toString() != OVERLAY_TOUCH_BACKEND) return
        if (anchor != null &&
            action.finishedAtMs <= anchor.finishedAtMs + TEXT_INPUT_ANCHOR_CLICK_GRACE_MS
        ) {
            return
        }
        val elapsedMs = nowMs - action.finishedAtMs
        if (elapsedMs !in 0L..TEXT_INPUT_KEYBOARD_CLICK_SUPPRESS_WINDOW_MS) return
        val x = action.params["x"].asFloatOrNull() ?: return
        val y = action.params["y"].asFloatOrNull() ?: return
        if (target.bounds.containsPoint(x, y) || anchor?.target?.bounds?.containsPoint(x, y) == true) {
            return
        }
        recordedActions.removeAt(index)
        overlayKeyboardClickSuppressedCount += 1
        OmniLog.d(
            TAG,
            "manual trace suppressed text-change click before input_text x=$x y=$y"
        )
    }

    private fun currentTextInputCandidateFromXml(
        xml: String?,
        anchor: TextInputAnchor
    ): XmlNodeCandidate? {
        if (xml.isNullOrBlank()) return null
        val packageName = packageNameFromXml(xml)
        val rootArea = parseRootBounds(xml)?.area() ?: Int.MAX_VALUE
        val anchorTarget = anchor.target
        return parseXmlNodeCandidates(xml)
            .filter { candidate ->
                candidate.visible &&
                    candidate.enabled &&
                    candidate.isEditableLike() &&
                    (candidate.password || !candidate.text.isNullOrBlank()) &&
                    !(
                        shouldIgnoreTarget(
                            packageName = candidate.packageName ?: packageName,
                            label = candidate.bestLabel,
                            resourceId = candidate.resourceId
                        ) && candidate.isExplicitIgnoredControl(rootArea)
                    )
            }
            .maxWithOrNull(
                compareBy<XmlNodeCandidate> { candidate ->
                    currentTextInputCandidateScore(candidate, anchor, anchorTarget)
                }.thenByDescending { candidate ->
                    candidate.bounds.width().coerceAtLeast(1) * candidate.bounds.height().coerceAtLeast(1)
                }
            )
    }

    private fun focusedTextInputCandidateFromXml(
        xml: String?,
        requireText: Boolean = true
    ): XmlNodeCandidate? {
        if (xml.isNullOrBlank()) return null
        val packageName = packageNameFromXml(xml)
        val rootArea = parseRootBounds(xml)?.area() ?: Int.MAX_VALUE
        return parseXmlNodeCandidates(xml)
            .filter { candidate ->
                candidate.visible &&
                    candidate.enabled &&
                    candidate.focused &&
                    candidate.isEditableLike() &&
                    (!requireText || candidate.password || !candidate.text.isNullOrBlank()) &&
                    !(
                        shouldIgnoreTarget(
                            packageName = candidate.packageName ?: packageName,
                            label = candidate.bestLabel,
                            resourceId = candidate.resourceId
                        ) && candidate.isExplicitIgnoredControl(rootArea)
                    )
            }
            .maxWithOrNull(
                compareBy<XmlNodeCandidate> { candidate ->
                    if (candidate.focused) 1_000 else 0
                }.thenByDescending { candidate ->
                    candidate.bounds.width().coerceAtLeast(1) * candidate.bounds.height().coerceAtLeast(1)
                }
            )
    }

    private fun hasFocusedTextInputTargetInXml(xml: String?): Boolean =
        focusedTextInputCandidateFromXml(xml, requireText = false) != null

    private fun currentTextInputCandidateScore(
        candidate: XmlNodeCandidate,
        anchor: TextInputAnchor,
        anchorTarget: ManualEventTarget
    ): Int {
        var score = 0
        if (candidate.focused) score += 1_000
        if (candidate.bounds.containsPoint(anchor.x, anchor.y)) score += 260
        if (!anchorTarget.resourceId.isNullOrBlank() &&
            candidate.resourceId == anchorTarget.resourceId
        ) {
            score += 160
        }
        if (candidate.bounds == anchorTarget.bounds) score += 120
        if (!anchorTarget.packageName.isNullOrBlank() &&
            candidate.packageName == anchorTarget.packageName
        ) {
            score += 40
        }
        return score
    }

    private fun textReplayTargetFromAnchor(
        anchor: TextInputAnchor,
        sourceTarget: ManualEventTarget?,
        inputText: String,
        resolutionSuffix: String = "real_touch_text_anchor"
    ): ManualEventTarget {
        val anchorTarget = anchor.target
        val beforeLabel = anchorTarget.label.trim()
        val stableLabel = beforeLabel.takeIf {
            it.isNotBlank() && it != inputText && it != REDACTED_TEXT
        } ?: sourceTarget?.label ?: anchorTarget.label.ifBlank { "输入框" }
        return anchorTarget.copy(
            label = stableLabel,
            packageName = anchorTarget.packageName ?: sourceTarget?.packageName,
            resourceId = anchorTarget.resourceId ?: sourceTarget?.resourceId,
            text = anchorTarget.text ?: sourceTarget?.text,
            contentDescription = anchorTarget.contentDescription ?: sourceTarget?.contentDescription,
            stableKey = anchorTarget.stableKey.ifBlank { sourceTarget?.stableKey.orEmpty() },
            resolution = "${anchorTarget.resolution}+$resolutionSuffix"
        )
    }

    private fun unanchoredTextReplayTarget(
        packageName: String?,
        xml: String?,
        source: AccessibilitySourceSnapshot?,
        inputText: String
    ): ManualEventTarget {
        val fallbackPackageName = firstNonBlank(
            usableTargetPackageName(packageName),
            packageNameFromXml(xml),
            usableFallbackPackageName(lastUsablePackageName)
        ).ifBlank { null }
        val sourceTarget = source?.toTextTarget(fallbackPackageName)
        if (sourceTarget != null) {
            return sourceTarget.copy(
                label = textEventLabel(source, sourceTarget.label, inputText),
                packageName = sourceTarget.packageName ?: fallbackPackageName,
                resolution = "event_source_unanchored_text"
            )
        }
        return lastRecordedTouchTextTarget(fallbackPackageName, inputText)
            ?: ManualEventTarget(
                label = "输入框",
                bounds = Rect(0, 0, 1, 1),
                packageName = fallbackPackageName,
                className = source?.className,
                resourceId = source?.viewIdResourceName,
                text = source?.text,
                contentDescription = source?.contentDescription,
                stableKey = "text_event_unlocated|${fallbackPackageName.orEmpty()}",
                resolution = "text_event_unlocated"
            )
    }

    private fun lastRecordedTouchTextTarget(
        fallbackPackageName: String?,
        inputText: String
    ): ManualEventTarget? {
        val action = recordedActions.lastOrNull { candidate ->
            candidate.actionName == OobActionSchema.TOOL_CLICK ||
                candidate.actionName == OobActionSchema.TOOL_LONG_PRESS
        } ?: return null
        val x = action.params["x"].asFloatOrNull()
        val y = action.params["y"].asFloatOrNull()
        val bounds = parseBounds(action.params["bounds"]?.toString())
            ?: if (x != null && y != null) {
                val left = x.toInt().coerceAtLeast(0)
                val top = y.toInt().coerceAtLeast(0)
                Rect(left, top, left + 1, top + 1)
            } else {
                return null
            }
        val label = textEventLabel(
            source = null,
            fallbackLabel = action.params["target_description"]?.toString().orEmpty(),
            inputText = inputText
        )
        val className = action.params["node_class"]?.toString()
        val resourceId = action.params["node_resource_id"]?.toString()
        return ManualEventTarget(
            label = label,
            bounds = bounds,
            packageName = usableTargetPackageName(action.packageName) ?: fallbackPackageName,
            className = className,
            resourceId = resourceId,
            text = action.params["node_text"]?.toString(),
            contentDescription = action.params["node_content_description"]?.toString(),
            stableKey = firstNonBlank(resourceId, className, "recent_touch") + "|" + boundsString(bounds),
            resolution = "recent_touch_unanchored_text"
        )
    }

    private fun textEventLabel(
        source: AccessibilitySourceSnapshot?,
        fallbackLabel: String,
        inputText: String
    ): String {
        val input = normalizeInputTextContent(inputText)
        val label = firstNonBlank(
            source?.contentDescription,
            source?.hintText,
            source?.viewIdResourceName?.substringAfterLast('/'),
            fallbackLabel,
            source?.className
        ).take(MAX_LABEL_LENGTH)
        val normalized = label.trim()
        val generic = normalized == "android.view.View" ||
            normalized == "android.widget.TextView" ||
            normalized == "屏幕坐标"
        return normalized
            .takeIf { it.isNotBlank() && it != input && it != REDACTED_TEXT && !generic }
            ?: "输入框"
    }

    private fun manualInputTextTargetFor(
        xml: String?,
        inputText: String
    ): ManualEventTarget? {
        val fallbackPackageName = firstNonBlank(
            packageNameFromXml(xml),
            usableFallbackPackageName(lastUsablePackageName)
        ).ifBlank { null }
        val focusedTarget = focusedTextInputCandidateFromXml(
            xml = xml,
            requireText = false
        )?.toManualTarget(
            fallbackPackageName = fallbackPackageName,
            resolution = "manual_control_focused_xml"
        )
        val anchor = textInputAnchor
        if (anchor != null) {
            return textReplayTargetFromAnchor(
                anchor = anchor,
                sourceTarget = focusedTarget,
                inputText = inputText,
                resolutionSuffix = "manual_control"
            )
        }
        return focusedTarget?.copy(
            label = textEventLabel(
                source = null,
                fallbackLabel = focusedTarget.label,
                inputText = inputText
            ),
            packageName = focusedTarget.packageName ?: fallbackPackageName,
            resolution = "manual_control_focused_xml"
        ) ?: lastRecordedTouchTextTarget(fallbackPackageName, inputText)
    }

    private fun manualFallbackTextTarget(
        xml: String?,
        inputText: String
    ): ManualEventTarget {
        val fallbackPackageName = firstNonBlank(
            packageNameFromXml(xml),
            usableFallbackPackageName(lastUsablePackageName)
        ).ifBlank { null }
        return ManualEventTarget(
            label = textEventLabel(
                source = null,
                fallbackLabel = "输入框",
                inputText = inputText
            ),
            bounds = Rect(0, 0, 1, 1),
            packageName = fallbackPackageName,
            className = null,
            resourceId = null,
            text = null,
            contentDescription = null,
            stableKey = "manual_control_input|${fallbackPackageName.orEmpty()}",
            resolution = "manual_control_unlocated"
        )
    }

    private fun setManualControlTextChangeSuppressionLocked(text: String) {
        manualControlTextChangeSuppressText = text
        manualControlTextChangeSuppressUntilMs =
            System.currentTimeMillis() + MANUAL_CONTROL_TEXT_CHANGE_SUPPRESS_MS
    }

    private fun shouldSuppressManualControlTextChangeLocked(text: String, nowMs: Long): Boolean {
        if (nowMs > manualControlTextChangeSuppressUntilMs) {
            manualControlTextChangeSuppressText = null
            return false
        }
        val suppressedText = manualControlTextChangeSuppressText ?: return false
        if (suppressedText != text) return false
        manualControlTextChangedSuppressedCount += 1
        suppressedSemanticActionEventCount += 1
        OmniLog.d(TAG, "manual control suppressed duplicate text-change input_text")
        return true
    }

    private fun normalizeManualPressKey(key: String): String? =
        when (key.trim().lowercase()) {
            "enter", "search", "done", "go", "send", "next" -> "enter"
            "back" -> "back"
            "home" -> "home"
            else -> null
        }

    private fun manualControlEventContextFor(
        eventType: String,
        tool: String,
        target: ManualEventTarget?,
        key: String?,
        dispatchOutcome: OverlayDispatchOutcome
    ): Map<String, Any?> {
        return linkedMapOf<String, Any?>(
            "event_type" to eventType,
            "event_has_source" to false,
            "recording_backend" to MANUAL_CONTROL_BACKEND,
            "tool" to tool,
            "key" to key,
            "target_package" to target?.packageName,
            "target_resource_id" to target?.resourceId,
            "target_class" to target?.className,
            "target_bounds" to target?.bounds?.let(::boundsString),
            "target_resolution" to target?.resolution,
            "dispatch" to linkedMapOf(
                "status" to dispatchOutcome.status,
                "executed" to dispatchOutcome.executed,
                "error_code" to dispatchOutcome.errorCode,
                "error_message" to dispatchOutcome.errorMessage
            ).filterValues { it != null },
            "source" to "manual_recording_control_overlay"
        ).filterValues { it != null }
    }

    private data class TimedXmlCapture(
        val xml: String?,
        val durationMs: Long
    )

    private fun captureCurrentXmlTimed(reason: String): TimedXmlCapture {
        val startedAtMs = SystemClock.uptimeMillis()
        val xml = runBlocking {
            withTimeoutOrNull(BEFORE_XML_CAPTURE_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { captureCurrentXml() }
            }
        }?.takeIf { it.isNotBlank() }
        val durationMs = (SystemClock.uptimeMillis() - startedAtMs).coerceAtLeast(0L)
        recordXmlCaptureTiming(reason, durationMs, xml != null)
        if (xml != null) {
            val xmlPackageName = packageNameFromXml(xml)
            if (xmlPackageName != null) {
                synchronized(recordingLock) {
                    rememberUsablePackageNameLocked(xmlPackageName)
                }
            }
        }
        return TimedXmlCapture(xml = xml, durationMs = durationMs)
    }

    private fun captureCurrentXmlSafe(reason: String = "manual_recording"): String? =
        captureCurrentXmlTimed(reason).xml

    private fun recordXmlCaptureTiming(
        reason: String,
        durationMs: Long,
        success: Boolean
    ) {
        xmlCaptureCount += 1
        if (success) {
            xmlCaptureSuccessCount += 1
        } else {
            xmlCaptureTimeoutOrEmptyCount += 1
        }
        xmlCaptureTotalMs += durationMs
        xmlCaptureMaxMs = max(xmlCaptureMaxMs, durationMs)
        xmlCaptureLastMs = durationMs
        xmlCaptureLastReason = reason
    }

    private fun currentXmlForTextFallback(reason: String = "text_fallback"): String? =
        captureCurrentXmlSafe(reason)

    private fun normalizeInputTextContent(rawText: String): String {
        val normalized = rawText.replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) return ""
        val separators = listOf(",", "，", ":", "：")
        val splitIndex = separators
            .map { normalized.indexOf(it) }
            .filter { it > 0 }
            .minOrNull()
            ?: return normalized
        val prefix = normalized.substring(0, splitIndex).trim().lowercase()
        if (prefix !in TEXT_FIELD_ACCESSIBILITY_PREFIXES) return normalized
        return normalized.substring(splitIndex + 1).trim()
    }

    private fun hasTextChangedFromAnchor(anchor: TextInputAnchor, safeText: String): Boolean {
        if (safeText == REDACTED_TEXT) return true
        val beforeText = normalizeInputTextContent(anchor.target.text.orEmpty())
        return beforeText.isBlank() || beforeText != safeText
    }

    private fun flushPendingTextUnlessAnchoredTo(anchorId: String?, xmlOverride: String? = lastXmlSnapshot) {
        val pending = pendingText ?: return
        if (anchorId != null && pending.anchorId == anchorId) return
        flushPendingText(xmlOverride)
    }

    private fun flushPendingText(xmlOverride: String? = lastXmlSnapshot) {
        val pending = pendingText ?: return
        pendingText = null
        val title = if (pending.text == REDACTED_TEXT) {
            "人工输入敏感文本"
        } else {
            "人工输入文本"
        }
        lastXmlSnapshot = xmlOverride ?: lastXmlSnapshot
        lastScreenshotSnapshot = pending.beforeScreenshot ?: lastScreenshotSnapshot
        val params = linkedMapOf<String, Any?>(
            "target_description" to pending.label,
            "text" to pending.text,
            "x" to pending.bounds.centerX().toFloat(),
            "y" to pending.bounds.centerY().toFloat(),
            "bounds" to boundsString(pending.bounds),
            "node_class" to pending.className,
            "node_resource_id" to pending.resourceId,
            "recording_backend" to pending.recordingBackend,
            "target_resolution" to pending.resolution
        ).filterValues { it != null }
        appendRecordedAction(ManualVlmRecordedAction(
            actionName = OobActionSchema.TOOL_INPUT_TEXT,
            title = title,
            params = params,
            packageName = pending.packageName,
            beforeXml = pending.beforeXml,
            afterXml = null,
            beforeScreenshot = pending.beforeScreenshot,
            afterScreenshot = null,
            startedAtMs = pending.startedAtMs,
            finishedAtMs = pending.updatedAtMs,
            summary = if (pending.text == REDACTED_TEXT) title else "$title：${pending.text.take(MAX_SUMMARY_TEXT)}",
            eventContext = pending.eventContext
        ))
        rememberPostInputClickWindowLocked(pending)
    }

    private fun rememberPostInputClickWindowLocked(pending: PendingTextAction) {
        val nowMs = System.currentTimeMillis()
        postInputClickWindow = PostInputClickWindow(
            inputNodeKey = pending.nodeKey,
            inputBounds = Rect(pending.bounds),
            inputPackageName = pending.packageName,
            openedAtMs = nowMs,
            expiresAtMs = nowMs + POST_INPUT_CLICK_GRACE_MS
        )
    }

    private fun shouldIgnorePackage(packageName: String?): Boolean {
        val normalized = packageName?.trim().orEmpty()
        return normalized.isNotEmpty() && normalized == ownPackageName
    }

    private fun usableTargetPackageName(packageName: String?): String? =
        packageName?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.takeUnless { it == ownPackageName }

    private fun usableFallbackPackageName(packageName: String?): String? =
        usableTargetPackageName(packageName)
            ?.takeUnless { it == SYSTEM_UI_PACKAGE }
            ?.takeUnless { it.endsWith(".systemuiplugin", ignoreCase = true) }

    private fun rememberUsablePackageNameLocked(packageName: String?) {
        usableFallbackPackageName(packageName)?.let { lastUsablePackageName = it }
    }

    private fun fallbackActionPackageName(xml: String?): String? =
        packageNameFromXml(xml)
            ?: usableFallbackPackageName(lastUsablePackageName)

    private fun resolvedActionPackageName(
        target: ManualEventTarget,
        xml: String?
    ): String? =
        usableTargetPackageName(target.packageName)
            ?: packageNameFromXml(xml)
            ?: usableFallbackPackageName(lastUsablePackageName)

    private fun navigationActionFor(packageName: String?, label: String): String? {
        if (packageName != SYSTEM_UI_PACKAGE) return null
        val normalized = label.lowercase()
        return when {
            normalized == "back" ||
                normalized.contains("back") ||
                normalized.contains("返回") -> OobActionSchema.TOOL_PRESS_KEY
            normalized == "home" ||
                normalized.contains("home") ||
                normalized.contains("主页") ||
                normalized.contains("主屏幕") -> OobActionSchema.TOOL_PRESS_KEY
            else -> null
        }
    }

    private fun shouldIgnoreNode(node: AccessibilityNodeInfo): Boolean {
        val packageName = node.safePackageName()
        return shouldIgnoreTarget(
            packageName = packageName,
            label = node.bestLabel(),
            resourceId = node.safeViewIdResourceName()
        )
    }

    private fun shouldIgnoreTarget(
        packageName: String?,
        label: String?,
        resourceId: String?
    ): Boolean {
        if (shouldIgnorePackage(packageName)) return true
        val text = listOfNotNull(label, resourceId).joinToString(" ").lowercase()
        return OOB_CONTROL_HINTS.any { text.contains(it) }
    }

    private fun sourceSnapshotFromEvent(event: AccessibilityEvent): AccessibilitySourceSnapshot? {
        val source = runCatching { event.source }.getOrNull() ?: return null
        return source.toSourceSnapshot()
    }

    private fun AccessibilityNodeInfo.toSourceSnapshot(): AccessibilitySourceSnapshot =
        AccessibilitySourceSnapshot(
            bounds = boundsInScreenOrNull()?.let { Rect(it) },
            packageName = safePackageName(),
            className = safeClassName(),
            text = safeText(),
            hintText = safeHintText(),
            contentDescription = safeContentDescription(),
            viewIdResourceName = safeViewIdResourceName(),
            isPassword = runCatching { isPassword }.getOrDefault(false)
        )

    private fun AccessibilitySourceSnapshot.bestLabel(): String {
        return firstNonBlank(
            contentDescription,
            text,
            hintText,
            viewIdResourceName?.substringAfterLast('/'),
            className
        ).take(MAX_LABEL_LENGTH)
    }

    private fun AccessibilitySourceSnapshot.isTextEntryLike(): Boolean {
        return isPassword ||
            isTextInputClass(className) ||
            viewIdResourceName.orEmpty().contains("edit", ignoreCase = true) ||
            viewIdResourceName.orEmpty().contains("input", ignoreCase = true)
    }

    private fun AccessibilitySourceSnapshot.stableKey(bounds: Rect?): String {
        return firstNonBlank(viewIdResourceName, className) + "|" +
            "${bounds?.left},${bounds?.top},${bounds?.right},${bounds?.bottom}"
    }

    private fun AccessibilitySourceSnapshot.toTextTarget(
        fallbackPackageName: String?
    ): ManualEventTarget? {
        if (shouldIgnoreTarget(packageName, bestLabel(), viewIdResourceName)) return null
        val safeBounds = bounds?.takeUnless { it.isEmpty } ?: return null
        return ManualEventTarget(
            label = bestLabel(),
            bounds = Rect(safeBounds),
            packageName = packageName ?: fallbackPackageName,
            className = className,
            resourceId = viewIdResourceName,
            text = text,
            contentDescription = contentDescription,
            stableKey = stableKey(safeBounds),
            resolution = "event_source_text"
        )
    }

    private fun AccessibilityNodeInfo.boundsInScreenOrNull(): Rect? {
        val rect = Rect()
        return runCatching {
            getBoundsInScreen(rect)
            rect.takeUnless { it.isEmpty }
        }.getOrNull()
    }

    private fun AccessibilityNodeInfo.bestLabel(): String {
        return firstNonBlank(
            safeContentDescription(),
            safeText(),
            safeHintText(),
            safeViewIdResourceName()?.substringAfterLast('/'),
            safeClassName()
        ).take(MAX_LABEL_LENGTH)
    }

    private fun AccessibilityNodeInfo.stableKey(bounds: Rect?): String {
        return firstNonBlank(safeViewIdResourceName(), safeClassName()) + "|" +
            "${bounds?.left},${bounds?.top},${bounds?.right},${bounds?.bottom}"
    }

    private fun AccessibilityNodeInfo.safePackageName(): String? =
        runCatching { packageName?.toString() }.getOrNull()

    private fun AccessibilityNodeInfo.safeClassName(): String? =
        runCatching { className?.toString() }.getOrNull()

    private fun AccessibilityNodeInfo.safeText(): String? =
        runCatching { text?.toString() }.getOrNull()

    private fun AccessibilityNodeInfo.safeHintText(): String? =
        runCatching { hintText?.toString() }.getOrNull()

    private fun AccessibilityNodeInfo.safeContentDescription(): String? =
        runCatching { contentDescription?.toString() }.getOrNull()

    private fun AccessibilityNodeInfo.safeViewIdResourceName(): String? =
        runCatching { viewIdResourceName }.getOrNull()

    private fun targetFromSourceNode(
        node: AccessibilityNodeInfo,
        fallbackPackageName: String?
    ): ManualEventTarget? {
        val targetNode = actionableSourceNode(node) ?: node
        if (shouldIgnoreNode(targetNode)) return null
        val bounds = targetNode.boundsInScreenOrNull() ?: return null
        if (bounds.isEmpty) return null
        val packageName = targetNode.safePackageName() ?: fallbackPackageName
        val resolution = if (targetNode === node) {
            "event_source"
        } else {
            "event_source_actionable_ancestor"
        }
        return ManualEventTarget(
            label = targetNode.bestLabel().ifBlank { node.bestLabel() },
            bounds = bounds,
            packageName = packageName,
            className = targetNode.safeClassName(),
            resourceId = targetNode.safeViewIdResourceName(),
            text = targetNode.safeText() ?: node.safeText(),
            contentDescription = targetNode.safeContentDescription()
                ?: node.safeContentDescription(),
            stableKey = targetNode.stableKey(bounds),
            resolution = resolution
        )
    }

    private fun targetFromTextSourceNode(
        node: AccessibilityNodeInfo,
        fallbackPackageName: String?
    ): ManualEventTarget? {
        if (!shouldIgnoreNode(node)) {
            val bounds = node.boundsInScreenOrNull()
            if (bounds != null && !bounds.isEmpty) {
                val packageName = node.safePackageName() ?: fallbackPackageName
                return ManualEventTarget(
                    label = node.bestLabel(),
                    bounds = bounds,
                    packageName = packageName,
                    className = node.safeClassName(),
                    resourceId = node.safeViewIdResourceName(),
                    text = node.safeText(),
                    contentDescription = node.safeContentDescription(),
                    stableKey = node.stableKey(bounds),
                    resolution = "event_source_text"
                )
            }
        }
        return targetFromSourceNode(node, fallbackPackageName)
    }

    private fun actionableSourceNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(MAX_ACTIONABLE_ANCESTOR_DEPTH + 1) {
            val candidate = current ?: return null
            val bounds = candidate.boundsInScreenOrNull()
            if (
                bounds != null &&
                !bounds.isEmpty &&
                runCatching { candidate.isEnabled }.getOrDefault(false) &&
                (
                    runCatching { candidate.isClickable }.getOrDefault(false) ||
                        runCatching { candidate.isLongClickable }.getOrDefault(false) ||
                        runCatching { candidate.isScrollable }.getOrDefault(false)
                    )
            ) {
                return candidate
            }
            current = runCatching { candidate.parent }.getOrNull()
        }
        return null
    }

    private fun isTextInputClass(className: String?): Boolean {
        val normalized = className.orEmpty()
        return normalized.contains("EditText", ignoreCase = true) ||
            normalized.contains("TextInput", ignoreCase = true)
    }

    private fun targetAtCoordinateFromXml(
        xml: String?,
        x: Float,
        y: Float,
        fallbackPackageName: String?,
        preferScrollable: Boolean
    ): ManualEventTarget? {
        val nodes = parseXmlNodeCandidates(xml)
            .filter { candidate ->
                candidate.bounds.containsPoint(x, y) &&
                    !shouldIgnoreTarget(
                        packageName = candidate.packageName ?: fallbackPackageName,
                        label = candidate.bestLabel,
                        resourceId = candidate.resourceId
                    )
            }
        if (nodes.isEmpty()) return null
        val best = nodes.maxWithOrNull(
            compareBy<XmlNodeCandidate> { it.coordinateScore(preferScrollable) }
                .thenByDescending { it.bounds.width() * it.bounds.height() }
        ) ?: return null
        return best.toManualTarget(
            fallbackPackageName = fallbackPackageName,
            resolution = if (best.isActionableForCoordinate(preferScrollable)) {
                "raw_touch_coordinate_xml_grounded"
            } else {
                "raw_touch_coordinate_xml_container"
            }
        )
    }

    private fun coordinateOnlyTarget(
        xml: String?,
        x: Float,
        y: Float,
        resolution: String
    ): ManualEventTarget {
        val left = x.toInt().coerceAtLeast(0)
        val top = y.toInt().coerceAtLeast(0)
        return ManualEventTarget(
            label = "屏幕坐标",
            bounds = Rect(left, top, left + 1, top + 1),
            packageName = packageNameFromXml(xml),
            className = null,
            resourceId = null,
            text = null,
            contentDescription = null,
            stableKey = "raw_coordinate|$left,$top",
            resolution = resolution
        )
    }

    private fun rememberTextInputAnchorFromRealTouch(
        beforeXml: String?,
        beforeScreenshot: ManualVlmScreenshotRef?,
        x: Float,
        y: Float,
        backend: String,
        anchorId: String,
        startedAtMs: Long,
        finishedAtMs: Long
    ) {
        val fallbackPackageName = packageNameFromXml(beforeXml)
        val target = textInputTargetAtCoordinateFromXml(
            xml = beforeXml,
            x = x,
            y = y,
            fallbackPackageName = fallbackPackageName,
            backend = backend
        )
        if (target == null) {
            synchronized(recordingLock) {
                textInputAnchor = null
            }
            return
        }
        val anchor = TextInputAnchor(
            id = anchorId,
            backend = backend,
            beforeXml = beforeXml,
            beforeScreenshot = beforeScreenshot,
            target = target,
            x = x,
            y = y,
            startedAtMs = startedAtMs,
            finishedAtMs = finishedAtMs
        )
        synchronized(recordingLock) {
            textInputAnchor = anchor
        }
    }

    private fun clearTextInputAnchor() {
        synchronized(recordingLock) {
            textInputAnchor = null
        }
    }

    private fun preSeedFocusedTextInputAnchorFrom(
        xml: String?,
        screenshot: ManualVlmScreenshotRef?
    ): TextInputAnchor? {
        val candidate = focusedTextInputCandidateFromXml(xml, requireText = false) ?: return null
        val now = System.currentTimeMillis()
        val target = candidate.toManualTarget(
            fallbackPackageName = packageNameFromXml(xml),
            resolution = "focused_xml_start_anchor"
        ).let { t -> t.copy(label = t.label.takeUnless { it == REDACTED_TEXT } ?: "输入框") }
        return TextInputAnchor(
            id = focusedXmlTextAnchorId(target, now),
            backend = FOCUSED_XML_TEXT_INPUT_BACKEND,
            beforeXml = xml,
            beforeScreenshot = screenshot,
            target = target,
            x = target.bounds.centerX().toFloat(),
            y = target.bounds.centerY().toFloat(),
            startedAtMs = now,
            finishedAtMs = now
        )
    }

    private fun textInputTargetAtCoordinateFromXml(
        xml: String?,
        x: Float,
        y: Float,
        fallbackPackageName: String?,
        backend: String
    ): ManualEventTarget? {
        val packageName = packageNameFromXml(xml) ?: fallbackPackageName
        val rootArea = parseRootBounds(xml)?.area() ?: Int.MAX_VALUE
        val candidates = parseXmlNodeCandidates(xml)
            .filter { candidate ->
                candidate.visible &&
                    candidate.enabled &&
                    candidate.bounds.containsPoint(x, y) &&
                    candidate.isEditableLike() &&
                    !(
                        shouldIgnoreTarget(
                            packageName = candidate.packageName ?: packageName,
                            label = candidate.bestLabel,
                            resourceId = candidate.resourceId
                        ) && candidate.isExplicitIgnoredControl(rootArea)
                    )
            }
        val best = candidates.maxWithOrNull(
            compareBy<XmlNodeCandidate> { it.coordinateScore(preferScrollable = false) }
                .thenByDescending { it.bounds.width() * it.bounds.height() }
        ) ?: return null
        val resolution = when (backend) {
            OVERLAY_TOUCH_BACKEND -> "overlay_touch_before_xml_text_target"
            RAW_TOUCH_BACKEND -> "raw_touch_before_xml_text_target"
            else -> "real_touch_before_xml_text_target"
        }
        return best.toManualTarget(packageName, resolution)
    }

    private fun textInputBackendFor(backend: String): String {
        return when (backend) {
            OVERLAY_TOUCH_BACKEND -> OVERLAY_TOUCH_TEXT_INPUT_BACKEND
            RAW_TOUCH_BACKEND -> RAW_TOUCH_TEXT_INPUT_BACKEND
            else -> REAL_TOUCH_TEXT_INPUT_BACKEND
        }
    }

    private fun overlayTextAnchorId(gesture: ManualOverlayTouchGesture): String {
        return listOf(
            OVERLAY_TOUCH_BACKEND,
            gesture.startedAtMs,
            gesture.finishedAtMs,
            gesture.startX.toInt(),
            gesture.startY.toInt()
        ).joinToString("|")
    }

    private fun rawTextAnchorId(gestureId: Long): String = "$RAW_TOUCH_BACKEND|$gestureId"

    private fun focusedXmlTextAnchorId(target: ManualEventTarget, now: Long): String =
        "$FOCUSED_XML_TEXT_INPUT_BACKEND|$now|${target.stableKey}"

    private fun textEventAnchorId(target: ManualEventTarget, now: Long): String =
        "$A11Y_TEXT_EVENT_BACKEND|$now|${target.stableKey}"

    private fun coordinateHitsIgnoredTarget(xml: String?, x: Float, y: Float): Boolean {
        val packageName = packageNameFromXml(xml)
        val rootArea = parseRootBounds(xml)?.area() ?: Int.MAX_VALUE
        return parseXmlNodeCandidates(xml).any { candidate ->
            candidate.bounds.containsPoint(x, y) &&
                shouldIgnoreTarget(
                    packageName = candidate.packageName ?: packageName,
                    label = candidate.bestLabel,
                    resourceId = candidate.resourceId
                ) &&
                candidate.isExplicitIgnoredControl(rootArea)
        }
    }

    private fun overlayClickMayOpenIme(xml: String?, x: Float, y: Float): Boolean {
        if (xml.isNullOrBlank()) return false
        val packageName = packageNameFromXml(xml)
        val rootArea = parseRootBounds(xml)?.area() ?: Int.MAX_VALUE
        val candidates = parseXmlNodeCandidates(xml)
            .filter { candidate ->
                candidate.visible &&
                    candidate.enabled &&
                    candidate.bounds.containsPoint(x, y) &&
                    !(
                        shouldIgnoreTarget(
                            packageName = candidate.packageName ?: packageName,
                            label = candidate.bestLabel,
                            resourceId = candidate.resourceId
                        ) && candidate.isExplicitIgnoredControl(rootArea)
                    )
        }
        if (candidates.isEmpty()) return false
        return candidates.any { it.isEditableLike() }
    }

    private fun XmlNodeCandidate.isExplicitIgnoredControl(rootArea: Int): Boolean {
        val text = listOfNotNull(bestLabel, resourceId, className).joinToString(" ").lowercase()
        if (OOB_CONTROL_HINTS.any { text.contains(it) }) return true
        if (!visible || !enabled) return false
        if (!clickable && !focusable && !editable && !scrollable) return false
        val area = bounds.area()
        if (area <= 0) return false
        val maxControlArea = if (rootArea == Int.MAX_VALUE) {
            MAX_IGNORED_CONTROL_AREA_PX
        } else {
            (rootArea * MAX_IGNORED_CONTROL_AREA_RATIO).toInt()
                .coerceAtLeast(MAX_IGNORED_CONTROL_AREA_PX)
        }
        return area <= maxControlArea
    }

    private fun XmlNodeCandidate.isEditableLike(): Boolean {
        val text = listOfNotNull(className, resourceId, bestLabel)
            .joinToString(" ")
            .lowercase()
        return editable ||
            text.contains("edittext") ||
            text.contains("textinput") ||
            text.contains("editable")
    }

    private fun packageNameFromXml(xml: String?): String? =
        parseXmlNodeCandidates(xml)
            .firstNotNullOfOrNull { usableTargetPackageName(it.packageName) }

    private fun pageStableFingerprint(xml: String?): String {
        if (xml.isNullOrBlank()) return ""
        return parseXmlNodeCandidates(xml)
            .asSequence()
            .filter { it.visible }
            .take(MAX_PAGE_FINGERPRINT_NODES)
            .joinToString("\n") { candidate ->
                val stableLabel = if (candidate.editable) "" else candidate.bestLabel.take(MAX_PAGE_LABEL_LENGTH)
                listOf(
                    candidate.packageName.orEmpty(),
                    candidate.className.orEmpty(),
                    candidate.resourceId.orEmpty(),
                    stableLabel,
                    boundsString(candidate.bounds),
                    candidate.clickable,
                    candidate.scrollable,
                    candidate.focusable,
                    candidate.editable,
                    candidate.enabled
                ).joinToString("|")
            }
    }

    private fun pageSummary(xml: String?): Map<String, Any?> {
        val candidates = parseXmlNodeCandidates(xml)
        if (candidates.isEmpty()) return emptyMap()
        val labels = candidates
            .asSequence()
            .filter { it.visible }
            .map { it.bestLabel }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_PAGE_SUMMARY_LABELS)
            .toList()
        return linkedMapOf<String, Any?>(
            "package_name" to packageNameFromXml(xml),
            "labels" to labels.takeIf { it.isNotEmpty() },
            "visible_node_count" to candidates.count { it.visible },
            "clickable_count" to candidates.count { it.visible && it.clickable },
            "editable_count" to candidates.count { it.visible && it.editable },
            "scrollable_count" to candidates.count { it.visible && it.scrollable }
        ).filterValues { it != null }
    }

    private fun fingerprintHash(value: String): String =
        Integer.toHexString(value.hashCode())

    private fun Rect.containsPoint(x: Float, y: Float): Boolean =
        x >= left && x <= right && y >= top && y <= bottom

    private fun Rect.area(): Int =
        width().coerceAtLeast(0) * height().coerceAtLeast(0)

    private fun XmlNodeCandidate.isActionableForCoordinate(preferScrollable: Boolean): Boolean =
        clickable || focusable || editable || (preferScrollable && scrollable)

    private fun XmlNodeCandidate.coordinateScore(preferScrollable: Boolean): Int {
        var score = 0
        if (visible) score += 20
        if (enabled) score += 20
        if (clickable) score += 80
        if (focusable) score += 40
        if (editable) score += 60
        if (preferScrollable && scrollable) score += 100
        val area = bounds.width().coerceAtLeast(1) * bounds.height().coerceAtLeast(1)
        score += (1_000_000 / area).coerceIn(0, 40)
        return score
    }

    private fun parseXmlNodeCandidates(xml: String?): List<XmlNodeCandidate> {
        if (xml.isNullOrBlank()) return emptyList()
        return NODE_TAG_REGEX.findAll(xml).mapNotNull { match ->
            val attrs = parseXmlAttributes(match.groupValues.getOrNull(1).orEmpty())
            val bounds = parseBounds(attrs["bounds"]) ?: return@mapNotNull null
            XmlNodeCandidate(
                bounds = bounds,
                packageName = attrs["package"],
                className = attrs["class"],
                text = decodeXmlAttr(attrs["text"]),
                contentDescription = decodeXmlAttr(attrs["content-desc"] ?: attrs["contentDescription"]),
                hintText = decodeXmlAttr(attrs["hint-text"] ?: attrs["hintText"]),
                resourceId = attrs["resource-id"] ?: attrs["resourceId"],
                clickable = attrs["clickable"] == "true",
                scrollable = attrs["scrollable"] == "true",
                focusable = attrs["focusable"] == "true",
                focused = attrs["focused"] == "true",
                editable = attrs["editable"] == "true",
                password = attrs["password"] == "true",
                enabled = attrs["enabled"] != "false",
                visible = attrs["visible-to-user"] != "false"
            )
        }.toList()
    }

    private fun parseXmlAttributes(raw: String): Map<String, String> {
        return ATTR_REGEX.findAll(raw).associate { match ->
            match.groupValues[1] to match.groupValues[2]
        }
    }

    private fun parseRootBounds(xml: String?): Rect? {
        if (xml.isNullOrBlank()) return null
        val hierarchy = HIERARCHY_TAG_REGEX.find(xml)?.groupValues?.getOrNull(1) ?: return null
        return parseBounds(parseXmlAttributes(hierarchy)["bounds"])
    }

    private fun parseBounds(value: String?): Rect? {
        if (value.isNullOrBlank()) return null
        val match = BOUNDS_REGEX.find(value) ?: return null
        val left = match.groupValues[1].toIntOrNull() ?: return null
        val top = match.groupValues[2].toIntOrNull() ?: return null
        val right = match.groupValues[3].toIntOrNull() ?: return null
        val bottom = match.groupValues[4].toIntOrNull() ?: return null
        return Rect(left, top, right, bottom)
    }

    private fun decodeXmlAttr(value: String?): String? {
        val raw = value ?: return null
        return raw
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
    }

    private fun captureCurrentXml(): String? {
        return try {
            AccessibilityController.initController()
            // withOld=false: live accessibility-tree query, not event-driven cache.
            // When the current surface has no accessibility tree (Launcher/Desktop,
            // WebView/SurfaceView, games), source XML is optional. Do not fall back
            // to an older cached tree because that pollutes coordinate-only RunLog.
            AccessibilityController.getCaptureScreenShotXml(false)
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            OmniLog.w(TAG, "manual trace xml capture failed: ${e.message}")
            null
        }
    }

    private fun captureCurrentScreenshotRef(
        stage: String,
        annotation: ScreenshotAnnotation? = null
    ): ManualVlmScreenshotRef? {
        if (!debugScreenshotsActive()) {
            screenshotSkippedCount += 1
            return null
        }
        return try {
            val sequence = nextScreenshotSequence()
            val capturedAtMs = System.currentTimeMillis()
            val capture = runBlocking {
                withTimeoutOrNull(DEBUG_SCREENSHOT_CAPTURE_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        AccessibilityController.captureScreenshotImage(
                            isBitmap = true,
                            isBase64 = false,
                            isFile = false,
                            isFilterOverlay = true,
                            compressQuality = DEBUG_SCREENSHOT_QUALITY
                        )
                    }
                }
            }
            val bitmap = capture?.imageBitmap
            if (capture?.isSuccess != true || bitmap == null) {
                if (bitmap != null && !bitmap.isRecycled) {
                    bitmap.recycle()
                }
                screenshotFailedCount += 1
                debugScreenshotLastErrorType = "capture_unsuccessful"
                OmniLog.w(TAG, "manual debug screenshot capture failed stage=$stage")
                return null
            }
            var outputBitmap: Bitmap? = null
            try {
                val preparedBitmap = prepareDebugScreenshotBitmap(
                    bitmap = bitmap,
                    // Keep the screenshot itself clean for downstream detection/VLM evidence.
                    // The touch marker is persisted as metadata on ManualVlmScreenshotRef.
                    annotation = null,
                    appliedScale = capture.appliedScale
                )
                outputBitmap = preparedBitmap
                val relativeDir = screenshotRootRelativePath()
                val screenshotDir = File(context.filesDir, relativeDir)
                screenshotDir.mkdirs()
                val file = File(
                    screenshotDir,
                    "${sequence.toString().padStart(4, '0')}_${safePathSegment(stage)}.jpg"
                )
                val bytes = ByteArrayOutputStream().use { output ->
                    preparedBitmap.compress(Bitmap.CompressFormat.JPEG, DEBUG_SCREENSHOT_JPEG_QUALITY, output)
                    output.toByteArray()
                }
                file.writeBytes(bytes)
                screenshotStoredCount += 1
                ManualVlmScreenshotRef(
                    path = file.absolutePath,
                    relativePath = file.relativeTo(context.filesDir).path,
                    mimeType = "image/jpeg",
                    width = preparedBitmap.width,
                    height = preparedBitmap.height,
                    bytes = bytes.size.toLong(),
                    sha256 = sha256(bytes),
                    capturedAtMs = capturedAtMs,
                    captureStage = stage,
                    annotation = annotation?.asMap(capture.appliedScale).orEmpty()
                )
            } finally {
                val preparedBitmap = outputBitmap
                if (preparedBitmap != null && !preparedBitmap.isRecycled) {
                    preparedBitmap.recycle()
                }
                if (preparedBitmap !== bitmap && !bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        } catch (error: OutOfMemoryError) {
            recordDebugScreenshotFailure(
                stage = stage,
                error = error,
                disableForSession = true,
                disabledReason = "disabled_after_oom"
            )
        } catch (error: Throwable) {
            recordDebugScreenshotFailure(stage, error)
        }
    }

    private fun debugScreenshotsActive(): Boolean =
        enableDebugScreenshots && debugScreenshotDisabledReason == null

    private fun recordDebugScreenshotFailure(
        stage: String,
        error: Throwable,
        disableForSession: Boolean = false,
        disabledReason: String? = null
    ): ManualVlmScreenshotRef? {
        screenshotFailedCount += 1
        debugScreenshotLastErrorType = error.javaClass.name
        if (disableForSession) {
            debugScreenshotDisabledReason = disabledReason ?: error.javaClass.name
        }
        return try {
            OmniLog.w(
                TAG,
                "manual debug screenshot capture failed stage=$stage " +
                    "type=${error.javaClass.name}: ${error.message}"
            )
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun prepareDebugScreenshotBitmap(
        bitmap: Bitmap,
        annotation: ScreenshotAnnotation?,
        appliedScale: Float
    ): Bitmap {
        val mutableBitmap = if (bitmap.isMutable && bitmap.config != Bitmap.Config.HARDWARE) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, true).also {
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        }
        if (annotation != null) {
            drawDebugScreenshotAnnotation(mutableBitmap, annotation, appliedScale)
        }
        return mutableBitmap
    }

    private fun drawDebugScreenshotAnnotation(
        bitmap: Bitmap,
        annotation: ScreenshotAnnotation,
        appliedScale: Float
    ) {
        val canvas = Canvas(bitmap)
        val scale = appliedScale.takeIf { it > 0f } ?: 1f
        val stroke = max(4f, min(bitmap.width, bitmap.height) * 0.006f)
        val radius = max(16f, min(bitmap.width, bitmap.height) * 0.024f)
        val redPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 48, 48)
            style = Paint.Style.STROKE
            strokeWidth = stroke
        }
        val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = stroke / 2f
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(70, 255, 48, 48)
            style = Paint.Style.FILL
        }
        val x = annotation.x * scale
        val y = annotation.y * scale
        canvas.drawCircle(x, y, radius, fillPaint)
        canvas.drawCircle(x, y, radius, whitePaint)
        canvas.drawCircle(x, y, radius, redPaint)
        canvas.drawLine(x - radius * 1.45f, y, x + radius * 1.45f, y, whitePaint)
        canvas.drawLine(x, y - radius * 1.45f, x, y + radius * 1.45f, whitePaint)
        canvas.drawLine(x - radius * 1.45f, y, x + radius * 1.45f, y, redPaint)
        canvas.drawLine(x, y - radius * 1.45f, x, y + radius * 1.45f, redPaint)
        if (annotation.endX != null && annotation.endY != null) {
            val endX = annotation.endX * scale
            val endY = annotation.endY * scale
            canvas.drawLine(x, y, endX, endY, whitePaint)
            canvas.drawLine(x, y, endX, endY, redPaint)
            canvas.drawCircle(endX, endY, radius * 0.55f, whitePaint)
            canvas.drawCircle(endX, endY, radius * 0.55f, redPaint)
        }
    }

    private fun buildSummary(actions: List<ManualVlmRecordedAction>): String {
        if (actions.isEmpty()) return ""
        val actionSummary = actions.take(MAX_SUMMARY_ACTIONS).joinToString("；") { action ->
            action.summary.ifBlank { action.title }
        }
        val suffix = if (actions.size > MAX_SUMMARY_ACTIONS) "；..." else ""
        return "用户在接管期间手动完成了 ${actions.size} 步操作：$actionSummary$suffix。请基于当前屏幕继续执行原任务。"
    }

    private fun recordingBackendForStatus(): String {
        return when {
            manualControlRecordedCount > 0 && overlayGestureRecordedCount > 0 -> "mixed_manual_control"
            manualControlRecordedCount > 0 -> MANUAL_CONTROL_BACKEND
            overlayGestureRecordedCount > 0 -> OVERLAY_TOUCH_BACKEND
            rawTouchStatus?.available == true && enableRawTouch -> "mixed"
            enableRawTouch -> "overlay_touch_with_raw_unavailable"
            else -> OVERLAY_TOUCH_BACKEND
        }
    }

    private fun appendRecordedAction(action: ManualVlmRecordedAction) {
        val index = recordedActions.size + 1
        recordedActions += action
        val callback = onActionRecorded
        if (callback != null) {
            actionPersistCallbackQueuedCount += 1
            actionPersistScope.launch {
                runCatching { callback(index, action) }
                    .onFailure { error ->
                        synchronized(recordingLock) {
                            actionPersistCallbackFailedCount += 1
                            actionPersistCallbackLastErrorType = error.javaClass.name
                            actionPersistCallbackLastErrorMessage = error.message?.take(MAX_ERROR_MESSAGE_LENGTH)
                        }
                        OmniLog.w(TAG, "manual trace action persist callback failed: ${error.message}")
                    }
            }
        }
        OmniLog.d(TAG, "manual trace recorded: ${action.actionName} ${action.summary}")
    }

    private fun eventContextFor(
        event: AccessibilityEvent,
        target: ManualEventTarget,
        sourceSnapshot: AccessibilitySourceSnapshot? = null,
        clickInference: Map<String, Any?> = emptyMap()
    ): Map<String, Any?> {
        return linkedMapOf<String, Any?>(
            "event_type" to eventTypeName(event.eventType),
            "event_package" to event.packageName?.toString(),
            "event_class" to event.className?.toString(),
            "event_text" to event.text.joinToString(" ").take(120).ifBlank { null },
            "event_time_ms" to event.eventTime,
            "event_has_source" to (sourceSnapshot != null),
            "source_class" to sourceSnapshot?.className,
            "source_view_id" to sourceSnapshot?.viewIdResourceName,
            "source_text" to sourceSnapshot?.text?.take(120),
            "source_content_description" to sourceSnapshot?.contentDescription?.take(120),
            "source_bounds" to sourceSnapshot?.bounds?.let(::boundsString),
            "scroll_x" to event.scrollX,
            "scroll_y" to event.scrollY,
            "scroll_delta_x" to event.scrollDeltaX,
            "scroll_delta_y" to event.scrollDeltaY,
            "from_index" to event.fromIndex,
            "to_index" to event.toIndex,
            "item_count" to event.itemCount,
            "target_resolution" to target.resolution,
            "target_package" to target.packageName,
            "target_resource_id" to target.resourceId,
            "target_class" to target.className,
            "target_bounds" to boundsString(target.bounds),
            "click_inference" to clickInference.takeIf { it.isNotEmpty() }
        ).filterValues { it != null }
    }

    private fun rawEventContextFor(
        gesture: ManualRawTouchGesture,
        target: ManualEventTarget
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "event_type" to "RAW_GETEVENT_${gesture.actionName.uppercase()}",
        "event_has_source" to false,
        "recording_backend" to gesture.backend,
        "device_path" to gesture.devicePath,
        "device_name" to gesture.deviceName,
        "gesture_id" to gesture.gestureId,
        "gesture_duration_ms" to gesture.durationMs,
        "gesture_distance_px" to gesture.distancePx,
        "gesture_point_count" to gesture.pointCount,
        "start_x" to gesture.startX,
        "start_y" to gesture.startY,
        "end_x" to gesture.endX,
        "end_y" to gesture.endY,
        "raw_start_x" to gesture.rawStartX,
        "raw_start_y" to gesture.rawStartY,
        "raw_end_x" to gesture.rawEndX,
        "raw_end_y" to gesture.rawEndY,
        "target_resolution" to target.resolution,
        "target_package" to target.packageName,
        "target_resource_id" to target.resourceId,
        "target_class" to target.className,
        "target_bounds" to boundsString(target.bounds)
    ).filterValues { it != null }

    private fun overlayEventContextFor(
        gesture: ManualOverlayTouchGesture,
        target: ManualEventTarget,
        operationId: String,
        dispatchOutcome: OverlayDispatchOutcome,
        beforeXml: String?,
        beforeXmlCaptureMs: Long?
    ): Map<String, Any?> = (linkedMapOf<String, Any?>(
        "event_type" to "OVERLAY_TOUCH_${gesture.actionName.uppercase()}",
        "event_has_source" to false,
        "recording_backend" to OVERLAY_TOUCH_BACKEND,
        "coordinate_space" to SCREEN_ABSOLUTE_COORDINATE_SPACE,
        "execution_mode" to SYNTHETIC_REPLAY_EXECUTION_MODE,
        "gesture_duration_ms" to gesture.durationMs,
        "gesture_distance_px" to gesture.distancePx,
        "direction" to overlaySwipeDirectionName(gesture).takeIf { gesture.actionName == OobActionSchema.TOOL_SWIPE },
        "start_x" to gesture.startX,
        "start_y" to gesture.startY,
        "end_x" to gesture.endX,
        "end_y" to gesture.endY,
        "display_width" to gesture.displayWidth.takeIf { it > 0 },
        "display_height" to gesture.displayHeight.takeIf { it > 0 },
        "target_resolution" to target.resolution,
        "target_package" to target.packageName,
        "target_resource_id" to target.resourceId,
        "target_class" to target.className,
        "target_bounds" to boundsString(target.bounds)
    ) + overlayDispatchDiagnostics(
        operationId,
        beforeXml,
        dispatchOutcome,
        beforeXmlCaptureMs = beforeXmlCaptureMs
    )).filterValues { it != null }

    private fun textInputEventContextFor(
        event: AccessibilityEvent,
        target: ManualEventTarget,
        sourceSnapshot: AccessibilitySourceSnapshot?,
        anchor: TextInputAnchor
    ): Map<String, Any?> {
        return eventContextFor(event, target, sourceSnapshot) + linkedMapOf(
            "input_anchor" to anchor.asMap()
        )
    }

    private fun xmlTextInputEventContextFor(
        target: ManualEventTarget,
        sourceCandidate: XmlNodeCandidate,
        anchor: TextInputAnchor,
        rawText: String,
        resolutionSuffix: String
    ): Map<String, Any?> {
        return linkedMapOf<String, Any?>(
            "event_type" to "FOCUSED_XML_TEXT_FALLBACK",
            "event_has_source" to false,
            "raw_xml_text" to rawText.take(120).takeIf { it.isNotBlank() },
            "source_class" to sourceCandidate.className,
            "source_view_id" to sourceCandidate.resourceId,
            "source_text" to sourceCandidate.text?.take(120),
            "source_content_description" to sourceCandidate.contentDescription?.take(120),
            "source_bounds" to boundsString(sourceCandidate.bounds),
            "target_resolution" to target.resolution,
            "target_package" to target.packageName,
            "target_resource_id" to target.resourceId,
            "target_class" to target.className,
            "target_bounds" to boundsString(target.bounds),
            "input_anchor" to anchor.asMap(),
            "fallback_reason" to resolutionSuffix
        ).filterValues { it != null }
    }

    private fun unanchoredTextInputEventContextFor(
        event: AccessibilityEvent,
        target: ManualEventTarget,
        sourceSnapshot: AccessibilitySourceSnapshot?
    ): Map<String, Any?> {
        return eventContextFor(event, target, sourceSnapshot) + linkedMapOf(
            "input_anchor_policy" to "text_event_without_anchor",
            "recording_backend" to A11Y_TEXT_EVENT_BACKEND
        )
    }

    private fun focusedXmlTextInputEventContextFor(
        target: ManualEventTarget,
        sourceCandidate: XmlNodeCandidate,
        rawText: String,
        resolutionSuffix: String
    ): Map<String, Any?> {
        return linkedMapOf<String, Any?>(
            "event_type" to "FOCUSED_XML_TEXT_FALLBACK",
            "event_has_source" to false,
            "raw_xml_text" to rawText.take(120).takeIf { it.isNotBlank() },
            "source_class" to sourceCandidate.className,
            "source_view_id" to sourceCandidate.resourceId,
            "source_text" to sourceCandidate.text?.take(120),
            "source_content_description" to sourceCandidate.contentDescription?.take(120),
            "source_bounds" to boundsString(sourceCandidate.bounds),
            "target_resolution" to target.resolution,
            "target_package" to target.packageName,
            "target_resource_id" to target.resourceId,
            "target_class" to target.className,
            "target_bounds" to boundsString(target.bounds),
            "fallback_reason" to resolutionSuffix
        ).filterValues { it != null }
    }

    private fun imeSubmitEventContextFor(
        gesture: ManualOverlayTouchGesture,
        packageName: String?
    ): Map<String, Any?> {
        return linkedMapOf<String, Any?>(
            "event_type" to IME_SUBMIT_EVENT_TYPE,
            "event_has_source" to false,
            "recording_backend" to IME_SUBMIT_BACKEND,
            "target_package" to packageName,
            "key" to "enter",
            "gesture_x" to gesture.startX,
            "gesture_y" to gesture.startY,
            "display_width" to gesture.displayWidth.takeIf { it > 0 },
            "display_height" to gesture.displayHeight.takeIf { it > 0 },
            "submit_detection" to linkedMapOf(
                "source" to "manual_overlay_keyboard_region",
                "requires_ime_visible_or_expected" to true,
                "min_x_ratio" to 0.72f,
                "min_keyboard_y_ratio" to 0.55f
            )
        ).filterValues { it != null }
    }

    private fun rawSwipeDirection(gesture: ManualRawTouchGesture): String {
        val dx = gesture.endX - gesture.startX
        val dy = gesture.endY - gesture.startY
        return if (abs(dx) > abs(dy)) {
            if (dx > 0) "right" else "left"
        } else {
            if (dy > 0) "down" else "up"
        }
    }

    private fun overlaySwipeDirection(gesture: ManualOverlayTouchGesture): ScrollDirection {
        return when (overlaySwipeDirectionName(gesture)) {
            "up" -> ScrollDirection.UP
            "down" -> ScrollDirection.DOWN
            "left" -> ScrollDirection.LEFT
            "right" -> ScrollDirection.RIGHT
            else -> ScrollDirection.DOWN
        }
    }

    private fun overlaySwipeDirectionName(gesture: ManualOverlayTouchGesture): String {
        val explicit = gesture.direction?.lowercase()
        if (explicit == "up" || explicit == "down" || explicit == "left" || explicit == "right") {
            return explicit
        }
        val dx = gesture.endX - gesture.startX
        val dy = gesture.endY - gesture.startY
        return if (abs(dx) > abs(dy)) {
            if (dx > 0) "right" else "left"
        } else {
            if (dy > 0) "down" else "up"
        }
    }

    private fun ManualEventTarget.asOverlayTarget(): ManualEventTarget =
        copy(resolution = resolution.replace("raw_touch", "overlay_touch"))

    private fun buildDiagnostics(): Map<String, Any?> {
        val rawActions = recordedActions.count {
            val backend = it.params["recording_backend"]?.toString()
            backend == RAW_TOUCH_BACKEND || backend == RAW_TOUCH_TEXT_INPUT_BACKEND
        }
        val overlayActions = recordedActions.count {
            val backend = it.params["recording_backend"]?.toString()
            backend == OVERLAY_TOUCH_BACKEND || backend == OVERLAY_TOUCH_TEXT_INPUT_BACKEND
        }
        val imeSubmitActions = recordedActions.count {
            it.params["recording_backend"]?.toString() == IME_SUBMIT_BACKEND
        }
        val manualControlActions = recordedActions.count {
            it.params["recording_backend"]?.toString() == MANUAL_CONTROL_BACKEND
        }
        val semanticActions = 0
        val rawTouchAvailable = rawTouchStatus?.available == true
        val overlayTouchAvailable = overlayGestureStartedCount > 0 ||
            overlayGestureRecordedCount > 0 ||
            imeSubmitActions > 0
        val expectedOverlayRecordCount = (overlayGestureStartedCount - overlayGestureIgnoredControlCount)
            .coerceAtLeast(0)
        val overlayTouchComplete = overlayTouchAvailable &&
            overlayGestureFailedCount == 0 &&
            overlayGestureRecordedCount >= expectedOverlayRecordCount
        val completeness = when {
            overlayTouchComplete -> ManualRecordingDiagnostics.COMPLETE_OVERLAY_TOUCH
            overlayTouchAvailable -> ManualRecordingDiagnostics.INCOMPLETE_OVERLAY_TOUCH
            else -> ManualRecordingDiagnostics.completeness(
                rawTouchAvailable = rawTouchAvailable,
                rawTouchActiveAtStop = rawTouchActiveAtStop
            )
        }
        val guaranteesNoMissingClicks = overlayTouchComplete ||
            ManualRecordingDiagnostics.guaranteesNoMissingClicks(
                rawTouchAvailable = rawTouchAvailable,
                rawTouchActiveAtStop = rawTouchActiveAtStop
            )
        val warningMessage = when {
            overlayTouchAvailable && !overlayTouchComplete -> ManualRecordingDiagnostics.warningMessage(completeness)
            enableRawTouch -> ManualRecordingDiagnostics.warningMessage(completeness)
            else -> null
        }
        return linkedMapOf<String, Any?>(
            "raw_touch" to rawTouchStatus?.asMap()?.plus(
                linkedMapOf<String, Any?>(
                    "active_at_stop" to rawTouchActiveAtStop,
                    "started_gesture_count" to rawGestureStartedCount,
                    "finished_gesture_count" to rawGestureFinishedCount,
                    "recorded_gesture_count" to rawGestureRecordedCount,
                    "ignored_control_gesture_count" to rawGestureIgnoredControlCount,
                    "recorded_action_count" to rawActions,
                    "event_stream" to rawGeteventStreamDiagnostics()
                ).filterValues { it != null }
            ),
            "screenshots" to linkedMapOf(
                "schema_version" to "oob.runlog.screenshot_refs.v1",
                "requested" to enableDebugScreenshots,
                "enabled" to debugScreenshotsActive(),
                "mode" to if (debugScreenshotsActive()) {
                    "debug_marked_click_positions"
                } else {
                    "disabled"
                },
                "disabled_reason" to (
                    debugScreenshotDisabledReason
                        ?: if (enableDebugScreenshots) null else {
                            "manual_recording_uses_real_touch_and_before_xml"
                        }
                    ),
                "last_error_type" to debugScreenshotLastErrorType,
                "storage" to "app_private_files",
                "reference_style" to "path_only",
                "stored_count" to screenshotStoredCount,
                "failed_count" to screenshotFailedCount,
                "skipped_count" to screenshotSkippedCount,
                "root_relative_path" to screenshotRootRelativePath(),
                "annotation" to if (debugScreenshotsActive()) {
                    "actual_touch_position"
                } else {
                    null
                }
            ).filterValues { it != null },
            "xml_capture" to linkedMapOf(
                "schema_version" to "oob.manual_recording.xml_capture_timing.v1",
                "capture_count" to xmlCaptureCount,
                "success_count" to xmlCaptureSuccessCount,
                "timeout_or_empty_count" to xmlCaptureTimeoutOrEmptyCount,
                "total_ms" to xmlCaptureTotalMs,
                "avg_ms" to if (xmlCaptureCount > 0) xmlCaptureTotalMs / xmlCaptureCount else 0L,
                "max_ms" to xmlCaptureMaxMs,
                "last_ms" to xmlCaptureLastMs,
                "last_reason" to xmlCaptureLastReason.takeIf { it.isNotBlank() },
                "timeout_ms" to BEFORE_XML_CAPTURE_TIMEOUT_MS
            ).filterValues { it != null },
            "accessibility_events" to linkedMapOf(
                "event_count" to accessibilityEventCount,
                "ignored_package_event_count" to accessibilityIgnoredPackageCount,
                "event_type_counts" to accessibilityEventTypeCounts.toMap(),
                "suppressed_semantic_action_event_count" to suppressedSemanticActionEventCount,
                "suppressed_non_raw_action_count" to suppressedNonRawActionCount,
                "records_replayable_actions" to false,
                "records_text_input_when_touch_anchored" to true,
                "records_text_input_from_text_changed_without_anchor" to true,
                "records_post_input_click_when_touch_bypassed" to true,
                "post_input_click_grace_ms" to POST_INPUT_CLICK_GRACE_MS,
                "post_input_a11_action_recorded_count" to postInputA11ActionRecordedCount,
                "post_input_a11_action_suppressed_count" to postInputA11ActionSuppressedCount,
                "unanchored_text_changed_recorded_count" to unanchoredTextChangedRecordedCount,
                "unanchored_text_changed_suppressed_count" to unanchoredTextChangedSuppressedCount,
                "manual_control_text_changed_suppressed_count" to manualControlTextChangedSuppressedCount,
                "active_background_event_count" to accessibilityEventJobs.get(),
                "drain_timeout_count" to accessibilityEventDrainTimeoutCount,
                "recorded_action_count" to semanticActions
            ),
            "overlay_touch" to linkedMapOf(
                "available" to overlayTouchAvailable,
                "backend" to OVERLAY_TOUCH_BACKEND,
                "execution_mode" to SYNTHETIC_REPLAY_EXECUTION_MODE,
                "started_gesture_count" to overlayGestureStartedCount,
                "recorded_gesture_count" to overlayGestureRecordedCount,
                "ime_submit_recorded_count" to imeSubmitRecordedCount,
                "manual_control_recorded_count" to manualControlRecordedCount,
                "ignored_control_gesture_count" to overlayGestureIgnoredControlCount,
                "failed_gesture_count" to overlayGestureFailedCount,
                "expected_recorded_gesture_count" to expectedOverlayRecordCount,
                "coordinate_replay_count" to overlayCoordinateReplayCount,
                "coordinate_replay_failed_count" to overlayCoordinateReplayFailedCount,
                "node_replay_fallback_count" to overlayNodeReplayFallbackCount,
                "node_replay_fallback_failed_count" to overlayNodeReplayFallbackFailedCount,
                "keyboard_click_suppressed_count" to overlayKeyboardClickSuppressedCount,
                "pending_post_record_count" to 0,
                "post_record_timeout_count" to overlayPostRecordTimeoutCount,
                "recorded_action_count" to (overlayActions + imeSubmitActions + manualControlActions)
            ),
            "unattributed_window_transitions" to linkedMapOf(
                "event_count" to windowTransitionEventCount,
                "count" to 0,
                "replayable" to false,
                "reason" to "window/content changes are ignored; manual RunLog records concrete touch, text-input, and keyboard-submit actions"
            ),
            "manual_recording" to linkedMapOf(
                "action_source" to when {
                    overlayActions > 0 && rawActions > 0 -> "mixed_real_touch"
                    manualControlActions > 0 && (overlayActions > 0 || imeSubmitActions > 0 || rawActions > 0) -> "mixed_manual_control"
                    manualControlActions > 0 -> "manual_control"
                    overlayActions > 0 || imeSubmitActions > 0 -> "overlay_touch"
                    rawActions > 0 -> "raw_touch"
                    else -> "none"
                },
                "overlay_touch_available" to overlayTouchAvailable,
                "execution_mode" to if (overlayTouchAvailable) SYNTHETIC_REPLAY_EXECUTION_MODE else null,
                "raw_touch_enabled" to enableRawTouch,
                "raw_touch_required" to false,
                "a11_replay_actions_enabled" to false,
                "a11_text_input_enabled" to true,
                "a11_text_input_anchor_policy" to "text_event_or_touch_anchor",
                "a11_post_input_click_enabled" to true,
                "action_count" to recordedActions.size,
                "overlay_action_count" to overlayActions,
                "ime_submit_action_count" to imeSubmitActions,
                "manual_control_action_count" to manualControlActions,
                "raw_action_count" to rawActions,
                "semantic_action_count" to semanticActions,
                "async_action_persist" to linkedMapOf(
                    "queued_count" to actionPersistCallbackQueuedCount,
                    "failed_count" to actionPersistCallbackFailedCount,
                    "last_error_type" to actionPersistCallbackLastErrorType,
                    "last_error_message" to actionPersistCallbackLastErrorMessage
                ).filterValues { it != null },
                "xml_capture_count" to xmlCaptureCount,
                "xml_capture_total_ms" to xmlCaptureTotalMs,
                "xml_capture_avg_ms" to if (xmlCaptureCount > 0) xmlCaptureTotalMs / xmlCaptureCount else 0L,
                "xml_capture_max_ms" to xmlCaptureMaxMs,
                "suppressed_non_raw_action_count" to suppressedNonRawActionCount,
                "completeness" to completeness,
                "missing_raw_touch" to (enableRawTouch && !rawTouchAvailable),
                "raw_touch_active_at_stop" to rawTouchActiveAtStop,
                "guarantees_no_missing_clicks" to guaranteesNoMissingClicks,
                "unattributed_window_transition_count" to 0,
                "warning_message" to warningMessage
            )
        ).filterValues { it != null }
    }

    private fun eventTypeName(eventType: Int): String = when (eventType) {
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "TYPE_VIEW_CLICKED"
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "TYPE_VIEW_LONG_CLICKED"
        AccessibilityEvent.TYPE_VIEW_FOCUSED -> "TYPE_VIEW_FOCUSED"
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "TYPE_VIEW_TEXT_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "TYPE_WINDOW_STATE_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "TYPE_WINDOW_CONTENT_CHANGED"
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "TYPE_VIEW_SCROLLED"
        else -> "TYPE_$eventType"
    }

    private fun rawGeteventStreamDiagnostics(): Map<String, Any?>? {
        if (rawGeteventLineCount <= 0 && rawGeteventRecentLines.isEmpty()) return null
        return linkedMapOf(
            "format" to "getevent -lt",
            "scope" to "selected_touch_device_only",
            "retention_policy" to "last_$MAX_RAW_GETEVENT_RECENT_LINES",
            "line_count" to rawGeteventLineCount,
            "retained_line_count" to rawGeteventRecentLines.size,
            "dropped_line_count" to rawGeteventDroppedLineCount,
            "truncated" to (rawGeteventDroppedLineCount > 0),
            "events" to rawGeteventRecentLines.toList()
        )
    }

    private fun eventWallTime(eventTimeMs: Long, nowWallMs: Long): Long {
        if (eventTimeMs <= 0L) return nowWallMs
        val ageMs = (SystemClock.uptimeMillis() - eventTimeMs).coerceAtLeast(0L)
        return (nowWallMs - ageMs).coerceAtMost(nowWallMs)
    }

    private fun firstNonBlank(vararg values: String?): String {
        for (value in values) {
            val normalized = value?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            if (normalized.isNotEmpty()) return normalized
        }
        return ""
    }

    private fun incrementCount(counts: MutableMap<String, Int>, key: String) {
        counts[key] = (counts[key] ?: 0) + 1
    }

    private fun Any?.asFloatOrNull(): Float? = when (this) {
        is Number -> toFloat()
        is String -> toFloatOrNull()
        else -> null
    }

    private data class PendingTextAction(
        val nodeKey: String,
        val anchorId: String,
        val packageName: String?,
        val label: String,
        val text: String,
        val bounds: Rect,
        val className: String?,
        val resourceId: String?,
        val resolution: String,
        val recordingBackend: String,
        val beforeXml: String?,
        val beforeScreenshot: ManualVlmScreenshotRef?,
        val startedAtMs: Long,
        val updatedAtMs: Long,
        val eventContext: Map<String, Any?>
    )

    private data class PostInputClickWindow(
        val inputNodeKey: String,
        val inputBounds: Rect,
        val inputPackageName: String?,
        val openedAtMs: Long,
        val expiresAtMs: Long
    )

    private data class AccessibilitySourceSnapshot(
        val bounds: Rect?,
        val packageName: String?,
        val className: String?,
        val text: String?,
        val hintText: String?,
        val contentDescription: String?,
        val viewIdResourceName: String?,
        val isPassword: Boolean
    )

    private data class TextInputAnchor(
        val id: String,
        val backend: String,
        val beforeXml: String?,
        val beforeScreenshot: ManualVlmScreenshotRef?,
        val target: ManualEventTarget,
        val x: Float,
        val y: Float,
        val startedAtMs: Long,
        val finishedAtMs: Long
    ) {
        fun asMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
            "id" to id,
            "backend" to backend,
            "x" to x,
            "y" to y,
            "started_at_ms" to startedAtMs,
            "finished_at_ms" to finishedAtMs,
            "target_description" to target.label,
            "target_bounds" to "[${target.bounds.left},${target.bounds.top}][${target.bounds.right},${target.bounds.bottom}]",
            "target_package" to target.packageName,
            "target_resource_id" to target.resourceId,
            "target_class" to target.className,
            "target_resolution" to target.resolution,
            "has_before_xml" to !beforeXml.isNullOrBlank(),
            "has_before_screenshot" to (beforeScreenshot != null)
        ).filterValues { it != null }

    }

    private data class ManualEventTarget(
        val label: String,
        val bounds: Rect,
        val packageName: String?,
        val className: String?,
        val resourceId: String?,
        val text: String?,
        val contentDescription: String?,
        val stableKey: String,
        val resolution: String
    )

    private data class XmlNodeCandidate(
        val bounds: Rect,
        val packageName: String?,
        val className: String?,
        val text: String?,
        val contentDescription: String?,
        val hintText: String?,
        val resourceId: String?,
        val clickable: Boolean,
        val scrollable: Boolean,
        val focusable: Boolean,
        val focused: Boolean,
        val editable: Boolean,
        val password: Boolean,
        val enabled: Boolean,
        val visible: Boolean
    ) {
        val bestLabel: String
            get() = firstNonBlankStatic(
                contentDescription,
                text,
                hintText,
                resourceId?.substringAfterLast('/'),
                className
            )

        fun toManualTarget(
            fallbackPackageName: String?,
            resolution: String
        ): ManualEventTarget {
            return ManualEventTarget(
                label = bestLabel,
                bounds = bounds,
                packageName = packageName ?: fallbackPackageName,
                className = className,
                resourceId = resourceId,
                text = text,
                contentDescription = contentDescription,
                stableKey = firstNonBlankStatic(resourceId, className) + "|" + boundsString(bounds),
                resolution = resolution
            )
        }
    }

    private companion object {
        private const val TAG = "ManualVlmTraceRecorder"
        private const val DUPLICATE_EVENT_WINDOW_MS = 400L
        private const val OVERLAY_TOUCH_BACKEND = "overlay_touch"
        private const val OVERLAY_TOUCH_TEXT_INPUT_BACKEND = "overlay_touch_text_input"
        private const val IME_SUBMIT_BACKEND = "ime_submit"
        private const val MANUAL_CONTROL_BACKEND = "manual_control"
        private const val A11Y_POST_INPUT_BACKEND = "a11y_post_input"
        private const val A11Y_TEXT_EVENT_BACKEND = "a11y_text_event"
        private const val SCREEN_ABSOLUTE_COORDINATE_SPACE = "screen_absolute_px"
        private const val SYNTHETIC_REPLAY_EXECUTION_MODE = "synthetic_replay"
        private const val A11Y_ANCHORED_EXECUTION_MODE = "a11y_event_anchored"
        private const val IME_ACTION_EXECUTION_MODE = "ime_action"
        private const val MANUAL_CONTROL_EXECUTION_MODE = "manual_control"
        private const val IME_SUBMIT_EVENT_TYPE = "IME_SUBMIT_KEY"
        private const val MANUAL_CONTROL_INPUT_EVENT_TYPE = "MANUAL_CONTROL_INPUT_TEXT"
        private const val MANUAL_CONTROL_PRESS_KEY_EVENT_TYPE = "MANUAL_CONTROL_PRESS_KEY"
        private const val MANUAL_CONTROL_AFTER_ACTION_CAPTURE_DELAY_MS = 180L
        private const val MANUAL_CONTROL_TEXT_CHANGE_SUPPRESS_MS = 1_500L
        private const val OVERLAY_RECORD_DRAIN_POLL_MS = 100L
        private const val OVERLAY_RECORD_DRAIN_TIMEOUT_MS = 600L
        private const val ACCESSIBILITY_EVENT_DRAIN_POLL_MS = 50L
        private const val ACCESSIBILITY_EVENT_DRAIN_TIMEOUT_MS = 600L
        private const val BEFORE_XML_CAPTURE_TIMEOUT_MS = 300L
        private const val TEXT_INPUT_ANCHOR_ACTIVE_TTL_MS = 8_000L
        private const val POST_INPUT_CLICK_GRACE_MS = 4_000L
        private const val TEXT_INPUT_KEYBOARD_CLICK_SUPPRESS_WINDOW_MS = 2_500L
        private const val TEXT_INPUT_ANCHOR_CLICK_GRACE_MS = 300L
        private const val OVERLAY_LONG_PRESS_MIN_DURATION_MS = 600L
        private const val OVERLAY_SWIPE_MIN_DURATION_MS = 120L
        private const val OVERLAY_SWIPE_MIN_DISTANCE_PX = 16f
        private const val OVERLAY_CLICK_REPLAY_TIMEOUT_MS = 900L
        private const val DISPATCH_STATUS_COMPLETED = "dispatch_completed"
        private const val DISPATCH_STATUS_TIMEOUT = "dispatch_timeout"
        private const val DISPATCH_STATUS_CANCELLED = "dispatch_cancelled"
        private const val DISPATCH_STATUS_FAILED = "dispatch_failed"
        private const val RAW_TOUCH_BACKEND = "device_getevent"
        private const val RAW_TOUCH_TEXT_INPUT_BACKEND = "device_getevent_text_input"
        private const val FOCUSED_XML_TEXT_INPUT_BACKEND = "focused_xml_text_input"
        private const val REAL_TOUCH_TEXT_INPUT_BACKEND = "real_touch_text_input"
        private const val MAX_LABEL_LENGTH = 80
        private const val MAX_SUMMARY_TEXT = 40
        private const val MAX_SUMMARY_ACTIONS = 8
        private const val MAX_ACTIONABLE_ANCESTOR_DEPTH = 4
        private const val MAX_IGNORED_CONTROL_AREA_RATIO = 0.20f
        private const val MAX_IGNORED_CONTROL_AREA_PX = 160_000
        private const val MAX_WINDOW_TRANSITION_SAMPLES = 8
        private const val MAX_RAW_GETEVENT_RECENT_LINES = 2_000
        private const val MAX_PAGE_FINGERPRINT_NODES = 220
        private const val MAX_PAGE_SUMMARY_LABELS = 8
        private const val MAX_PAGE_LABEL_LENGTH = 48
        private const val MAX_ERROR_MESSAGE_LENGTH = 240
        private const val DEBUG_SCREENSHOT_CAPTURE_TIMEOUT_MS = 2_800L
        private const val DEBUG_SCREENSHOT_JPEG_QUALITY = 90
        private val DEBUG_SCREENSHOT_QUALITY = ImageQuality.MEDIUM
        private const val REDACTED_TEXT = "[REDACTED]"
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private val TEXT_FIELD_ACCESSIBILITY_PREFIXES = setOf(
            "搜索",
            "search"
        )
        private val NODE_TAG_REGEX = Regex("<node\\b([^>]*)>")
        private val HIERARCHY_TAG_REGEX = Regex("<hierarchy\\b([^>]*)>")
        private val ATTR_REGEX = Regex("([A-Za-z0-9_:-]+)=\"([^\"]*)\"")
        private val BOUNDS_REGEX = Regex("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]")
        private val OOB_CONTROL_HINTS = listOf(
            "已完成操作",
            "完成学习",
            "取消学习",
            "继续执行",
            "用户已接管",
            "接管",
            "学习中",
            "resume",
            "continue",
            "takeover",
            "omnimind",
            "omnibot"
        )

        private fun firstNonBlankStatic(vararg values: String?): String {
            for (value in values) {
                val normalized = value?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
                if (normalized.isNotEmpty()) return normalized
            }
            return ""
        }

        private fun boundsString(bounds: Rect): String =
            "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]"

        private fun safePathSegment(value: String): String {
            val normalized = value
                .replace(Regex("[^A-Za-z0-9._-]+"), "_")
                .trim('_')
            return normalized.ifBlank { "manual_trace" }.take(96)
        }
    }

    private fun nextScreenshotSequence(): Int = synchronized(recordingLock) {
        screenshotSequence += 1
        screenshotSequence
    }

    private fun screenshotRootRelativePath(): String =
        "oob_runlog_artifacts/${safePathSegment(sessionLabel)}/screenshots"

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private data class ScreenshotAnnotation(
        val actionName: String,
        val x: Float,
        val y: Float,
        val endX: Float? = null,
        val endY: Float? = null
    ) {
        fun asMap(appliedScale: Float): Map<String, Any?> = linkedMapOf(
            "kind" to "actual_touch_position",
            "action_name" to actionName,
            "x" to x,
            "y" to y,
            "end_x" to endX,
            "end_y" to endY,
            "coordinate_space" to SCREEN_ABSOLUTE_COORDINATE_SPACE,
            "applied_scale" to appliedScale
        ).filterValues { it != null }

        companion object {
            fun point(actionName: String, x: Float, y: Float): ScreenshotAnnotation =
                ScreenshotAnnotation(actionName = actionName, x = x, y = y)

            fun forGesture(gesture: ManualOverlayTouchGesture): ScreenshotAnnotation {
                return if (gesture.actionName == OobActionSchema.TOOL_SWIPE) {
                    ScreenshotAnnotation(
                        actionName = gesture.actionName,
                        x = gesture.startX,
                        y = gesture.startY,
                        endX = gesture.endX,
                        endY = gesture.endY
                    )
                } else {
                    point(gesture.actionName, gesture.startX, gesture.startY)
                }
            }

            fun forRawGesture(gesture: ManualRawTouchGesture): ScreenshotAnnotation {
                return if (gesture.actionName == OobActionSchema.TOOL_SWIPE) {
                    ScreenshotAnnotation(
                        actionName = gesture.actionName,
                        x = gesture.startX,
                        y = gesture.startY,
                        endX = gesture.endX,
                        endY = gesture.endY
                    )
                } else {
                    point(
                        actionName = gesture.actionName,
                        x = (gesture.startX + gesture.endX) / 2f,
                        y = (gesture.startY + gesture.endY) / 2f
                    )
                }
            }
        }
    }
}
