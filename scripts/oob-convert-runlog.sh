#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

DEVICE_SERIAL="${ANDROID_SERIAL:-emulator-5556}"
PACKAGE_NAME="${PACKAGE_NAME:-cn.com.omnimind.bot.debug}"
ACTION="cn.com.omnimind.bot.debug.CONVERT_RUNLOG_AND_RUN_FUNCTION"
RECEIVER_CLASS="cn.com.omnimind.bot.debug.DebugRunLogFunctionReplayReceiver"
RUN_ID=""
RUN_LOG_PATH=""
FUNCTION_ID=""
NAME=""
DESCRIPTION=""
OUTPUT_PATH=""
ARGUMENTS_JSON=""
ARGUMENTS_JSON_PATH=""
RUN_AFTER_CONVERT=0
AGENT_VISIBLE=0
ENHANCE=0
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-60}"

usage() {
  cat <<'EOF'
Usage:
  scripts/oob-convert-runlog.sh --run-id <id> [--device SERIAL] [--output-path PATH] [--agent-visible]
  scripts/oob-convert-runlog.sh --run-log-path runtime/runlogs/foo.run_log.json [--device SERIAL] [--agent-visible]

Converts an OOB InternalRunLog into a native reusable Function through Kotlin.
The script only sends adb broadcasts; RunLog conversion and registration happen
inside OOB's OobRunLogReplayService/OobOmniFlowToolkitService.

By default the converted Function stays a manual hidden asset. Use
--agent-visible only when intentionally publishing it for online VLM call_tool
recall.

Use --enhance to pass the registered Function through OOB update_function
mode=enhance before optional replay. Replay then uses the saved enhanced
Function.

Use --arguments-json or --arguments-json-path with --run to replay the saved
Function with explicit Function arguments.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --device|--target)
      DEVICE_SERIAL="$2"
      shift 2
      ;;
    --package)
      PACKAGE_NAME="$2"
      shift 2
      ;;
    --run-id|--run_id)
      RUN_ID="$2"
      shift 2
      ;;
    --run-log-path|--runlog-path)
      RUN_LOG_PATH="$2"
      shift 2
      ;;
    --function-id)
      FUNCTION_ID="$2"
      shift 2
      ;;
    --name)
      NAME="$2"
      shift 2
      ;;
    --description)
      DESCRIPTION="$2"
      shift 2
      ;;
    --output-path)
      OUTPUT_PATH="$2"
      shift 2
      ;;
    --arguments-json|--args-json)
      ARGUMENTS_JSON="$2"
      shift 2
      ;;
    --arguments-json-path|--args-json-path)
      ARGUMENTS_JSON_PATH="$2"
      shift 2
      ;;
    --run)
      RUN_AFTER_CONVERT=1
      shift
      ;;
    --agent-visible|--agent_visible)
      AGENT_VISIBLE=1
      shift
      ;;
    --enhance)
      ENHANCE=1
      shift
      ;;
    --no-enhance)
      ENHANCE=0
      shift
      ;;
    --timeout)
      TIMEOUT_SECONDS="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ -z "${RUN_ID// }" && -z "${RUN_LOG_PATH// }" ]]; then
  echo "Either --run-id or --run-log-path is required" >&2
  usage >&2
  exit 2
fi
if [[ -n "${RUN_LOG_PATH// }" && ! -f "$RUN_LOG_PATH" ]]; then
  echo "RunLog path not found: $RUN_LOG_PATH" >&2
  exit 2
fi
if [[ -n "${ARGUMENTS_JSON_PATH// }" ]]; then
  if [[ ! -f "$ARGUMENTS_JSON_PATH" ]]; then
    echo "Arguments JSON path not found: $ARGUMENTS_JSON_PATH" >&2
    exit 2
  fi
  ARGUMENTS_JSON="$(python3 - "$ARGUMENTS_JSON_PATH" <<'PY'
import json
import sys
from pathlib import Path
print(json.dumps(json.loads(Path(sys.argv[1]).read_text(encoding="utf-8")), ensure_ascii=False, separators=(",", ":")))
PY
)"
fi
if [[ -n "${ARGUMENTS_JSON// }" ]]; then
  ARGUMENTS_JSON="$(python3 - "$ARGUMENTS_JSON" <<'PY'
import json
import sys
print(json.dumps(json.loads(sys.argv[1]), ensure_ascii=False, separators=(",", ":")))
PY
)"
fi

mkdir -p runtime/functions
if [[ -z "${OUTPUT_PATH// }" ]]; then
  stamp="$(date +%Y%m%d-%H%M%S)"
  OUTPUT_PATH="runtime/functions/${stamp}.function.json"
fi
mkdir -p "$(dirname "$OUTPUT_PATH")"

ADB=(adb -s "$DEVICE_SERIAL")
RECEIVER="$PACKAGE_NAME/$RECEIVER_CLASS"
RESULT_FILE="files/debug-runlog-function-replay-result.json"
RESULT_TMP="$(mktemp -t oob-convert-result.XXXXXX.json)"
trap 'rm -f "$RESULT_TMP"' EXIT

base64_text() {
  python3 - "$1" <<'PY'
import base64
import sys
print(base64.b64encode(sys.argv[1].encode("utf-8")).decode("ascii"))
PY
}

read_app_file() {
  local path="$1"
  "${ADB[@]}" shell run-as "$PACKAGE_NAME" cat "$path" 2>/dev/null || true
}

"${ADB[@]}" get-state >/dev/null
"${ADB[@]}" shell run-as "$PACKAGE_NAME" rm -f "$RESULT_FILE" >/dev/null 2>&1 || true

BROADCAST_ARGS=(
  shell am broadcast
  -a "$ACTION"
  -n "$RECEIVER"
  --ez run "$([[ "$RUN_AFTER_CONVERT" -eq 1 ]] && echo true || echo false)"
  --ez agentVisible "$([[ "$AGENT_VISIBLE" -eq 1 ]] && echo true || echo false)"
  --ez enhance "$([[ "$ENHANCE" -eq 1 ]] && echo true || echo false)"
)
if [[ -n "${RUN_ID// }" ]]; then
  BROADCAST_ARGS+=(--es runId "$RUN_ID")
fi
if [[ -n "${RUN_LOG_PATH// }" ]]; then
  REMOTE_RUNLOG_NAME="$(python3 - "$RUN_LOG_PATH" <<'PY'
import re
import sys
from pathlib import Path
name = Path(sys.argv[1]).name
name = re.sub(r"[^A-Za-z0-9._-]+", "_", name)
print(name[:120] or "runlog.json")
PY
)"
  REMOTE_RUNLOG_PATH="debug-runlogs/$REMOTE_RUNLOG_NAME"
  REMOTE_RUNLOG_ABS="/data/user/0/$PACKAGE_NAME/files/$REMOTE_RUNLOG_PATH"
  "${ADB[@]}" shell run-as "$PACKAGE_NAME" mkdir -p "/data/user/0/$PACKAGE_NAME/files/debug-runlogs" >/dev/null
  python3 - "$RUN_LOG_PATH" <<'PY' | "${ADB[@]}" shell run-as "$PACKAGE_NAME" sh -c "cat > '$REMOTE_RUNLOG_ABS'"
import json
import re
import sys
from pathlib import Path

data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
payload = data.get("payload") if isinstance(data, dict) and isinstance(data.get("payload"), dict) else data

def first_map(*values):
    for value in values:
        if isinstance(value, dict):
            return value
    return {}

def enrich_args(args):
    if not isinstance(args, dict):
        return
    evidence = first_map(args.get("target_evidence"))
    if not evidence:
        return
    label = str(evidence.get("label") or "")
    resource_id = str(evidence.get("resource_id") or evidence.get("resourceId") or "").strip()
    if not resource_id:
        match = re.search(r"[A-Za-z0-9_.]+:id/[A-Za-z0-9_.$-]+", label)
        resource_id = match.group(0) if match else ""
    if resource_id and not str(args.get("node_resource_id") or "").strip():
        args["node_resource_id"] = resource_id
    if evidence.get("index") is not None and not str(args.get("element_index") or "").strip():
        args["element_index"] = evidence.get("index")
    bounds = evidence.get("bounds")
    if isinstance(bounds, list) and len(bounds) == 4 and not str(args.get("bounds") or "").strip():
        args["bounds"] = "[" + ",".join(str(item) for item in bounds) + "]"

def enrich_payload(value):
    if not isinstance(value, dict):
        return value
    for card in value.get("cards") or []:
        if not isinstance(card, dict):
            continue
        enrich_args(card.get("arguments"))
        enrich_args(card.get("args"))
        tool_call = first_map(card.get("tool_call"))
        enrich_args(tool_call.get("arguments"))
    return value

payload = enrich_payload(payload)
print(json.dumps(payload, ensure_ascii=False, separators=(",", ":")))
PY
  BROADCAST_ARGS+=(--es runLogPath "$REMOTE_RUNLOG_ABS")
fi
if [[ -n "${FUNCTION_ID// }" ]]; then
  BROADCAST_ARGS+=(--es functionId "$FUNCTION_ID")
fi
if [[ -n "${NAME// }" ]]; then
  BROADCAST_ARGS+=(--es nameBase64 "$(base64_text "$NAME")")
fi
if [[ -n "${DESCRIPTION// }" ]]; then
  BROADCAST_ARGS+=(--es descriptionBase64 "$(base64_text "$DESCRIPTION")")
fi
if [[ -n "${ARGUMENTS_JSON// }" ]]; then
  BROADCAST_ARGS+=(--es argumentsBase64 "$(base64_text "$ARGUMENTS_JSON")")
fi

"${ADB[@]}" "${BROADCAST_ARGS[@]}" >/dev/null

deadline=$((SECONDS + TIMEOUT_SECONDS))
while (( SECONDS < deadline )); do
  read_app_file "$RESULT_FILE" >"$RESULT_TMP"
  if [[ -s "$RESULT_TMP" ]] && python3 - "$RESULT_TMP" <<'PY' >/dev/null 2>&1
import json
import sys
json.load(open(sys.argv[1], encoding="utf-8"))
PY
  then
    break
  fi
  sleep 1
done

if [[ ! -s "$RESULT_TMP" ]] || ! python3 - "$RESULT_TMP" <<'PY' >/dev/null 2>&1
import json
import sys
json.load(open(sys.argv[1], encoding="utf-8"))
PY
then
  echo "Timed out waiting for convert result on $DEVICE_SERIAL" >&2
  exit 1
fi

python3 - "$RESULT_TMP" "$OUTPUT_PATH" "$DEVICE_SERIAL" <<'PY'
import json
import sys
from pathlib import Path

result_path, output_path, target = sys.argv[1], sys.argv[2], sys.argv[3]
data = json.load(open(result_path, encoding="utf-8"))
spec = data.get("function_spec") or (data.get("convert") or {}).get("function_spec")
enhanced = data.get("enhanced_function_spec")
if isinstance(enhanced, dict) and enhanced:
    spec = enhanced
if isinstance(spec, dict) and spec:
    Path(output_path).write_text(
        json.dumps(spec, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
else:
    output_path = ""
enhance = data.get("enhance") if isinstance(data.get("enhance"), dict) else {}
replay = data.get("replay") if isinstance(data.get("replay"), dict) else {}
explicit_enhanced_replay = data.get("replay_uses_enhanced_function")
if explicit_enhanced_replay is True:
    replay_uses_enhanced_function = True
elif explicit_enhanced_replay is False:
    replay_uses_enhanced_function = False
else:
    replay_uses_enhanced_function = (
        data.get("enhance_requested") is True
        and enhance.get("success") is True
        and isinstance(enhanced, dict)
        and bool(enhanced)
        and replay.get("success") is True
    )
summary = {
    "success": data.get("success") is True,
    "target": target,
    "run_id": data.get("run_id"),
    "function_id": data.get("function_id") or (data.get("convert") or {}).get("function_id"),
    "function_path": output_path or None,
    "convert": data.get("convert"),
    "enhance": enhance or data.get("enhance"),
    "enhance_cost": data.get("enhance_cost"),
    "enhance_requested": data.get("enhance_requested"),
    "function_spec_hash": data.get("function_spec_hash"),
    "enhanced_function_spec_hash": data.get("enhanced_function_spec_hash"),
    "function_spec_before_replay_hash": data.get("function_spec_before_replay_hash"),
    "replay_arguments": data.get("replay_arguments"),
    "replay": replay or data.get("replay"),
    "replay_uses_enhanced_function": replay_uses_enhanced_function,
    "replay_uses_enhanced_function_source": "receiver" if explicit_enhanced_replay is not None else "enhanced_spec_after_update_function",
    "replay_skipped_reason": data.get("replay_skipped_reason"),
    "error_message": data.get("error_message") or (data.get("convert") or {}).get("error_message"),
}
print(json.dumps(summary, ensure_ascii=False, indent=2))
sys.exit(0 if spec else 1)
PY
