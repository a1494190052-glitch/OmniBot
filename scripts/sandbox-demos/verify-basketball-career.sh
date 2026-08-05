#!/usr/bin/env bash
set -euo pipefail

SCRIPT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIXTURE="$SCRIPT_ROOT/basketball-career"
PACKAGE="cn.com.omnimind.bot.debug"
RECEIVER="$PACKAGE/cn.com.omnimind.bot.debug.DebugSandboxProjectReceiver"
ACTIVITY="$PACKAGE/cn.com.omnimind.bot.activity.PluginAppActivity"
ACTION="cn.com.omnimind.bot.debug.SANDBOX_PROJECT"
RESULT_FILE="files/debug-sandbox-project-result.json"
REMOTE_STAGING="/data/local/tmp/openomnibot-basketball-career"
WORKSPACE_PATH="sandbox-demos/basketball-career"
PLUGIN_ID="local.project.basketball-career"
DEVICE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --device)
      DEVICE="${2:-}"
      shift 2
      ;;
    *)
      echo "Usage: $0 --device <adb-serial>" >&2
      exit 2
      ;;
  esac
done

if [[ -z "$DEVICE" ]]; then
  echo "--device is required" >&2
  exit 2
fi

encode_file() {
  base64 < "$1" | tr -d '\r\n'
}

encode_text() {
  printf '%s' "$1" | base64 | tr -d '\r\n'
}

wait_for_result() {
  local attempts=0
  local payload=""
  while [[ $attempts -lt 240 ]]; do
    if adb -s "$DEVICE" shell run-as "$PACKAGE" test -f "$RESULT_FILE"; then
      payload="$(adb -s "$DEVICE" shell run-as "$PACKAGE" cat "$RESULT_FILE" | tr -d '\r')"
      printf '%s\n' "$payload"
      printf '%s' "$payload" | python3 -c '
import json, sys
payload = json.load(sys.stdin)
if payload.get("success") is not True:
    raise SystemExit(payload.get("errorMessage", "sandbox operation failed"))
' >/dev/null
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
  adb -s "$DEVICE" shell am broadcast -a "$ACTION" -n "$RECEIVER" "$@" >/dev/null
  wait_for_result
}

publish_project() {
  send_broadcast \
    --es operation publish \
    --es sourcePath "$WORKSPACE_PATH" \
    --es manifestBase64 "$(encode_file "$FIXTURE/manifest.json")"
}

invoke_tool() {
  local tool_name="$1"
  local arguments="$2"
  send_broadcast \
    --es operation invoke \
    --es pluginId "$PLUGIN_ID" \
    --es toolName "$tool_name" \
    --es argumentsBase64 "$(encode_text "$arguments")"
}

adb -s "$DEVICE" get-state >/dev/null
adb -s "$DEVICE" shell pm path "$PACKAGE" >/dev/null
adb -s "$DEVICE" shell rm -rf "$REMOTE_STAGING"
adb -s "$DEVICE" shell mkdir -p "$REMOTE_STAGING"
adb -s "$DEVICE" push "$FIXTURE/." "$REMOTE_STAGING/" >/dev/null
adb -s "$DEVICE" shell run-as "$PACKAGE" mkdir -p "workspace/sandbox-demos"
adb -s "$DEVICE" shell run-as "$PACKAGE" rm -rf "workspace/$WORKSPACE_PATH"
adb -s "$DEVICE" shell run-as "$PACKAGE" cp -R "$REMOTE_STAGING" "workspace/$WORKSPACE_PATH"

adb -s "$DEVICE" shell monkey -p "$PACKAGE" 1 >/dev/null
sleep 1

echo "Publishing basketball career Vibe App"
publish_result="$(publish_project)"
printf '%s\n' "$publish_result"

players="$(invoke_tool basketball_career_list_players '{"_limit":10,"_order_by":"id DESC"}')"
if printf '%s' "$players" | python3 -c '
import json, sys
rows = json.load(sys.stdin).get("result", {}).get("rows", [])
raise SystemExit(0 if not rows else 1)
'; then
  echo "Creating persistent test player"
  invoke_tool basketball_career_create_player \
    '{"name":"小万一号","position":"控球后卫","archetype":"球场指挥官","overall":68,"fans":1200}'
fi

echo "Re-publishing to verify plugin data survives updates"
publish_project >/dev/null
persisted="$(invoke_tool basketball_career_list_players '{"name":"小万一号","_limit":10}')"
printf '%s' "$persisted" | python3 -c '
import json, sys
rows = json.load(sys.stdin).get("result", {}).get("rows", [])
if not any(row.get("name") == "小万一号" for row in rows):
    raise SystemExit("persistent player was lost after re-publish")
' >/dev/null

echo "Launching the standalone plugin Activity"
adb -s "$DEVICE" shell am start -W \
  -a android.intent.action.VIEW \
  -d "omnibot://plugin-app/$PLUGIN_ID" \
  -n "$ACTIVITY" >/dev/null

echo "BASKETBALL_CAREER_VIBE_APP=PASS"
echo "- plugin=$PLUGIN_ID"
echo "- persistent_player=小万一号"
echo "- standalone_activity=PASS"
echo "- shortcut_status=$(printf '%s' "$publish_result" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("shortcut", {}).get("status", "unknown"))')"
echo "If Android shows Add to Home Screen, confirm it once on the device."
