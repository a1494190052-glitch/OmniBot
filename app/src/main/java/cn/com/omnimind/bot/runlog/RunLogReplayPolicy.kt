package cn.com.omnimind.bot.runlog
import cn.com.omnimind.baselib.runlog.OobActionSchema

import cn.com.omnimind.bot.agent.AgentToolNames
import cn.com.omnimind.bot.omniflow.OobFunctionToolNames

/**
 * Static replay classification shared by RunLog conversion and local replay.
 *
 * This is not a dispatcher or service layer. Function replay lowers UI steps
 * to canonical actions and sends them through ActionExecutor; ReplayHelper only
 * owns replay checks/remapping. This policy only owns non-action tool categories.
 */
object RunLogReplayPolicy {
    const val schemaVersion: String = "oob.runlog_replay_policy.v1"
    const val fixedReplayOnly: Boolean = false
    const val fixedReplayRunner: String = "oob_omniflow_loop"
    const val EXECUTOR_OMNIFLOW: String = "omniflow"
    const val EXECUTOR_AGENT: String = "agent"
    const val EXECUTOR_TOOL: String = "tool"
    const val TOOL_AGENT_RUN: String = "oob.agent.run"
    const val TOOL_CALL_TOOL: String = "call_tool"
    const val TOOL_WAIT: String = "wait"
    const val TOOL_EXTERNAL_TOOL: String = "external_tool"
    const val TOOL_OOB_AGENT_RUN_LEGACY: String = "oob_agent_run"
    const val TOOL_OMNIFLOW_RECALL: String = "omniflow.recall"
    const val TOOL_OMNIFLOW_INGEST_RUN_LOG: String = "omniflow.ingest_run_log"

    val omniflowActions: Set<String> = OobActionSchema.replayableToolNames

    val coordinateActions: Set<String> = OobActionSchema.coordinateToolNames

    val perceptionTools: Set<String> = setOf(
        AgentToolNames.VLM_TASK,
        "image_picker",
        "android_privileged_action_screenshot",
        "screen_capture",
    )

    private val functionDataFlowTools: Set<String> = setOf(
        OobFunctionToolNames.FUNCTION_LIST,
        OobFunctionToolNames.FUNCTION_GET,
        OobFunctionToolNames.FUNCTION_REGISTER,
        OobFunctionToolNames.FUNCTION_UPDATE,
    ) + OobFunctionToolNames.runLogTools

    val dataFlowTools: Set<String> = setOf(
        AgentToolNames.BROWSER_USE,
        AgentToolNames.WEB_SEARCH,
        "memory_search",
        "memory_recall",
        "memory_query",
        TOOL_OOB_AGENT_RUN_LEGACY,
        TOOL_OMNIFLOW_RECALL,
        TOOL_OMNIFLOW_INGEST_RUN_LOG,
    ) + functionDataFlowTools

    val omniflowFunctionTools: Set<String> = emptySet()

    val omniflowToolCallTools: Set<String> = setOf(
        TOOL_CALL_TOOL,
    )

    /** Backward-compatible contract field. Graph route execution is not local. */
    val providerOnlyTools: Set<String> = emptySet()

    val skipTools: Set<String> = setOf(
        "notification_send",
        "calendar_event_create",
        "skills_loaded",
        "status_update",
        "assistant_response",
        "get_state",
    )

    fun normalizeToolName(toolName: String): String = toolName.trim().lowercase()

    fun omniflowActionForToolName(toolName: String): String? =
        resolveActionName(toolName)

    fun isCoordinateAction(toolName: String): Boolean =
        resolveActionName(toolName) in coordinateActions

    fun isPerceptionTool(toolName: String): Boolean =
        normalizeToolName(toolName) in perceptionTools

    fun isDataFlowTool(toolName: String): Boolean =
        normalizeToolName(toolName) in dataFlowTools

    fun isProviderOnlyTool(toolName: String): Boolean =
        normalizeToolName(toolName) in providerOnlyTools

    fun isOmniflowToolCallTool(toolName: String): Boolean =
        normalizeToolName(toolName) in omniflowToolCallTools

    fun isOmniflowExecutionTool(toolName: String): Boolean =
        isOmniflowToolCallTool(toolName)

    fun isAgentTool(toolName: String): Boolean =
        isPerceptionTool(toolName) || isDataFlowTool(toolName) || isProviderOnlyTool(toolName)

    fun shouldSkipTool(toolName: String): Boolean =
        normalizeToolName(toolName) in skipTools

    fun agentStepReason(toolName: String): String {
        val normalized = normalizeToolName(toolName)
        return when {
            normalized in perceptionTools -> "perception_only_step_without_recorded_actions"
            normalized in dataFlowTools -> "data_flow_tool_requires_live_context"
            normalized in providerOnlyTools -> "provider_owned_replay_requires_omniflow"
            else -> "non_scriptable_or_vlm_step"
        }
    }

}
