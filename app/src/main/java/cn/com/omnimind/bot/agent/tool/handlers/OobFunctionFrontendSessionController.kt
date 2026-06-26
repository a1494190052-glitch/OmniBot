package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.assists.OmniFlowUiSession
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.ManualToolStopCancellationException
import cn.com.omnimind.bot.manager.AssistsCoreManager
import cn.com.omnimind.bot.omniflow.OobFunctionJson.firstNonBlank
import cn.com.omnimind.uikit.loader.cat.DraggableBallInstance
import cn.com.omnimind.uikit.settings.CompanionOverlaySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
        val frontendMode = frontendParent.trim().lowercase()
        val suppressUiOverlay = frontendMode in HEADLESS_FRONTEND_PARENTS
        val canUseUiOverlay = !suppressUiOverlay && !isHeadlessJvm() && canUseMainDispatcher()
        val embeddedInVlmTask = frontendMode == "vlm_task"
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
        val completeRequested = AtomicBoolean(false)
        val label = frontendLabel(functionId, spec)
        fun requestStopNow() {
            stopRequested.set(true)
        }
        fun requestCompleteNow() {
            completeRequested.set(true)
        }
        OmniFlowUiSession.registerRun(
            runId = runId,
            onStopRequested = { requestStopNow() },
            onCompleteRequested = { requestCompleteNow() }
        )
        toolHandle?.bindStopAction {
            requestStopNow()
        }
        OmniFlowUiSession.beginTask(runId, taskId)
        dispatchRunProgress(
            status = "started",
            runId = runId,
            taskId = taskId,
            functionId = functionId,
            label = label,
            stepCount = stepCount,
            embeddedInVlmTask = embeddedInVlmTask,
            message = helper.localized("准备执行复用指令"),
        )
        if (suppressUiOverlay) {
            CompanionOverlaySettings.dismissFloatingUi()
        } else if (canUseUiOverlay) {
            runCatching {
                withContext(Dispatchers.Main) {
                    DraggableBallInstance.loadBall()
                    DraggableBallInstance.doingTask(
                        message = helper.localized("准备执行复用指令"),
                        subMessage = helper.localized(label),
                        forceOnTop = true,
                        isTouchable = true
                    )
                }
            }.onFailure {
                OmniLog.w(TAG, "start OmniFlow frontend failed: ${it.message}")
            }
        }
        return Session(
            runId = runId,
            taskId = taskId,
            stopRequested = stopRequested,
            completeRequested = completeRequested,
            label = label,
            helper = helper,
            functionId = functionId,
            stepCount = stepCount,
            embeddedInVlmTask = embeddedInVlmTask,
            canUseUiOverlay = canUseUiOverlay,
            toolHandle = toolHandle,
        )
    }

    private suspend fun canUseMainDispatcher(): Boolean =
        withTimeoutOrNull(MAIN_DISPATCHER_PROBE_TIMEOUT_MS) {
            runCatching {
                withContext(Dispatchers.Main.immediate) { true }
            }.getOrDefault(false)
        } ?: false

    private fun isHeadlessJvm(): Boolean =
        System.getProperty("java.awt.headless")
            ?.equals("true", ignoreCase = true) == true

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
        private val completeRequested: AtomicBoolean,
        private val label: String,
        private val helper: SharedHelper,
        private val functionId: String,
        private val stepCount: Int,
        private val embeddedInVlmTask: Boolean,
        private val canUseUiOverlay: Boolean,
        private val toolHandle: cn.com.omnimind.bot.agent.AgentToolExecutionHandle?,
    ) {
        fun requestStop() {
            stopRequested.set(true)
        }

        fun isStopRequested(): Boolean = stopRequested.get()

        fun isCompleteRequested(): Boolean = completeRequested.get()

        fun isUserFinishedRequested(): Boolean =
            completeRequested.get() && !stopRequested.get()

        fun throwIfStopRequested() {
            if (stopRequested.get()) {
                throw ManualToolStopCancellationException("OmniFlow execution stopped manually")
            }
        }

        suspend fun update(progress: String) {
            throwIfStopRequested()
            val progressText = progress.trim().ifBlank { label }.take(48)
            val currentStepNumber = STEP_PROGRESS_REGEX.find(progressText)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            val message = helper.localized(
                "复用指令：${label.take(32)}"
            )
            dispatchRunProgress(
                status = "progress",
                runId = runId,
                taskId = taskId,
                functionId = functionId,
                label = label,
                stepCount = stepCount,
                embeddedInVlmTask = embeddedInVlmTask,
                message = helper.localized(progressText),
                currentStepNumber = currentStepNumber,
            )
            if (canUseUiOverlay) {
                runCatching {
                    withContext(Dispatchers.Main) {
                        DraggableBallInstance.doingTask(
                            message = message,
                            subMessage = helper.localized(progressText),
                            forceOnTop = true,
                            isTouchable = true
                        )
                    }
                }.onFailure {
                    OmniLog.w(TAG, "update OmniFlow frontend failed: ${it.message}")
                }
            }
            throwIfStopRequested()
        }

        suspend fun finish(message: String, closeAfterMs: Long = 0L) {
            OmniFlowUiSession.endTask(taskId)
            val end = OmniFlowUiSession.endRun(runId)
            toolHandle?.bindStopAction(null)
            dispatchRunProgress(
                status = if (stopRequested.get()) "stopped" else "finished",
                runId = runId,
                taskId = taskId,
                functionId = functionId,
                label = label,
                stepCount = stepCount,
                embeddedInVlmTask = embeddedInVlmTask,
                message = helper.localized(message.ifBlank { "任务已完成" }),
            )
            if (canUseUiOverlay) {
                runCatching {
                    withContext(NonCancellable + Dispatchers.Main) {
                        if (!end.wasActive) return@withContext
                        val finishMsg = helper.localized(message.ifBlank { "任务已完成" })
                        if (embeddedInVlmTask) {
                            DraggableBallInstance.doingTask(
                                message = helper.localized("复用指令执行完成"),
                                subMessage = finishMsg,
                                forceOnTop = true,
                                isTouchable = true
                            )
                            delay(OMNIFLOW_FINISH_VISIBLE_MS)
                            DraggableBallInstance.finishDoingTask(finishMsg)
                        } else {
                            DraggableBallInstance.doingTask(
                                message = finishMsg,
                                subMessage = helper.localized(label),
                                forceOnTop = true,
                                isTouchable = true
                            )
                            if (closeAfterMs > 0L) {
                                delay(closeAfterMs)
                                DraggableBallInstance.finishDoingTask(finishMsg)
                            }
                        }
                        if (embeddedInVlmTask && !stopRequested.get()) {
                            AssistsCoreManager.requestCompleteActiveVlmTask(
                                runOrTaskId = runId,
                                reason = "omniflow_finished",
                            )
                        }
                    }
                }.onFailure {
                    OmniLog.w(TAG, "finish OmniFlow frontend failed: ${it.message}")
                }
            }
        }
    }

    private companion object {
        const val TAG = "OobFunctionFrontendSession"
        const val MAIN_DISPATCHER_PROBE_TIMEOUT_MS = 100L
        const val OMNIFLOW_FINISH_VISIBLE_MS = 900L
        val STEP_PROGRESS_REGEX = Regex("""第\s*(\d+)\s*/\s*\d+\s*步""")
        val HEADLESS_FRONTEND_PARENTS = setOf(
            "debug_replay",
            "native_replay",
            "androidworld_validator",
            "headless",
        )

        fun dispatchRunProgress(
            status: String,
            runId: String,
            taskId: String,
            functionId: String,
            label: String,
            stepCount: Int,
            embeddedInVlmTask: Boolean,
            message: String,
            currentStepNumber: Int? = null,
        ) {
            val currentStepIndex = currentStepNumber
                ?.takeIf { it > 0 }
                ?.minus(1)
            AssistsCoreManager.dispatchOobFunctionRunProgress(
                linkedMapOf<String, Any?>(
                    "status" to status,
                    "run_id" to runId,
                    "runId" to runId,
                    "task_id" to taskId,
                    "taskId" to taskId,
                    "function_id" to functionId,
                    "functionId" to functionId,
                    "label" to label,
                    "message" to message,
                    "step_count" to stepCount.takeIf { it > 0 },
                    "stepCount" to stepCount.takeIf { it > 0 },
                    "current_step_index" to currentStepIndex,
                    "currentStepIndex" to currentStepIndex,
                    "current_step_number" to currentStepNumber,
                    "currentStepNumber" to currentStepNumber,
                    "parent" to if (embeddedInVlmTask) "vlm_task" else "oob_function_direct_run",
                    "embedded_in_vlm_task" to embeddedInVlmTask,
                    "timestamp_ms" to System.currentTimeMillis(),
                ).filterValues { it != null }
            )
        }
    }
}
