#!/usr/bin/env bash
# Strict device smoke for:
#   manual recording -> RunLog -> native reusable Function registration.
#
# This script intentionally fails when no Android device is connected. It is the
# repeatable evidence gate for the real phone manual-recording loop; local unit
# tests are not a substitute for this smoke.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

DEVICE_SERIAL="${ANDROID_SERIAL:-}"
PACKAGE_NAME="${OOB_PACKAGE_NAME:-cn.com.omnimind.bot.debug}"
DESCRIPTION="${OOB_MANUAL_RECORDING_SMOKE_DESCRIPTION:-OOB manual recording reusable Function smoke}"
TIMEOUT_SECONDS="${OOB_MANUAL_RECORDING_SMOKE_TIMEOUT_SECONDS:-60}"
SETTLE_SECONDS="${OOB_MANUAL_RECORDING_SMOKE_SETTLE_SECONDS:-1}"
RAW_JSON=0

usage() {
  cat <<'EOF'
Usage:
  scripts/oob-manual-recording-function-smoke.sh --device SERIAL

Validates the OOB-native manual recording path on a real debug-installed device:
  1. starts DebugHumanRunRecordingReceiver
  2. records deterministic overlay-touch click and swipe gestures
  3. finishes the human recording
  4. verifies the returned RunLog was converted and registered as a reusable Function

Options:
  --device SERIAL       Target adb device. Defaults to ANDROID_SERIAL; if
                        unset, exactly one connected device is required.
  --package NAME        Debug app package. Default: cn.com.omnimind.bot.debug.
  --description TEXT    Recording description/name.
  --timeout SECONDS     Wait timeout per phase. Default: 60.
  --settle SECONDS      Delay after opening settings page. Default: 1.
  --raw-json            Print full JSON payloads in addition to summaries.
  --help                Show this help.

Preconditions:
  - develop debug APK is installed
  - OOB accessibility and overlay permissions are enabled
  - the device is unlocked
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
    --description|--name)
      DESCRIPTION="$2"
      shift 2
      ;;
    --description=*|--name=*)
      DESCRIPTION="${1#*=}"
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
ACTION="cn.com.omnimind.bot.debug.HUMAN_RUN_RECORDING"
RECEIVER="$PACKAGE_NAME/.DebugHumanRunRecordingReceiver"
START_FILE="files/debug-human-run-recording-start.json"
GESTURE_FILE="files/debug-human-run-recording-gesture.json"
RESULT_FILE="files/debug-human-run-recording-result.json"

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/oob-manual-recording-smoke.XXXXXX")"
START_JSON="$WORK_DIR/start.json"
CLICK_JSON="$WORK_DIR/click.json"
SWIPE_JSON="$WORK_DIR/swipe.json"
RESULT_JSON="$WORK_DIR/result.json"

cleanup() {
  rm -rf "$WORK_DIR"
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

is_device_locked() {
  local trust window
  window="$("${ADB[@]}" shell dumpsys window 2>/dev/null | tr -d '\r' || true)"
  if grep -Eq 'isKeyguardShowing=true|mDreamingLockscreen=true|mIsShowing=true' <<<"$window"; then
    return 0
  fi
  trust="$("${ADB[@]}" shell dumpsys trust 2>/dev/null | tr -d '\r' || true)"
  if grep -Eq '\(current\).*deviceLocked=1' <<<"$trust"; then
    return 0
  fi
  return 1
}

check_accessibility_ready() {
  local enabled services component
  component="$PACKAGE_NAME/com.google.android.accessibility.selecttospeak.SelectToSpeakService"
  enabled="$("${ADB[@]}" shell settings get secure accessibility_enabled 2>/dev/null | tr -d '\r' || true)"
  services="$("${ADB[@]}" shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r' || true)"
  if [[ "$enabled" != "1" || "$services" != *"$component"* ]]; then
    cat >&2 <<EOF
OOB accessibility service is not enabled for $PACKAGE_NAME.
  accessibility_enabled=${enabled:-<empty>}
  enabled_accessibility_services=${services:-<empty>}
Expected service: $component
EOF
    return 1
  fi
}

preflight_device_ready() {
  if is_device_locked; then
    cat >&2 <<EOF
Device $DEVICE_SERIAL is locked or showing keyguard. Unlock it before running
manual recording smoke; otherwise Accessibility and overlay gesture capture
cannot be verified reliably.
EOF
    return 1
  fi
  check_accessibility_ready
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
    sleep 1
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
else:
    conversion = data.get("conversion") if isinstance(data.get("conversion"), dict) else {}
    spec = data.get("function_spec") if isinstance(data.get("function_spec"), dict) else {}
    print(f"== {label} summary ==")
    print(json.dumps({
        "success": data.get("success"),
        "phase": data.get("phase"),
        "run_id": data.get("run_id"),
        "recording_success": data.get("recording_success"),
        "conversion_success": data.get("conversion_success"),
        "function_registered": data.get("function_registered"),
        "function_id": data.get("function_id") or spec.get("function_id") or conversion.get("function_id"),
        "action_count": data.get("action_count"),
        "error": data.get("error_message") or conversion.get("error_message"),
    }, ensure_ascii=False, indent=2))
PY
}

validate_start() {
  python3 - "$1" <<'PY'
import json
import sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
checks = [
    (data.get("success") is True, "success=true"),
    (data.get("phase") == "started", "phase=started"),
    (data.get("recording_active") is True, "recording_active=true"),
]
failed = [label for ok, label in checks if not ok]
if failed:
    print(json.dumps(data, ensure_ascii=False, indent=2), file=sys.stderr)
    raise SystemExit("manual recording start failed checks: " + ", ".join(failed))
PY
}

validate_gesture() {
  python3 - "$1" "$2" <<'PY'
import json
import sys
path, expected_action = sys.argv[1], sys.argv[2]
data = json.load(open(path, encoding="utf-8"))
gesture = data.get("gesture") if isinstance(data.get("gesture"), dict) else {}
checks = [
    (data.get("success") is True, "success=true"),
    (data.get("phase") == "gesture_recorded", "phase=gesture_recorded"),
    (data.get("executed") is True, "executed=true"),
    (data.get("recorded") is True, "recorded=true"),
    (gesture.get("action_name") == expected_action, f"gesture.action_name={expected_action}"),
]
failed = [label for ok, label in checks if not ok]
if failed:
    print(json.dumps(data, ensure_ascii=False, indent=2), file=sys.stderr)
    raise SystemExit("manual recording gesture failed checks: " + ", ".join(failed))
PY
}

validate_finish() {
  python3 - "$1" <<'PY'
import json
import sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
conversion = data.get("conversion") if isinstance(data.get("conversion"), dict) else {}
run_log = data.get("run_log") if isinstance(data.get("run_log"), dict) else {}
spec = data.get("function_spec") if isinstance(data.get("function_spec"), dict) else {}
metadata = spec.get("metadata") if isinstance(spec.get("metadata"), dict) else {}
cards = run_log.get("cards") if isinstance(run_log.get("cards"), list) else []
function_id = data.get("function_id") or data.get("created_function_id") or spec.get("function_id")
action_names = []
for card in cards:
    if not isinstance(card, dict):
        continue
    action_names.append(card.get("tool_name") or card.get("action_name") or card.get("action_type"))
checks = [
    (data.get("success") is True, "success=true"),
    (data.get("recording_success") is True, "recording_success=true"),
    (data.get("conversion_success") is True, "conversion_success=true"),
    (data.get("function_registered") is True, "function_registered=true"),
    (conversion.get("success") is True, "conversion.success=true"),
    (conversion.get("registered") is True, "conversion.registered=true"),
    (bool(function_id), "function_id present"),
    (bool(spec), "function_spec present"),
    (metadata.get("visibility") == "manual_function", "metadata.visibility=manual_function"),
    (metadata.get("enhancement_policy") == "offline_only", "metadata.enhancement_policy=offline_only"),
    (int(data.get("action_count") or 0) >= 2, "action_count>=2"),
    (len(cards) >= 2, "run_log.cards>=2"),
    ("click" in action_names, "click action recorded"),
    (any(name in {"swipe", "scroll"} for name in action_names), "swipe/scroll action recorded"),
]
failed = [label for ok, label in checks if not ok]
if failed:
    print(json.dumps(data, ensure_ascii=False, indent=2), file=sys.stderr)
    raise SystemExit("manual recording finish failed checks: " + ", ".join(failed))
PY
}

device_size() {
  local size width height
  size="$("${ADB[@]}" shell wm size | tr -d '\r' | awk -F': ' '/Physical size/ {print $2; exit}')"
  width="${size%x*}"
  height="${size#*x}"
  if [[ -z "${width// }" || -z "${height// }" || "$width" == "$height" ]]; then
    width=720
    height=1280
  fi
  echo "$width $height"
}

send_recording_op() {
  local op="$1"
  shift
  "${ADB[@]}" shell am broadcast \
    -a "$ACTION" \
    -n "$RECEIVER" \
    --es op "$op" \
    "$@" >/dev/null
}

send_gesture() {
  local output_path="$1"
  shift
  clear_app_file "$GESTURE_FILE"
  send_recording_op gesture "$@"
  wait_for_result_file "$GESTURE_FILE" "gesture" "$output_path"
}

echo "== OOB manual recording Function smoke =="
echo "device=$DEVICE_SERIAL"
echo "package=$PACKAGE_NAME"

if ! "${ADB[@]}" get-state >/dev/null 2>&1; then
  echo "Device is not available: $DEVICE_SERIAL" >&2
  exit 2
fi
if ! "${ADB[@]}" shell run-as "$PACKAGE_NAME" pwd >/dev/null 2>&1; then
  echo "Cannot run-as $PACKAGE_NAME. Install the develop debug APK first." >&2
  exit 2
fi
preflight_device_ready

clear_app_file "$START_FILE"
clear_app_file "$GESTURE_FILE"
clear_app_file "$RESULT_FILE"

"${ADB[@]}" shell am start -a android.settings.SETTINGS >/dev/null 2>&1 || true
sleep "$SETTLE_SECONDS"

description_b64="$(base64_text "$DESCRIPTION")"
send_recording_op start \
  --es nameBase64 "$description_b64" \
  --es descriptionBase64 "$description_b64" \
  --ez debugScreenshots false
wait_for_result_file "$START_FILE" "manual recording start" "$START_JSON"
print_summary "manual recording start" "$START_JSON"
validate_start "$START_JSON"

read -r width height < <(device_size)
tap_x=$((width / 2))
tap_y=$((height / 2))
swipe_x=$((width / 2))
swipe_y1=$((height * 4 / 5))
swipe_y2=$((height / 3))

send_gesture "$CLICK_JSON" \
  --es actionName click \
  --es x "$tap_x" \
  --es y "$tap_y" \
  --es durationMs 100 \
  --es displayWidth "$width" \
  --es displayHeight "$height"
print_summary "manual click" "$CLICK_JSON"
validate_gesture "$CLICK_JSON" "click"

send_gesture "$SWIPE_JSON" \
  --es actionName swipe \
  --es x1 "$swipe_x" \
  --es y1 "$swipe_y1" \
  --es x2 "$swipe_x" \
  --es y2 "$swipe_y2" \
  --es durationMs 500 \
  --es direction up \
  --es displayWidth "$width" \
  --es displayHeight "$height"
print_summary "manual swipe" "$SWIPE_JSON"
validate_gesture "$SWIPE_JSON" "swipe"

clear_app_file "$RESULT_FILE"
send_recording_op finish
wait_for_result_file "$RESULT_FILE" "manual recording finish" "$RESULT_JSON"
print_summary "manual recording finish" "$RESULT_JSON"
validate_finish "$RESULT_JSON"

echo "== Manual recording Function smoke passed =="
