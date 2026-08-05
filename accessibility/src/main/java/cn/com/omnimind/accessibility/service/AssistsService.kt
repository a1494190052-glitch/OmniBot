package cn.com.omnimind.accessibility.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CopyOnWriteArraySet

open class AssistsService : AccessibilityService() {
    @Volatile
    private var connected: Boolean = false

    @Volatile
    var lastPackageName: String = ""
        private set

    @Volatile
    var lastActivityName: String = ""
        private set

    override fun onCreate() {
        super.onCreate()
        connected = false
        instance = this
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        instance = this
        listeners.forEach { listener -> runCatching { listener.onServiceConnected(this) } }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        instance = this
        event.packageName?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let {
            lastPackageName = it
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.className?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let {
                lastActivityName = it
            }
        }
        listeners.forEach { listener -> runCatching { listener.onAccessibilityEvent(event) } }
    }

    override fun onInterrupt() {
        listeners.forEach { listener -> runCatching { listener.onInterrupt() } }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        if (instance === this) instance = null
        listeners.forEach { listener -> runCatching { listener.onUnbind() } }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun hideKeyboard() {
        softKeyboardController.showMode = SHOW_MODE_HIDDEN
    }

    fun restoreKeyboard() {
        softKeyboardController.showMode = SHOW_MODE_AUTO
    }

    companion object {
        @Volatile
        var instance: AssistsService? = null
            private set

        private val listeners = CopyOnWriteArraySet<AssistsServiceListener>()

        fun readyInstance(): AssistsService? = instance?.takeIf { it.connected }

        fun isReady(): Boolean = readyInstance() != null

        fun addListener(listener: AssistsServiceListener) {
            listeners += listener
        }

        fun removeListener(listener: AssistsServiceListener) {
            listeners -= listener
        }
    }
}
