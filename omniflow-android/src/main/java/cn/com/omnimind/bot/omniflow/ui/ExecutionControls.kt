package cn.com.omnimind.bot.omniflow.ui

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class ExecutionControls private constructor(
    private val controls: ExecutionOverlay.Session?,
    private val stopRequested: AtomicBoolean,
    private val dispatchStop: () -> Unit,
) {
    suspend fun awaitRunning() {
        controls?.awaitRunning()
        if (stopRequested.get()) throw CancellationException("GUI execution stopped")
    }

    fun requestStop() {
        controls?.requestStop() ?: dispatchStop()
    }

    fun update(message: String) {
        controls?.update(message)
    }

    fun updatePhase(phase: ExecutionPhase) {
        controls?.updatePhase(phase)
    }

    suspend fun finish(message: String, visibleMs: Long = 900L) {
        withContext(NonCancellable) {
            controls?.finish(message, visibleMs)
        }
    }

    companion object {
        suspend fun start(
            context: Context,
            title: String,
            initialPhase: ExecutionPhase,
            onStop: () -> Unit,
        ): ExecutionControls {
            val stopRequested = AtomicBoolean(false)
            val dispatchStop = {
                if (stopRequested.compareAndSet(false, true)) onStop()
            }
            val controls = withContext(Dispatchers.Main) {
                ExecutionOverlay.show(context, title, initialPhase, dispatchStop)
            }
            return ExecutionControls(controls, stopRequested, dispatchStop)
        }
    }
}
