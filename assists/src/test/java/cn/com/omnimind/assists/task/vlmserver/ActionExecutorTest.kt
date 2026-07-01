package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionExecutorTest {
    @Test
    fun `click dispatches canonical coordinates`() = runBlocking {
        withQuietLogs {
            val operator = FakeDeviceOperator()
            val executor = ActionExecutor(operator, UIContextManager())

            val result = executor.executeAction(
                UIStep(
                    observation = "",
                    thought = "tap settings row",
                    action = ClickAction(
                        targetDescription = "Network & internet",
                        x = 360f,
                        y = 625f,
                        nodeId = "20"
                    )
                )
            )

            assertEquals(listOf(360f to 625f), operator.clickedCoordinates)
            assertFalse(result.result.orEmpty().startsWith("执行失败"))
            assertTrue(result.result.orEmpty().contains("点击"))
        }
    }

    @Test
    fun `dangerous text is not blocked by removed risk gate`() = runBlocking {
        withQuietLogs {
            val operator = FakeDeviceOperator()
            val executor = ActionExecutor(operator, UIContextManager())

            val result = executor.executeAction(
                UIStep(
                    observation = "",
                    thought = "tap pay",
                    action = ClickAction(
                        targetDescription = "确认支付",
                        x = 600f,
                        y = 1600f,
                    ),
                    packageName = "com.example.pay",
                    observationXml = "<hierarchy />",
                )
            )

            assertEquals(listOf(600f to 1600f), operator.clickedCoordinates)
            assertFalse(result.result.orEmpty().startsWith("执行失败"))
        }
    }

    @Test
    fun `input text focuses coordinates then types`() = runBlocking {
        withQuietLogs {
            val operator = FakeDeviceOperator()
            val executor = ActionExecutor(operator, UIContextManager())

            val result = executor.executeAction(
                UIStep(
                    observation = "",
                    thought = "type first name",
                    action = InputTextAction(
                        targetDescription = "First name",
                        text = "Alice",
                        x = 356f,
                        y = 660f,
                        nodeId = "33"
                    )
                )
            )

            assertEquals(listOf(356f to 660f), operator.clickedCoordinates)
            assertEquals(listOf("Alice"), operator.focusedInputs)
            assertFalse(result.result.orEmpty().startsWith("执行失败"))
            assertTrue(result.result.orEmpty().contains("输入文本成功"))
        }
    }

    @Test
    fun `click post action delay is shorter than legacy one second settle`() = runBlocking {
        withQuietLogs {
            val operator = FakeDeviceOperator()
            val executor = ActionExecutor(operator, UIContextManager())

            val startedAt = System.currentTimeMillis()
            val result = executor.executeAction(
                UIStep(
                    observation = "",
                    thought = "tap search box",
                    action = ClickAction(
                        targetDescription = "Search box",
                        x = 360f,
                        y = 120f,
                    )
                )
            )
            val elapsedMs = System.currentTimeMillis() - startedAt

            assertFalse(result.result.orEmpty().startsWith("执行失败"))
            assertTrue("click elapsedMs=$elapsedMs", elapsedMs in 250L..900L)
            assertEquals("300", result.pageDiagnostics["action_executor_post_delay_ms"])
            assertTrue(
                result.pageDiagnostics["action_executor_total_ms"].orEmpty().toLong() >= 250L
            )
        }
    }

    @Test
    fun `action transfer failure stops before physical dispatch`() = runBlocking {
        withQuietLogs {
            val operator = FakeDeviceOperator()
            val executor = ActionExecutor(operator, UIContextManager())

            val result = executor.act(
                action = "click",
                args = mapOf("x" to 360f, "y" to 625f),
                source = "function_replay",
                check = ActionExecutor.ActCheckConfig(
                    actionTransfer = { _, _ ->
                        throw IllegalStateException(
                            "OOB_FUNCTION_SOURCE_NOT_REACHED: action transfer could not match the recorded source page: no_anchor_match"
                        )
                    },
                ),
            )

            assertFalse(result.success)
            assertTrue(result.message.contains("OOB_FUNCTION_SOURCE_NOT_REACHED"))
            assertEquals(emptyList<Pair<Float, Float>>(), operator.clickedCoordinates)
        }
    }

    @Test
    fun `function run action returns function payload as action result data`() = runBlocking {
        withQuietLogs {
            val operator = FakeDeviceOperator()
            val executor = ActionExecutor(
                operator,
                UIContextManager(),
                FunctionRunExecutor { action, _ ->
                    OperationResult(
                        success = true,
                        message = "function completed",
                        data = buildJsonObject {
                            put("function_id", JsonPrimitive(action.functionId))
                            put("fallback", JsonPrimitive(false))
                        }
                    )
                }
            )

            val result = executor.executeAction(
                UIStep(
                    observation = "",
                    thought = "reuse known flow",
                    action = FunctionRunAction(functionId = "order_takeout")
                )
            )

            assertEquals("function completed", result.result)
            assertEquals(
                JsonPrimitive("order_takeout"),
                result.actionResultData?.jsonObject?.get("function_id")
            )
        }
    }

    @Test
    fun `function run action forwards recalled workflow arguments to function executor`() = runBlocking {
        withQuietLogs {
            val operator = FakeDeviceOperator()
            var captured: FunctionRunAction? = null
            val executor = ActionExecutor(
                operator,
                UIContextManager(),
                FunctionRunExecutor { action, _ ->
                    captured = action
                    OperationResult(
                        success = true,
                        message = "function completed",
                        data = buildJsonObject {
                            put("function_id", action.functionId)
                            put("query", action.arguments["query"] ?: JsonPrimitive(""))
                        }
                    )
                }
            )

            val result = executor.executeAction(
                UIStep(
                    observation = "",
                    thought = "reuse xhs search flow",
                    action = FunctionRunAction(
                        functionId = "xhs_search",
                        toolName = "run_recalled_workflow_1",
                        arguments = buildJsonObject {
                            put("query", "狗狗")
                        }
                    )
                )
            )

            assertEquals("function completed", result.result)
            assertEquals("xhs_search", captured?.functionId)
            assertEquals("run_recalled_workflow_1", captured?.toolName)
            assertEquals(JsonPrimitive("狗狗"), captured?.arguments?.get("query"))
            assertEquals(JsonPrimitive("狗狗"), result.actionResultData?.jsonObject?.get("query"))
            assertEquals(emptyList<Pair<Float, Float>>(), operator.clickedCoordinates)
        }
    }

    private class FakeDeviceOperator : DeviceOperator {
        val clickedCoordinates = mutableListOf<Pair<Float, Float>>()
        val focusedInputs = mutableListOf<String>()

        override suspend fun clickCoordinate(x: Float, y: Float): OperationResult {
            clickedCoordinates += x to y
            return OperationResult(true, "点击坐标 ($x, $y) 成功")
        }

        override suspend fun longClickCoordinate(
            x: Float,
            y: Float,
            duration: Long
        ): OperationResult = OperationResult(true, "long clicked")

        override suspend fun inputText(text: String): OperationResult =
            OperationResult(true, "输入文本成功: $text").also {
                focusedInputs += text
            }

        override suspend fun pressHotKey(key: String): OperationResult =
            OperationResult(true, "hotkey")

        override suspend fun copyToClipboard(text: String): OperationResult =
            OperationResult(true, "copied")

        override suspend fun getClipboard(): String? = null

        override suspend fun slideCoordinate(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            duration: Long
        ): OperationResult = OperationResult(true, "slid")

        override suspend fun goHome(): OperationResult = OperationResult(true, "home")

        override suspend fun goBack(): OperationResult = OperationResult(true, "back")

        override suspend fun launchApplication(packageName: String): OperationResult =
            OperationResult(true, "launched")

        override suspend fun captureScreenshot(): String = ""

        override fun getLastScreenshotWidth(): Int = 720

        override fun getLastScreenshotHeight(): Int = 1280

        override fun getDisplayWidth(): Int = 720

        override fun getDisplayHeight(): Int = 1280

        override suspend fun showInfo(message: String) = Unit

        override fun isReady(): Boolean = true

        override fun currentXml(): String? = "<hierarchy />"

        override fun currentPackageName(): String? = "com.example"

        override fun currentActivityName(): String? = "MainActivity"

        override suspend fun hideKeyboard(): OperationResult =
            OperationResult(true, "hidden")
    }

    private inline fun withQuietLogs(block: () -> Unit) {
        val previousLogLevel = OmniLog.getLogLevel()
        OmniLog.setLogLevel(OmniLog.Level.DISABLE)
        try {
            block()
        } finally {
            OmniLog.setLogLevel(previousLogLevel)
        }
    }
}
