#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: tools/check-main-stream-zero-diff.sh [--base <ref>] [--fetch] [--show-diff]

Checks that the Agent task frontend input/output stream path is aligned with
origin/main by file-level zero diff.

This checker is intentionally scoped to the Agent task stream path:
  - native Agent task stream/runtime/channel files must be 0 diff;
  - frontend thinking/AgentStreamEvent handling files must be 0 diff;
  - known stream side-channel keywords must be absent.

VLM task implementation files are outside this check.
USAGE
}

BASE_REF="${BASE_REF:-origin/main}"
FETCH=0
SHOW_DIFF=0

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
)

FRONTEND_AGENT_STREAM_PATHS=(
  "ui/lib/models/agent_stream_event.dart"
  "ui/lib/services/agent_stream_meta.dart"
  "ui/lib/services/agent_stream_reducer.dart"
  "ui/lib/features/home/pages/chat/mixins/agent_stream_handler.dart"
  "ui/lib/features/home/pages/command_overlay/chat_bot_sheet.dart"
  "ui/lib/features/home/pages/command_overlay/widgets/cards/deep_thinking_card.dart"
  "ui/lib/features/home/pages/command_overlay/widgets/message_bubble.dart"
)

SIDE_CHANNEL_PATTERN='onAgentStreamEventBatch|AgentStreamEventBatcher|agentStreamEventBatcher|onFunctionRunProgress|nativeMethodEventStream|NativeMethodEvent|FunctionRunProgress|functionRunProgress|onToolCardEvent|onToolCallPreview|onAgentRunStateChanged|agentRunStateChanged|onVlmToolEvent|onSkillsResolved|UserDialog'

status=0

pass() {
  echo "PASS $1"
}

fail() {
  status=1
  echo "FAIL $1"
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

echo "Base ref: ${BASE_REF}"
echo
echo "Checking native Agent task stream backend 0 diff..."
check_zero_diff "native Agent task stream backend" "${NATIVE_AGENT_STREAM_PATHS[@]}"

echo
echo "Checking frontend Agent task stream/thinking 0 diff..."
check_zero_diff "frontend Agent task stream/thinking" "${FRONTEND_AGENT_STREAM_PATHS[@]}"

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
if [[ "$status" -eq 0 ]]; then
  echo "RESULT pass: Agent task stream path is file-level aligned with ${BASE_REF}."
else
  echo "RESULT fail: Agent task stream path is not file-level aligned with ${BASE_REF}."
fi

exit "$status"
