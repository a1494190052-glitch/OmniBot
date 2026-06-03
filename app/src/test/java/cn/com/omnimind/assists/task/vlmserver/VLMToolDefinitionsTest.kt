package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.i18n.PromptLocale
import cn.com.omnimind.baselib.runlog.OobCanonicalActionSchema
import kotlinx.serialization.json.boolean
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
    fun `model visible VLM tools do not expose legacy wait action`() {
        val toolNames = VLMToolDefinitions.tools(PromptLocale.EN_US)
            .map { it.function.name }
            .toSet()

        assertFalse(toolNames.contains("wait"))
        assertTrue(toolNames.contains("click"))
        assertTrue(toolNames.contains("input_text"))
        assertFalse(toolNames.contains("type"))
        assertTrue(toolNames.contains("scroll"))
        assertTrue(toolNames.contains("oob_function_run"))
        assertFalse(toolNames.contains("get_state"))
        assertTrue(toolNames.contains("finished"))
        assertFalse(toolNames.contains("call_function"))
        assertFalse(toolNames.contains("run_function"))
        assertTrue(toolNames.containsAll(OobCanonicalActionSchema.modelVisibleTools.map { it.name }))
    }

    @Test
    fun `oob function run is exposed as callable candidate tool with open arguments`() {
        val tool = VLMToolDefinitions.tools(PromptLocale.EN_US)
            .single { it.function.name == "oob_function_run" }
        val parameters = tool.function.parameters
        val properties = parameters["properties"]!!.jsonObject
        val argumentsSchema = properties["arguments"]!!.jsonObject
        val required = parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content }

        assertTrue(tool.function.description.contains("current-turn OmniFlow recall context"))
        assertTrue(properties.containsKey("function_id"))
        assertTrue(properties.containsKey("arguments"))
        assertTrue(properties.keys == setOf("function_id", "arguments"))
        assertTrue(required.containsAll(listOf("function_id", "arguments")))
        assertTrue(argumentsSchema["additionalProperties"]!!.jsonPrimitive.boolean)
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
                "oob_function_run",
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
    fun `prompt guide does not teach the model to call wait`() {
        val promptGuide = VLMToolDefinitions.renderPromptGuide(PromptLocale.EN_US)

        assertFalse(promptGuide.contains("wait("))
        assertFalse(promptGuide.contains("waiting actions"))
        assertTrue(promptGuide.contains("input_text(target_description, text, element_index?, x, y)"))
        assertTrue(promptGuide.contains("oob_function_run(function_id, arguments)"))
        assertFalse(promptGuide.contains("get_state("))
        assertFalse(promptGuide.contains("call_function("))
        assertFalse(promptGuide.contains("run_function("))
        assertTrue(promptGuide.contains("Coordinates are fallback only"))
        assertTrue(promptGuide.contains("page settling and stability detection are handled internally"))
    }

    @Test
    fun `compact action schema requires native tool call and rejects legacy formats`() {
        val promptGuide = VLMToolDefinitions.renderCompactActionSchemaGuide(PromptLocale.EN_US)

        assertTrue(promptGuide.contains("native tool_call only"))
        assertTrue(promptGuide.contains("Do not use legacy action/swipe/coordinate/coordinate2"))
        assertFalse(promptGuide.contains("fallback JSON"))
        assertFalse(promptGuide.contains("\"tool\":\"tool_name\""))
        assertFalse(promptGuide.contains("get_state"))
        assertFalse(promptGuide.contains("wait"))
    }
}
