#!/usr/bin/env python3

from __future__ import annotations

import argparse
import hashlib
from io import BytesIO
import json
from pathlib import Path, PurePosixPath
from zipfile import ZipFile

MANIFEST_ENTRY = "assets/omniflow-runtime/manifest.properties"
BUNDLE_ENTRY = "assets/omniflow-runtime/bundle.zip"
RUNTIME_PROPERTIES_ENTRY = "runtime.properties"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Verify the OmniTransfer checkpoint packaged in an OpenOmniBot APK."
    )
    parser.add_argument("--apk", type=Path, required=True)
    return parser.parse_args()


def properties(payload: bytes) -> dict[str, str]:
    result: dict[str, str] = {}
    for raw_line in payload.decode("utf-8").splitlines():
        line = raw_line.strip()
        if line and not line.startswith(("#", "!")) and "=" in line:
            key, value = line.split("=", 1)
            result[key.strip()] = value.strip()
    return result


def required(values: dict[str, str], name: str, source: str) -> str:
    value = values.get(name, "").strip()
    if not value:
        raise RuntimeError(f"{source} missing {name}")
    return value


def verify(apk: Path) -> dict[str, object]:
    if not apk.is_file():
        raise RuntimeError(f"APK not found: {apk}")
    with ZipFile(apk) as outer:
        manifest = properties(outer.read(MANIFEST_ENTRY))
        bundle_payload = outer.read(BUNDLE_ENTRY)
    bundle_sha256 = hashlib.sha256(bundle_payload).hexdigest()
    if bundle_sha256 != required(manifest, "bundle.sha256", "manifest"):
        raise RuntimeError("bundle SHA256 differs from manifest")
    manifest_checkpoint = required(
        manifest,
        "omnitransfer.checkpoint",
        "manifest",
    )
    checkpoint_path = PurePosixPath(manifest_checkpoint)
    if checkpoint_path.is_absolute() or ".." in checkpoint_path.parts:
        raise RuntimeError("manifest checkpoint path is not package-relative")
    if checkpoint_path.suffix != ".npz":
        raise RuntimeError("manifest checkpoint must be a NumPy checkpoint")

    with ZipFile(BytesIO(bundle_payload)) as bundle:
        runtime_properties = properties(bundle.read(RUNTIME_PROPERTIES_ENTRY))
        runtime_checkpoint = required(
            runtime_properties,
            "omnitransfer.checkpoint",
            "runtime.properties",
        )
        if runtime_checkpoint != manifest_checkpoint:
            raise RuntimeError("manifest and runtime checkpoint paths differ")
        manifest_json_repair = required(
            manifest,
            "json_repair.version",
            "manifest",
        )
        runtime_json_repair = required(
            runtime_properties,
            "json_repair.version",
            "runtime.properties",
        )
        if runtime_json_repair != manifest_json_repair:
            raise RuntimeError("manifest and runtime json_repair versions differ")
        json_repair_entries = {
            "site-packages/json_repair/__init__.py",
            (
                "site-packages/"
                f"json_repair-{manifest_json_repair}.dist-info/licenses/LICENSE"
            ),
        }
        missing_json_repair_entries = sorted(
            json_repair_entries.difference(bundle.namelist())
        )
        if missing_json_repair_entries:
            raise RuntimeError(
                "packaged json_repair files are missing: "
                + ", ".join(missing_json_repair_entries)
            )
        checkpoint_entry = f"site-packages/omnitransfer/{manifest_checkpoint}"
        checkpoint_entries = sorted(
            name
            for name in bundle.namelist()
            if name.startswith("site-packages/omnitransfer/checkpoints/")
            and name.endswith((".npz", ".pt"))
        )
        if checkpoint_entries != [checkpoint_entry]:
            raise RuntimeError(
                "packaged OmniTransfer checkpoints differ: "
                + ", ".join(checkpoint_entries)
            )
        checkpoint_sha256 = hashlib.sha256(bundle.read(checkpoint_entry)).hexdigest()

    return {
        "success": True,
        "apk": str(apk.resolve()),
        "checkpoint": manifest_checkpoint,
        "checkpoint_sha256": checkpoint_sha256,
        "json_repair_version": manifest_json_repair,
        "bundle_sha256": bundle_sha256,
    }


def main() -> int:
    args = parse_args()
    try:
        result = verify(args.apk)
    except Exception as error:
        print(f"APK_RUNTIME_CONTRACT=FAIL\n- {error}")
        return 1
    print("APK_RUNTIME_CONTRACT=PASS")
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
