package cn.com.omnimind.bot.omniflow

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniFlowFunctionRecallAdapterTest {
    @Test
    fun `python chooses id while candidate contract comes from current catalog`() = runBlocking {
        val calls = mutableListOf<String>()
        val adapter = OmniFlowFunctionRecallAdapter(
            enabled = { true },
            bridgeCall = { operation, payload ->
                calls += "$operation:${payload["action"].orEmpty()}"
                when (operation) {
                    "catalog" -> emptyMap()
                    "recall" -> mapOf(
                        "functions" to listOf(
                            mapOf(
                                "id" to "open_settings",
                                "description" to "stale description",
                                "actions" to listOf(mapOf("tool" to "wait")),
                            )
                        )
                    )
                    else -> error("unexpected operation: $operation")
                }
            },
        )
        val spec = functionSpec()

        val first = adapter.recall(
            request = mapOf("goal" to "open settings", "k" to 4),
            functionSpecs = listOf(spec),
        )
        val second = adapter.recall(
            request = mapOf("goal" to "open settings", "k" to 4),
            functionSpecs = listOf(spec),
        )

        val candidate = (first["candidates"] as List<*>).single() as Map<*, *>
        assertEquals("omniflow_python", first["runtime_source"])
        assertEquals("Open device settings", candidate["name"])
        assertEquals("Open the Android settings app", candidate["description"])
        assertEquals(listOf("catalog:clear", "catalog:put", "recall:", "recall:"), calls)
        assertEquals("omniflow_python", second["runtime_source"])
    }

    @Test
    fun `python miss stays a miss without Kotlin fallback`() = runBlocking {
        val adapter = OmniFlowFunctionRecallAdapter(
            enabled = { true },
            bridgeCall = { operation, _ ->
                if (operation == "recall") mapOf("functions" to emptyList<Any>()) else emptyMap()
            },
        )

        val result = adapter.recall(
            request = mapOf("goal" to "unrelated task"),
            functionSpecs = listOf(functionSpec()),
        )

        assertEquals("omniflow_python", result["runtime_source"])
        assertEquals("miss", result["retrieval_state"])
        assertEquals("python_recall_miss", result["reason"])
    }

    @Test
    fun `unknown Python id cannot escape the visible catalog`() = runBlocking {
        val adapter = OmniFlowFunctionRecallAdapter(
            enabled = { true },
            bridgeCall = { operation, _ ->
                if (operation == "recall") {
                    mapOf("functions" to listOf(mapOf("id" to "stale_hidden_function")))
                } else {
                    emptyMap()
                }
            },
        )

        val result = adapter.recall(
            request = mapOf("goal" to "open settings"),
            functionSpecs = listOf(functionSpec()),
        )

        assertEquals("omniflow_python", result["runtime_source"])
        assertEquals("miss", result["retrieval_state"])
        assertEquals("python_recall_miss", result["reason"])
    }

    @Test
    fun `disabled Python reports unavailable without bridge calls`() = runBlocking {
        var bridgeCalled = false
        val adapter = OmniFlowFunctionRecallAdapter(
            enabled = { false },
            bridgeCall = { _, _ ->
                bridgeCalled = true
                emptyMap()
            },
        )

        val result = adapter.recall(
            request = mapOf("goal" to "open settings"),
            functionSpecs = listOf(functionSpec()),
        )

        assertEquals("omniflow_python", result["runtime_source"])
        assertEquals("unavailable", result["retrieval_state"])
        assertEquals("python_not_ready", result["reason"])
        assertTrue(!bridgeCalled)
    }

    private fun functionSpec(): Map<String, Any?> = linkedMapOf(
        "schema_version" to "oob.reusable_function.v1",
        "function_id" to "open_settings",
        "name" to "Open device settings",
        "description" to "Open the Android settings app",
        "agent_visible" to true,
        "parameters" to linkedMapOf(
            "type" to "object",
            "properties" to emptyMap<String, Any?>(),
            "required" to emptyList<String>(),
            "additionalProperties" to false,
        ),
        "execution" to linkedMapOf(
            "steps" to listOf(
                linkedMapOf(
                    "id" to "step_1",
                    "tool" to "open_app",
                    "args" to mapOf("package_name" to "com.android.settings"),
                )
            )
        ),
    )

    private fun Any?.orEmpty(): String = this?.toString().orEmpty()
}
