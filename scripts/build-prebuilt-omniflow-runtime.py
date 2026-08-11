#!/usr/bin/env python3
"""Refresh the APK's prebuilt runtime from the canonical local repositories."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import lzma
from pathlib import Path
import re
import shutil
import subprocess
from tempfile import TemporaryDirectory
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo


TRANSFER_FILES = (
    "__init__.py",
    "learned_matcher.py",
    "mutual_matcher.py",
    "numpy_matcher.py",
    "numpy_v9_matcher.py",
    "runtime.py",
    "schema.py",
    "ui_graph.py",
)
PINNED_OMNITRANSFER_COMMIT = "da49fd13698ab14fc7e8aa7b56e0199f4709ab27"
PINNED_OMNITRANSFER_ARCHIVE_SHA256 = (
    "0311141f8e47d0c17ef97ba3f6b7a679f13f11d2d77e8de16fb0f87836a2b805"
)
OMNITRANSFER_GITHUB_ROOT = "https://github.com/wuzw21/OmniTransfer"
OMNITRANSFER_RAW_ROOT = "https://raw.githubusercontent.com/wuzw21/OmniTransfer"
PYTHON_ENVIRONMENT_PROFILE = "alpine-3.21-system-numpy-v1"

def write_builtin_assets(flow_target: Path) -> None:
    catalog_root = flow_target / "catalog"
    pointer = json.loads((catalog_root / "default.json").read_text(encoding="utf-8"))
    release_id = str(pointer.get("release_id") or "").strip()
    if pointer.get("schema_version") != "omniflow.catalog-pointer.v1" or not release_id:
        raise RuntimeError("builtin_catalog_pointer_invalid")
    release = catalog_root / "releases" / release_id
    manifest = json.loads((release / "manifest.json").read_text(encoding="utf-8"))
    if manifest.get("schema_version") != "omniflow.catalog-manifest.v1":
        raise RuntimeError("builtin_catalog_manifest_invalid")
    for name, expected_sha256 in dict(manifest.get("files") or {}).items():
        actual_sha256 = hashlib.sha256((release / name).read_bytes()).hexdigest()
        if actual_sha256 != expected_sha256:
            raise RuntimeError(f"builtin_catalog_checksum_mismatch:{name}")
    encoded = (release / "states.json.xz.b64").read_bytes()
    states_bytes = lzma.decompress(base64.b64decode(encoded))
    states = json.loads(states_bytes)
    if not isinstance(states, dict) or len(states) < 5:
        raise RuntimeError("builtin_catalog_states_invalid")
    builtin = flow_target / "builtin"
    builtin.mkdir(parents=True, exist_ok=True)
    (builtin / "states.json").write_text(
        json.dumps(states, ensure_ascii=False, sort_keys=True),
        encoding="utf-8",
    )
    (builtin / "function_store.json").write_bytes(
        (release / "function_store.json").read_bytes()
    )


def parse_args() -> argparse.Namespace:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser()
    parser.add_argument("--omniflow-root", type=Path, required=True)
    parser.add_argument(
        "--omnitransfer-root",
        type=Path,
        default=Path.home() / "Projects/Omni/OmniTransfer",
    )
    parser.add_argument(
        "--reuse-packaged-omnitransfer",
        action="store_true",
        help=(
            "Refresh OmniFlow and canonical OmniTransfer source while retaining "
            "the already packaged portable checkpoint."
        ),
    )
    parser.add_argument(
        "--archive",
        type=Path,
        default=root
        / "plugins/omni-vlm-lite/runtime-skill/omniflow-gui-runtime/scripts/"
        "runtime.prebuilt.zip",
    )
    parser.add_argument(
        "--properties",
        type=Path,
        default=root
        / "plugins/omni-vlm-lite/runtime-skill/omniflow-gui-runtime/scripts/"
        "runtime/runtime.properties",
    )
    parser.add_argument(
        "--release-manifest",
        type=Path,
        default=root
        / "plugins/omni-vlm-lite/runtime-skill/omniflow-gui-runtime/scripts/"
        "runtime.prebuilt.manifest.json",
    )
    parser.add_argument(
        "--catalog",
        type=Path,
        default=root / "plugins/catalog.v1.json",
    )
    parser.add_argument(
        "--marker",
        type=Path,
        default=root
        / "plugins/omni-vlm-lite/runtime-skill/omniflow-gui-runtime/"
        "PACKAGED_RUNTIME_SKILL",
    )
    return parser.parse_args()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sha256_directory(root: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(item for item in root.rglob("*") if item.is_file()):
        relative = path.relative_to(root)
        if "__pycache__" in relative.parts or path.suffix in {".pyc", ".pyo"}:
            continue
        digest.update(relative.as_posix().encode("utf-8"))
        digest.update(b"\0")
        digest.update(path.read_bytes())
    return digest.hexdigest()


def git_head(root: Path) -> str:
    return subprocess.check_output(
        ("git", "-C", str(root), "rev-parse", "HEAD"),
        text=True,
    ).strip()


def git_file(root: Path, relative: str, *, revision: str = "HEAD") -> bytes:
    return subprocess.check_output(
        ("git", "-C", str(root), "show", f"{revision}:{relative}"),
    )


def copy_committed_transfer_sources(
    transfer_root: Path,
    target: Path,
    *,
    revision: str,
) -> None:
    target.mkdir(parents=True, exist_ok=True)
    for name in TRANSFER_FILES:
        (target / name).write_bytes(
            git_file(
                transfer_root,
                f"src/omnitransfer/{name}",
                revision=revision,
            )
        )


def copy_tree(source: Path, target: Path) -> None:
    shutil.copytree(
        source,
        target,
        ignore=shutil.ignore_patterns("__pycache__", "*.pyc", "*.pyo", ".DS_Store"),
    )


def read_properties(path: Path) -> tuple[list[str], dict[str, str]]:
    lines = path.read_text(encoding="utf-8").splitlines()
    values: dict[str, str] = {}
    for line in lines:
        if line.strip() and not line.lstrip().startswith(("#", "!")) and "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return lines, values


def write_properties(path: Path, lines: list[str], updates: dict[str, str]) -> None:
    remaining = dict(updates)
    output: list[str] = []
    for line in lines:
        if "=" not in line or line.lstrip().startswith(("#", "!")):
            output.append(line)
            continue
        key = line.split("=", 1)[0].strip()
        output.append(f"{key}={remaining.pop(key)}" if key in remaining else line)
    output.extend(f"{key}={value}" for key, value in sorted(remaining.items()))
    path.write_text("\n".join(output) + "\n", encoding="utf-8")


def schema_digest_updates(
    properties: dict[str, str],
    schema_root: Path,
) -> dict[str, str]:
    """Refresh manifest checksums for the schemas packaged beside the skill."""

    updates: dict[str, str] = {}
    for key in properties:
        if not key.startswith("schema.") or not key.endswith(".sha256"):
            continue
        filename = key.removeprefix("schema.").removesuffix(".sha256")
        matches = sorted(schema_root.rglob(filename))
        if len(matches) != 1:
            raise RuntimeError(
                f"schema_manifest_file_missing_or_ambiguous:{filename}"
            )
        updates[key] = sha256_file(matches[0])
    bridge_contract = schema_root / "oob/omniflow_android_bridge.v2.json"
    if not bridge_contract.is_file():
        raise RuntimeError("bridge_contract_schema_missing")
    updates["bridge.contract.sha256"] = sha256_file(bridge_contract)
    return updates


def checkpoint_relative_path(transfer_root: Path, *, revision: str) -> str:
    runtime_source = git_file(
        transfer_root,
        "src/omnitransfer/runtime.py",
        revision=revision,
    ).decode("utf-8")
    declaration = re.search(
        r"_DEFAULT_MATCHER_CHECKPOINT\s*=\s*\((.*?)\)\s*\n",
        runtime_source,
        flags=re.DOTALL,
    )
    if declaration is None:
        raise RuntimeError("canonical_omnitransfer_checkpoint_not_declared")
    components = re.findall(r'["\']([^"\']+)["\']', declaration.group(1))
    try:
        relative = "/".join(components[components.index("checkpoints") :])
    except ValueError as error:
        raise RuntimeError("canonical_omnitransfer_checkpoint_not_declared") from error
    checkpoint_path = f"src/omnitransfer/{relative}"
    try:
        git_file(transfer_root, checkpoint_path, revision=revision)
    except subprocess.CalledProcessError as error:
        raise RuntimeError(
            f"canonical_omnitransfer_checkpoint_missing:{relative}"
        ) from error
    if Path(relative).suffix == ".npz":
        return relative
    portable_relative = str(Path(relative).with_suffix(".npz"))
    if (
        "NumpyMutualGraphMatcher" not in runtime_source
    ):
        raise RuntimeError(
            "canonical_omnitransfer_android_runtime_requires_numpy_checkpoint:"
            f"{relative}"
        )
    try:
        git_file(
            transfer_root,
            f"src/omnitransfer/{portable_relative}",
            revision=revision,
        )
    except subprocess.CalledProcessError as error:
        raise RuntimeError(
            "canonical_omnitransfer_android_runtime_requires_numpy_checkpoint:"
            f"{relative}"
        ) from error
    return portable_relative


def write_deterministic_zip(source: Path, target: Path) -> None:
    temporary = target.with_suffix(target.suffix + ".tmp")
    with ZipFile(temporary, "w", compression=ZIP_DEFLATED, compresslevel=9) as archive:
        for path in sorted(source.rglob("*")):
            if not path.is_file():
                continue
            relative = path.relative_to(source).as_posix()
            info = ZipInfo(relative, date_time=(1981, 1, 1, 0, 0, 0))
            info.compress_type = ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, path.read_bytes(), compress_type=ZIP_DEFLATED, compresslevel=9)
    temporary.replace(target)


def remove_packaged_numpy(site_packages: Path) -> None:
    """Keep NumPy in the managed Alpine environment, not the hot-update zip."""
    for path in site_packages.glob("numpy*"):
        if path.is_dir():
            shutil.rmtree(path)
        else:
            path.unlink()


def update_catalog_digest(path: Path, digest: str) -> None:
    content = path.read_text(encoding="utf-8")
    updated, count = re.subn(
        r'("prebuiltRuntimeSha256"\s*:\s*")[a-f0-9]{64}(")',
        rf"\g<1>{digest}\2",
        content,
        count=1,
    )
    if count != 1:
        raise RuntimeError("omniflow_catalog_prebuilt_digest_missing")
    path.write_text(updated, encoding="utf-8")


def main() -> int:
    args = parse_args()
    flow_root = args.omniflow_root.expanduser().resolve()
    transfer_root = args.omnitransfer_root.expanduser().resolve()
    canonical_transfer = (Path.home() / "Projects/Omni/OmniTransfer").resolve()
    if transfer_root != canonical_transfer:
        raise RuntimeError(f"canonical_omnitransfer_required:{canonical_transfer}")
    flow_package = flow_root / "omniflow"
    transfer_package = transfer_root / "src/omnitransfer"
    if not flow_package.is_dir() or not transfer_package.is_dir():
        raise RuntimeError("local_runtime_source_missing")
    if not args.archive.is_file():
        raise RuntimeError(f"prebuilt_runtime_base_missing:{args.archive}")

    flow_commit = git_head(flow_root)
    lines, packaged_values = read_properties(args.properties)
    transfer_commit = PINNED_OMNITRANSFER_COMMIT
    if args.reuse_packaged_omnitransfer:
        checkpoint = packaged_values.get("omnitransfer.checkpoint", "").strip()
        if not checkpoint:
            raise RuntimeError("packaged_omnitransfer_manifest_incomplete")
    else:
        checkpoint = checkpoint_relative_path(
            transfer_root,
            revision=transfer_commit,
        )
    with TemporaryDirectory(prefix="oob-runtime-") as temporary:
        staging = Path(temporary)
        with ZipFile(args.archive) as archive:
            archive.extractall(staging)
        remove_packaged_numpy(staging / ".runtime/site-packages")
        flow_target = staging / "python/omniflow"
        shutil.rmtree(flow_target)
        copy_tree(flow_package, flow_target)
        schema_target = staging / "python/schemas/oob"
        shutil.rmtree(schema_target)
        copy_tree(args.catalog.parent / "omni-vlm-lite/schemas/oob", schema_target)
        # Materialize catalog data only. Runtime behavior must come unchanged
        # from the canonical OmniFlow-exp source tree.
        write_builtin_assets(flow_target)
        transfer_target = staging / ".runtime/omnitransfer/src/omnitransfer"
        if args.reuse_packaged_omnitransfer:
            checkpoint_target = transfer_target / checkpoint
            if not checkpoint_target.is_file():
                raise RuntimeError(
                    f"packaged_omnitransfer_checkpoint_missing:{checkpoint}"
                )
            # Reuse only the portable checkpoint. Runtime source is always
            # refreshed from canonical OmniTransfer so an old APK bundle can
            # never preserve build-time behavior patches.
            copy_committed_transfer_sources(
                transfer_root,
                transfer_target,
                revision=transfer_commit,
            )
        else:
            shutil.rmtree(transfer_target)
            copy_committed_transfer_sources(
                transfer_root,
                transfer_target,
                revision=transfer_commit,
            )
            checkpoint_target = transfer_target / checkpoint
            checkpoint_target.parent.mkdir(parents=True)
            checkpoint_target.write_bytes(
                git_file(
                    transfer_root,
                    f"src/omnitransfer/{checkpoint}",
                    revision=transfer_commit,
                )
            )
        # OmniTransfer source is copied unchanged. Missing mappings remain
        # transfer failures so the normal runtime can fall back to the VLM.

        flow_sha = sha256_directory(flow_target)
        transfer_sha = sha256_directory(transfer_target)
        catalog_pointer = json.loads(
            (flow_target / "catalog/default.json").read_text(encoding="utf-8")
        )
        catalog_release = str(catalog_pointer["release_id"])
        catalog_manifest = (
            flow_target / "catalog/releases" / catalog_release / "manifest.json"
        )
        environment_sha = hashlib.sha256(
            PYTHON_ENVIRONMENT_PROFILE.encode("utf-8")
        ).hexdigest()
        runtime_version = (
            "2026.08.07.local."
            f"{flow_sha[:8]}.{transfer_sha[:8]}.{environment_sha[:8]}."
            f"{flow_commit[:8]}"
        )
        updates = {
            "runtime.version": runtime_version,
            "omniflow.commit": flow_commit,
            "omniflow.source.sha256": flow_sha,
            "omniflow.catalog.release": catalog_release,
            "omniflow.catalog.manifest.sha256": sha256_file(catalog_manifest),
            "omnitransfer.commit": transfer_commit,
            "omnitransfer.source.sha256": transfer_sha,
            "omnitransfer.archive.url": (
                f"{OMNITRANSFER_GITHUB_ROOT}/archive/{transfer_commit}.tar.gz"
            ),
            "omnitransfer.archive.sha256": PINNED_OMNITRANSFER_ARCHIVE_SHA256,
            "omnitransfer.checkpoint": checkpoint,
            "omnitransfer.checkpoint.url": (
                f"{OMNITRANSFER_RAW_ROOT}/{transfer_commit}/"
                f"src/omnitransfer/{checkpoint}"
            ),
            "omnitransfer.checkpoint.sha256": sha256_file(checkpoint_target),
        }
        updates.update(
            schema_digest_updates(
                packaged_values,
                args.catalog.parent / "omni-vlm-lite/schemas",
            )
        )
        write_properties(args.properties, lines, updates)
        args.marker.write_text(runtime_version + "\n", encoding="utf-8")
        # The prebuilt archive is extracted directly into scripts/runtime and
        # therefore its root-level manifest would otherwise overwrite the
        # packaged v9 manifest with whichever version the base archive carried.
        shutil.copy2(args.properties, staging / "runtime.properties")
        fingerprint = sha256_file(args.properties)
        installed = staging / ".runtime/installed.json"
        installed.write_text(
            json.dumps(
                {
                    "fingerprint": fingerprint,
                    "runtime_version": runtime_version,
                    "omniflow_commit": flow_commit,
                    "omniflow_catalog_release": catalog_release,
                    "omnitransfer_commit": transfer_commit,
                },
                sort_keys=True,
            ),
            encoding="utf-8",
        )
        write_deterministic_zip(staging, args.archive)

    archive_sha = sha256_file(args.archive)
    update_catalog_digest(args.catalog, archive_sha)
    release_manifest = {
        "schema_version": "omniflow.runtime-release.v1",
        "runtime_version": runtime_version,
        "python_environment_profile": PYTHON_ENVIRONMENT_PROFILE,
        "archive": args.archive.name,
        "archive_bytes": args.archive.stat().st_size,
        "archive_sha256": archive_sha,
        "omniflow_commit": flow_commit,
        "omniflow_source_sha256": flow_sha,
        "omnitransfer_commit": transfer_commit,
        "omnitransfer_source_sha256": transfer_sha,
        "omnitransfer_checkpoint": checkpoint,
        "omnitransfer_checkpoint_sha256": updates[
            "omnitransfer.checkpoint.sha256"
        ],
    }
    args.release_manifest.parent.mkdir(parents=True, exist_ok=True)
    args.release_manifest.write_text(
        json.dumps(release_manifest, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(f"PREBUILT_OMNIFLOW_RUNTIME=PASS")
    print(f"runtime_version={runtime_version}")
    print(f"omnitransfer_checkpoint={checkpoint}")
    print(f"archive_sha256={archive_sha}")
    print(f"release_manifest={args.release_manifest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
