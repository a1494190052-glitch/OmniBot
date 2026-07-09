package cn.com.omnimind.bot.function

import cn.com.omnimind.assists.FunctionUiSession
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.ManualToolStopCancellationException
import cn.com.omnimind.bot.agent.tool.handlers.SharedHelper
import cn.com.omnimind.bot.function.FunctionJson.firstNonBlank
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
    fun cardId(parentToolCallId: String?, toolName: String, stepId: String): String {
        val base = safeCardIdPart(firstNonBlank(parentToolCallId, toolName, "function"))
        val step = safeCardIdPart(stepId.ifBlank { "step" })
        return "${base}_${step}_function"
    }

    fun runningSummary(functionId: String): String {
        return if (functionId.isNotBlank()) {
            "${helper.localized("正在执行复用指令")}：$functionId"
        } else {
            helper.localized("正在执行复用指令")
        }
    }

    fun finishedSummary(functionId: String, success: Boolean): String {
        val base = helper.localized(if (success) "复用指令执行完成" else "复用指令执行失败")
        return if (functionId.isNotBlank()) "$base：$functionId" else base
    }

    fun callToolCardPayload(
        cardId: String,
        toolName: String,
        stepTitle: String,
        functionId: String,
        callableTool: String,
        functionArguments: Map<String, Any?>,
        status: String,
        success: Boolean?,
        summary: String,
        progress: String,
        startedAtMs: Long,
        finishedAtMs: Long?,
        result: Map<String, Any?>?,
    ): Map<String, Any?> {
        val argsPayload = linkedMapOf<String, Any?>(
            "function_id" to functionId.takeIf { it.isNotBlank() },
            "arguments" to functionArguments.takeIf { it.isNotEmpty() },
        ).filterValues { it != null }
        val resultPayload = result?.let {
            linkedMapOf<String, Any?>(
                "function_id" to functionId.takeIf { id -> id.isNotBlank() },
                "source" to FunctionRun.FUNCTION_RUN_SOURCE,
                "run_source" to FunctionRun.FUNCTION_RUN_SOURCE,
                "runner" to it["runner"],
                "called_function_run_id" to it["called_function_run_id"],
                "called_function_step_count" to it["called_function_step_count"],
                "called_function_success_step_count" to it["called_function_success_step_count"],
                "success" to (it["success"] != false),
                "summary" to it["summary"],
                "error_code" to it["error_code"],
            ).filterValues { value -> value != null }
        }
        val argsJson = helper.encodeLocalizedPayload(argsPayload)
        return linkedMapOf<String, Any?>(
            "cardId" to cardId,
            "toolCallId" to cardId,
            "callId" to cardId,
            "toolName" to toolName,
            "displayName" to helper.localized("复用指令"),
            "toolType" to "oob_function",
            "source" to FunctionRun.FUNCTION_RUN_SOURCE,
            "runSource" to FunctionRun.FUNCTION_RUN_SOURCE,
            "run_source" to FunctionRun.FUNCTION_RUN_SOURCE,
            "runner" to (result?.get("runner") ?: FunctionRun.FUNCTION_DIRECT_RUNNER),
            "toolTitle" to if (functionId.isNotBlank()) {
                "${helper.localized("复用指令")}：$functionId"
            } else {
                stepTitle
            },
            "summary" to summary,
            "progress" to progress,
            "status" to status,
            "success" to success,
            "args" to argsJson,
            "argsJson" to argsJson,
            "sourceTool" to callableTool,
            "functionId" to functionId,
            "function_id" to functionId,
            "startedAtMs" to startedAtMs,
            "started_at_ms" to startedAtMs,
            "finishedAtMs" to finishedAtMs,
            "finished_at_ms" to finishedAtMs,
            "durationMs" to finishedAtMs?.let { (it - startedAtMs).coerceAtLeast(0) },
            "duration_ms" to finishedAtMs?.let { (it - startedAtMs).coerceAtLeast(0) },
            "resultPreviewJson" to resultPayload?.let { helper.encodeLocalizedPayload(it) }.orEmpty(),
            "rawResultJson" to result?.let { helper.encodeLocalizedPayload(it) }.orEmpty(),
        ).filterValues { it != null }
    }

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
        val canUseUiOverlay = !isHeadlessJvm()
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
        FunctionUiSession.registerRun(
            runId = runId,
            onStopRequested = { requestStopNow() },
            onCompleteRequested = { requestCompleteNow() }
        )
        toolHandle?.bindStopAction {
            requestStopNow()
        }
        FunctionUiSession.beginTask(runId, taskId)
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
            runId = runId,
            taskId = taskId,
            stopRequested = stopRequested,
            completeRequested = completeRequested,
            label = label,
            helper = helper,
            embeddedInVlmTask = embeddedInVlmTask,
            canUseUiOverlay = canUseUiOverlay,
            toolHandle = toolHandle,
        )
    }

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

    private fun safeCardIdPart(raw: String): String {
        val normalized = raw.trim().replace(Regex("[^A-Za-z0-9_.:-]"), "_")
        return normalized.take(96).ifBlank { "function" }
    }

    class Session internal constructor(
        private val runId: String,
        private val taskId: String,
        private val stopRequested: AtomicBoolean,
        private val completeRequested: AtomicBoolean,
        private val label: String,
        private val helper: SharedHelper,
        private val embeddedInVlmTask: Boolean,
        private val canUseUiOverlay: Boolean,
        private val toolHandle: cn.com.omnimind.bot.agent.AgentToolExecutionHandle?,
    ) {
        fun requestStop() {
            stopRequested.set(true)
        }

        fun isStopRequested(): Boolean = stopRequested.get()

        fun isUserFinishedRequested(): Boolean =
            completeRequested.get() && !stopRequested.get()

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
            toolHandle?.bindStopAction(null)
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
