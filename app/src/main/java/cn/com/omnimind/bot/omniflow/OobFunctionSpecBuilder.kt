package cn.com.omnimind.bot.omniflow

import cn.com.omnimind.bot.omniflow.OobFunctionJson.boolArg
import cn.com.omnimind.bot.omniflow.OobFunctionJson.firstNonBlank
import cn.com.omnimind.bot.omniflow.OobFunctionJson.listArg
import cn.com.omnimind.bot.omniflow.OobFunctionJson.mapArg
import cn.com.omnimind.bot.runlog.OmniflowActionRuntime
import cn.com.omnimind.bot.runlog.OobActionCodec
import cn.com.omnimind.bot.runlog.RunLogReplayPolicy

/**
 * Builds canonical OOB reusable Function specs from the simple public register
 * shape and normalizes inserted steps for update_function.
 */
class OobFunctionSpecBuilder {
    fun functionSpecForRegistration(request: Map<String, Any?>): Map<String, Any?> {
        val explicit = explicitFunctionSpec(request)
        if (explicit.isNotEmpty()) return explicit
        val steps = simpleRegistrationSteps(request)
        if (steps.isEmpty()) return emptyMap()
        val now = System.currentTimeMillis().toString()
        val rawFunctionId = firstNonBlank(
            request["function_id"],
            request["functionId"],
            request["id"],
        )
        val name = firstNonBlank(request["name"], request["title"], rawFunctionId)
            .ifBlank { "OOB reusable function" }
        val description = firstNonBlank(
            request["description"],
            request["goal"],
            request["summary"],
            name,
        )
        val functionId = rawFunctionId.ifBlank {
            simpleFunctionIdFrom(name = name, description = description, now = now)
        }
        val sourceContext = sourceContextFromRegistration(request)
        val sourcePackageName = firstNonBlank(
            mapArg(sourceContext["src_ctx"])["package_name"],
            mapArg(sourceContext["src_ctx"])["packageName"],
        )
        val packageName = firstNonBlank(
            request["packageName"],
            request["package_name"],
            request["current_package"],
            request["currentPackage"],
            mapArg(request["source_page"])["package_name"],
            mapArg(request["source_page"])["packageName"],
            mapArg(request["sourcePage"])["package_name"],
            mapArg(request["sourcePage"])["packageName"],
            sourcePackageName,
        )
        val normalizedSteps = steps.mapIndexed { index, raw ->
            normalizeSimpleRegisteredStep(
                raw = raw,
                index = index,
                inheritedSourceContext = sourceContext.takeIf { index == 0 }.orEmpty(),
            )
        }
        val capabilities = simpleExecutionCapabilities(normalizedSteps)
        val explicitAgentVisible = request["agent_visible"] ?: request["agentVisible"]
        val explicitVisibility = firstNonBlank(request["visibility"])
        return linkedMapOf<String, Any?>(
            "schema_version" to OobFunctionSpecVocabulary.SCHEMA_VERSION_V1,
            "function_id" to functionId,
            "name" to name,
            "description" to description,
            "agent_visible" to explicitAgentVisible,
            "visibility" to explicitVisibility.takeIf { it.isNotBlank() },
            "parameters" to listArg(request["parameters"]).mapNotNull { raw ->
                mapArg(raw).takeIf { it.isNotEmpty() }
            },
            "constraints" to linkedMapOf(
                "package_name" to packageName.takeIf { it.isNotBlank() },
            ).filterValues { it != null },
            "source" to linkedMapOf(
                "kind" to "agent_registered_function",
                "goal" to firstNonBlank(request["goal"], description),
                "package_name" to packageName.takeIf { it.isNotBlank() },
                "registered_via" to "oob_function_register.simple",
                "source_context_mode" to firstNonBlank(
                    mapArg(sourceContext["_oob_meta"])["mode"],
                    "none"
                ).takeIf { sourceContext.isNotEmpty() },
                "registered_at" to now,
            ).filterValues { it != null },
            "execution" to linkedMapOf(
                "kind" to OobFunctionSpecVocabulary.EXECUTION_KIND_TOOL_SEQUENCE,
                "runner" to OobFunctionSpecVocabulary.EXECUTION_RUNNER_TOOL_SEQUENCE,
                "entrypoint" to "execute",
                "capabilities" to capabilities,
                "steps" to normalizedSteps,
                "step_count" to normalizedSteps.size,
                "omniflow_step_count" to capabilities["omniflow_step_count"],
                "agent_step_count" to capabilities["agent_step_count"],
                OobFunctionSpecVocabulary.FIELD_HAS_AGENT_STEPS to
                    capabilities[OobFunctionSpecVocabulary.FIELD_HAS_AGENT_STEPS],
            ),
            "_oob_registry" to linkedMapOf(
                "registered_at" to now,
                "updated_at" to now,
                "runner" to OobFunctionSpecVocabulary.REGISTRY_RUNNER_AGENT_REUSABLE_FUNCTION,
                "storage" to "workspace",
                "registration_input_mode" to "simple",
            ),
        ).filterValues { it != null }
    }

    fun hasExplicitFunctionSpec(request: Map<String, Any?>): Boolean =
        explicitFunctionSpec(request).isNotEmpty()

    private fun explicitFunctionSpec(request: Map<String, Any?>): Map<String, Any?> =
        mapArg(request["function_spec"])
            .ifEmpty { mapArg(request["functionSpec"]) }
            .ifEmpty {
                if ((request.containsKey("function_id") || request.containsKey("name")) &&
                    (mapArg(request["execution"]).isNotEmpty() || listArg(request["actions"]).isNotEmpty())
                ) {
                    request
                } else {
                    emptyMap()
                }
            }

    private fun simpleRegistrationSteps(request: Map<String, Any?>): List<Map<String, Any?>> =
        listArg(request["steps"])
            .ifEmpty { listArg(request["execution_steps"]) }
            .ifEmpty { listArg(request["executionSteps"]) }
            .mapNotNull { raw -> mapArg(raw).takeIf { it.isNotEmpty() } }

    fun normalizeSimpleRegisteredStep(
        raw: Map<String, Any?>,
        index: Int,
        inheritedSourceContext: Map<String, Any?>,
    ): Map<String, Any?> {
        val rawTool = firstNonBlank(
            raw["tool"],
            raw["action"],
            raw["type"],
        ).ifBlank {
            if (firstNonBlank(mapArg(raw["args"])["function_id"]).isNotBlank()) {
                OobFunctionToolNames.FUNCTION_RUN
            } else {
                OobActionCodec.ACTION_FINISHED
            }
        }
        val normalizedTool = RunLogReplayPolicy.normalizeToolName(rawTool)
        val action = OobActionCodec.canonicalActionForName(rawTool)
        val sourceContext = mapArg(raw["source_context"])
            .ifEmpty { inheritedSourceContext }
        val title = firstNonBlank(raw["title"], raw["summary"], raw["description"])
            .ifBlank { simpleStepTitle(action ?: normalizedTool, raw, index) }
        val stepArgs = normalizeSimpleStepArgs(raw, rawTool)

        val step = linkedMapOf<String, Any?>(
            "id" to firstNonBlank(raw["id"], raw["step_id"], "step_${index + 1}"),
            "index" to index,
            "title" to title,
        )
        when {
            action != null -> {
                step["kind"] = "function"
                step["executor"] = RunLogReplayPolicy.EXECUTOR_OMNIFLOW
                step["model_free"] = true
                step["scriptable"] = true
                step["tool"] = action
                step["args"] = stepArgs
                if (sourceContext.isNotEmpty()) {
                    step["source_context"] = sourceContext
                }
            }
            RunLogReplayPolicy.isOmniflowGraphTool(normalizedTool) -> {
                step["kind"] = "omniflow_graph"
                step["executor"] = RunLogReplayPolicy.EXECUTOR_OMNIFLOW
                step["model_free"] = true
                step["scriptable"] = true
                step["tool"] = normalizedTool
                step["args"] = stepArgs
                if (sourceContext.isNotEmpty()) step["source_context"] = sourceContext
            }
            RunLogReplayPolicy.isOmniflowFunctionTool(normalizedTool) ||
                RunLogReplayPolicy.isOmniflowToolCallTool(normalizedTool) ||
                firstNonBlank(stepArgs["function_id"]).isNotBlank() -> {
                val canonicalTool = if (RunLogReplayPolicy.isOmniflowToolCallTool(normalizedTool)) {
                    RunLogReplayPolicy.TOOL_CALL_TOOL
                } else {
                    OobFunctionToolNames.FUNCTION_RUN
                }
                step["kind"] = "omniflow_function"
                step["executor"] = RunLogReplayPolicy.EXECUTOR_OMNIFLOW
                step["model_free"] = true
                step["scriptable"] = true
                step["tool"] = canonicalTool
                step["args"] = canonicalSimpleCallToolArgs(stepArgs)
                if (sourceContext.isNotEmpty()) step["source_context"] = sourceContext
            }
            else -> {
                step["kind"] = "tool_call"
                step["executor"] = RunLogReplayPolicy.EXECUTOR_TOOL
                step["scriptable"] = true
                step["tool"] = normalizedTool
                step["args"] = stepArgs
                if (sourceContext.isNotEmpty()) step["source_context"] = sourceContext
            }
        }
        return step.filterValues { it != null }
    }

    private fun normalizeSimpleStepArgs(
        raw: Map<String, Any?>,
        rawTool: String,
    ): Map<String, Any?> {
        val action = OobActionCodec.canonicalActionForName(rawTool)
            ?: OobActionCodec.normalizeName(rawTool)
        val args = linkedMapOf<String, Any?>()
        args.putAll(mapArg(raw["args"]))
        args.putAlias("package_name", raw["package_name"], raw["packageName"])
        args.putAlias("x", raw["x"])
        args.putAlias("y", raw["y"])
        args.putAlias("x1", raw["x1"])
        args.putAlias("y1", raw["y1"])
        args.putAlias("x2", raw["x2"])
        args.putAlias("y2", raw["y2"])
        args.putAlias("text", raw["text"])
        args.putAlias("content", raw["content"])
        args.putAlias("value", raw["value"])
        args.putAlias("target_description", raw["target_description"], raw["targetDescription"])
        args.putAlias("selector", raw["selector"])
        args.putAlias("node_id", raw["node_id"], raw["nodeId"])
        args.putAlias("element_index", raw["element_index"], raw["elementIndex"])
        args.putAlias("scrollable_index", raw["scrollable_index"], raw["scrollableIndex"])
        args.putAlias("direction", raw["direction"])
        args.putAlias("duration_ms", raw["duration_ms"], raw["durationMs"])
        args.putAlias("clear", raw["clear"])
        args.putAlias("bounds", raw["bounds"])
        if (action == OobActionCodec.ACTION_INPUT_TEXT) {
            args.remove("content")
            args.remove("value")
        }
        if (action == OobActionCodec.ACTION_FINISHED && args.isEmpty()) {
            args["content"] = "Done"
        }
        return OobActionCodec.argsForStep(
            mapOf(
                "tool" to rawTool,
                "args" to args.filterValues { it != null },
            )
        )
    }

    private fun canonicalSimpleCallToolArgs(normalizedArgs: Map<String, Any?>): Map<String, Any?> {
        val functionId = firstNonBlank(
            normalizedArgs["function_id"],
        )
        val targetTool = firstNonBlank(
            normalizedArgs["tool_name"],
            normalizedArgs["target_tool"],
        )
        val nestedArguments = mapArg(normalizedArgs["arguments"])
        return linkedMapOf<String, Any?>().apply {
            putAll(normalizedArgs)
            if (functionId.isNotBlank()) put("function_id", functionId)
            if (targetTool.isNotBlank()) put("tool_name", targetTool)
            if (nestedArguments.isNotEmpty()) put("arguments", nestedArguments)
        }.filterValues { it != null }
    }

    private fun sourceContextFromRegistration(request: Map<String, Any?>): Map<String, Any?> {
        val explicit = mapArg(request["source_context"])
            .ifEmpty { mapArg(request["sourceContext"]) }
        if (explicit.isNotEmpty()) return explicit
        val sourcePage = mapArg(request["source_page"])
            .ifEmpty { mapArg(request["sourcePage"]) }
            .ifEmpty { mapArg(request["currentPage"]) }
            .ifEmpty { mapArg(request["current_page"]) }
        val pageXmlFromRequest = firstNonBlank(
            sourcePage["page"],
            sourcePage["xml"],
            sourcePage["observation_xml"],
            sourcePage["observationXml"],
            request["current_xml"],
            request["currentXml"],
            request["source_xml"],
            request["sourceXml"],
            request["xml"],
        )
        val requestPackageName = firstNonBlank(
            sourcePage["package_name"],
            sourcePage["packageName"],
            request["package_name"],
            request["packageName"],
            request["current_package"],
            request["currentPackage"],
        )
        val requestActivityName = firstNonBlank(
            sourcePage["activity_name"],
            sourcePage["activityName"],
            request["activity_name"],
            request["activityName"],
        )
        val autoCaptureDisabled = boolArg(request["disable_current_page_capture"]) ||
            boolArg(request["disableCurrentPageCapture"]) ||
            boolArg(request["no_current_page_capture"]) ||
            boolArg(request["noCurrentPageCapture"])
        val autoCaptureAllowed = !autoCaptureDisabled
        val capturedPage = if (pageXmlFromRequest.isBlank() && autoCaptureAllowed) {
            currentPageSourceContext()
        } else {
            emptyMap()
        }
        val capturedSrcCtx = mapArg(capturedPage["src_ctx"])
        val pageXml = firstNonBlank(pageXmlFromRequest, capturedSrcCtx["page"])
        if (pageXml.isBlank()) return emptyMap()
        val packageName = firstNonBlank(
            requestPackageName,
            capturedSrcCtx["package_name"],
            capturedSrcCtx["packageName"],
        )
        val activityName = firstNonBlank(
            requestActivityName,
            capturedSrcCtx["activity_name"],
            capturedSrcCtx["activityName"],
        )
        val mode = if (pageXmlFromRequest.isBlank()) "current_page_capture" else "explicit_request"
        return linkedMapOf(
            "src_ctx" to linkedMapOf(
                "page" to pageXml,
                "package_name" to packageName.takeIf { it.isNotBlank() },
                "activity_name" to activityName.takeIf { it.isNotBlank() },
                "require_unique_action_signature" to false,
            ).filterValues { it != null },
            "_oob_meta" to linkedMapOf(
                "mode" to mode,
                "captured_current_page" to (mode == "current_page_capture"),
            ),
        )
    }

    private fun currentPageSourceContext(): Map<String, Any?> {
        val pageXml = runCatching {
            OmniflowActionRuntime.backend.currentXml()?.trim().orEmpty()
        }.getOrDefault("")
        if (pageXml.isBlank()) return emptyMap()
        val packageName = runCatching {
            OmniflowActionRuntime.backend.currentPackageName()?.trim().orEmpty()
        }.getOrDefault("")
        val activityName = runCatching {
            OmniflowActionRuntime.backend.currentActivityName()?.trim().orEmpty()
        }.getOrDefault("")
        return linkedMapOf(
            "src_ctx" to linkedMapOf(
                "page" to pageXml,
                "package_name" to packageName.takeIf { it.isNotBlank() },
                "activity_name" to activityName.takeIf { it.isNotBlank() },
                "require_unique_action_signature" to false,
            ).filterValues { it != null }
        )
    }

    private fun simpleStepTitle(action: String, raw: Map<String, Any?>, index: Int): String {
        val args = mapArg(raw["args"])
        val target = firstNonBlank(
            args["target_description"],
            args["label"],
            args["text"],
            args["content"].takeIf { action != OobActionCodec.ACTION_INPUT_TEXT },
        )
        return when {
            target.isNotBlank() -> "$action: $target"
            else -> "$action step ${index + 1}"
        }
    }

    private fun simpleFunctionIdFrom(name: String, description: String, now: String): String {
        val seed = "$name $description"
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(48)
            .ifBlank { "registered_function" }
        return "oob_fn_${seed}_${now.takeLast(6)}"
    }

    fun simpleExecutionCapabilities(steps: List<Map<String, Any?>>): Map<String, Any?> =
        linkedMapOf(
            "omniflow_step_count" to steps.count { it["executor"] == RunLogReplayPolicy.EXECUTOR_OMNIFLOW },
            "agent_step_count" to steps.count { it["executor"] == RunLogReplayPolicy.EXECUTOR_AGENT },
            OobFunctionSpecVocabulary.FIELD_HAS_AGENT_STEPS to steps.any {
                it["executor"] == RunLogReplayPolicy.EXECUTOR_AGENT
            },
        )

    private fun MutableMap<String, Any?>.putAlias(key: String, vararg values: Any?) {
        if (containsKey(key)) return
        values.forEach { value ->
            if (value == null) return@forEach
            if (value is String && value.isBlank()) return@forEach
            put(key, value)
            return
        }
    }
}
