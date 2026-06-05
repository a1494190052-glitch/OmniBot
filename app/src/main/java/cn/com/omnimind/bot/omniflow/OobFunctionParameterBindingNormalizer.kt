package cn.com.omnimind.bot.omniflow

import cn.com.omnimind.baselib.runlog.OobCanonicalActionSchema
import cn.com.omnimind.bot.runlog.OobActionCodec
import cn.com.omnimind.bot.runlog.OobFunctionSchemaBuilder

/**
 * Normalizes runtime parameter bindings at the Function storage boundary.
 *
 * Enhancement agents should produce semantic parameter names. The normalizer
 * still accepts legacy mechanical names such as input_text_3 at import/storage
 * boundaries, but execution only honors explicit JSONPath bindings stored on
 * the Function spec.
 */
object OobFunctionParameterBindingNormalizer {
    fun normalize(functionSpec: Map<String, Any?>): Map<String, Any?> {
        val spec = OobFunctionJson.mutableJsonMap(OobFunctionJson.sanitizeMap(functionSpec))
        val steps = OobFunctionSchemaBuilder.materializedSteps(spec)
        val parameterBindings = linkedMapOf<String, LinkedHashSet<String>>()
        val changes = mutableListOf<Map<String, Any?>>()

        collectExistingBindingTables(spec, parameterBindings)
        normalizeLegacyParameters(spec, steps, parameterBindings, changes)
        normalizeSchemaParameters(spec, steps, parameterBindings, changes)
        syncMetadataBindings(spec, parameterBindings, changes)
        return spec
    }

    private fun collectExistingBindingTables(
        spec: Map<String, Any?>,
        parameterBindings: MutableMap<String, LinkedHashSet<String>>,
    ) {
        val metadata = mutableMapArg(spec["metadata"])
        val entries = OobFunctionJson.listArg(spec["x_oob_parameter_bindings"]) +
            OobFunctionJson.listArg(spec["parameter_bindings"]) +
            OobFunctionJson.listArg(metadata["oob_parameter_bindings"])
        entries.forEach { raw ->
            val entry = OobFunctionJson.mapArg(raw)
            val name = OobFunctionJson.firstNonBlank(entry["name"], entry["parameter"])
            if (name.isEmpty()) return@forEach
            val bindings = (
                bindingList(entry["bindings"]) +
                    listOfNotNull(entry["binding"]?.toString()?.trim()?.takeIf(String::isNotEmpty))
                ).distinct()
            if (bindings.isNotEmpty()) {
                parameterBindings.getOrPut(name) { linkedSetOf() }.addAll(bindings)
            }
        }
    }

    private fun normalizeLegacyParameters(
        spec: MutableMap<String, Any?>,
        steps: List<Map<String, Any?>>,
        parameterBindings: MutableMap<String, LinkedHashSet<String>>,
        changes: MutableList<Map<String, Any?>>,
    ) {
        val rawParameters = spec["parameters"] as? List<*> ?: return
        val normalized = rawParameters.map { raw ->
            val parameter = mutableMapArg(raw)
            val name = parameter["name"]?.toString()?.trim().orEmpty()
            if (name.isEmpty()) return@map parameter
            if (!isPublicParameterName(name)) return@map null

            val existing = bindingList(parameter["bindings"])
            val inferred = if (existing.isEmpty()) inferBindingsForName(name, spec, steps) else emptyList()
            val merged = (existing + inferred).distinct()
            if (merged.isNotEmpty()) {
                parameter["bindings"] = merged
                parameterBindings.getOrPut(name) { linkedSetOf() }.addAll(merged)
            }
            if (existing.isEmpty() && inferred.isNotEmpty()) {
                changes += changeEntry(name, inferred, "legacy_parameter_name")
            }
            parameter
        }.filterNotNull()
        spec["parameters"] = normalized
    }

    private fun normalizeSchemaParameters(
        spec: MutableMap<String, Any?>,
        steps: List<Map<String, Any?>>,
        parameterBindings: MutableMap<String, LinkedHashSet<String>>,
        changes: MutableList<Map<String, Any?>>,
    ) {
        val schema = spec["parameters"] as? MutableMap<*, *> ?: return
        val properties = schema["properties"] as? Map<*, *> ?: return
        val normalizedProperties = linkedMapOf<String, Any?>()
        properties.forEach { (rawName, rawProperty) ->
            val name = rawName?.toString()?.trim().orEmpty()
            if (name.isEmpty()) return@forEach
            val property = mutableMapArg(rawProperty)
            if (!isPublicParameterName(name)) return@forEach
            val existing = (
                bindingList(property["x_oob_bindings"]) +
                    bindingList(property["x-oob-bindings"]) +
                    bindingList(property["bindings"])
                ).distinct()
            val inferred = if (existing.isEmpty()) inferBindingsForName(name, spec, steps) else emptyList()
            val merged = (existing + inferred).distinct()
            if (merged.isNotEmpty()) {
                property["x_oob_bindings"] = merged
                parameterBindings.getOrPut(name) { linkedSetOf() }.addAll(merged)
            }
            if (existing.isEmpty() && inferred.isNotEmpty()) {
                changes += changeEntry(name, inferred, "schema_parameter_name")
            }
            normalizedProperties[name] = property
        }
        @Suppress("UNCHECKED_CAST")
        (schema as MutableMap<String, Any?>)["properties"] = normalizedProperties
        val normalizedRequired = OobFunctionJson.listArg(schema["required"])
            .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            .filter(::isPublicParameterName)
            .filter { it in normalizedProperties.keys }
        schema["required"] = normalizedRequired
    }

    private fun syncMetadataBindings(
        spec: MutableMap<String, Any?>,
        parameterBindings: Map<String, Set<String>>,
        changes: List<Map<String, Any?>>,
    ) {
        if (parameterBindings.isEmpty() && changes.isEmpty()) return
        val metadata = mutableMapArg(spec["metadata"])
        val byName = linkedMapOf<String, LinkedHashSet<String>>()
        OobFunctionJson.listArg(metadata["oob_parameter_bindings"]).forEach { raw ->
            val entry = OobFunctionJson.mapArg(raw)
            val name = OobFunctionJson.firstNonBlank(entry["name"], entry["parameter"])
            if (name.isEmpty()) return@forEach
            if (!isPublicParameterName(name)) return@forEach
            val bindings = (
                bindingList(entry["bindings"]) +
                    listOfNotNull(entry["binding"]?.toString()?.trim()?.takeIf(String::isNotEmpty))
                ).distinct()
            if (bindings.isNotEmpty()) {
                byName.getOrPut(name) { linkedSetOf() }.addAll(bindings)
            }
        }
        parameterBindings.forEach { (name, bindings) ->
            if (!isPublicParameterName(name)) return@forEach
            if (bindings.isNotEmpty()) byName.getOrPut(name) { linkedSetOf() }.addAll(bindings)
        }
        if (byName.isNotEmpty()) {
            val table = byName.map { (name, bindings) ->
                linkedMapOf(
                    "name" to name,
                    "bindings" to bindings.toList(),
                )
            }
            spec["x_oob_parameter_bindings"] = table
            metadata["oob_parameter_bindings"] = table
        }
        if (changes.isNotEmpty()) {
            metadata["oob_parameter_binding_normalization"] = linkedMapOf(
                "strategy" to "input_text_step_name_binding",
                "change_count" to changes.size,
                "changes" to changes,
            )
        }
        spec["metadata"] = metadata
    }

    private fun inferBindingsForName(
        name: String,
        spec: Map<String, Any?>,
        steps: List<Map<String, Any?>>,
    ): List<String> {
        if (!isPublicParameterName(name)) return emptyList()
        val explicitInputTextIndex = stepIndexFromInputTextName(name, steps)
        if (explicitInputTextIndex != null) {
            return inputTextBindingsForStep(name, spec, steps, explicitInputTextIndex)
        }
        return semanticBindingsForName(name, steps)
    }

    private fun inputTextBindingsForStep(
        name: String,
        spec: Map<String, Any?>,
        steps: List<Map<String, Any?>>,
        stepIndex: Int,
    ): List<String> {
        val step = steps.getOrNull(stepIndex) ?: return emptyList()
        if (OobActionCodec.actionNameForStep(step) != OobActionCodec.ACTION_INPUT_TEXT) {
            return emptyList()
        }
        val output = linkedSetOf<String>()
        INPUT_TEXT_ARG_KEYS.forEach { key ->
            output += "$.execution.steps[$stepIndex].args.$key"
        }
        actionBindingsForStep(spec, stepIndex).forEach(output::add)
        return output.toList()
    }

    private fun semanticBindingsForName(
        name: String,
        steps: List<Map<String, Any?>>,
    ): List<String> {
        val normalizedName = normalizeBindingName(name)
        if (normalizedName.isEmpty()) return emptyList()
        val allowedLeafNames = semanticLeafAliases(normalizedName)
        val output = linkedSetOf<String>()
        steps.forEachIndexed { index, step ->
            collectStepArgBindings(
                output = output,
                stepIndex = index,
                value = OobActionCodec.argsForStep(step),
                path = emptyList(),
                allowedLeafNames = allowedLeafNames,
            )
        }
        return output.toList()
    }

    private fun collectStepArgBindings(
        output: MutableSet<String>,
        stepIndex: Int,
        value: Any?,
        path: List<String>,
        allowedLeafNames: Set<String>,
    ) {
        when (value) {
            is Map<*, *> -> value.forEach { (rawKey, child) ->
                val key = rawKey?.toString()?.trim().orEmpty()
                if (key.isEmpty()) return@forEach
                collectStepArgBindings(
                    output = output,
                    stepIndex = stepIndex,
                    value = child,
                    path = path + key,
                    allowedLeafNames = allowedLeafNames,
                )
            }
            is Iterable<*> -> value.forEachIndexed { index, child ->
                if (index >= MAX_BINDING_LIST_SCAN) return@forEachIndexed
                val key = path.lastOrNull()?.let { "$it[$index]" } ?: "item[$index]"
                collectStepArgBindings(
                    output = output,
                    stepIndex = stepIndex,
                    value = child,
                    path = path.dropLast(1) + key,
                    allowedLeafNames = allowedLeafNames,
                )
            }
            is Array<*> -> value.forEachIndexed { index, child ->
                if (index >= MAX_BINDING_LIST_SCAN) return@forEachIndexed
                val key = path.lastOrNull()?.let { "$it[$index]" } ?: "item[$index]"
                collectStepArgBindings(
                    output = output,
                    stepIndex = stepIndex,
                    value = child,
                    path = path.dropLast(1) + key,
                    allowedLeafNames = allowedLeafNames,
                )
            }
            null -> return
            else -> {
                if (path.isEmpty()) return
                val leaf = normalizeBindingName(path.last().substringBefore("["))
                if (leaf !in allowedLeafNames || path.any(::isBlockedBindingPathPart)) return
                val suffix = jsonPathSuffix(path) ?: return
                output += "$.execution.steps[$stepIndex].args.$suffix"
            }
        }
    }

    private fun semanticLeafAliases(normalizedName: String): Set<String> {
        val aliases = linkedSetOf(normalizedName)
        when (normalizedName) {
            "search_query" -> aliases += listOf("query", "keyword", "search_term")
            "keyword", "search_term" -> aliases += listOf("query", "keyword", "search_query")
            "message_text" -> aliases += listOf("message")
            "phone_number" -> aliases += listOf("phone", "phone_number", "number")
            "contact_name" -> aliases += listOf("name", "contact_name")
            "target_url" -> aliases += listOf("url", "target_url", "link")
        }
        return aliases
    }

    private fun normalizeBindingName(value: String): String =
        value.trim()
            .replace(Regex("""([a-z0-9])([A-Z])"""), "$1_$2")
            .replace(Regex("""[^A-Za-z0-9]+"""), "_")
            .trim('_')
            .lowercase()

    private fun isBlockedBindingPathPart(rawPart: String): Boolean {
        val key = normalizeBindingName(rawPart.substringBefore("["))
        return key in BLOCKED_BINDING_KEYS
    }

    private fun isPublicParameterName(name: String): Boolean =
        normalizeBindingName(name) !in INTERNAL_PARAMETER_NAMES

    private fun jsonPathSuffix(path: List<String>): String? {
        if (path.isEmpty()) return null
        val parts = mutableListOf<String>()
        for (part in path) {
            val match = BINDING_PATH_PART_REGEX.matchEntire(part) ?: return null
            val key = match.groupValues[1]
            val index = match.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }
            parts += if (index == null) key else "$key[$index]"
        }
        val suffix = parts.joinToString(".")
        return suffix.takeIf { it.isNotBlank() }
    }

    private fun actionBindingsForStep(
        spec: Map<String, Any?>,
        stepIndex: Int,
    ): List<String> {
        val actions = OobFunctionJson.listArg(spec["actions"])
        val action = OobFunctionJson.mapArg(actions.getOrNull(stepIndex))
        if (action.isEmpty()) return emptyList()
        val actionType = OobFunctionJson.firstNonBlank(action["tool"])
        if (OobActionCodec.canonicalActionForName(actionType) != OobActionCodec.ACTION_INPUT_TEXT) {
            return emptyList()
        }
        val output = linkedSetOf<String>()
        val args = OobFunctionJson.mapArg(action["args"])
        if (args.isNotEmpty()) {
            INPUT_TEXT_ARG_KEYS.forEach { key -> output += "$.actions[$stepIndex].args.$key" }
        }
        return output.toList()
    }

    private fun stepIndexFromInputTextName(
        name: String,
        steps: List<Map<String, Any?>>,
    ): Int? {
        val match = INPUT_TEXT_NAME_REGEX.matchEntire(name.trim()) ?: return null
        val explicitStepNumber = match.groupValues.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.toIntOrNull()
        if (explicitStepNumber != null) return explicitStepNumber.minus(1).takeIf { it >= 0 }
        return steps.indexOfFirst {
            OobActionCodec.actionNameForStep(it) == OobActionCodec.ACTION_INPUT_TEXT
        }.takeIf { it >= 0 }
    }

    private fun bindingList(value: Any?): List<String> =
        OobFunctionJson.listArg(value)
            .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            .filter(::isCanonicalBindingPath)

    private fun isCanonicalBindingPath(path: String): Boolean =
        CANONICAL_EXECUTION_BINDING_REGEX.matches(path) ||
            CANONICAL_ACTION_BINDING_REGEX.matches(path)

    private fun mutableMapArg(value: Any?): LinkedHashMap<String, Any?> =
        OobFunctionJson.mutableJsonMap(OobFunctionJson.mapArg(value))

    private fun changeEntry(
        name: String,
        bindings: List<String>,
        source: String,
    ): Map<String, Any?> = linkedMapOf(
        "parameter" to name,
        "source" to source,
        "bindings" to bindings,
    )

    private val INPUT_TEXT_NAME_REGEX = Regex("""input[_-]?text(?:[_-]?(\d+))?""", RegexOption.IGNORE_CASE)
    private val INPUT_TEXT_ARG_KEYS =
        OobCanonicalActionSchema.argNames(OobActionCodec.ACTION_INPUT_TEXT)
            .filter { it == OobCanonicalActionSchema.ARG_TEXT }
    private val BINDING_PATH_PART_REGEX = Regex("""^([A-Za-z0-9_]+)(?:\[(\d+)])?$""")
    private val CANONICAL_EXECUTION_BINDING_REGEX =
        Regex("""^\$\.execution\.steps\[\d+]\.args\.[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+|\[\d+])*$""")
    private val CANONICAL_ACTION_BINDING_REGEX =
        Regex("""^\$\.actions\[\d+]\.args\.[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+|\[\d+])*$""")
    private const val MAX_BINDING_LIST_SCAN = 8
    private val BLOCKED_BINDING_KEYS = setOf(
        "x",
        "y",
        "left",
        "top",
        "right",
        "bottom",
        "width",
        "height",
        "center_x",
        "center_y",
        "bounds",
        "rect",
        "position",
        "coordinate",
        "coordinates",
    )
    private val INTERNAL_PARAMETER_NAMES = setOf(
        "package_name",
        "package",
        "target_description",
        "target",
        "selector",
        "node_id",
        "node_resource_id",
        "element_index",
        "scrollable_index",
        "x",
        "y",
        "x1",
        "y1",
        "x2",
        "y2",
        "bounds",
        "clear",
        "duration_ms",
    )
}
