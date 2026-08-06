#!/usr/bin/env bash
set -euo pipefail

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
repo_root="$(CDPATH= cd -- "$script_dir/.." && pwd)"
omniflow_root="${OMNIFLOW_ROOT:-$repo_root/../OmniFlow-exp}"
canonical_transfer_root="${OMNITRANSFER_ROOT:-$repo_root/../OmniTransfer}"
output_dir="$repo_root/artifacts"
device_serial=""
install_apk=0

usage() {
  printf '%s\n' \
    'Usage: scripts/build-foolproof-apk.sh [options]' \
    '' \
    'Options:' \
    '  --device SERIAL   Install and smoke-check only this adb device.' \
    '  --output DIR      Artifact directory (default: artifacts).' \
    '  --no-install      Build only (default when --device is omitted).' \
    '  -h, --help        Show this help.'
}

while (($#)); do
  case "$1" in
    --device)
      device_serial="${2:?missing device serial}"
      install_apk=1
      shift 2
      ;;
    --output)
      output_dir="${2:?missing output directory}"
      shift 2
      ;;
    --no-install)
      install_apk=0
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      printf 'Unknown argument: %s\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

for command_name in python3 git shasum; do
  command -v "$command_name" >/dev/null || {
    printf 'Missing required command: %s\n' "$command_name" >&2
    exit 1
  }
done

[[ -d "$omniflow_root/.git" ]] || {
  printf 'Canonical OmniFlow repository missing: %s\n' "$omniflow_root" >&2
  exit 1
}
[[ -d "$canonical_transfer_root/.git" ]] || {
  printf 'Canonical OmniTransfer repository missing: %s\n' "$canonical_transfer_root" >&2
  exit 1
}

if [[ -z "${JAVA_HOME:-}" ]]; then
  android_studio_jdk='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
  if [[ -x "$android_studio_jdk/bin/java" ]]; then
    export JAVA_HOME="$android_studio_jdk"
  fi
fi
[[ -x "${JAVA_HOME:-}/bin/java" ]] || {
  printf 'JAVA_HOME must point to a working JDK.\n' >&2
  exit 1
}
export PATH="$JAVA_HOME/bin:$PATH"

runtime_archive="$repo_root/plugins/omni-vlm-lite/runtime-skill/omniflow-gui-runtime/scripts/runtime.prebuilt.zip"
runtime_properties="$repo_root/plugins/omni-vlm-lite/runtime-skill/omniflow-gui-runtime/scripts/runtime/runtime.properties"
apk_source="$repo_root/app/build/outputs/apk/developStandard/debug/app-develop-standard-debug.apk"

cd "$repo_root"
python3 scripts/build-prebuilt-omniflow-runtime.py \
  --omniflow-root "$omniflow_root" \
  --omnitransfer-root "$canonical_transfer_root" \
  --reuse-packaged-omnitransfer
PYTHONPATH=. python3 -m unittest \
  plugins/omni-vlm-lite/tests/test_runtime_bundle.py
./gradlew --no-daemon assembleDevelopStandardDebug \
  -Ptarget=lib/main_standard.dart

[[ -f "$apk_source" ]] || {
  printf 'APK was not produced: %s\n' "$apk_source" >&2
  exit 1
}
[[ -f "$runtime_archive" && -f "$runtime_properties" ]] || {
  printf 'Runtime bundle was not produced.\n' >&2
  exit 1
}

runtime_version="$(sed -n 's/^runtime.version=//p' "$runtime_properties")"
catalog_release="$(sed -n 's/^omniflow.catalog.release=//p' "$runtime_properties")"
omniflow_commit="$(sed -n 's/^omniflow.commit=//p' "$runtime_properties")"
[[ -n "$runtime_version" && -n "$catalog_release" && -n "$omniflow_commit" ]] || {
  printf 'Runtime provenance is incomplete.\n' >&2
  exit 1
}

mkdir -p "$output_dir"
safe_runtime_version="${runtime_version//[^A-Za-z0-9._-]/_}"
short_commit="${omniflow_commit:0:8}"
apk_output="$output_dir/OpenOmniBot-foolproof-${safe_runtime_version}-${short_commit}-debug.apk"
runtime_output="$output_dir/omniflow-runtime-${safe_runtime_version}-${short_commit}.zip"
cp "$apk_source" "$apk_output"
cp "$runtime_archive" "$runtime_output"

if ((install_apk)); then
  command -v adb >/dev/null || {
    printf 'Missing required command: adb\n' >&2
    exit 1
  }
  adb -s "$device_serial" get-state | grep -qx device || {
    printf 'ADB device is not ready: %s\n' "$device_serial" >&2
    exit 1
  }
  adb -s "$device_serial" install -r "$apk_output"
fi

printf 'FOOLPROOF_APK=PASS\n'
printf 'apk=%s\n' "$apk_output"
printf 'runtime=%s\n' "$runtime_output"
printf 'runtime_version=%s\n' "$runtime_version"
printf 'catalog_release=%s\n' "$catalog_release"
printf 'omniflow_commit=%s\n' "$omniflow_commit"
shasum -a 256 "$apk_output" "$runtime_output"
