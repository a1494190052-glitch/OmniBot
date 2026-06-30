package cn.com.omnimind.bot.agent

import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.llm.contentText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpControllerResponsesTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `responses request body maps chat history to instructions input and function call output`() {
        val method = HttpController::class.java.getDeclaredMethod(
            "buildOpenAIResponsesRequestBody",
            String::class.java,
            String::class.java
        )
        method.isAccessible = true
        val payload = method.invoke(
            HttpController,
            """
                {
                  "model": "gpt-4.1",
                  "stream": true,
                  "max_completion_tokens": 256,
                  "tool_choice": "required",
                  "tools": [
                    {
                      "type": "function",
                      "function": {
                        "name": "get_weather",
                        "description": "Get weather",
                        "parameters": {"type":"object","properties":{"city":{"type":"string"}}}
                      }
                    }
                  ],
                  "messages": [
                    {"role": "system", "content": "You are helpful."},
                    {"role": "user", "content": "Weather in Shanghai?"},
                    {
                      "role": "assistant",
                      "tool_calls": [
                        {
                          "id": "call_1",
                          "type": "function",
                          "function": {"name": "get_weather", "arguments": "{\"city\":\"Shanghai\"}"}
                        }
                      ]
                    },
                    {"role": "tool", "tool_call_id": "call_1", "content": "{\"temp\":28}"}
                  ]
                }
            """.trimIndent(),
            "gpt-4.1-mini"
        ) as String

        val root = json.parseToJsonElement(payload).jsonObject
        assertEquals("gpt-4.1-mini", root["model"]?.jsonPrimitive?.content)
        assertEquals("You are helpful.", root["instructions"]?.jsonPrimitive?.content)
        assertEquals("required", root["tool_choice"]?.jsonPrimitive?.content)
        assertEquals("256", root["max_output_tokens"]?.jsonPrimitive?.content)

        val input = root["input"]!!.jsonArray
        assertEquals("user", input[0].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals(
            "Weather in Shanghai?",
            input[0].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content
        )
        assertEquals("function_call", input[1].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("call_1", input[1].jsonObject["call_id"]?.jsonPrimitive?.content)
        assertEquals("function_call_output", input[2].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("{\"temp\":28}", input[2].jsonObject["output"]?.jsonPrimitive?.content)

        val tools = root["tools"]!!.jsonArray
        assertEquals("function", tools[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("get_weather", tools[0].jsonObject["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `responses stream adapter converts output text events into chat chunks`() {
        val chunks = mutableListOf<String>()
        val wrapped = HttpController.wrapResponsesListener(
            object : EventSourceListener() {
                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    chunks += data
                }
            }
        )

        val source = dummyEventSource()
        wrapped.onEvent(
            source,
            null,
            "response.output_text.delta",
            """{"type":"response.output_text.delta","delta":"Hello"}"""
        )
        wrapped.onEvent(
            source,
            null,
            "response.completed",
            """{"type":"response.completed","response":{"usage":{"prompt_tokens":4,"completion_tokens":1,"total_tokens":5}}}"""
        )

        val accumulator = AgentLlmStreamAccumulator(json)
        chunks.forEach(accumulator::consume)
        val turn = accumulator.buildTurn()

        assertEquals("Hello", turn.message.contentText())
        assertEquals(4, turn.usage?.promptTokens)
    }

    @Test
    fun `responses stream adapter converts function call events into tool calls`() {
        val chunks = mutableListOf<String>()
        val wrapped = HttpController.wrapResponsesListener(
            object : EventSourceListener() {
                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    chunks += data
                }
            }
        )

        val source = dummyEventSource()
        wrapped.onEvent(
            source,
            null,
            "response.output_item.added",
            """{"type":"response.output_item.added","item":{"type":"function_call","call_id":"call_7","name":"get_weather","arguments":"{\"city\":\"Shanghai\"}"}}"""
        )
        wrapped.onEvent(
            source,
            null,
            "response.completed",
            """{"type":"response.completed","response":{"usage":{"prompt_tokens":4,"completion_tokens":1,"total_tokens":5}}}"""
        )

        val accumulator = AgentLlmStreamAccumulator(json)
        chunks.forEach(accumulator::consume)
        val turn = accumulator.buildTurn()

        assertEquals(1, turn.message.toolCalls?.size)
        assertEquals("get_weather", turn.message.toolCalls?.first()?.function?.name)
        assertTrue(turn.message.toolCalls?.first()?.function?.arguments?.contains("Shanghai") == true)
        assertEquals("tool_calls", turn.finishReason)
    }

    @Test
    fun `responses stream adapter keeps item_id argument deltas on original tool call`() {
        val chunks = mutableListOf<String>()
        val wrapped = HttpController.wrapResponsesListener(
            object : EventSourceListener() {
                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    chunks += data
                }
            }
        )

        val source = dummyEventSource()
        wrapped.onEvent(
            source,
            null,
            "response.output_item.added",
            """{"type":"response.output_item.added","item":{"type":"function_call","id":"msg_tool_1","call_id":"call_7","name":"get_weather","arguments":"","status":"in_progress"}}"""
        )
        wrapped.onEvent(
            source,
            null,
            "response.function_call_arguments.delta",
            """{"type":"response.function_call_arguments.delta","item_id":"msg_tool_1","delta":"{\"city\":\"Shanghai\"}","output_index":1}"""
        )
        wrapped.onEvent(
            source,
            null,
            "response.function_call_arguments.done",
            """{"type":"response.function_call_arguments.done","item_id":"msg_tool_1","name":"get_weather","arguments":"{\"city\":\"Shanghai\"}","output_index":1}"""
        )
        wrapped.onEvent(
            source,
            null,
            "response.completed",
            """{"type":"response.completed","response":{"usage":{"prompt_tokens":4,"completion_tokens":1,"total_tokens":5}}}"""
        )

        val accumulator = AgentLlmStreamAccumulator(json)
        chunks.forEach(accumulator::consume)
        val turn = accumulator.buildTurn()

        assertEquals(1, turn.message.toolCalls?.size)
        assertEquals("call_7", turn.message.toolCalls?.first()?.id)
        assertEquals("get_weather", turn.message.toolCalls?.first()?.function?.name)
        assertEquals("""{"city":"Shanghai"}""", turn.message.toolCalls?.first()?.function?.arguments)
        assertEquals("tool_calls", turn.finishReason)
    }

    @Test
    fun `responses stream adapter deduplicates final assistant text snapshots`() {
        val chunks = mutableListOf<String>()
        val wrapped = HttpController.wrapResponsesListener(
            object : EventSourceListener() {
                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    chunks += data
                }
            }
        )

        val source = dummyEventSource()
        wrapped.onEvent(
            source,
            null,
            "response.output_text.delta",
            """{"type":"response.output_text.delta","delta":"Hello "}"""
        )
        wrapped.onEvent(
            source,
            null,
            "response.output_text.delta",
            """{"type":"response.output_text.delta","delta":"world"}"""
        )
        wrapped.onEvent(
            source,
            null,
            "response.content_part.done",
            """{"type":"response.content_part.done","part":{"type":"output_text","text":"Hello world"}}"""
        )
        wrapped.onEvent(
            source,
            null,
            "response.output_item.done",
            """{"type":"response.output_item.done","item":{"type":"message","content":[{"type":"output_text","text":"Hello world"}]}}"""
        )
        wrapped.onEvent(
            source,
            null,
            "response.completed",
            """{"type":"response.completed","response":{"usage":{"prompt_tokens":4,"completion_tokens":2,"total_tokens":6}}}"""
        )

        val accumulator = AgentLlmStreamAccumulator(json)
        chunks.forEach(accumulator::consume)
        val turn = accumulator.buildTurn()

        assertEquals("Hello world", turn.message.contentText())
        assertEquals("stop", turn.finishReason)
        assertEquals(2, turn.usage?.completionTokens)
    }

    private fun dummyEventSource(): EventSource {
        return object : EventSource {
            override fun request(): Request =
                Request.Builder().url("https://example.com").build()

            override fun cancel() = Unit
        }
    }
}
