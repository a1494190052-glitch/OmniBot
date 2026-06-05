package cn.com.omnimind.bot.runlog

import cn.com.omnimind.baselib.runlog.OobCanonicalActionSchema
import cn.com.omnimind.bot.runlog.OobActionCodec.firstNonBlank
import cn.com.omnimind.bot.runlog.OobActionCodec.listArg
import cn.com.omnimind.bot.runlog.OobActionCodec.mapArg

/**
 * Builds reusable Function parameters and canonical action specs from compiled replay steps.
 *
 * RunLogReusableFunctionCompiler owns card-to-step conversion and top-level
 * Function assembly. This object owns deterministic input_text parameter
 * inference and parameter binding metadata.
 */
object RunLogReusableFunctionParameterizer {
    data class Result(
        val legacyParameters: List<Map<String, Any?>>,
        val parameters: Map<String, Any?>,
        val actions: List<Map<String, Any?>>,
        val parameterBindings: List<Map<String, Any?>>,
    )

    fun parameterize(steps: List<Map<String, Any?>>): Result {
        val legacyParameters = inferParameters(steps)
        return Result(
            legacyParameters = legacyParameters,
            parameters = canonicalParameterSchema(legacyParameters),
            actions = canonicalActionsForSteps(steps, legacyParameters),
            parameterBindings = parameterBindingsMetadata(legacyParameters),
        )
    }

    private fun inferParameters(steps: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val parameters = mutableListOf<Map<String, Any?>>()
        val usedNames = mutableSetOf<String>()
        steps.forEachIndexed { index, step ->
            val tool = OobActionCodec.actionNameForStep(step)
            if (tool !in INPUT_TEXT_ACTIONS) return@forEachIndexed
            val args = OobActionCodec.argsForStep(step)
            val inputKey = INPUT_TEXT_ARG_KEYS.firstOrNull { key ->
                args[key]?.toString()?.trim()?.isNotEmpty() == true
            } ?: return@forEachIndexed
            val defaultValue = args[inputKey]?.toString()?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
            val baseName = parameterNameForInputStep(step, index)
            val name = uniqueParameterName(baseName, usedNames)
            usedNames += name
            parameters += linkedMapOf(
                "name" to name,
                "type" to "string",
                "required" to false,
                "default" to defaultValue,
                "description" to "Text to input for step ${index + 1}: ${step["title"] ?: tool}",
                "source" to linkedMapOf(
                    "kind" to "run_log_argument",
                    "step_id" to step["id"],
                    "tool" to tool,
                    "arg_key" to inputKey,
                ),
                "bindings" to listOf("$.execution.steps[$index].args.$inputKey"),
            )
        }
        return parameters
    }

    private fun canonicalParameterSchema(
        legacyParameters: List<Map<String, Any?>>,
    ): Map<String, Any?> {
        val properties = linkedMapOf<String, Any?>()
        val required = mutableListOf<String>()
        legacyParameters.forEach { parameter ->
            val name = firstNonBlank(parameter["name"])
            if (name.isBlank()) return@forEach
            val bindings = listArg(parameter["bindings"]) +
                listArg(parameter["bindings"]).mapNotNull(::actionBindingForExecutionBinding)
            val property = linkedMapOf<String, Any?>(
                "type" to jsonSchemaType(firstNonBlank(parameter["type"]).ifBlank { "string" }),
            )
            firstNonBlank(parameter["description"]).takeIf { it.isNotBlank() }?.let {
                property["description"] = it
            }
            if (parameter.containsKey("default")) {
                property["default"] = parameter["default"]
            }
            listArg(parameter["enum"]).ifEmpty { listArg(parameter["values"]) }
                .takeIf { it.isNotEmpty() }
                ?.let { property["enum"] = it }
            if (bindings.isNotEmpty()) {
                property["x_oob_bindings"] = bindings.distinct()
            }
            properties[name] = property
            if (isTruthy(parameter["required"])) {
                required += name
            }
        }
        return linkedMapOf(
            "type" to "object",
            "properties" to properties,
            "required" to required,
            "additionalProperties" to false,
        )
    }

    private fun canonicalActionsForSteps(
        steps: List<Map<String, Any?>>,
        legacyParameters: List<Map<String, Any?>>,
    ): List<Map<String, Any?>> {
        val templates = parameterTemplatesByStepArg(legacyParameters)
        return steps.mapIndexedNotNull { index, step ->
            canonicalActionForStep(step, index, templates)
        }
    }

    private fun parameterBindingsMetadata(
        legacyParameters: List<Map<String, Any?>>,
    ): List<Map<String, Any?>> {
        return legacyParameters.mapNotNull { parameter ->
            val nameValue = firstNonBlank(parameter["name"])
            val bindings = parameter["bindings"] as? List<*> ?: emptyList<Any?>()
            if (nameValue.isBlank() || bindings.isEmpty()) return@mapNotNull null
            linkedMapOf(
                "name" to nameValue,
                "bindings" to bindings,
            )
        }
    }

    private fun parameterTemplatesByStepArg(
        legacyParameters: List<Map<String, Any?>>,
    ): Map<Pair<Int, String>, String> {
        val output = linkedMapOf<Pair<Int, String>, String>()
        legacyParameters.forEach { parameter ->
            val name = firstNonBlank(parameter["name"])
            if (name.isBlank()) return@forEach
            listArg(parameter["bindings"]).forEach { rawBinding ->
                val binding = rawBinding?.toString()?.trim().orEmpty()
                val match = EXECUTION_ARG_BINDING_REGEX.matchEntire(binding) ?: return@forEach
                val stepIndex = match.groupValues[1].toIntOrNull() ?: return@forEach
                val argKey = match.groupValues[2]
                output[stepIndex to argKey] = "\${$name}"
            }
        }
        return output
    }

    private fun actionBindingForExecutionBinding(rawBinding: Any?): String? {
        val binding = rawBinding?.toString()?.trim().orEmpty()
        val match = EXECUTION_ARG_BINDING_REGEX.matchEntire(binding) ?: return null
        val stepIndex = match.groupValues[1].toIntOrNull() ?: return null
        val argKey = match.groupValues[2]
        return "$.actions[$stepIndex].args.$argKey"
    }

    private fun canonicalActionForStep(
        step: Map<String, Any?>,
        index: Int,
        parameterTemplates: Map<Pair<Int, String>, String>,
    ): Map<String, Any?>? {
        val args = OobActionCodec.argsForStep(step)
        val action = OobActionCodec.actionNameForStep(step)
        val description = firstNonBlank(step["title"], step["summary"]).takeIf { it.isNotBlank() }
        return when {
            OobActionCodec.canonicalActionForName(action) != null -> {
                val canonicalAction = OobActionCodec.canonicalActionForName(action) ?: action
                nullableMap(
                    "tool" to canonicalAction,
                    "args" to canonicalArgsForAction(
                        tool = canonicalAction,
                        args = args,
                        stepIndex = index,
                        parameterTemplates = parameterTemplates,
                    ),
                    "description" to description,
                )
            }
            RunLogReplayPolicy.isOmniflowToolCallTool(action) -> {
                val functionId = firstNonBlank(
                    args["function_id"],
                )
                nullableMap(
                    "tool" to RunLogReplayPolicy.TOOL_CALL_TOOL,
                    "args" to nullableMap(
                        OobCanonicalActionSchema.ARG_FUNCTION_ID to functionId,
                        "arguments" to mapArg(args["arguments"]),
                    ),
                    "description" to description,
                )
            }
            RunLogReplayPolicy.isOmniflowGraphTool(action) -> nullableMap(
                "tool" to RunLogReplayPolicy.TOOL_CLICK_NODE,
                "args" to nullableMap(
                    "node_id" to firstNonBlank(args["node_id"]),
                    "path" to listArg(args["path"]).takeIf { it.isNotEmpty() },
                ),
                "description" to description,
            )
            action == RunLogReplayPolicy.TOOL_WAIT -> nullableMap(
                "tool" to RunLogReplayPolicy.TOOL_WAIT,
                "args" to nullableMap(
                    "time_ms" to firstPresent(args["time_ms"]),
                    "time_s" to firstPresent(args["time_s"], args["seconds"]),
                    "selector" to firstNonBlank(args["selector"]).takeIf { it.isNotBlank() },
                    "url" to firstNonBlank(args["url"]).takeIf { it.isNotBlank() },
                ).takeIf { it.isNotEmpty() },
                "description" to description,
            )
            else -> nullableMap(
                "tool" to RunLogReplayPolicy.TOOL_EXTERNAL_TOOL,
                "args" to nullableMap(
                    "tool_name" to firstNonBlank(step["tool"], action),
                    "arguments" to args,
                ),
                "description" to description,
            )
        }
    }

    private fun canonicalArgsForAction(
        tool: String,
        args: Map<String, Any?>,
        stepIndex: Int,
        parameterTemplates: Map<Pair<Int, String>, String>,
    ): Map<String, Any?> {
        val output = linkedMapOf<String, Any?>()
        OobCanonicalActionSchema.argNames(tool).forEach { key ->
            val value = when {
                tool == OobActionCodec.ACTION_INPUT_TEXT && key == OobCanonicalActionSchema.ARG_TEXT ->
                    parameterTemplates[stepIndex to key] ?: firstPresent(args[key])
                else -> firstPresent(args[key])
            }
            if (value != null) {
                output[key] = value
            }
        }
        return output
    }

    private fun jsonSchemaType(type: String): String =
        when (type.lowercase()) {
            "int", "integer" -> "integer"
            "number", "float", "double" -> "number"
            "bool", "boolean" -> "boolean"
            "array", "object" -> type.lowercase()
            else -> "string"
        }

    private fun isTruthy(value: Any?): Boolean =
        when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.trim().equals("true", ignoreCase = true)
            else -> false
        }

    private fun parameterNameForInputStep(step: Map<String, Any?>, index: Int): String {
        val rawLabel = firstNonBlank(
            step["parameter_name"],
            step["input_name"],
            step["title"],
        )
        val label = rawLabel
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(32)
        return when {
            label.endsWith("_text") -> label
            label.isNotBlank() -> "${label}_text"
            index == 0 -> DEFAULT_INPUT_PARAMETER_NAME
            else -> "${DEFAULT_INPUT_PARAMETER_NAME}_${index + 1}"
        }
    }

    private fun uniqueParameterName(baseName: String, usedNames: Set<String>): String {
        val normalized = baseName
            .ifBlank { DEFAULT_INPUT_PARAMETER_NAME }
            .replace(Regex("_+"), "_")
            .trim('_')
            .ifBlank { DEFAULT_INPUT_PARAMETER_NAME }
        if (normalized !in usedNames) return normalized
        var suffix = 2
        while ("${normalized}_$suffix" in usedNames) {
            suffix += 1
        }
        return "${normalized}_$suffix"
    }

    private fun firstPresent(vararg values: Any?): Any? {
        for (value in values) {
            if (value == null) continue
            if (value is String && value.trim().isEmpty()) continue
            return value
        }
        return null
    }

    private fun nullableMap(vararg pairs: Pair<String, Any?>): Map<String, Any?> {
        return linkedMapOf<String, Any?>().apply {
            pairs.forEach { (key, value) ->
                if (value != null) put(key, value)
            }
        }
    }

    private val INPUT_TEXT_ACTIONS = setOf(OobActionCodec.ACTION_INPUT_TEXT)
    private val INPUT_TEXT_ARG_KEYS =
        OobCanonicalActionSchema.argNames(OobActionCodec.ACTION_INPUT_TEXT)
            .filter { it == OobCanonicalActionSchema.ARG_TEXT }
    private val EXECUTION_ARG_BINDING_REGEX = Regex("""^\$\.execution\.steps\[(\d+)]\.args\.([A-Za-z0-9_]+)$""")
    private const val DEFAULT_INPUT_PARAMETER_NAME = "input_text"
}
