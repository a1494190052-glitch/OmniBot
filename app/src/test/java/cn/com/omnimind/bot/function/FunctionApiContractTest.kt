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

        assertEquals(setOf("function_spec"), mcpProperties.keys)
        assertEquals(listOf("function_spec"), FunctionJson.listArg(mcpInput["required"]))

        val staticTool = FunctionApi.staticToolDefinitions(PromptLocale.EN_US).single { definition ->
            val function = definition["function"] as? JsonObject
            function?.get("name")?.jsonPrimitive?.content == FunctionApi.FUNCTION_REGISTER
        }
        val staticFunction = staticTool["function"] as JsonObject
        val staticInput = staticFunction["parameters"] as JsonObject
        val staticProperties = staticInput["properties"] as JsonObject

        assertTrue(staticProperties.containsKey("function_spec"))
        assertFalse(staticProperties.containsKey("steps"))
        assertFalse(staticProperties.containsKey("function_id"))
    }
}
