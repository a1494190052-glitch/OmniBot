package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.runlog.OobActionSchema
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionExecutorTest {
    @Test
    fun inputTextUsesTargetAwareDeviceOperation() = runBlocking {
        val device = FakeTargetedInputDeviceOperator()
        val result = executor(device).act(
            action = OobActionSchema.TOOL_INPUT_TEXT,
            args = mapOf(
                OobActionSchema.ARG_TEXT to "hello",
                OobActionSchema.ARG_TARGET_DESCRIPTION to "搜索框",
                OobActionSchema.ARG_X to 120f,
                OobActionSchema.ARG_Y to 240f,
                OobActionSchema.ARG_NODE_RESOURCE_ID to "search_input",
            ),
            source = "manual_recording",
        )

        assertTrue(result.success)
        assertEquals("hello", device.targetedInput?.text)
        assertEquals("搜索框", device.targetedInput?.targetDescription)
        assertEquals("search_input", device.targetedInput?.nodeResourceId)
        assertEquals(0, device.genericInputCount)
        assertEquals("manual_recording", result.diagnostics["action_source"])
    }

    @Test
    fun enterUsesTargetAwareImeOperationWhenTargetExists() = runBlocking {
        val device = FakeTargetedInputDeviceOperator()
        val result = executor(device).act(
            action = OobActionSchema.TOOL_PRESS_KEY,
            args = mapOf(
                OobActionSchema.ARG_KEY to "enter",
                OobActionSchema.ARG_TARGET_DESCRIPTION to "消息输入框",
                OobActionSchema.ARG_X to 300f,
                OobActionSchema.ARG_Y to 700f,
            ),
            source = "manual_recording",
        )

        assertTrue(result.success)
        assertEquals("消息输入框", device.targetedEnter?.targetDescription)
        assertEquals(0, device.hotKeyCount)
    }

    @Test
    fun enterWithoutTargetUsesGenericHotKey() = runBlocking {
        val device = FakeTargetedInputDeviceOperator()
        val result = executor(device).act(
            action = OobActionSchema.TOOL_PRESS_KEY,
            args = mapOf(OobActionSchema.ARG_KEY to "enter"),
        )

        assertTrue(result.success)
        assertEquals(1, device.hotKeyCount)
        assertEquals(null, device.targetedEnter)
    }

    @Test
    fun canonicalWaitSupportsManualSixtySecondLimit() {
        assertEquals(60_000L, MAX_CANONICAL_WAIT_MS)
    }

    private fun executor(device: DeviceOperator): ActionExecutor = ActionExecutor(
        deviceOperator = device,
        contextManager = UIContextManager(),
    )

    private data class TargetCall(
        val text: String = "",
        val targetDescription: String,
        val x: Float?,
        val y: Float?,
        val nodeResourceId: String,
    )

    private class FakeTargetedInputDeviceOperator : TargetedInputDeviceOperator {
        var targetedInput: TargetCall? = null
        var targetedEnter: TargetCall? = null
        var genericInputCount = 0
        var hotKeyCount = 0

        override suspend fun inputTextAtTarget(
            text: String,
            targetDescription: String,
            x: Float?,
            y: Float?,
            nodeResourceId: String,
        ): OperationResult {
            targetedInput = TargetCall(text, targetDescription, x, y, nodeResourceId)
            return success()
        }

        override suspend fun pressImeEnterAtTarget(
            targetDescription: String,
            x: Float?,
            y: Float?,
            nodeResourceId: String,
        ): OperationResult {
            targetedEnter = TargetCall(
                targetDescription = targetDescription,
                x = x,
                y = y,
                nodeResourceId = nodeResourceId,
            )
            return success()
        }

        override suspend fun clickCoordinate(x: Float, y: Float) = success()
        override suspend fun longClickCoordinate(x: Float, y: Float, duration: Long) = success()
        override suspend fun inputText(text: String): OperationResult {
            genericInputCount += 1
            return success()
        }
        override suspend fun pressHotKey(key: String): OperationResult {
            hotKeyCount += 1
            return success()
        }
        override suspend fun copyToClipboard(text: String) = success()
        override suspend fun getClipboard(): String? = null
        override suspend fun slideCoordinate(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            duration: Long,
        ) = success()
        override suspend fun goHome() = success()
        override suspend fun goBack() = success()
        override suspend fun launchApplication(packageName: String) = success()
        override suspend fun captureScreenshot(): String = ""
        override fun getLastScreenshotWidth(): Int = 1080
        override fun getLastScreenshotHeight(): Int = 1920
        override fun getDisplayWidth(): Int = 1080
        override fun getDisplayHeight(): Int = 1920
        override suspend fun showInfo(message: String) = Unit
        override fun isReady(): Boolean = true
        override fun currentXml(): String? = null
        override fun currentPackageName(): String? = null
        override fun currentActivityName(): String? = null
        override suspend fun hideKeyboard() = success()

        private fun success() = OperationResult(true, "ok")
    }
}
