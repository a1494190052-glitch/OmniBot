package cn.com.omnimind.bot.function

import cn.com.omnimind.baselib.runlog.OobActionSchema
import cn.com.omnimind.bot.agent.AgentToolNames

object FunctionSchema {
    const val EXECUTOR_FUNCTION: String = "function"
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
        toolName == OobActionSchema.TOOL_CALL_TOOL

    fun isFunctionStepTool(toolName: String): Boolean =
        toolName in OobActionSchema.replayableToolNames || isFunctionCallTool(toolName)

    fun isFunctionExecutor(raw: Any?): Boolean =
        raw?.toString()?.trim()?.lowercase() == EXECUTOR_FUNCTION

    fun isCoordinateAction(toolName: String): Boolean =
        toolName in OobActionSchema.coordinateToolNames

    fun isBrowserReplayTool(toolName: String): Boolean =
        toolName == AgentToolNames.BROWSER_USE

    fun shouldSkipCapturedTool(toolName: String): Boolean =
        toolName in capturedNoiseTools

    fun inputSchema(spec: Map<String, Any?>): Map<String, Any?> =
        FunctionJson.mapArg(spec["input_schema"])

    fun functionId(spec: Map<String, Any?>): String =
        FunctionJson.firstNonBlank(spec["function_id"])

    fun functionIdFromSpec(spec: Map<String, Any?>): String = functionId(spec)

    fun parameterNames(spec: Map<String, Any?>): List<String> =
        FunctionJson.mapArg(inputSchema(spec)["properties"]).keys.toList()

    fun callableSummary(spec: Map<String, Any?>): Map<String, Any?> = linkedMapOf(
        "function_id" to functionId(spec),
        "name" to FunctionJson.firstNonBlank(spec["name"]),
        "description" to FunctionJson.firstNonBlank(spec["description"]),
        "input_schema" to inputSchema(spec),
        "argument_names" to parameterNames(spec),
        "step_count" to materializedSteps(spec).size,
    )

    fun materializedSteps(spec: Map<String, Any?>): List<Map<String, Any?>> =
        FunctionJson.listArg(spec["steps"]).mapIndexed { index, raw ->
            val step = FunctionJson.mapArg(raw)
            val action = FunctionJson.mapArg(step["action"])
            val tool = FunctionJson.firstNonBlank(action["tool"])
            require(tool.isNotBlank()) { "function_action_tool_required" }
            linkedMapOf(
                "step_index" to index,
                "source_state_id" to FunctionJson.firstNonBlank(step["source_state_id"]).takeIf(String::isNotBlank),
                "action" to linkedMapOf(
                    "tool" to tool,
                    "args" to FunctionJson.mapArg(action["args"]),
                ),
            ).filterValues { it != null }
        }

    fun action(step: Map<String, Any?>): Map<String, Any?> =
        FunctionJson.mapArg(step["action"])

    fun actionTool(step: Map<String, Any?>): String =
        FunctionJson.firstNonBlank(action(step)["tool"])

    fun actionArgs(step: Map<String, Any?>): Map<String, Any?> =
        FunctionJson.mapArg(action(step)["args"])

    fun stepSummaries(spec: Map<String, Any?>): List<Map<String, Any?>> =
        materializedSteps(spec).mapIndexed { index, step ->
            val tool = actionTool(step)
            linkedMapOf(
                "step_index" to index,
                "tool" to tool,
            )
        }
}
