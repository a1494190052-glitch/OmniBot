package cn.com.omnimind.bot.omniflow

import cn.com.omnimind.baselib.runlog.OobCanonicalActionSchema
import cn.com.omnimind.bot.runlog.RunLogReplayPolicy

/**
 * Exportable schema bundle for Function management/debugging.
 *
 * Keep this as data-only JSON-compatible maps so MCP resources, tests, and
 * docs can all inspect the same contract without adding another model tool.
 */
object OobFunctionSchemaExport {
    const val RESOURCE_URI = "omniflow://schemas/function-management"
    const val SCHEMA_VERSION = "oob.function_schema_export.v1"

    fun bundle(mcpTools: List<Map<String, Any?>> = emptyList()): Map<String, Any?> =
        linkedMapOf(
            "schema_version" to SCHEMA_VERSION,
            "kind" to "oob_function_schema_export",
            "resource_uri" to RESOURCE_URI,
            "canonical_actions_schema_version" to OobCanonicalActionSchema.SCHEMA_VERSION,
            "runlog_replay_policy_schema_version" to RunLogReplayPolicy.schemaVersion,
            "schemas" to linkedMapOf(
                "oob.reusable_function.v1" to reusableFunctionSchema,
                "oob.function_enhancement.v1" to enhancementReportSchema,
                "update_function.input.mcp" to OobFunctionUpdateToolSchema.inputSchema(
                    includeCamelCaseAliases = false
                ),
                "update_function.input.agent_profile" to OobFunctionUpdateToolSchema.inputSchema(
                    includeCamelCaseAliases = true
                ),
                "update_function.analysis" to OobFunctionUpdateToolSchema.analysisSchema,
                "update_function.patch" to OobFunctionUpdateToolSchema.patchSchema,
                RunLogReplayPolicy.schemaVersion to replayPolicySchema,
            ),
            "tool_schemas" to mcpTools.mapNotNull(::toolSchemaSummary),
        )

    private fun toolSchemaSummary(tool: Map<String, Any?>): Map<String, Any?>? {
        val name = tool["name"]?.toString()?.trim().orEmpty()
        if (name.isEmpty()) return null
        return linkedMapOf(
            "name" to name,
            "inputSchema" to tool["inputSchema"],
        )
    }

    private val reusableFunctionSchema: Map<String, Any?>
        get() = obj(
            description = "Saved OOB reusable Function. Execution fields are preserved by enhancement; update_function owns safe patches.",
            properties = linkedMapOf(
                "schema_version" to constString("oob.reusable_function.v1"),
                "function_id" to string("Stable Function id."),
                "name" to string("Short user-visible Function name."),
                "description" to string("Reusable description with app/page conditions, runtime inputs, and success signal."),
                "package_name" to string("Optional Android package scope."),
                "parameters" to array(
                    "Runtime parameter descriptors.",
                    obj(
                        properties = linkedMapOf(
                            "name" to string("Parameter name."),
                            "type" to string("Parameter type."),
                            "description" to string("User-visible parameter description."),
                            "required" to boolean("Whether the caller must provide this value."),
                            "default" to obj(description = "Optional default value."),
                            "bindings" to array("JSONPath bindings under execution.steps[*].args.", string()),
                        )
                    ),
                ),
                "execution" to obj(
                    properties = linkedMapOf(
                        "steps" to array(
                            "Ordered executable or agent-routed steps.",
                            obj(
                                properties = linkedMapOf(
                                    "id" to string("Step id."),
                                    "tool" to string("Canonical action/tool name."),
                                    "executor" to string("omniflow, agent, or tool."),
                                    "model_free" to boolean("Whether local replay can execute without model planning."),
                                    "title" to string("Short step title."),
                                    "description" to string("Visible action intent."),
                                    "args" to obj(description = "Concrete replay arguments."),
                                    "cleanup_annotation" to OobFunctionUpdateToolSchema.cleanupAnnotationExportSchema,
                                    "source_context" to obj(description = "Recorder evidence. Not sent to label enhancement prompts."),
                                )
                            ),
                        ),
                    )
                ),
                "agent_reuse" to OobFunctionUpdateToolSchema.agentReuseExportSchema,
                "metadata" to obj(description = "Runtime metadata, enhancement report, checker rules, and diagnostics."),
            ),
        )

    private val enhancementReportSchema: Map<String, Any?>
        get() = obj(
            description = "Report persisted under metadata.oob_enhancement after label enhancement.",
            properties = linkedMapOf(
                "schema_version" to constString("oob.function_enhancement.v1"),
                "source" to constString("run_log_agent_label_enhancer"),
                "status" to enumString("Enhancement result.", listOf("enhanced", "unchanged", "partial", "failed")),
                "changed" to boolean("Whether validated Function JSON changed."),
                "message" to string("User-visible status message."),
                "updated_at" to string("UTC ISO timestamp."),
                "sections" to array(
                    "Per-request parse and validation diagnostics.",
                    obj(
                        properties = linkedMapOf(
                            "part" to string("Request part, for example header, step_0, parameters, or agent_reuse."),
                            "section" to string("Logical section."),
                            "status" to string("parsed, repaired, empty_patch, no_response, invalid_json, error, changed, or unchanged."),
                            "accepted" to boolean("Whether this section may contribute to the merge."),
                            "chunk_index" to integer("Zero-based chunk index for step prompts."),
                            "chunk_count" to integer("Total chunk count for step prompts."),
                            "step_indexes" to array("Step indexes included in this request.", integer("Step index.")),
                            "prompt_chars" to integer("Prompt character count for this request."),
                            "prompt_approx_tokens" to integer("Prompt character count divided by four, rounded up."),
                            "raw_chars" to integer("Raw model output character count."),
                        )
                    ),
                ),
            ),
        )

    private val replayPolicySchema: Map<String, Any?>
        get() = obj(
            description = "Replay policy summary for RunLog conversion and local replay.",
            properties = linkedMapOf(
                "schema_version" to constString(RunLogReplayPolicy.schemaVersion),
                "omniflow_actions" to enumArray(RunLogReplayPolicy.omniflowActions),
                "coordinate_actions" to enumArray(RunLogReplayPolicy.coordinateActions),
                "data_flow_tools" to enumArray(RunLogReplayPolicy.dataFlowTools),
                "perception_tools" to enumArray(RunLogReplayPolicy.perceptionTools),
                "skip_tools" to enumArray(RunLogReplayPolicy.skipTools),
            ),
        )

    private fun obj(
        description: String? = null,
        properties: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> = linkedMapOf<String, Any?>("type" to "object").apply {
        if (description != null) put("description", description)
        if (properties.isNotEmpty()) put("properties", properties)
    }

    private fun array(description: String, items: Map<String, Any?>): Map<String, Any?> =
        linkedMapOf("type" to "array", "description" to description, "items" to items)

    private fun string(description: String = ""): Map<String, Any?> =
        primitive("string", description)

    private fun integer(description: String): Map<String, Any?> =
        primitive("integer", description)

    private fun boolean(description: String): Map<String, Any?> =
        primitive("boolean", description)

    private fun constString(value: String): Map<String, Any?> =
        linkedMapOf("type" to "string", "const" to value)

    private fun enumString(description: String, values: List<String>): Map<String, Any?> =
        linkedMapOf("type" to "string", "description" to description, "enum" to values)

    private fun enumArray(values: Set<String>): Map<String, Any?> =
        array("Allowed values.", enumString("Allowed value.", values.toList()))

    private fun primitive(type: String, description: String): Map<String, Any?> =
        linkedMapOf<String, Any?>("type" to type).apply {
            if (description.isNotBlank()) put("description", description)
        }
}
