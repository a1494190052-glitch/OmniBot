#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DESKTOP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RUNNER_DIR="$DESKTOP_DIR/runner"
BACKEND_DIR="$DESKTOP_DIR/backend"

usage() {
  cat <<'EOF'
Usage:
  tool/desktop.sh [run|dev] [--device macos|windows] [flutter args...]
  tool/desktop.sh backend [--data-dir DIR] [--bind HOST:PORT] [backend args...]
  tool/desktop.sh package [macos|windows] [packager args...]

Commands:
  run, dev  Build the Rust backend in debug mode and run the Flutter desktop app.
  backend   Run only the Rust backend from source for local API/model-provider debugging.
  package   Build an installable desktop package for the current host or selected platform.

Examples:
  tool/desktop.sh run
  tool/desktop.sh run --device macos --verbose
  tool/desktop.sh backend --bind 127.0.0.1:58761
  tool/desktop.sh package macos --skip-codesign
EOF
}

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "error: '$1' is required but was not found on PATH" >&2
    exit 127
  fi
}

host_flutter_device() {
  case "$(uname -s)" in
    Darwin) echo "macos" ;;
    MINGW*|MSYS*|CYGWIN*) echo "windows" ;;
    *) echo "" ;;
  esac
}

host_package_platform() {
  case "$(uname -s)" in
    Darwin) echo "macos" ;;
    MINGW*|MSYS*|CYGWIN*) echo "windows" ;;
    *) echo "" ;;
  esac
}

run_dev() {
  local device="${OMNIBOT_DESKTOP_DEVICE:-}"
  if [[ "${1:-}" == "--device" || "${1:-}" == "-d" ]]; then
    if [[ $# -lt 2 ]]; then
      echo "error: --device requires a value" >&2
      exit 2
    fi
    device="$2"
    shift 2
  fi
  if [[ -z "$device" ]]; then
    device="$(host_flutter_device)"
  fi
  if [[ -z "$device" ]]; then
    echo "error: could not infer Flutter desktop device; pass --device macos|windows" >&2
    exit 2
  fi

  require_cmd cargo
  require_cmd flutter

  echo "==> Building Rust backend (debug)"
  (cd "$BACKEND_DIR" && cargo build -p omnibot-backend)

  echo "==> Running OmniBot on Flutter device: $device"
  (cd "$RUNNER_DIR" && flutter run -d "$device" "$@")
}

run_backend() {
  local data_dir="${OMNIBOT_DESKTOP_DATA_DIR:-$DESKTOP_DIR/.dev-data}"
  local bind="${OMNIBOT_BACKEND_BIND:-127.0.0.1:58761}"
  local passthrough=()

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --data-dir)
        if [[ $# -lt 2 ]]; then
          echo "error: --data-dir requires a value" >&2
          exit 2
        fi
        data_dir="$2"
        shift 2
        ;;
      --bind)
        if [[ $# -lt 2 ]]; then
          echo "error: --bind requires a value" >&2
          exit 2
        fi
        bind="$2"
        shift 2
        ;;
      *)
        passthrough+=("$1")
        shift
        ;;
    esac
  done

  require_cmd cargo
  mkdir -p "$data_dir"

  echo "==> Running Rust backend at $bind"
  echo "==> Data dir: $data_dir"
  (
    cd "$BACKEND_DIR"
    OMNIBOT_DATA_DIR="$data_dir" cargo run -p omnibot-backend -- \
      --data-dir "$data_dir" \
      --bind "$bind" \
      "${passthrough[@]}"
  )
}

package_app() {
  local platform="${1:-}"
  if [[ -z "$platform" || "$platform" == --* ]]; then
    platform="$(host_package_platform)"
  else
    shift
  fi
  if [[ -z "$platform" ]]; then
    echo "error: could not infer package platform; pass macos or windows" >&2
    exit 2
  fi

  case "$platform" in
    macos|darwin)
      "$RUNNER_DIR/tool/package_macos.sh" "$@"
      ;;
    windows|win)
      if command -v pwsh >/dev/null 2>&1; then
        (cd "$RUNNER_DIR" && pwsh -NoProfile -ExecutionPolicy Bypass -File "$RUNNER_DIR/tool/package_windows.ps1" "$@")
      elif command -v powershell.exe >/dev/null 2>&1; then
        (cd "$RUNNER_DIR" && powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$RUNNER_DIR/tool/package_windows.ps1" "$@")
      else
        echo "error: PowerShell is required to run the Windows package script" >&2
        exit 127
      fi
      ;;
    *)
      echo "error: unsupported package platform '$platform'" >&2
      exit 2
      ;;
  esac
}

cmd="${1:-run}"
case "$cmd" in
  run|dev)
    shift || true
    run_dev "$@"
    ;;
  backend)
    shift || true
    run_backend "$@"
    ;;
  package)
    shift || true
    package_app "$@"
    ;;
  help|-h|--help)
    usage
    ;;
  *)
    echo "error: unknown command '$cmd'" >&2
    usage >&2
    exit 2
    ;;
esac
