package cn.com.omnimind.bot.vlm

import cn.com.omnimind.assists.task.vlmserver.UIContext
import cn.com.omnimind.assists.task.vlmserver.VLMToolDefinitions
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import cn.com.omnimind.baselib.runlog.OobActionSchema
import cn.com.omnimind.baselib.i18n.PromptLocale
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidGuiPolicyTest {
    @Test
    fun requestRequiresShortRationaleAndAttachesCurrentScreenshot() {
        val turnRequest = AndroidGuiPolicy().buildRequest(
            context = UIContext(overallTask = "打开网络设置"),
            screenshot = "ZmFrZS1qcGVn",
            model = "scene.vlm.operation.primary",
        )
        val request = turnRequest.request

        val systemPrompt = requireNotNull(request.messages.first().content).jsonPrimitive.content
        assertTrue(systemPrompt.contains("function.arguments"))
        val compactGuide = VLMToolDefinitions.renderCompactActionSchemaGuide(PromptLocale.ZH_CN)
        assertTrue(compactGuide.contains("summary"))
        val clickParameters = request.tools
            .single { it.function.name == OobActionSchema.TOOL_CLICK }
            .function
            .parameters
        assertTrue(
            clickParameters["required"]
                ?.jsonArray
                ?.any { it.jsonPrimitive.content == "summary" } == true
        )
        val userParts = requireNotNull(request.messages.last().content).jsonArray
        assertEquals("text", userParts[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("image_url", userParts[1].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals(
            "data:image/jpeg;base64,ZmFrZS1qcGVn",
            userParts[1].jsonObject["image_url"]
                ?.jsonObject
                ?.get("url")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun qwenPointArrayIsAdaptedBeforeCanonicalValidation() {
        val policy = AndroidGuiPolicy()
        val turnRequest = policy.buildRequest(
            context = UIContext(overallTask = "打开应用设置"),
            screenshot = null,
            model = "Qwen3-VL-235B-A22B-Instruct",
        )

        val arguments = policy.parseAndValidateArguments(
            turnRequest = turnRequest,
            toolName = OobActionSchema.TOOL_CLICK,
            rawArguments = buildJsonObject {
                put("summary", "进入应用列表")
                put("target_description", "Apps")
                put("x", buildJsonArray {
                    add(JsonPrimitive(500))
                    add(JsonPrimitive(561))
                })
            }.toString(),
        )

        assertEquals(500.0, arguments["x"]?.jsonPrimitive?.content?.toDouble())
        assertEquals(561.0, arguments["y"]?.jsonPrimitive?.content?.toDouble())
        assertFalse(arguments.containsKey("summary"))
    }

    @Test
    fun pointArrayDialectSupportsLongPressAndInputText() {
        val policy = AndroidGuiPolicy()
        val turnRequest = policy.buildRequest(
            context = UIContext(overallTask = "编辑应用名称"),
            screenshot = null,
            model = "Qwen3-VL-235B-A22B-Instruct",
        )

        val longPress = policy.parseAndValidateArguments(
            turnRequest = turnRequest,
            toolName = OobActionSchema.TOOL_LONG_PRESS,
            rawArguments = buildJsonObject {
                put("summary", "打开快捷菜单")
                put("target_description", "应用图标")
                put("x", buildJsonArray {
                    add(JsonPrimitive(300))
                    add(JsonPrimitive(400))
                })
            }.toString(),
        )
        val inputText = policy.parseAndValidateArguments(
            turnRequest = turnRequest,
            toolName = OobActionSchema.TOOL_INPUT_TEXT,
            rawArguments = buildJsonObject {
                put("summary", "填写应用名称")
                put("target_description", "名称输入框")
                put("text", "Omni")
                put("x", buildJsonArray {
                    add(JsonPrimitive(500))
                    add(JsonPrimitive(600))
                })
            }.toString(),
        )

        assertEquals("300", longPress["x"]?.jsonPrimitive?.content)
        assertEquals("400", longPress["y"]?.jsonPrimitive?.content)
        assertEquals("500", inputText["x"]?.jsonPrimitive?.content)
        assertEquals("600", inputText["y"]?.jsonPrimitive?.content)
    }

    @Test
    fun qwenSwipePointArraysAreAdaptedBeforeCanonicalValidation() {
        val policy = AndroidGuiPolicy()
        val turnRequest = policy.buildRequest(
            context = UIContext(overallTask = "向上浏览"),
            screenshot = null,
            model = "Qwen3-VL-235B-A22B-Instruct",
        )

        val arguments = policy.parseAndValidateArguments(
            turnRequest = turnRequest,
            toolName = OobActionSchema.TOOL_SWIPE,
            rawArguments = buildJsonObject {
                put("summary", "向上查看更多内容")
                put("target_description", "设置列表")
                put("direction", "up")
                put("x1", buildJsonArray {
                    add(JsonPrimitive(500))
                    add(JsonPrimitive(800))
                })
                put("x2", buildJsonArray {
                    add(JsonPrimitive(500))
                    add(JsonPrimitive(200))
                })
            }.toString(),
        )

        assertEquals("500", arguments["x1"]?.jsonPrimitive?.content)
        assertEquals("800", arguments["y1"]?.jsonPrimitive?.content)
        assertEquals("500", arguments["x2"]?.jsonPrimitive?.content)
        assertEquals("200", arguments["y2"]?.jsonPrimitive?.content)
    }

    @Test
    fun malformedPointArrayIsRejectedAsModelDialectError() {
        val policy = AndroidGuiPolicy()
        val turnRequest = policy.buildRequest(
            context = UIContext(overallTask = "打开应用设置"),
            screenshot = null,
            model = "Qwen3-VL-235B-A22B-Instruct",
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            policy.parseAndValidateArguments(
                turnRequest = turnRequest,
                toolName = OobActionSchema.TOOL_CLICK,
                rawArguments = buildJsonObject {
                    put("summary", "进入应用列表")
                    put("target_description", "应用")
                    put("x", buildJsonArray { add(JsonPrimitive(500)) })
                }.toString(),
            )
        }

        assertTrue(error.message.orEmpty().startsWith("model_argument_dialect_invalid:"))
    }

    @Test
    fun textualToolCallIsReportedAsUnsupportedButNeverParsedForExecution() {
        val turn = ChatCompletionTurn(
            message = ChatCompletionMessage(
                role = "assistant",
                content = JsonPrimitive(
                    """{"tool_call":{"name":"click","arguments":{"x":500,"y":561}}}"""
                ),
            ),
            finishReason = "stop",
        )

        assertEquals(
            "model_native_tool_calls_unsupported: " +
                "model returned a tool call in assistant.content instead of native tool_calls",
            AndroidGuiPolicy().modelTurnContractViolation(turn),
        )
    }

    @Test
    fun textualLegacyActionIsReportedAsUnsupportedButNeverParsedForExecution() {
        val turn = ChatCompletionTurn(
            message = ChatCompletionMessage(
                role = "assistant",
                content = JsonPrimitive(
                    """{"action":{"type":"click","x":500,"y":919},"summary":"打开应用"}"""
                ),
            ),
            finishReason = "stop",
        )

        assertEquals(
            "model_native_tool_calls_unsupported: " +
                "model returned a tool call in assistant.content instead of native tool_calls",
            AndroidGuiPolicy().modelTurnContractViolation(turn),
        )
    }

    @Test
    fun nativeToolCallPassesTurnContractAndPlainTextGetsGenericViolation() {
        val policy = AndroidGuiPolicy()
        val nativeTurn = ChatCompletionTurn(
            message = ChatCompletionMessage(
                role = "assistant",
                toolCalls = listOf(
                    AssistantToolCall(
                        id = "call-1",
                        function = AssistantToolCallFunction(
                            name = OobActionSchema.TOOL_CLICK,
                            arguments = """{"x":500,"y":561}""",
                        ),
                    )
                ),
            ),
            finishReason = "tool_calls",
        )
        val plainTextTurn = ChatCompletionTurn(
            message = ChatCompletionMessage(
                role = "assistant",
                content = JsonPrimitive("我无法完成这个任务"),
            ),
            finishReason = "stop",
        )

        assertNull(policy.modelTurnContractViolation(nativeTurn))
        assertEquals(
            "provider_tool_call_contract_violation: provider returned no native tool_calls",
            policy.modelTurnContractViolation(plainTextTurn),
        )
    }

    @Test
    fun recoveryTurnAttachesPreviousAndCurrentScreenshotsInOrder() {
        val turnRequest = AndroidGuiPolicy().buildRequest(
            context = UIContext(overallTask = "打开网络设置"),
            screenshot = "Y3VycmVudA==",
            previousScreenshot = "cHJldmlvdXM=",
            model = "scene.vlm.operation.primary",
        )

        val userParts = requireNotNull(turnRequest.request.messages.last().content).jsonArray
        assertEquals(5, userParts.size)
        assertEquals(
            "[Previous screenshot before the last action]",
            userParts[1].jsonObject["text"]?.jsonPrimitive?.content,
        )
        assertEquals(
            "data:image/jpeg;base64,cHJldmlvdXM=",
            userParts[2].jsonObject["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.content,
        )
        assertEquals(
            "[Current screenshot after the last action]",
            userParts[3].jsonObject["text"]?.jsonPrimitive?.content,
        )
        assertEquals(
            "data:image/jpeg;base64,Y3VycmVudA==",
            userParts[4].jsonObject["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.content,
        )
    }
}
