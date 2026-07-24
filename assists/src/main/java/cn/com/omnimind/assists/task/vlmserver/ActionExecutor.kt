package cn.com.omnimind.assists.task.vlmserver

/**
 * 动作执行器 - 负责执行UI操作动作
 * 对应Python中的 act 方法
 */

import cn.com.omnimind.baselib.runlog.ActionCoordinateCodec
import cn.com.omnimind.baselib.runlog.OobActionSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

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

class ActionExecutor(
    private val deviceOperator: DeviceOperator,
) {
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
        return ActionCoordinateCodec.toScreenPixels(
            args = args,
            displaySize = ActionCoordinateCodec.DisplaySize(
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

class ActionStoppedException(message: String) : IllegalStateException(message)
