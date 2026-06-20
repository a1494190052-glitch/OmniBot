package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.baselib.runlog.OobPrimitiveActionLedger
import cn.com.omnimind.baselib.runlog.OobPrimitiveActionRiskPolicy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionExecutorTest {
    @Test
    fun `click uses coordinates even when indexed node id is present`() = runBlocking {
        val previousLogLevel = OmniLog.getLogLevel()
        OmniLog.setLogLevel(OmniLog.Level.DISABLE)
        OobPrimitiveActionLedger.resetForTesting()
        val operator = FakeDeviceOperator()
        val executor = ActionExecutor(operator, UIContextManager())

        try {
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

            assertEquals(emptyList<String>(), operator.clickedNodeIds)
            assertEquals(listOf(360f to 625f), operator.clickedCoordinates)
            assertFalse(result.result.orEmpty().startsWith("执行失败"))
            assertTrue(result.result.orEmpty().contains("点击坐标"))
        } finally {
            OmniLog.setLogLevel(previousLogLevel)
        }
    }

    @Test
    fun `online vlm records successful primitive click`() = runBlocking {
        val previousLogLevel = OmniLog.getLogLevel()
        OmniLog.setLogLevel(OmniLog.Level.DISABLE)
        OobPrimitiveActionLedger.resetForTesting()
        val operator = FakeDeviceOperator()
        val executor = ActionExecutor(operator, UIContextManager())

        try {
            val result = executor.executeAction(
                UIStep(
                    observation = "",
                    thought = "tap safe button",
                    action = ClickAction(
                        targetDescription = "Open menu",
                        x = 120f,
                        y = 240f,
                    ),
                    packageName = "com.example",
                    observationXml = SAFE_BUTTON_XML,
                )
            )

            assertFalse(result.result.orEmpty().startsWith("执行失败"))
            val record = OobPrimitiveActionLedger.recentRecordsForTesting().single()
            assertEquals("vlm_online", record.source)
            assertEquals("click", record.tool)
            assertEquals(true, record.success)
            assertEquals(false, record.blocked)
            assertEquals("com.example", record.packageName)
            assertTrue(record.beforeXmlSha256.isNotBlank())
            assertEquals(SAFE_BUTTON_XML.length, record.beforeXmlChars)
        } finally {
            OobPrimitiveActionLedger.resetForTesting()
            OmniLog.setLogLevel(previousLogLevel)
        }
    }

    @Test
    fun `online vlm blocks dangerous primitive click before dispatch`() = runBlocking {
        val previousLogLevel = OmniLog.getLogLevel()
        OmniLog.setLogLevel(OmniLog.Level.DISABLE)
        OobPrimitiveActionLedger.resetForTesting()
        val operator = FakeDeviceOperator()
        val executor = ActionExecutor(operator, UIContextManager())

        try {
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
                    observationXml = SAFE_BUTTON_XML,
                )
            )

            assertTrue(result.result.orEmpty().startsWith("执行失败"))
            assertEquals(emptyList<Pair<Float, Float>>(), operator.clickedCoordinates)
            assertEquals(
                OobPrimitiveActionRiskPolicy.ERROR_DANGEROUS_ACTION_BLOCKED,
                result.pageDiagnostics["primitive_action_error_code"],
            )
            val record = OobPrimitiveActionLedger.recentRecordsForTesting().single()
            assertEquals(false, record.success)
            assertEquals(true, record.blocked)
            assertEquals(OobPrimitiveActionRiskPolicy.ERROR_DANGEROUS_ACTION_BLOCKED, record.errorCode)
        } finally {
            OobPrimitiveActionLedger.resetForTesting()
            OmniLog.setLogLevel(previousLogLevel)
        }
    }

    @Test
    fun `online vlm primitive input text ledger redacts text value`() = runBlocking {
        val previousLogLevel = OmniLog.getLogLevel()
        OmniLog.setLogLevel(OmniLog.Level.DISABLE)
        OobPrimitiveActionLedger.resetForTesting()
        val operator = FakeDeviceOperator()
        val executor = ActionExecutor(operator, UIContextManager())

        try {
            val result = executor.executeAction(
                UIStep(
                    observation = "",
                    thought = "type query",
                    action = InputTextAction(
                        targetDescription = "Search box",
                        text = "coffee near me",
                        x = 260f,
                        y = 120f,
                        nodeId = "88",
                    ),
                    packageName = "com.example",
                    observationXml = SAFE_BUTTON_XML,
                )
            )

            assertFalse(result.result.orEmpty().startsWith("执行失败"))
            assertEquals(listOf("88" to "coffee near me"), operator.nodeInputs)
            val record = OobPrimitiveActionLedger.recentRecordsForTesting().single()
            assertEquals("input_text", record.tool)
            assertEquals("<redacted>", record.args["text"])
            assertEquals(true, record.args["text_present"])
            assertEquals(14, record.args["text_length"])
            assertEquals(true, record.args["text_redacted"])
        } finally {
            OobPrimitiveActionLedger.resetForTesting()
            OmniLog.setLogLevel(previousLogLevel)
        }
    }

    @Test
    fun `click post action delay is shorter than legacy one second settle`() = runBlocking {
        val previousLogLevel = OmniLog.getLogLevel()
        OmniLog.setLogLevel(OmniLog.Level.DISABLE)
        val operator = FakeDeviceOperator()
        val executor = ActionExecutor(operator, UIContextManager())

        try {
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
        } finally {
            OmniLog.setLogLevel(previousLogLevel)
        }
    }

    @Test
    fun `input text uses indexed node id before coordinate focus fallback`() = runBlocking {
        val previousLogLevel = OmniLog.getLogLevel()
        OmniLog.setLogLevel(OmniLog.Level.DISABLE)
        val operator = FakeDeviceOperator()
        val executor = ActionExecutor(operator, UIContextManager())

        try {
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

            assertEquals(listOf("33" to "Alice"), operator.nodeInputs)
            assertEquals(emptyList<Pair<Float, Float>>(), operator.clickedCoordinates)
            assertFalse(result.result.orEmpty().startsWith("执行失败"))
            assertTrue(result.result.orEmpty().contains("输入文本成功"))
        } finally {
            OmniLog.setLogLevel(previousLogLevel)
        }
    }

    @Test
    fun `input text falls back to coordinate focus when node input fails`() = runBlocking {
        val previousLogLevel = OmniLog.getLogLevel()
        OmniLog.setLogLevel(OmniLog.Level.DISABLE)
        val operator = FakeDeviceOperator(
            nodeInputResult = OperationResult(false, "组件输入失败")
        )
        val executor = ActionExecutor(operator, UIContextManager())

        try {
            val result = executor.executeAction(
                UIStep(
                    observation = "",
                    thought = "type phone",
                    action = InputTextAction(
                        targetDescription = "Phone",
                        text = "415-555-0130",
                        x = 356f,
                        y = 1112f,
                        nodeId = "56"
                    )
                )
            )

            assertEquals(listOf("56" to "415-555-0130"), operator.nodeInputs)
            assertEquals(listOf(356f to 1112f), operator.clickedCoordinates)
            assertEquals(listOf("415-555-0130"), operator.focusedInputs)
            assertFalse(result.result.orEmpty().startsWith("执行失败"))
        } finally {
            OmniLog.setLogLevel(previousLogLevel)
        }
    }

    @Test
    fun `function run action returns function payload as action result data`() = runBlocking {
        val previousLogLevel = OmniLog.getLogLevel()
        OmniLog.setLogLevel(OmniLog.Level.DISABLE)
        val operator = FakeDeviceOperator()
        val executor = ActionExecutor(operator, UIContextManager())
        VLMFunctionRunRegistry.register(object : VLMFunctionRunHandler {
            override suspend fun runFunction(request: VLMFunctionRunRequest): OperationResult {
                return OperationResult(
                    success = true,
                    message = "function completed",
                    data = buildJsonObject {
                        put("function_id", JsonPrimitive(request.functionId))
                        put("fallback", JsonPrimitive(false))
                    }
                )
            }
        })

        try {
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
        } finally {
            VLMFunctionRunRegistry.register(null)
            OmniLog.setLogLevel(previousLogLevel)
        }
    }

    private class FakeDeviceOperator(
        private val clickNodeResult: OperationResult = OperationResult(true, "node clicked"),
        private val nodeInputResult: OperationResult = OperationResult(true, "向组件输入文本成功")
    ) : DeviceOperator {
        val clickedNodeIds = mutableListOf<String>()
        val clickedCoordinates = mutableListOf<Pair<Float, Float>>()
        val nodeInputs = mutableListOf<Pair<String, String>>()
        val focusedInputs = mutableListOf<String>()

        override suspend fun clickCoordinate(x: Float, y: Float): OperationResult {
            clickedCoordinates += x to y
            return OperationResult(true, "点击坐标 ($x, $y) 成功")
        }

        override suspend fun clickNodeById(
            nodeId: String,
            targetDescription: String
        ): OperationResult {
            clickedNodeIds += nodeId
            return clickNodeResult
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

        override suspend fun inputTextToNodeById(
            nodeId: String,
            text: String,
            targetDescription: String
        ): OperationResult {
            nodeInputs += nodeId to text
            return nodeInputResult
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
    }

    private companion object {
        private const val SAFE_BUTTON_XML =
            "<hierarchy><node text=\"Open menu\" clickable=\"true\" bounds=\"[80,200][300,280]\" /></hierarchy>"
    }
}
