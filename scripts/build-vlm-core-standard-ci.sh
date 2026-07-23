#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

BUILD_NUMBER="${VLM_CORE_BUILD_NUMBER:-${GITHUB_RUN_NUMBER:-1}}"
PUBLISH_VALUE="${VLM_CORE_PUBLISH:-false}"
ARTIFACT_DIR="${VLM_CORE_ARTIFACT_DIR:-$ROOT_DIR/app/build/outputs/release-artifacts/vlm-core}"

if [[ ! "$BUILD_NUMBER" =~ ^[0-9]+$ ]] || [[ "$BUILD_NUMBER" -lt 1 ]]; then
  echo "VLM_CORE_BUILD_NUMBER must be a positive integer" >&2
  exit 1
fi

configured_version="$(sed -n 's/^[[:space:]]*val[[:space:]]*defaultAppVersionName[[:space:]]*=[[:space:]]*"\([0-9.]*\)".*/\1/p' app/build.gradle.kts | head -n 1)"
if [[ ! "$configured_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?$ ]]; then
  echo "Unable to read a numeric base versionName from app/build.gradle.kts" >&2
  exit 1
fi

IFS='.' read -r -a version_parts <<< "$configured_version"
version_base="${version_parts[0]}.${version_parts[1]}.${version_parts[2]}"
version_sequence=$((1000 + BUILD_NUMBER))
version_code=$((1000000 + BUILD_NUMBER))
version_name="${version_base}.${version_sequence}"
release_tag="v${version_name}"

export OMNIBOT_UPDATE_CHANNEL="vlm-core"
export OMNIBOT_VERSION_CODE="$version_code"
export OMNIBOT_VERSION_NAME="$version_name"

echo "Building vlm-core Standard ${version_name} (${version_code})"
if [[ "${VLM_CORE_DRY_RUN:-false}" == "true" ]]; then
  echo "Dry run enabled; skipping APK build and publishing."
  exit 0
fi
bash scripts/build-local-release.sh \
  --edition standard \
  --update-channel vlm-core \
  --tag "$release_tag" \
  --out-dir "$ARTIFACT_DIR" \
  --non-interactive

publish_value_normalized="$(printf '%s' "$PUBLISH_VALUE" | tr '[:upper:]' '[:lower:]')"
case "$publish_value_normalized" in
  1|true|yes|on)
    if [[ -z "${APP_UPDATE_WORKER_URL:-}" || -z "${APP_UPDATE_WORKER_TOKEN:-}" ]]; then
      echo "Worker publishing is enabled but APP_UPDATE_WORKER_URL/TOKEN is missing" >&2
      exit 1
    fi
    bash scripts/build-local-release.sh \
      --skip-build \
      --edition standard \
      --update-channel vlm-core \
      --tag "$release_tag" \
      --out-dir "$ARTIFACT_DIR" \
      --publish-worker \
      --non-interactive
    published=true
    ;;
  *)
    echo "Worker publishing is disabled; APK is available as a CI artifact only."
    published=false
    ;;
esac

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  {
    echo "version_name=$version_name"
    echo "version_code=$version_code"
    echo "release_tag=$release_tag"
    echo "artifact_dir=$ARTIFACT_DIR"
    echo "published=$published"
  } >> "$GITHUB_OUTPUT"
fi
