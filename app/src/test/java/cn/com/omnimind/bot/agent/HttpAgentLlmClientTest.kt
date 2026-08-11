package cn.com.omnimind.bot.agent

import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.llm.ChatCompletionFunction
import cn.com.omnimind.baselib.llm.ChatCompletionStreamOptions
import cn.com.omnimind.baselib.llm.ChatCompletionTool
import cn.com.omnimind.baselib.llm.OpenAiWireApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpAgentLlmClientTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `transient stream failure retries the same model turn`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        var attempts = 0
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride(),
                streamRequestOp = { _, _, listener, _, _, _, _, _, _, _ ->
                    attempts += 1
                    val source = dummyEventSource()
                    if (attempts == 1) {
                        listener.onFailure(
                            source,
                            IllegalStateException("Software caused connection abort"),
                            null,
                        )
                    } else {
                        listener.onOpen(source, okResponse())
                        listener.onEvent(
                            source,
                            null,
                            "message",
                            """{"choices":[{"delta":{"content":"完成"},"finish_reason":"stop"}]}""",
                        )
                        listener.onEvent(source, null, "message", "[DONE]")
                    }
                    source
                },
                maxTransientStreamRetries = 2,
                transientStreamRetryDelayMs = 0L,
                json = json,
            )

            val turn = client.streamTurn(request = simpleRequest())

            assertEquals(2, attempts)
            assertEquals("完成", turn.message.contentText())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `non transient client error is not retried`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        var attempts = 0
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride(),
                streamRequestOp = { _, _, listener, _, _, _, _, _, _, _ ->
                    attempts += 1
                    val source = dummyEventSource()
                    listener.onFailure(
                        source,
                        IllegalStateException("unauthorized"),
                        Response.Builder()
                            .request(Request.Builder().url("https://example.com").build())
                            .protocol(Protocol.HTTP_1_1)
                            .code(401)
                            .message("Unauthorized")
                            .body("unauthorized".toResponseBody())
                            .build(),
                    )
                    source
                },
                maxTransientStreamRetries = 2,
                transientStreamRetryDelayMs = 0L,
                json = json,
            )

            val error = runCatching { client.streamTurn(simpleRequest()) }.exceptionOrNull()

            assertEquals(1, attempts)
            assertEquals(401, (error as AgentStreamRequestException).statusCode)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `official GLM VLM route normalizes mixed multimodal content and keeps native tools`() {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val client = HttpAgentLlmClient(scope = scope, modelOverride = testOverride())
            val request = simpleRequest().copy(
                messages = listOf(
                    cn.com.omnimind.baselib.llm.ChatCompletionMessage(
                        role = "system",
                        content = JsonPrimitive("Choose one tool"),
                    ),
                    cn.com.omnimind.baselib.llm.ChatCompletionMessage(
                        role = "user",
                        content = JsonArray(
                            listOf(
                                JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("text"),
                                        "text" to JsonPrimitive("Current screen"),
                                    )
                                )
                            )
                        ),
                    ),
                ),
                tools = listOf(
                    ChatCompletionTool(
                        function = ChatCompletionFunction(name = "click"),
                    ),
                ),
                toolChoice = JsonPrimitive("required"),
                parallelToolCalls = false,
                streamOptions = ChatCompletionStreamOptions(),
            )

            val variants = client.buildRequestVariants(
                request = request,
                routeInfo = routeInfo(
                    requestedModel = "scene.vlm.operation.primary",
                    resolvedModel = "GLM-5.1",
                    protocolType = "openai_compatible",
                    requiresReasoningEcho = false,
                    apiBase = "https://llmapi.paratera.com/v1/chat/completions",
                ),
            )

            assertEquals(listOf("default"), variants.map { it.name })
            assertNull(variants.first().request.streamOptions)
            assertEquals("click", variants.first().request.tools.single().function.name)
            assertNull(variants.first().request.functions)
            assertTrue(variants.first().request.messages.all { it.content is JsonArray })
            val systemText = (variants.first().request.messages.first().content as JsonArray)
                .first()
                .jsonObject
                .getValue("text")
                .jsonPrimitive
                .content
            assertEquals("Choose one tool", systemText)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `successful non streaming responses body completes a stream turn`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride(),
                resolveRouteInfoOp = { model, _, _, _, _, protocolType, _ ->
                    routeInfo(
                        requestedModel = model,
                        resolvedModel = "gpt-5.6-sol",
                        protocolType = protocolType ?: "openai_compatible",
                        requiresReasoningEcho = false,
                        wireApi = OpenAiWireApi.RESPONSES,
                    )
                },
                streamRequestOp = { _, _, listener, _, _, _, _, _, _, _ ->
                    val source = dummyEventSource()
                    listener.onFailure(
                        source,
                        IllegalStateException("Expected text/event-stream"),
                        okResponse(
                            """{"object":"response","status":"completed","output":[{"type":"function_call","call_id":"call-1","name":"click","arguments":"{\"summary\":\"打开蓝牙\",\"x\":900,\"y\":300}"}],"usage":{"prompt_tokens":120,"completion_tokens":15,"total_tokens":135}}""",
                        ),
                    )
                    source
                },
                json = json,
            )

            val turn = client.streamTurn(request = simpleRequest())

            assertEquals("gpt-5.6-sol", turn.resolvedModel)
            assertEquals("click", turn.message.toolCalls?.single()?.function?.name)
            assertEquals(120, turn.usage?.promptTokens)
            assertEquals(15, turn.usage?.completionTokens)
            assertEquals(135, turn.usage?.totalTokens)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `closed stream without completion signal fails instead of silently succeeding`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride(),
                streamRequestOp = { _, _, listener, _, _, _, _, _, _, _ ->
                    val source = dummyEventSource()
                    listener.onOpen(source, okResponse())
                    listener.onEvent(
                        source,
                        null,
                        "message",
                        """{"choices":[{"delta":{"content":"还没输出完"}}]}"""
                    )
                    listener.onClosed(source)
                    source
                },
                streamIdleWatchdogMs = 5_000L,
                json = json
            )

            val error = runCatching {
                client.streamTurn(request = simpleRequest())
            }.exceptionOrNull()

            requireNotNull(error)
            assertTrue(
                error.message.orEmpty().contains("closed before completion signal")
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `idle watchdog fails stalled stream with explicit error`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride(),
                streamRequestOp = { _, _, listener, _, _, _, _, _, _, _ ->
                    val source = dummyEventSource()
                    listener.onOpen(source, okResponse())
                    listener.onEvent(
                        source,
                        null,
                        "message",
                        """{"choices":[{"delta":{"content":"先来一段"}}]}"""
                    )
                    source
                },
                streamIdleWatchdogMs = 50L,
                json = json
            )

            val error = runCatching {
                client.streamTurn(request = simpleRequest())
            }.exceptionOrNull()

            requireNotNull(error)
            assertTrue(error.message.orEmpty().contains("idle timeout"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `done signal still completes stream normally`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride(),
                streamRequestOp = { _, _, listener, _, _, _, _, _, _, _ ->
                    val source = dummyEventSource()
                    listener.onOpen(source, okResponse())
                    listener.onEvent(
                        source,
                        null,
                        "message",
                        """{"choices":[{"delta":{"content":"最终回答"}}]}"""
                    )
                    listener.onEvent(source, null, "message", "[DONE]")
                    source
                },
                streamIdleWatchdogMs = 5_000L,
                json = json
            )

            val turn = client.streamTurn(request = simpleRequest())

            assertEquals("最终回答", turn.message.contentText())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `scene request returns the resolved route model`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride(),
                resolveRouteInfoOp = { model, _, _, _, _, protocolType, _ ->
                    routeInfo(
                        requestedModel = model,
                        resolvedModel = "qwen3-vl-plus",
                        protocolType = protocolType ?: "openai_compatible",
                        requiresReasoningEcho = false,
                    )
                },
                streamRequestOp = { _, _, listener, _, _, _, _, _, _, _ ->
                    val source = dummyEventSource()
                    listener.onOpen(source, okResponse())
                    listener.onEvent(
                        source,
                        null,
                        "message",
                        """{"choices":[{"delta":{"content":"完成"},"finish_reason":"stop"}]}""",
                    )
                    listener.onEvent(source, null, "message", "[DONE]")
                    source
                },
                streamIdleWatchdogMs = 5_000L,
                json = json,
            )

            val turn = client.streamTurn(
                request = simpleRequest().copy(model = "scene.vlm.operation.primary"),
            )

            assertEquals("qwen3-vl-plus", turn.resolvedModel)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `resolved route requiring reasoning echo preserves reasoning content even when override is not deepseek`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride(),
                resolveRouteInfoOp = { model, _, _, _, _, protocolType, _ ->
                    routeInfo(
                        requestedModel = model,
                        resolvedModel = "deepseek-v4-flash",
                        protocolType = protocolType ?: "deepseek",
                        requiresReasoningEcho = true
                    )
                },
                streamRequestOp = { _, _, listener, _, _, _, _, _, _, _ ->
                    val source = dummyEventSource()
                    listener.onOpen(source, okResponse())
                    listener.onEvent(
                        source,
                        null,
                        "message",
                        """{"choices":[{"delta":{"reasoning_content":"需要先查工具","tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_time","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
                    )
                    listener.onEvent(source, null, "message", "[DONE]")
                    source
                },
                streamIdleWatchdogMs = 5_000L,
                json = json
            )

            val turn = client.streamTurn(request = simpleRequest())

            assertEquals("需要先查工具", turn.reasoning)
            assertEquals("需要先查工具", turn.message.reasoningContent)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `resolved route without reasoning echo keeps plain-answer reasoning off assistant message`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride(),
                resolveRouteInfoOp = { model, _, _, _, _, protocolType, _ ->
                    routeInfo(
                        requestedModel = model,
                        resolvedModel = "qwen-plus",
                        protocolType = protocolType ?: "openai_compatible",
                        requiresReasoningEcho = false
                    )
                },
                streamRequestOp = { _, _, listener, _, _, _, _, _, _, _ ->
                    val source = dummyEventSource()
                    listener.onOpen(source, okResponse())
                    listener.onEvent(
                        source,
                        null,
                        "message",
                        """{"choices":[{"delta":{"reasoning_content":"内部思考","content":"最终回答"},"finish_reason":"stop"}]}"""
                    )
                    listener.onEvent(source, null, "message", "[DONE]")
                    source
                },
                streamIdleWatchdogMs = 5_000L,
                json = json
            )

            val turn = client.streamTurn(request = simpleRequest())

            assertEquals("内部思考", turn.reasoning)
            assertEquals("最终回答", turn.message.contentText())
            assertNull(turn.message.reasoningContent)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `qwen route emits pending reasoning before content`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val firstContentUpdate = CompletableDeferred<String>()
        val emissions = mutableListOf<String>()
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride(),
                resolveRouteInfoOp = { model, _, _, _, _, protocolType, _ ->
                    routeInfo(
                        requestedModel = model,
                        resolvedModel = "qwen3.6-plus",
                        protocolType = protocolType ?: "openai_compatible",
                        requiresReasoningEcho = false
                    )
                },
                streamRequestOp = { _, _, listener, _, _, _, _, _, _, _ ->
                    val source = dummyEventSource()
                    listener.onOpen(source, okResponse())
                    listener.onEvent(
                        source,
                        null,
                        "message",
                        """{"choices":[{"delta":{"content":"","reasoning_content":"先分析"}}]}"""
                    )
                    listener.onEvent(
                        source,
                        null,
                        "message",
                        """{"choices":[{"delta":{"content":"","reasoning_content":"更多"}}]}"""
                    )
                    listener.onEvent(
                        source,
                        null,
                        "message",
                        """{"choices":[{"delta":{"content":"最终"}}]}"""
                    )
                    withTimeout(1_000L) {
                        firstContentUpdate.await()
                    }
                    listener.onEvent(
                        source,
                        null,
                        "message",
                        """{"choices":[{"delta":{"content":"回答"},"finish_reason":"stop"}]}"""
                    )
                    listener.onEvent(source, null, "message", "[DONE]")
                    source
                },
                streamIdleWatchdogMs = 5_000L,
                json = json
            )

            val turn = client.streamTurn(
                request = simpleRequest(),
                onReasoningUpdate = { reasoning ->
                    emissions += "reasoning:$reasoning"
                },
                onContentUpdate = { content ->
                    emissions += "content:$content"
                    if (!firstContentUpdate.isCompleted) {
                        firstContentUpdate.complete(content)
                    }
                }
            )

            assertEquals("最终", firstContentUpdate.await())
            assertEquals("先分析更多", turn.reasoning)
            assertEquals("最终回答", turn.message.contentText())
            val lastReasoningIndex = emissions.indexOfLast {
                it.startsWith("reasoning:")
            }
            val firstContentIndex = emissions.indexOfFirst {
                it.startsWith("content:")
            }
            assertTrue(lastReasoningIndex >= 0)
            assertTrue(firstContentIndex >= 0)
            assertTrue(lastReasoningIndex < firstContentIndex)
        } finally {
            scope.cancel()
        }
    }

    private fun simpleRequest() = cn.com.omnimind.baselib.llm.ChatCompletionRequest(
        messages = listOf(
            cn.com.omnimind.baselib.llm.ChatCompletionMessage(
                role = "user",
                content = kotlinx.serialization.json.JsonPrimitive("继续")
            )
        ),
        model = "test-model",
        stream = true
    )

    private fun testOverride() = AgentModelOverride(
        providerProfileId = "test",
        modelId = "test-model",
        apiBase = "https://example.com",
        apiKey = "test-key"
    )

    private fun dummyEventSource(): EventSource {
        return object : EventSource {
            override fun request(): Request =
                Request.Builder().url("https://example.com").build()

            override fun cancel() = Unit
        }
    }

    private fun okResponse(body: String? = null): Response {
        return Response.Builder()
            .request(Request.Builder().url("https://example.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body?.toResponseBody())
            .build()
    }

    private fun routeInfo(
        requestedModel: String,
        resolvedModel: String,
        protocolType: String,
        requiresReasoningEcho: Boolean,
        apiBase: String = "https://example.com",
        wireApi: String = OpenAiWireApi.CHAT_COMPLETIONS,
    ) = HttpController.ChatCompletionRouteInfo(
        requestedModel = requestedModel,
        resolvedModel = resolvedModel,
        apiBase = apiBase,
        providerProfileId = "test",
        providerProfileName = "Test",
        routeTag = "test",
        bindingApplied = false,
        bindingProfileMissing = false,
        overrideApplied = true,
        protocolType = protocolType,
        wireApi = wireApi,
        requiresReasoningEcho = requiresReasoningEcho
    )
    @Test
    fun `qwen openai compatible route reclassifies leading content before closing think tag`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride(),
                resolveRouteInfoOp = { model, _, _, _, _, protocolType, _ ->
                    routeInfo(
                        requestedModel = model,
                        resolvedModel = "qwen3.6-plus",
                        protocolType = protocolType ?: "openai_compatible",
                        requiresReasoningEcho = false
                    )
                },
                streamRequestOp = { _, _, listener, _, _, _, _, _, _, _ ->
                    val source = dummyEventSource()
                    listener.onOpen(source, okResponse())
                    listener.onEvent(
                        source,
                        null,
                        "message",
                        """{"choices":[{"delta":{"content":"first reasoning</th"}}]}"""
                    )
                    listener.onEvent(
                        source,
                        null,
                        "message",
                        """{"choices":[{"delta":{"content":"ink>final answer"}}]}"""
                    )
                    listener.onEvent(source, null, "message", "[DONE]")
                    source
                },
                streamIdleWatchdogMs = 5_000L,
                json = json
            )

            val turn = client.streamTurn(request = simpleRequest())

            assertEquals("first reasoning", turn.reasoning)
            assertEquals("final answer", turn.message.contentText())
        } finally {
            scope.cancel()
        }
    }

}
