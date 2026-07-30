package cn.com.omnimind.androidgui

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CopyOnWriteArraySet

class AndroidGuiAccessibilityService : AccessibilityService() {
    @Volatile
    internal var lastPackageName: String = ""
        private set

    @Volatile
    internal var lastActivityName: String = ""
        private set

    override fun onServiceConnected() {
        instance = this
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

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: AndroidGuiAccessibilityService? = null
            private set

        private val listeners = CopyOnWriteArraySet<AndroidGuiEventListener>()

        fun isReady(): Boolean = instance != null

        fun addEventListener(listener: AndroidGuiEventListener) {
            listeners += listener
        }

        fun removeEventListener(listener: AndroidGuiEventListener) {
            listeners -= listener
        }
    }
}

fun interface AndroidGuiEventListener {
    fun onAccessibilityEvent(event: AccessibilityEvent)
}
