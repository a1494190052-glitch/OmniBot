#!/usr/bin/env bash
set -euo pipefail

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
repo_root="$(CDPATH= cd -- "$script_dir/.." && pwd)"

if [[ -z "${JAVA_HOME:-}" ]]; then
  android_studio_jdk='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
  if [[ -x "$android_studio_jdk/bin/java" ]]; then
    export JAVA_HOME="$android_studio_jdk"
  fi
fi
if [[ ! -x "${JAVA_HOME:-}/bin/java" ]]; then
  printf 'JAVA_HOME must point to a working JDK.\n' >&2
  exit 1
fi

cd "$repo_root"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --no-daemon assembleDevelopStandardDebug \
  -POMNIBOT_PROFILE=main \
  -Ptarget=lib/main_standard.dart

apk="$repo_root/app/build/outputs/apk/developStandard/debug/app-develop-standard-debug.apk"
[[ -f "$apk" ]] || {
  printf 'APK was not produced: %s\n' "$apk" >&2
  exit 1
}
output="$repo_root/artifacts/OpenOmniBot-main-debug.apk"
mkdir -p "$(dirname -- "$output")"
cp "$apk" "$output"
printf 'MAIN_APK=PASS\n'
printf 'apk=%s\n' "$output"
