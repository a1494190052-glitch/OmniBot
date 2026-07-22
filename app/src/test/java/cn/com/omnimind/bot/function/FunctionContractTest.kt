package cn.com.omnimind.bot.function

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class FunctionContractTest {
    @Test
    fun `canonical function keeps only persisted action args`() {
        val canonical = FunctionContract.canonical(
            functionSpec(
                steps = listOf(
                    linkedMapOf(
                        "step_index" to 0,
                        "source_state_id" to "state-1",
                        "action" to mapOf(
                            "tool" to "click",
                        "args" to mapOf(
                            "x" to 500.0,
                            "y" to 500.0,
                            "target_description" to "Settings",
                            "node_resource_id" to "settings_button",
                        ),
                        ),
                    ),
                ),
            ),
        )

        val step = FunctionJson.mapArg(FunctionJson.listArg(canonical["steps"]).single())
        val action = FunctionJson.mapArg(step["action"])
        val args = FunctionJson.mapArg(action["args"])
        assertEquals(mapOf("x" to 500L, "y" to 500L), args)
        assertEquals("state-1", step["source_state_id"])
        assertEquals(
            setOf(
                "schema_version",
                "function_id",
                "name",
                "description",
                "input_schema",
                "bindings",
                "steps",
                "checker_rules",
                "agent_visible",
            ),
            canonical.keys,
        )
        assertFalse(canonical.toString().contains("target_description"))
        assertFalse(canonical.toString().contains("node_resource_id"))
    }

    @Test
    fun `canonical function rejects storage metadata in action args`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            FunctionContract.canonical(
                functionSpec(
                    steps = listOf(
                        linkedMapOf(
                            "step_index" to 0,
                            "source_state_id" to "state-1",
                            "action" to mapOf(
                                "tool" to "click",
                                "args" to mapOf(
                                    "x" to 500,
                                    "y" to 500,
                                    "xml" to "<hierarchy />",
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }

        assertEquals(
            "canonical_action_unknown_args:click:xml",
            error.message,
        )
    }

    private fun functionSpec(steps: List<Map<String, Any?>>): Map<String, Any?> = linkedMapOf(
        "schema_version" to FunctionContract.SCHEMA_VERSION,
        "function_id" to "fn_settings",
        "name" to "Open settings",
        "description" to "Open Android settings",
        "input_schema" to linkedMapOf(
            "type" to "object",
            "properties" to emptyMap<String, Any?>(),
            "required" to emptyList<String>(),
            "additionalProperties" to false,
        ),
        "bindings" to emptyList<Map<String, Any?>>(),
        "steps" to steps,
        "checker_rules" to emptyList<Map<String, Any?>>(),
        "agent_visible" to true,
    )
}
