package cn.com.omnimind.uikit.loader

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import cn.com.omnimind.androidgui.AndroidGuiEnvironment
import cn.com.omnimind.assists.HumanTrajectoryLearningSession
import cn.com.omnimind.assists.ManualInputTarget
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.baselib.util.dpToPx
import cn.com.omnimind.uikit.UIKit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object ManualRecordingControlOverlay {
    private const val TAG = "ManualRecordingControlOverlay"

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var state: State = State.READY
    private val recordingControlScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastOverlayX: Int? = null
    private var lastOverlayY: Int? = null
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartX = 0
    private var dragStartY = 0
    private var dragging = false
    private var transientStatusToken = 0
    private var manualActionDialogShowing = false
    private var captureStateCallback: (suspend () -> Map<String, Any?>)? = null
    private var sessionRunId: String? = null

    enum class State {
        PREPARING,
        READY,
        RECORDING,
        PAUSED
    }

    fun show(
        context: Context? = UIKit.appContext,
        runId: String,
        state: State = State.READY,
        onCaptureState: (suspend () -> Map<String, Any?>)? = null
    ): Boolean {
        require(runId.isNotBlank()) { "manual_recording_run_id_required" }
        this.state = state
        val appContext = context?.applicationContext ?: UIKit.appContext
        val safeContext = appContext ?: return false
        return synchronized(this) {
            if (overlayView?.isAttachedToWindow == true) {
                sessionRunId = runId
                captureStateCallback = onCaptureState
                bindState(overlayView, state)
                return@synchronized true
            }
            dismissLocked()
            sessionRunId = runId
            captureStateCallback = onCaptureState
            val shown = tryShow(safeContext, state)
            if (!shown) {
                sessionRunId = null
                captureStateCallback = null
            }
            shown
        }
    }

    /**
     * Shows the overlay in active recording state.
     */
    fun markRecording() {
        val context = synchronized(this) {
            state = State.RECORDING
            bindState(overlayView, State.RECORDING)
            overlayView?.context
        }
        val shown = ManualTouchRecordLoader.show(context ?: UIKit.appContext)
        if (shown) {
            keepControlsAboveTouchRecorder()
        } else {
            recordingControlScope.launch {
                HumanTrajectoryLearningSession.pauseActive()
                withContext(Dispatchers.Main) {
                    markPaused()
                    showTransientStatus("开启悬浮窗权限", 1400L)
                }
            }
        }
    }

    /**
     * Shows the overlay in ready state before event capture starts.
     */
    fun markReady() {
        synchronized(this) {
            state = State.READY
            bindState(overlayView, State.READY)
        }
        ManualTouchRecordLoader.hide()
    }

    /**
     * Shows the overlay in paused state.
     */
    fun markPaused() {
        synchronized(this) {
            state = State.PAUSED
            bindState(overlayView, State.PAUSED)
        }
        ManualTouchRecordLoader.hide()
    }

    fun dismiss() {
        val runId = synchronized(this) { sessionRunId }
        synchronized(this) {
            dismissLocked()
        }
        // Cancel any active session that was never explicitly completed.
        // This covers force-dismissal paths (back press, system overlay kill, etc.)
        // where the Finish button was never tapped.
        if (runId != null) {
            recordingControlScope.launch {
                HumanTrajectoryLearningSession.cancelActive(
                    expectedRunId = runId,
                    message = "录制窗口关闭，轨迹学习已取消"
                )
            }
        }
    }

    fun cancelRecording(message: String = "人工轨迹学习已取消") {
        val runId = synchronized(this) { sessionRunId }
        synchronized(this) {
            dismissLocked()
        }
        recordingControlScope.launch {
            val updated = runCatching {
                runId != null && HumanTrajectoryLearningSession.cancelActive(
                    expectedRunId = runId,
                    message = message
                )
            }.getOrElse { error ->
                OmniLog.e(TAG, "cancel manual recording failed: ${error.message}", error)
                false
            }
            if (!updated) {
                OmniLog.w(TAG, "cancel requested without active manual recording session")
            }
        }
    }

    private fun dismissLocked() {
        ManualTouchRecordLoader.hide()
        val view = overlayView
        val manager = windowManager
        val params = overlayParams
        if (params != null) {
            lastOverlayX = params.x
            lastOverlayY = params.y
        }
        overlayView = null
        windowManager = null
        overlayParams = null
        captureStateCallback = null
        sessionRunId = null
        if (view != null && manager != null && view.isAttachedToWindow) {
            runCatching { manager.removeView(view) }
                .onFailure { OmniLog.w(TAG, "dismiss failed: ${it.message}") }
        }
    }

    fun ensureOnTop() {
        synchronized(this) {
            keepControlsAboveTouchRecorderLocked()
        }
    }

    fun showTransientStatus(message: String, durationMs: Long = 800L) {
        val token = synchronized(this) {
            transientStatusToken += 1
            transientStatusToken
        }
        recordingControlScope.launch {
            withContext(Dispatchers.Main) {
                setTitleText(message)
            }
            delay(durationMs)
            withContext(Dispatchers.Main) {
                synchronized(this@ManualRecordingControlOverlay) {
                    if (transientStatusToken == token) {
                        bindState(overlayView, state)
                    }
                }
            }
        }
    }

    fun offerInput(target: ManualInputTarget) {
        if (target.password) {
            showTransientStatus("密码输入不录制", 1_400L)
            return
        }
        recordingControlScope.launch {
            if (!ManualTouchRecordLoader.awaitIdle()) return@launch
            withContext(Dispatchers.Main) {
                val context = synchronized(this@ManualRecordingControlOverlay) {
                    if (state != State.RECORDING || manualActionDialogShowing) return@withContext
                    overlayView?.context ?: UIKit.appContext
                } ?: return@withContext
                if (!ManualTouchRecordLoader.prepareForManualAction()) return@withContext
                val inputAvailable = synchronized(this@ManualRecordingControlOverlay) {
                    if (state != State.RECORDING || manualActionDialogShowing) {
                        false
                    } else {
                        manualActionDialogShowing = true
                        true
                    }
                }
                if (!inputAvailable) {
                    return@withContext
                }
                showManualInputTextDialog(context, target)
            }
        }
    }

    private fun tryShow(
        context: Context,
        state: State
    ): Boolean {
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = buildView(context)
        bindState(view, state)
        val screenWidth = context.resources.displayMetrics.widthPixels
        val params = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            format = android.graphics.PixelFormat.TRANSLUCENT
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
            x = lastOverlayX ?: ((screenWidth - 140.dpToPx()) / 2).coerceAtLeast(8.dpToPx())
            y = lastOverlayY ?: 56.dpToPx()
        }
        attachDragHandler(view, manager, params)
        return runCatching {
            manager.addView(view, params)
            windowManager = manager
            overlayView = view
            overlayParams = params
            OmniLog.d(
                TAG,
                "manual recording control overlay shown type=application state=$state"
            )
            true
        }.getOrElse { error ->
            OmniLog.e(
                TAG,
                "show failed type=application: ${error.message}",
                error
            )
            false
        }
    }

    private fun keepControlsAboveTouchRecorder() {
        synchronized(this) {
            keepControlsAboveTouchRecorderLocked()
        }
    }

    private fun keepControlsAboveTouchRecorderLocked() {
        val view = overlayView ?: return
        val manager = windowManager ?: return
        val params = overlayParams ?: return
        if (!view.isAttachedToWindow) return
        runCatching {
            manager.removeViewImmediate(view)
            manager.addView(view, params)
        }.recoverCatching {
            if (view.isAttachedToWindow) {
                manager.updateViewLayout(view, params)
            } else {
                manager.addView(view, params)
            }
        }.onFailure { error ->
            OmniLog.w(TAG, "keep controls above touch recorder failed: ${error.message}")
        }
    }

    private fun buildView(context: Context): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12.dpToPx().toFloat()
                setColor(Color.rgb(28, 30, 36))
                setStroke(1.dpToPx(), Color.argb(60, 255, 255, 255))
            }
            elevation = 6.dpToPx().toFloat()
        }
        val title = TextView(context).apply {
            tag = "manual_recording_title"
            text = "录制"
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            setPadding(0, 0, 2.dpToPx(), 0)
        }
        val pauseButton = TextView(context).apply {
            tag = "manual_recording_pause_action"
            text = "暂停"
            contentDescription = "暂停手动录制"
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(7.dpToPx(), 4.dpToPx(), 7.dpToPx(), 4.dpToPx())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 9.dpToPx().toFloat()
                setColor(Color.rgb(58, 64, 78))
            }
            setOnClickListener {
                if (synchronized(this@ManualRecordingControlOverlay) { manualActionDialogShowing }) {
                    showTransientStatus("动作处理中", 900L)
                    return@setOnClickListener
                }
                val shouldResume = when (ManualRecordingControlOverlay.state) {
                    State.PREPARING -> return@setOnClickListener
                    State.READY -> true
                    State.RECORDING -> false
                    State.PAUSED -> true
                }
                isEnabled = false
                recordingControlScope.launch {
                    val updated = if (shouldResume) {
                        HumanTrajectoryLearningSession.resumeActive()
                    } else {
                        ManualTouchRecordLoader.beginFinishing()
                        if (ManualTouchRecordLoader.awaitIdle()) {
                            HumanTrajectoryLearningSession.pauseActive()
                        } else {
                            false
                        }
                    }
                    withContext(Dispatchers.Main) {
                        isEnabled = true
                        if (!updated) {
                            if (!shouldResume) {
                                markRecording()
                                showTransientStatus("动作尚未保存，暂停失败", 1200L)
                            }
                            return@withContext
                        }
                        if (shouldResume) {
                            markRecording()
                        } else {
                            markPaused()
                        }
                    }
                }
            }
        }
        val manualActionButton = TextView(context).apply {
            tag = "manual_recording_manual_action"
            text = "动作"
            contentDescription = "手动补录 input_text、press_key 或 wait"
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(7.dpToPx(), 4.dpToPx(), 7.dpToPx(), 4.dpToPx())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 9.dpToPx().toFloat()
                setColor(Color.rgb(74, 66, 122))
            }
            setOnClickListener {
                showManualActionDialog(context)
            }
        }
        val cancelButton = TextView(context).apply {
            tag = "manual_recording_cancel_action"
            text = "取消"
            contentDescription = "取消手动录制"
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(7.dpToPx(), 4.dpToPx(), 7.dpToPx(), 4.dpToPx())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 9.dpToPx().toFloat()
                setColor(Color.rgb(92, 56, 56))
            }
            setOnClickListener {
                isEnabled = false
                text = "取消中"
                this@ManualRecordingControlOverlay.cancelRecording("人工轨迹学习已取消")
            }
        }
        val finishButton = TextView(context).apply {
            tag = "manual_recording_finish_action"
            text = "完成"
            contentDescription = "结束手动录制"
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(7.dpToPx(), 4.dpToPx(), 7.dpToPx(), 4.dpToPx())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 9.dpToPx().toFloat()
                setColor(Color.rgb(31, 111, 235))
            }
            setOnClickListener {
                if (synchronized(this@ManualRecordingControlOverlay) { manualActionDialogShowing }) {
                    showTransientStatus("动作处理中", 900L)
                    return@setOnClickListener
                }
                isEnabled = false
                text = "保存中"
                val runId = synchronized(this@ManualRecordingControlOverlay) { sessionRunId }
                ManualTouchRecordLoader.beginFinishing()
                recordingControlScope.launch {
                    val drained = ManualTouchRecordLoader.awaitIdle()
                    if (!drained) {
                        OmniLog.w(TAG, "finishing manual recording with undrained touch work")
                    }
                    val updated = runCatching {
                        runId != null && HumanTrajectoryLearningSession.completeActive(runId)
                    }.getOrElse { error ->
                        OmniLog.e(TAG, "finish manual recording failed: ${error.message}", error)
                        false
                    }
                    if (!updated) {
                        OmniLog.w(TAG, "finish clicked without active manual recording session")
                    }
                    withContext(Dispatchers.Main) {
                        synchronized(this@ManualRecordingControlOverlay) {
                            dismissLocked()
                        }
                    }
                }
            }
        }
        container.addView(
            title,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        container.addView(
            pauseButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 5.dpToPx()
            }
        )
        container.addView(
            manualActionButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 5.dpToPx()
            }
        )
        container.addView(
            cancelButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 5.dpToPx()
            }
        )
        container.addView(
            finishButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 5.dpToPx()
            }
        )
        return container
    }

    private fun showManualActionDialog(context: Context) {
        val inputTarget = runCatching {
            kotlinx.coroutines.runBlocking {
                AndroidGuiEnvironment(context).inputTarget()?.let {
                    ManualInputTarget(
                        description = it.description,
                        x = it.x,
                        y = it.y,
                        nodeResourceId = it.nodeResourceId.takeIf(String::isNotBlank),
                        password = it.password,
                    )
                }
            }
        }.getOrNull()
        val canShow = synchronized(this) {
            if (manualActionDialogShowing) return
            if (state != State.RECORDING) return@synchronized false
            manualActionDialogShowing = true
            true
        }
        if (!canShow) {
            showTransientStatus("先开始录制", 900L)
            return
        }
        if (!ManualTouchRecordLoader.prepareForManualAction()) {
            synchronized(this) {
                manualActionDialogShowing = false
            }
            showTransientStatus("稍后再试", 900L)
            return
        }
        val labels = arrayOf(
            "输入文字",
            "按回车",
            "按返回",
            "回到桌面",
            "等待"
        )
        val dialog = AlertDialog.Builder(context)
            .setTitle("补录动作")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> when {
                        inputTarget == null -> finishManualActionDialog("请先点击输入框")
                        inputTarget.password -> finishManualActionDialog("密码输入不录制")
                        else -> showManualInputTextDialog(context, inputTarget)
                    }
                    1 -> executeManualPressKey("enter")
                    2 -> executeManualPressKey("back")
                    3 -> executeManualPressKey("home")
                    4 -> showManualWaitDialog(context)
                    else -> finishManualActionDialog()
                }
            }
            .setNegativeButton("取消") { _, _ ->
                finishManualActionDialog()
            }
            .create()
        dialog.setOnCancelListener {
            finishManualActionDialog()
        }
        if (!showOverlayDialog(dialog)) {
            finishManualActionDialog("补录窗口失败")
        }
    }

    private fun showManualInputTextDialog(
        context: Context,
        inputTarget: ManualInputTarget?,
        draft: String = "",
        errorMessage: String? = null,
    ) {
        val input = EditText(context).apply {
            hint = errorMessage ?: "输入要写入目标输入框的文本"
            minLines = 1
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(false)
            setText(draft)
            setSelection(text?.length ?: 0)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(inputTarget?.description?.let { "输入：$it" } ?: "输入文字")
            .setView(input)
            .setPositiveButton("输入", null)
            .setNeutralButton("输入并回车", null)
            .setNegativeButton(if (inputTarget == null) "取消" else "仅保留点击") { _, _ ->
                finishManualActionDialog()
            }
            .create()
        dialog.setOnShowListener {
            val submit: (Boolean) -> Unit = submit@{ pressEnter ->
                val text = input.text?.toString().orEmpty()
                if (text.isEmpty()) {
                    input.error = "请输入文本"
                    return@submit
                }
                dialog.dismiss()
                executeManualInputText(context, text, inputTarget, pressEnter)
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { submit(false) }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { submit(true) }
            input.requestFocus()
            dialog.window?.apply {
                setGravity(Gravity.BOTTOM)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                )
            }
            input.post {
                val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as? InputMethodManager
                inputMethodManager?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        dialog.setOnCancelListener {
            finishManualActionDialog()
        }
        if (!showOverlayDialog(dialog)) {
            finishManualActionDialog("补录窗口失败")
        }
    }

    private fun showManualWaitDialog(context: Context) {
        val input = EditText(context).apply {
            hint = "等待秒数（1-60）"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle("wait")
            .setView(input)
            .setPositiveButton("执行并记录", null)
            .setNegativeButton("取消") { _, _ ->
                finishManualActionDialog()
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val seconds = input.text?.toString()?.trim()?.toLongOrNull()
                if (seconds == null || seconds !in 1L..60L) {
                    input.error = "请输入 1-60 秒"
                    return@setOnClickListener
                }
                dialog.dismiss()
                executeManualWait(seconds * 1_000L)
            }
        }
        dialog.setOnCancelListener {
            finishManualActionDialog()
        }
        if (!showOverlayDialog(dialog)) {
            finishManualActionDialog("补录窗口失败")
        }
    }

    private fun executeManualInputText(
        context: Context,
        text: String,
        inputTarget: ManualInputTarget?,
        pressEnter: Boolean,
    ) {
        showTransientStatus("输入中", 600L)
        if (!ManualTouchRecordLoader.blockTouches()) {
            finishManualActionDialog("补录失败")
            return
        }
        recordingControlScope.launch {
            val recorded = runCatching {
                HumanTrajectoryLearningSession.recordManualInputText(text, inputTarget)
            }.getOrElse { error ->
                OmniLog.e(TAG, "manual input_text action failed: ${error.message}", error)
                false
            }
            val enterRecorded = if (recorded && pressEnter) {
                runCatching { HumanTrajectoryLearningSession.recordManualPressKey("enter") }
                    .getOrElse { error ->
                        OmniLog.e(TAG, "manual enter action failed: ${error.message}", error)
                        false
                    }
            } else {
                !pressEnter
            }
            withContext(Dispatchers.Main) {
                when {
                    !recorded && ManualTouchRecordLoader.prepareForManualAction() -> {
                        showManualInputTextDialog(
                            context = context,
                            inputTarget = inputTarget,
                            draft = text,
                            errorMessage = "输入失败，内容已保留，请重试",
                        )
                    }
                    !recorded -> finishManualActionDialog("输入失败")
                    !enterRecorded -> finishManualActionDialog("文字已输入，回车失败")
                    pressEnter -> finishManualActionDialog("已输入并回车")
                    else -> finishManualActionDialog("已输入")
                }
            }
        }
    }

    private fun executeManualPressKey(key: String) {
        showTransientStatus("补录中", 600L)
        if (!ManualTouchRecordLoader.blockTouches()) {
            finishManualActionDialog("补录失败")
            return
        }
        recordingControlScope.launch {
            val recorded = runCatching {
                HumanTrajectoryLearningSession.recordManualPressKey(key)
            }.getOrElse { error ->
                OmniLog.e(TAG, "manual press_key action failed: ${error.message}", error)
                false
            }
            withContext(Dispatchers.Main) {
                finishManualActionDialog(if (recorded) "已补录 press_key" else "补录失败")
            }
        }
    }

    private fun executeManualWait(durationMs: Long) {
        val seconds = durationMs / 1_000L
        showTransientStatus("等待 $seconds 秒", durationMs + 400L)
        if (!ManualTouchRecordLoader.blockTouches()) {
            finishManualActionDialog("等待失败")
            return
        }
        recordingControlScope.launch {
            val recorded = runCatching {
                HumanTrajectoryLearningSession.recordManualWait(durationMs)
            }.getOrElse { error ->
                OmniLog.e(TAG, "manual wait action failed: ${error.message}", error)
                false
            }
            withContext(Dispatchers.Main) {
                finishManualActionDialog(if (recorded) "已记录 wait ${seconds}s" else "等待失败")
            }
        }
    }

    private fun finishManualActionDialog(message: String? = null) {
        synchronized(this) {
            manualActionDialogShowing = false
        }
        if (HumanTrajectoryLearningSession.isActive() && !HumanTrajectoryLearningSession.isPaused()) {
            markRecording()
        } else {
            markPaused()
        }
        if (!message.isNullOrBlank()) {
            showTransientStatus(message, 1_000L)
        }
    }

    private fun showOverlayDialog(dialog: AlertDialog): Boolean {
        return runCatching {
            applyOverlayWindowType(dialog)
            dialog.show()
            applyOverlayWindowType(dialog)
            true
        }.getOrElse { error ->
            OmniLog.e(TAG, "show manual action dialog failed: ${error.message}", error)
            false
        }
    }

    private fun applyOverlayWindowType(dialog: AlertDialog) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        dialog.window?.setType(type)
    }

    private fun attachDragHandler(
        view: View,
        manager: WindowManager,
        params: WindowManager.LayoutParams
    ) {
        val touchListener = View.OnTouchListener { target, event ->
            handleDragTouch(target, event, manager, params)
        }
        view.setOnTouchListener(touchListener)
        (view as? LinearLayout)?.let { container ->
            (0 until container.childCount)
                .map { container.getChildAt(it) }
                .firstOrNull { it.tag == "manual_recording_title" }
                ?.setOnTouchListener(touchListener)
        }
    }

    private fun handleDragTouch(
        target: View,
        event: MotionEvent,
        manager: WindowManager,
        params: WindowManager.LayoutParams
    ): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                dragStartX = params.x
                dragStartY = params.y
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - dragStartRawX
                val dy = event.rawY - dragStartRawY
                if (!dragging && (abs(dx) > 6.dpToPx() || abs(dy) > 6.dpToPx())) {
                    dragging = true
                    beginDragRecordingSuppression()
                }
                if (dragging) {
                    val layoutView = overlayView ?: target
                    val display = target.context.resources.displayMetrics
                    val maxX = max(0, display.widthPixels - layoutView.width)
                    val maxY = max(0, display.heightPixels - layoutView.height)
                    params.x = min(max(0, dragStartX + dx.toInt()), maxX)
                    params.y = min(max(8.dpToPx(), dragStartY + dy.toInt()), maxY)
                    lastOverlayX = params.x
                    lastOverlayY = params.y
                    runCatching {
                        if (layoutView.isAttachedToWindow) {
                            manager.updateViewLayout(layoutView, params)
                        }
                    }.onFailure { OmniLog.w(TAG, "drag update failed: ${it.message}") }
                }
                return true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    endDragRecordingSuppression()
                }
                return true
            }
        }
        return true
    }

    private fun beginDragRecordingSuppression() {
        // Keep drag handling UI-only. Session state changes can wait for replay/XML
        // work, and doing that inside ACTION_MOVE can trigger an overlay Input ANR.
    }

    private fun endDragRecordingSuppression() {
    }

    private fun bindState(view: View?, state: State) {
        val container = view as? LinearLayout ?: return
        val title = findChildByTag(container, "manual_recording_title") as? TextView
        val button = (0 until container.childCount)
            .map { container.getChildAt(it) }
            .firstOrNull { it.tag == "manual_recording_finish_action" } as? TextView
        val pauseButton = (0 until container.childCount)
            .map { container.getChildAt(it) }
            .firstOrNull { it.tag == "manual_recording_pause_action" } as? TextView
        val manualActionButton = (0 until container.childCount)
            .map { container.getChildAt(it) }
            .firstOrNull { it.tag == "manual_recording_manual_action" } as? TextView
        val cancelButton = (0 until container.childCount)
            .map { container.getChildAt(it) }
            .firstOrNull { it.tag == "manual_recording_cancel_action" } as? TextView
        title?.text = when (state) {
            State.PREPARING -> "准备"
            State.READY -> "待机"
            State.RECORDING -> "录制"
            State.PAUSED -> "暂停"
        }
        pauseButton?.apply {
            visibility = if (state == State.PREPARING) View.GONE else View.VISIBLE
            isEnabled = state != State.PREPARING
            text = when (state) {
                State.PREPARING -> "暂停"
                State.READY -> "开始"
                State.RECORDING -> "暂停"
                State.PAUSED -> "继续"
            }
            contentDescription = when (state) {
                State.PREPARING -> "暂停手动录制"
                State.READY -> "开始手动录制"
                State.RECORDING -> "暂停手动录制"
                State.PAUSED -> "继续手动录制"
            }
        }
        manualActionButton?.apply {
            visibility = if (state == State.RECORDING) View.VISIBLE else View.GONE
            isEnabled = state == State.RECORDING
            text = "动作"
            contentDescription = "手动补录 input_text 或 press_key"
        }
        cancelButton?.apply {
            visibility = View.VISIBLE
            isEnabled = true
            text = "取消"
            contentDescription = "取消手动录制"
        }
        button?.apply {
            visibility = if (state == State.PREPARING) View.GONE else View.VISIBLE
            isEnabled = state != State.PREPARING
            text = "完成"
            contentDescription = "完成并保存手动录制"
        }
    }

    private fun setTitleText(message: String) {
        val container = overlayView as? LinearLayout ?: return
        val title = findChildByTag(container, "manual_recording_title") as? TextView ?: return
        title.text = message
    }

    private fun findChildByTag(container: LinearLayout, tag: String): View? {
        return (0 until container.childCount)
            .map { container.getChildAt(it) }
            .firstOrNull { it.tag == tag }
    }
}
