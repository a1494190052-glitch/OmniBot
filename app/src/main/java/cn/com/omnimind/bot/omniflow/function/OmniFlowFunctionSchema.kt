package cn.com.omnimind.bot.omniflow.function
import cn.com.omnimind.bot.runlog.argsForStep
import cn.com.omnimind.bot.runlog.actionNameForStep
import cn.com.omnimind.bot.runlog.resolveActionName
import cn.com.omnimind.baselib.runlog.OobActionSchema

import cn.com.omnimind.bot.omniflow.function.OmniFlowFunctionJson.boolArg
import cn.com.omnimind.bot.omniflow.function.OmniFlowFunctionJson.firstNonBlank
import cn.com.omnimind.bot.omniflow.function.OmniFlowFunctionJson.listArg
import cn.com.omnimind.bot.omniflow.function.OmniFlowFunctionJson.mapArg
import cn.com.omnimind.bot.omniflow.function.OmniFlowFunctionJson.mutableJsonMap
import cn.com.omnimind.bot.omniflow.function.OmniFlowFunctionJson.sanitizeMap
import cn.com.omnimind.bot.omniflow.function.OmniFlowFunctionJson.sanitizeValue
import cn.com.omnimind.bot.runlog.RunLogReplayPolicy

/**
 * Builds the public argument contract used by runtime recall/resolve before an
 * OmniFlow Function is replayed.
 */
object OmniFlowFunctionSchema {
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

    fun functionIdFromSpec(spec: Map<String, Any?>): String =
        firstNonBlank(spec["function_id"], spec["functionId"], spec["name"])

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

    fun materialize(
        functionSpec: Map<String, Any?>,
        arguments: Map<String, Any?>
    ): Map<String, Any?> {
        val spec = mutableJsonMap(sanitizeMap(functionSpec))
        val resolvedArguments = linkedMapOf<String, Any?>()
        val bindingResults = mutableListOf<Map<String, Any?>>()
        val suppliedArgumentNames = arguments.keys
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val suppliedArgumentStatus = linkedMapOf<String, LinkedHashMap<String, Any?>>()
        val ignoredArguments = mutableListOf<LinkedHashMap<String, Any?>>()
        val missingRequired = mutableListOf<String>()
        val legacyParameters = spec["parameters"] as? List<*> ?: emptyList<Any?>()
        if (legacyParameters.isNotEmpty()) {
            legacyParameters.forEach { rawParameter ->
                val parameter = rawParameter as? Map<*, *> ?: return@forEach
                val name = parameter["name"]?.toString()?.trim().orEmpty()
                if (name.isEmpty()) return@forEach
                if (isInternalFunctionArgumentName(name)) {
                    if (arguments.containsKey(name)) {
                        val ignored = ignoredArgumentStatus(name, "internal_replay_argument")
                        suppliedArgumentStatus[name] = ignored
                        ignoredArguments += ignored
                    }
                    return@forEach
                }
                val type = parameter["type"]?.toString()?.trim().orEmpty()
                val hasCallArgument = arguments.containsKey(name)
                val hasDefault = parameter.containsKey("default")
                val rawValue = if (hasCallArgument) arguments[name] else parameter["default"]
                val value = coerceParameterValue(rawValue, type)
                if (value == null) {
                    if (isRequired(parameter["required"]) && !hasCallArgument && !hasDefault) {
                        missingRequired += name
                    }
                    return@forEach
                }
                resolvedArguments[name] = value
                val bindings = listArg(parameter["bindings"])
                    .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
                    .filter(::isCanonicalBindingPath)
                var appliedCount = 0
                bindings.forEach { binding ->
                    val applied = setJsonPathValue(spec, binding, value)
                    if (applied) appliedCount += 1
                    bindingResults += linkedMapOf(
                        "parameter" to name,
                        "binding" to binding,
                        "applied" to applied
                    )
                }
                if (hasCallArgument) {
                    suppliedArgumentStatus[name] = argumentStatus(
                        name = name,
                        bindings = bindings,
                        appliedCount = appliedCount
                    )
                }
            }
        } else {
            val parameterSchema = mapArg(spec["parameters"])
            val properties = mapArg(parameterSchema["properties"])
            val requiredNames = listArg(parameterSchema["required"])
                .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
                .toSet()
            properties.forEach { (name, rawProperty) ->
                val parameter = mapArg(rawProperty)
                if (name.isBlank()) return@forEach
                if (isInternalFunctionArgumentName(name)) {
                    if (arguments.containsKey(name)) {
                        val ignored = ignoredArgumentStatus(name, "internal_replay_argument")
                        suppliedArgumentStatus[name] = ignored
                        ignoredArguments += ignored
                    }
                    return@forEach
                }
                val type = firstJsonSchemaType(parameter["type"])
                val hasCallArgument = arguments.containsKey(name)
                val hasDefault = parameter.containsKey("default")
                val rawValue = if (hasCallArgument) arguments[name] else parameter["default"]
                val value = coerceParameterValue(rawValue, type)
                if (value == null) {
                    if (name in requiredNames && !hasCallArgument && !hasDefault) {
                        missingRequired += name
                    }
                    return@forEach
                }
                resolvedArguments[name] = value
                val bindings = parameterBindings(name, parameter, spec)
                var appliedCount = 0
                bindings.forEach { binding ->
                    val applied = setJsonPathValue(spec, binding, value)
                    if (applied) appliedCount += 1
                    bindingResults += linkedMapOf(
                        "parameter" to name,
                        "binding" to binding,
                        "applied" to applied
                    )
                }
                if (hasCallArgument) {
                    suppliedArgumentStatus[name] = argumentStatus(
                        name = name,
                        bindings = bindings,
                        appliedCount = appliedCount
                    )
                }
            }
        }

        suppliedArgumentNames.forEach { name ->
            suppliedArgumentStatus.putIfAbsent(
                name,
                if (isInternalFunctionArgumentName(name)) {
                    ignoredArgumentStatus(name, "internal_replay_argument").also { ignoredArguments += it }
                } else {
                    ignoredArgumentStatus(name, "argument_not_declared").also { ignoredArguments += it }
                }
            )
        }
        val unboundArguments = suppliedArgumentStatus.values
            .filter { it["applied"] != true && it["ignored"] != true }
            .map { linkedMapOf<String, Any?>().apply { putAll(it) } }
        val bindingAppliedCount = bindingResults.count { it["applied"] == true }
        val suppliedBindingAppliedCount = suppliedArgumentStatus.values.sumOf { status ->
            when (val count = status["applied_count"]) {
                is Number -> count.toInt()
                is String -> count.toIntOrNull() ?: 0
                else -> 0
            }
        }

        if (resolvedArguments.isNotEmpty()) {
            val rendered = renderParameterTemplates(spec, resolvedArguments)
            if (rendered is Map<*, *>) {
                val renderedSpec = mutableJsonMap(sanitizeMap(rendered))
                spec.clear()
                spec.putAll(renderedSpec)
            }
        }

        val existingRuntime = spec["runtime"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        spec["runtime"] = linkedMapOf<String, Any?>().apply {
            existingRuntime.forEach { (key, value) ->
                if (key != null) put(key.toString(), sanitizeValue(value))
            }
            put("arguments", sanitizeMap(arguments))
            put("resolved_arguments", resolvedArguments)
            put("binding_results", bindingResults)
            put("supplied_argument_names", suppliedArgumentNames)
            put("argument_binding_status", suppliedArgumentStatus.values.toList())
            put("unbound_arguments", unboundArguments)
            put("ignored_arguments", ignoredArguments.distinctBy { it["name"] })
            put("binding_applied_count", bindingAppliedCount)
            put("supplied_binding_applied_count", suppliedBindingAppliedCount)
            put("missing_required_arguments", missingRequired)
            put("materialized_at", System.currentTimeMillis().toString())
            put("runner", RUNNER)
        }
        val execution = spec["execution"] as? MutableMap<String, Any?>
        execution?.put("arguments_applied", true)
        execution?.put("argument_application", "native_materialized_before_agent_run")
        return spec
    }

    fun missingRequiredArguments(
        functionSpec: Map<String, Any?>,
        arguments: Map<String, Any?>
    ): List<String> {
        val legacyParameters = functionSpec["parameters"] as? List<*>
        if (legacyParameters != null) {
            return legacyParameters.mapNotNull { rawParameter ->
                val parameter = rawParameter as? Map<*, *> ?: return@mapNotNull null
                val name = parameter["name"]?.toString()?.trim().orEmpty()
                if (name.isEmpty() || isInternalFunctionArgumentName(name) || !isRequired(parameter["required"])) {
                    return@mapNotNull null
                }
                val hasArgument = arguments.containsKey(name) && arguments[name] != null
                val hasDefault = parameter.containsKey("default") && parameter["default"] != null
                if (hasArgument || hasDefault) null else name
            }
        }
        val schema = mapArg(functionSpec["parameters"])
        val required = listArg(schema["required"])
            .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            .filterNot(::isInternalFunctionArgumentName)
        if (required.isEmpty()) return emptyList()
        val properties = mapArg(schema["properties"])
        return required.mapNotNull { name ->
            val property = mapArg(properties[name])
            val hasArgument = arguments.containsKey(name) && arguments[name] != null
            val hasDefault = property.containsKey("default") && property["default"] != null
            if (hasArgument || hasDefault) null else name
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

    private fun parameterBindings(
        name: String,
        property: Map<String, Any?>,
        spec: Map<String, Any?>,
    ): List<String> {
        val output = linkedSetOf<String>()
        parameterBindings(property).forEach { path ->
            path.takeIf(::isCanonicalBindingPath)?.let(output::add)
        }

        val metadata = mapArg(spec["metadata"])
        val bindingEntries = listArg(spec["x_oob_parameter_bindings"]) +
            listArg(spec["parameter_bindings"]) +
            listArg(metadata["oob_parameter_bindings"])
        bindingEntries.forEach { rawEntry ->
            val entry = mapArg(rawEntry)
            if (firstNonBlank(entry["name"], entry["parameter"]) != name) return@forEach
            listArg(entry["bindings"]).forEach { raw ->
                raw?.toString()?.trim()?.takeIf(String::isNotEmpty)
                    ?.takeIf(::isCanonicalBindingPath)
                    ?.let(output::add)
            }
            firstNonBlank(entry["binding"])
                .takeIf(String::isNotEmpty)
                ?.takeIf(::isCanonicalBindingPath)
                ?.let(output::add)
        }
        return output.toList()
    }

    private fun isPublicParameterName(name: String): Boolean =
        normalizeParameterName(name) !in INTERNAL_PARAMETER_NAMES

    private fun normalizeParameterName(value: String): String =
        value.trim().replace(Regex("""([a-z0-9])([A-Z])"""), "$1_$2")
            .replace(Regex("""[^A-Za-z0-9]+"""), "_").trim('_').lowercase()

    private fun isInternalFunctionArgumentName(name: String): Boolean =
        normalizeParameterName(name) in INTERNAL_PARAMETER_NAMES

    private fun isCanonicalBindingPath(path: String): Boolean =
        path.matches(Regex("""^\$\.execution\.steps\[\d+]\.args\.[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+|\[\d+])*$""")) ||
            path.matches(Regex("""^\$\.actions\[\d+]\.args\.[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+|\[\d+])*$"""))

    private fun coerceParameterValue(value: Any?, type: String): Any? {
        if (value == null) return null
        return when (type.trim().lowercase()) {
            "number", "integer", "int" -> when (value) {
                is Number -> value
                is String -> value.trim().let { text ->
                    text.toLongOrNull() ?: text.toDoubleOrNull() ?: value
                }
                else -> value
            }
            "boolean", "bool" -> when (value) {
                is Boolean -> value
                is String -> when (value.trim().lowercase()) {
                    "true", "1", "yes", "y" -> true
                    "false", "0", "no", "n" -> false
                    else -> value
                }
                else -> value
            }
            else -> value
        }
    }

    private fun isRequired(value: Any?): Boolean =
        when (value) {
            is Boolean -> value
            is String -> value.trim().equals("true", ignoreCase = true)
            else -> false
        }

    private fun firstJsonSchemaType(value: Any?): String =
        when (value) {
            is String -> value.trim()
            is List<*> -> value.firstNotNullOfOrNull {
                it?.toString()?.trim()?.takeIf { type -> type != "null" && type.isNotEmpty() }
            }.orEmpty()
            else -> ""
        }

    private fun ignoredArgumentStatus(
        name: String,
        reason: String,
    ): LinkedHashMap<String, Any?> =
        linkedMapOf(
            "name" to name,
            "declared" to false,
            "binding_count" to 0,
            "applied_count" to 0,
            "applied" to false,
            "ignored" to true,
            "reason" to reason,
        )

    private fun argumentStatus(
        name: String,
        bindings: List<String>,
        appliedCount: Int,
    ): LinkedHashMap<String, Any?> =
        linkedMapOf(
            "name" to name,
            "declared" to true,
            "binding_count" to bindings.size,
            "applied_count" to appliedCount,
            "applied" to (appliedCount > 0),
            "reason" to when {
                appliedCount > 0 -> "bound"
                bindings.isEmpty() -> "no_binding_path"
                else -> "binding_path_not_applied"
            },
        )

    private fun renderParameterTemplates(
        value: Any?,
        arguments: Map<String, Any?>,
    ): Any? {
        if (arguments.isEmpty()) return value
        return when (value) {
            is String -> renderTemplateString(value, arguments)
            is Map<*, *> -> linkedMapOf<String, Any?>().apply {
                value.forEach { (key, item) ->
                    if (key != null) put(key.toString(), renderParameterTemplates(item, arguments))
                }
            }
            is List<*> -> value.map { renderParameterTemplates(it, arguments) }
            is Array<*> -> value.map { renderParameterTemplates(it, arguments) }
            else -> value
        }
    }

    private fun renderTemplateString(
        text: String,
        arguments: Map<String, Any?>,
    ): Any? {
        val exact = PARAMETER_TOKEN_REGEX.matchEntire(text.trim())
        if (exact != null) {
            val name = exact.groupValues[1]
            if (arguments.containsKey(name)) return arguments[name]
        }
        return PARAMETER_TOKEN_REGEX.replace(text) { match ->
            val name = match.groupValues[1]
            arguments[name]?.toString() ?: match.value
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun setJsonPathValue(
        root: MutableMap<String, Any?>,
        path: String,
        value: Any?
    ): Boolean {
        val normalized = path.trim().removePrefix("$.")
        if (normalized.isEmpty()) return false
        val parts = normalized.split(".").filter { it.isNotBlank() }
        if (parts.isEmpty()) return false
        var current: Any? = root
        parts.forEachIndexed { index, part ->
            val token = parsePathToken(part) ?: return false
            val isLast = index == parts.lastIndex
            val next = when (current) {
                is MutableMap<*, *> -> {
                    val map = current as MutableMap<String, Any?>
                    if (isLast && token.index == null) {
                        map[token.key] = value
                        return true
                    }
                    val child = map[token.key] ?: if (token.index == null && !isLast) {
                        linkedMapOf<String, Any?>().also { map[token.key] = it }
                    } else {
                        return false
                    }
                    if (token.index == null) {
                        child
                    } else {
                        val list = child as? MutableList<Any?> ?: return false
                        if (token.index !in list.indices) return false
                        if (isLast) {
                            list[token.index] = value
                            return true
                        }
                        list[token.index]
                    }
                }
                is MutableList<*> -> {
                    val list = current as MutableList<Any?>
                    val listIndex = token.key.toIntOrNull() ?: token.index ?: return false
                    if (listIndex !in list.indices) return false
                    if (isLast) {
                        list[listIndex] = value
                        return true
                    }
                    list[listIndex]
                }
                else -> return false
            }
            current = next
        }
        return false
    }

    private data class JsonPathToken(val key: String, val index: Int?)

    private fun parsePathToken(part: String): JsonPathToken? {
        val match = Regex("""^([A-Za-z0-9_]+)(?:\[(\d+)])?$""").matchEntire(part)
            ?: return null
        return JsonPathToken(
            key = match.groupValues[1],
            index = match.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toIntOrNull()
        )
    }

    fun sourceRunIds(spec: Map<String, Any?>): List<String> {
        fun MutableList<String>.addText(value: Any?) {
            val text = value?.toString()?.trim().orEmpty()
            if (text.isNotEmpty() && text !in this) add(text)
        }

        fun MutableList<String>.addList(value: Any?) {
            listArg(value).forEach { addText(it) }
        }

        val source = mapArg(spec["source"])
        val metadata = mapArg(spec["metadata"])
        val evidence = mapArg(metadata["oob_function_evidence"])
        val asset = mapArg(metadata["omniflow_asset"])
        return mutableListOf<String>().apply {
            addList(spec["source_run_ids"])
            addText(spec["source_run_id"])
            addList(metadata["source_run_ids"])
            addText(source["run_id"])
            addText(source["run_log_id"])
            addText(source["source_run_id"])
            addList(evidence["source_run_ids"])
            addList(asset["source_run_ids"])
            addText(metadata["run_id"])
            addText(metadata["run_log_id"])
            addText(metadata["source_run_id"])
            addText(evidence["latest_run_id"])
            addText(asset["source_run_id"])
        }
    }

    fun materializedSteps(spec: Map<String, Any?>): List<Map<String, Any?>> {
        val execution = mapArg(spec["execution"])
        val legacySteps = listArg(execution["steps"]).mapNotNull { mapArg(it).takeIf { m -> m.isNotEmpty() } }
        if (legacySteps.isNotEmpty()) return legacySteps
        val topLevelSteps = listArg(spec["steps"]).mapNotNull { mapArg(it).takeIf { m -> m.isNotEmpty() } }
        if (topLevelSteps.isNotEmpty()) return topLevelSteps
        return listArg(spec["actions"]).mapIndexedNotNull { index, raw -> canonicalActionToStep(index, mapArg(raw)) }
    }

    fun stepSummaries(spec: Map<String, Any?>): List<Map<String, Any?>> =
        materializedSteps(spec).mapIndexed { index, step ->
            val tool = actionNameForStep(step)
            linkedMapOf("index" to index, "id" to firstNonBlank(step["id"], "step_${index + 1}"),
                "title" to firstNonBlank(step["title"], step["summary"], tool),
                "kind" to step["kind"], "executor" to step["executor"], "tool" to tool)
        }

    private fun canonicalActionToStep(index: Int, action: Map<String, Any?>): Map<String, Any?>? {
        val rawType = firstNonBlank(action["tool"], action["type"], action["action"])
        if (rawType.isEmpty()) return null
        val normalizedType = resolveActionName(rawType) ?: OobActionSchema.normalizeToolName(rawType)
        val params = argsForStep(mapOf("tool" to rawType, "args" to mapArg(action["args"])))
        val sourceContext = mapArg(params["source_context"])
        val title = firstNonBlank(action["description"], action["prompt"], rawType).ifBlank { normalizedType }
        val stepId = firstNonBlank(action["id"], action["step_id"], "step_${index + 1}")
        return when {
            normalizedType == OobActionSchema.TOOL_CLICK ->
                localActionStep(stepId, index, title, OobActionSchema.TOOL_CLICK, linkedMapOf<String, Any?>().apply {
                    putFP("x", params["x"]); putFP("y", params["y"])
                    putFP("target_description", params["target_description"]); putFP("selector", params["selector"])
                    putFP("node_id", params["node_id"]); putFP("element_index", params["element_index"])
                    putFP("node_resource_id", params["node_resource_id"]); putFP("bounds", params["bounds"])
                    if (sourceContext.isNotEmpty()) put("source_context", sourceContext)
                }, sourceContext)
            normalizedType == OobActionSchema.TOOL_LONG_PRESS ->
                localActionStep(stepId, index, title, OobActionSchema.TOOL_LONG_PRESS, linkedMapOf<String, Any?>().apply {
                    putFP("x", params["x"]); putFP("y", params["y"]); putFP("duration_ms", params["duration_ms"])
                    putFP("target_description", params["target_description"]); putFP("selector", params["selector"])
                    putFP("node_id", params["node_id"]); putFP("element_index", params["element_index"])
                    if (sourceContext.isNotEmpty()) put("source_context", sourceContext)
                }, sourceContext)
            normalizedType == OobActionSchema.TOOL_INPUT_TEXT ->
                localActionStep(stepId, index, title, OobActionSchema.TOOL_INPUT_TEXT, linkedMapOf<String, Any?>().apply {
                    putFP("text", params["text"]); putFP("target_description", params["target_description"])
                    putFP("x", params["x"]); putFP("y", params["y"]); putFP("node_id", params["node_id"])
                    putFP("element_index", params["element_index"]); putFP("node_resource_id", params["node_resource_id"])
                    putFP("bounds", params["bounds"]); putFP("selector", params["selector"]); putFP("clear", params["clear"])
                    if (sourceContext.isNotEmpty()) put("source_context", sourceContext)
                }, sourceContext)
            normalizedType == OobActionSchema.TOOL_SWIPE ->
                localActionStep(stepId, index, title, OobActionSchema.TOOL_SWIPE, linkedMapOf<String, Any?>().apply {
                    putFP("target_description", params["target_description"]); putFP("x1", params["x1"]); putFP("y1", params["y1"])
                    putFP("x2", params["x2"]); putFP("y2", params["y2"]); putFP("direction", params["direction"])
                    putFP("duration_ms", params["duration_ms"]); putFP("scrollable_index", params["scrollable_index"])
                    if (sourceContext.isNotEmpty()) put("source_context", sourceContext)
                }, sourceContext)
            normalizedType == OobActionSchema.TOOL_OPEN_APP ->
                localActionStep(stepId, index, title, OobActionSchema.TOOL_OPEN_APP,
                    linkedMapOf<String, Any?>().apply { putFP("package_name", params["package_name"]) }, emptyMap())
            normalizedType == OobActionSchema.TOOL_PRESS_KEY ->
                localActionStep(stepId, index, title, OobActionSchema.TOOL_PRESS_KEY,
                    linkedMapOf<String, Any?>().apply { putFP("key", params["key"]) }, emptyMap())
            normalizedType == OobActionSchema.TOOL_FINISHED ->
                localActionStep(stepId, index, title, OobActionSchema.TOOL_FINISHED, linkedMapOf<String, Any?>().apply {
                    putFP("content", params["content"]); putFP("enable_summary", params["enable_summary"])
                    putFP("summary_prompt", params["summary_prompt"])
                }, emptyMap())
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
    private const val RUNNER = "oob_agent_reusable_function"
    private val PARAMETER_TOKEN_REGEX by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)\}""")
    }
}
