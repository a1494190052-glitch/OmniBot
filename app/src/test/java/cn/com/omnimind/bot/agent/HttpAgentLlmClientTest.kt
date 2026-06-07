package cn.com.omnimind.bot.agent

import cn.com.omnimind.assists.controller.http.HttpController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
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
    fun `closed stream without completion signal fails instead of silently succeeding`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride(),
                streamRequestOp = { _, _, listener, _, _, _, _, _ ->
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
                streamRequestOp = { _, _, listener, _, _, _, _, _ ->
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
                streamRequestOp = { _, _, listener, _, _, _, _, _ ->
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
    fun `resolved route requiring reasoning echo preserves reasoning content even when override is not deepseek`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride(),
                resolveRouteInfoOp = { model, _, _, _, protocolType ->
                    routeInfo(
                        requestedModel = model,
                        resolvedModel = "deepseek-v4-flash",
                        protocolType = protocolType ?: "deepseek",
                        requiresReasoningEcho = true
                    )
                },
                streamRequestOp = { _, _, listener, _, _, _, _, _ ->
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
                resolveRouteInfoOp = { model, _, _, _, protocolType ->
                    routeInfo(
                        requestedModel = model,
                        resolvedModel = "qwen-plus",
                        protocolType = protocolType ?: "openai_compatible",
                        requiresReasoningEcho = false
                    )
                },
                streamRequestOp = { _, _, listener, _, _, _, _, _ ->
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

    private fun okResponse(): Response {
        return Response.Builder()
            .request(Request.Builder().url("https://example.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .build()
    }

    private fun routeInfo(
        requestedModel: String,
        resolvedModel: String,
        protocolType: String,
        requiresReasoningEcho: Boolean,
        apiBase: String = "https://example.com"
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
        requiresReasoningEcho = requiresReasoningEcho
    )
    @Test
    fun `qwen openai compatible route reclassifies leading content before closing think tag`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride(),
                resolveRouteInfoOp = { model, _, _, _, protocolType ->
                    routeInfo(
                        requestedModel = model,
                        resolvedModel = "qwen3.6-plus",
                        protocolType = protocolType ?: "openai_compatible",
                        requiresReasoningEcho = false
                    )
                },
                streamRequestOp = { _, _, listener, _, _, _, _, _ ->
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

    @Test
    fun `nvidia kimi guarded route retries conservative no thinking variant after leak`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val requestBodies = mutableListOf<String>()
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride(),
                resolveRouteInfoOp = { model, _, _, _, protocolType ->
                    routeInfo(
                        requestedModel = model,
                        resolvedModel = "kimi-k2.6",
                        protocolType = protocolType ?: "openai_compatible",
                        requiresReasoningEcho = false,
                        apiBase = "https://integrate.api.nvidia.com/v1"
                    )
                },
                streamRequestOp = { _, requestBodyJson, listener, _, _, _, _, _ ->
                    requestBodies += requestBodyJson
                    val source = dummyEventSource()
                    listener.onOpen(source, okResponse())
                    if (requestBodies.size == 1) {
                        listener.onEvent(
                            source,
                            null,
                            "message",
                            """{"choices":[{"delta":{"content":"# Understanding the User's Question\nThe user is asking for a fix"}}]}"""
                        )
                    } else {
                        listener.onEvent(
                            source,
                            null,
                            "message",
                            """{"choices":[{"delta":{"content":"final answer"}}]}"""
                        )
                        listener.onEvent(source, null, "message", "[DONE]")
                    }
                    source
                },
                streamIdleWatchdogMs = 5_000L,
                json = json
            )

            val turn = client.streamTurn(request = simpleRequest())

            assertEquals("final answer", turn.message.contentText())
            assertEquals(2, requestBodies.size)
            assertTrue(requestBodies[0].contains(""""enable_thinking":false"""))
            assertTrue(requestBodies[0].contains(""""reasoning_effort":"none""""))
            assertTrue(requestBodies[0].contains(""""thinking":{"type":"disabled"}"""))
            assertTrue(requestBodies[0].contains("stream_options"))
            assertTrue(requestBodies[1].contains(""""enable_thinking":false"""))
            assertTrue(requestBodies[1].contains(""""reasoning_effort":"none""""))
            assertTrue(requestBodies[1].contains(""""thinking":{"type":"disabled"}"""))
            assertTrue(!requestBodies[1].contains("stream_options"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `nvidia kimi guarded route fails after conservative variants still leak`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val requestBodies = mutableListOf<String>()
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride(),
                resolveRouteInfoOp = { model, _, _, _, protocolType ->
                    routeInfo(
                        requestedModel = model,
                        resolvedModel = "kimi-k2.6",
                        protocolType = protocolType ?: "openai_compatible",
                        requiresReasoningEcho = false,
                        apiBase = "https://integrate.api.nvidia.com/v1"
                    )
                },
                streamRequestOp = { _, requestBodyJson, listener, _, _, _, _, _ ->
                    requestBodies += requestBodyJson
                    val source = dummyEventSource()
                    listener.onOpen(source, okResponse())
                    listener.onEvent(
                        source,
                        null,
                        "message",
                        """{"choices":[{"delta":{"content":"# Understanding the User's Question\nThe user is asking for a fix"}}]}"""
                    )
                    source
                },
                streamIdleWatchdogMs = 5_000L,
                json = json
            )

            val error = runCatching {
                client.streamTurn(request = simpleRequest())
            }.exceptionOrNull()

            requireNotNull(error)
            assertTrue(error is AgentStreamReasoningLeakException)
            assertEquals(2, requestBodies.size)
            assertTrue(requestBodies[0].contains("stream_options"))
            assertTrue(!requestBodies[1].contains("stream_options"))
        } finally {
            scope.cancel()
        }
    }
}
