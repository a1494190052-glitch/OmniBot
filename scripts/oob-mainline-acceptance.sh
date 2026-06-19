#!/usr/bin/env bash
# Mainline acceptance gate for the current OOB OmniFlow work.
#
# This script intentionally composes existing narrow checks instead of adding a
# second execution path. Device checks prove live Android behavior; offline
# checks prove schema/contracts and UI/l10n routing.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

DEVICE_SERIAL="${ANDROID_SERIAL:-}"
PACKAGE_NAME="${OOB_PACKAGE_NAME:-cn.com.omnimind.bot.debug}"
OUTPUT_DIR="${OOB_MAINLINE_ACCEPTANCE_OUTPUT_DIR:-runtime/mainline-acceptance/$(date +%Y%m%d-%H%M%S)}"
SKIP_DEVICE=0
OFFLINE_SEED=1
RUN_INSTALL=0
RAW_JSON=0
PYTHON_BIN="${PYTHON_BIN:-python3}"
if [[ -z "${FLUTTER_BIN:-}" ]]; then
  if command -v flutter >/dev/null 2>&1; then
    FLUTTER_BIN="$(command -v flutter)"
  else
    FLUTTER_BIN="/Users/wuzewen/Desktop/项目与代码/flutter/bin/flutter"
  fi
fi

usage() {
  cat <<'EOF'
Usage:
  scripts/oob-mainline-acceptance.sh --device SERIAL

Runs the current OOB OmniFlow mainline gates:
  1. Android unit tests for manual recording recovery and VLM recall execution
  2. Flutter UI/l10n tests for reusable command/memory surfaces
  3. OmniFlow Python offline contract smoke
  4. real-device manual recording Function smoke
  5. real-device VLM RunLog -> auto-register -> recall-hit -> fast replay smoke

Options:
  --device SERIAL       Target adb device. Defaults to ANDROID_SERIAL; if unset,
                        device gates fail unless --skip-device is used.
  --package NAME        Debug app package. Default: cn.com.omnimind.bot.debug.
  --output-dir DIR      Directory for reports and device smoke artifacts.
  --install             Build and install develop debug APK before device gates.
  --skip-device         Run only offline/unit/UI/contract gates.
  --offline-seed        Use debug offline seed for the VLM recall loop. Default.
  --online-vlm          Use the configured online VLM provider for phase 1.
  --raw-json            Print the final report JSON only.
  --help                Show this help.

Notes:
  - --offline-seed proves the native registration/recall/replay loop, not online
    model accuracy.
  - --online-vlm requires a valid VLM provider binding and token.
  - The script does not create a PR or push anything.
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
    --output-dir)
      OUTPUT_DIR="$2"
      shift 2
      ;;
    --output-dir=*)
      OUTPUT_DIR="${1#--output-dir=}"
      shift
      ;;
    --install)
      RUN_INSTALL=1
      shift
      ;;
    --skip-device)
      SKIP_DEVICE=1
      shift
      ;;
    --offline-seed)
      OFFLINE_SEED=1
      shift
      ;;
    --online-vlm)
      OFFLINE_SEED=0
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

mkdir -p "$OUTPUT_DIR"
REPORT_JSON="$OUTPUT_DIR/mainline-acceptance-report.json"
REPORT_MD="$OUTPUT_DIR/mainline-acceptance-report.md"
TIMING_TSV="$OUTPUT_DIR/timing.tsv"
: > "$TIMING_TSV"

status_json_items=()
overall_success=1

json_escape() {
  "$PYTHON_BIN" - "$1" <<'PY'
import json
import sys
print(json.dumps(sys.argv[1], ensure_ascii=False))
PY
}

append_gate() {
  local name="$1"
  local status="$2"
  local duration_ms="$3"
  local log_path="$4"
  local note="${5:-}"
  local exit_code="${6:-null}"
  local blocker_reason="${7:-}"
  status_json_items+=("{\"name\":$(json_escape "$name"),\"status\":$(json_escape "$status"),\"duration_ms\":$duration_ms,\"log_path\":$(json_escape "$log_path"),\"note\":$(json_escape "$note"),\"exit_code\":$exit_code,\"blocker_reason\":$(json_escape "$blocker_reason")}")
  printf '%s\t%s\n' "$name" "$duration_ms" >> "$TIMING_TSV"
  if [[ "$status" == "failed" ]]; then
    overall_success=0
  fi
}

classify_gate_failure() {
  local name="$1"
  local exit_code="$2"
  local log_path="$3"
  if [[ "$name" == "vlm_recall_loop_device_smoke" ]]; then
    if [[ "$exit_code" -eq 5 ]] ||
      grep -qiE 'provider_tool_call_contract_violation|missing native tool[_ -]?call|without native tool call|returned text instead of native tool[_ -]?call|text output without native tool[_ -]?call' "$log_path"; then
      echo "provider_tool_call_contract_violation"
      return 0
    fi
    if [[ "$exit_code" -eq 4 ]] ||
      grep -qiE 'provider_auth_or_configuration_failed|model provider configuration/authentication|authentication error|invalid proxy server token|unable to find token|unauthorized|api key' "$log_path"; then
      echo "provider_auth_or_configuration_failed"
      return 0
    fi
  fi
  echo ""
}

run_gate() {
  local name="$1"
  local log_path="$OUTPUT_DIR/${name//[^A-Za-z0-9_.-]/_}.log"
  shift
  local start_ms end_ms status note exit_code blocker_reason
  start_ms="$("$PYTHON_BIN" - <<'PY'
import time
print(int(time.time() * 1000))
PY
)"
  echo "== gate: $name =="
  if "$@" >"$log_path" 2>&1; then
    status="passed"
    note=""
    exit_code=0
    blocker_reason=""
  else
    exit_code="$?"
    status="failed"
    note="see log"
    blocker_reason="$(classify_gate_failure "$name" "$exit_code" "$log_path")"
    if [[ -n "$blocker_reason" ]]; then
      note="$blocker_reason; see log"
    fi
  fi
  end_ms="$("$PYTHON_BIN" - <<'PY'
import time
print(int(time.time() * 1000))
PY
)"
  append_gate "$name" "$status" "$((end_ms - start_ms))" "$log_path" "$note" "$exit_code" "$blocker_reason"
  if [[ "$status" == "failed" ]]; then
    echo "gate failed: $name (log: $log_path)" >&2
  fi
  return 0
}

run_android_unit_gates() {
  ./gradlew --no-daemon :app:testDevelopStandardDebugUnitTest \
    --tests cn.com.omnimind.bot.runlog.ManualRecordingRunLogRecoveryTest \
    --tests cn.com.omnimind.bot.runlog.OobVlmRunLogAutoRegistrarTest \
    --tests cn.com.omnimind.bot.vlm.VlmToolCoordinatorRecallExecutionTest \
    -x :app:flutterPubGetForWebBundle
}

run_flutter_ui_gates() {
  (cd ui && "$FLUTTER_BIN" test --no-pub \
    test/l10n/app_text_localizer_test.dart \
    test/features/home/widgets/home_drawer_test.dart \
    test/features/home/pages/command_overlay/command_overlay_test.dart \
    test/features/home/pages/command_overlay/widgets/chat_input_area_test.dart \
    test/features/task/pages/execution_history/function_library_page_test.dart \
    test/features/home/pages/settings/workspace_memory_setting_page_test.dart \
    test/features/home/pages/chat/utils/omniflow_tool_profile_router_test.dart)
}

run_install_gate() {
  ./gradlew --no-daemon :app:installDevelopStandardDebug \
    -Ptarget=lib/main_standard.dart \
    -x :app:flutterPubGetForWebBundle
}

resolve_device() {
  if [[ -n "${DEVICE_SERIAL// }" ]]; then
    return 0
  fi
  local connected_devices=()
  while IFS= read -r serial; do
    [[ -n "${serial// }" ]] && connected_devices+=("$serial")
  done < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
  if [[ "${#connected_devices[@]}" -eq 1 ]]; then
    DEVICE_SERIAL="${connected_devices[0]}"
    return 0
  fi
  echo "Expected exactly one connected adb device, found ${#connected_devices[@]}. Pass --device or use --skip-device." >&2
  return 1
}

run_manual_recording_gate() {
  scripts/oob-manual-recording-function-smoke.sh \
    --device "$DEVICE_SERIAL" \
    --package "$PACKAGE_NAME" \
    --timeout 120 \
    --settle 2
}

run_vlm_recall_gate() {
  local args=(
    scripts/oob-vlm-recall-loop-smoke.sh
    --device "$DEVICE_SERIAL"
    --package "$PACKAGE_NAME"
    --goal "打开网络设置"
    --target-package com.android.settings
    --output-dir "$OUTPUT_DIR/vlm-recall-loop"
    --timeout 240
    --settle 2
  )
  if [[ "$OFFLINE_SEED" -eq 1 ]]; then
    args+=(--offline-seed)
  fi
  "${args[@]}"
}

run_gate android_unit_gates run_android_unit_gates
run_gate flutter_ui_l10n_gates run_flutter_ui_gates
run_gate omniflow_python_offline_contract "$PYTHON_BIN" scripts/oob-omniflow-python-offline-contract-smoke.py

device_status="required"
device_note=""
if [[ "$SKIP_DEVICE" -eq 1 ]]; then
  device_status="skipped"
  device_note="--skip-device"
  append_gate device_gates "$device_status" 0 "" "$device_note"
else
  if resolve_device; then
    if [[ "$RUN_INSTALL" -eq 1 ]]; then
      run_gate install_develop_debug_apk run_install_gate
    fi
    run_gate manual_recording_device_smoke run_manual_recording_gate
    run_gate vlm_recall_loop_device_smoke run_vlm_recall_gate
  else
    append_gate device_gates "failed" 0 "" "no usable adb device"
  fi
fi

gates_json="$(IFS=,; echo "${status_json_items[*]}")"
mode="online_vlm"
if [[ "$OFFLINE_SEED" -eq 1 ]]; then
  mode="offline_seed_native_loop"
fi

"$PYTHON_BIN" - "$REPORT_JSON" "$REPORT_MD" "$overall_success" "$OUTPUT_DIR" "$DEVICE_SERIAL" "$mode" "$gates_json" <<'PY'
from __future__ import annotations

import json
from pathlib import Path
import sys
from datetime import datetime, timezone

report_path = Path(sys.argv[1])
markdown_path = Path(sys.argv[2])
overall_success = sys.argv[3] == "1"
output_dir = sys.argv[4]
device = sys.argv[5]
vlm_mode = sys.argv[6]
gates = json.loads("[" + sys.argv[7] + "]") if sys.argv[7] else []
device_gates = [
    gate for gate in gates
    if gate["name"] in {
        "manual_recording_device_smoke",
        "vlm_recall_loop_device_smoke",
        "device_gates",
    }
]
offline_seed = vlm_mode == "offline_seed_native_loop"
report = {
    "schema_version": "oob.mainline_acceptance_report.v1",
    "generated_at": datetime.now(timezone.utc).isoformat(),
    "success": overall_success,
    "device": device or None,
    "vlm_mode": vlm_mode,
    "accuracy_scope": (
        "native_registration_recall_replay_only"
        if offline_seed else "online_vlm_plus_native_registration_recall_replay"
    ),
    "output_dir": output_dir,
    "gates": gates,
    "summary": {
        "gate_count": len(gates),
        "passed": sum(1 for gate in gates if gate["status"] == "passed"),
        "failed": sum(1 for gate in gates if gate["status"] == "failed"),
        "skipped": sum(1 for gate in gates if gate["status"] == "skipped"),
        "device_gate_count": len(device_gates),
        "offline_seed": offline_seed,
    },
    "notes": (
        [
            "offline_seed mode validates the native OOB RunLog/register/recall/replay loop, not online model accuracy.",
            "Use --online-vlm with a configured provider that returns native OpenAI tool_calls to measure online model accuracy.",
            "Python OmniFlow remains an offline/dev/eval adapter; live phone execution stays in Kotlin.",
        ]
        if offline_seed
        else [
            "online_vlm mode measures the configured provider plus native OOB registration/recall/replay.",
            "provider_auth_or_configuration_failed and provider_tool_call_contract_violation are provider/environment blockers, not reasons to re-enable legacy text action parsing.",
            "Python OmniFlow remains an offline/dev/eval adapter; live phone execution stays in Kotlin.",
        ]
    ),
}
report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

lines = [
    "# OOB Mainline Acceptance Report",
    "",
    f"- success: `{overall_success}`",
    f"- device: `{device or ''}`",
    f"- vlm_mode: `{vlm_mode}`",
    f"- accuracy_scope: `{report['accuracy_scope']}`",
    f"- output_dir: `{output_dir}`",
    "",
    "## Gates",
    "",
]
for gate in gates:
    lines.append(
        f"- {gate['name']}: `{gate['status']}` ({gate['duration_ms']} ms)"
        + (f" - {gate['note']}" if gate.get("note") else "")
    )
markdown_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
print(json.dumps(report, ensure_ascii=False, indent=2))
PY

if [[ "$RAW_JSON" -ne 1 ]]; then
  echo "mainline_acceptance_report=$REPORT_JSON"
  echo "mainline_acceptance_markdown=$REPORT_MD"
fi

exit "$((overall_success == 1 ? 0 : 1))"
