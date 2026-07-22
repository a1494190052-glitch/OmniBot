package cn.com.omnimind.bot.function

import cn.com.omnimind.baselib.runlog.CanonicalActionConverter

internal object FunctionContract {
    const val SCHEMA_VERSION = "omniflow.function.v2"

    private val topLevelFields = setOf(
        "schema_version",
        "function_id",
        "name",
        "description",
        "input_schema",
        "bindings",
        "steps",
        "checker_rules",
        "agent_visible",
    )
    private val functionIdPattern = Regex("^[A-Za-z][A-Za-z0-9_-]{0,63}$")
    private val sourcePathPattern = Regex(
        "^\\$\\.arguments(?:\\.[A-Za-z_][A-Za-z0-9_]*|\\[[0-9]+])+$"
    )
    private val targetPathPattern = Regex(
        "^\\$\\.steps\\[[0-9]+]\\.action\\.args" +
            "(?:\\.[A-Za-z_][A-Za-z0-9_]*|\\[[0-9]+])+$"
    )

    fun sanitize(value: Map<*, *>): Map<String, Any?> = sanitizeMap(value)

    fun canonical(spec: Map<*, *>): Map<String, Any?> {
        val value = stringMap(spec)
        require(value.keys == topLevelFields) { "function_contract_fields_invalid" }
        require(value["schema_version"] == SCHEMA_VERSION) { "function_schema_version_invalid" }
        val functionId = text(value["function_id"])
        require(functionIdPattern.matches(functionId)) { "function_id_invalid" }
        val name = text(value["name"])
        val description = text(value["description"])
        require(name.isNotEmpty()) { "function_name_required" }
        require(description.isNotEmpty()) { "function_description_required" }
        val inputSchema = canonicalInputSchema(value["input_schema"])
        val bindings = list(value["bindings"]).map { raw ->
            val binding = stringMap(raw)
            require(binding.keys == setOf("source", "target")) { "function_binding_invalid" }
            val source = text(binding["source"])
            val target = text(binding["target"])
            require(sourcePathPattern.matches(source)) { "function_binding_source_invalid" }
            require(targetPathPattern.matches(target)) { "function_binding_target_invalid" }
            linkedMapOf("source" to source, "target" to target)
        }
        val steps = list(value["steps"]).mapIndexed { index, raw ->
            val step = stringMap(raw)
            require(step.keys.all { it in setOf("step_index", "source_state_id", "action") }) {
                "function_step_fields_invalid"
            }
            require(step.keys == setOf("step_index", "source_state_id", "action")) {
                "function_step_fields_invalid"
            }
            require(number(step["step_index"]) == index) { "function_step_index_invalid" }
            val sourceStateId = text(step["source_state_id"])
            require(sourceStateId.isNotEmpty()) { "function_step_source_state_id_required" }
            val rawAction = stringMap(step["action"])
            require(rawAction.keys == setOf("tool", "args")) { "function_action_fields_invalid" }
            val tool = text(rawAction["tool"])
            require(tool.isNotEmpty()) { "function_action_tool_required" }
            val action = CanonicalActionConverter.convert(
                tool = tool,
                args = stringMap(rawAction["args"]),
                replayableOnly = true,
            )
            linkedMapOf<String, Any?>(
                "step_index" to index,
                "source_state_id" to sourceStateId,
                "action" to action,
            )
        }
        require(steps.isNotEmpty()) { "function_steps_required" }
        val checkerRules = list(value["checker_rules"]).map { sanitizeValue(it) }
        val agentVisible = value["agent_visible"] as? Boolean
            ?: error("function_agent_visible_required")
        return linkedMapOf(
            "schema_version" to SCHEMA_VERSION,
            "function_id" to functionId,
            "name" to name,
            "description" to description,
            "input_schema" to inputSchema,
            "bindings" to bindings,
            "steps" to steps,
            "checker_rules" to checkerRules,
            "agent_visible" to agentVisible,
        )
    }

    private fun canonicalInputSchema(raw: Any?): Map<String, Any?> {
        val schema = stringMap(raw)
        require(schema.keys == setOf("type", "properties", "required", "additionalProperties")) {
            "function_input_schema_fields_invalid"
        }
        require(schema["type"] == "object") { "function_input_schema_type_invalid" }
        require(schema["additionalProperties"] == false) {
            "function_input_schema_additional_properties_invalid"
        }
        val properties = sanitizeMap(stringMap(schema["properties"]))
        val required = list(schema["required"]).map(::text)
        require(required.all(properties::containsKey)) { "function_input_schema_required_invalid" }
        return linkedMapOf(
            "type" to "object",
            "properties" to properties,
            "required" to required.distinct(),
            "additionalProperties" to false,
        )
    }

    private fun sanitizeMap(value: Map<*, *>): LinkedHashMap<String, Any?> =
        linkedMapOf<String, Any?>().apply {
            value.forEach { (rawKey, item) ->
                val key = rawKey?.toString() ?: return@forEach
                put(key, sanitizeValue(item))
            }
        }

    private fun sanitizeValue(value: Any?): Any? = when (value) {
        is Double -> if (value.isFinite() && value % 1.0 == 0.0) value.toLong() else value
        is Float -> if (value.isFinite() && value % 1f == 0f) value.toLong() else value
        is Map<*, *> -> sanitizeMap(value)
        is Iterable<*> -> value.map(::sanitizeValue)
        is Array<*> -> value.map(::sanitizeValue)
        else -> value
    }

    private fun stringMap(value: Any?): Map<String, Any?> =
        (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }.orEmpty()

    private fun list(value: Any?): List<Any?> = value as? List<*> ?: emptyList()

    private fun text(value: Any?): String = value?.toString()?.trim().orEmpty()

    private fun number(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }
}
