package cn.com.omnimind.baselib.runlog

import kotlin.math.round

object CanonicalActionConverter {
    enum class CoordinateSpace {
        RELATIVE_0_1000,
        SCREEN_ABSOLUTE_PX,
    }

    data class DisplaySize(
        val width: Double,
        val height: Double,
    )

    fun convert(
        tool: String,
        args: Map<String, Any?>,
        coordinateSpace: CoordinateSpace = CoordinateSpace.RELATIVE_0_1000,
        displaySize: DisplaySize? = null,
        replayableOnly: Boolean = false,
        persistedOnly: Boolean = true,
    ): Map<String, Any?> {
        val toolSpec = requireNotNull(OobActionSchema.tool(tool)) {
            "canonical_action_tool_unsupported:$tool"
        }
        require(toolSpec.kind == OobActionSchema.Kind.ACTION) {
            "canonical_action_kind_invalid:${toolSpec.kind.name.lowercase()}:${toolSpec.name}"
        }
        val unknownArgs = args.keys
            .filterNot { key -> toolSpec.args.any { spec -> spec.name == key } }
            .sorted()
        require(unknownArgs.isEmpty()) {
            "canonical_action_unknown_args:${toolSpec.name}:${unknownArgs.joinToString(",")}"
        }
        require(!replayableOnly || toolSpec.replayable) {
            "canonical_action_tool_not_replayable:${toolSpec.name}"
        }
        val selectedSpecs = if (persistedOnly) {
            toolSpec.args.filter(OobActionSchema.ArgSpec::persisted)
        } else {
            toolSpec.args
        }
        val coordinateArgs = selectedSpecs.mapNotNullTo(linkedSetOf()) { spec ->
            spec.name.takeIf(::isCoordinateArg)
        }
        val resolvedDisplay = if (
            coordinateSpace == CoordinateSpace.SCREEN_ABSOLUTE_PX &&
            coordinateArgs.any(args::containsKey)
        ) {
            requireNotNull(displaySize) {
                "canonical_action_display_required:${toolSpec.name}"
            }
        } else {
            null
        }
        val canonicalArgs = linkedMapOf<String, Any?>()
        selectedSpecs.forEach { spec ->
            val raw = args[spec.name] ?: return@forEach
            val converted = if (resolvedDisplay != null && isCoordinateArg(spec.name)) {
                val dimension = if (spec.name in X_COORDINATE_ARGS) {
                    resolvedDisplay.width
                } else {
                    resolvedDisplay.height
                }
                canonicalNumber(number(raw, spec.name) / dimension * 1000.0)
            } else {
                canonicalValue(raw, spec)
            }
            validateRange(converted, spec)
            canonicalArgs[spec.name] = converted
        }
        val missing = selectedSpecs
            .filter { it.required && !canonicalArgs.containsKey(it.name) }
            .map(OobActionSchema.ArgSpec::name)
        require(missing.isEmpty()) {
            "canonical_action_required_args_missing:${toolSpec.name}:${missing.joinToString(",")}"
        }
        return linkedMapOf(
            OobActionSchema.ROOT_TOOL to toolSpec.name,
            OobActionSchema.ROOT_ARGS to canonicalArgs,
        )
    }

    fun toScreenPixels(
        tool: String,
        args: Map<String, Any?>,
        displaySize: DisplaySize,
    ): Map<String, Any?> {
        require(displaySize.width > 0.0 && displaySize.height > 0.0) {
            "canonical_action_display_invalid"
        }
        val toolSpec = requireNotNull(OobActionSchema.tool(tool)) {
            "canonical_action_tool_unsupported:$tool"
        }
        require(toolSpec.kind == OobActionSchema.Kind.ACTION) {
            "canonical_action_kind_invalid:${toolSpec.kind.name.lowercase()}:${toolSpec.name}"
        }
        val screenArgs = linkedMapOf<String, Any?>().apply { putAll(args) }
        toolSpec.args.filter { isCoordinateArg(it.name) }.forEach { spec ->
            val raw = args[spec.name]
            if (raw == null) {
                require(!spec.required) {
                    "canonical_action_required_args_missing:${toolSpec.name}:${spec.name}"
                }
                return@forEach
            }
            val relative = number(raw, spec.name)
            require(relative in 0.0..1000.0) {
                "canonical_action_arg_range_invalid:${spec.name}"
            }
            val dimension = if (spec.name in X_COORDINATE_ARGS) {
                displaySize.width
            } else {
                displaySize.height
            }
            screenArgs[spec.name] = canonicalNumber(relative / 1000.0 * dimension)
        }
        return screenArgs
    }

    private fun canonicalValue(value: Any, spec: OobActionSchema.ArgSpec): Any = when (spec.type) {
        OobActionSchema.Type.STRING -> requireString(value, spec.name)
        OobActionSchema.Type.NUMBER -> canonicalNumber(number(value, spec.name))
        OobActionSchema.Type.INTEGER -> {
            val number = number(value, spec.name)
            require(number % 1.0 == 0.0) { "canonical_action_arg_type_invalid:${spec.name}" }
            number.toLong()
        }
        OobActionSchema.Type.BOOLEAN -> value as? Boolean
            ?: error("canonical_action_arg_type_invalid:${spec.name}")
        OobActionSchema.Type.OBJECT -> canonicalMap(value, spec.name)
        OobActionSchema.Type.STRING_ARRAY -> (value as? Iterable<*>)
            ?.map { requireString(it, spec.name) }
            ?: error("canonical_action_arg_type_invalid:${spec.name}")
    }.also { converted ->
        require(spec.enumValues.isEmpty() || converted in spec.enumValues) {
            "canonical_action_arg_enum_invalid:${spec.name}"
        }
    }

    private fun canonicalMap(value: Any, name: String): Map<String, Any?> {
        val map = value as? Map<*, *> ?: error("canonical_action_arg_type_invalid:$name")
        return linkedMapOf<String, Any?>().apply {
            map.forEach { (key, item) ->
                val textKey = key as? String ?: error("canonical_action_arg_type_invalid:$name")
                put(textKey, canonicalJsonValue(item, name))
            }
        }
    }

    private fun canonicalJsonValue(value: Any?, name: String): Any? = when (value) {
        null, is String, is Boolean -> value
        is Number -> canonicalNumber(number(value, name))
        is Map<*, *> -> canonicalMap(value, name)
        is Iterable<*> -> value.map { canonicalJsonValue(it, name) }
        is Array<*> -> value.map { canonicalJsonValue(it, name) }
        else -> error("canonical_action_arg_type_invalid:$name")
    }

    private fun validateRange(value: Any, spec: OobActionSchema.ArgSpec) {
        val number = value as? Number ?: return
        spec.minimum?.toDouble()?.let { minimum ->
            require(number.toDouble() >= minimum) { "canonical_action_arg_range_invalid:${spec.name}" }
        }
        spec.maximum?.toDouble()?.let { maximum ->
            require(number.toDouble() <= maximum) { "canonical_action_arg_range_invalid:${spec.name}" }
        }
    }

    private fun number(value: Any?, name: String): Double = numberOrNull(value)
        ?: error("canonical_action_arg_type_invalid:$name")

    private fun numberOrNull(value: Any?): Double? = (value as? Number)
        ?.toDouble()
        ?.takeIf(Double::isFinite)

    private fun requireString(value: Any?, name: String): String = value as? String
        ?: error("canonical_action_arg_type_invalid:$name")

    private fun canonicalNumber(value: Double): Number {
        require(value.isFinite()) { "canonical_action_number_invalid" }
        val rounded = round(value * 1000.0) / 1000.0
        return if (rounded % 1.0 == 0.0) rounded.toLong() else rounded
    }

    private fun isCoordinateArg(name: String): Boolean =
        name in X_COORDINATE_ARGS || name in Y_COORDINATE_ARGS

    private val X_COORDINATE_ARGS = setOf("x", "x1", "x2")
    private val Y_COORDINATE_ARGS = setOf("y", "y1", "y2")
}
