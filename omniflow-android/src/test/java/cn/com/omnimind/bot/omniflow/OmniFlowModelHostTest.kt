package cn.com.omnimind.bot.omniflow

import android.content.Context
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import cn.com.omnimind.baselib.llm.ChatCompletionUsage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OmniFlowModelHostTest {
    @Test
    fun `model turn forwards Python request and returns raw native tool call`() = runBlocking {
        val reasoningUpdates = mutableListOf<String>()
        val client = object : OmniFlowModelClient {
            override suspend fun streamTurn(
                request: cn.com.omnimind.baselib.llm.ChatCompletionRequest,
                onReasoningUpdate: (suspend (String) -> Unit)?,
            ): ChatCompletionTurn {
                assertEquals("scene.vlm.operation.primary", request.model)
                assertEquals("required", (request.toolChoice as JsonPrimitive).content)
                assertFalse(request.parallelToolCalls!!)
                val click = request.tools.single().function
                assertEquals("click", click.name)
                assertEquals(true, click.strict)
                assertEquals(false, click.parameters["additionalProperties"]?.jsonPrimitive?.boolean)
                assertEquals(
                    listOf("summary", "x", "y"),
                    click.parameters["required"]?.jsonArray?.map { it.jsonPrimitive.content },
                )
                val properties = click.parameters["properties"]?.jsonObject.orEmpty()
                assertEquals(0.0, properties["x"]?.jsonObject?.get("minimum")?.jsonPrimitive?.double)
                assertEquals(1000.0, properties["x"]?.jsonObject?.get("maximum")?.jsonPrimitive?.double)
                assertEquals(
                    listOf("tap", "long_press"),
                    properties["mode"]?.jsonObject?.get("enum")?.jsonArray?.map {
                        it.jsonPrimitive.content
                    },
                )
                onReasoningUpdate?.invoke("search is visible")
                return ChatCompletionTurn(
                    message = ChatCompletionMessage(
                        role = "assistant",
                        toolCalls = listOf(
                            AssistantToolCall(
                                id = "call-1",
                                function = AssistantToolCallFunction(
                                    name = "click",
                                    arguments = """{"summary":"点击搜索","x":[500,300],"legacy":true}""",
                                ),
                            ),
                        ),
                    ),
                    reasoning = "search is visible",
                    finishReason = "tool_calls",
                    resolvedModel = "Qwen3-VL-235B-A22B-Instruct",
                    usage = ChatCompletionUsage(
                        promptTokens = 20,
                        completionTokens = 5,
                        totalTokens = 25,
                    ),
                )
            }
        }
        val host = OmniFlowModelHost(client, reasoningUpdates::add)

        val result = host.modelTurn(
            mapOf(
                "model" to "scene.vlm.operation.primary",
                "request" to mapOf(
                    "model" to "scene.vlm.operation.primary",
                    "messages" to listOf(
                        mapOf("role" to "user", "content" to "tap search"),
                    ),
                    "tools" to listOf(
                        mapOf(
                            "type" to "function",
                            "function" to mapOf(
                                "name" to "click",
                                "description" to "Tap one point",
                                "strict" to true,
                                "parameters" to mapOf(
                                    "type" to "object",
                                    "additionalProperties" to false,
                                    "required" to listOf("summary", "x", "y"),
                                    "properties" to mapOf(
                                        "summary" to mapOf("type" to "string"),
                                        "x" to mapOf(
                                            "type" to "number",
                                            "minimum" to 0,
                                            "maximum" to 1000,
                                        ),
                                        "y" to mapOf(
                                            "type" to "number",
                                            "minimum" to 0,
                                            "maximum" to 1000,
                                        ),
                                        "mode" to mapOf(
                                            "type" to "string",
                                            "enum" to listOf("tap", "long_press"),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                    "tool_choice" to "required",
                    "parallel_tool_calls" to false,
                    "stream" to true,
                ),
            ),
        )

        assertEquals(listOf("search is visible"), reasoningUpdates)
        assertEquals("scene.vlm.operation.primary", result["requested_model"])
        assertEquals("Qwen3-VL-235B-A22B-Instruct", result["resolved_model"])
        val toolCall = (result["tool_calls"] as List<*>).single() as Map<*, *>
        val function = toolCall["function"] as Map<*, *>
        assertEquals("click", function["name"])
        assertEquals(
            """{"summary":"点击搜索","x":[500,300],"legacy":true}""",
            function["arguments"],
        )
        assertEquals(25, (result["usage"] as Map<*, *>)["total_tokens"])
    }

    @Test
    fun `json completion crosses only the configured platform boundary`() = runBlocking {
        var receivedRequest: ChatCompletionRequest? = null
        OmniFlow.configure(
            object : OmniFlowPlatform {
                override suspend fun startProcess(
                    context: Context,
                    command: String,
                    environment: Map<String, String>,
                ): Process = error("process_not_expected")

                override suspend fun ensurePython(context: Context, expectedVersion: String) = Unit

                override suspend fun completeJson(request: ChatCompletionRequest): String {
                    receivedRequest = request
                    return """{"function_id":"none"}"""
                }
            },
        )

        val result = OmniFlowModelHost.completeJson(
            mapOf(
                "model" to "scene.dispatch.model",
                "prompt" to "Select a Function",
                "max_tokens" to 321,
                "temperature" to 0.0,
            ),
        )

        assertEquals("""{"function_id":"none"}""", result["content"])
        assertEquals("scene.dispatch.model", receivedRequest?.model)
        assertEquals(321, receivedRequest?.maxCompletionTokens)
        assertEquals(0.0, receivedRequest?.temperature)
        assertEquals(
            "json_object",
            receivedRequest?.responseFormat?.get("type")?.jsonPrimitive?.content,
        )
    }
}
