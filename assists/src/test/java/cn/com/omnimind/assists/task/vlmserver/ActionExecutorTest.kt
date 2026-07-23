package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.runlog.OobActionSchema
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionExecutorTest {
    @Test
    fun localOpenAppRejectsCamelCasePackageAlias() = runBlocking {
        val device = FakeTargetedInputDeviceOperator()

        val result = executor(device).act(
            action = OobActionSchema.TOOL_OPEN_APP,
            args = mapOf("packageName" to "demo.app"),
        )

        assertFalse(result.success)
        assertTrue(result.message.contains("open_app requires package_name"))
        assertEquals(0, device.launchedPackages.size)
    }

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
        assertEquals(129.6f, device.targetedInput?.x ?: 0f, 0.001f)
        assertEquals(460.8f, device.targetedInput?.y ?: 0f, 0.001f)
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

    @Test
    fun localClickConvertsCanonicalCoordinatesExactlyOnce() = runBlocking {
        val device = FakeTargetedInputDeviceOperator()

        val result = executor(device).act(
            action = OobActionSchema.TOOL_CLICK,
            args = mapOf(
                OobActionSchema.ARG_X to 900,
                OobActionSchema.ARG_Y to 75,
            ),
        )

        assertTrue(result.success)
        assertEquals(972f, device.clickedX ?: 0f, 0.001f)
        assertEquals(144f, device.clickedY ?: 0f, 0.001f)
    }

    @Test
    fun localClickRejectsNonCanonicalCoordinates() = runBlocking {
        val device = FakeTargetedInputDeviceOperator()

        val result = executor(device).act(
            action = OobActionSchema.TOOL_CLICK,
            args = mapOf(
                OobActionSchema.ARG_X to 1250,
                OobActionSchema.ARG_Y to 75,
            ),
        )

        assertFalse(result.success)
        assertEquals(null, device.clickedX)
        assertTrue(result.message.contains("canonical_action_arg_range_invalid:x"))
    }

    @Test
    fun vlmInputDispatchesDirectlyToTargetAwareDeviceOperation() = runBlocking {
        val device = FakeTargetedInputDeviceOperator()
        val executor = ActionExecutor(
            deviceOperator = device,
            contextManager = UIContextManager(),
        )

        val requestedState = State(
            stateId = "requested",
            xml = "<requested />",
            packageName = "demo.requested",
        )
        val step = executor.act(
            UIStep(
                observation = "",
                thought = "",
                action = actionOf(
                    OobActionSchema.TOOL_INPUT_TEXT,
                    mapOf(
                        OobActionSchema.ARG_TARGET_DESCRIPTION to "搜索框",
                        OobActionSchema.ARG_TEXT to "hello",
                        OobActionSchema.ARG_X to 500,
                        OobActionSchema.ARG_Y to 500,
                    ),
                ),
                beforeState = requestedState,
            )
        )

        assertFalse(step.result.orEmpty().startsWith(ACTION_FAILURE_PREFIX))
        assertEquals("搜索框", device.targetedInput?.targetDescription)
        assertEquals("hello", device.targetedInput?.text)
        assertEquals(540f, device.targetedInput?.x ?: 0f, 0.001f)
        assertEquals(960f, device.targetedInput?.y ?: 0f, 0.001f)
        assertEquals(0, device.genericInputCount)
        assertEquals(requestedState, step.beforeState)
        assertEquals("vlm_online", step.pageDiagnostics["action_source"])
    }

    @Test
    fun vlmClickConvertsCanonicalCoordinatesAtDeviceDispatch() = runBlocking {
        val device = FakeTargetedInputDeviceOperator()
        val executor = ActionExecutor(
            deviceOperator = device,
            contextManager = UIContextManager(),
        )

        val step = executor.act(
            UIStep(
                observation = "",
                thought = "",
                action = actionOf(
                    OobActionSchema.TOOL_CLICK,
                    mapOf(
                        OobActionSchema.ARG_TARGET_DESCRIPTION to "搜索按钮",
                        OobActionSchema.ARG_X to 900,
                        OobActionSchema.ARG_Y to 75,
                    ),
                ),
                beforeState = State(
                    stateId = "before",
                    xml = "<hierarchy />",
                    display = StateDisplay(720, 1280),
                ),
            ),
        )

        assertFalse(step.result.orEmpty().startsWith(ACTION_FAILURE_PREFIX))
        assertEquals(972f, device.clickedX ?: 0f, 0.001f)
        assertEquals(144f, device.clickedY ?: 0f, 0.001f)
        assertEquals("vlm_online", step.pageDiagnostics["action_source"])
    }

    @Test
    fun vlmActionFailureRemainsFailure() = runBlocking {
        val device = FakeTargetedInputDeviceOperator().apply {
            clickFailureMessage = "physical click failed"
        }
        val executor = ActionExecutor(
            deviceOperator = device,
            contextManager = UIContextManager(),
        )

        val step = executor.act(
            UIStep(
                observation = "",
                thought = "",
                action = actionOf(
                    OobActionSchema.TOOL_CLICK,
                    mapOf(
                        OobActionSchema.ARG_TARGET_DESCRIPTION to "搜索按钮",
                        OobActionSchema.ARG_X to 500,
                        OobActionSchema.ARG_Y to 500,
                    ),
                ),
                beforeState = State(stateId = "before", xml = "<before />"),
            ),
        )

        assertTrue(step.result.orEmpty().startsWith(ACTION_FAILURE_PREFIX))
        assertTrue(step.result.orEmpty().contains("physical click failed"))
    }

    @Test
    fun functionInvocationDoesNotAddAnOuterSettleDelay() = runBlocking {
        val executor = ActionExecutor(
            deviceOperator = FakeTargetedInputDeviceOperator(),
            contextManager = UIContextManager(),
            functionRunExecutor = FunctionRunExecutor { _, _ ->
                OperationResult(success = true, message = "ok")
            },
        )

        val step = executor.act(
            UIStep(
                observation = "",
                thought = "",
                action = FunctionInvocation(
                    functionId = "search_product",
                ),
            )
        )

        assertFalse(step.result.orEmpty().startsWith(ACTION_FAILURE_PREFIX))
        assertFalse(step.pageDiagnostics.containsKey("action_executor_post_delay_ms"))
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
        val launchedPackages = mutableListOf<String>()
        var targetedInput: TargetCall? = null
        var targetedEnter: TargetCall? = null
        var genericInputCount = 0
        var hotKeyCount = 0
        var clickedX: Float? = null
        var clickedY: Float? = null
        var clickFailureMessage: String? = null

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

        override suspend fun clickCoordinate(x: Float, y: Float): OperationResult {
            clickedX = x
            clickedY = y
            return clickFailureMessage?.let { OperationResult(false, it) } ?: success()
        }
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
        override suspend fun launchApplication(packageName: String): OperationResult {
            launchedPackages += packageName
            return success()
        }
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
