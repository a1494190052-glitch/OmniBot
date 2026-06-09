package cn.com.omnimind.bot.omniflow

/**
 * Agent-facing JSON schemas for update_function.
 *
 * These schemas are descriptive rather than fully closed: update_function accepts
 * both legacy and agent-generated aliases, so the schema should guide generation
 * without rejecting safe extra metadata too early.
 */
object OobFunctionUpdateToolSchema {
    fun inputSchema(includeCamelCaseAliases: Boolean): Map<String, Any?> =
        obj(
            properties = inputProperties(includeCamelCaseAliases = includeCamelCaseAliases),
        ).toMutableMap().apply {
            put("required", listOf("function_id"))
        }

    val analysisSchema: Map<String, Any?>
        get() = obj(
            description = "Agent-authored RunLog evidence analysis to persist in Function metadata.",
            properties = linkedMapOf(
                "summary" to string("Short conclusion from the RunLog evidence."),
                "step_findings" to array(
                    description = "Per-step evidence mapping between Function steps and RunLog cards.",
                    items = obj(
                        properties = linkedMapOf(
                            "function_step_index" to integer("Function step index."),
                            "runlog_card_index" to integer("RunLog card index."),
                            "label" to string("Short finding label."),
                            "role" to string("Evidence role, for example success_evidence, failure_evidence, or noise."),
                            "reason" to string("Why this evidence matters."),
                        ),
                    ),
                ),
                "failure_reason" to obj(
                    properties = linkedMapOf(
                        "code" to string("Failure code if known."),
                        "message" to string("Failure explanation if known."),
                    ),
                ),
                "recommended_patch" to obj(
                    description = "Optional patch with the same shape as update_function.patch.",
                ),
            ),
        )

    val patchSchema: Map<String, Any?>
        get() = obj(
            description = "Safe in-place patch for one existing Function. update_function never creates, splits, merges, or batch-registers Functions.",
            properties = linkedMapOf(
                "name" to string("Updated user-visible Function name."),
                "description" to string("Updated reusable Function description."),
                "steps" to array(
                    description = "Non-structural per-step label/annotation patches.",
                    items = stepLabelPatchSchema,
                ),
                "parameters" to array(
                    description = "Optional parameter descriptors. Coordinate, XML, screenshot, and source_context bindings are ignored as unsafe.",
                    items = obj(
                        properties = linkedMapOf(
                            "name" to string("Parameter name."),
                            "type" to string("Parameter type."),
                            "description" to string("User-visible parameter description."),
                            "required" to boolean("Whether the parameter is required."),
                            "default" to obj(description = "Optional default value."),
                            "bindings" to array("Binding paths into Function args.", string()),
                        ),
                    ),
                ),
                "agent_reuse" to agentReuseSchema,
                "agentReuse" to agentReuseSchema,
                "metadata" to obj(
                    description = "Optional metadata patch. checker_rules/checkerRules are sanitized into supported runtime checker rules.",
                    properties = linkedMapOf(
                        "checker_rules" to checkerRulesSchema,
                        "checkerRules" to checkerRulesSchema,
                    ),
                ),
                "checker_rules" to checkerRulesSchema,
                "checkerRules" to checkerRulesSchema,
                "ops" to operationArraySchema,
                "operations" to operationArraySchema,
                "repairs" to operationArraySchema,
                "replace_target" to replaceTargetOperationSchema,
                "replaceTarget" to replaceTargetOperationSchema,
            ),
        )

    fun inputProperties(includeCamelCaseAliases: Boolean): Map<String, Any?> {
        val properties = linkedMapOf<String, Any?>(
            "function_id" to string("Existing Function id to update in place."),
            "run_id" to string("Optional local RunLog id used as evidence for agent analysis."),
            "instruction" to string("Optional user correction or enhancement instruction."),
            "mode" to enumString("Update mode.", listOf("enhance", "repair", "annotate", "fix", "correction")),
            "analysis" to analysisSchema,
            "patch" to patchSchema,
            "usage" to obj(description = "Optional token usage from the API call that produced this enhancement analysis."),
            "cost" to obj(description = "Optional cost estimate from the API call that produced this enhancement analysis."),
            "dry_run" to boolean("Preview changes without saving."),
            "allow_execution_change" to boolean("Allow repair operations that alter executable step targets."),
            "allow_structural_change" to boolean("Allow insert/delete step operations."),
        )
        if (includeCamelCaseAliases) {
            properties["dryRun"] = boolean("Alias of dry_run.")
            properties["allowExecutionChange"] = boolean("Alias of allow_execution_change.")
            properties["allowStructuralChange"] = boolean("Alias of allow_structural_change.")
        }
        return properties
    }

    val cleanupAnnotationExportSchema: Map<String, Any?>
        get() = cleanupAnnotationSchema

    val agentReuseExportSchema: Map<String, Any?>
        get() = agentReuseSchema

    private val operationProperties: Map<String, Any?>
        get() = linkedMapOf(
            "op" to enumString(
                "Operation type.",
                listOf("replace_target", "replace_click_target", "retarget_action", "insert_step", "add_step", "delete_step", "remove_step"),
            ),
            "tool" to string("Target action/tool name, for example click, input_text, swipe, open_app."),
            "action" to string("Alias of tool."),
            "tool_name" to string("Alias of tool."),
            "step_index" to integer("Target step index, or insert index for insert_step."),
            "stepIndex" to integer("Alias of step_index."),
            "step_id" to string("Target step id."),
            "stepId" to string("Alias of step_id."),
            "wrong_text" to string("Current/wrong target text to avoid."),
            "wrongText" to string("Alias of wrong_text."),
            "desired_text" to string("Desired replacement target text."),
            "desiredText" to string("Alias of desired_text."),
            "target_text" to string("Alias of desired_text."),
            "title" to string("Title for inserted or updated step."),
            "description" to string("Description for inserted or updated step."),
            "target_description" to string("Target description for inserted step."),
            "text" to string("Input text for input_text inserted step."),
            "x" to number("Optional recorded x coordinate for inserted step."),
            "y" to number("Optional recorded y coordinate for inserted step."),
            "bounds" to obj(description = "Optional bounds for inserted or retargeted step."),
            "args" to obj(description = "Optional full args for inserted step."),
            "arguments" to obj(description = "Alias of args."),
            "step" to obj(description = "Optional full step object for insert_step."),
            "new_step" to obj(description = "Alias of step."),
            "reason" to string("Why this operation is needed."),
        )

    private val operationArraySchema: Map<String, Any?>
        get() = array("Executable repair operations.", obj(properties = operationProperties))

    private val replaceTargetOperationSchema: Map<String, Any?>
        get() = obj(description = "Replace one step target with a safer target.", properties = operationProperties)

    private val stepLabelPatchSchema: Map<String, Any?>
        get() = obj(
            properties = linkedMapOf(
                "index" to integer("Step index."),
                "step_index" to integer("Alias of index."),
                "id" to string("Step id."),
                "step_id" to string("Alias of id."),
                "title" to string("Updated step title."),
                "summary" to string("Updated step summary."),
                "description" to string("Updated step description."),
                "cleanup_annotation" to cleanupAnnotationSchema,
                "cleanupAnnotation" to cleanupAnnotationSchema,
            ),
        )

    private val cleanupAnnotationSchema: Map<String, Any?>
        get() = obj(
            description = "Optional annotation for cleanup/noise/checker candidate steps. Does not directly insert/delete executable steps.",
            properties = linkedMapOf(
                "schema_version" to string("Annotation schema version."),
                "cleanup_action" to string("For optional runtime checker use optional_checker."),
                "optional_condition" to string("When this checker candidate should run."),
                "reason" to string("Why the annotation is safe/useful."),
                "action_purpose" to string("What the original action was trying to do."),
                "role" to string("Optional role such as checker_candidate."),
            ),
        )

    private val agentReuseSchema: Map<String, Any?>
        get() = obj(
            properties = linkedMapOf(
                "reuse_when" to array("When the Function should be reused.", string()),
                "avoid_when" to array("When the Function should not be reused.", string()),
                "success_signal" to string("How to know the Function succeeded."),
                "key_actions" to array("Important user-visible actions.", obj()),
                "checker_assets" to array("Links from optional checker annotations to runtime checker rules.", obj()),
            ),
        )

    private val checkerRulesSchema: Map<String, Any?>
        get() = array(
            description = "Supported runtime checker rules only. Unsupported condition/action pairs are ignored.",
            items = obj(
                properties = linkedMapOf(
                    "id" to string("Stable checker rule id."),
                    "condition" to string("Supported condition such as ad_blocking, permission_dialog, keyboard_obscuring, package_mismatch, app_upgrade_prompt, or resolver_dialog."),
                    "action" to string("Supported action such as dismiss, allow, hide_keyboard, open_app, or choose."),
                    "enabled" to boolean("Whether the checker is enabled."),
                    "params" to obj(
                        properties = linkedMapOf(
                            "package_name" to string("Expected package for package_mismatch/open_app checker."),
                        ),
                    ),
                ),
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

    private fun number(description: String): Map<String, Any?> =
        primitive("number", description)

    private fun boolean(description: String): Map<String, Any?> =
        primitive("boolean", description)

    private fun enumString(description: String, values: List<String>): Map<String, Any?> =
        linkedMapOf<String, Any?>("type" to "string", "description" to description, "enum" to values)

    private fun primitive(type: String, description: String): Map<String, Any?> =
        linkedMapOf<String, Any?>("type" to type).apply {
            if (description.isNotBlank()) put("description", description)
        }
}
