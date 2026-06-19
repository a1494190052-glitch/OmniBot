#!/usr/bin/env bash
# Strict device smoke for:
#   vlm_task -> RunLog -> Function auto/register -> recall hit -> native replay.
#
# This script intentionally fails when no Android device is connected. It is the
# repeatable evidence gate for the real phone loop; local unit tests are not a
# substitute for this smoke.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

DEVICE_SERIAL="${ANDROID_SERIAL:-}"
PACKAGE_NAME="${OOB_PACKAGE_NAME:-cn.com.omnimind.bot.debug}"
TARGET_PACKAGE="${OOB_VLM_SMOKE_TARGET_PACKAGE:-com.android.settings}"
START_INTENT_ACTION="${OOB_VLM_SMOKE_START_INTENT_ACTION:-android.settings.SETTINGS}"
GOAL="${OOB_VLM_SMOKE_GOAL:-打开网络设置}"
MAX_STEPS="${OOB_VLM_SMOKE_MAX_STEPS:-3}"
TIMEOUT_SECONDS="${OOB_VLM_SMOKE_TIMEOUT_SECONDS:-180}"
SETTLE_SECONDS="${OOB_VLM_SMOKE_SETTLE_SECONDS:-2}"
PROFILE_ID="${OOB_PROVIDER_PROFILE_ID:-}"
MODEL_ID="${OMNIMIND_MODEL:-${OPENAI_MODEL:-}}"
SKILL_ID="${OOB_VLM_SMOKE_SKILL_ID:-vlm-android-gui}"
OUTPUT_DIR="${OOB_VLM_SMOKE_OUTPUT_DIR:-}"
RAW_JSON=0
OFFLINE_SEED="${OOB_VLM_SMOKE_OFFLINE_SEED:-0}"

usage() {
  cat <<'EOF'
Usage:
  scripts/oob-vlm-recall-loop-smoke.sh --device SERIAL

Validates the OOB-native VLM recall loop on a real debug-installed device:
  1. first vlm_task runs with recall disabled and registers its successful RunLog
  2. native recall returns a strict hit from the same start page
  3. RUN_VLM_RECALL_HIT executes that hit through Kotlin replay
  4. a second vlm_task short-circuits through omniflow_recall_hit
  5. CONVERT_RUNLOG_AND_RUN_FUNCTION --ez enhance true remains offline-only

Options:
  --device SERIAL           Target adb device. Defaults to ANDROID_SERIAL; if
                            unset, exactly one connected device is required.
  --package NAME            Debug app package. Default: cn.com.omnimind.bot.debug.
  --goal TEXT               Goal used for both VLM runs. Default: 打开网络设置.
  --target-package PACKAGE  App package to start between phases. Default:
                            com.android.settings.
  --start-intent-action A   Intent action used to reset the start page. Default:
                            android.settings.SETTINGS.
  --max-steps N             First/second VLM max steps. Default: 3.
  --timeout SECONDS         Wait timeout per phase. Default: 180.
  --settle SECONDS          Delay after opening the start page. Default: 2.
  --profile-id ID           Optional model provider profile id for the receiver.
  --model-id ID             Optional model id for the receiver.
  --skill-id ID             Built-in skill guidance id. Default: vlm-android-gui.
  --output-dir DIR          Keep all phase JSON files and write
                            vlm-accuracy-report.{json,md} in DIR.
  --offline-seed            Debug-only: seed a successful vlm_task RunLog from
                            the live page instead of calling the online VLM in
                            phase 1. This validates native auto-register,
                            recall, replay, and phase-4 fast path without
                            provider credentials; it is not a model accuracy
                            measurement.
  --raw-json                Print full JSON payloads in addition to summaries.
  --help                    Show this help.

Preconditions:
  - develop debug APK is installed
  - OOB accessibility and overlay permissions are enabled
  - a working VLM model binding is configured for the first run
  - the device is unlocked

Provider/authentication failures on the first VLM call are reported as an
environment blocker before recall/replay validation starts.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --device|--target)
      DEVICE_SERIAL="$2"
      shift 2
      ;;
    --device=*)
      DEVICE_SERIAL="${1#--device=}"
      shift
      ;;
    --package)
      PACKAGE_NAME="$2"
      shift 2
      ;;
    --package=*)
      PACKAGE_NAME="${1#--package=}"
      shift
      ;;
    --goal)
      GOAL="$2"
      shift 2
      ;;
    --goal=*)
      GOAL="${1#--goal=}"
      shift
      ;;
    --target-package)
      TARGET_PACKAGE="$2"
      shift 2
      ;;
    --target-package=*)
      TARGET_PACKAGE="${1#--target-package=}"
      shift
      ;;
    --start-intent-action)
      START_INTENT_ACTION="$2"
      shift 2
      ;;
    --start-intent-action=*)
      START_INTENT_ACTION="${1#--start-intent-action=}"
      shift
      ;;
    --max-steps)
      MAX_STEPS="$2"
      shift 2
      ;;
    --max-steps=*)
      MAX_STEPS="${1#--max-steps=}"
      shift
      ;;
    --timeout)
      TIMEOUT_SECONDS="$2"
      shift 2
      ;;
    --timeout=*)
      TIMEOUT_SECONDS="${1#--timeout=}"
      shift
      ;;
    --settle)
      SETTLE_SECONDS="$2"
      shift 2
      ;;
    --settle=*)
      SETTLE_SECONDS="${1#--settle=}"
      shift
      ;;
    --profile-id)
      PROFILE_ID="$2"
      shift 2
      ;;
    --profile-id=*)
      PROFILE_ID="${1#--profile-id=}"
      shift
      ;;
    --model-id|--model)
      MODEL_ID="$2"
      shift 2
      ;;
    --model-id=*|--model=*)
      MODEL_ID="${1#*=}"
      shift
      ;;
    --skill-id)
      SKILL_ID="$2"
      shift 2
      ;;
    --skill-id=*)
      SKILL_ID="${1#--skill-id=}"
      shift
      ;;
    --output-dir)
      OUTPUT_DIR="$2"
      shift 2
      ;;
    --output-dir=*)
      OUTPUT_DIR="${1#--output-dir=}"
      shift
      ;;
    --offline-seed)
      OFFLINE_SEED=1
      shift
      ;;
    --raw-json)
      RAW_JSON=1
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

case "$MAX_STEPS" in (*[!0-9]*|"") echo "--max-steps must be numeric" >&2; exit 2;; esac
case "$TIMEOUT_SECONDS" in (*[!0-9]*|"") echo "--timeout must be numeric" >&2; exit 2;; esac
case "$SETTLE_SECONDS" in (*[!0-9]*|"") echo "--settle must be numeric" >&2; exit 2;; esac

if [[ -z "${DEVICE_SERIAL// }" ]]; then
  connected_devices=()
  while IFS= read -r serial; do
    [[ -n "${serial// }" ]] && connected_devices+=("$serial")
  done < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
  if [[ "${#connected_devices[@]}" -ne 1 ]]; then
    echo "Expected exactly one connected adb device, found ${#connected_devices[@]}. Pass --device SERIAL." >&2
    exit 2
  fi
  DEVICE_SERIAL="${connected_devices[0]}"
fi

ADB=(adb -s "$DEVICE_SERIAL")
VLM_RECEIVER="$PACKAGE_NAME/cn.com.omnimind.bot.debug.DebugVlmRunLogReceiver"
RECALL_RECEIVER="$PACKAGE_NAME/cn.com.omnimind.bot.debug.DebugOobRecallReceiver"
RECALL_HIT_RECEIVER="$PACKAGE_NAME/cn.com.omnimind.bot.debug.DebugVlmRecallHitReceiver"
CONVERT_REPLAY_RECEIVER="$PACKAGE_NAME/cn.com.omnimind.bot.debug.DebugRunLogFunctionReplayReceiver"

VLM_RESULT_FILE="files/debug-vlm-runlog-result.json"
RECALL_RESULT_FILE="files/debug-oob-recall-result.json"
RECALL_HIT_RESULT_FILE="files/debug-vlm-recall-hit-result.json"
CONVERT_REPLAY_RESULT_FILE="files/debug-runlog-function-replay-result.json"

if [[ -n "${OUTPUT_DIR// }" ]]; then
  WORK_DIR="$OUTPUT_DIR"
  mkdir -p "$WORK_DIR"
  KEEP_WORK_DIR=1
else
  WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/oob-vlm-recall-smoke.XXXXXX")"
  KEEP_WORK_DIR=0
fi
FIRST_JSON="$WORK_DIR/first-vlm.json"
RECALL_JSON="$WORK_DIR/recall.json"
RECALL_HIT_JSON="$WORK_DIR/recall-hit.json"
SECOND_JSON="$WORK_DIR/second-vlm.json"
ENHANCE_JSON="$WORK_DIR/enhance-offline.json"
REPORT_JSON="$WORK_DIR/vlm-accuracy-report.json"
REPORT_MD="$WORK_DIR/vlm-accuracy-report.md"

cleanup() {
  if [[ "$KEEP_WORK_DIR" -eq 0 ]]; then
    rm -rf "$WORK_DIR"
  fi
}
trap cleanup EXIT

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

clear_app_file() {
  local path="$1"
  "${ADB[@]}" shell run-as "$PACKAGE_NAME" rm -f "$path" >/dev/null 2>&1 || true
}

wait_for_result_file() {
  local path="$1"
  local label="$2"
  local output_path="$3"
  local deadline=$((SECONDS + TIMEOUT_SECONDS))
  while (( SECONDS < deadline )); do
    read_app_file "$path" | tr -d '\r' > "$output_path"
    if [[ -s "$output_path" ]] && python3 - "$output_path" <<'PY' >/dev/null 2>&1
import json
import sys
json.load(open(sys.argv[1], encoding="utf-8"))
PY
    then
      return 0
    fi
    sleep 2
  done
  echo "Timed out waiting for $label result: $path" >&2
  return 1
}

print_summary() {
  local label="$1"
  local path="$2"
  python3 - "$label" "$path" "$RAW_JSON" <<'PY'
import json
import sys
label, path, raw = sys.argv[1], sys.argv[2], sys.argv[3] == "1"
data = json.load(open(path, encoding="utf-8"))
if raw:
    print(f"== {label} raw ==")
    print(json.dumps(data, ensure_ascii=False, indent=2))
    return_code = 0
else:
    convert = data.get("convert") if isinstance(data.get("convert"), dict) else {}
    recall = data.get("recall") if isinstance(data.get("recall"), dict) else {}
    outcome = data.get("outcome") if isinstance(data.get("outcome"), dict) else {}
    summary = {
        "success": data.get("success"),
        "phase": data.get("phase"),
        "run_id": data.get("run_id"),
        "function_id": (
            data.get("function_id")
            or convert.get("function_id")
            or convert.get("created_function_id")
            or ((convert.get("function_spec") or {}).get("function_id") if isinstance(convert.get("function_spec"), dict) else None)
        ),
        "runlog_success": data.get("runlog_success"),
        "runlog_card_count": data.get("runlog_card_count"),
        "convert_success": data.get("convert_success"),
        "recall_decision": recall.get("decision"),
        "execution_route": data.get("executionRoute") or outcome.get("executionRoute") or outcome.get("execution_route"),
        "enhancement_policy": data.get("enhancement_policy"),
        "error": data.get("error_message") or data.get("error"),
    }
    print(f"== {label} summary ==")
    print(json.dumps({k: v for k, v in summary.items() if v not in (None, "", [])}, ensure_ascii=False, indent=2))
PY
}

check_first_run_provider_blocker() {
  python3 - "$1" <<'PY'
import json
import sys

data = json.load(open(sys.argv[1], encoding="utf-8"))
if data.get("success") is True:
    raise SystemExit(0)

outcome = data.get("outcome") if isinstance(data.get("outcome"), dict) else {}
run_log = data.get("run_log") if isinstance(data.get("run_log"), dict) else {}
cards = run_log.get("cards") if isinstance(run_log.get("cards"), list) else []
texts = [
    outcome.get("message"),
    outcome.get("errorMessage"),
    data.get("error_message"),
    run_log.get("error_message"),
]
for card in cards[:3]:
    if not isinstance(card, dict):
        continue
    result = card.get("result") if isinstance(card.get("result"), dict) else {}
    params = card.get("params") if isinstance(card.get("params"), dict) else {}
    texts.extend([
        card.get("title"),
        result.get("message"),
        params.get("content"),
    ])

haystack = "\n".join(str(text) for text in texts if text).lower()
provider_markers = [
    "scene stream request failed(401)",
    "authentication error",
    "invalid proxy server token",
    "unable to find token",
    "unauthorized",
    "api key",
    "provider",
]
if not any(marker in haystack for marker in provider_markers):
    raise SystemExit(0)

binding = data.get("configured_binding") if isinstance(data.get("configured_binding"), dict) else {}
diagnostic = {
    "success": False,
    "phase": "first_vlm_provider_blocker",
    "reason": "provider_auth_or_configuration_failed",
    "run_id": data.get("run_id"),
    "outcome_status": outcome.get("status"),
    "profile_id": binding.get("profileId"),
    "model_id": binding.get("modelId"),
    "hint": "Configure a valid VLM model provider before running recall/replay smoke.",
}
print("First VLM run blocked by model provider configuration/authentication.", file=sys.stderr)
print(json.dumps({k: v for k, v in diagnostic.items() if v not in (None, "", [])}, ensure_ascii=False, indent=2), file=sys.stderr)
raise SystemExit(4)
PY
}

json_extract_first_run_ids() {
  python3 - "$1" <<'PY'
import json
import sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
convert = data.get("convert") if isinstance(data.get("convert"), dict) else {}
spec = convert.get("function_spec") if isinstance(convert.get("function_spec"), dict) else {}
result = convert.get("result") if isinstance(convert.get("result"), dict) else {}
result_spec = result.get("function_spec") if isinstance(result.get("function_spec"), dict) else {}
run_id = data.get("run_id") or convert.get("run_id") or ""
function_id = (
    convert.get("function_id")
    or convert.get("created_function_id")
    or spec.get("function_id")
    or result_spec.get("function_id")
    or ""
)
print(run_id)
print(function_id)
PY
}

validate_first_run() {
  python3 - "$1" <<'PY'
import json
import sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
convert = data.get("convert") if isinstance(data.get("convert"), dict) else {}
spec = convert.get("function_spec") if isinstance(convert.get("function_spec"), dict) else {}
result = convert.get("result") if isinstance(convert.get("result"), dict) else {}
if not spec and isinstance(result.get("function_spec"), dict):
    spec = result["function_spec"]
metadata = spec.get("metadata") if isinstance(spec.get("metadata"), dict) else {}
function_id = convert.get("function_id") or convert.get("created_function_id") or spec.get("function_id")
checks = [
    (data.get("success") is True, "success=true"),
    (data.get("runlog_success") is True, "runlog_success=true"),
    (int(data.get("runlog_card_count") or 0) > 0, "runlog_card_count>0"),
    (data.get("convert_success") is True or convert.get("success") is True, "convert_success=true"),
    (bool(function_id), "registered function_id present"),
    (metadata.get("enhancement_policy") == "offline_only", "metadata.enhancement_policy=offline_only"),
]
failed = [label for ok, label in checks if not ok]
if failed:
    print(json.dumps(data, ensure_ascii=False, indent=2), file=sys.stderr)
    raise SystemExit("first VLM run failed checks: " + ", ".join(failed))
PY
}

validate_recall() {
  python3 - "$1" "$2" <<'PY'
import json
import sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
expected_function_id = sys.argv[2]
recall = data.get("recall") if isinstance(data.get("recall"), dict) else {}
hit = recall.get("hit") if isinstance(recall.get("hit"), dict) else {}
checks = [
    (recall.get("success") is True, "recall.success=true"),
    (recall.get("decision") == "hit", "recall.decision=hit"),
    (hit.get("function_id") == expected_function_id, "recall.hit.function_id matches first run"),
]
failed = [label for ok, label in checks if not ok]
if failed:
    print(json.dumps(data, ensure_ascii=False, indent=2), file=sys.stderr)
    raise SystemExit("recall failed checks: " + ", ".join(failed))
PY
}

validate_recall_hit() {
  python3 - "$1" "$2" <<'PY'
import json
import sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
expected_function_id = sys.argv[2]
outcome = data.get("outcome") if isinstance(data.get("outcome"), dict) else {}
summary = outcome.get("omniflowExecutionSummary") if isinstance(outcome.get("omniflowExecutionSummary"), dict) else {}
route = data.get("executionRoute") or outcome.get("executionRoute") or ""
checks = [
    (data.get("success") is True, "success=true"),
    (data.get("phase") == "executed", "phase=executed"),
    (route.startswith("omniflow_recall_hit"), "executionRoute starts omniflow_recall_hit"),
    (expected_function_id in route, "executionRoute includes function_id"),
    (summary.get("success") is True, "omniflowExecutionSummary.success=true"),
]
failed = [label for ok, label in checks if not ok]
if failed:
    print(json.dumps(data, ensure_ascii=False, indent=2), file=sys.stderr)
    raise SystemExit("recall-hit execution failed checks: " + ", ".join(failed))
PY
}

validate_second_run() {
  python3 - "$1" "$2" <<'PY'
import json
import sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
expected_function_id = sys.argv[2]
outcome = data.get("outcome") if isinstance(data.get("outcome"), dict) else {}
summary = outcome.get("omniflowExecutionSummary") if isinstance(outcome.get("omniflowExecutionSummary"), dict) else {}
route = outcome.get("executionRoute") or outcome.get("execution_route") or ""
checks = [
    (data.get("success") is True, "success=true"),
    (route.startswith("omniflow_recall_hit"), "outcome.executionRoute starts omniflow_recall_hit"),
    (expected_function_id in route, "outcome.executionRoute includes function_id"),
    (summary.get("success") is True, "outcome.omniflowExecutionSummary.success=true"),
]
failed = [label for ok, label in checks if not ok]
if failed:
    print(json.dumps(data, ensure_ascii=False, indent=2), file=sys.stderr)
    raise SystemExit("second VLM fast path failed checks: " + ", ".join(failed))
PY
}

validate_enhance_offline() {
  python3 - "$1" <<'PY'
import json
import sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
enhance = data.get("enhance") if isinstance(data.get("enhance"), dict) else {}
checks = [
    (data.get("enhance_requested") is True, "enhance_requested=true"),
    (data.get("enhancement_policy") == "offline_only", "enhancement_policy=offline_only"),
    (enhance.get("policy") == "offline_only", "enhance.policy=offline_only"),
    (enhance.get("status") in {"queued", "skipped"}, "enhance.status queued/skipped"),
    (data.get("replay_uses_enhanced_function") is False, "replay_uses_enhanced_function=false"),
    ("enhanced_function_spec_hash" not in data, "no enhanced_function_spec_hash"),
]
failed = [label for ok, label in checks if not ok]
if failed:
    print(json.dumps(data, ensure_ascii=False, indent=2), file=sys.stderr)
    raise SystemExit("offline enhance failed checks: " + ", ".join(failed))
PY
}

reset_start_page() {
  "${ADB[@]}" shell am force-stop "$TARGET_PACKAGE" >/dev/null 2>&1 || true
  if ! "${ADB[@]}" shell am start -a "$START_INTENT_ACTION" >/dev/null 2>&1; then
    "${ADB[@]}" shell monkey -p "$TARGET_PACKAGE" 1 >/dev/null 2>&1 || true
  fi
  sleep "$SETTLE_SECONDS"
}

echo "== OOB VLM recall loop smoke =="
echo "device=$DEVICE_SERIAL"
echo "package=$PACKAGE_NAME"
echo "target_package=$TARGET_PACKAGE"
echo "goal=$GOAL"
echo "offline_seed=$OFFLINE_SEED"

if ! "${ADB[@]}" get-state >/dev/null 2>&1; then
  echo "Device is not available: $DEVICE_SERIAL" >&2
  exit 2
fi
if ! "${ADB[@]}" shell run-as "$PACKAGE_NAME" pwd >/dev/null 2>&1; then
  echo "Cannot run-as $PACKAGE_NAME. Install the develop debug APK first." >&2
  exit 2
fi

optional_model_args=()
if [[ -n "${PROFILE_ID// }" ]]; then
  optional_model_args+=(--es profileId "$PROFILE_ID")
fi
if [[ -n "${MODEL_ID// }" ]]; then
  optional_model_args+=(--es modelId "$MODEL_ID")
fi
if [[ -n "${SKILL_ID// }" ]]; then
  optional_model_args+=(--es skillId "$SKILL_ID")
fi
offline_seed_args=()
REQUEST_GOAL="$GOAL"
case "$OFFLINE_SEED" in
  1|true|TRUE|yes|YES|on|ON)
    offline_seed_args+=(--ez offlineSeed true)
    REQUEST_GOAL="vlmseed$(date +%s)$RANDOM"
    ;;
  0|false|FALSE|no|NO|off|OFF|"")
    ;;
  *)
    echo "--offline-seed/OOB_VLM_SMOKE_OFFLINE_SEED must be boolean" >&2
    exit 2
    ;;
esac
goal_b64="$(base64_text "$REQUEST_GOAL")"
if [[ "$REQUEST_GOAL" != "$GOAL" ]]; then
  echo "request_goal=$REQUEST_GOAL"
fi

if [[ "${#offline_seed_args[@]}" -gt 0 ]]; then
  echo "== Phase 1: offline-seeded VLM RunLog with recall disabled =="
else
  echo "== Phase 1: first VLM run with recall disabled =="
fi
reset_start_page
clear_app_file "$VLM_RESULT_FILE"
first_vlm_broadcast_args=(
  shell am broadcast
  -a cn.com.omnimind.bot.debug.RUN_VLM_RUNLOG \
  -n "$VLM_RECEIVER" \
  --es goalBase64 "$goal_b64" \
  --es packageName "$TARGET_PACKAGE" \
  --ei maxSteps "$MAX_STEPS" \
  --ez register true \
  --ez disableOmniFlowRecall true
)
if [[ "${#offline_seed_args[@]}" -gt 0 ]]; then
  first_vlm_broadcast_args+=("${offline_seed_args[@]}")
fi
if [[ "${#optional_model_args[@]}" -gt 0 ]]; then
  first_vlm_broadcast_args+=("${optional_model_args[@]}")
fi
"${ADB[@]}" "${first_vlm_broadcast_args[@]}" >/dev/null
wait_for_result_file "$VLM_RESULT_FILE" "first VLM run" "$FIRST_JSON"
print_summary "first VLM run" "$FIRST_JSON"
check_first_run_provider_blocker "$FIRST_JSON"
validate_first_run "$FIRST_JSON"
first_ids=()
while IFS= read -r value; do
  first_ids+=("$value")
done < <(json_extract_first_run_ids "$FIRST_JSON")
FIRST_RUN_ID="${first_ids[0]:-}"
FUNCTION_ID="${first_ids[1]:-}"
echo "first_run_id=$FIRST_RUN_ID"
echo "function_id=$FUNCTION_ID"

echo "== Phase 2: native recall strict hit =="
reset_start_page
clear_app_file "$RECALL_RESULT_FILE"
"${ADB[@]}" shell am broadcast \
  -a cn.com.omnimind.bot.debug.RUN_OOB_RECALL \
  -n "$RECALL_RECEIVER" \
  --es goalBase64 "$goal_b64" \
  --es current_package "$TARGET_PACKAGE" \
  --ez auto_execute true >/dev/null
wait_for_result_file "$RECALL_RESULT_FILE" "native recall" "$RECALL_JSON"
print_summary "native recall" "$RECALL_JSON"
validate_recall "$RECALL_JSON" "$FUNCTION_ID"

echo "== Phase 3: recall-hit-only native replay =="
reset_start_page
clear_app_file "$RECALL_HIT_RESULT_FILE"
"${ADB[@]}" shell am broadcast \
  -a cn.com.omnimind.bot.debug.RUN_VLM_RECALL_HIT \
  -n "$RECALL_HIT_RECEIVER" \
  --es goalBase64 "$goal_b64" \
  --es packageName "$TARGET_PACKAGE" \
  --ei timeoutSeconds "$TIMEOUT_SECONDS" >/dev/null
wait_for_result_file "$RECALL_HIT_RESULT_FILE" "recall-hit-only replay" "$RECALL_HIT_JSON"
print_summary "recall-hit-only replay" "$RECALL_HIT_JSON"
validate_recall_hit "$RECALL_HIT_JSON" "$FUNCTION_ID"

echo "== Phase 4: second vlm_task fast path =="
reset_start_page
clear_app_file "$VLM_RESULT_FILE"
second_vlm_broadcast_args=(
  shell am broadcast
  -a cn.com.omnimind.bot.debug.RUN_VLM_RUNLOG \
  -n "$VLM_RECEIVER" \
  --es goalBase64 "$goal_b64" \
  --es packageName "$TARGET_PACKAGE" \
  --ei maxSteps "$MAX_STEPS" \
  --ez startFromCurrent true
)
if [[ "${#optional_model_args[@]}" -gt 0 ]]; then
  second_vlm_broadcast_args+=("${optional_model_args[@]}")
fi
"${ADB[@]}" "${second_vlm_broadcast_args[@]}" >/dev/null
wait_for_result_file "$VLM_RESULT_FILE" "second VLM run" "$SECOND_JSON"
print_summary "second VLM run" "$SECOND_JSON"
validate_second_run "$SECOND_JSON" "$FUNCTION_ID"

echo "== Phase 5: enhance flag stays offline-only =="
reset_start_page
clear_app_file "$CONVERT_REPLAY_RESULT_FILE"
"${ADB[@]}" shell am broadcast \
  -a cn.com.omnimind.bot.debug.CONVERT_RUNLOG_AND_RUN_FUNCTION \
  -n "$CONVERT_REPLAY_RECEIVER" \
  --es run_id "$FIRST_RUN_ID" \
  --es goalBase64 "$goal_b64" \
  --ez run true \
  --ez enhance true >/dev/null
wait_for_result_file "$CONVERT_REPLAY_RESULT_FILE" "convert-and-replay enhance" "$ENHANCE_JSON"
print_summary "convert-and-replay enhance" "$ENHANCE_JSON"
validate_enhance_offline "$ENHANCE_JSON"

if [[ "$KEEP_WORK_DIR" -eq 1 ]]; then
  echo "== Phase 6: write VLM accuracy report =="
  scripts/oob-vlm-accuracy-report.py \
    --smoke-dir "$WORK_DIR" \
    --case-name "$GOAL" \
    --output "$REPORT_JSON" \
    --markdown "$REPORT_MD" \
    --strict >/dev/null
  echo "accuracy_report=$REPORT_JSON"
  echo "accuracy_markdown=$REPORT_MD"
fi

echo "== VLM recall loop smoke passed =="
