package cn.com.omnimind.bot.omniflow.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import cn.com.omnimind.baselib.util.OmniLog
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal object ExecutionOverlay {
    private const val TAG = "OmniFlowOverlay"
    private var activeSession: Session? = null

    fun show(
        context: Context,
        goal: String,
        initialPhase: ExecutionPhase,
        onStop: () -> Unit,
    ): Session? = synchronized(this) {
        activeSession?.dismissLocked()
        val appContext = context.applicationContext
        if (!Settings.canDrawOverlays(appContext)) return@synchronized null
        val manager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val session = Session(manager, onStop, initialPhase)
        val view = buildView(appContext, goal, session)
        val params = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            width = appContext.resources.displayMetrics.widthPixels - appContext.dp(32)
            height = WindowManager.LayoutParams.WRAP_CONTENT
            y = appContext.dp(32)
        }
        runCatching {
            manager.addView(view, params)
            session.attach(view)
            activeSession = session
            session
        }.onFailure { error ->
            OmniLog.w(TAG, "show GUI controls failed: ${error.message}")
        }.getOrNull()
    }

    private fun buildView(context: Context, goal: String, session: Session): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(14), context.dp(10), context.dp(12), context.dp(10))
            elevation = context.dp(8).toFloat()
            background = rounded(Color.WHITE, context.dp(18).toFloat(), "#80A9FF")
        }
        val title = TextView(context).apply {
            text = goal.trim().ifBlank { "视觉任务执行中" }.take(64)
            setTextColor(Color.parseColor("#202F51"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, context.dp(8), 0, 0)
        }
        val status = TextView(context).apply {
            text = session.statusLabel
            setTextColor(Color.parseColor("#5F6875"))
            textSize = 11f
        }
        val pause = action(context, "接管", "#F3F4F5", "#202F51") {
            session.togglePaused()
        }
        val stop = action(context, "停止", "#FFF0F0", "#C73636") {
            session.requestStop()
        }
        row.addView(
            status,
            LinearLayout.LayoutParams(0, WindowManager.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(pause)
        row.addView(
            stop,
            LinearLayout.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = context.dp(8) },
        )
        container.addView(title)
        container.addView(row)
        session.bind(title, status, pause, stop)
        return container
    }

    private fun action(
        context: Context,
        label: String,
        backgroundColor: String,
        textColor: String,
        onClick: () -> Unit,
    ): TextView = TextView(context).apply {
        text = label
        setTextColor(Color.parseColor(textColor))
        textSize = 12f
        gravity = Gravity.CENTER
        minWidth = context.dp(62)
        setPadding(context.dp(12), context.dp(7), context.dp(12), context.dp(7))
        background = rounded(Color.parseColor(backgroundColor), context.dp(15).toFloat())
        setOnClickListener { onClick() }
    }

    private fun rounded(color: Int, radius: Float, strokeColor: String? = null) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            strokeColor?.let { setStroke(1, Color.parseColor(it)) }
        }

    private fun Context.dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    class Session internal constructor(
        private val manager: WindowManager,
        private val onStop: () -> Unit,
        initialPhase: ExecutionPhase,
    ) {
        private val statusState = ExecutionStatusState(initialPhase)
        private val paused = MutableStateFlow(false)
        private val stopped = AtomicBoolean(false)
        private var view: View? = null
        private var title: TextView? = null
        private var status: TextView? = null
        private var pause: TextView? = null
        private var stop: TextView? = null

        internal val statusLabel: String
            get() = statusState.label

        internal fun attach(view: View) {
            this.view = view
        }

        internal fun bind(
            title: TextView,
            status: TextView,
            pause: TextView,
            stop: TextView,
        ) {
            this.title = title
            this.status = status
            this.pause = pause
            this.stop = stop
        }

        suspend fun awaitRunning() {
            paused.filter { value -> !value }.first()
            if (stopped.get()) throw CancellationException("GUI task stopped")
        }

        fun update(message: String) {
            val text = message.trim().take(64)
            if (text.isEmpty() || stopped.get()) return
            view?.post { title?.text = text }
        }

        fun updatePhase(phase: ExecutionPhase) {
            statusState.updatePhase(phase)
            if (stopped.get()) return
            view?.post {
                if (!stopped.get()) status?.text = statusState.label
            }
        }

        internal fun togglePaused() {
            if (stopped.get()) return
            paused.value = !paused.value
            renderPaused()
        }

        fun requestStop() {
            if (!stopped.compareAndSet(false, true)) return
            paused.value = false
            view?.post {
                status?.text = "正在停止"
                pause?.isEnabled = false
                stop?.isEnabled = false
            }
            onStop()
        }

        suspend fun finish(message: String, visibleMs: Long = 900L) {
            withContext(Dispatchers.Main) {
                stopped.set(true)
                paused.value = false
                title?.text = message.take(64)
                status?.text = message.take(24)
                pause?.visibility = View.GONE
                stop?.visibility = View.GONE
            }
            delay(visibleMs)
            withContext(Dispatchers.Main) { dismiss() }
        }

        fun dismiss() = synchronized(ExecutionOverlay) {
            dismissLocked()
            if (activeSession === this) activeSession = null
        }

        internal fun dismissLocked() {
            val attached = view ?: return
            runCatching { manager.removeViewImmediate(attached) }
            view = null
        }

        private fun renderPaused() {
            val isPaused = paused.value
            statusState.setPaused(isPaused)
            pause?.text = if (isPaused) "继续" else "接管"
            status?.text = statusState.label
        }
    }
}
