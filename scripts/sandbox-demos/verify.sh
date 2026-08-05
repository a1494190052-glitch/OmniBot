#!/usr/bin/env bash
set -euo pipefail

FIXTURE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PACKAGE="cn.com.omnimind.bot.debug"
COMPONENT="$PACKAGE/cn.com.omnimind.bot.debug.DebugSandboxProjectReceiver"
ACTION="cn.com.omnimind.bot.debug.SANDBOX_PROJECT"
RESULT_FILE="files/debug-sandbox-project-result.json"
WORKSPACE_PREFIX="sandbox-demos"
REMOTE_STAGING="/data/local/tmp/openomnibot-sandbox-demos"
DEVICE=""

usage() {
  echo "Usage: $0 --device <adb-serial>"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --device)
      DEVICE="${2:-}"
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

if [[ -z "$DEVICE" ]]; then
  echo "--device is required" >&2
  exit 2
fi

adb -s "$DEVICE" get-state >/dev/null
adb -s "$DEVICE" shell pm path "$PACKAGE" >/dev/null

encode_file() {
  base64 < "$1" | tr -d '\r\n'
}

encode_text() {
  printf '%s' "$1" | base64 | tr -d '\r\n'
}

wait_for_result() {
  local attempts=0
  local result=""
  while [[ $attempts -lt 480 ]]; do
    if adb -s "$DEVICE" shell run-as "$PACKAGE" test -f "$RESULT_FILE"; then
      result="$(adb -s "$DEVICE" shell run-as "$PACKAGE" cat "$RESULT_FILE" | tr -d '\r')"
      printf '%s\n' "$result"
      if ! printf '%s' "$result" | python3 -c 'import json,sys; raise SystemExit(0 if json.load(sys.stdin).get("success") is True else 1)'; then
        return 1
      fi
      return 0
    fi
    sleep 0.25
    attempts=$((attempts + 1))
  done
  echo "Timed out waiting for $RESULT_FILE" >&2
  return 1
}

send_broadcast() {
  adb -s "$DEVICE" shell run-as "$PACKAGE" rm -f "$RESULT_FILE"
  adb -s "$DEVICE" shell am broadcast -a "$ACTION" -n "$COMPONENT" "$@" >/dev/null
  wait_for_result
}

publish_fixture() {
  local fixture="$1"
  echo
  echo "Publishing $fixture"
  send_broadcast \
    --es operation publish \
    --es sourcePath "$WORKSPACE_PREFIX/$fixture" \
    --es manifestBase64 "$(encode_file "$FIXTURE_ROOT/$fixture/manifest.json")"
}

invoke_tool() {
  local plugin_id="$1"
  local tool_name="$2"
  local arguments="$3"
  echo
  echo "Invoking $tool_name"
  send_broadcast \
    --es operation invoke \
    --es pluginId "$plugin_id" \
    --es toolName "$tool_name" \
    --es argumentsBase64 "$(encode_text "$arguments")"
}

invoke_agent() {
  local message="$1"
  local expected_tools="$2"
  local result
  echo
  echo "Agent: $message"
  result="$(send_broadcast \
    --es operation agent \
    --es messageBase64 "$(encode_text "$message")" \
    --el timeoutMillis 120000)"
  printf '%s\n' "$result"
  EXPECTED_TOOLS="$expected_tools" python3 -c '
import json, os, sys
payload = json.load(sys.stdin)
expected = [name for name in os.environ["EXPECTED_TOOLS"].split(",") if name]
called = [
    event.get("toolName")
    for event in payload.get("events", [])
    if event.get("type") == "tool_complete"
]
missing = [name for name in expected if name not in called]
if missing:
    raise SystemExit(f"Agent did not call expected tools {missing}; called={called}")
errors = [
    event for event in payload.get("events", [])
    if event.get("type") == "error"
    or event.get("result", {}).get("type") == "error"
]
if errors:
    raise SystemExit(f"Agent tool execution failed: {errors}")
' <<< "$result"
}

echo "Staging Demo projects on $DEVICE"
adb -s "$DEVICE" shell rm -rf "$REMOTE_STAGING"
adb -s "$DEVICE" shell mkdir -p "$REMOTE_STAGING"
adb -s "$DEVICE" push "$FIXTURE_ROOT/." "$REMOTE_STAGING/" >/dev/null
adb -s "$DEVICE" shell run-as "$PACKAGE" mkdir -p "workspace/$WORKSPACE_PREFIX"

for fixture in fitness-checkin weekly-coach birth-profile; do
  adb -s "$DEVICE" shell run-as "$PACKAGE" rm -rf "workspace/$WORKSPACE_PREFIX/$fixture"
  adb -s "$DEVICE" shell run-as "$PACKAGE" cp -R \
    "$REMOTE_STAGING/$fixture" "workspace/$WORKSPACE_PREFIX/$fixture"
done

publish_fixture fitness-checkin
invoke_tool \
  local.project.fitness-checkin-demo \
  fitness_checkin_demo_record_workout \
  '{"exercise":"深蹲","weight":60,"repetitions":8,"recorded_at":"2026-08-04 18:30"}'
invoke_tool \
  local.project.fitness-checkin-demo \
  fitness_checkin_demo_list_workouts \
  '{"exercise":"深蹲","_limit":5,"_order_by":"id DESC"}'

publish_fixture weekly-coach
invoke_tool \
  local.project.weekly-coach-demo \
  weekly_coach_demo_create_plan \
  '{"goal":"七天内恢复规律运动","daily_minutes":20,"constraints":"工作日只能晚上训练，膝盖不适合跳跃"}'

publish_fixture birth-profile
invoke_tool \
  local.project.birth-profile-demo \
  birth_profile_demo_save_profile \
  '{"person_name":"Demo 用户","birth_date":"1992-08-18","birth_time":"09:30","birth_place":"杭州"}'
invoke_tool \
  local.project.birth-profile-demo \
  birth_profile_demo_list_profiles \
  '{"person_name":"Demo 用户","_limit":5}'
invoke_tool \
  local.project.birth-profile-demo \
  birth_profile_demo_interpret_profile \
  '{"person_name":"Demo 用户","birth_date":"1992-08-18","birth_time":"09:30","birth_place":"杭州"}'

invoke_agent \
  "请用健身打卡插件直接记录：2026-08-04 18:40，我完成了杠铃深蹲 65kg 5次。" \
  "fitness_checkin_demo_record_workout"
invoke_agent \
  "请用每周教练插件直接生成计划：目标是七天内恢复规律运动，每天20分钟；工作日只能晚上，膝盖不能跳跃。" \
  "weekly_coach_demo_create_plan"
invoke_agent \
  "请用出生信息插件完成三件事：保存 Demo Agent，1993-06-12 08:15，苏州；查询刚保存的信息；最后生成文化娱乐解读。" \
  "birth_profile_demo_save_profile,birth_profile_demo_list_profiles,birth_profile_demo_interpret_profile"

echo
echo "All sandbox project demos passed on $DEVICE"
