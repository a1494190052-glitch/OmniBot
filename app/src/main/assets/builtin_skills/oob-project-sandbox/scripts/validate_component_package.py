#!/usr/bin/env python3
"""
Validate an OOB Project sandbox component manifest or market index.

Usage:
    python3 validate_component_package.py --manifest path/to/oob_project_component_manifest.v1.json --root path/to/package
    python3 validate_component_package.py --market path/to/market.json
"""

import argparse
import fnmatch
import json
import os
import re
import sys
from pathlib import PurePosixPath


COMPONENT_SCHEMA_VERSION = "oob.project.component.v1"
MARKET_SCHEMA_VERSION = "oob.project.market.v1"
COMPONENT_ID_RE = re.compile(r"^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$")
SEMVER_RE = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?$")
ALLOWED_TYPES = {
    "skill",
    "widget",
    "workflow",
    "template_app",
    "asset_bundle",
    "display",
}
BLOCKED_PATH_PATTERNS = [
    "data/**",
    "logs/**",
    "cache/**",
    "captures/**",
    "screenshots/**",
    ".env",
    ".env.*",
    "*.jks",
    "*.keystore",
    "*.p12",
    "*.pem",
    "*.key",
    "*secret*",
    "*token*",
]
SECRET_TEXT_RE = re.compile(
    r"(sk-[A-Za-z0-9_-]{20,}|api[_-]?key\s*[:=]\s*['\"][^'\"]+|token\s*[:=]\s*['\"][^'\"]+)",
    re.IGNORECASE,
)


def load_json(path):
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def normalize_package_path(path):
    raw = str(path or "").replace("\\", "/").strip()
    if not raw:
        return ""
    pure = PurePosixPath(raw)
    if pure.is_absolute():
        return ""
    parts = []
    for part in pure.parts:
        if part in {"", "."}:
            continue
        if part == "..":
            return ""
        parts.append(part)
    return "/".join(parts)


def blocked_reason(path):
    normalized = normalize_package_path(path)
    if not normalized:
        return "path is empty, absolute, or escapes the package root"
    lower = normalized.lower()
    for pattern in BLOCKED_PATH_PATTERNS:
        if fnmatch.fnmatch(lower, pattern.lower()):
            return f"matches blocked pattern {pattern}"
    return ""


def read_small_text(path, max_bytes=256 * 1024):
    try:
        if os.path.getsize(path) > max_bytes:
            return ""
        with open(path, "r", encoding="utf-8") as handle:
            return handle.read()
    except Exception:
        return ""


class Reporter:
    def __init__(self):
        self.failures = []
        self.warnings = []

    def fail(self, message):
        self.failures.append(message)

    def warn(self, message):
        self.warnings.append(message)

    def print(self):
        for warning in self.warnings:
            print(f"WARN: {warning}")
        for failure in self.failures:
            print(f"FAIL: {failure}")
        if not self.failures:
            print("PASS: Project sandbox package validation passed")


def validate_component(manifest, root, reporter):
    if manifest.get("schemaVersion") != COMPONENT_SCHEMA_VERSION:
        reporter.fail(f"schemaVersion must be {COMPONENT_SCHEMA_VERSION}")

    component_id = manifest.get("componentId")
    if not isinstance(component_id, str) or not COMPONENT_ID_RE.match(component_id):
        reporter.fail("componentId must use lowercase letters, digits, and hyphens")

    version = manifest.get("version")
    if not isinstance(version, str) or not SEMVER_RE.match(version):
        reporter.fail("version must be semantic version format, for example 0.1.0")

    component_type = manifest.get("type")
    if component_type not in ALLOWED_TYPES:
        reporter.fail(f"type must be one of {sorted(ALLOWED_TYPES)}")

    for key in ["name", "description"]:
        if not str(manifest.get(key, "")).strip():
            reporter.fail(f"{key} is required")

    data_policy = manifest.get("dataPolicy")
    if not isinstance(data_policy, dict):
        reporter.fail("dataPolicy is required")
        data_policy = {}
    if data_policy.get("uploadsUserData") is not False:
        reporter.fail("dataPolicy.uploadsUserData must be false")
    if data_policy.get("uploadsRuntimeData") is not False:
        reporter.fail("dataPolicy.uploadsRuntimeData must be false")

    entry = manifest.get("entry")
    if not isinstance(entry, dict):
        reporter.fail("entry is required")
    else:
        reason = blocked_reason(entry.get("path"))
        if reason:
            reporter.fail(f"entry.path {entry.get('path')!r} is invalid: {reason}")

    files = manifest.get("files")
    if not isinstance(files, list) or not files:
        reporter.fail("files must be a non-empty list")
        files = []

    seen = set()
    for index, item in enumerate(files):
        if not isinstance(item, dict):
            reporter.fail(f"files[{index}] must be an object")
            continue
        rel_path = normalize_package_path(item.get("path"))
        if not rel_path:
            reporter.fail(f"files[{index}].path is invalid")
            continue
        if rel_path in seen:
            reporter.fail(f"duplicate file path: {rel_path}")
        seen.add(rel_path)
        reason = blocked_reason(rel_path)
        if reason:
            reporter.fail(f"{rel_path} is blocked: {reason}")
        if root:
            full_path = os.path.abspath(os.path.join(root, rel_path))
            root_path = os.path.abspath(root)
            if not full_path.startswith(root_path + os.sep) and full_path != root_path:
                reporter.fail(f"{rel_path} escapes package root")
            elif item.get("required", True) is not False and not os.path.exists(full_path):
                reporter.fail(f"required file does not exist: {rel_path}")
            else:
                text = read_small_text(full_path)
                if text and SECRET_TEXT_RE.search(text):
                    reporter.fail(f"{rel_path} appears to contain a secret or token")


def validate_market(index, reporter):
    if index.get("schemaVersion") != MARKET_SCHEMA_VERSION:
        reporter.fail(f"market schemaVersion must be {MARKET_SCHEMA_VERSION}")

    items = index.get("items")
    if not isinstance(items, list):
        reporter.fail("market items must be a list")
        return

    ids = set()
    for item in items:
        component_id = item.get("componentId")
        if not isinstance(component_id, str) or not COMPONENT_ID_RE.match(component_id):
            reporter.fail(f"market componentId is invalid: {component_id!r}")
            continue
        version = item.get("version")
        identity = f"{component_id}@{version}"
        if identity in ids:
            reporter.fail(f"duplicate market item: {identity}")
        ids.add(identity)
        if not isinstance(version, str) or not SEMVER_RE.match(version):
            reporter.fail(f"{component_id} has invalid version: {version!r}")
        policy = item.get("dataPolicySummary")
        if not isinstance(policy, dict):
            reporter.fail(f"{identity} is missing dataPolicySummary")
            continue
        if policy.get("uploadsUserData") is not False:
            reporter.fail(f"{identity} uploadsUserData must be false")
        if policy.get("uploadsRuntimeData") is not False:
            reporter.fail(f"{identity} uploadsRuntimeData must be false")


def main(argv):
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", help="Component manifest JSON path")
    parser.add_argument("--root", help="Package root used to verify listed files")
    parser.add_argument("--market", help="Market index JSON path")
    args = parser.parse_args(argv)

    if not args.manifest and not args.market:
        parser.error("provide --manifest or --market")

    reporter = Reporter()
    if args.manifest:
        validate_component(load_json(args.manifest), args.root, reporter)
    if args.market:
        validate_market(load_json(args.market), reporter)

    reporter.print()
    return 1 if reporter.failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
