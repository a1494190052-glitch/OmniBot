package cn.com.omnimind.bot.omniflow

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniFlowFunctionRecallAdapterTest {
    @Test
    fun `recall uses the Python catalog without Kotlin synchronization`() = runBlocking {
        val calls = mutableListOf<String>()
        val adapter = OmniFlowFunctionRecallAdapter { operation, _ ->
            calls += operation
            mapOf(
                "candidates" to listOf(
                    mapOf(
                        "function" to functionSpec(),
                        "retrieval" to mapOf(
                            "score" to 0.92,
                            "source" to "goal_token_jaccard",
                            "rank" to 1,
                        ),
                    ),
                ),
            )
        }

        val result = adapter.recall(mapOf("goal" to "open settings", "k" to 4))

        val candidate = (result["candidates"] as List<*>).single() as Map<*, *>
        val function = candidate["function"] as Map<*, *>
        val retrieval = candidate["retrieval"] as Map<*, *>
        assertEquals(listOf("recall"), calls)
        assertEquals("has_candidates", result["retrieval_state"])
        assertEquals("open_settings", function["function_id"])
        assertEquals("Open device settings", function["name"])
        assertEquals(0.92, retrieval["score"])
        assertEquals("goal_token_jaccard", retrieval["source"])
        assertEquals(1, retrieval["rank"])
    }

    @Test
    fun `Python bridge failure reports unavailable`() = runBlocking {
        var bridgeCalled = false
        val adapter = OmniFlowFunctionRecallAdapter { _, _ ->
            bridgeCalled = true
            error("runtime unavailable")
        }

        val result = adapter.recall(mapOf("goal" to "open settings"))

        assertEquals("unavailable", result["retrieval_state"])
        assertEquals("python_recall_error:runtime unavailable", result["reason"])
        assertTrue(bridgeCalled)
    }

    @Test
    fun `missing retrieval score reports a contract error instead of defaulting to zero`() = runBlocking {
        val adapter = OmniFlowFunctionRecallAdapter { _, _ ->
            mapOf(
                "candidates" to listOf(
                    mapOf(
                        "function" to functionSpec(),
                        "retrieval" to mapOf(
                            "source" to "goal_token_jaccard",
                            "rank" to 1,
                        ),
                    ),
                ),
            )
        }

        val result = adapter.recall(mapOf("goal" to "open settings"))

        assertEquals(false, result["success"])
        assertEquals("unavailable", result["retrieval_state"])
        assertEquals(
            "python_recall_error:recall_candidate_score_invalid",
            result["reason"],
        )
        assertEquals(emptyList<Any>(), result["candidates"])
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
}
