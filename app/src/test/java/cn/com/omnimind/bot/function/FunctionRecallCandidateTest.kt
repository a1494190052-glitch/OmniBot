package cn.com.omnimind.bot.function

import org.junit.Assert.assertEquals
import org.junit.Test

class FunctionRecallCandidateTest {
    @Test
    fun `canonical recall envelope exposes typed retrieval evidence`() {
        val raw = mapOf(
            "function" to functionSpec(),
            "retrieval" to mapOf(
                "score" to 0.92,
                "source" to "goal_token_jaccard",
                "rank" to 1,
            ),
        )

        val candidate = FunctionRecallCandidate.parse(raw)

        assertEquals("open_settings", candidate.functionId)
        assertEquals(0.92, candidate.score, 0.0)
        assertEquals("goal_token_jaccard", candidate.source)
        assertEquals(1, candidate.rank)
        assertEquals(raw, candidate.toMap())
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
