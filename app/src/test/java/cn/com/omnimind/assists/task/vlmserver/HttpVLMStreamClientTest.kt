package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.llm.ChatCompletionFunction
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionTool
import cn.com.omnimind.baselib.llm.ModelSceneRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpVLMStreamClientTest {
    @Test
    fun `strict native tool request retry keeps tools and required tool choice`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val seenRequests = mutableListOf<ChatCompletionRequest>()
        try {
            val client = HttpVLMStreamClient(
                scope = scope,
                requestOp = { request, listener ->
                    seenRequests += request
                    val source = dummyEventSource()
                    if (seenRequests.size == 1) {
                        scope.launch {
                            listener.onFailure(source, RuntimeException("bad request"), badRequestResponse())
                        }
                    } else {
                        scope.launch {
                            listener.onOpen(source, okResponse())
                            listener.onEvent(
                                source,
                                null,
                                "message",
                                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"click","arguments":"{\"target_description\":\"Search\",\"x\":500,\"y\":420}"}}]},"finish_reason":"tool_calls"}]}"""
                            )
                            listener.onEvent(source, null, "message", "[DONE]")
                        }
                    }
                    SceneChatCompletionStreamHandle(
                        eventSource = source,
                        parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                        route = "scene.vlm.operation.primary",
                        resolvedModel = "vlm-test-model"
                    )
                }
            )

            val turn = client.streamTurn(request = strictToolRequest())

            assertTrue(
                turn.requestVariant in setOf(
                    "no_tool_strict",
                    "no_thinking_controls",
                    "no_tool_strict_no_thinking_controls",
                    "no_parallel_tool_calls",
                    "no_tool_strict_no_parallel_tool_calls",
                    "no_stream_options",
                    "minimal_strict_tools",
                    "minimal_required_tools"
                )
            )
            assertEquals(2, seenRequests.size)
            val retry = seenRequests[1]
            assertTrue(retry.tools.any { it.function.name == "click" })
            assertEquals("\"required\"", retry.toolChoice.toString())
            assertTrue(retry.parallelToolCalls == false || retry.parallelToolCalls == null)
            assertFalse(seenRequests.any { it.toolChoice == null })
            assertFalse(seenRequests.any { it.tools.isEmpty() })
        } finally {
            scope.cancel()
        }
    }

    private fun strictToolRequest() = ChatCompletionRequest(
        messages = listOf(
            ChatCompletionMessage(
                role = "user",
                content = JsonPrimitive("tap search")
            )
        ),
        model = "scene.vlm.operation.primary",
        stream = true,
        tools = listOf(
            ChatCompletionTool(
                function = ChatCompletionFunction(
                    name = "click",
                    parameters = JsonObject(emptyMap()),
                    strict = true
                )
            )
        ),
        toolChoice = JsonPrimitive("required"),
        parallelToolCalls = false,
        maxCompletionTokens = 384,
        temperature = 0.2
    )

    private fun dummyEventSource(): EventSource {
        return object : EventSource {
            override fun request(): Request =
                Request.Builder().url("https://example.com").build()

            override fun cancel() = Unit
        }
    }

    @Test
    fun `strict native tool request can fall back by removing strict schema only`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val seenRequests = mutableListOf<ChatCompletionRequest>()
        try {
            val client = HttpVLMStreamClient(
                scope = scope,
                requestOp = { request, listener ->
                    seenRequests += request
                    val source = dummyEventSource()
                    if (seenRequests.size == 1) {
                        scope.launch {
                            listener.onFailure(source, RuntimeException("unsupported strict"), badRequestResponse())
                        }
                    } else {
                        scope.launch {
                            listener.onOpen(source, okResponse())
                            listener.onEvent(
                                source,
                                null,
                                "message",
                                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"click","arguments":"{\"target_description\":\"Search\",\"x\":500,\"y\":420}"}}]},"finish_reason":"tool_calls"}]}"""
                            )
                            listener.onEvent(source, null, "message", "[DONE]")
                        }
                    }
                    SceneChatCompletionStreamHandle(
                        eventSource = source,
                        parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                        route = "scene.vlm.operation.primary",
                        resolvedModel = "vlm-test-model"
                    )
                }
            )

            val turn = client.streamTurn(request = strictToolRequest())

            assertEquals("no_tool_strict", turn.requestVariant)
            assertEquals(true, seenRequests.first().tools.single().function.strict)
            assertEquals(null, seenRequests[1].tools.single().function.strict)
            assertEquals("\"required\"", seenRequests[1].toolChoice.toString())
            assertTrue(seenRequests[1].tools.any { it.function.name == "click" })
        } finally {
            scope.cancel()
        }
    }
    private fun okResponse(): Response =
        Response.Builder()
            .request(Request.Builder().url("https://example.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .build()

    private fun badRequestResponse(): Response =
        Response.Builder()
            .request(Request.Builder().url("https://example.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(400)
            .message("Bad Request")
            .build()
}
