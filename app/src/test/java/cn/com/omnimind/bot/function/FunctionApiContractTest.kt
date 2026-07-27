package cn.com.omnimind.bot.function

import cn.com.omnimind.baselib.i18n.PromptLocale
import cn.com.omnimind.bot.runlog.listArg
import cn.com.omnimind.bot.runlog.mapArg
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
        val mcpInput = mapArg(mcpTool["inputSchema"])
        val mcpProperties = mapArg(mcpInput["properties"])

        assertEquals(setOf("function"), mcpProperties.keys)
        assertEquals(listOf("function"), listArg(mcpInput["required"]))
        val functionSchema = mapArg(mcpProperties["function"])
        val functionProperties = mapArg(functionSchema["properties"])
        val checkerRules = mapArg(functionProperties["checker_rules"])
        val checker = mapArg(checkerRules["items"])
        val checkerProperties = mapArg(checker["properties"])
        assertEquals(
            setOf("schema_version", "trigger", "source_state_id", "action"),
            checkerProperties.keys,
        )
        assertFalse(checker.containsKey("${'$'}ref"))

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
    fun `schema bundle exposes the canonical checker rule`() {
        val schemas = mapArg(FunctionApi.schemaBundle()["schemas"])
        val checker = mapArg(schemas["omniflow.checker_rule.v1"])
        val checkerProperties = mapArg(checker["properties"])

        assertEquals(
            setOf("schema_version", "trigger", "source_state_id", "action"),
            checkerProperties.keys,
        )
        assertEquals(
            listOf("schema_version", "trigger", "source_state_id", "action"),
            listArg(checker["required"]),
        )
    }

    @Test
    fun `run log conversion exposes default offline enhancement`() {
        val convertTool = FunctionApi.mcpToolDefinitions.single {
            it["name"] == FunctionApi.RUN_LOG_CONVERT
        }
        val convertInput = mapArg(convertTool["inputSchema"])
        val convertProperties = mapArg(convertInput["properties"])
        val enhance = mapArg(convertProperties["enhance"])
        val updateTool = FunctionApi.mcpToolDefinitions.single {
            it["name"] == FunctionApi.FUNCTION_UPDATE
        }

        assertEquals("boolean", enhance["type"])
        assertTrue(enhance["description"].toString().contains("Default true"))
        assertTrue(enhance["description"].toString().contains("After base registration"))
        assertTrue(updateTool["description"].toString().contains("registers the base Function first"))
    }

    @Test
    fun `recall uses the bridge limit field without a Kotlin alias`() {
        val recallTool = FunctionApi.mcpToolDefinitions.single {
            it["name"] == FunctionApi.FUNCTION_RECALL
        }
        val properties = mapArg(mapArg(recallTool["inputSchema"])["properties"])

        assertEquals(setOf("goal", "limit"), properties.keys)
        assertFalse(properties.containsKey("k"))
    }

    @Test
    fun `function skill exposes exactly its profile tools`() {
        val names = FunctionApi.staticToolDefinitions(PromptLocale.EN_US).map { definition ->
            val function = definition["function"] as JsonObject
            function["name"]!!.jsonPrimitive.content
        }.toSet()

        assertEquals(FunctionApi.profileTools, names)
        assertFalse(FunctionApi.FUNCTION_RECALL in names)
    }
}
