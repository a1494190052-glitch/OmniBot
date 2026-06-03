package cn.com.omnimind.bot.runlog

import cn.com.omnimind.bot.omniflow.OobFunctionToolNames
import cn.com.omnimind.bot.runlog.OobActionCodec.boolArg
import cn.com.omnimind.bot.runlog.OobActionCodec.firstNonBlank
import cn.com.omnimind.bot.runlog.OobActionCodec.listArg
import cn.com.omnimind.bot.runlog.OobActionCodec.mapArg

/**
 * Builds the JSON-schema shaped argument contract used when an OOB reusable
 * Function is exposed as an agent tool.
 */
object OobFunctionSchemaBuilder {
    fun inputSchema(spec: Map<String, Any?>): Map<String, Any?> {
        val explicit = mapArg(spec["inputSchema"]).ifEmpty { mapArg(spec["input_schema"]) }
        if (explicit.isNotEmpty()) return explicit

        val canonical = mapArg(spec["parameters"])
        if (canonical.isNotEmpty() && firstNonBlank(canonical["type"]).equals("object", ignoreCase = true)) {
            return canonical
        }

        val properties = linkedMapOf<String, Any?>()
        val required = mutableListOf<String>()
        listArg(spec["parameters"]).forEach { raw ->
            val parameter = mapArg(raw)
            val name = parameter["name"]?.toString()?.trim().orEmpty()
            if (name.isEmpty()) return@forEach

            val type = parameter["type"]?.toString()?.trim()?.ifEmpty { "string" } ?: "string"
            val property = linkedMapOf<String, Any?>(
                "type" to jsonSchemaType(type)
            )
            parameter["description"]?.toString()?.takeIf { it.isNotBlank() }?.let {
                property["description"] = it
            }
            if (parameter.containsKey("default")) {
                property["default"] = parameter["default"]
            }
            val enumValues = listArg(parameter["enum"]).ifEmpty { listArg(parameter["values"]) }
            if (enumValues.isNotEmpty()) {
                property["enum"] = enumValues
            }

            properties[name] = property
            if (boolArg(parameter["required"])) {
                required += name
            }
        }

        return linkedMapOf(
            "type" to "object",
            "properties" to properties,
            "required" to required
        )
    }

    fun functionId(spec: Map<String, Any?>): String =
        firstNonBlank(spec["function_id"])

    fun parameterNames(spec: Map<String, Any?>): List<String> {
        val canonical = mapArg(spec["parameters"])
        if (canonical.isNotEmpty()) {
            return mapArg(canonical["properties"]).keys
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
        return listArg(spec["parameters"]).mapNotNull { raw ->
            mapArg(raw)["name"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    fun materializedSteps(spec: Map<String, Any?>): List<Map<String, Any?>> {
        val execution = mapArg(spec["execution"])
        val legacySteps = listArg(execution["steps"]).mapNotNull { raw ->
            mapArg(raw).takeIf { it.isNotEmpty() }
        }
        if (legacySteps.isNotEmpty()) return legacySteps

        return listArg(spec["actions"]).mapIndexedNotNull { index, raw ->
            canonicalActionToStep(index, mapArg(raw))
        }
    }

    fun stepSummaries(spec: Map<String, Any?>): List<Map<String, Any?>> =
        materializedSteps(spec).mapIndexed { index, step ->
            val tool = OobActionCodec.actionNameForStep(step)
            linkedMapOf(
                "index" to index,
                "id" to firstNonBlank(step["id"], "step_${index + 1}"),
                "title" to firstNonBlank(step["title"], step["summary"], tool),
                "kind" to step["kind"],
                "executor" to step["executor"],
                "tool" to tool,
            )
        }

    private fun canonicalActionToStep(
        index: Int,
        action: Map<String, Any?>,
    ): Map<String, Any?>? {
        val rawType = firstNonBlank(action["tool"])
        if (rawType.isEmpty()) return null
        val normalizedType = OobActionCodec.canonicalActionForName(rawType)
            ?: OobActionCodec.normalizeName(rawType)
        val params = OobActionCodec.argsForStep(
            mapOf(
                "tool" to rawType,
                "args" to mapArg(action["args"]),
            )
        )
        val sourceContext = mapArg(params["source_context"])
        val title = firstNonBlank(action["description"], action["prompt"], rawType)
            .ifBlank { normalizedType }
        val stepId = firstNonBlank(action["id"], action["step_id"], "step_${index + 1}")

        return when {
            normalizedType == OobActionCodec.ACTION_CLICK -> {
                val nodeId = firstNonBlank(params["node_id"])
                if (nodeId.isNotBlank() && firstNonBlank(params["x"]).isBlank() && firstNonBlank(params["y"]).isBlank()) {
                    graphStep(
                        stepId = stepId,
                        index = index,
                        title = title,
                        args = linkedMapOf(
                            "node_id" to nodeId,
                        ).filterValues { it.isNotBlank() },
                    )
                } else {
                    localActionStep(
                        stepId = stepId,
                        index = index,
                        title = title,
                        action = OobActionCodec.ACTION_CLICK,
                        args = linkedMapOf<String, Any?>().apply {
                            putFirstPresent("x", params["x"])
                            putFirstPresent("y", params["y"])
                            putFirstPresent(
                                "target_description",
                                params["target_description"],
                            )
                            putFirstPresent("selector", params["selector"])
                            putFirstPresent("node_id", params["node_id"])
                            putFirstPresent("element_index", params["element_index"])
                            if (sourceContext.isNotEmpty()) put("source_context", sourceContext)
                        },
                        sourceContext = sourceContext,
                    )
                }
            }
            normalizedType == OobActionCodec.ACTION_LONG_PRESS -> localActionStep(
                stepId = stepId,
                index = index,
                title = title,
                action = OobActionCodec.ACTION_LONG_PRESS,
                args = linkedMapOf<String, Any?>().apply {
                    putFirstPresent("x", params["x"])
                    putFirstPresent("y", params["y"])
                    putFirstPresent("duration_ms", params["duration_ms"])
                    putFirstPresent("target_description", params["target_description"])
                    putFirstPresent("selector", params["selector"])
                    putFirstPresent("node_id", params["node_id"])
                    putFirstPresent("element_index", params["element_index"])
                    if (sourceContext.isNotEmpty()) put("source_context", sourceContext)
                },
                sourceContext = sourceContext,
            )
            normalizedType == OobActionCodec.ACTION_INPUT_TEXT -> localActionStep(
                stepId = stepId,
                index = index,
                title = title,
                action = OobActionCodec.ACTION_INPUT_TEXT,
                args = linkedMapOf<String, Any?>().apply {
                    putFirstPresent("text", params["text"])
                    putFirstPresent(
                        "target_description",
                        params["target_description"],
                    )
                    putFirstPresent("x", params["x"])
                    putFirstPresent("y", params["y"])
                    putFirstPresent("node_id", params["node_id"])
                    putFirstPresent("element_index", params["element_index"])
                    putFirstPresent("node_resource_id", params["node_resource_id"])
                    putFirstPresent("bounds", params["bounds"])
                    putFirstPresent("selector", params["selector"])
                    putFirstPresent("clear", params["clear"])
                    if (sourceContext.isNotEmpty()) put("source_context", sourceContext)
                },
                sourceContext = sourceContext,
            )
            normalizedType == OobActionCodec.ACTION_SCROLL -> localActionStep(
                stepId = stepId,
                index = index,
                title = title,
                action = OobActionCodec.ACTION_SCROLL,
                args = linkedMapOf<String, Any?>().apply {
                    putFirstPresent("target_description", params["target_description"])
                    putFirstPresent("x1", params["x1"])
                    putFirstPresent("y1", params["y1"])
                    putFirstPresent("x2", params["x2"])
                    putFirstPresent("y2", params["y2"])
                    putFirstPresent("direction", params["direction"])
                    putFirstPresent("duration_ms", params["duration_ms"])
                    putFirstPresent("scrollable_index", params["scrollable_index"])
                    if (sourceContext.isNotEmpty()) put("source_context", sourceContext)
                },
                sourceContext = sourceContext,
            )
            normalizedType == OobActionCodec.ACTION_OPEN_APP -> localActionStep(
                stepId = stepId,
                index = index,
                title = title,
                action = OobActionCodec.ACTION_OPEN_APP,
                args = linkedMapOf<String, Any?>().apply {
                    putFirstPresent("package_name", params["package_name"])
                },
                sourceContext = emptyMap(),
            )
            normalizedType == OobActionCodec.ACTION_PRESS_BACK ||
                normalizedType == OobActionCodec.ACTION_PRESS_HOME -> localActionStep(
                stepId = stepId,
                index = index,
                title = title,
                action = normalizedType,
                args = emptyMap(),
                sourceContext = emptyMap(),
            )
            normalizedType == OobActionCodec.ACTION_FINISHED -> localActionStep(
                stepId = stepId,
                index = index,
                title = title,
                action = OobActionCodec.ACTION_FINISHED,
                args = linkedMapOf<String, Any?>().apply {
                    putFirstPresent("content", params["content"])
                    putFirstPresent("enable_summary", params["enable_summary"])
                    putFirstPresent("summary_prompt", params["summary_prompt"])
                },
                sourceContext = emptyMap(),
            )
            RunLogReplayPolicy.isOmniflowGraphTool(normalizedType) -> graphStep(
                stepId = stepId,
                index = index,
                title = title,
                args = linkedMapOf<String, Any?>().apply {
                    putFirstPresent("node_id", params["node_id"])
                    putFirstPresent("path", params["path"])
                    putFirstPresent("utg", params["utg"])
                },
            )
            RunLogReplayPolicy.isOmniflowFunctionTool(normalizedType) -> functionStep(
                stepId = stepId,
                index = index,
                title = title,
                args = linkedMapOf<String, Any?>().apply {
                    putFirstPresent(
                        "function_id",
                        params["function_id"],
                    )
                    putFirstPresent("node_id", params["node_id"])
                    val arguments = mapArg(params["arguments"])
                    if (arguments.isNotEmpty()) put("arguments", arguments)
                },
            )
            normalizedType == RunLogReplayPolicy.TOOL_EXTERNAL_TOOL -> externalToolStep(
                stepId = stepId,
                index = index,
                title = title,
                toolName = firstNonBlank(
                    params["tool_name"],
                ),
                args = mapArg(params["arguments"]),
            )
            else -> externalToolStep(
                stepId = stepId,
                index = index,
                title = title,
                toolName = normalizedType,
                args = params,
            )
        }
    }

    private fun localActionStep(
        stepId: String,
        index: Int,
        title: String,
        action: String,
        args: Map<String, Any?>,
        sourceContext: Map<String, Any?>,
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "id" to stepId,
        "index" to index,
        "title" to title,
        "kind" to "function",
        "executor" to RunLogReplayPolicy.EXECUTOR_OMNIFLOW,
        "model_free" to true,
        "scriptable" to true,
        "tool" to action,
        "args" to args.filterValues { it != null },
        "source_context" to sourceContext.takeIf { it.isNotEmpty() },
    ).filterValues { it != null }

    private fun graphStep(
        stepId: String,
        index: Int,
        title: String,
        args: Map<String, Any?>,
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "id" to stepId,
        "index" to index,
        "title" to title,
        "kind" to "omniflow_graph",
        "executor" to RunLogReplayPolicy.EXECUTOR_OMNIFLOW,
        "model_free" to true,
        "scriptable" to true,
        "tool" to RunLogReplayPolicy.TOOL_GO_TO_NODE,
        "args" to args.filterValues { it != null },
    )

    private fun functionStep(
        stepId: String,
        index: Int,
        title: String,
        args: Map<String, Any?>,
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "id" to stepId,
        "index" to index,
        "title" to title,
        "kind" to "omniflow_function",
        "executor" to RunLogReplayPolicy.EXECUTOR_OMNIFLOW,
        "model_free" to true,
        "scriptable" to true,
        "tool" to OobFunctionToolNames.FUNCTION_RUN,
        "args" to args.filterValues { it != null },
    )

    private fun externalToolStep(
        stepId: String,
        index: Int,
        title: String,
        toolName: String,
        args: Map<String, Any?>,
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "id" to stepId,
        "index" to index,
        "title" to title,
        "kind" to "tool_call",
        "executor" to RunLogReplayPolicy.EXECUTOR_TOOL,
        "scriptable" to true,
        "tool" to toolName,
        "args" to args,
    )

    private fun jsonSchemaType(type: String): String =
        when (type.lowercase()) {
            "int", "integer" -> "integer"
            "number", "float", "double" -> "number"
            "bool", "boolean" -> "boolean"
            "array", "object" -> type.lowercase()
            else -> "string"
        }

    private fun MutableMap<String, Any?>.putFirstPresent(key: String, vararg values: Any?) {
        values.firstOrNull { value ->
            value != null && value.toString().trim().isNotEmpty()
        }?.let { put(key, it) }
    }
}
