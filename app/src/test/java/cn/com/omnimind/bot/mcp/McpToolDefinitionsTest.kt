package cn.com.omnimind.bot.mcp

import cn.com.omnimind.bot.agent.AgentToolNames
import cn.com.omnimind.bot.function.FunctionApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class McpToolDefinitionsTest {
    @Test
    fun fixedToolsIncludeAgentRunAndFunctionControls() {
        val names = McpToolDefinitions.fixedTools.map { it["name"].toString() }.toSet()

        assertTrue(names.contains(AgentToolNames.VLM_TASK))
        assertTrue(names.contains("agent_run"))
        assertFalse(names.contains("call_tool"))
        assertTrue(names.contains(FunctionApi.FUNCTION_RECALL))
        assertTrue(names.contains(FunctionApi.FUNCTION_INGEST_RUN_LOG))
        assertTrue(names.contains(FunctionApi.FUNCTION_LIST))
        assertTrue(names.contains(FunctionApi.FUNCTION_GET))
        assertTrue(names.contains(FunctionApi.FUNCTION_REGISTER))
        assertTrue(names.contains(FunctionApi.FUNCTION_UPDATE))
        assertFalse(names.contains("oob_function_guard_check"))
        assertTrue(names.contains(FunctionApi.FUNCTION_DELETE))
        assertTrue(names.contains(FunctionApi.FUNCTION_CLEAR))
        assertTrue(names.contains(FunctionApi.RUN_LOG_LIST))
        assertTrue(names.contains(FunctionApi.RUN_LOG_GET))
        assertTrue(names.contains(FunctionApi.RUN_LOG_CONVERT))
    }

    @Test
    fun fixedToolsAreExplicitlyRoutedLocally() {
        val routeSource = listOf(
            File("app/src/main/java/cn/com/omnimind/bot/mcp/McpRoutes.kt"),
            File("src/main/java/cn/com/omnimind/bot/mcp/McpRoutes.kt"),
        ).first { it.exists() }.readText()
        val hasFunctionDispatcher =
            "FUNCTION_MCP_TOOL_NAMES" in routeSource &&
                "functionManagementService.executeTool" in routeSource
        val missingRoutes = McpToolDefinitions.fixedToolNames
            .filterNot { toolName ->
                "\"$toolName\" ->" in routeSource ||
                    functionToolRouteConstants[toolName]?.let { "$it ->" in routeSource } == true ||
                    (toolName in functionDispatchedToolNames && hasFunctionDispatcher)
            }

        assertTrue(
            "Fixed MCP tools must be routed locally: $missingRoutes",
            missingRoutes.isEmpty()
        )
    }

    private val functionToolRouteConstants = mapOf(
        AgentToolNames.VLM_TASK to "AgentToolNames.VLM_TASK",
        FunctionApi.FUNCTION_LIST to "FunctionApi.FUNCTION_LIST",
        FunctionApi.FUNCTION_GET to "FunctionApi.FUNCTION_GET",
        FunctionApi.FUNCTION_REGISTER to "FunctionApi.FUNCTION_REGISTER",
        FunctionApi.FUNCTION_UPDATE to "FunctionApi.FUNCTION_UPDATE",
        FunctionApi.FUNCTION_DELETE to "FunctionApi.FUNCTION_DELETE",
        FunctionApi.FUNCTION_CLEAR to "FunctionApi.FUNCTION_CLEAR",
        FunctionApi.RUN_LOG_LIST to "FunctionApi.RUN_LOG_LIST",
        FunctionApi.RUN_LOG_GET to "FunctionApi.RUN_LOG_GET",
        FunctionApi.RUN_LOG_CONVERT to "FunctionApi.RUN_LOG_CONVERT",
    )

    private val functionDispatchedToolNames = FunctionApi.mcpToolNames

    @Test
    fun getStateToolExposesXmlScreenshotControls() {
        val tool = McpToolDefinitions.fixedTools.single {
            it["name"] == "get_state"
        }
        val schema = tool["inputSchema"] as Map<*, *>
        val properties = schema["properties"] as Map<*, *>
        val description = tool["description"]?.toString().orEmpty()

        assertTrue(description.contains("Accessibility XML"))
        assertTrue(properties.containsKey("include_xml"))
        assertTrue(properties.containsKey("include_screenshot"))
        assertTrue(properties.containsKey("include_indexed_context"))
        assertTrue(properties.containsKey("include_marked_screenshot"))
        assertTrue(properties.containsKey("include_image_content"))
        assertTrue(properties.containsKey("filter_overlay"))
        assertTrue(properties.containsKey("image_quality"))
        assertTrue(properties.containsKey("max_xml_chars"))
    }

    @Test
    fun callToolIsInternalReplayOnlyNotPublicMcpTool() {
        val names = McpToolDefinitions.fixedTools.map { it["name"].toString() }.toSet()
        val routeSource = listOf(
            File("app/src/main/java/cn/com/omnimind/bot/mcp/McpRoutes.kt"),
            File("src/main/java/cn/com/omnimind/bot/mcp/McpRoutes.kt"),
        ).first { it.exists() }.readText()

        assertFalse(names.contains("call_tool"))
        assertFalse(names.contains("omniflow" + ".call_tool"))
        assertFalse(names.contains("oob_" + "tool_call"))
        assertFalse(routeSource.contains("TOOL_CALL_TOOL ->"))
        assertFalse(routeSource.contains("executeOobToolCall(context, args)"))
    }

    @Test
    fun functionExecutionIsNotModelVisibleMcpTool() {
        val names = McpToolDefinitions.fixedTools.map { it["name"].toString() }.toSet()
        val routeSource = listOf(
            File("app/src/main/java/cn/com/omnimind/bot/mcp/McpRoutes.kt"),
            File("src/main/java/cn/com/omnimind/bot/mcp/McpRoutes.kt"),
        ).first { it.exists() }.readText()
        val httpHostSource = listOf(
            File("app/src/main/java/cn/com/omnimind/bot/devicehost/LocalDeviceHttpHostManager.kt"),
            File("src/main/java/cn/com/omnimind/bot/devicehost/LocalDeviceHttpHostManager.kt"),
        ).first { it.exists() }.readText()

        assertFalse(names.contains("run_function"))
        assertFalse(names.contains("oob_" + "function_run"))
        assertFalse(names.contains("omniflow.run_function"))
        assertFalse(names.contains("omniflow.call_function"))
        assertFalse(names.contains("omniflow.execute_function"))
        assertTrue(routeSource.contains("\"run_function\" -> FunctionRun(context).runFunction(args)"))
        assertFalse(routeSource.contains("\"omniflow.call_function\""))

        assertTrue(httpHostSource.contains("post(\"/omniflow/function/run\")"))
        assertTrue(httpHostSource.contains("FunctionRun(context).runFunction(args)"))
    }

    @Test
    fun updateFunctionToolExposesFunctionSpecSaveInputs() {
        val tool = McpToolDefinitions.fixedTools.single {
            it["name"] == FunctionApi.FUNCTION_UPDATE
        }
        val schema = tool["inputSchema"] as Map<*, *>
        val properties = schema["properties"] as Map<*, *>
        val description = tool["description"]?.toString().orEmpty()

        assertTrue(description.contains("function_spec"))
        assertTrue(description.contains("Function metadata"))
        assertTrue(properties.containsKey("function_id"))
        assertTrue(properties.containsKey("function_spec"))
        assertTrue(properties.containsKey("run_id"))
        assertTrue(properties.containsKey("offline_job"))
        assertTrue(properties.containsKey("auto_analyze_with_model"))
        assertTrue(properties.containsKey("analysis"))
        assertTrue(properties.containsKey("patch"))
        assertTrue(properties.containsKey("dry_run"))
        assertFalse(properties.containsKey("functionId"))
        assertFalse(properties.containsKey("runId"))

        val analysis = properties["analysis"] as Map<*, *>
        val analysisProperties = analysis["properties"] as Map<*, *>
        assertTrue(analysisProperties.containsKey("summary"))
        assertTrue(analysisProperties.containsKey("recommended_patch"))

        val patch = properties["patch"] as Map<*, *>
        val patchProperties = patch["properties"] as Map<*, *>
        assertTrue(patchProperties.containsKey("steps"))
        assertTrue(patchProperties.containsKey("parameters"))
        assertTrue(patchProperties.containsKey("agent_reuse"))
        assertTrue(patchProperties.containsKey("checker_rules"))
        assertFalse(patchProperties.containsKey("ops"))
        assertFalse(patchProperties.containsKey("replace_" + "target"))
    }

    @Test
    fun functionManagementSchemasAreExportedFromSharedBundle() {
        val resource = McpToolDefinitions.schemaExportResource
        assertEquals("omniflow://schemas/function-management", resource["uri"])
        assertEquals("application/json", resource["mimeType"])

        val bundle = McpToolDefinitions.schemaExportBundle
        assertEquals("oob.function_schema_export.v1", bundle["schema_version"])
        val schemas = bundle["schemas"] as Map<*, *>
        assertTrue(schemas.containsKey("oob.reusable_function.v1"))
        assertTrue(schemas.containsKey("oob.function_enhancement.v1"))
        assertTrue(schemas.containsKey("update_function.input.mcp"))
        assertTrue(schemas.containsKey("update_function.input.agent_profile"))
        assertTrue(schemas.containsKey("update_function.analysis"))
        assertTrue(schemas.containsKey("update_function.patch"))

        val exportedUpdateSchema = schemas["update_function.input.mcp"] as Map<*, *>
        val liveUpdateSchema = McpToolDefinitions.fixedTools.single {
            it["name"] == FunctionApi.FUNCTION_UPDATE
        }["inputSchema"]
        assertEquals(liveUpdateSchema, exportedUpdateSchema)

        val agentProfileSchema = schemas["update_function.input.agent_profile"] as Map<*, *>
        val agentProperties = agentProfileSchema["properties"] as Map<*, *>
        assertTrue(agentProperties.containsKey("dryRun"))
        assertFalse(agentProperties.containsKey("allow" + "ExecutionChange"))

        val toolSchemas = bundle["tool_schemas"] as List<*>
        assertTrue(
            toolSchemas.map { (it as Map<*, *>)["name"] }
                .contains(FunctionApi.FUNCTION_UPDATE)
        )
    }

    @Test
    fun recallToolExposesPageMatchInputs() {
        val tool = McpToolDefinitions.fixedTools.single {
            it["name"] == FunctionApi.FUNCTION_RECALL
        }
        val schema = tool["inputSchema"] as Map<*, *>
        val properties = schema["properties"] as Map<*, *>
        val description = tool["description"]?.toString().orEmpty()

        assertTrue(description.contains("saved Function execution is selected by the local runtime"))
        assertTrue(properties.containsKey("goal"))
        assertTrue(properties.containsKey("current_package"))
        assertTrue(properties.containsKey("current_node_id"))
        assertTrue(properties.containsKey("current_xml"))
        assertTrue(properties.containsKey("k"))
        assertTrue(properties.containsKey("include_debug"))
        val includeDebug = properties["include_debug"] as Map<*, *>
        assertEquals(false, includeDebug["default"])
    }

    @Test
    fun vlmTaskToolExposesDirectGuiAgentControls() {
        val tool = McpToolDefinitions.fixedTools.single {
            it["name"] == AgentToolNames.VLM_TASK
        }
        val schema = tool["inputSchema"] as Map<*, *>
        val properties = schema["properties"] as Map<*, *>
        assertTrue(properties.containsKey("goal"))
        assertTrue(properties.containsKey("model"))
        assertTrue(properties.containsKey("packageName"))
        assertTrue(properties.containsKey("maxSteps"))
        assertTrue(properties.containsKey("startFromCurrent"))
        assertTrue(properties.containsKey("needSummary"))
        assertTrue(properties.containsKey("disableFunctionRecall"))
        assertFalse(properties.containsKey("parseOnly"))
        val disableRecall = properties["disableFunctionRecall"] as Map<*, *>
        assertEquals(false, disableRecall["default"])
    }

    @Test
    fun agentRunToolExposesFocusedToolControls() {
        val tool = McpToolDefinitions.fixedTools.single {
            it["name"] == "agent_run"
        }
        val schema = tool["inputSchema"] as Map<*, *>
        val properties = schema["properties"] as Map<*, *>
        val agentRunServiceSource = listOf(
            File("app/src/main/java/cn/com/omnimind/bot/webchat/AgentRunService.kt"),
            File("src/main/java/cn/com/omnimind/bot/webchat/AgentRunService.kt"),
        ).first { it.exists() }.readText()

        assertTrue(properties.containsKey("toolProfile"))
        assertTrue(properties.containsKey("allowedTools"))
        val toolProfile = properties["toolProfile"] as Map<*, *>
        assertEquals(listOf(FunctionApi.PROFILE), toolProfile["enum"])
        val profileDescription = toolProfile["description"].toString()
        assertTrue(profileDescription.contains("Functions"))
        assertTrue(profileDescription.contains("run"))
        assertFalse(profileDescription.contains("OOB Functions"))
        assertTrue(agentRunServiceSource.contains("arguments[\"toolProfile\"]"))
        assertTrue(agentRunServiceSource.contains("arguments[\"allowedTools\"]"))
    }

    @Test
    fun functionManagementDescriptionsUseRuntimeOwnedFunctionLanguage() {
        val descriptions = McpToolDefinitions.fixedTools
            .filter { (it["name"] as? String) in FunctionApi.toolNames }
            .joinToString("\n") { it["description"].toString() }

        assertTrue(descriptions.contains("Function"))
        assertFalse(descriptions.contains("OOB Function"))
        assertFalse(descriptions.contains("OOB reusable"))
        assertFalse(descriptions.contains("direct deterministic replay"))
    }

    @Test
    fun oobFunctionRegisterToolExposesSimpleConversationSchema() {
        val tool = McpToolDefinitions.fixedTools.single {
            it["name"] == FunctionApi.FUNCTION_REGISTER
        }
        val schema = tool["inputSchema"] as Map<*, *>
        val properties = schema["properties"] as Map<*, *>

        assertTrue(properties.containsKey("function_id"))
        assertTrue(properties.containsKey("name"))
        assertTrue(properties.containsKey("description"))
        assertTrue(properties.containsKey("steps"))
        assertTrue(properties.containsKey("source_page"))
        assertTrue(properties.containsKey("function_spec"))
        assertFalse(properties.containsKey("functionId"))
        assertFalse(properties.containsKey("sourcePage"))
        assertFalse(properties.containsKey("functionSpec"))
        assertTrue(schema["required"] == null)
    }
}
