package cn.com.omnimind.bot.omniflow
import cn.com.omnimind.bot.runlog.argsForStep
import cn.com.omnimind.bot.runlog.resolveActionName
import cn.com.omnimind.baselib.runlog.OobActionSchema

import cn.com.omnimind.bot.omniflow.OobFunctionJson.firstNonBlank
import cn.com.omnimind.bot.omniflow.OobFunctionJson.mapArg
import cn.com.omnimind.bot.runlog.RunLogReplayPolicy

/**
 * Normalizes raw step Maps into the canonical OmniFlow step format.
 * Used by OobFunctionUpdateService (insert_step) and OobOmniFlowToolkitService (simple registration).
 */
internal object OobFunctionStepNormalizer {

    fun normalizeSimpleRegisteredStep(
        raw: Map<String, Any?>,
        index: Int,
        inheritedSourceContext: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> {
        val rawTool = firstNonBlank(
            raw["tool"], raw["action"], raw["type"],
        ).ifBlank {
            if (firstNonBlank(mapArg(raw["args"])["function_id"]).isNotBlank()) {
                RunLogReplayPolicy.TOOL_CALL_TOOL
            } else {
                OobActionSchema.TOOL_FINISHED
            }
        }
        val normalizedTool = RunLogReplayPolicy.normalizeToolName(rawTool)
        val action = resolveActionName(rawTool)
        val sourceContext = mapArg(raw["source_context"]).ifEmpty { inheritedSourceContext }
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
                if (sourceContext.isNotEmpty()) step["source_context"] = sourceContext
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
            RunLogReplayPolicy.isOmniflowToolCallTool(normalizedTool) ||
                    firstNonBlank(stepArgs["function_id"]).isNotBlank() -> {
                step["kind"] = "omniflow_function"
                step["executor"] = RunLogReplayPolicy.EXECUTOR_OMNIFLOW
                step["model_free"] = true
                step["scriptable"] = true
                step["tool"] = RunLogReplayPolicy.TOOL_CALL_TOOL
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

    fun executionCapabilities(steps: List<Map<String, Any?>>): Map<String, Any?> = linkedMapOf(
        "omniflow_step_count" to steps.count { it["executor"] == RunLogReplayPolicy.EXECUTOR_OMNIFLOW },
        "agent_step_count" to steps.count { it["executor"] == RunLogReplayPolicy.EXECUTOR_AGENT },
        "has_agent_steps" to steps.any { it["executor"] == RunLogReplayPolicy.EXECUTOR_AGENT },
    )

    private fun normalizeSimpleStepArgs(raw: Map<String, Any?>, rawTool: String): Map<String, Any?> {
        val action = resolveActionName(rawTool) ?: OobActionSchema.normalizeToolName(rawTool)
        val args = linkedMapOf<String, Any?>()
        args.putAll(mapArg(raw["args"]))
        args.putAlias("package_name", raw["package_name"], raw["packageName"])
        args.putAlias("x", raw["x"])
        args.putAlias("y", raw["y"])
        args.putAlias("x1", raw["x1"]); args.putAlias("y1", raw["y1"])
        args.putAlias("x2", raw["x2"]); args.putAlias("y2", raw["y2"])
        args.putAlias("text", raw["text"])
        args.putAlias("content", raw["content"])
        args.putAlias("value", raw["value"])
        args.putAlias("target_description", raw["target_description"], raw["targetDescription"])
        args.putAlias("selector", raw["selector"])
        args.putAlias("node_id", raw["node_id"], raw["nodeId"])
        args.putAlias("scrollable_index", raw["scrollable_index"], raw["scrollableIndex"])
        args.putAlias("direction", raw["direction"])
        args.putAlias("duration_ms", raw["duration_ms"], raw["durationMs"])
        args.putAlias("clear", raw["clear"])
        args.putAlias("bounds", raw["bounds"])
        if (action == OobActionSchema.TOOL_INPUT_TEXT) { args.remove("content"); args.remove("value") }
        if (action == OobActionSchema.TOOL_FINISHED && args.isEmpty()) args["content"] = "Done"
        return argsForStep(mapOf("tool" to rawTool, "args" to args.filterValues { it != null }))
    }

    private fun canonicalSimpleCallToolArgs(normalizedArgs: Map<String, Any?>): Map<String, Any?> {
        val functionId = firstNonBlank(normalizedArgs["function_id"])
        val targetTool = firstNonBlank(normalizedArgs["tool_name"], normalizedArgs["target_tool"])
        val nestedArguments = mapArg(normalizedArgs["arguments"])
        return linkedMapOf<String, Any?>().apply {
            putAll(normalizedArgs)
            if (functionId.isNotBlank()) put("function_id", functionId)
            if (targetTool.isNotBlank()) put("tool_name", targetTool)
            if (nestedArguments.isNotEmpty()) put("arguments", nestedArguments)
        }.filterValues { it != null }
    }

    private fun simpleStepTitle(action: String, raw: Map<String, Any?>, index: Int): String {
        val args = mapArg(raw["args"])
        val target = firstNonBlank(
            args["target_description"], args["label"], args["text"],
            args["content"].takeIf { action != OobActionSchema.TOOL_INPUT_TEXT },
        )
        return if (target.isNotBlank()) "$action: $target" else "$action step ${index + 1}"
    }

    private fun MutableMap<String, Any?>.putAlias(key: String, vararg values: Any?) {
        if (containsKey(key)) return
        for (value in values) {
            if (value == null) continue
            if (value is String && value.isBlank()) continue
            put(key, value)
            return
        }
    }
}
