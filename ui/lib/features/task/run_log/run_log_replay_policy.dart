import 'package:ui/features/task/run_log/oob_canonical_action_schema.dart';

class RunLogReplayPolicy {
  const RunLogReplayPolicy._();

  static const schemaVersion = 'oob.runlog_replay_policy.v1';

  static const omniflowActions = OobCanonicalActionSchema.replayableToolNames;

  static const coordinateActions = OobCanonicalActionSchema.coordinateToolNames;

  static const perceptionTools = <String>{
    'vlm_task',
    'image_picker',
    'android_privileged_action_screenshot',
    'screen_capture',
  };

  static const dataFlowTools = <String>{
    'browser_use',
    'web_search',
    'memory_search',
    'memory_recall',
    'memory_query',
    'oob_agent_run',
    'omniflow.recall',
    'omniflow.ingest_run_log',
    'oob_function_list',
    'oob_function_get',
    'oob_function_register',
    'update_function',
    'oob_function_guard_check',
    'oob_run_log_list',
    'oob_run_log_get',
    'oob_run_log_convert',
  };

  static const omniflowGraphTools = <String>{
    'go_to_node',
    'click_node',
  };

  static const omniflowFunctionTools = <String>{};

  static const omniflowToolCallTools = <String>{
    'call_tool',
  };

  static const providerOnlyTools = <String>{};

  static const skipTools = <String>{
    'notification_send',
    'calendar_event_create',
    'skills_loaded',
    'status_update',
    'assistant_response',
    'get_state',
    'wait',
  };

  static String normalizeToolName(String toolName) {
    return toolName.trim().toLowerCase();
  }

  static String? omniflowActionForToolName(String toolName) {
    final normalized = normalizeToolName(toolName);
    return omniflowActions.contains(normalized) ? normalized : null;
  }

  static bool isCoordinateAction(String toolName) {
    final action = omniflowActionForToolName(toolName);
    return action != null && coordinateActions.contains(action);
  }

  static bool isPerceptionTool(String toolName) {
    return perceptionTools.contains(normalizeToolName(toolName));
  }

  static bool isDataFlowTool(String toolName) {
    return dataFlowTools.contains(normalizeToolName(toolName));
  }

  static bool isProviderOnlyTool(String toolName) {
    return providerOnlyTools.contains(normalizeToolName(toolName));
  }

  static bool isOmniflowGraphTool(String toolName) {
    return omniflowGraphTools.contains(normalizeToolName(toolName));
  }

  static bool isOmniflowFunctionTool(String toolName) {
    return omniflowFunctionTools.contains(normalizeToolName(toolName));
  }

  static bool isOmniflowToolCallTool(String toolName) {
    return omniflowToolCallTools.contains(normalizeToolName(toolName));
  }

  static bool isOmniflowExecutionTool(String toolName) {
    return isOmniflowGraphTool(toolName) ||
        isOmniflowFunctionTool(toolName) ||
        isOmniflowToolCallTool(toolName);
  }

  static bool isAgentTool(String toolName) {
    return isPerceptionTool(toolName) ||
        isDataFlowTool(toolName) ||
        isProviderOnlyTool(toolName);
  }

  static bool shouldSkipTool(String toolName) {
    return skipTools.contains(normalizeToolName(toolName));
  }

  static String agentStepReason(String toolName) {
    final normalized = normalizeToolName(toolName);
    if (perceptionTools.contains(normalized)) {
      return 'perception_only_step_without_recorded_actions';
    }
    if (dataFlowTools.contains(normalized)) {
      return 'data_flow_tool_requires_live_context';
    }
    if (providerOnlyTools.contains(normalized)) {
      return 'provider_owned_replay_requires_omniflow';
    }
    return 'non_scriptable_or_vlm_step';
  }
}
