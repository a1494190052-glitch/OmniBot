package cn.com.omnimind.bot.function

import cn.com.omnimind.assists.FunctionUiSession
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.ManualToolStopCancellationException
import cn.com.omnimind.bot.agent.tool.handlers.SharedHelper
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
class FunctionFrontendSessionController(
    private val helper: SharedHelper,
) {
    suspend fun start(
        functionId: String,
        runId: String,
        frontendRunId: String = "",
        frontendTaskId: String = "",
        frontendParent: String = "",
    ): Session {
        val frontendMode = frontendParent.trim().lowercase()
        val canUseUiOverlay = !isHeadlessJvm()
        val embeddedInVlmTask = frontendMode == "vlm_task"
        val resolvedRunId = frontendRunId
            .trim()
            .takeIf { it.isNotEmpty() }
            ?: runId
        val taskId = frontendTaskId
            .trim()
            .takeIf { it.isNotEmpty() }
            ?: "${resolvedRunId}_omniflow_ui"
        val stopRequested = AtomicBoolean(false)
        val label = frontendLabel(functionId)
        fun requestStopNow() {
            stopRequested.set(true)
        }
        FunctionUiSession.registerRun(
            runId = resolvedRunId,
            onStopRequested = { requestStopNow() },
            onCompleteRequested = {},
        )
        FunctionUiSession.beginTask(resolvedRunId, taskId)
        if (canUseUiOverlay) {
            runCatching {
                withContext(Dispatchers.Main) {
                    DraggableBallInstance.loadBall()
                    DraggableBallInstance.setDoing(
                        message = helper.localized("准备执行复用指令"),
                        isShowTakeOver = false,
                        subMessage = helper.localized(label),
                        isShowStop = true,
                        isTouchable = true,
                        forceOnTop = true
                    )
                }
            }.onFailure {
                OmniLog.w(TAG, "start Function frontend failed: ${it.message}")
            }
        }
        return Session(
            runId = resolvedRunId,
            taskId = taskId,
            stopRequested = stopRequested,
            label = label,
            helper = helper,
            embeddedInVlmTask = embeddedInVlmTask,
            canUseUiOverlay = canUseUiOverlay,
        )
    }

    private fun isHeadlessJvm(): Boolean =
        System.getProperty("java.awt.headless")
            ?.equals("true", ignoreCase = true) == true

    private fun frontendLabel(functionId: String): String =
        functionId.replace(Regex("[_\\s-]+"), " ").trim().take(32).ifBlank { "复用指令" }

    class Session internal constructor(
        private val runId: String,
        private val taskId: String,
        private val stopRequested: AtomicBoolean,
        private val label: String,
        private val helper: SharedHelper,
        private val embeddedInVlmTask: Boolean,
        private val canUseUiOverlay: Boolean,
    ) {
        fun isStopRequested(): Boolean = stopRequested.get()

        fun throwIfStopRequested() {
            if (stopRequested.get()) {
                throw ManualToolStopCancellationException("Function execution stopped manually")
            }
        }

        suspend fun update(progress: String) {
            throwIfStopRequested()
            val progressText = progress.trim().ifBlank { label }.take(48)
            val message = helper.localized(
                "复用指令：${label.take(32)}"
            )
            if (canUseUiOverlay) {
                runCatching {
                    withContext(Dispatchers.Main) {
                        DraggableBallInstance.setDoing(
                            message = message,
                            isShowTakeOver = false,
                            subMessage = helper.localized(progressText),
                            isShowStop = true,
                            isTouchable = true,
                            forceOnTop = true
                        )
                    }
                }.onFailure {
                    OmniLog.w(TAG, "update Function frontend failed: ${it.message}")
                }
            }
            throwIfStopRequested()
        }

        suspend fun finish(message: String, closeAfterMs: Long = 0L) {
            FunctionUiSession.endTask(taskId)
            val end = FunctionUiSession.endRun(runId)
            if (canUseUiOverlay) {
                runCatching {
                    withContext(NonCancellable + Dispatchers.Main) {
                        if (!end.wasActive) return@withContext
                        val finishMsg = helper.localized(message.ifBlank { "任务已完成" })
                        if (embeddedInVlmTask) {
                            DraggableBallInstance.setDoing(
                                message = helper.localized("复用指令执行完成"),
                                isShowTakeOver = false,
                                subMessage = finishMsg,
                                isShowStop = false,
                                isTouchable = false,
                                forceOnTop = true
                            )
                            delay(OMNIFLOW_FINISH_VISIBLE_MS)
                            DraggableBallInstance.finishDoingTask(finishMsg)
                        } else {
                            DraggableBallInstance.setDoing(
                                message = finishMsg,
                                isShowTakeOver = false,
                                subMessage = helper.localized(label),
                                isShowStop = false,
                                isTouchable = false,
                                forceOnTop = true
                            )
                            if (closeAfterMs > 0L) {
                                delay(closeAfterMs)
                                DraggableBallInstance.finishDoingTask(finishMsg)
                            }
                        }
                    }
                }.onFailure {
                    OmniLog.w(TAG, "finish Function frontend failed: ${it.message}")
                }
            }
        }
    }

    private companion object {
        const val TAG = "FunctionFrontendSession"
        const val OMNIFLOW_FINISH_VISIBLE_MS = 900L
    }
}
