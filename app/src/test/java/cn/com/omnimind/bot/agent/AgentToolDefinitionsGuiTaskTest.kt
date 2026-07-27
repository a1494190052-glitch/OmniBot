package cn.com.omnimind.bot.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentToolDefinitionsGuiTaskTest {
    @Test
    fun `vlm task exposes only the user goal`() {
        val function = AgentToolDefinitions.guiTaskTool["function"] as JsonObject
        val parameters = function["parameters"] as JsonObject
        val properties = parameters["properties"] as JsonObject
        val required = parameters["required"] as JsonArray

        assertEquals(setOf("goal"), properties.keys)
        assertEquals(listOf("goal"), required.map { it.jsonPrimitive.content })
        assertEquals(false, parameters["additionalProperties"]?.jsonPrimitive?.boolean)
    }
}
