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
            bridgeCall = { operation, payload ->
                calls += "$operation:${payload["action"]?.toString() ?: ""}"
                when (operation) {
                    "catalog" -> emptyMap()
                    "recall" -> mapOf(
                        "functions" to listOf(
                            mapOf(
                                "function_id" to "open_settings",
                                "description" to "stale description",
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
        assertEquals(listOf("catalog:replace", "recall:", "recall:"), calls)
        assertEquals("omniflow_python", second["runtime_source"])
    }

    @Test
    fun `python miss stays a miss without Kotlin fallback`() = runBlocking {
        val adapter = OmniFlowFunctionRecallAdapter(
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
            bridgeCall = { operation, _ ->
                if (operation == "recall") {
                    mapOf("functions" to listOf(mapOf("function_id" to "stale_hidden_function")))
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
    fun `Python bridge failure reports unavailable`() = runBlocking {
        var bridgeCalled = false
        val adapter = OmniFlowFunctionRecallAdapter(
            bridgeCall = { _, _ ->
                bridgeCalled = true
                error("runtime unavailable")
            },
        )

        val result = adapter.recall(
            request = mapOf("goal" to "open settings"),
            functionSpecs = listOf(functionSpec()),
        )

        assertEquals("omniflow_python", result["runtime_source"])
        assertEquals("unavailable", result["retrieval_state"])
        assertEquals("python_recall_error:runtime unavailable", result["reason"])
        assertTrue(bridgeCalled)
    }

    @Test
    fun `invalid catalog entry does not block valid recall`() = runBlocking {
        val adapter = OmniFlowFunctionRecallAdapter(
            bridgeCall = { operation, payload ->
                when (operation) {
                    "catalog" -> when (payload["action"]) {
                        "replace" -> mapOf(
                            "invalid_functions" to mapOf(
                                "legacy_target" to "function_action_target_forbidden:0",
                            ),
                        )
                        "clear" -> emptyMap()
                        "put" -> {
                            val function = payload["function"] as Map<*, *>
                            if (function["function_id"] == "legacy_target") {
                                error("function_action_target_forbidden:0")
                            }
                            emptyMap()
                        }
                        else -> error("unexpected catalog action: ${payload["action"]}")
                    }
                    "recall" -> mapOf(
                        "functions" to listOf(mapOf("function_id" to "open_settings")),
                    )
                    else -> error("unexpected operation: $operation")
                }
            },
        )

        val result = adapter.recall(
            request = mapOf("goal" to "open settings"),
            functionSpecs = listOf(invalidFunctionSpec(), functionSpec()),
        )

        assertEquals("has_candidates", result["retrieval_state"])
        assertEquals(
            mapOf("legacy_target" to "function_action_target_forbidden:0"),
            result["invalid_functions"],
        )
        val candidate = (result["candidates"] as List<*>).single() as Map<*, *>
        assertEquals("open_settings", candidate["function_id"])
    }

    private fun functionSpec(): Map<String, Any?> = linkedMapOf(
        "schema_version" to "omniflow.function.v2",
        "function_id" to "open_settings",
        "name" to "Open device settings",
        "description" to "Open the Android settings app",
        "input_schema" to linkedMapOf(
            "type" to "object",
            "properties" to emptyMap<String, Any?>(),
            "required" to emptyList<String>(),
            "additionalProperties" to false,
        ),
        "bindings" to emptyList<Map<String, Any?>>(),
        "steps" to listOf(
            linkedMapOf(
                "step_index" to 0,
                "source_state_id" to "state-0",
                "action" to linkedMapOf(
                    "tool" to "open_app",
                    "args" to mapOf("package_name" to "com.android.settings"),
                ),
            ),
        ),
        "checker_rules" to emptyList<Map<String, Any?>>(),
        "agent_visible" to true,
    )

    private fun invalidFunctionSpec(): Map<String, Any?> = linkedMapOf(
        "schema_version" to "omniflow.function.v2",
        "function_id" to "legacy_target",
        "name" to "Legacy target",
        "description" to "Invalid legacy Function",
        "input_schema" to linkedMapOf(
            "type" to "object",
            "properties" to emptyMap<String, Any?>(),
            "required" to emptyList<String>(),
            "additionalProperties" to false,
        ),
        "bindings" to emptyList<Map<String, Any?>>(),
        "steps" to listOf(
            linkedMapOf(
                "step_index" to 0,
                "source_state_id" to "state-0",
                "action" to linkedMapOf(
                    "tool" to "click",
                    "args" to mapOf(
                        "x" to 10,
                        "y" to 20,
                        "target" to mapOf("text" to "legacy"),
                    ),
                ),
            ),
        ),
        "checker_rules" to emptyList<Map<String, Any?>>(),
        "agent_visible" to true,
    )
}
