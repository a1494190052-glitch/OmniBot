#!/usr/bin/env bash
# One-click private WebChat bootstrap for a rooted OpenOmniBot device.
#
# Run from a development host with adb, or directly in Termux on the rooted
# device. The installed APK must include RemoteAccessBootstrapReceiver.
set -euo pipefail

PACKAGE_NAME="${OOB_PACKAGE_NAME:-}"
DEVICE_SERIAL=""
PORT="${OOB_REMOTE_PORT:-8899}"
HOST_OVERRIDE="${OOB_REMOTE_HOST:-}"
REFRESH_TOKEN=0
SHOW_QR=1
OPEN_SHARE=0

ACTION="cn.com.omnimind.bot.action.BOOTSTRAP_REMOTE_ACCESS"
RECEIVER_CLASS="cn.com.omnimind.bot.mcp.RemoteAccessBootstrapReceiver"
RESULT_PREFIX="OOB_REMOTE_LINK_V1="
ERROR_PREFIX="OOB_REMOTE_ERROR_V1="

usage() {
  cat <<'EOF'
Usage:
  bash scripts/oob-remote-link.sh [options]

Run modes:
  - On macOS/Linux: uses adb and the first connected Android device.
  - In Termux on the rooted target: uses su directly.

Options:
  --device <serial>   Use a specific adb device; forces adb mode.
  --package <name>    Installed application id. Auto-detects release/debug.
  --port <port>       OpenOmniBot local-service port. Default: 8899.
  --host <IPv4>       Force a private/Tailscale link address.
  --refresh-token     Rotate the server token before generating the link.
  --share             Open Android's share sheet with the generated link.
  --no-qr             Do not render a terminal QR code when qrencode exists.
  --help              Show this help.

The generated URL contains the WebChat token in a URL fragment (#token=...),
so the token is not sent in the HTTP request. Treat the complete URL as a
password. Keep port 8899 private; use the same LAN or Tailscale, never public
port forwarding.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --device)
      [[ $# -ge 2 ]] || { echo "--device requires a value" >&2; exit 2; }
      DEVICE_SERIAL="$2"
      shift
      ;;
    --device=*) DEVICE_SERIAL="${1#--device=}" ;;
    --package)
      [[ $# -ge 2 ]] || { echo "--package requires a value" >&2; exit 2; }
      PACKAGE_NAME="$2"
      shift
      ;;
    --package=*) PACKAGE_NAME="${1#--package=}" ;;
    --port)
      [[ $# -ge 2 ]] || { echo "--port requires a value" >&2; exit 2; }
      PORT="$2"
      shift
      ;;
    --port=*) PORT="${1#--port=}" ;;
    --host)
      [[ $# -ge 2 ]] || { echo "--host requires a value" >&2; exit 2; }
      HOST_OVERRIDE="$2"
      shift
      ;;
    --host=*) HOST_OVERRIDE="${1#--host=}" ;;
    --refresh-token) REFRESH_TOKEN=1 ;;
    --share) OPEN_SHARE=1 ;;
    --no-qr) SHOW_QR=0 ;;
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
  shift
done

[[ "$PORT" =~ ^[0-9]+$ ]] || { echo "--port must be numeric" >&2; exit 2; }
(( PORT >= 1024 && PORT <= 65535 )) || {
  echo "--port must be between 1024 and 65535" >&2
  exit 2
}
[[ -z "$PACKAGE_NAME" || "$PACKAGE_NAME" =~ ^[A-Za-z0-9._]+$ ]] || {
  echo "--package contains unsupported characters" >&2
  exit 2
}
[[ -z "$HOST_OVERRIDE" || "$HOST_OVERRIDE" =~ ^[0-9.]+$ ]] || {
  echo "--host must be an IPv4 address" >&2
  exit 2
}

MODE="adb"
if [[ -z "$DEVICE_SERIAL" ]] && command -v getprop >/dev/null 2>&1; then
  if [[ -n "$(getprop ro.build.version.sdk 2>/dev/null || true)" ]]; then
    MODE="local"
  fi
fi

remote_command() {
  local command="" quoted="" argument
  for argument in "$@"; do
    quoted="${argument//\'/\'\\\'\'}"
    command+="'$quoted' "
  done
  printf '%s' "$command"
}

if [[ "$MODE" == "adb" ]]; then
  command -v adb >/dev/null 2>&1 || {
    echo "adb is required outside Android/Termux" >&2
    exit 1
  }
  if [[ -z "$DEVICE_SERIAL" ]]; then
    DEVICE_SERIAL="$(adb devices | awk 'NR > 1 && $2 == "device" { print $1; exit }')"
  fi
  [[ -n "$DEVICE_SERIAL" ]] || {
    echo "No online adb device found" >&2
    exit 1
  }
  ADB=(adb -s "$DEVICE_SERIAL")
  "${ADB[@]}" get-state >/dev/null

  android_shell() {
    "${ADB[@]}" shell "$(remote_command "$@")"
  }
else
  command -v su >/dev/null 2>&1 || {
    echo "Root (su) is required when running on the target phone" >&2
    exit 1
  }

  android_shell() {
    su -c "$(remote_command "$@")"
  }
fi

package_exists() {
  android_shell pm path "$1" 2>/dev/null | tr -d '\r' | grep -q '^package:'
}

if [[ -z "$PACKAGE_NAME" ]]; then
  for candidate in cn.com.omnimind.bot cn.com.omnimind.bot.debug; do
    if package_exists "$candidate"; then
      PACKAGE_NAME="$candidate"
      break
    fi
  done
fi

[[ -n "$PACKAGE_NAME" ]] || {
  echo "OpenOmniBot is not installed (checked release and debug package ids)" >&2
  exit 1
}
package_exists "$PACKAGE_NAME" || {
  echo "Package is not installed: $PACKAGE_NAME" >&2
  exit 1
}

echo "[openomnibot] mode=$MODE package=$PACKAGE_NAME port=$PORT"
if [[ -n "$HOST_OVERRIDE" ]]; then
  echo "[openomnibot] requested_host=$HOST_OVERRIDE"
fi

# Keep the app process alive while its embedded Ktor server is bootstrapped.
android_shell monkey \
  -p "$PACKAGE_NAME" \
  -c android.intent.category.LAUNCHER \
  1 >/dev/null 2>&1 || true

broadcast_args=(
  am broadcast
  --receiver-foreground
  -a "$ACTION"
  -n "$PACKAGE_NAME/$RECEIVER_CLASS"
  --ei port "$PORT"
)
if [[ -n "$HOST_OVERRIDE" ]]; then
  broadcast_args+=(--es host "$HOST_OVERRIDE")
fi
if [[ "$REFRESH_TOKEN" -eq 1 ]]; then
  broadcast_args+=(--ez refresh_token true)
fi

broadcast_output="$(android_shell "${broadcast_args[@]}" 2>&1 | tr -d '\r')"
launch_url="$(
  printf '%s\n' "$broadcast_output" |
    sed -n "s|.*data=\"${RESULT_PREFIX}\([^\"]*\)\".*|\1|p" |
    tail -n 1
)"

if [[ -z "$launch_url" ]]; then
  error_code="$(
    printf '%s\n' "$broadcast_output" |
      sed -n "s|.*${ERROR_PREFIX}\([^\"[:space:]]*\).*|\1|p" |
      tail -n 1
  )"
  if [[ "$broadcast_output" == *"Permission Denial"* ]]; then
    echo "The installed APK does not allow the shell/root bootstrap receiver." >&2
  elif [[ "$broadcast_output" == *"unable to resolve Intent"* || "$broadcast_output" == *"result=0"* ]]; then
    echo "The installed APK does not contain RemoteAccessBootstrapReceiver; update OpenOmniBot first." >&2
  else
    echo "Remote link bootstrap failed: ${error_code:-unknown_error}" >&2
  fi
  echo "$broadcast_output" >&2
  exit 1
fi

probe_status="skipped"
if command -v curl >/dev/null 2>&1; then
  if [[ "$MODE" == "adb" ]]; then
    forwarded_port="$("${ADB[@]}" forward tcp:0 "tcp:$PORT" | tr -d '\r')"
    if curl --fail --silent --show-error --max-time 5 \
      "http://127.0.0.1:${forwarded_port}/webchat/" >/dev/null; then
      probe_status="ok"
    else
      probe_status="failed"
    fi
    "${ADB[@]}" forward --remove "tcp:$forwarded_port" >/dev/null 2>&1 || true
  elif curl --fail --silent --show-error --max-time 5 \
    "http://127.0.0.1:${PORT}/webchat/" >/dev/null; then
    probe_status="ok"
  else
    probe_status="failed"
  fi
fi

echo "[openomnibot] health=$probe_status"
echo
echo "Open this private link on your controller phone:"
echo "$launch_url"

if [[ "$SHOW_QR" -eq 1 ]]; then
  if command -v qrencode >/dev/null 2>&1; then
    echo
    qrencode -t ANSIUTF8 "$launch_url"
  else
    echo
    echo "Tip: install qrencode locally to render a terminal QR code."
  fi
fi

if [[ "$OPEN_SHARE" -eq 1 ]]; then
  android_shell am start \
    -a android.intent.action.SEND \
    -t text/plain \
    --es android.intent.extra.TEXT "$launch_url" >/dev/null
fi

echo
echo "Security: the full link is a credential. Do not paste it into public QR or URL services."
