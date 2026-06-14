package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.i18n.PromptLocale
import cn.com.omnimind.baselib.runlog.OobCanonicalActionSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
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
        assertFalse(toolNames.contains("call_tool"))
        assertTrue(toolNames.contains("finished"))
        assertTrue(toolNames.containsAll(
            OobCanonicalActionSchema.modelVisibleTools
                .map { it.name }
                .filterNot { it == "get_state" }
        ))
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
    fun `argument validation rejects hidden tools and non schema aliases`() {
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
        assertTrue(promptGuide.contains("Function recall, argument filling, and replay are handled automatically"))
        assertTrue(promptGuide.contains("do not emit call_tool, function_id, or hidden Function tools"))
        assertFalse(promptGuide.contains("call_tool(function_id?, tool_name?, arguments)"))
        assertFalse(promptGuide.contains("preferred_call_tool"))
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
