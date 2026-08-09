#!/usr/bin/env bash
set -euo pipefail

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
repo_root="$(CDPATH= cd -- "$script_dir/.." && pwd)"
omniflow_root="${OMNIFLOW_ROOT:-$repo_root/../OmniFlow-exp}"
canonical_transfer_root="${OMNITRANSFER_ROOT:-$repo_root/../OmniTransfer}"
output_dir="$repo_root/artifacts"
device_serial=""
install_apk=0
build_apk=1

usage() {
  printf '%s\n' \
    'Usage: scripts/build-foolproof-apk.sh [options]' \
    '' \
    'Options:' \
    '  --device SERIAL   Install and smoke-check only this adb device.' \
    '  --output DIR      Artifact directory (default: artifacts).' \
    '  --runtime-only    Build the versioned hot-update zip without rebuilding APK.' \
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
    --runtime-only)
      build_apk=0
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

if ((build_apk)) && [[ -z "${JAVA_HOME:-}" ]]; then
  android_studio_jdk='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
  if [[ -x "$android_studio_jdk/bin/java" ]]; then
    export JAVA_HOME="$android_studio_jdk"
  fi
fi
if ((build_apk)) && [[ ! -x "${JAVA_HOME:-}/bin/java" ]]; then
  printf 'JAVA_HOME must point to a working JDK.\n' >&2
  exit 1
fi
if ((build_apk)); then
  export PATH="$JAVA_HOME/bin:$PATH"
fi

runtime_archive="$repo_root/plugins/omni-vlm-lite/runtime-skill/omniflow-gui-runtime/scripts/runtime.prebuilt.zip"
runtime_properties="$repo_root/plugins/omni-vlm-lite/runtime-skill/omniflow-gui-runtime/scripts/runtime/runtime.properties"
runtime_release_manifest="$repo_root/plugins/omni-vlm-lite/runtime-skill/omniflow-gui-runtime/scripts/runtime.prebuilt.manifest.json"
component_manifest="$repo_root/plugins/omni-vlm-lite/component.json"
apk_source="$repo_root/app/build/outputs/apk/developStandard/debug/app-develop-standard-debug.apk"

cd "$repo_root"
python3 scripts/build-prebuilt-omniflow-runtime.py \
  --omniflow-root "$omniflow_root" \
  --omnitransfer-root "$canonical_transfer_root"
PYTHONPATH=. python3 -m unittest \
  plugins/omni-vlm-lite/tests/test_runtime_bundle.py \
  plugins/omni-vlm-lite/tests/test_component_bundle.py
if ((build_apk)); then
  ./gradlew --no-daemon assembleDevelopStandardDebug \
    -POMNIBOT_PROFILE=investor \
    -Ptarget=lib/main_standard.dart
  [[ -f "$apk_source" ]] || {
    printf 'APK was not produced: %s\n' "$apk_source" >&2
    exit 1
  }
fi
[[ -f "$runtime_archive" && -f "$runtime_properties" ]] || {
  printf 'Runtime bundle was not produced.\n' >&2
  exit 1
}
[[ -f "$component_manifest" ]] || {
  printf 'OmniFlow component manifest was not produced.\n' >&2
  exit 1
}

runtime_version="$(sed -n 's/^runtime.version=//p' "$runtime_properties")"
catalog_release="$(sed -n 's/^omniflow.catalog.release=//p' "$runtime_properties")"
omniflow_commit="$(sed -n 's/^omniflow.commit=//p' "$runtime_properties")"
omnitransfer_checkpoint="$(sed -n 's/^omnitransfer.checkpoint=//p' "$runtime_properties")"
[[ -n "$runtime_version" && -n "$catalog_release" && -n "$omniflow_commit" ]] || {
  printf 'Runtime provenance is incomplete.\n' >&2
  exit 1
}
[[ "$omnitransfer_checkpoint" == *"v9"* && "$omnitransfer_checkpoint" == *.npz ]] || {
  printf 'Runtime did not select the portable OmniTransfer v9 checkpoint: %s\n' \
    "$omnitransfer_checkpoint" >&2
  exit 1
}

mkdir -p "$output_dir"
safe_runtime_version="${runtime_version//[^A-Za-z0-9._-]/_}"
short_commit="${omniflow_commit:0:8}"
apk_output="$output_dir/OpenOmniBot-foolproof-${safe_runtime_version}-${short_commit}-debug.apk"
runtime_output="$output_dir/omniflow-runtime-${safe_runtime_version}-${short_commit}.zip"
runtime_manifest_output="$output_dir/omniflow-runtime-${safe_runtime_version}-${short_commit}.manifest.json"
component_output="$output_dir/omniflow-component-${safe_runtime_version}-${short_commit}.zip"
if ((build_apk)); then
  cp "$apk_source" "$apk_output"
fi
cp "$runtime_archive" "$runtime_output"
cp "$runtime_release_manifest" "$runtime_manifest_output"
python3 scripts/build-omniflow-component.py --output "$component_output"

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
  package_name='cn.com.omnimind.bot.debug'
  adb -s "$device_serial" shell monkey \
    -p "$package_name" -c android.intent.category.LAUNCHER 1 >/dev/null
  sleep 20
  installed_runtime_version="$(
    adb -s "$device_serial" shell run-as "$package_name" \
      cat workspace/.omnibot/skills/omniflow-gui-runtime/scripts/runtime/runtime.properties \
      | sed -n 's/^runtime.version=//p' \
      | tr -d '\r'
  )"
  [[ "$installed_runtime_version" == "$runtime_version" ]] || {
    printf 'Installed runtime version mismatch: expected=%s actual=%s\n' \
      "$runtime_version" "$installed_runtime_version" >&2
    exit 1
  }
  adb -s "$device_serial" shell am broadcast \
    -a "$package_name.CALL_OMNIFLOW_TOOL" \
    -n "$package_name/$package_name.DebugOmniFlowToolReceiver" \
    --es name list_functions --es arguments e30= >/dev/null
  sleep 45
  function_result="$(
    adb -s "$device_serial" shell run-as "$package_name" \
      cat files/debug-omniflow-tool-result.json
  )"
  grep -q '"success": true' <<<"$function_result"
  grep -q '"function_id": "order_beverage_meituan"' <<<"$function_result"
  grep -q '"function_id": "manual_americano_checkout_20260806"' <<<"$function_result"
  grep -q '"count": 2' <<<"$function_result"
fi

if ((build_apk)); then
  printf 'FOOLPROOF_APK=PASS\n'
  printf 'apk=%s\n' "$apk_output"
else
  printf 'OMNIFLOW_RUNTIME_HOT_UPDATE=PASS\n'
fi
printf 'runtime=%s\n' "$runtime_output"
printf 'runtime_manifest=%s\n' "$runtime_manifest_output"
printf 'component=%s\n' "$component_output"
printf 'runtime_version=%s\n' "$runtime_version"
printf 'catalog_release=%s\n' "$catalog_release"
printf 'omniflow_commit=%s\n' "$omniflow_commit"
printf 'omnitransfer_checkpoint=%s\n' "$omnitransfer_checkpoint"
if ((build_apk)); then
  shasum -a 256 "$apk_output" "$runtime_output" "$component_output"
else
  shasum -a 256 "$runtime_output" "$component_output"
fi
