package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.i18n.PromptLocale
import cn.com.omnimind.baselib.runlog.OobCanonicalActionSchema
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VLMToolDefinitionsTest {
    @Test
    fun `model visible VLM tools expose canonical action schema`() {
        val toolNames = VLMToolDefinitions.tools(PromptLocale.EN_US)
            .map { it.function.name }
            .toSet()

        assertTrue(toolNames.contains("wait"))
        assertTrue(toolNames.contains("click"))
        assertTrue(toolNames.contains("input_text"))
        assertFalse(toolNames.contains("type"))
        assertTrue(toolNames.contains("swipe"))
        assertFalse(toolNames.contains("get_state"))
        assertTrue(toolNames.contains("call_tool"))
        assertTrue(toolNames.contains("finished"))
        assertTrue(toolNames.containsAll(
            OobCanonicalActionSchema.modelVisibleTools
                .map { it.name }
                .filterNot { it == "get_state" }
        ))
    }

    @Test
    fun `call tool exposes function target and arguments`() {
        val tool = VLMToolDefinitions.tools(PromptLocale.EN_US)
            .single { it.function.name == "call_tool" }
        val parameters = tool.function.parameters
        val properties = parameters["properties"]!!.jsonObject
        val required = parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content }

        assertTrue(properties.containsKey("function_id"))
        assertTrue(properties.containsKey("tool_name"))
        assertTrue(properties.containsKey("arguments"))
        assertTrue(required.contains("arguments"))
    }

    @Test
    fun `input text exposes only canonical text argument`() {
        val tool = VLMToolDefinitions.tools(PromptLocale.EN_US)
            .single { it.function.name == "input_text" }
        val properties = tool.function.parameters["properties"]!!.jsonObject

        assertTrue(properties.containsKey("target_description"))
        assertTrue(properties.containsKey("text"))
        assertFalse(properties.containsKey("content"))
        assertFalse(properties.containsKey("value"))
    }

    @Test
    fun `argument validation rejects non schema aliases`() {
        assertThrows(IllegalArgumentException::class.java) {
            VLMToolDefinitions.validateArguments(
                "call_tool",
                buildJsonObject {
                    put("functionId", "legacy")
                    put("arguments", buildJsonObject {})
                },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            VLMToolDefinitions.validateArguments(
                "input_text",
                buildJsonObject {
                    put("target_description", "search")
                    put("content", "cat")
                    put("x", 500)
                    put("y", 500)
                },
            )
        }
    }

    @Test
    fun `prompt guide teaches canonical tool calls`() {
        val promptGuide = VLMToolDefinitions.renderPromptGuide(PromptLocale.EN_US)

        assertTrue(promptGuide.contains("wait(time_s?)"))
        assertTrue(promptGuide.contains("input_text(target_description, text, element_index?, x, y)"))
        assertTrue(promptGuide.contains("call_tool(function_id?, tool_name?, arguments)"))
        assertTrue(promptGuide.contains("recalled reusable workflow"))
        assertFalse(promptGuide.contains("get_state("))
        assertTrue(promptGuide.contains("Coordinates are fallback only"))
        assertTrue(promptGuide.contains("Use wait only when the page is clearly loading"))
    }

    @Test
    fun `compact action schema requires native tool call and rejects legacy formats`() {
        val promptGuide = VLMToolDefinitions.renderCompactActionSchemaGuide(PromptLocale.EN_US)

        assertTrue(promptGuide.contains("native tool_call only"))
        assertTrue(promptGuide.contains("Do not use legacy action/coordinate/coordinate2"))
        assertFalse(promptGuide.contains("fallback JSON"))
        assertFalse(promptGuide.contains("\"tool\":\"tool_name\""))
        assertFalse(promptGuide.contains("get_state"))
        assertTrue(promptGuide.contains("wait"))
    }
}
