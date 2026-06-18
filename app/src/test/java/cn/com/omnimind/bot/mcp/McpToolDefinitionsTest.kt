package cn.com.omnimind.bot.mcp

import cn.com.omnimind.bot.agent.AgentToolNames
import cn.com.omnimind.bot.omniflow.OobFunctionToolNames
import cn.com.omnimind.bot.runlog.RunLogReplayPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class McpToolDefinitionsTest {
    @Test
    fun fixedToolsIncludeAgentRunAndOobFunctionControls() {
        val names = McpToolDefinitions.fixedTools.map { it["name"].toString() }.toSet()

        assertTrue(names.contains(AgentToolNames.VLM_TASK))
        assertTrue(names.contains("agent_run"))
        assertFalse(names.contains("call_tool"))
        assertTrue(names.contains("omniflow.recall"))
        assertTrue(names.contains("omniflow.ingest_run_log"))
        assertTrue(names.contains("omniflow.explore_replay"))
        assertTrue(names.contains(OobFunctionToolNames.FUNCTION_LIST))
        assertTrue(names.contains(OobFunctionToolNames.FUNCTION_GET))
        assertTrue(names.contains(OobFunctionToolNames.FUNCTION_REGISTER))
        assertTrue(names.contains(OobFunctionToolNames.FUNCTION_UPDATE))
        assertFalse(names.contains("oob_function_guard_check"))
        assertTrue(names.contains(OobFunctionToolNames.FUNCTION_DELETE))
        assertTrue(names.contains(OobFunctionToolNames.FUNCTION_CLEAR))
        assertTrue(names.contains(OobFunctionToolNames.RUN_LOG_LIST))
        assertTrue(names.contains(OobFunctionToolNames.RUN_LOG_GET))
        assertTrue(names.contains(OobFunctionToolNames.RUN_LOG_CONVERT))
    }

    @Test
    fun fixedToolsAreExplicitlyRoutedLocally() {
        val routeSource = listOf(
            File("app/src/main/java/cn/com/omnimind/bot/mcp/McpRoutes.kt"),
            File("src/main/java/cn/com/omnimind/bot/mcp/McpRoutes.kt"),
        ).first { it.exists() }.readText()
        val hasOmniFlowDispatcher =
            "OMNIFLOW_MCP_TOOL_NAMES" in routeSource &&
                "omniflowToolkit.executeTool" in routeSource
        val missingRoutes = McpToolDefinitions.fixedToolNames
            .filterNot { toolName ->
                "\"$toolName\" ->" in routeSource ||
                    functionToolRouteConstants[toolName]?.let { "$it ->" in routeSource } == true ||
                    (toolName in omniFlowDispatchedToolNames && hasOmniFlowDispatcher)
            }

        assertTrue(
            "Fixed MCP tools must be routed locally: $missingRoutes",
            missingRoutes.isEmpty()
        )
    }

    private val functionToolRouteConstants = mapOf(
        AgentToolNames.VLM_TASK to "AgentToolNames.VLM_TASK",
        OobFunctionToolNames.FUNCTION_LIST to "OobFunctionToolNames.FUNCTION_LIST",
        OobFunctionToolNames.FUNCTION_GET to "OobFunctionToolNames.FUNCTION_GET",
        OobFunctionToolNames.FUNCTION_REGISTER to "OobFunctionToolNames.FUNCTION_REGISTER",
        OobFunctionToolNames.FUNCTION_UPDATE to "OobFunctionToolNames.FUNCTION_UPDATE",
        OobFunctionToolNames.FUNCTION_DELETE to "OobFunctionToolNames.FUNCTION_DELETE",
        OobFunctionToolNames.FUNCTION_CLEAR to "OobFunctionToolNames.FUNCTION_CLEAR",
        OobFunctionToolNames.RUN_LOG_LIST to "OobFunctionToolNames.RUN_LOG_LIST",
        OobFunctionToolNames.RUN_LOG_GET to "OobFunctionToolNames.RUN_LOG_GET",
        OobFunctionToolNames.RUN_LOG_CONVERT to "OobFunctionToolNames.RUN_LOG_CONVERT",
    )

    private val omniFlowDispatchedToolNames = OobFunctionToolNames.profileTools + setOf(
        "omniflow.recall",
        "omniflow.ingest_run_log",
        "omniflow.explore_replay",
    )

    @Test
    fun exploreReplayToolExposesNativeExplorerControls() {
        val tool = McpToolDefinitions.fixedTools.single {
            it["name"] == "omniflow.explore_replay"
        }
        val schema = tool["inputSchema"] as Map<*, *>
        val properties = schema["properties"] as Map<*, *>

        assertTrue(properties.containsKey("goal"))
        assertTrue(properties.containsKey("package_name"))
        assertTrue(properties.containsKey("max_steps"))
        assertTrue(properties.containsKey("stop_text"))
        assertTrue(properties.containsKey("allow_risky_actions"))
        assertTrue(properties.containsKey("replay"))
        assertTrue(properties.containsKey("reset_before_replay"))
    }

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
        assertFalse(names.contains("call_tool"))
        assertFalse(names.contains("omniflow" + ".call_tool"))
        assertFalse(names.contains("oob_" + "tool_call"))
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
        assertFalse(names.contains("oob_function_run"))
        assertFalse(names.contains("omniflow.run_function"))
        assertFalse(names.contains("omniflow.call_function"))
        assertFalse(names.contains("omniflow.execute_function"))
        assertFalse(routeSource.contains("\"run_function\""))
        assertFalse(routeSource.contains("\"omniflow.call_function\""))

        assertTrue(httpHostSource.contains("post(\"/omniflow/function/run\")"))
        assertTrue(httpHostSource.contains("OobOmniFlowToolkitService(context).executeTool(\"run_function\", args)"))
    }

    @Test
    fun updateFunctionToolExposesRunLogAnalysisInputs() {
        val tool = McpToolDefinitions.fixedTools.single {
            it["name"] == OobFunctionToolNames.FUNCTION_UPDATE
        }
        val schema = tool["inputSchema"] as Map<*, *>
        val properties = schema["properties"] as Map<*, *>
        val description = tool["description"]?.toString().orEmpty()

        assertTrue(description.contains("run_id"))
        assertTrue(description.contains("analysis_context"))
        assertTrue(properties.containsKey("function_id"))
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
        assertTrue(patchProperties.containsKey("ops"))
        assertTrue(patchProperties.containsKey("steps"))
        assertTrue(patchProperties.containsKey("checker_rules"))
        val ops = patchProperties["ops"] as Map<*, *>
        val opItems = ops["items"] as Map<*, *>
        val opProperties = opItems["properties"] as Map<*, *>
        val op = opProperties["op"] as Map<*, *>
        val enumValues = op["enum"] as List<*>
        assertTrue(enumValues.contains("replace_target"))
        assertTrue(enumValues.contains("insert_step"))
        assertTrue(enumValues.contains("delete_step"))
        assertTrue(opProperties.containsKey("desired_text"))
        assertTrue(opProperties.containsKey("action"))
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
        assertTrue(schemas.containsKey(RunLogReplayPolicy.schemaVersion))

        val exportedUpdateSchema = schemas["update_function.input.mcp"] as Map<*, *>
        val liveUpdateSchema = McpToolDefinitions.fixedTools.single {
            it["name"] == OobFunctionToolNames.FUNCTION_UPDATE
        }["inputSchema"]
        assertEquals(liveUpdateSchema, exportedUpdateSchema)

        val agentProfileSchema = schemas["update_function.input.agent_profile"] as Map<*, *>
        val agentProperties = agentProfileSchema["properties"] as Map<*, *>
        assertTrue(agentProperties.containsKey("dryRun"))
        assertTrue(agentProperties.containsKey("allowExecutionChange"))

        val toolSchemas = bundle["tool_schemas"] as List<*>
        assertTrue(
            toolSchemas.map { (it as Map<*, *>)["name"] }
                .contains(OobFunctionToolNames.FUNCTION_UPDATE)
        )
    }

    @Test
    fun recallToolExposesPageMatchInputs() {
        val tool = McpToolDefinitions.fixedTools.single {
            it["name"] == "omniflow.recall"
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
        assertFalse(properties.containsKey("allowOmniFlowFunctionAutoExecute"))
        assertFalse(properties.containsKey("disableOmniFlowRecall"))
        assertFalse(properties.containsKey("parseOnly"))
    }

    @Test
    fun agentRunToolExposesFocusedToolControls() {
        val tool = McpToolDefinitions.fixedTools.single {
            it["name"] == "agent_run"
        }
        val schema = tool["inputSchema"] as Map<*, *>
        val properties = schema["properties"] as Map<*, *>

        assertTrue(properties.containsKey("toolProfile"))
        assertTrue(properties.containsKey("allowedTools"))
        val toolProfile = properties["toolProfile"] as Map<*, *>
        assertEquals(listOf("function_management"), toolProfile["enum"])
        val profileDescription = toolProfile["description"].toString()
        assertTrue(profileDescription.contains("OmniFlow Functions"))
        assertFalse(profileDescription.contains("OOB Functions"))
    }

    @Test
    fun functionManagementDescriptionsUseRuntimeOwnedOmniFlowLanguage() {
        val descriptions = McpToolDefinitions.fixedTools
            .filter { (it["name"] as? String) in OobFunctionToolNames.profileTools }
            .joinToString("\n") { it["description"].toString() }

        assertTrue(descriptions.contains("OmniFlow Function"))
        assertFalse(descriptions.contains("OOB Function"))
        assertFalse(descriptions.contains("OOB reusable"))
        assertFalse(descriptions.contains("direct deterministic replay"))
    }

    @Test
    fun oobFunctionRegisterToolExposesSimpleConversationSchema() {
        val tool = McpToolDefinitions.fixedTools.single {
            it["name"] == OobFunctionToolNames.FUNCTION_REGISTER
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
