package cn.com.omnimind.bot.function

import cn.com.omnimind.baselib.i18n.PromptLocale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FunctionApiContractTest {
    @Test
    fun `register tool exposes only canonical function spec`() {
        val mcpTool = FunctionApi.mcpToolDefinitions.single {
            it["name"] == FunctionApi.FUNCTION_REGISTER
        }
        val mcpInput = FunctionJson.mapArg(mcpTool["inputSchema"])
        val mcpProperties = FunctionJson.mapArg(mcpInput["properties"])

        assertEquals(setOf("function"), mcpProperties.keys)
        assertEquals(listOf("function"), FunctionJson.listArg(mcpInput["required"]))

        val staticTool = FunctionApi.staticToolDefinitions(PromptLocale.EN_US).single { definition ->
            val function = definition["function"] as? JsonObject
            function?.get("name")?.jsonPrimitive?.content == FunctionApi.FUNCTION_REGISTER
        }
        val staticFunction = staticTool["function"] as JsonObject
        val staticInput = staticFunction["parameters"] as JsonObject
        val staticProperties = staticInput["properties"] as JsonObject

        assertTrue(staticProperties.containsKey("function"))
        assertFalse(staticProperties.containsKey("steps"))
        assertFalse(staticProperties.containsKey("function_id"))
    }

    @Test
    fun `prompt candidates only render canonical function fields`() {
        val candidate = linkedMapOf<String, Any?>(
            "schema_version" to "omniflow.function.v2",
            "function_id" to "search_product",
            "name" to "Search product",
            "description" to "Search one product",
            "input_schema" to linkedMapOf(
                "type" to "object",
                "properties" to linkedMapOf<String, Any?>(),
                "required" to emptyList<String>(),
                "additionalProperties" to false,
            ),
            "bindings" to emptyList<Map<String, Any?>>(),
            "steps" to listOf(
                linkedMapOf(
                    "step_index" to 0,
                    "source_state_id" to "state-0",
                    "action" to linkedMapOf(
                        "tool" to "wait",
                        "args" to linkedMapOf("duration_ms" to 1000),
                    ),
                )
            ),
            "checker_rules" to emptyList<Map<String, Any?>>(),
            "agent_visible" to true,
            "score" to "LEGACY_SCORE",
            "recall_scope" to "LEGACY_SCOPE",
            "agent_reuse" to linkedMapOf(
                "reuse_when" to "LEGACY_REUSE",
                "success_signal" to "LEGACY_SUCCESS",
            ),
        )

        val prompt = FunctionApi.buildPromptCandidateContext(
            candidates = listOf(candidate),
            locale = PromptLocale.EN_US,
        )

        assertTrue(prompt.contains("`search_product`"))
        assertFalse(prompt.contains("LEGACY_SCORE"))
        assertFalse(prompt.contains("LEGACY_SCOPE"))
        assertFalse(prompt.contains("LEGACY_REUSE"))
        assertFalse(prompt.contains("LEGACY_SUCCESS"))
    }
}
