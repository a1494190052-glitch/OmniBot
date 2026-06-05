package cn.com.omnimind.bot.agent.tool.handlers

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import cn.com.omnimind.assists.OmniFlowUiSession
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.ManualToolStopCancellationException
import cn.com.omnimind.bot.omniflow.OobFunctionJson.firstNonBlank
import cn.com.omnimind.uikit.loader.cat.DraggableBallInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the transient frontend state shown while a local Function replay is
 * running. The replay handler owns execution; this controller only manages UI
 * lifecycle and user stop signals.
 */
class OobFunctionFrontendSessionController(
    private val helper: SharedHelper,
) {
    suspend fun start(
        functionId: String,
        spec: Map<String, Any?>,
        stepCount: Int,
        toolHandle: cn.com.omnimind.bot.agent.AgentToolExecutionHandle?,
        callStack: List<String>,
        fallbackRunIdProvider: () -> String,
        frontendRunId: String = "",
        frontendTaskId: String = "",
        frontendParent: String = "",
    ): Session? {
        if (stepCount <= 0 || callStack.isNotEmpty()) return null
        if (!canUseMainDispatcher()) return null
        val embeddedInVlmTask = frontendParent.trim() == "vlm_task"
        val runId = frontendRunId
            .trim()
            .takeIf { it.isNotEmpty() }
            ?: toolHandle?.runId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: fallbackRunIdProvider()
        val taskId = frontendTaskId
            .trim()
            .takeIf { it.isNotEmpty() }
            ?: "${runId}_omniflow_ui"
        val stopRequested = AtomicBoolean(false)
        val label = frontendLabel(functionId, spec)
        val stopOverlay = OobFunctionStopOverlay(
            context = helper.context,
            label = helper.localized("停止"),
            onStop = {
                stopRequested.set(true)
                OmniFlowUiSession.requestStopSession(runId)
                DraggableBallInstance.finishDoingTask(helper.localized("任务已停止"))
            }
        )
        OmniFlowUiSession.registerRun(
            runId = runId,
            onStopRequested = { stopRequested.set(true) },
            onCompleteRequested = { stopRequested.set(true) }
        )
        OmniFlowUiSession.beginTask(runId, taskId)
        runCatching {
            withContext(Dispatchers.Main) {
                DraggableBallInstance.loadBall()
                DraggableBallInstance.setDoing(
                    message = helper.localized("准备执行复用指令"),
                    isShowTakeOver = false,
                    subMessage = helper.localized(label),
                    isShowStop = false,
                    isTouchable = false,
                    forceOnTop = true
                )
                stopOverlay.show()
            }
        }.onFailure {
            OmniLog.w(TAG, "start OmniFlow frontend failed: ${it.message}")
        }
        return Session(
            runId = runId,
            taskId = taskId,
            stopRequested = stopRequested,
            label = label,
            helper = helper,
            embeddedInVlmTask = embeddedInVlmTask,
            stopOverlay = stopOverlay,
        )
    }

    private suspend fun canUseMainDispatcher(): Boolean =
        runCatching {
            withContext(Dispatchers.Main.immediate) { true }
        }.getOrDefault(false)

    private fun frontendLabel(
        functionId: String,
        spec: Map<String, Any?>,
    ): String {
        val name = firstNonBlank(
            spec["name"],
            spec["title"],
            spec["description"],
            functionId,
        )
        return name.replace(Regex("\\s+"), " ").take(32).ifBlank { "复用指令" }
    }

    class Session internal constructor(
        private val runId: String,
        private val taskId: String,
        private val stopRequested: AtomicBoolean,
        private val label: String,
        private val helper: SharedHelper,
        private val embeddedInVlmTask: Boolean,
        private val stopOverlay: OobFunctionStopOverlay,
    ) {
        fun throwIfStopRequested() {
            if (stopRequested.get()) {
                throw ManualToolStopCancellationException("OmniFlow execution stopped manually")
            }
        }

        suspend fun update(progress: String) {
            throwIfStopRequested()
            val progressText = progress.trim().ifBlank { label }.take(48)
            val message = helper.localized(
                "复用指令：${label.take(32)}"
            )
            runCatching {
                withContext(Dispatchers.Main) {
                    DraggableBallInstance.setDoing(
                        message = message,
                        isShowTakeOver = false,
                        subMessage = helper.localized(progressText),
                        isShowStop = false,
                        isTouchable = false,
                        forceOnTop = true
                    )
                }
            }.onFailure {
                OmniLog.w(TAG, "update OmniFlow frontend failed: ${it.message}")
            }
            throwIfStopRequested()
        }

        suspend fun finish(message: String, closeAfterMs: Long = 0L) {
            OmniFlowUiSession.endTask(taskId)
            val end = OmniFlowUiSession.endRun(runId)
            runCatching {
                withContext(NonCancellable + Dispatchers.Main) {
                    stopOverlay.hide()
                    if (!end.wasActive) return@withContext
                    if (embeddedInVlmTask) {
                        DraggableBallInstance.setDoing(
                            message = helper.localized("复用指令执行完成"),
                            isShowTakeOver = false,
                            subMessage = helper.localized("智能执行中"),
                            isShowStop = false,
                            isTouchable = false,
                            forceOnTop = true
                        )
                    } else {
                        DraggableBallInstance.setDoing(
                            message = helper.localized(message.ifBlank { "任务已完成" }),
                            isShowTakeOver = false,
                            subMessage = helper.localized(label),
                            isShowStop = false,
                            isTouchable = false,
                            forceOnTop = true
                        )
                        if (closeAfterMs > 0L) {
                            delay(closeAfterMs)
                            DraggableBallInstance.finishDoingTask(
                                helper.localized(message.ifBlank { "任务已完成" })
                            )
                        }
                    }
                }
            }.onFailure {
                OmniLog.w(TAG, "finish OmniFlow frontend failed: ${it.message}")
            }
        }
    }

    private companion object {
        const val TAG = "OobFunctionFrontendSession"
    }
}

internal class OobFunctionStopOverlay(
    context: Context,
    private val label: String,
    private val onStop: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null

    fun show() {
        if (view != null) return
        val button = TextView(appContext).apply {
            text = label
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = label
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 18.dp().toFloat()
                setColor(0xE6C62828.toInt())
            }
            elevation = 8.dp().toFloat()
            setOnClickListener { onStop() }
        }
        val params = WindowManager.LayoutParams(
            56.dp(),
            36.dp(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 16.dp()
            y = 128.dp()
        }
        runCatching {
            windowManager.addView(button, params)
            view = button
        }.onFailure {
            OmniLog.w(TAG, "show OOB stop overlay failed: ${it.message}")
        }
    }

    fun hide() {
        val current = view ?: return
        view = null
        runCatching {
            windowManager.removeView(current)
        }.onFailure {
            OmniLog.w(TAG, "hide OOB stop overlay failed: ${it.message}")
        }
    }

    private fun Int.dp(): Int =
        (this * appContext.resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val TAG = "OobFunctionStopOverlay"
    }
}
