package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.i18n.PromptLocale
import cn.com.omnimind.baselib.runlog.OobCanonicalActionSchema
import com.google.gson.Gson
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
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
    fun `model visible tool schemas require coordinates instead of replacing them with indexes`() {
        val tools = VLMToolDefinitions.tools(PromptLocale.EN_US).associateBy { it.function.name }

        listOf("click", "input_text").forEach { toolName ->
            val tool = tools.getValue(toolName)
            val required = tool.function.parameters["required"]!!.jsonArray
                .mapNotNull { it.jsonPrimitive.contentOrNull }
                .toSet()
            val description = tool.function.description
            val elementIndexDescription = tool.function.parameters["properties"]!!
                .jsonObject
                .getValue("element_index")
                .jsonObject
                .getValue("description")
                .jsonPrimitive
                .content

            assertTrue(required.contains("x"))
            assertTrue(required.contains("y"))
            assertFalse(description.contains("prefer element_index", ignoreCase = true))
            assertFalse(description.contains("fallback only", ignoreCase = true))
            assertTrue(elementIndexDescription.contains("cannot replace required coordinates"))
        }
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
        assertTrue(promptGuide.contains("input_text(target_description, text, x, y, element_index?)"))
        assertTrue(promptGuide.contains("schema.required"))
        assertTrue(promptGuide.contains("Function recall, runtime resolve, and replay are handled automatically"))
        assertTrue(promptGuide.contains("same bounded runtime resolve path"))
        assertTrue(promptGuide.contains("fills public arguments before replay"))
        assertTrue(promptGuide.contains("one ordinary current-screen UI action after a replay miss"))
        assertTrue(promptGuide.contains("If this turn reaches the VLM, output exactly one ordinary UI action"))
        assertTrue(promptGuide.contains("do not emit call_tool, function_id, or hidden Function tools"))
        assertTrue(promptGuide.contains("Action choice: use click for visible buttons"))
        assertTrue(promptGuide.contains("use input_text when known text must be typed"))
        assertTrue(promptGuide.contains("use wait only for clear loading"))
        assertFalse(promptGuide.contains("fill_params"))
        assertFalse(promptGuide.contains("repair_step"))
        assertFalse(promptGuide.contains("call_tool(function_id?, tool_name?, arguments)"))
        assertFalse(promptGuide.contains("preferred_call_tool"))
        assertFalse(promptGuide.contains("get_state("))
        assertTrue(promptGuide.contains("Coordinate fields in schema.required must be 0..1000 relative coordinates"))
        assertTrue(promptGuide.contains("The system decodes coordinates to screen absolute pixels before execution"))
        assertTrue(promptGuide.contains("Use wait only when the page is clearly loading"))
    }

    @Test
    fun `compact action schema requires native tool call and rejects legacy formats`() {
        val promptGuide = VLMToolDefinitions.renderCompactActionSchemaGuide(PromptLocale.EN_US)

        assertTrue(promptGuide.contains("Allowed tools this turn"))
        assertTrue(promptGuide.contains("exactly one native tool_call"))
        assertTrue(promptGuide.contains("schema.required"))
        assertTrue(promptGuide.contains("about-20-word summary only"))
        assertTrue(promptGuide.contains("cannot replace coordinates or other fields listed in the selected tool's schema.required"))
        assertTrue(promptGuide.contains("Action choice: use click for visible buttons"))
        assertTrue(promptGuide.contains("use swipe when the target is not currently visible"))
        assertTrue(promptGuide.contains("first decide whether the user's goal is already satisfied"))
        assertTrue(promptGuide.contains("search box or input field"))
        assertTrue(promptGuide.contains("target input is focused"))
        assertFalse(promptGuide.contains("required=["))
        assertFalse(promptGuide.contains("fallback JSON"))
        assertFalse(promptGuide.contains("\"tool\":\"tool_name\""))
        assertFalse(promptGuide.contains("get_state"))
    }

    @Test
    fun `canonical action schema is single source for kotlin and dart accessors`() {
        val schema = readCanonicalActionSchema()
        val schemaTools = (schema["tools"] as List<*>)
            .map { it as Map<*, *> }
        val schemaToolNames = schemaTools.map { it["name"].toString() }
        val schemaArgsByTool = schemaTools.associate { tool ->
            tool["name"].toString() to (tool["args"] as List<*>)
                .map { (it as Map<*, *>)["name"].toString() }
        }

        assertTrue(schema["schema_version"] == OobCanonicalActionSchema.SCHEMA_VERSION)
        assertTrue(schema["root_tool"] == OobCanonicalActionSchema.ROOT_TOOL)
        assertTrue(schema["root_args"] == OobCanonicalActionSchema.ROOT_ARGS)
        assertTrue(schemaToolNames == OobCanonicalActionSchema.tools.map { it.name })
        OobCanonicalActionSchema.tools.forEach { tool ->
            assertTrue(schemaArgsByTool[tool.name] == tool.args.map { it.name })
        }

        val dart = readSource("ui/lib/features/task/run_log/oob_canonical_action_schema.dart")
        schemaToolNames.forEach { toolName ->
            assertTrue(dart.contains("name: \"$toolName\""))
        }
        schemaArgsByTool.values.flatten().distinct().forEach { argName ->
            assertTrue(dart.contains("name: \"$argName\""))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun readCanonicalActionSchema(): Map<String, Any?> =
        Gson().fromJson(
            readSource("schemas/oob/oob_canonical_actions.v1.json"),
            Map::class.java
        ) as Map<String, Any?>

    private fun readSource(relativePath: String): String =
        listOf(
            File(relativePath),
            File("../$relativePath")
        ).first { it.exists() }.readText()
}
