package cn.com.omnimind.bot.function

/**
 * Normalizes explicit Function argument bindings at the storage boundary.
 *
 * Agents own parameter extraction. This normalizer does not infer bindings from
 * parameter names or step shapes; unbound parameters simply stay unbound and are
 * not exposed as callable runtime arguments.
 */
object FunctionParameterBindingNormalizer {
    fun normalize(functionSpec: Map<String, Any?>): Map<String, Any?> {
        val spec = FunctionJson.mutableJsonMap(FunctionJson.sanitizeMap(functionSpec))
        val parameterBindings = linkedMapOf<String, LinkedHashSet<String>>()

        collectExistingBindingTables(spec, parameterBindings)
        normalizeLegacyParameters(spec, parameterBindings)
        normalizeSchemaParameters(spec, parameterBindings)
        syncMetadataBindings(spec, parameterBindings)
        return spec
    }

    private fun collectExistingBindingTables(
        spec: Map<String, Any?>,
        parameterBindings: MutableMap<String, LinkedHashSet<String>>,
    ) {
        val metadata = mutableMapArg(spec["metadata"])
        val entries = FunctionJson.listArg(spec["x_oob_parameter_bindings"]) +
            FunctionJson.listArg(spec["parameter_bindings"]) +
            FunctionJson.listArg(metadata["oob_parameter_bindings"])
        entries.forEach { raw ->
            val entry = FunctionJson.mapArg(raw)
            val name = FunctionJson.firstNonBlank(entry["name"], entry["parameter"])
            if (name.isEmpty() || !isPublicParameterName(name)) return@forEach
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
        parameterBindings: MutableMap<String, LinkedHashSet<String>>,
    ) {
        val rawParameters = spec["parameters"] as? List<*> ?: return
        val normalized = rawParameters.map { raw ->
            val parameter = mutableMapArg(raw)
            val name = parameter["name"]?.toString()?.trim().orEmpty()
            if (name.isEmpty()) return@map parameter
            if (!isPublicParameterName(name)) return@map null

            val bindings = bindingList(parameter["bindings"])
            if (bindings.isNotEmpty()) {
                parameter["bindings"] = bindings
                parameterBindings.getOrPut(name) { linkedSetOf() }.addAll(bindings)
            }
            parameter
        }.filterNotNull()
        spec["parameters"] = normalized
    }

    private fun normalizeSchemaParameters(
        spec: MutableMap<String, Any?>,
        parameterBindings: MutableMap<String, LinkedHashSet<String>>,
    ) {
        val schema = spec["parameters"] as? MutableMap<*, *> ?: return
        val properties = schema["properties"] as? Map<*, *> ?: return
        val normalizedProperties = linkedMapOf<String, Any?>()
        properties.forEach { (rawName, rawProperty) ->
            val name = rawName?.toString()?.trim().orEmpty()
            if (name.isEmpty() || !isPublicParameterName(name)) return@forEach
            val property = mutableMapArg(rawProperty)
            val bindings = (
                bindingList(property["x_oob_bindings"]) +
                    bindingList(property["x-oob-bindings"]) +
                    bindingList(property["bindings"])
                ).distinct()
            if (bindings.isNotEmpty()) {
                property["x_oob_bindings"] = bindings
                parameterBindings.getOrPut(name) { linkedSetOf() }.addAll(bindings)
            }
            normalizedProperties[name] = property
        }
        @Suppress("UNCHECKED_CAST")
        (schema as MutableMap<String, Any?>)["properties"] = normalizedProperties
        val normalizedRequired = FunctionJson.listArg(schema["required"])
            .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            .filter(::isPublicParameterName)
            .filter { it in normalizedProperties.keys }
        schema["required"] = normalizedRequired
    }

    private fun syncMetadataBindings(
        spec: MutableMap<String, Any?>,
        parameterBindings: Map<String, Set<String>>,
    ) {
        if (parameterBindings.isEmpty()) return
        val metadata = mutableMapArg(spec["metadata"])
        val byName = linkedMapOf<String, LinkedHashSet<String>>()
        FunctionJson.listArg(metadata["oob_parameter_bindings"]).forEach { raw ->
            val entry = FunctionJson.mapArg(raw)
            val name = FunctionJson.firstNonBlank(entry["name"], entry["parameter"])
            if (name.isEmpty() || !isPublicParameterName(name)) return@forEach
            val bindings = (
                bindingList(entry["bindings"]) +
                    listOfNotNull(entry["binding"]?.toString()?.trim()?.takeIf(String::isNotEmpty))
                ).distinct()
            if (bindings.isNotEmpty()) {
                byName.getOrPut(name) { linkedSetOf() }.addAll(bindings)
            }
        }
        parameterBindings.forEach { (name, bindings) ->
            if (isPublicParameterName(name) && bindings.isNotEmpty()) {
                byName.getOrPut(name) { linkedSetOf() }.addAll(bindings)
            }
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
            spec["metadata"] = metadata
        }
    }

    private fun bindingList(value: Any?): List<String> =
        FunctionJson.listArg(value)
            .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            .filter(::isCanonicalBindingPath)

    private fun isCanonicalBindingPath(path: String): Boolean =
        CANONICAL_EXECUTION_BINDING_REGEX.matches(path) ||
            CANONICAL_ACTION_BINDING_REGEX.matches(path)

    private fun mutableMapArg(value: Any?): LinkedHashMap<String, Any?> =
        FunctionJson.mutableJsonMap(FunctionJson.mapArg(value))

    private fun isPublicParameterName(name: String): Boolean =
        normalizeBindingName(name) !in INTERNAL_PARAMETER_NAMES

    private fun normalizeBindingName(value: String): String =
        value.trim()
            .replace(Regex("""([a-z0-9])([A-Z])"""), "$1_$2")
            .replace(Regex("""[^A-Za-z0-9]+"""), "_")
            .trim('_')
            .lowercase()

    private val CANONICAL_EXECUTION_BINDING_REGEX =
        Regex("""^\$\.execution\.steps\[\d+]\.args\.[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+|\[\d+])*$""")
    private val CANONICAL_ACTION_BINDING_REGEX =
        Regex("""^\$\.actions\[\d+]\.args\.[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+|\[\d+])*$""")
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
