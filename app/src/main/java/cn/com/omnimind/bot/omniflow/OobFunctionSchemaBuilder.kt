package cn.com.omnimind.bot.omniflow

import cn.com.omnimind.bot.omniflow.OobFunctionJson.boolArg
import cn.com.omnimind.bot.omniflow.OobFunctionJson.firstNonBlank
import cn.com.omnimind.bot.omniflow.OobFunctionJson.listArg
import cn.com.omnimind.bot.omniflow.OobFunctionJson.mapArg
import cn.com.omnimind.bot.runlog.OobActionCodec
import cn.com.omnimind.bot.runlog.RunLogReplayPolicy

/**
 * Builds the JSON-schema shaped argument contract used when an OOB reusable
 * Function is exposed as an agent tool.
 */
object OobFunctionSchemaBuilder {
    fun inputSchema(spec: Map<String, Any?>): Map<String, Any?> {
        val explicit = mapArg(spec["inputSchema"]).ifEmpty { mapArg(spec["input_schema"]) }
        if (explicit.isNotEmpty()) return publicInputSchema(explicit)

        val canonical = mapArg(spec["parameters"])
        if (canonical.isNotEmpty() && firstNonBlank(canonical["type"]).equals("object", ignoreCase = true)) {
            return publicInputSchema(canonical)
        }

        val properties = linkedMapOf<String, Any?>()
        val required = mutableListOf<String>()
        listArg(spec["parameters"]).forEach { raw ->
            val parameter = mapArg(raw)
            val name = parameter["name"]?.toString()?.trim().orEmpty()
            if (name.isEmpty()) return@forEach
            if (!isPublicParameterName(name)) return@forEach

            val type = parameter["type"]?.toString()?.trim()?.ifEmpty { "string" } ?: "string"
            val bindings = listArg(parameter["bindings"])
                .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            if (bindings.isEmpty()) return@forEach
            val property = linkedMapOf<String, Any?>("type" to jsonSchemaType(type))
            parameter["description"]?.toString()?.takeIf { it.isNotBlank() }?.let { property["description"] = it }
            if (parameter.containsKey("default")) property["default"] = parameter["default"]
            val enumValues = listArg(parameter["enum"]).ifEmpty { listArg(parameter["values"]) }
            if (enumValues.isNotEmpty()) property["enum"] = enumValues
            property["x_oob_bindings"] = bindings
            properties[name] = property
            if (boolArg(parameter["required"])) required += name
        }
        return linkedMapOf(
            "type" to "object",
            "properties" to properties,
            "required" to required.filter { it in properties.keys },
            "additionalProperties" to false,
        )
    }

    fun functionId(spec: Map<String, Any?>): String = firstNonBlank(spec["function_id"])

    fun parameterNames(spec: Map<String, Any?>): List<String> {
        val canonical = mapArg(spec["parameters"])
        if (canonical.isNotEmpty()) {
            return mapArg(inputSchema(spec)["properties"]).keys.map { it.trim() }.filter { it.isNotEmpty() }
        }
        return listArg(spec["parameters"]).mapNotNull { raw ->
            val parameter = mapArg(raw)
            val name = parameter["name"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            name?.takeIf { isPublicParameterName(it) && listArg(parameter["bindings"]).isNotEmpty() }
        }
    }

    private fun publicInputSchema(schema: Map<String, Any?>): Map<String, Any?> {
        val properties = mapArg(schema["properties"])
        val publicProperties = linkedMapOf<String, Any?>()
        properties.forEach { (name, rawProperty) ->
            if (!isPublicParameterName(name)) return@forEach
            val property = mapArg(rawProperty)
            val bindings = parameterBindings(property)
            if (bindings.isEmpty()) return@forEach
            publicProperties[name] = linkedMapOf<String, Any?>().apply {
                putAll(property); put("x_oob_bindings", bindings)
                remove("bindings"); remove("x-oob-bindings")
            }
        }
        val required = listArg(schema["required"])
            .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            .filter { it in publicProperties.keys }
        return linkedMapOf<String, Any?>().apply {
            put("type", "object"); put("properties", publicProperties)
            put("required", required); put("additionalProperties", false)
        }
    }

    private fun parameterBindings(property: Map<String, Any?>): List<String> =
        (listArg(property["x_oob_bindings"]) + listArg(property["x-oob-bindings"]) + listArg(property["bindings"]))
            .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }.distinct()

    private fun isPublicParameterName(name: String): Boolean =
        normalizeParameterName(name) !in INTERNAL_PARAMETER_NAMES

    private fun normalizeParameterName(value: String): String =
        value.trim().replace(Regex("""([a-z0-9])([A-Z])"""), "$1_$2")
            .replace(Regex("""[^A-Za-z0-9]+"""), "_").trim('_').lowercase()

    fun materializedSteps(spec: Map<String, Any?>): List<Map<String, Any?>> {
        val execution = mapArg(spec["execution"])
        val legacySteps = listArg(execution["steps"]).mapNotNull { mapArg(it).takeIf { m -> m.isNotEmpty() } }
        if (legacySteps.isNotEmpty()) return legacySteps
        return listArg(spec["actions"]).mapIndexedNotNull { index, raw -> canonicalActionToStep(index, mapArg(raw)) }
    }

    fun stepSummaries(spec: Map<String, Any?>): List<Map<String, Any?>> =
        materializedSteps(spec).mapIndexed { index, step ->
            val tool = OobActionCodec.actionNameForStep(step)
            linkedMapOf("index" to index, "id" to firstNonBlank(step["id"], "step_${index + 1}"),
                "title" to firstNonBlank(step["title"], step["summary"], tool),
                "kind" to step["kind"], "executor" to step["executor"], "tool" to tool)
        }

    private fun canonicalActionToStep(index: Int, action: Map<String, Any?>): Map<String, Any?>? {
        val rawType = firstNonBlank(action["tool"], action["type"], action["action"])
        if (rawType.isEmpty()) return null
        val normalizedType = OobActionCodec.canonicalActionForName(rawType) ?: OobActionCodec.normalizeName(rawType)
        val params = OobActionCodec.argsForStep(mapOf("tool" to rawType, "args" to mapArg(action["args"])))
        val sourceContext = mapArg(params["source_context"])
        val title = firstNonBlank(action["description"], action["prompt"], rawType).ifBlank { normalizedType }
        val stepId = firstNonBlank(action["id"], action["step_id"], "step_${index + 1}")
        return when {
            normalizedType == OobActionCodec.ACTION_CLICK -> {
                val nodeId = firstNonBlank(params["node_id"])
                if (nodeId.isNotBlank() && firstNonBlank(params["x"]).isBlank() && firstNonBlank(params["y"]).isBlank()) {
                    graphStep(stepId, index, title, linkedMapOf("node_id" to nodeId).filterValues { it.isNotBlank() })
                } else {
                    localActionStep(stepId, index, title, OobActionCodec.ACTION_CLICK, linkedMapOf<String, Any?>().apply {
                        putFP("x", params["x"]); putFP("y", params["y"])
                        putFP("target_description", params["target_description"]); putFP("selector", params["selector"])
                        putFP("node_id", params["node_id"]); putFP("element_index", params["element_index"])
                        putFP("node_resource_id", params["node_resource_id"]); putFP("bounds", params["bounds"])
                        if (sourceContext.isNotEmpty()) put("source_context", sourceContext)
                    }, sourceContext)
                }
            }
            normalizedType == OobActionCodec.ACTION_LONG_PRESS ->
                localActionStep(stepId, index, title, OobActionCodec.ACTION_LONG_PRESS, linkedMapOf<String, Any?>().apply {
                    putFP("x", params["x"]); putFP("y", params["y"]); putFP("duration_ms", params["duration_ms"])
                    putFP("target_description", params["target_description"]); putFP("selector", params["selector"])
                    putFP("node_id", params["node_id"]); putFP("element_index", params["element_index"])
                    if (sourceContext.isNotEmpty()) put("source_context", sourceContext)
                }, sourceContext)
            normalizedType == OobActionCodec.ACTION_INPUT_TEXT ->
                localActionStep(stepId, index, title, OobActionCodec.ACTION_INPUT_TEXT, linkedMapOf<String, Any?>().apply {
                    putFP("text", params["text"]); putFP("target_description", params["target_description"])
                    putFP("x", params["x"]); putFP("y", params["y"]); putFP("node_id", params["node_id"])
                    putFP("element_index", params["element_index"]); putFP("node_resource_id", params["node_resource_id"])
                    putFP("bounds", params["bounds"]); putFP("selector", params["selector"]); putFP("clear", params["clear"])
                    if (sourceContext.isNotEmpty()) put("source_context", sourceContext)
                }, sourceContext)
            normalizedType == OobActionCodec.ACTION_SWIPE ->
                localActionStep(stepId, index, title, OobActionCodec.ACTION_SWIPE, linkedMapOf<String, Any?>().apply {
                    putFP("target_description", params["target_description"]); putFP("x1", params["x1"]); putFP("y1", params["y1"])
                    putFP("x2", params["x2"]); putFP("y2", params["y2"]); putFP("direction", params["direction"])
                    putFP("duration_ms", params["duration_ms"]); putFP("scrollable_index", params["scrollable_index"])
                    if (sourceContext.isNotEmpty()) put("source_context", sourceContext)
                }, sourceContext)
            normalizedType == OobActionCodec.ACTION_OPEN_APP ->
                localActionStep(stepId, index, title, OobActionCodec.ACTION_OPEN_APP,
                    linkedMapOf<String, Any?>().apply { putFP("package_name", params["package_name"]) }, emptyMap())
            normalizedType == OobActionCodec.ACTION_PRESS_KEY ->
                localActionStep(stepId, index, title, OobActionCodec.ACTION_PRESS_KEY,
                    linkedMapOf<String, Any?>().apply { putFP("key", params["key"]) }, emptyMap())
            normalizedType == OobActionCodec.ACTION_FINISHED ->
                localActionStep(stepId, index, title, OobActionCodec.ACTION_FINISHED, linkedMapOf<String, Any?>().apply {
                    putFP("content", params["content"]); putFP("enable_summary", params["enable_summary"])
                    putFP("summary_prompt", params["summary_prompt"])
                }, emptyMap())
            RunLogReplayPolicy.isOmniflowGraphTool(normalizedType) ->
                graphStep(stepId, index, title, linkedMapOf<String, Any?>().apply {
                    putFP("node_id", params["node_id"]); putFP("path", params["path"]); putFP("utg", params["utg"])
                })
            RunLogReplayPolicy.isOmniflowToolCallTool(normalizedType) ->
                functionStep(stepId, index, title, linkedMapOf<String, Any?>().apply {
                    putFP("function_id", params["function_id"]); putFP("node_id", params["node_id"])
                    val arguments = mapArg(params["arguments"])
                    if (arguments.isNotEmpty()) put("arguments", arguments)
                })
            normalizedType == RunLogReplayPolicy.TOOL_EXTERNAL_TOOL ->
                externalToolStep(stepId, index, title, firstNonBlank(params["tool_name"]), mapArg(params["arguments"]))
            else -> externalToolStep(stepId, index, title, normalizedType, params)
        }
    }

    private fun localActionStep(stepId: String, index: Int, title: String, action: String, args: Map<String, Any?>, sourceContext: Map<String, Any?>): Map<String, Any?> =
        linkedMapOf<String, Any?>("id" to stepId, "index" to index, "title" to title, "kind" to "function",
            "executor" to RunLogReplayPolicy.EXECUTOR_OMNIFLOW, "model_free" to true, "scriptable" to true,
            "tool" to action, "args" to args.filterValues { it != null },
            "source_context" to sourceContext.takeIf { it.isNotEmpty() }).filterValues { it != null }

    private fun graphStep(stepId: String, index: Int, title: String, args: Map<String, Any?>): Map<String, Any?> =
        linkedMapOf<String, Any?>("id" to stepId, "index" to index, "title" to title, "kind" to "omniflow_graph",
            "executor" to RunLogReplayPolicy.EXECUTOR_OMNIFLOW, "model_free" to true, "scriptable" to true,
            "tool" to RunLogReplayPolicy.TOOL_GO_TO_NODE, "args" to args.filterValues { it != null })

    private fun functionStep(stepId: String, index: Int, title: String, args: Map<String, Any?>): Map<String, Any?> =
        linkedMapOf<String, Any?>("id" to stepId, "index" to index, "title" to title, "kind" to "omniflow_function",
            "executor" to RunLogReplayPolicy.EXECUTOR_OMNIFLOW, "model_free" to true, "scriptable" to true,
            "tool" to RunLogReplayPolicy.TOOL_CALL_TOOL, "args" to args.filterValues { it != null })

    private fun externalToolStep(stepId: String, index: Int, title: String, toolName: String, args: Map<String, Any?>): Map<String, Any?> =
        linkedMapOf<String, Any?>("id" to stepId, "index" to index, "title" to title, "kind" to "tool_call",
            "executor" to RunLogReplayPolicy.EXECUTOR_TOOL, "scriptable" to true, "tool" to toolName, "args" to args)

    private fun jsonSchemaType(type: String): String = when (type.lowercase()) {
        "int", "integer" -> "integer"; "number", "float", "double" -> "number"
        "bool", "boolean" -> "boolean"; "array", "object" -> type.lowercase(); else -> "string"
    }

    private fun MutableMap<String, Any?>.putFP(key: String, vararg values: Any?) {
        values.firstOrNull { it != null && it.toString().trim().isNotEmpty() }?.let { put(key, it) }
    }

    private val INTERNAL_PARAMETER_NAMES = setOf("package_name","package","target_description","target",
        "selector","node_id","node_resource_id","element_index","scrollable_index",
        "x","y","x1","y1","x2","y2","bounds","clear","duration_ms")
}
