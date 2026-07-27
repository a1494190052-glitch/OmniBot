#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: tools/check-main-stream-zero-diff.sh [--base <ref>] [--fetch] [--show-diff]
       tools/check-main-stream-zero-diff.sh [--strict-agent-workflow]

Checks that the Agent task input/output/thinking workflow is aligned with
origin/main.

This checker is intentionally scoped to the Agent task stream path:
  - native Agent stream lifecycle/channel/persistence files must be 0 diff;
  - frontend AgentStreamEvent parsing/reducing/persistence/timeline files must be 0 diff;
  - mixed service files may differ only outside Agent stream/createAgentTask anchors;
  - known stream side-channel keywords and Agent-task side uses must be absent.

Agent runtime/tool capability files are reported separately. They are not a
stream lifecycle failure unless --strict-agent-workflow is provided, because
vlm-core intentionally carries VLM/Function tool capability outside the stream
lifecycle.

VLM task implementation files and RunLog/Function ordinary query APIs are
outside this check unless they call into Agent task stream workflow.
USAGE
}

BASE_REF="${BASE_REF:-origin/main}"
FETCH=0
SHOW_DIFF=0
STRICT_AGENT_WORKFLOW=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --base)
      if [[ $# -lt 2 ]]; then
        echo "error: --base requires a ref" >&2
        exit 2
      fi
      BASE_REF="$2"
      shift 2
      ;;
    --fetch)
      FETCH=1
      shift
      ;;
    --show-diff)
      SHOW_DIFF=1
      shift
      ;;
    --strict-agent-workflow)
      STRICT_AGENT_WORKFLOW=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "error: unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

if [[ "$FETCH" -eq 1 ]]; then
  git fetch origin main:refs/remotes/origin/main --prune
fi

if ! git rev-parse --verify --quiet "${BASE_REF}^{commit}" >/dev/null; then
  echo "error: base ref not found: ${BASE_REF}" >&2
  exit 2
fi

NATIVE_AGENT_STREAM_PATHS=(
  "app/src/main/java/cn/com/omnimind/bot/agent/AgentStreamEvent.kt"
  "app/src/main/java/cn/com/omnimind/bot/agent/AgentTextSanitizer.kt"
  "app/src/main/java/cn/com/omnimind/bot/agent/llm/AgentLlmClient.kt"
  "app/src/main/java/cn/com/omnimind/bot/agent/llm/AgentLlmStreamAccumulator.kt"
  "app/src/main/java/cn/com/omnimind/bot/agent/llm/ChatCompletionModels.kt"
  "app/src/main/java/cn/com/omnimind/bot/agent/conversation/AgentConversationHistoryRepository.kt"
  "app/src/main/java/cn/com/omnimind/bot/agent/conversation/AgentConversationHistorySupport.kt"
  "app/src/main/java/cn/com/omnimind/bot/agent/conversation/ConversationSnapshotOrdering.kt"
  "app/src/main/java/cn/com/omnimind/bot/agent/skill/SelfImprovingSkillFailureHook.kt"
  "app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentEventAdapter.kt"
  "app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentModels.kt"
  "app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentOrchestrator.kt"
  "app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentSystemPrompt.kt"
  "app/src/main/java/cn/com/omnimind/bot/agent/runtime/OmniAgentExecutor.kt"
  "app/src/main/java/cn/com/omnimind/bot/agent/runtime/SubagentDispatcher.kt"
  "app/src/main/java/cn/com/omnimind/bot/agent/workspace/AgentWorkspaceManager.kt"
  "app/src/main/java/cn/com/omnimind/bot/agent/workspace/memory/WorkspaceMemoryService.kt"
  "app/src/main/java/cn/com/omnimind/bot/manager/AssistsCoreManager.kt"
  "app/src/main/java/cn/com/omnimind/bot/ui/channel/AssistsCoreChannel.kt"
  "app/src/main/java/cn/com/omnimind/bot/webchat/AgentRunService.kt"
)

AGENT_RUNTIME_TOOL_REVIEW_PATHS=(
  "app/src/main/java/cn/com/omnimind/bot/agent/AgentToolExecutionControl.kt"
  "app/src/main/java/cn/com/omnimind/bot/agent/AgentToolJson.kt"
  "app/src/main/java/cn/com/omnimind/bot/agent/AgentToolNames.kt"
  "app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentRuntimeContracts.kt"
  "app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentRuntimeModels.kt"
  "app/src/main/java/cn/com/omnimind/bot/agent/tool/AgentToolDefinitions.kt"
  "app/src/main/java/cn/com/omnimind/bot/agent/tool/AgentToolRegistry.kt"
  "app/src/main/java/cn/com/omnimind/bot/agent/tool/AgentToolRouter.kt"
  "app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/FunctionToolHandler.kt"
  "app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/ImagePickerToolHandler.kt"
  "app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/LocalActionToolHandler.kt"
  "app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/GuiTaskToolHandler.kt"
  "app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/WebSearchToolHandler.kt"
)

FRONTEND_AGENT_STREAM_PATHS=(
  "ui/lib/models/agent_stream_event.dart"
  "ui/lib/models/chat_message_model.dart"
  "ui/lib/services/agent_stream_meta.dart"
  "ui/lib/services/agent_stream_reducer.dart"
  "ui/lib/services/conversation_history_service.dart"
  "ui/lib/services/conversation_service.dart"
  "ui/lib/features/home/pages/chat/mixins/agent_stream_handler.dart"
  "ui/lib/features/home/pages/chat/services/chat_conversation_runtime_coordinator.dart"
  "ui/lib/features/home/pages/chat/utils/agent_run_timeline.dart"
  "ui/lib/features/home/pages/chat/utils/agent_thinking_card_locator.dart"
  "ui/lib/features/home/pages/chat/utils/deep_thinking_persistence.dart"
  "ui/lib/features/home/pages/chat/widgets/agent_run_group_message.dart"
  "ui/lib/features/home/pages/command_overlay/chat_bot_sheet.dart"
  "ui/lib/features/home/pages/command_overlay/widgets/cards/agent_tool_summary_card.dart"
  "ui/lib/features/home/pages/command_overlay/widgets/cards/card_widget_factory.dart"
  "ui/lib/features/home/pages/command_overlay/widgets/cards/deep_thinking_card.dart"
  "ui/lib/features/home/pages/command_overlay/widgets/message_bubble.dart"
)

SIDE_CHANNEL_PATTERN='onAgentStreamEventBatch|AgentStreamEventBatcher|agentStreamEventBatcher|onFunctionRunProgress|nativeMethodEventStream|NativeMethodEvent|FunctionRunProgress|functionRunProgress|onToolCardEvent|onToolCallPreview|onAgentRunStateChanged|agentRunStateChanged|onVlmToolEvent|onSkillsResolved|UserDialog'
MIXED_AGENT_STREAM_DIFF_PATTERN='onAgentStreamEvent|AgentStreamEvent|setOnAgentStreamEventCallback|removeOnAgentStreamEventCallback|createAgentTask|continueAgentTask|allowedTools|toolProfile'
AGENT_TASK_SIDE_USE_PATTERN='AssistsMessageService\.createAgentTask|MethodCall\("createAgentTask"|invokeMethod\([^)]*createAgentTask'
AGENT_TASK_ENTRY_SIDE_CHANNEL_PATTERN='toolProfile|allowedTools|onAgentStreamEventBatch|onFunctionRunProgress|nativeMethodEventStream|NativeMethodEvent'

status=0

pass() {
  echo "PASS $1"
}

fail() {
  status=1
  echo "FAIL $1"
}

info() {
  echo "INFO $1"
}

check_zero_diff() {
  local label="$1"
  shift
  local paths=("$@")

  if git diff --quiet --exit-code "$BASE_REF" -- "${paths[@]}"; then
    pass "$label files are 0 diff."
    return
  fi

  fail "$label files differ from ${BASE_REF}."
  git diff --name-status "$BASE_REF" -- "${paths[@]}"
  if [[ "$SHOW_DIFF" -eq 1 ]]; then
    git diff "$BASE_REF" -- "${paths[@]}"
  fi
}

check_mixed_agent_stream_diff() {
  local path="$1"
  if git diff --quiet --exit-code "$BASE_REF" -- "$path"; then
    pass "$path has no diff."
    return
  fi

  local touched
  touched="$(git diff -U0 "$BASE_REF" -- "$path" | rg "$MIXED_AGENT_STREAM_DIFF_PATTERN" || true)"
  if [[ -z "$touched" ]]; then
    pass "$path changes do not touch Agent stream/createAgentTask anchors."
    return
  fi

  fail "$path changes touch Agent stream/createAgentTask anchors."
  printf '%s\n' "$touched"
  if [[ "$SHOW_DIFF" -eq 1 ]]; then
    git diff "$BASE_REF" -- "$path"
  fi
}

check_flutter_chat_sync_bridge_dispatch() {
  local path="app/src/main/java/cn/com/omnimind/bot/webchat/FlutterChatSyncBridge.kt"
  local bridge_diff
  bridge_diff="$(git diff -U0 "$BASE_REF" -- "$path" | rg 'mainScope|CoroutineScope|SupervisorJob|launch|private fun dispatch|invokeMethod\(method, arguments\)|dispatchConversation|dispatchExternalUserMessageAppended' || true)"
  if [[ -z "$bridge_diff" ]]; then
    pass "$path dispatch lifecycle matches ${BASE_REF}."
    return
  fi

  fail "$path dispatch lifecycle differs from ${BASE_REF}."
  printf '%s\n' "$bridge_diff"
  if [[ "$SHOW_DIFF" -eq 1 ]]; then
    git diff "$BASE_REF" -- "$path"
  fi
}

check_no_agent_task_side_uses() {
  local allowed_paths_regex='^(ui/lib/features/home/pages/chat/chat_page_conversation_flow\.dart|ui/lib/features/home/pages/command_overlay/chat_bot_sheet\.dart|ui/lib/services/assists_core_service\.dart|ui/lib/services/scheduled_task_scheduler_service\.dart|app/src/main/java/cn/com/omnimind/bot/manager/AssistsCoreManager\.kt|app/src/main/java/cn/com/omnimind/bot/ui/channel/AssistsCoreChannel\.kt|app/src/main/java/cn/com/omnimind/bot/webchat/AgentRunService\.kt|app/src/main/java/cn/com/omnimind/bot/agent/workspace/schedule/WorkspaceScheduledTaskScheduler\.kt)$'
  local hits
  hits="$(
    rg -n "$AGENT_TASK_SIDE_USE_PATTERN" app assists ui -S \
      | awk -F: -v allowed="$allowed_paths_regex" '$1 !~ allowed { print }' || true
  )"
  if [[ -z "$hits" ]]; then
    pass "no Agent task side uses found outside allowlist."
    return
  fi
  fail "Agent task side uses found outside allowlist."
  printf '%s\n' "$hits"
}

check_no_agent_task_entry_side_channels() {
  local paths=(
    "ui/lib/services/assists_core_service.dart"
    "app/src/main/java/cn/com/omnimind/bot/webchat/AgentRunService.kt"
    "app/src/main/java/cn/com/omnimind/bot/ui/channel/AssistsCoreChannel.kt"
    "app/src/main/java/cn/com/omnimind/bot/manager/AssistsCoreManager.kt"
  )
  local hits
  hits="$(rg -n "$AGENT_TASK_ENTRY_SIDE_CHANNEL_PATTERN" "${paths[@]}" -S || true)"
  if [[ -z "$hits" ]]; then
    pass "Agent task entry files do not expose tool controls or side-channel stream methods."
    return
  fi
  fail "Agent task entry files expose tool controls or side-channel stream methods."
  printf '%s\n' "$hits"
}

report_agent_runtime_tool_diffs() {
  if git diff --quiet --exit-code "$BASE_REF" -- "${AGENT_RUNTIME_TOOL_REVIEW_PATHS[@]}"; then
    pass "Agent runtime/tool capability review files are 0 diff."
    return
  fi

  if [[ "$STRICT_AGENT_WORKFLOW" -eq 1 ]]; then
    fail "Agent runtime/tool capability files differ from ${BASE_REF}."
  else
    info "Agent runtime/tool capability files differ from ${BASE_REF}; not counted as stream lifecycle failure."
  fi
  git diff --name-status "$BASE_REF" -- "${AGENT_RUNTIME_TOOL_REVIEW_PATHS[@]}"
  if [[ "$SHOW_DIFF" -eq 1 ]]; then
    git diff "$BASE_REF" -- "${AGENT_RUNTIME_TOOL_REVIEW_PATHS[@]}"
  fi
}

echo "Base ref: ${BASE_REF}"
echo
echo "Checking native Agent task stream backend 0 diff..."
check_zero_diff "native Agent task stream backend" "${NATIVE_AGENT_STREAM_PATHS[@]}"

echo
echo "Reviewing Agent runtime/tool capability diffs..."
report_agent_runtime_tool_diffs

echo
echo "Checking frontend Agent task stream/thinking 0 diff..."
check_zero_diff "frontend Agent task stream/thinking" "${FRONTEND_AGENT_STREAM_PATHS[@]}"

echo
echo "Checking mixed Flutter service Agent stream anchors..."
check_mixed_agent_stream_diff "ui/lib/services/assists_core_service.dart"
check_flutter_chat_sync_bridge_dispatch

echo
echo "Checking side-channel stream keywords..."
if rg -n "$SIDE_CHANNEL_PATTERN" app assists ui -S; then
  fail "known side-channel stream keywords found."
else
  pass "no known side-channel stream keywords found."
fi

if [[ -e "app/src/main/java/cn/com/omnimind/bot/manager/AgentStreamEventBatcher.kt" ]]; then
  fail "AgentStreamEventBatcher.kt exists."
else
  pass "AgentStreamEventBatcher.kt is absent."
fi

echo
echo "Checking Agent task side uses..."
check_no_agent_task_side_uses
check_no_agent_task_entry_side_channels

echo
if [[ "$status" -eq 0 ]]; then
  if [[ "$STRICT_AGENT_WORKFLOW" -eq 1 ]]; then
    echo "RESULT pass: strict Agent workflow review is aligned with ${BASE_REF}."
  else
    echo "RESULT pass: Agent task stream lifecycle is aligned with ${BASE_REF}."
  fi
else
  if [[ "$STRICT_AGENT_WORKFLOW" -eq 1 ]]; then
    echo "RESULT fail: strict Agent workflow review is not aligned with ${BASE_REF}."
  else
    echo "RESULT fail: Agent task stream lifecycle is not aligned with ${BASE_REF}."
  fi
fi

exit "$status"
