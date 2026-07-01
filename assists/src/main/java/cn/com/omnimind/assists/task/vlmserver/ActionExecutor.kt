package cn.com.omnimind.assists.task.vlmserver

/**
 * 动作执行器 - 负责执行UI操作动作
 * 对应Python中的 act 方法
 */

import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.baselib.runlog.OobActionSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json

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

fun interface FunctionRunExecutor {
    suspend fun run(action: FunctionRunAction, context: VLMFunctionRunContext): OperationResult
}

class ActionExecutor(
    private val deviceOperator: DeviceOperator,
    private val contextManager: UIContextManager,
    private val functionRunExecutor: FunctionRunExecutor? = null,
) {
    private val TAG = "ActionExecutor"
    private val json = Json { ignoreUnknownKeys = true }

    data class ActArgsResult(
        val args: Map<String, Any?>,
        val diagnostics: Map<String, Any?> = emptyMap(),
    )

    data class ActCheckConfig(
        val step: Map<String, Any?>? = null,
        val stopRequested: (() -> Boolean)? = null,
        val checker: (suspend (action: String, args: Map<String, Any?>) -> Map<String, Any?>)? = null,
        val actionTransfer: (suspend (action: String, args: Map<String, Any?>) -> ActArgsResult)? = null,
    )

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
        val actionBodyStart = System.currentTimeMillis()
        val rawResult = when (val action = vlmStep.action) {
            is ClickAction -> {
                executeCanonicalAction(
                    tool = OobActionSchema.TOOL_CLICK,
                    args = linkedMapOf(
                        "target_description" to action.targetDescription,
                        "x" to action.x,
                        "y" to action.y,
                    ),
                    source = "vlm_online",
                    context = functionRunContext,
                )
            }

            is LongPressAction -> {
                executeCanonicalAction(
                    tool = OobActionSchema.TOOL_LONG_PRESS,
                    args = linkedMapOf(
                        "target_description" to action.targetDescription,
                        "x" to action.x,
                        "y" to action.y,
                    ),
                    source = "vlm_online",
                    context = functionRunContext,
                )
            }

            is InputTextAction -> {
                executeCanonicalAction(
                    tool = OobActionSchema.TOOL_INPUT_TEXT,
                    args = linkedMapOf(
                        "target_description" to action.targetDescription,
                        "text" to action.text,
                        "x" to action.x,
                        "y" to action.y,
                        "node_id" to action.nodeId,
                    ).filterValues { it != null },
                    source = "vlm_online",
                    context = functionRunContext,
                )
            }

            is SwipeAction -> {
                executeCanonicalAction(
                    tool = OobActionSchema.TOOL_SWIPE,
                    args = linkedMapOf(
                        "target_description" to action.targetDescription,
                        "x1" to action.x1,
                        "y1" to action.y1,
                        "x2" to action.x2,
                        "y2" to action.y2,
                        "duration_ms" to action.durationMs,
                        "direction" to action.direction,
                        "scrollable_index" to action.scrollableIndex,
                    ).filterValues { it != null },
                    source = "vlm_online",
                    context = functionRunContext,
                )
            }

            is OpenAppAction -> {
                executeCanonicalAction(
                    tool = OobActionSchema.TOOL_OPEN_APP,
                    args = mapOf("package_name" to action.packageName),
                    source = "vlm_online",
                    context = functionRunContext,
                )
            }

            is PressKeyAction -> {
                executeCanonicalAction(
                    tool = OobActionSchema.TOOL_PRESS_KEY,
                    args = mapOf("key" to action.key),
                    source = "vlm_online",
                    context = functionRunContext,
                )
            }

            is WaitAction -> {
                val waitMs = action.durationMs
                    ?: ((action.timeS ?: 1.0).coerceAtLeast(0.0) * 1000.0).toLong()
                executeCanonicalAction(
                    tool = OobActionSchema.TOOL_WAIT,
                    args = linkedMapOf(
                        "time_s" to action.timeS,
                        "duration_ms" to waitMs,
                    ).filterValues { it != null },
                    source = "vlm_online",
                    context = functionRunContext,
                )
            }

            is GetStateAction -> {
                OperationResult(
                    success = true,
                    message = action.reason.ifBlank { "已重新获取当前页面状态" },
                    data = null
                )
            }

            is FunctionRunAction -> {
                functionRunExecutor?.run(action, functionRunContext)
                    ?: OperationResult(
                        success = false,
                        message = "复用指令执行器未注册",
                        data = null,
                    )
            }

            is RecordAction -> {
                // 特殊处理：记录动作不调用设备，返回成功结果
                OperationResult(
                    success = true,
                    message = "记忆关键信息成功",
                    data = null
                )
            }

            is FinishedAction -> {
                OperationResult(
                    success = true,
                    message = action.content.ifEmpty { "任务完成" },
                    data = null
                )
            }

            is InfoAction -> {
                OperationResult(
                    success = true,
                    message = "Agent询问: ${action.value}",
                    data = null
                )
            }

            is AbortAction -> {
                OperationResult(
                    success = true,
                    message = "任务终止: ${action.value}",
                    data = null
                )
            }
        }

        val actionBodyMs = System.currentTimeMillis() - actionBodyStart
        val postDelayMs = if (rawResult.success) postActionDelayMs(vlmStep.action) else 0L
        if (postDelayMs > 0) {
            ensureActionActive()
            kotlinx.coroutines.delay(postDelayMs)
        }
        val totalMs = System.currentTimeMillis() - actionStart
        val result = rawResult.copy(
            diagnostics = rawResult.diagnostics + linkedMapOf(
                "action_executor_action_ms" to actionBodyMs.toString(),
                "action_executor_post_delay_ms" to postDelayMs.toString(),
                "action_executor_total_ms" to totalMs.toString(),
            )
        )
        OmniLog.i(
            "TimeRecord",
            "VLM-actionExecutor ${vlmStep.action.name} took $totalMs ms (actionMs=$actionBodyMs postDelayMs=$postDelayMs)"
        )

        return UIStep(
            observation = vlmStep.observation,
            thought = vlmStep.thought,
            action = vlmStep.action,
            result = if (result.success) result.message else "执行失败: ${result.message}",
            actionResultData = result.data,
            pageDiagnostics = result.diagnostics,
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
        check: ActCheckConfig? = null,
        source: String = SOURCE_AGENT_ACTION,
        diagnostics: Map<String, Any?> = emptyMap(),
    ): OperationResult {
        return try {
            throwIfStopRequested(check)
            val checkDiagnostics = linkedMapOf<String, Any?>()
            var effectiveAction = resolveActionName(action) ?: OobActionSchema.normalizeToolName(action)
            var effectiveArgs = canonicalActionArgs(effectiveAction, args)

            check?.checker?.let { checker ->
                val checkerResults = mutableListOf<Map<String, Any?>>()
                for (attempt in 0 until CHECKER_STABILIZE_LIMIT) {
                    val result = checker(effectiveAction, effectiveArgs)
                    if (result.isNotEmpty()) checkerResults += result
                    throwIfStopRequested(check)
                    if (!checkerChangedPage(result)) {
                        break
                    }
                    delay(CHECKER_SETTLE_MS)
                    throwIfStopRequested(check)
                }
                when (checkerResults.size) {
                    0 -> Unit
                    1 -> checkDiagnostics["checker"] = checkerResults.first()
                    else -> checkDiagnostics["checker"] = checkerResults
                }
            }

            check?.actionTransfer?.let { transfer ->
                val result = transfer(effectiveAction, effectiveArgs)
                effectiveArgs = canonicalActionArgs(effectiveAction, result.args)
                if (result.diagnostics.isNotEmpty()) {
                    checkDiagnostics["action_transfer"] = result.diagnostics
                }
                throwIfStopRequested(check)
            }

            val dispatchResult = dispatchCanonical(effectiveAction, effectiveArgs)
            val mergedDiagnostics = diagnostics + checkDiagnostics + mapOf("action_source" to source)
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
        source: String,
        context: VLMFunctionRunContext,
    ): OperationResult {
        return try {
            val enrichedArgs = linkedMapOf<String, Any?>().apply {
                putAll(args)
                if (context.taskId.isNotBlank()) put("task_id", context.taskId)
                if (context.runId.isNotBlank()) put("run_id", context.runId)
            }
            val dispatchResult = act(
                action = tool,
                args = enrichedArgs,
                source = source,
            )
            if (dispatchResult.success) {
                dispatchResult.copy(message = actionSuccessMessage(tool, args))
            } else {
                dispatchResult
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

    private suspend fun dispatchCanonical(
        action: String,
        args: Map<String, Any?>,
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
                    "resource_id",
                    "nodeResourceId",
                )
                if (deviceOperator is AndroidDeviceOperator) {
                    return deviceOperator.inputText(
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
                    "packageName",
                    "package",
                )
                if (packageName.isBlank()) {
                    OperationResult(false, "open_app requires package_name", null)
                } else {
                    deviceOperator.launchApplication(packageName)
                }
            }

            OobActionSchema.TOOL_PRESS_KEY -> when (stringArg(args, OobActionSchema.ARG_KEY).lowercase()) {
                "back" -> deviceOperator.goBack()
                "home" -> deviceOperator.goHome()
                "enter" -> deviceOperator.pressHotKey("ENTER")
                else -> OperationResult(false, "press_key requires key=back/home/enter", null)
            }

            OobActionSchema.TOOL_WAIT -> {
                val waitMs = longArg(
                    args,
                    OobActionSchema.ARG_TIME_MS,
                    OobActionSchema.ARG_DURATION_MS,
                    defaultValue = -1L,
                ).takeIf { it >= 0L }
                    ?: ((numberArg(args, OobActionSchema.ARG_TIME_S) ?: 1.0)
                        .coerceAtLeast(0.0) * 1000.0).toLong()
                val clamped = waitMs.coerceIn(0L, MAX_WAIT_MS)
                delay(clamped)
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
        if (x1 != null && y1 != null && x2 != null && y2 != null) {
            return deviceOperator.slideCoordinate(x1, y1, x2, y2, durationMs)
        }

        val direction = stringArg(args, OobActionSchema.ARG_DIRECTION).ifBlank { "down" }
        val distance = floatArg(args, OobActionSchema.ARG_DISTANCE, defaultValue = 300f)
        val x = floatArg(args, OobActionSchema.ARG_X, defaultValue = deviceOperator.getDisplayWidth() / 2f)
        val y = floatArg(args, OobActionSchema.ARG_Y, defaultValue = deviceOperator.getDisplayHeight() / 2f)
        val half = (distance / 2f).coerceAtLeast(1f)
        val (startX, startY, endX, endY) = when (direction.lowercase()) {
            "up" -> listOf(x, y + half, x, y - half)
            "left" -> listOf(x + half, y, x - half, y)
            "right" -> listOf(x - half, y, x + half, y)
            else -> listOf(x, y - half, x, y + half)
        }
        return deviceOperator.slideCoordinate(startX, startY, endX, endY, durationMs)
    }

    private fun canonicalActionArgs(
        action: String,
        args: Map<String, Any?>,
    ): Map<String, Any?> {
        if (action != OobActionSchema.TOOL_OPEN_APP) return args
        val packageName = firstNonBlank(args["package_name"], args["packageName"], args["package"])
        if (packageName.isBlank()) return args
        return linkedMapOf<String, Any?>().apply {
            putAll(args)
            put("package_name", packageName)
        }
    }

    private fun throwIfStopRequested(check: ActCheckConfig?) {
        if (check?.stopRequested?.invoke() == true) {
            throw ActionStoppedException("Function execution stopped manually")
        }
    }

    private fun checkerChangedPage(result: Map<String, Any?>): Boolean {
        if (result.isEmpty()) return false
        if (result["executed"] == true || result["effect"] == "run_actions") return true
        val effects = result["effects"] as? Iterable<*> ?: return false
        return effects.any { effect ->
            val map = effect as? Map<*, *> ?: return@any false
            map["effect"] == "run_actions" || map["executed"] == true
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
        private const val FUNCTION_RUN_POST_DELAY_MS = 500L
        private const val MAX_WAIT_MS = 10_000L
        private const val CHECKER_STABILIZE_LIMIT = 3
        private const val CHECKER_SETTLE_MS = 1_000L

        fun resolveActionName(raw: String): String? =
            OobActionSchema.canonicalToolName(raw)?.takeIf { it in OobActionSchema.replayableToolNames }

        fun firstNonBlank(vararg values: Any?): String {
            for (value in values) {
                val text = value?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) return text
            }
            return ""
        }

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

        fun postActionDelayMs(action: UIAction): Long =
            when (action) {
                is ClickAction -> CLICK_POST_DELAY_MS
                is PressKeyAction -> KEY_POST_DELAY_MS
                is InputTextAction -> INPUT_TEXT_POST_DELAY_MS
                is LongPressAction -> LONG_PRESS_POST_DELAY_MS
                is SwipeAction -> SWIPE_POST_DELAY_MS
                is OpenAppAction -> OPEN_APP_POST_DELAY_MS
                is FunctionRunAction -> FUNCTION_RUN_POST_DELAY_MS
                else -> 0L
            }
    }
}

data class VLMFunctionRunContext(
    val taskId: String = "",
    val runId: String = "",
)

class ActionStoppedException(message: String) : IllegalStateException(message)
