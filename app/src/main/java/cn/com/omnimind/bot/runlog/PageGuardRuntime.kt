package cn.com.omnimind.bot.runlog

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.accessibility.service.AssistsServiceListener
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.uikit.loader.FloatingHalfScreenLoader
import cn.com.omnimind.uikit.loader.cat.DraggableBallInstance
import cn.com.omnimind.uikit.settings.CompanionOverlaySettings
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Lightweight long-lived page guard for the floating XiaoWan UI.
 *
 * This is intentionally not a GKD-style subscription engine. It reuses the
 * existing OmniFlow checker candidate scoring and only runs while the floating
 * XiaoWan overlay is actually visible.
 */
object PageGuardRuntime {
    private const val TAG = "PageGuardRuntime"
    private const val DEBOUNCE_MS = 450L
    private const val MIN_RUN_INTERVAL_MS = 1_200L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val installed = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val checkerBudget = OmniflowStepExecutor.CheckerTriggerBudget()

    @Volatile
    private var lastRequestedAtMs: Long = 0L

    @Volatile
    private var lastRunAtMs: Long = 0L

    @Volatile
    private var lastResult: Map<String, Any?> = emptyMap()

    private val listener = object : AssistsServiceListener {
        override fun onAccessibilityEvent(event: AccessibilityEvent) {
            if (!isRelevantEvent(event)) return
            requestRun("accessibility_event:${event.eventType}")
        }

        override fun onUnbind() {
            running.set(false)
        }
    }

    fun install(context: Context) {
        CompanionOverlaySettings.init(context)
        if (!installed.compareAndSet(false, true)) return
        AssistsService.addListener(listener)
        OmniLog.i(TAG, "floating XiaoWan page guard installed")
    }

    fun status(): Map<String, Any?> = linkedMapOf(
        "installed" to installed.get(),
        "active" to isActive(),
        "floating_overlay_enabled" to CompanionOverlaySettings.isEnabled(),
        "floating_ball_showing" to DraggableBallInstance.isShowing(),
        "floating_panel_showing" to FloatingHalfScreenLoader.isShowing(),
        "running" to running.get(),
        "last_requested_at_ms" to lastRequestedAtMs.takeIf { it > 0L },
        "last_run_at_ms" to lastRunAtMs.takeIf { it > 0L },
        "last_result" to lastResult.takeIf { it.isNotEmpty() },
    ).filterValues { it != null }

    private fun requestRun(source: String) {
        if (!isActive()) return
        val requestedAt = System.currentTimeMillis()
        lastRequestedAtMs = requestedAt
        scope.launch {
            delay(DEBOUNCE_MS)
            if (lastRequestedAtMs != requestedAt) return@launch
            runOnce(source)
        }
    }

    private suspend fun runOnce(source: String) {
        if (!isActive()) return
        val now = System.currentTimeMillis()
        if (now - lastRunAtMs < MIN_RUN_INTERVAL_MS) return
        if (!running.compareAndSet(false, true)) return
        try {
            if (!isActive()) return
            val result = OmniflowStepExecutor.runPageGuardOnce(
                execute = true,
                source = "floating_xiaowan:$source",
                checkerBudget = checkerBudget,
            )
            lastRunAtMs = System.currentTimeMillis()
            lastResult = result
            if (result["matched"] == true || result["executed"] == true) {
                OmniLog.i(TAG, "page guard result=$result")
            }
        } catch (error: Exception) {
            lastResult = linkedMapOf(
                "success" to false,
                "error" to error.message.orEmpty(),
                "source" to source,
                "captured_at_ms" to System.currentTimeMillis(),
            )
            OmniLog.w(TAG, "page guard failed: ${error.message}", error)
        } finally {
            running.set(false)
        }
    }

    private fun isActive(): Boolean =
        CompanionOverlaySettings.isEnabled() &&
            (DraggableBallInstance.isShowing() || FloatingHalfScreenLoader.isShowing())

    private fun isRelevantEvent(event: AccessibilityEvent): Boolean =
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> true
            else -> false
        }
}
