package cn.com.omnimind.bot.function

import cn.com.omnimind.baselib.runlog.OobActionSchema
import cn.com.omnimind.bot.agent.AgentToolNames
import cn.com.omnimind.bot.runlog.actionNameForStep
import cn.com.omnimind.bot.runlog.resolveActionName

object FunctionSchema {
    const val EXECUTOR_FUNCTION: String = "function"
    const val LEGACY_EXECUTOR_FUNCTION: String = "omniflow"
    const val EXECUTOR_AGENT: String = "agent"
    const val EXECUTOR_TOOL: String = "tool"
    const val TOOL_EXTERNAL_TOOL: String = "external_tool"

    private val capturedNoiseTools = setOf(
        "notification_send",
        "calendar_event_create",
        "skills_loaded",
        "status_update",
        "assistant_response",
        OobActionSchema.TOOL_GET_STATE,
    )

    fun isFunctionCallTool(toolName: String): Boolean =
        OobActionSchema.normalizeToolName(toolName) == OobActionSchema.TOOL_CALL_TOOL

    fun isFunctionStepTool(toolName: String): Boolean =
        resolveActionName(toolName) != null || isFunctionCallTool(toolName)

    fun isFunctionExecutor(raw: Any?): Boolean =
        raw?.toString()?.trim()?.lowercase() in setOf(
            EXECUTOR_FUNCTION,
            LEGACY_EXECUTOR_FUNCTION,
        )

    fun isCoordinateAction(toolName: String): Boolean =
        resolveActionName(toolName) in OobActionSchema.coordinateToolNames

    fun isBrowserReplayTool(toolName: String): Boolean =
        OobActionSchema.normalizeToolName(toolName) == AgentToolNames.BROWSER_USE

    fun shouldSkipCapturedTool(toolName: String): Boolean =
        OobActionSchema.normalizeToolName(toolName) in capturedNoiseTools

    fun inputSchema(spec: Map<String, Any?>): Map<String, Any?> {
        val explicit = FunctionJson.mapArg(spec["inputSchema"])
            .ifEmpty { FunctionJson.mapArg(spec["input_schema"]) }
            .ifEmpty {
                FunctionJson.mapArg(spec["parameters"])
                    .takeIf { FunctionJson.firstNonBlank(it["type"]).equals("object", true) }
                    .orEmpty()
            }
        if (explicit.isNotEmpty()) return objectSchema(explicit)

        val properties = linkedMapOf<String, Any?>()
        val required = mutableListOf<String>()
        FunctionJson.listArg(spec["parameters"]).forEach { raw ->
            val parameter = FunctionJson.mapArg(raw)
            val name = FunctionJson.firstNonBlank(parameter["name"])
            if (name.isBlank()) return@forEach
            properties[name] = linkedMapOf<String, Any?>().apply {
                put("type", jsonSchemaType(FunctionJson.firstNonBlank(parameter["type"])))
                parameter["description"]?.let { put("description", it) }
                if (parameter.containsKey("default")) put("default", parameter["default"])
                FunctionJson.listArg(parameter["enum"])
                    .ifEmpty { FunctionJson.listArg(parameter["values"]) }
                    .takeIf(List<Any?>::isNotEmpty)
                    ?.let { put("enum", it) }
                FunctionJson.listArg(parameter["bindings"])
                    .takeIf(List<Any?>::isNotEmpty)
                    ?.let { put("x_oob_bindings", it) }
            }
            if (FunctionJson.boolArg(parameter["required"])) required += name
        }
        return linkedMapOf(
            "type" to "object",
            "properties" to properties,
            "required" to required,
            "additionalProperties" to false,
        )
    }

    fun functionId(spec: Map<String, Any?>): String =
        FunctionJson.firstNonBlank(spec["function_id"], spec["id"])

    fun functionIdFromSpec(spec: Map<String, Any?>): String =
        FunctionJson.firstNonBlank(
            spec["function_id"],
            spec["id"],
            spec["functionId"],
            spec["name"],
        )

    fun parameterNames(spec: Map<String, Any?>): List<String> =
        FunctionJson.mapArg(inputSchema(spec)["properties"]).keys.toList()

    fun callableSummary(spec: Map<String, Any?>): Map<String, Any?> {
        val functionId = functionIdFromSpec(spec)
        val name = FunctionJson.firstNonBlank(spec["name"], functionId)
        val description = FunctionJson.firstNonBlank(spec["description"], name, functionId)
        return linkedMapOf<String, Any?>(
            "function_id" to functionId,
            "name" to name,
            "description" to description,
            "parameters" to inputSchema(spec),
            "argument_names" to parameterNames(spec),
            "step_count" to materializedSteps(spec).size,
        ).filterValues { value ->
            value != null && (value !is String || value.isNotBlank())
        }
    }

    fun sourceRunIds(spec: Map<String, Any?>): List<String> {
        val source = FunctionJson.mapArg(spec["source"])
        val metadata = FunctionJson.mapArg(spec["metadata"])
        return buildList {
            addValues(spec["source_run_ids"])
            addValue(spec["source_run_id"])
            addValues(metadata["source_run_ids"])
            addValue(source["run_id"])
            addValue(source["run_log_id"])
            addValue(source["source_run_id"])
            addValue(metadata["run_id"])
            addValue(metadata["run_log_id"])
            addValue(metadata["source_run_id"])
        }.distinct()
    }

    fun materializedSteps(spec: Map<String, Any?>): List<Map<String, Any?>> {
        val executionSteps = FunctionJson.listArg(FunctionJson.mapArg(spec["execution"])["steps"])
            .mapNotNull(::nonEmptyMap)
        if (executionSteps.isNotEmpty()) return executionSteps
        val topLevelSteps = FunctionJson.listArg(spec["steps"]).mapNotNull(::nonEmptyMap)
        if (topLevelSteps.isNotEmpty()) return topLevelSteps
        return FunctionJson.listArg(spec["actions"])
            .mapIndexedNotNull(::canonicalActionToStep)
    }

    fun stepSummaries(spec: Map<String, Any?>): List<Map<String, Any?>> =
        materializedSteps(spec).mapIndexed { index, step ->
            val tool = actionNameForStep(step)
            linkedMapOf(
                "index" to index,
                "id" to FunctionJson.firstNonBlank(step["id"], "step_${index + 1}"),
                "title" to FunctionJson.firstNonBlank(step["title"], step["summary"], tool),
                "kind" to step["kind"],
                "tool" to tool,
            )
        }

    private fun objectSchema(schema: Map<String, Any?>): Map<String, Any?> {
        val properties = FunctionJson.mapArg(schema["properties"])
        val required = FunctionJson.listArg(schema["required"])
            .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }
            .filter(properties::containsKey)
        return linkedMapOf(
            "type" to "object",
            "properties" to properties,
            "required" to required,
            "additionalProperties" to (schema["additionalProperties"] ?: false),
        )
    }

    private fun nonEmptyMap(value: Any?): Map<String, Any?>? =
        FunctionJson.mapArg(value).takeIf(Map<String, Any?>::isNotEmpty)

    private fun canonicalActionToStep(
        index: Int,
        raw: Any?,
    ): Map<String, Any?>? {
        val action = FunctionJson.mapArg(raw)
        val rawTool = FunctionJson.firstNonBlank(action["tool"], action["type"], action["action"])
        if (rawTool.isBlank()) return null
        val tool = resolveActionName(rawTool) ?: OobActionSchema.normalizeToolName(rawTool)
        val args = FunctionJson.mapArg(action["args"])
            .ifEmpty { FunctionJson.mapArg(action["params"]) }
        val functionStep = resolveActionName(tool) != null || isFunctionCallTool(tool)
        return linkedMapOf<String, Any?>(
            "id" to FunctionJson.firstNonBlank(action["id"], "step_${index + 1}"),
            "index" to index,
            "title" to FunctionJson.firstNonBlank(action["description"], action["prompt"], tool),
            "kind" to if (functionStep) "function" else "tool_call",
            "model_free" to functionStep.takeIf { it },
            "scriptable" to true,
            "tool" to tool,
            "args" to args,
            "source_context" to FunctionJson.mapArg(args["source_context"])
                .takeIf(Map<String, Any?>::isNotEmpty),
        ).filterValues { it != null }
    }

    private fun jsonSchemaType(raw: String): String = when (raw.lowercase()) {
        "int", "integer" -> "integer"
        "number", "float", "double" -> "number"
        "bool", "boolean" -> "boolean"
        "array", "object" -> raw.lowercase()
        else -> "string"
    }

    private fun MutableList<String>.addValue(value: Any?) {
        value?.toString()?.trim()?.takeIf(String::isNotBlank)?.let(::add)
    }

    private fun MutableList<String>.addValues(value: Any?) {
        FunctionJson.listArg(value).forEach { addValue(it) }
    }
}
