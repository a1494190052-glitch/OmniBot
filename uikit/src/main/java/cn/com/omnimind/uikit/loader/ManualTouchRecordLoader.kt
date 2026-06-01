package cn.com.omnimind.uikit.loader

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.PointF
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import cn.com.omnimind.assists.HumanTrajectoryLearningSession
import cn.com.omnimind.assists.ManualOverlayTouchGesture
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.uikit.UIKit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

object ManualTouchRecordLoader {
    private const val TAG = "ManualTouchRecordLoader"
    private const val MIN_SWIPE_DISTANCE_DP = 24f
    private const val OVERLAY_UNLOCK_REPLAY_DELAY_MS = 96L
    private const val OVERLAY_REPLAY_TOUCH_SUPPRESS_AFTER_MS = 120L
    private const val IME_VISIBILITY_PROBE_DELAY_MS = 120L
    private const val IME_VISIBILITY_PROBE_TIMEOUT_MS = 1_500L
    private const val IME_VISIBILITY_PROBE_POLL_MS = 70L
    private const val IME_RELOCK_INITIAL_DELAY_MS = 400L
    private const val IME_RELOCK_POLL_MS = 900L
    private const val IME_RELIABLE_TOP_MIN_RATIO = 0.25f
    private const val IME_RELIABLE_TOP_MAX_RATIO = 0.92f
    private const val IME_ESTIMATED_TOP_RATIO = 0.58f
    private const val IME_OPEN_EXPECTED_TTL_MS = 1_200L
    private const val IME_GEOMETRY_SEEN_TTL_MS = 1_500L
    private const val IME_TOP_CACHE_TTL_MS = 12_000L

    private val recordScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var currentTouchable: Boolean? = null
    private var currentOverlayHeight: Int = 0
    private var displayWidth: Int = 0
    private var displayHeight: Int = 0
    private var imeVisibilityProbeJob: Job? = null
    private var imeRelockJob: Job? = null
    private var imeGeometryProbeJob: Job? = null
    private var imeProbeClearWhenMissing: Boolean = false
    private var lastReliableImeTop: Int? = null
    private var lastReliableImeTopAtMs: Long = 0L
    private var lastImeGeometrySeenAtMs: Long = 0L
    private var imeOpenExpectedUntilMs: Long = 0L
    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    private var downAtMs = 0L
    private var isTracking = false
    private var isProcessing = false
    private var syntheticReplayInFlight = false
    private var syntheticReplaySuppressUntilMs = 0L
    private val pendingGestures = ArrayDeque<ManualOverlayTouchGesture>()

    fun show(context: Context? = UIKit.appContext): Boolean {
        val appContext = context?.applicationContext ?: UIKit.appContext
        if (appContext == null) return false
        return synchronized(this) {
            if (overlayView?.isAttachedToWindow == true) {
                lockTouchLocked()
                return@synchronized true
            }
            if (tryShowLocked(appContext)) return@synchronized true
            OmniLog.w(TAG, "manual touch recording overlay unavailable")
            false
        }
    }

    fun hide() {
        synchronized(this) {
            isTracking = false
            isProcessing = false
            pendingGestures.clear()
            imeVisibilityProbeJob?.cancel()
            imeVisibilityProbeJob = null
            imeRelockJob?.cancel()
            imeRelockJob = null
            imeGeometryProbeJob?.cancel()
            imeGeometryProbeJob = null
            imeProbeClearWhenMissing = false
            val view = overlayView
            val manager = windowManager
            overlayView = null
            overlayParams = null
            windowManager = null
            currentTouchable = null
            currentOverlayHeight = 0
            displayWidth = 0
            displayHeight = 0
            lastReliableImeTop = null
            lastReliableImeTopAtMs = 0L
            lastImeGeometrySeenAtMs = 0L
            imeOpenExpectedUntilMs = 0L
            syntheticReplayInFlight = false
            syntheticReplaySuppressUntilMs = 0L
            if (view != null && manager != null && view.isAttachedToWindow) {
                runCatching { manager.removeView(view) }
                    .onFailure { OmniLog.w(TAG, "hide failed: ${it.message}") }
            }
        }
    }

    private fun tryShowLocked(context: Context): Boolean {
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = buildTouchView(context)
        val displaySize = realDisplaySize(context)
        val params = buildParams(context, touchable = true)
        return runCatching {
            manager.addView(view, params)
            windowManager = manager
            overlayView = view
            overlayParams = params
            currentTouchable = true
            currentOverlayHeight = params.height
            displayWidth = displaySize.x
            displayHeight = displaySize.y
            OmniLog.d(TAG, "manual touch recording overlay shown type=application")
            true
        }.getOrElse { error ->
            OmniLog.e(TAG, "show failed type=application: ${error.message}", error)
            false
        }
    }

    private fun buildTouchView(context: Context): View {
        val minSwipeDistance = MIN_SWIPE_DISTANCE_DP * context.resources.displayMetrics.density
        val touchSlop = max(
            minSwipeDistance,
            ViewConfiguration.get(context).scaledTouchSlop.toFloat() * 2f
        )
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        return View(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, event ->
                if (!shouldSuppressReplayTouch(event)) {
                    handleTouchEvent(event, touchSlop, longPressTimeout)
                }
                true
            }
        }
    }

    private fun shouldSuppressReplayTouch(event: MotionEvent): Boolean {
        val suppress = synchronized(this) {
            val now = SystemClock.uptimeMillis()
            syntheticReplayInFlight || now <= syntheticReplaySuppressUntilMs
        }
        if (suppress && (
                event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                )
        ) {
            synchronized(this) { isTracking = false }
        }
        return suppress
    }

    private fun buildParams(
        context: Context,
        touchable: Boolean
    ): WindowManager.LayoutParams {
        return WindowManager.LayoutParams().apply {
            type = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else -> {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
            }
            val touchFlag = if (touchable) {
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            } else {
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                touchFlag
            format = PixelFormat.TRANSLUCENT
            val displaySize = realDisplaySize(context)
            width = displaySize.x
            height = overlayHeightForParamsLocked(touchable, displaySize.y)
            gravity = Gravity.TOP or Gravity.START
        }
    }

    private fun handleTouchEvent(
        event: MotionEvent,
        touchSlop: Float,
        longPressTimeout: Long
    ) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val point = clampRawPoint(event)
                startX = point.x
                startY = point.y
                endX = point.x
                endY = point.y
                downAtMs = System.currentTimeMillis()
                isTracking = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isTracking) return
                val point = clampRawPoint(event)
                endX = point.x
                endY = point.y
            }
            MotionEvent.ACTION_UP -> {
                if (!isTracking) return
                val point = clampRawPoint(event)
                endX = point.x
                endY = point.y
                val finishedAtMs = System.currentTimeMillis()
                val durationMs = finishedAtMs - downAtMs
                val distancePx = distance(startX, startY, endX, endY)
                // Use net displacement (start→end), not peak displacement during move.
                // Peak-based detection misclassifies taps where the finger briefly drifts.
                val actionName = when {
                    distancePx >= touchSlop -> "swipe"
                    durationMs >= longPressTimeout -> "long_press"
                    else -> "click"
                }
                isTracking = false
                val gesture = ManualOverlayTouchGesture(
                    actionName = actionName,
                    startX = startX,
                    startY = startY,
                    endX = endX,
                    endY = endY,
                    durationMs = durationMs,
                    distancePx = distancePx,
                    direction = directionName(startX, startY, endX, endY).takeIf { actionName == "swipe" },
                    startedAtMs = downAtMs,
                    finishedAtMs = finishedAtMs,
                    displayWidth = currentDisplaySize().x,
                    displayHeight = currentDisplaySize().y
                )
                enqueueGesture(gesture)
            }
            MotionEvent.ACTION_CANCEL -> {
                isTracking = false
            }
        }
    }

    private fun enqueueGesture(gesture: ManualOverlayTouchGesture) {
        val shouldStartWorker = synchronized(this) {
            pendingGestures.addLast(gesture)
            if (isProcessing) {
                false
            } else {
                isProcessing = true
                true
            }
        }
        if (shouldStartWorker) {
            processGestureQueue()
        }
    }

    private fun processGestureQueue() {
        recordScope.launch {
            while (true) {
                val gesture = synchronized(this@ManualTouchRecordLoader) {
                    pendingGestures.pollFirst()
                }
                if (gesture == null) {
                    val shouldContinue = withContext(Dispatchers.Main) {
                        var continueProcessing = false
                        synchronized(this@ManualTouchRecordLoader) {
                            if (pendingGestures.isNotEmpty()) {
                                continueProcessing = true
                            } else {
                                isProcessing = false
                                if (HumanTrajectoryLearningSession.isActive() &&
                                    !HumanTrajectoryLearningSession.isPaused()) {
                                    if (isImeVisibleLocked()) {
                                        lockTouchLocked()
                                        scheduleImeRelockLocked()
                                    } else {
                                        lockTouchLocked()
                                    }
                                } else {
                                    hide()
                                }
                            }
                        }
                        continueProcessing
                    }
                    if (shouldContinue) {
                        continue
                    }
                    return@launch
                }
                val keepRecording = processQueuedGesture(gesture)
                if (!keepRecording) {
                    withContext(Dispatchers.Main) {
                        synchronized(this@ManualTouchRecordLoader) {
                            pendingGestures.clear()
                            isProcessing = false
                            hide()
                        }
                    }
                    return@launch
                }
            }
        }
    }

    private suspend fun processQueuedGesture(gesture: ManualOverlayTouchGesture): Boolean {
        var sessionStillActive = withContext(Dispatchers.Main) {
            synchronized(this@ManualTouchRecordLoader) {
                HumanTrajectoryLearningSession.isActive() &&
                    !HumanTrajectoryLearningSession.isPaused()
            }
        }
        if (!sessionStillActive) return false

        val keyboardBlackBoxGesture = withContext(Dispatchers.Main) {
            synchronized(this@ManualTouchRecordLoader) {
                isKeyboardBlackBoxGestureLocked(gesture)
            }
        }
        var executed = false
        var recorded = false
        var mayOpenIme = false
        runCatching {
            withContext(Dispatchers.Main) {
                synchronized(this@ManualTouchRecordLoader) {
                    if (overlayView?.isAttachedToWindow == true &&
                        HumanTrajectoryLearningSession.isActive() &&
                        !HumanTrajectoryLearningSession.isPaused()) {
                        beginSyntheticReplaySuppressionLocked()
                        unlockTouchLocked()
                    }
                }
            }
            try {
                delay(OVERLAY_UNLOCK_REPLAY_DELAY_MS)
                val replayResult = if (keyboardBlackBoxGesture) {
                    HumanTrajectoryLearningSession.replayOverlayGestureWithoutRecording(gesture)
                } else {
                    HumanTrajectoryLearningSession.recordOverlayGesture(gesture) { mayOpenIme ->
                        withContext(Dispatchers.Main) {
                            synchronized(this@ManualTouchRecordLoader) {
                                if (overlayView?.isAttachedToWindow == true &&
                                    HumanTrajectoryLearningSession.isActive() &&
                                    !HumanTrajectoryLearningSession.isPaused()) {
                                    if (mayOpenIme) {
                                        rememberImeOpenExpectedLocked()
                                    }
                                    lockTouchLocked()
                                }
                            }
                        }
                    }
                }
                executed = replayResult.executed
                recorded = replayResult.recorded
                mayOpenIme = replayResult.mayOpenIme
            } finally {
                withContext(Dispatchers.Main) {
                    synchronized(this@ManualTouchRecordLoader) {
                        endSyntheticReplaySuppressionLocked()
                    }
                }
            }
        }.onFailure { error ->
            OmniLog.w(TAG, "record overlay gesture failed: ${error.message}")
        }

        if (keyboardBlackBoxGesture && executed) {
            // Keyboard taps are a black box: replay them so text changes happen,
            // but let TYPE_VIEW_TEXT_CHANGED merge the final input_text action.
        } else if (executed && recorded) {
            // Keep recording UI static. Per-gesture indicators/status updates add
            // extra overlay input work and can make the control window ANR.
        } else if (executed && !recorded) {
            OmniLog.w(TAG, "manual gesture executed but was not recorded action=${gesture.actionName}")
        }

        sessionStillActive = withContext(Dispatchers.Main) {
            synchronized(this@ManualTouchRecordLoader) {
                val active = HumanTrajectoryLearningSession.isActive() &&
                    !HumanTrajectoryLearningSession.isPaused()
                if (active) {
                    if (mayOpenIme || keyboardBlackBoxGesture) {
                        rememberImeOpenExpectedLocked()
                    }
                    lockTouchLocked()
                    if (!keyboardBlackBoxGesture && gesture.actionName == "click" && executed) {
                        scheduleImeVisibilityProbeLocked(clearWhenMissing = !mayOpenIme)
                    } else if (keyboardBlackBoxGesture && executed) {
                        scheduleImeRelockLocked()
                    }
                }
                active
            }
        }
        if (!sessionStillActive) return false
        return true
    }

    private fun beginSyntheticReplaySuppressionLocked() {
        syntheticReplayInFlight = true
    }

    private fun endSyntheticReplaySuppressionLocked() {
        syntheticReplayInFlight = false
        syntheticReplaySuppressUntilMs =
            SystemClock.uptimeMillis() + OVERLAY_REPLAY_TOUCH_SUPPRESS_AFTER_MS
    }

    private fun isKeyboardBlackBoxGestureLocked(gesture: ManualOverlayTouchGesture): Boolean {
        val displayHeight = currentDisplaySize().y
        if (displayHeight <= 0) return false
        val hasActiveTextInput = HumanTrajectoryLearningSession.hasActiveTextInputAnchor()
        val estimatedTop = estimatedImeTopValueLocked(displayHeight).takeIf { hasActiveTextInput }
        val imeTop = imeTopLocked()
        val keyboardTop = when {
            hasActiveTextInput && imeTop != null && estimatedTop != null ->
                minOf(imeTop, estimatedTop)
            hasActiveTextInput -> estimatedTop
            else -> imeTop
        }
            ?: return false
        if (keyboardTop >= displayHeight) return false
        val gestureY = when (gesture.actionName) {
            "swipe" -> (gesture.startY + gesture.endY) / 2f
            else -> gesture.startY
        }
        return gestureY >= keyboardTop
    }

    private fun scheduleImeVisibilityProbeLocked(clearWhenMissing: Boolean) {
        if (imeVisibilityProbeJob?.isActive == true) return
        scheduleImeGeometryProbeLocked(clearWhenMissing = clearWhenMissing)
        imeVisibilityProbeJob = recordScope.launch {
            delay(IME_VISIBILITY_PROBE_DELAY_MS)
            val deadline = System.currentTimeMillis() + IME_VISIBILITY_PROBE_TIMEOUT_MS
            var appeared = false
            while (System.currentTimeMillis() <= deadline &&
                HumanTrajectoryLearningSession.isActive() &&
                !HumanTrajectoryLearningSession.isPaused()
            ) {
                appeared = withContext(Dispatchers.Main) {
                    synchronized(this@ManualTouchRecordLoader) {
                        scheduleImeGeometryProbeLocked(clearWhenMissing = clearWhenMissing)
                        isImeVisibleLocked()
                    }
                }
                if (appeared) break
                delay(IME_VISIBILITY_PROBE_POLL_MS)
            }

            withContext(Dispatchers.Main) {
                synchronized(this@ManualTouchRecordLoader) {
                    imeVisibilityProbeJob = null
                    if (appeared &&
                        HumanTrajectoryLearningSession.isActive() &&
                        !HumanTrajectoryLearningSession.isPaused()) {
                        lockTouchLocked()
                        scheduleImeRelockLocked()
                    }
                }
            }
        }
    }

    private fun scheduleImeRelockLocked() {
        if (imeRelockJob?.isActive == true) return
        imeRelockJob = recordScope.launch {
            delay(IME_RELOCK_INITIAL_DELAY_MS)
            while (HumanTrajectoryLearningSession.isActive() && !HumanTrajectoryLearningSession.isPaused()) {
                val imeVisible = withContext(Dispatchers.Main) {
                    synchronized(this@ManualTouchRecordLoader) {
                        scheduleImeGeometryProbeLocked(clearWhenMissing = false)
                        val visible = isImeVisibleLocked()
                        if (visible) {
                            lockTouchLocked()
                        }
                        visible
                    }
                }
                if (!imeVisible) break
                delay(IME_RELOCK_POLL_MS)
            }
            withContext(Dispatchers.Main) {
                synchronized(this@ManualTouchRecordLoader) {
                    imeRelockJob = null
                    if (HumanTrajectoryLearningSession.isActive() && !HumanTrajectoryLearningSession.isPaused()) {
                        lockTouchLocked()
                    }
                }
            }
        }
    }

    private fun isImeVisibleLocked(): Boolean = imeTopLocked() != null

    private fun overlayHeightForParamsLocked(touchable: Boolean, fullHeight: Int): Int {
        val touchableBottom = touchableBottomLocked(fullHeight)
        if (!touchable) return fullHeight
        return imeTouchableTopLocked(fullHeight)?.coerceIn(1, touchableBottom)
            ?: if (shouldKeepImePassthroughWithoutTopLocked()) 1 else touchableBottom
    }

    private fun imeTouchableTopLocked(displayHeight: Int): Int? {
        val imeTop = imeTopLocked()
        val estimatedTop = estimatedImeTopValueLocked(displayHeight)
            .takeIf { HumanTrajectoryLearningSession.hasActiveTextInputAnchor() }
        return when {
            imeTop != null && estimatedTop != null -> minOf(imeTop, estimatedTop)
            estimatedTop != null -> estimatedTop
            else -> imeTop
        }
    }

    private fun shouldKeepImePassthroughWithoutTopLocked(): Boolean {
        val now = SystemClock.uptimeMillis()
        return now <= imeOpenExpectedUntilMs || imeGeometryRecentlySeenLocked()
    }

    private fun imeTopLocked(): Int? {
        val displayHeight = currentDisplaySize().y.takeIf { it > 0 } ?: return null
        // Keep IME detection cheap on the main thread. Filtered App XML probes
        // run asynchronously and only update the cached geometry.
        rootImeTopLocked(displayHeight)?.let { top ->
            rememberImeGeometrySeenLocked()
            return rememberReliableImeTopLocked(top)
        }
        return cachedReliableImeTopLocked(displayHeight) ?: estimatedImeTopLocked(displayHeight)
    }

    private fun rootImeTopLocked(displayHeight: Int): Int? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = overlayView?.rootWindowInsets
            if (insets != null && insets.isVisible(WindowInsets.Type.ime())) {
                val bottomInset = insets.getInsets(WindowInsets.Type.ime()).bottom
                if (bottomInset > 0) {
                    return trustedImeTopLocked(displayHeight - bottomInset, displayHeight)
                }
            }
        }
        return null
    }

    private fun trustedImeTopLocked(top: Int, displayHeight: Int): Int? {
        val minTop = (displayHeight * IME_RELIABLE_TOP_MIN_RATIO).toInt().coerceAtLeast(1)
        val maxTop = (displayHeight * IME_RELIABLE_TOP_MAX_RATIO).toInt()
            .coerceIn(minTop, displayHeight - 1)
        val clamped = top.coerceIn(1, displayHeight - 1)
        return clamped.takeIf { it in minTop..maxTop }
    }

    private fun scheduleImeGeometryProbeLocked(clearWhenMissing: Boolean) {
        if (imeGeometryProbeJob?.isActive == true) {
            if (clearWhenMissing) imeProbeClearWhenMissing = true
            return
        }
        imeProbeClearWhenMissing = clearWhenMissing
        val fullHeight = currentDisplaySize().y
        val fullWidth = currentDisplaySize().x
        if (fullHeight <= 0 || fullWidth <= 0) return
        imeGeometryProbeJob = recordScope.launch {
            val probedTop = HumanTrajectoryLearningSession.probeManualImeOverlayTop(
                displayHeight = fullHeight,
                displayWidth = fullWidth
            )
            withContext(Dispatchers.Main) {
                synchronized(this@ManualTouchRecordLoader) {
                    imeGeometryProbeJob = null
                    val shouldClearWhenMissing = clearWhenMissing || imeProbeClearWhenMissing
                    imeProbeClearWhenMissing = false
                    val active = HumanTrajectoryLearningSession.isActive() &&
                        !HumanTrajectoryLearningSession.isPaused()
                    if (!active) return@synchronized
                    val currentHeight = currentDisplaySize().y
                    val top = probedTop?.let { trustedImeTopLocked(it, currentHeight) }
                    if (top != null) {
                        rememberImeGeometrySeenLocked()
                        rememberReliableImeTopLocked(top)
                        lockTouchLocked()
                        scheduleImeRelockLocked()
                    } else if (shouldClearWhenMissing && SystemClock.uptimeMillis() > imeOpenExpectedUntilMs) {
                        val insetsVisible = rootImeTopLocked(currentHeight) != null
                        if (!insetsVisible) {
                            clearImeGeometryLocked()
                            lockTouchLocked()
                        }
                    }
                }
            }
        }
    }

    private fun touchableBottomLocked(fullHeight: Int): Int {
        if (fullHeight <= 1) return fullHeight
        val navigationBottomInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            overlayView?.rootWindowInsets
                ?.getInsets(WindowInsets.Type.navigationBars())
                ?.bottom
                ?: 0
        } else {
            0
        }.coerceIn(0, fullHeight - 1)
        return (fullHeight - navigationBottomInset).coerceIn(1, fullHeight)
    }

    private fun rememberReliableImeTopLocked(top: Int): Int {
        lastReliableImeTop = top
        lastReliableImeTopAtMs = SystemClock.uptimeMillis()
        return top
    }

    private fun cachedReliableImeTopLocked(displayHeight: Int): Int? {
        if (!imeGeometryRecentlySeenLocked() && SystemClock.uptimeMillis() > imeOpenExpectedUntilMs) {
            return null
        }
        val top = lastReliableImeTop ?: return null
        val fresh = SystemClock.uptimeMillis() - lastReliableImeTopAtMs <= IME_TOP_CACHE_TTL_MS
        return top.takeIf { fresh }?.coerceIn(1, displayHeight - 1)
    }

    private fun rememberImeGeometrySeenLocked() {
        lastImeGeometrySeenAtMs = SystemClock.uptimeMillis()
    }

    private fun imeGeometryRecentlySeenLocked(): Boolean {
        val seenAt = lastImeGeometrySeenAtMs
        return seenAt > 0 && SystemClock.uptimeMillis() - seenAt <= IME_GEOMETRY_SEEN_TTL_MS
    }

    private fun clearImeGeometryLocked() {
        lastReliableImeTop = null
        lastReliableImeTopAtMs = 0L
        lastImeGeometrySeenAtMs = 0L
    }

    private fun rememberImeOpenExpectedLocked() {
        imeOpenExpectedUntilMs = SystemClock.uptimeMillis() + IME_OPEN_EXPECTED_TTL_MS
    }

    private fun estimatedImeTopLocked(displayHeight: Int): Int? {
        if (!imeGeometryRecentlySeenLocked() && SystemClock.uptimeMillis() > imeOpenExpectedUntilMs) {
            return null
        }
        return estimatedImeTopValueLocked(displayHeight)
    }

    private fun estimatedImeTopValueLocked(displayHeight: Int): Int? {
        return trustedImeTopLocked((displayHeight * IME_ESTIMATED_TOP_RATIO).toInt(), displayHeight)
    }

    private fun lockTouchLocked() {
        updateTouchableLocked(touchable = true)
    }

    private fun unlockTouchLocked() {
        updateTouchableLocked(touchable = false)
    }

    private fun updateTouchableLocked(touchable: Boolean) {
        val view = overlayView ?: return
        val manager = windowManager ?: return
        val context = view.context ?: return
        if (!view.isAttachedToWindow) return
        val params = buildParams(context, touchable)
        if (currentTouchable == touchable && currentOverlayHeight == params.height) return
        overlayParams = params
        runCatching { manager.updateViewLayout(view, params) }
            .onSuccess {
                currentTouchable = touchable
                currentOverlayHeight = params.height
                OmniLog.d(
                    TAG,
                    "manual touch overlay updated touchable=$touchable height=${params.height}/$displayHeight"
                )
            }
            .onFailure { OmniLog.w(TAG, "update touchable=$touchable failed: ${it.message}") }
    }

    private fun clampRawPoint(event: MotionEvent): PointF {
        val displaySize = currentDisplaySize()
        val maxX = (displaySize.x - 1).coerceAtLeast(0).toFloat()
        val maxY = (displaySize.y - 1).coerceAtLeast(0).toFloat()
        return PointF(
            event.rawX.coerceIn(0f, maxX),
            event.rawY.coerceIn(0f, maxY)
        )
    }

    private fun currentDisplaySize(): Point {
        val width = displayWidth
        val height = displayHeight
        if (width > 0 && height > 0) return Point(width, height)
        return realDisplaySize(overlayView?.context ?: UIKit.appContext)
    }

    private fun realDisplaySize(context: Context?): Point {
        val safeContext = context ?: UIKit.appContext
        if (safeContext == null) return Point(0, 0)
        return runCatching {
            val manager = safeContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val screenSize = Point()
            @Suppress("DEPRECATION")
            manager.defaultDisplay.getRealSize(screenSize)
            screenSize
        }.getOrDefault(Point(0, 0))
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return sqrt(dx * dx + dy * dy)
    }

    private fun directionName(x1: Float, y1: Float, x2: Float, y2: Float): String {
        val dx = x2 - x1
        val dy = y2 - y1
        return if (abs(dx) > abs(dy)) {
            if (dx > 0) "right" else "left"
        } else {
            if (dy > 0) "down" else "up"
        }
    }
}
