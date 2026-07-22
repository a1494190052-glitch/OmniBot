package cn.com.omnimind.assists.task.vlmserver

/**
 * 动作执行器 - 负责执行UI操作动作
 * 对应Python中的 act 方法
 */

import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.baselib.runlog.CanonicalActionConverter
import cn.com.omnimind.baselib.runlog.OobActionSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json

internal const val MAX_CANONICAL_WAIT_MS = 60_000L

interface DeviceOperator {
    suspend fun clickCoordinate(x: Float, y: Float): OperationResult
    suspend fun longClickCoordinate(x: Float, y: Float, duration: Long = 1000L): OperationResult
    suspend fun inputText(text: String): OperationResult
    suspend fun pressHotKey(key: String): OperationResult
    suspend fun copyToClipboard(text: String): OperationResult
    suspend fun getClipboard(): String? // 获取剪贴板内容
    suspend fun slideCoordinate(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long): OperationResult
    suspend fun goHome(): OperationResult
    suspend fun goBack(): OperationResult
    suspend fun launchApplication(packageName: String): OperationResult
    suspend fun captureScreenshot(): String // 返回base64编码的截图
    fun getLastScreenshotWidth(): Int // 获取最后一次截图的宽度
    fun getLastScreenshotHeight(): Int // 获取最后一次截图的高度
    fun getDisplayWidth(): Int // 设备实际屏幕宽度
    fun getDisplayHeight(): Int // 设备实际屏幕高度
    suspend fun showInfo(message: String)
    fun isReady(): Boolean
    fun currentXml(): String?
    fun currentPackageName(): String?
    fun currentActivityName(): String?
    suspend fun hideKeyboard(): OperationResult
}

interface TargetedInputDeviceOperator : DeviceOperator {
    suspend fun inputTextAtTarget(
        text: String,
        targetDescription: String,
        x: Float?,
        y: Float?,
        nodeResourceId: String,
    ): OperationResult

    suspend fun pressImeEnterAtTarget(
        targetDescription: String,
        x: Float?,
        y: Float?,
        nodeResourceId: String,
    ): OperationResult
}

fun interface FunctionRunExecutor {
    suspend fun run(invocation: FunctionInvocation, context: VLMFunctionRunContext): OperationResult
}

class ActionExecutor(
    private val deviceOperator: DeviceOperator,
    private val contextManager: UIContextManager,
    private val functionRunExecutor: FunctionRunExecutor? = null,
    private val controlActExecutor: ControlActExecutor? = null,
) {
    private val TAG = "ActionExecutor"
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun ensureActionActive() {
        currentCoroutineContext().ensureActive()
    }

    /**
     * 执行VLM推理出的动作
     * 对应Python中的 act 方法
     * 注意：只执行动作，不更新上下文
     */
    suspend fun executeAction(
        vlmStep: UIStep,
        functionRunContext: VLMFunctionRunContext = VLMFunctionRunContext(),
    ): UIStep {

        val actionStart = System.currentTimeMillis()
        ensureActionActive()
        val rawResult = when (val command = vlmStep.action) {
            is Action -> {
                executeCanonicalAction(
                    tool = command.tool,
                    args = command.argsMap(),
                    state = vlmStep.beforeState,
                )
            }

            is Observe -> {
                OperationResult(
                    success = true,
                    message = command.reason.ifBlank { "已重新获取当前页面状态" },
                    data = null
                )
            }

            is FunctionInvocation -> {
                functionRunExecutor?.run(command, functionRunContext)
                    ?: OperationResult(
                        success = false,
                        message = "复用指令执行器未注册",
                        data = null,
                    )
            }

            is RecordMemory -> {
                // 特殊处理：记录动作不调用设备，返回成功结果
                OperationResult(
                    success = true,
                    message = "记忆关键信息成功",
                    data = null
                )
            }

            is FinishedDecision -> {
                OperationResult(
                    success = true,
                    message = command.content.ifEmpty { "任务完成" },
                    data = null
                )
            }

            is InfoDecision -> {
                OperationResult(
                    success = true,
                    message = "Agent询问: ${command.value}",
                    data = null
                )
            }

            is AbortDecision -> {
                OperationResult(
                    success = true,
                    message = "任务终止: ${command.value}",
                    data = null
                )
            }
        }

        val totalMs = System.currentTimeMillis() - actionStart
        val result = rawResult.copy(
            diagnostics = rawResult.diagnostics + linkedMapOf(
                "action_executor_total_ms" to totalMs.toString(),
            )
        )
        runCatching {
            OmniLog.i(
                "TimeRecord",
                "VLM-actionExecutor ${vlmStep.action.name} took $totalMs ms"
            )
        }

        return UIStep(
            observation = vlmStep.observation,
            thought = vlmStep.thought,
            action = vlmStep.action,
            result = if (result.success) result.message else "执行失败: ${result.message}",
            actionResultData = result.data,
            pageDiagnostics = result.diagnostics,
            beforeState = result.beforeState,
            afterState = result.afterState,
        )
    }


    suspend fun act(
        vlmStep: UIStep,
        functionRunContext: VLMFunctionRunContext = VLMFunctionRunContext(),
    ): UIStep {
        return executeAction(vlmStep, functionRunContext)
    }

    suspend fun act(
        action: String,
        args: Map<String, Any?>,
        source: String = SOURCE_AGENT_ACTION,
        diagnostics: Map<String, Any?> = emptyMap(),
        stopRequested: (() -> Boolean)? = null,
    ): OperationResult {
        return try {
            throwIfStopRequested(stopRequested)
            val effectiveAction = resolveActionName(action) ?: OobActionSchema.normalizeToolName(action)
            val dispatchResult = dispatchPhysical(
                effectiveAction,
                physicalArgs(effectiveAction, args),
                stopRequested,
            )
            val settleDelayMs = if (dispatchResult.success) {
                postActionDelayMs(effectiveAction)
            } else {
                0L
            }
            if (settleDelayMs > 0L) {
                waitInterruptibly(settleDelayMs, stopRequested)
            }
            val mergedDiagnostics = diagnostics + mapOf(
                "action_source" to source,
                "action_executor_post_delay_ms" to settleDelayMs,
            )
            if (mergedDiagnostics.isEmpty()) {
                dispatchResult
            } else {
                dispatchResult.copy(
                    diagnostics = dispatchResult.diagnostics + mergedDiagnostics.toStringDiagnostics()
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val message = error.message.orEmpty().ifBlank { "action failed: $action" }
            OperationResult(
                success = false,
                message = message,
                data = null,
                diagnostics = actionFailureDiagnostics(message),
            )
        }
    }

    private suspend fun executeCanonicalAction(
        tool: String,
        args: Map<String, Any?>,
        state: State?,
    ): OperationResult {
        return try {
            val controlled = requireNotNull(controlActExecutor) {
                "control_act_executor_not_registered"
            }.act(tool, args, state)
            if (controlled.success) {
                controlled.copy(message = actionSuccessMessage(tool, args))
            } else {
                controlled
            }
        } catch (error: Exception) {
            val message = error.message.orEmpty().ifBlank { "action failed: $tool" }
            OperationResult(
                success = false,
                message = message,
                data = null,
                diagnostics = actionFailureDiagnostics(message),
            )
        }
    }

    private suspend fun dispatchPhysical(
        action: String,
        args: Map<String, Any?>,
        stopRequested: (() -> Boolean)? = null,
    ): OperationResult {
        return when (action) {
            OobActionSchema.TOOL_CLICK -> deviceOperator.clickCoordinate(
                x = floatArg(args, OobActionSchema.ARG_X, defaultValue = 0f),
                y = floatArg(args, OobActionSchema.ARG_Y, defaultValue = 0f),
            )

            OobActionSchema.TOOL_LONG_PRESS -> deviceOperator.longClickCoordinate(
                x = floatArg(args, OobActionSchema.ARG_X, defaultValue = 0f),
                y = floatArg(args, OobActionSchema.ARG_Y, defaultValue = 0f),
                duration = longArg(args, OobActionSchema.ARG_DURATION_MS, defaultValue = 800L),
            )

            OobActionSchema.TOOL_INPUT_TEXT -> {
                val x = numberArg(args, OobActionSchema.ARG_X)?.toFloat()
                val y = numberArg(args, OobActionSchema.ARG_Y)?.toFloat()
                val text = stringArg(args, OobActionSchema.ARG_TEXT)
                val targetDescription = stringArg(args, OobActionSchema.ARG_TARGET_DESCRIPTION)
                val nodeResourceId = stringArg(
                    args,
                    OobActionSchema.ARG_NODE_RESOURCE_ID,
                    OobActionSchema.ARG_NODE_ID,
                )
                if (deviceOperator is TargetedInputDeviceOperator) {
                    return deviceOperator.inputTextAtTarget(
                        text = text,
                        targetDescription = targetDescription,
                        x = x,
                        y = y,
                        nodeResourceId = nodeResourceId,
                    )
                }
                if (x != null && y != null) {
                    val focusResult = deviceOperator.clickCoordinate(x, y)
                    if (!focusResult.success) return focusResult
                    delay(INPUT_FOCUS_DELAY_MS)
                }
                deviceOperator.inputText(text)
            }

            OobActionSchema.TOOL_SWIPE -> dispatchSwipe(args)

            OobActionSchema.TOOL_OPEN_APP -> {
                val packageName = stringArg(
                    args,
                    OobActionSchema.ARG_PACKAGE_NAME,
                )
                if (packageName.isBlank()) {
                    OperationResult(false, "open_app requires package_name", null)
                } else {
                    deviceOperator.launchApplication(packageName)
                }
            }

            OobActionSchema.TOOL_PRESS_KEY -> {
                val key = stringArg(args, OobActionSchema.ARG_KEY).lowercase()
                val targetDescription = stringArg(args, OobActionSchema.ARG_TARGET_DESCRIPTION)
                val x = numberArg(args, OobActionSchema.ARG_X)?.toFloat()
                val y = numberArg(args, OobActionSchema.ARG_Y)?.toFloat()
                val nodeResourceId = stringArg(
                    args,
                    OobActionSchema.ARG_NODE_RESOURCE_ID,
                    OobActionSchema.ARG_NODE_ID,
                )
                when (key) {
                    "back" -> deviceOperator.goBack()
                    "home" -> deviceOperator.goHome()
                    "enter" -> if (
                        deviceOperator is TargetedInputDeviceOperator &&
                        (targetDescription.isNotBlank() || nodeResourceId.isNotBlank() || x != null || y != null)
                    ) {
                        deviceOperator.pressImeEnterAtTarget(
                            targetDescription = targetDescription,
                            x = x,
                            y = y,
                            nodeResourceId = nodeResourceId,
                        )
                    } else {
                        deviceOperator.pressHotKey("ENTER")
                    }
                    else -> OperationResult(false, "press_key requires key=back/home/enter", null)
                }
            }

            OobActionSchema.TOOL_WAIT -> {
                val waitMs = longArg(
                    args,
                    OobActionSchema.ARG_DURATION_MS,
                    defaultValue = -1L,
                )
                if (waitMs < 0L) {
                    return OperationResult(false, "wait requires duration_ms", null)
                }
                val clamped = waitMs.coerceIn(0L, MAX_CANONICAL_WAIT_MS)
                waitInterruptibly(clamped, stopRequested)
                OperationResult(true, "等待 ${clamped}ms 完成", null)
            }

            OobActionSchema.TOOL_FINISHED -> OperationResult(true, "任务完成", null)

            else -> OperationResult(false, "Unsupported action: $action", null)
        }
    }

    private suspend fun dispatchSwipe(args: Map<String, Any?>): OperationResult {
        val x1 = numberArg(args, OobActionSchema.ARG_X1)?.toFloat()
        val y1 = numberArg(args, OobActionSchema.ARG_Y1)?.toFloat()
        val x2 = numberArg(args, OobActionSchema.ARG_X2)?.toFloat()
        val y2 = numberArg(args, OobActionSchema.ARG_Y2)?.toFloat()
        val durationMs = longArg(args, OobActionSchema.ARG_DURATION_MS, defaultValue = 300L)
        if (x1 == null || y1 == null || x2 == null || y2 == null) {
            return OperationResult(false, "swipe requires x1/y1/x2/y2", null)
        }
        return deviceOperator.slideCoordinate(x1, y1, x2, y2, durationMs)
    }

    private fun physicalArgs(
        action: String,
        args: Map<String, Any?>,
    ): Map<String, Any?> {
        val hasX = args.containsKey(OobActionSchema.ARG_X)
        val hasY = args.containsKey(OobActionSchema.ARG_Y)
        if (action == OobActionSchema.TOOL_INPUT_TEXT && !hasX && !hasY) return args
        require(action != OobActionSchema.TOOL_INPUT_TEXT || hasX == hasY) {
            "canonical_action_target_coordinates_incomplete"
        }
        if (action !in OobActionSchema.coordinateToolNames) return args
        return CanonicalActionConverter.toScreenPixels(
            tool = action,
            args = args,
            displaySize = CanonicalActionConverter.DisplaySize(
                width = deviceOperator.getDisplayWidth().toDouble(),
                height = deviceOperator.getDisplayHeight().toDouble(),
            ),
        )
    }

    private suspend fun waitInterruptibly(
        durationMs: Long,
        stopRequested: (() -> Boolean)?,
    ) {
        var remainingMs = durationMs.coerceAtLeast(0L)
        while (remainingMs > 0L) {
            throwIfStopRequested(stopRequested)
            val chunkMs = remainingMs.coerceAtMost(STOP_POLL_INTERVAL_MS)
            delay(chunkMs)
            remainingMs -= chunkMs
        }
        throwIfStopRequested(stopRequested)
    }

    private fun throwIfStopRequested(stopRequested: (() -> Boolean)?) {
        if (stopRequested?.invoke() == true) {
            throw ActionStoppedException("Function execution stopped manually")
        }
    }

    private fun actionSuccessMessage(tool: String, args: Map<String, Any?>): String =
        when (tool) {
            OobActionSchema.TOOL_CLICK -> "点击 ${args["target_description"].orEmpty()} 成功"
            OobActionSchema.TOOL_LONG_PRESS -> "长按 ${args["target_description"].orEmpty()} 成功"
            OobActionSchema.TOOL_INPUT_TEXT -> "输入文本成功"
            OobActionSchema.TOOL_SWIPE -> "滑动成功"
            OobActionSchema.TOOL_OPEN_APP -> "打开应用成功"
            OobActionSchema.TOOL_PRESS_KEY -> "按键成功"
            OobActionSchema.TOOL_WAIT -> "等待完成"
            else -> "动作执行成功"
        }

    private fun Any?.orEmpty(): String = this?.toString() ?: ""

    private fun Map<String, Any?>.toStringDiagnostics(): Map<String, String> =
        mapValues { (_, value) -> value?.toString().orEmpty() }
            .filterValues { it.isNotBlank() }

    private fun actionFailureDiagnostics(message: String): Map<String, String> =
        linkedMapOf(
            "error" to message,
            "local_action_error_code" to message.substringBefore(':').trim()
                .takeIf { it.startsWith("OOB_") }
                .orEmpty(),
        ).filterValues { it.isNotBlank() }

    private companion object {
        private const val SOURCE_AGENT_ACTION = "agent_local_action"
        private const val CLICK_POST_DELAY_MS = 300L
        private const val KEY_POST_DELAY_MS = 250L
        private const val INPUT_FOCUS_DELAY_MS = 250L
        private const val INPUT_TEXT_POST_DELAY_MS = 650L
        private const val LONG_PRESS_POST_DELAY_MS = 650L
        private const val SWIPE_POST_DELAY_MS = 800L
        private const val OPEN_APP_POST_DELAY_MS = 1000L
        private const val STOP_POLL_INTERVAL_MS = 100L
        fun resolveActionName(raw: String): String? =
            OobActionSchema.canonicalToolName(raw)?.takeIf { it in OobActionSchema.replayableToolNames }

        fun numberArg(args: Map<String, Any?>, vararg keys: String): Double? {
            for (key in keys) {
                val value = args[key] ?: continue
                val parsed = when (value) {
                    is Number -> value.toDouble()
                    is String -> value.trim().toDoubleOrNull()
                    else -> value.toString().trim().toDoubleOrNull()
                }
                if (parsed != null) return parsed
            }
            return null
        }

        fun floatArg(args: Map<String, Any?>, key: String, defaultValue: Float): Float =
            numberArg(args, key)?.toFloat() ?: defaultValue

        fun longArg(args: Map<String, Any?>, vararg keys: String, defaultValue: Long): Long =
            numberArg(args, *keys)?.toLong() ?: defaultValue

        fun stringArg(args: Map<String, Any?>, vararg keys: String): String {
            for (key in keys) {
                val text = args[key]?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) return text
            }
            return ""
        }

        fun postActionDelayMs(tool: String): Long = when (tool) {
            OobActionSchema.TOOL_CLICK -> CLICK_POST_DELAY_MS
            OobActionSchema.TOOL_PRESS_KEY -> KEY_POST_DELAY_MS
            OobActionSchema.TOOL_INPUT_TEXT -> INPUT_TEXT_POST_DELAY_MS
            OobActionSchema.TOOL_LONG_PRESS -> LONG_PRESS_POST_DELAY_MS
            OobActionSchema.TOOL_SWIPE -> SWIPE_POST_DELAY_MS
            OobActionSchema.TOOL_OPEN_APP -> OPEN_APP_POST_DELAY_MS
            else -> 0L
        }
    }
}

data class VLMFunctionRunContext(
    val taskId: String = "",
    val runId: String = "",
)

class ActionStoppedException(message: String) : IllegalStateException(message)
