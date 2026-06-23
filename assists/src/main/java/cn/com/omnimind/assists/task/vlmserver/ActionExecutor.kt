package cn.com.omnimind.assists.task.vlmserver

/**
 * 动作执行器 - 负责执行UI操作动作
 * 对应Python中的 act 方法
 */

import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.baselib.runlog.OobCanonicalActionSchema
import cn.com.omnimind.baselib.runlog.OobPrimitiveActionLedger
import cn.com.omnimind.baselib.runlog.OobPrimitiveActionRecord
import cn.com.omnimind.baselib.runlog.OobPrimitiveActionRiskPolicy
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

interface DeviceOperator {
    suspend fun clickCoordinate(x: Float, y: Float): OperationResult
    suspend fun clickNodeById(nodeId: String, targetDescription: String = ""): OperationResult =
        OperationResult(false, "组件点击不支持: node_id=$nodeId", null)
    suspend fun longClickCoordinate(x: Float, y: Float, duration: Long = 1000L): OperationResult
    suspend fun longClickNodeById(
        nodeId: String,
        targetDescription: String = "",
        duration: Long = 1000L,
    ): OperationResult = OperationResult(false, "组件长按不支持: node_id=$nodeId", null)
    suspend fun inputText(text: String): OperationResult
    suspend fun inputTextToNodeById(
        nodeId: String,
        text: String,
        targetDescription: String = "",
    ): OperationResult = OperationResult(false, "组件输入不支持: node_id=$nodeId", null)
    suspend fun pressHotKey(key: String): OperationResult
    suspend fun copyToClipboard(text: String): OperationResult
    suspend fun getClipboard(): String? // 获取剪贴板内容
    suspend fun slideCoordinate(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long): OperationResult
    suspend fun slideCoordinateWithContext(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        duration: Long,
        targetDescription: String,
    ): OperationResult {
        return slideCoordinate(x1, y1, x2, y2, duration)
    }
    suspend fun goHome(): OperationResult
    suspend fun goBack(): OperationResult
    suspend fun launchApplication(packageName: String): OperationResult
    suspend fun captureScreenshot(): String // 返回base64编码的截图
    fun getLastScreenshotWidth(): Int // 获取最后一次截图的宽度
    fun getLastScreenshotHeight(): Int // 获取最后一次截图的高度
    fun getDisplayWidth(): Int // 设备实际屏幕宽度
    fun getDisplayHeight(): Int // 设备实际屏幕高度
    suspend fun showInfo(message: String)

}

class ActionExecutor(
    private val deviceOperator: DeviceOperator,
    private val contextManager: UIContextManager
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
        val actionBodyStart = System.currentTimeMillis()
        val rawResult = when (val action = vlmStep.action) {
            is ClickAction -> {
                executePrimitive(
                    tool = OobCanonicalActionSchema.TOOL_CLICK,
                    args = linkedMapOf(
                        "target_description" to action.targetDescription,
                        "x" to action.x,
                        "y" to action.y,
                        "node_id" to action.nodeId,
                    ).filterValues { it != null },
                    functionRunContext = functionRunContext,
                    vlmStep = vlmStep,
                ) {
                    deviceOperator.clickCoordinate(action.x, action.y)
                }
            }

            is LongPressAction -> {
                executePrimitive(
                    tool = OobCanonicalActionSchema.TOOL_LONG_PRESS,
                    args = linkedMapOf(
                        "target_description" to action.targetDescription,
                        "x" to action.x,
                        "y" to action.y,
                        "node_id" to action.nodeId,
                    ).filterValues { it != null },
                    functionRunContext = functionRunContext,
                    vlmStep = vlmStep,
                ) {
                    deviceOperator.longClickCoordinate(action.x, action.y)
                }
            }

            is InputTextAction -> {
                executePrimitive(
                    tool = OobCanonicalActionSchema.TOOL_INPUT_TEXT,
                    args = linkedMapOf(
                        "target_description" to action.targetDescription,
                        "text" to action.text,
                        "x" to action.x,
                        "y" to action.y,
                        "node_id" to action.nodeId,
                    ).filterValues { it != null },
                    functionRunContext = functionRunContext,
                    vlmStep = vlmStep,
                ) {
                    val nodeId = action.nodeId?.trim().orEmpty()
                    val nodeInputResult = if (nodeId.isNotEmpty()) {
                        deviceOperator.inputTextToNodeById(
                            nodeId = nodeId,
                            text = action.text,
                            targetDescription = action.targetDescription
                        )
                    } else {
                        null
                    }
                    if (nodeInputResult?.success == true) {
                        nodeInputResult
                    } else {
                        val clickResult = deviceOperator.clickCoordinate(action.x, action.y)
                        if (!clickResult.success) {
                            clickResult
                        } else {
                            ensureActionActive()
                            kotlinx.coroutines.delay(INPUT_TEXT_FOCUS_DELAY_MS)
                            val inputResult = deviceOperator.inputText(action.text)
                            if (inputResult.success) {
                                OperationResult(
                                    success = true,
                                    message = "点击 ${action.targetDescription} 并输入文本成功",
                                    data = inputResult.data
                                )
                            } else {
                                inputResult
                            }
                        }
                    }
                }
            }

            is SwipeAction -> {
                executePrimitive(
                    tool = OobCanonicalActionSchema.TOOL_SWIPE,
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
                    functionRunContext = functionRunContext,
                    vlmStep = vlmStep,
                ) {
                    deviceOperator.slideCoordinateWithContext(
                        action.x1,
                        action.y1,
                        action.x2,
                        action.y2,
                        action.durationMs,
                        action.targetDescription
                    )
                }
            }

            is OpenAppAction -> {
                executePrimitive(
                    tool = OobCanonicalActionSchema.TOOL_OPEN_APP,
                    args = mapOf("package_name" to action.packageName),
                    functionRunContext = functionRunContext,
                    vlmStep = vlmStep,
                ) {
                    deviceOperator.launchApplication(action.packageName)
                }
            }

            is PressKeyAction -> {
                executePrimitive(
                    tool = OobCanonicalActionSchema.TOOL_PRESS_KEY,
                    args = mapOf("key" to action.key),
                    functionRunContext = functionRunContext,
                    vlmStep = vlmStep,
                ) {
                    when (action.key.trim().lowercase()) {
                        "home" -> deviceOperator.goHome()
                        "back" -> deviceOperator.goBack()
                        "enter" -> deviceOperator.pressHotKey("ENTER")
                        else -> OperationResult(
                            success = false,
                            message = "不支持的按键: ${action.key}",
                            data = null
                        )
                    }
                }
            }

            is WaitAction -> {
                val waitMs = action.durationMs
                    ?: ((action.timeS ?: 1.0).coerceAtLeast(0.0) * 1000.0).toLong()
                executePrimitive(
                    tool = OobCanonicalActionSchema.TOOL_WAIT,
                    args = linkedMapOf(
                        "time_s" to action.timeS,
                        "duration_ms" to waitMs,
                    ).filterValues { it != null },
                    functionRunContext = functionRunContext,
                    vlmStep = vlmStep,
                ) {
                    kotlinx.coroutines.delay(waitMs.coerceIn(0L, MAX_WAIT_ACTION_MS))
                    OperationResult(
                        success = true,
                        message = "等待 ${waitMs.coerceIn(0L, MAX_WAIT_ACTION_MS)}ms 完成",
                        data = null,
                    )
                }
            }

            is GetStateAction -> {
                OperationResult(
                    success = true,
                    message = action.reason.ifBlank { "已重新获取当前页面状态" },
                    data = null
                )
            }

            is FunctionRunAction -> {
                VLMFunctionRunRegistry.run(
                    VLMFunctionRunRequest(
                        functionId = action.functionId,
                        arguments = action.arguments,
                        taskId = functionRunContext.taskId,
                        runId = functionRunContext.runId,
                    )
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

    private suspend fun executePrimitive(
        tool: String,
        args: Map<String, Any?>,
        functionRunContext: VLMFunctionRunContext,
        vlmStep: UIStep,
        block: suspend () -> OperationResult,
    ): OperationResult {
        val startedAtMs = System.currentTimeMillis()
        val snapshot = primitiveSnapshot(vlmStep)
        val risk = OobPrimitiveActionRiskPolicy.evaluate(
            tool = tool,
            args = args,
            pageXml = snapshot.xml,
            packageName = snapshot.packageName,
            activityName = "",
        )
        if (!risk.allowed) {
            val finishedAtMs = System.currentTimeMillis()
            OobPrimitiveActionLedger.record(
                OobPrimitiveActionRecord(
                    source = "vlm_online",
                    tool = tool,
                    args = args,
                    taskId = functionRunContext.taskId,
                    runId = functionRunContext.runId,
                    packageName = snapshot.packageName,
                    beforeXmlSha256 = OobPrimitiveActionLedger.xmlSha256(snapshot.xml),
                    beforeXmlChars = snapshot.xml.length,
                    startedAtMs = startedAtMs,
                    finishedAtMs = finishedAtMs,
                    success = false,
                    blocked = true,
                    errorCode = risk.errorCode,
                    errorMessage = risk.reason,
                    diagnostics = risk.diagnostics(),
                )
            )
            return OperationResult(
                success = false,
                message = "${risk.errorCode}: ${risk.reason}",
                data = null,
                diagnostics = risk.diagnostics(),
            )
        }

        return try {
            val result = block()
            val finishedAtMs = System.currentTimeMillis()
            OobPrimitiveActionLedger.record(
                OobPrimitiveActionRecord(
                    source = "vlm_online",
                    tool = tool,
                    args = args,
                    taskId = functionRunContext.taskId,
                    runId = functionRunContext.runId,
                    packageName = snapshot.packageName,
                    beforeXmlSha256 = OobPrimitiveActionLedger.xmlSha256(snapshot.xml),
                    beforeXmlChars = snapshot.xml.length,
                    startedAtMs = startedAtMs,
                    finishedAtMs = finishedAtMs,
                    success = result.success,
                    errorCode = if (result.success) "" else "OOB_PRIMITIVE_ACTION_FAILED",
                    errorMessage = if (result.success) "" else result.message,
                    diagnostics = result.diagnostics,
                )
            )
            result
        } catch (error: Exception) {
            val finishedAtMs = System.currentTimeMillis()
            OobPrimitiveActionLedger.record(
                OobPrimitiveActionRecord(
                    source = "vlm_online",
                    tool = tool,
                    args = args,
                    taskId = functionRunContext.taskId,
                    runId = functionRunContext.runId,
                    packageName = snapshot.packageName,
                    beforeXmlSha256 = OobPrimitiveActionLedger.xmlSha256(snapshot.xml),
                    beforeXmlChars = snapshot.xml.length,
                    startedAtMs = startedAtMs,
                    finishedAtMs = finishedAtMs,
                    success = false,
                    errorCode = "OOB_PRIMITIVE_ACTION_EXCEPTION",
                    errorMessage = error.message.orEmpty(),
                )
            )
            throw error
        }
    }

    private fun primitiveSnapshot(vlmStep: UIStep): PrimitiveSnapshot =
        PrimitiveSnapshot(
            xml = vlmStep.observationXml.orEmpty(),
            packageName = vlmStep.packageName.orEmpty(),
        )

    private data class PrimitiveSnapshot(
        val xml: String,
        val packageName: String,
    )

    private companion object {
        private const val INPUT_TEXT_FOCUS_DELAY_MS = 350L
        private const val CLICK_POST_DELAY_MS = 300L
        private const val KEY_POST_DELAY_MS = 250L
        private const val INPUT_TEXT_POST_DELAY_MS = 650L
        private const val LONG_PRESS_POST_DELAY_MS = 650L
        private const val SWIPE_POST_DELAY_MS = 800L
        private const val OPEN_APP_POST_DELAY_MS = 1000L
        private const val FUNCTION_RUN_POST_DELAY_MS = 500L
        private const val MAX_WAIT_ACTION_MS = 10_000L

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
