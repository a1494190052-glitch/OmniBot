package cn.com.omnimind.bot.runlog

/**
 * Deterministic parameter inference for RunLog -> reusable Function conversion.
 *
 * Keep this small: by default only user-entered input_text content becomes a
 * public Function argument. Other action internals stay replay evidence unless
 * an enhancer explicitly rewrites the Function spec.
 */
object RunLogReusableFunctionParameterizer {
    data class Result(
        val parameters: Map<String, Any?>,
        val actions: List<Map<String, Any?>>,
        val parameterBindings: List<Map<String, Any?>>,
        val legacyParameters: List<Map<String, Any?>>,
    )

    fun parameterize(steps: List<Map<String, Any?>>): Result {
        val usedNames = mutableSetOf<String>()
        val properties = linkedMapOf<String, Any?>()
        val parameterBindings = mutableListOf<Map<String, Any?>>()
        val legacyParameters = mutableListOf<Map<String, Any?>>()
        val actions = steps.mapIndexed { index, step ->
            val action = actionFromStep(step)
            val tool = action["tool"]?.toString().orEmpty()
            val args = OobActionCodec.argsForStep(step).toMutableMap()
            if (tool == OobActionCodec.ACTION_INPUT_TEXT) {
                val textKey = INPUT_TEXT_ARG_KEYS.firstOrNull { key ->
                    args[key]?.toString()?.trim()?.isNotEmpty() == true
                }
                if (textKey != null) {
                    val parameterName = uniqueName(parameterNameForInput(step), usedNames)
                    usedNames += parameterName
                    val defaultValue = args[textKey]?.toString().orEmpty()
                    val bindings = listOf(
                        "$.execution.steps[$index].args.$textKey",
                        "$.actions[$index].args.$textKey",
                    )
                    properties[parameterName] = linkedMapOf<String, Any?>(
                        "type" to "string",
                        "description" to "Text for step ${index + 1}: ${step["title"] ?: tool}",
                        "default" to defaultValue,
                        "x_oob_bindings" to bindings,
                    )
                    parameterBindings += linkedMapOf(
                        "parameter" to parameterName,
                        "step_index" to index,
                        "arg_path" to textKey,
                        "bindings" to bindings,
                    )
                    legacyParameters += linkedMapOf(
                        "name" to parameterName,
                        "type" to "string",
                        "required" to false,
                        "default" to defaultValue,
                        "description" to "Text for step ${index + 1}: ${step["title"] ?: tool}",
                        "bindings" to bindings,
                    )
                    args[textKey] = "\${$parameterName}"
                }
            }
            action + ("args" to args)
        }

        return Result(
            parameters = linkedMapOf(
                "type" to "object",
                "properties" to properties,
                "required" to emptyList<String>(),
                "additionalProperties" to false,
            ),
            actions = actions,
            parameterBindings = parameterBindings,
            legacyParameters = legacyParameters,
        )
    }

    private fun actionFromStep(step: Map<String, Any?>): Map<String, Any?> {
        val tool = OobActionCodec.actionNameForStep(step)
        return linkedMapOf<String, Any?>(
            "tool" to tool,
            "args" to OobActionCodec.argsForStep(step),
        ).apply {
            step["title"]?.toString()?.takeIf { it.isNotBlank() }?.let {
                put("description", it)
            }
        }
    }

    private fun parameterNameForInput(step: Map<String, Any?>): String {
        val title = listOf(step["title"], step["summary"])
            .map { it?.toString().orEmpty().trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
            .take(30)
            .replace(Regex("[^A-Za-z0-9_]+"), "_")
            .trim('_')
            .lowercase()
        return title.takeIf { it.isNotBlank() } ?: "input_text"
    }

    private fun uniqueName(base: String, used: Set<String>): String {
        if (base !in used) return base
        var index = 2
        while ("${base}_$index" in used) index += 1
        return "${base}_$index"
    }

    private val INPUT_TEXT_ARG_KEYS = listOf("text")
}
