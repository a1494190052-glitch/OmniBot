package cn.com.omnimind.bot.omniflow

import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OmniFlowFunctionRecallRuntimeTest {
    private val contactFunction = OmniFlowFunctionRecallRuntime.Candidate(
        functionId = "complete_run_contacts",
        name = "创建联系人",
        description = "创建指定姓名和手机号的联系人",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("contact_name", buildJsonObject { put("type", "string") })
                put("phone_number", buildJsonObject { put("type", "string") })
            })
            put("required", buildJsonArray {
                add(JsonPrimitive("contact_name"))
                add(JsonPrimitive("phone_number"))
            })
            put("additionalProperties", false)
        },
    )

    @Test
    fun `router exposes functions and binds explicit arguments`() = runBlocking {
        var received: ChatCompletionRequest? = null
        val selection = OmniFlowFunctionRecallRuntime.route(
            goal = "创建联系人 Carol_C，手机号 13700137003",
            candidates = listOf(contactFunction),
            modelClient = object : OmniFlowModelClient {
                override suspend fun streamTurn(
                    request: ChatCompletionRequest,
                    onReasoningUpdate: (suspend (String) -> Unit)?,
                ): ChatCompletionTurn {
                    received = request
                    return turn(
                        name = contactFunction.functionId,
                        arguments = """{"contact_name":"Carol_C","phone_number":"13700137003"}""",
                    )
                }
            },
        )

        assertEquals(OmniVlmPlugin.MODEL_SCENE, received?.model)
        assertEquals("none", received?.reasoningEffort)
        assertEquals("required", received?.toolChoice?.jsonPrimitive?.content)
        assertEquals(
            listOf(contactFunction.functionId, "reject_recalled_function"),
            received?.tools?.map { it.function.name },
        )
        assertEquals(contactFunction.functionId, selection?.toolCall?.name)
        assertEquals("Carol_C", selection?.toolCall?.arguments?.get("contact_name"))
        assertEquals("13700137003", selection?.toolCall?.arguments?.get("phone_number"))
    }

    @Test
    fun `router rejects missing required arguments`() = runBlocking {
        val selection = OmniFlowFunctionRecallRuntime.route(
            goal = "创建联系人 Carol_C",
            candidates = listOf(contactFunction),
            modelClient = fixedModel(
                name = contactFunction.functionId,
                arguments = """{"contact_name":"Carol_C"}""",
            ),
        )

        assertNull(selection)
    }

    @Test
    fun `router accepts explicit reject tool`() = runBlocking {
        val selection = OmniFlowFunctionRecallRuntime.route(
            goal = "打开蓝牙",
            candidates = listOf(contactFunction),
            modelClient = fixedModel("reject_recalled_function", "{}"),
        )

        assertNull(selection)
    }

    @Test
    fun `router accepts explicit json content when provider omits tool call`() = runBlocking {
        val selection = OmniFlowFunctionRecallRuntime.route(
            goal = "创建联系人 Carol_C，手机号 13700137003",
            candidates = listOf(contactFunction),
            modelClient = object : OmniFlowModelClient {
                override suspend fun streamTurn(
                    request: ChatCompletionRequest,
                    onReasoningUpdate: (suspend (String) -> Unit)?,
                ): ChatCompletionTurn = ChatCompletionTurn(
                    message = ChatCompletionMessage(
                        role = "assistant",
                        content = JsonPrimitive(
                            "{\"function_id\":\"complete_run_contacts\",\"arguments\":{\"contact_name\":\"Carol_C\",\"phone_number\":\"13700137003\"}}",
                        ),
                    ),
                )
            },
        )

        assertEquals(contactFunction.functionId, selection?.toolCall?.name)
        assertEquals("Carol_C", selection?.toolCall?.arguments?.get("contact_name"))
    }

    @Test
    fun `router uses exact recorded goal when provider rejects recall`() = runBlocking {
        val recorded = OmniFlowFunctionRecallRuntime.Candidate(
            functionId = "open_contacts_recorded",
            name = "Open Google Contacts and stop after the Contacts list is visible.",
            description = "Complete this exact user request with the full recorded action sequence: " +
                "Open Google Contacts and stop after the Contacts list is visible.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {})
                put("required", buildJsonArray {})
                put("additionalProperties", false)
            },
        )
        val selection = OmniFlowFunctionRecallRuntime.route(
            goal = "Open Google Contacts and stop after the Contacts list is visible.",
            candidates = listOf(recorded),
            modelClient = fixedModel("reject_recalled_function", "{}"),
        )

        assertEquals(recorded.functionId, selection?.toolCall?.name)
        assertEquals(emptyMap<String, Any?>(), selection?.toolCall?.arguments)
    }

    @Test
    fun `recall attempt uses a separate run id`() {
        assertEquals(
            "gui-123-recall",
            OmniFlowFunctionRecallRuntime.recallRunId("gui-123"),
        )
    }

    private fun fixedModel(name: String, arguments: String) = object : OmniFlowModelClient {
        override suspend fun streamTurn(
            request: ChatCompletionRequest,
            onReasoningUpdate: (suspend (String) -> Unit)?,
        ): ChatCompletionTurn = turn(name, arguments)
    }

    private fun turn(name: String, arguments: String) = ChatCompletionTurn(
        message = ChatCompletionMessage(
            role = "assistant",
            toolCalls = listOf(
                AssistantToolCall(
                    id = "call-recall",
                    function = AssistantToolCallFunction(name, arguments),
                ),
            ),
        ),
        finishReason = "tool_calls",
        resolvedModel = "gpt-5.6-sol",
    )
}
