#!/usr/bin/env python3

from __future__ import annotations

import argparse
import hashlib
import re
import shutil
import subprocess
from pathlib import Path


IGNORED_NAMES = {".DS_Store"}
IGNORED_SUFFIXES = {".pyc", ".pyo", ".pt"}
OMNITRANSFER_CHECKPOINT_PATH = (
    "checkpoints/pair_evidence_mutual_no_null_v3_20260723/no_null_seed17.npz"
)
OMNITRANSFER_RUNTIME_PATHS = (
    "__init__.py",
    "learned_matcher.py",
    "mutual_matcher.py",
    "numpy_matcher.py",
    "runtime.py",
    "schema.py",
    "ui_graph.py",
    "checkpoints/pair_evidence_mutual_no_null_v3_20260723/README.md",
    OMNITRANSFER_CHECKPOINT_PATH,
)
OMNIFLOW_TEST_PATHS = (
    "test_gui.py",
    "data/vlm_tool_call_cases.v1.json",
)
SCHEMA_NAMES = (
    "README.md",
    "oob_canonical_actions.v1.json",
    "omniflow_canonical_run_log.v1.json",
    "omniflow_function.v2.json",
    "omniflow_checker_rule.v1.json",
    "omniflow_android_bridge.v2.json",
)


def parse_args() -> argparse.Namespace:
    repo_root = Path(__file__).resolve().parents[1]
    omni_root = repo_root.parent
    parser = argparse.ArgumentParser(
        description="Sync canonical OmniFlow and OmniTransfer runtime sources into OpenOmniBot."
    )
    parser.add_argument("--check", action="store_true")
    parser.add_argument(
        "--check-embedded",
        action="store_true",
        help="Verify the embedded snapshot hashes without accessing canonical repositories.",
    )
    parser.add_argument(
        "--bootstrap-omniflow-from-embedded",
        action="store_true",
        help="Overlay the app-aligned embedded OmniFlow runtime onto the canonical repo before syncing.",
    )
    parser.add_argument("--omniflow-repo", type=Path, default=omni_root / "OmniFlow")
    parser.add_argument("--omnitransfer-repo", type=Path, default=omni_root / "OmniTransfer")
    return parser.parse_args()


def should_include(path: Path) -> bool:
    return (
        "__pycache__" not in path.parts
        and path.name not in IGNORED_NAMES
        and path.suffix not in IGNORED_SUFFIXES
    )


def files_by_relative_path(root: Path) -> dict[str, Path]:
    return {
        path.relative_to(root).as_posix(): path
        for path in root.rglob("*")
        if path.is_file() and should_include(path.relative_to(root))
    }


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def directory_sha256(root: Path) -> str:
    digest = hashlib.sha256()
    files = files_by_relative_path(root)
    for relative_path in sorted(files):
        digest.update(relative_path.encode("utf-8"))
        digest.update(b"\0")
        with files[relative_path].open("rb") as source:
            for chunk in iter(lambda: source.read(8192), b""):
                digest.update(chunk)
    return digest.hexdigest()


def git_commit(repo: Path) -> str:
    return subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=repo,
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()


def assert_source_paths(omniflow_repo: Path, omnitransfer_repo: Path) -> None:
    required = [
        omniflow_repo / "omniflow/bridge.py",
        omniflow_repo / "schemas/oob/omniflow_android_bridge.v2.json",
        omnitransfer_repo / "src/omnitransfer/runtime.py",
        omnitransfer_repo
        / "src/omnitransfer"
        / OMNITRANSFER_CHECKPOINT_PATH,
    ]
    missing = [str(path) for path in required if not path.is_file()]
    if missing:
        raise SystemExit("Missing canonical runtime sources:\n" + "\n".join(missing))


def sync_files(source_files: dict[str, Path], target: Path) -> None:
    target.mkdir(parents=True, exist_ok=True)
    target_files = files_by_relative_path(target)
    for relative_path in sorted(target_files.keys() - source_files.keys()):
        target_files[relative_path].unlink()
    for relative_path, source_file in source_files.items():
        target_file = target / relative_path
        if target_file.is_file() and file_sha256(source_file) == file_sha256(target_file):
            continue
        target_file.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source_file, target_file)
    for directory in sorted(
        (path for path in target.rglob("*") if path.is_dir()),
        key=lambda path: len(path.parts),
        reverse=True,
    ):
        try:
            directory.rmdir()
        except OSError:
            pass


def copy_tree(source: Path, target: Path) -> None:
    sync_files(files_by_relative_path(source), target)


def copy_overlay(source: Path, target: Path) -> None:
    target.mkdir(parents=True, exist_ok=True)
    for relative_path, source_file in files_by_relative_path(source).items():
        target_file = target / relative_path
        target_file.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source_file, target_file)


def copy_selected(source: Path, target: Path, relative_paths: tuple[str, ...]) -> None:
    source_files = {}
    for relative_path in relative_paths:
        source_file = source / relative_path
        if not source_file.is_file():
            raise SystemExit(f"Missing canonical runtime file: {source_file}")
        source_files[relative_path] = source_file
    sync_files(source_files, target)


def copy_named_files(
    source: Path,
    target: Path,
    relative_paths: tuple[str, ...],
) -> None:
    for relative_path in relative_paths:
        source_file = source / relative_path
        if not source_file.is_file():
            raise SystemExit(f"Missing canonical file: {source_file}")
        target_file = target / relative_path
        if target_file.is_file() and file_sha256(source_file) == file_sha256(target_file):
            continue
        target_file.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source_file, target_file)


def compare_trees(source: Path, target: Path) -> list[str]:
    source_files = files_by_relative_path(source)
    target_files = files_by_relative_path(target)
    differences = []
    for relative_path in sorted(source_files.keys() | target_files.keys()):
        if relative_path not in source_files:
            differences.append(f"extra embedded file: {relative_path}")
        elif relative_path not in target_files:
            differences.append(f"missing embedded file: {relative_path}")
        elif file_sha256(source_files[relative_path]) != file_sha256(target_files[relative_path]):
            differences.append(f"content differs: {relative_path}")
    return differences


def compare_selected(source: Path, target: Path) -> list[str]:
    expected = set(OMNITRANSFER_RUNTIME_PATHS)
    target_files = files_by_relative_path(target)
    differences = []
    for relative_path in sorted(expected | target_files.keys()):
        if relative_path not in expected:
            differences.append(f"extra embedded file: {relative_path}")
            continue
        source_file = source / relative_path
        target_file = target_files.get(relative_path)
        if target_file is None:
            differences.append(f"missing embedded file: {relative_path}")
        elif file_sha256(source_file) != file_sha256(target_file):
            differences.append(f"content differs: {relative_path}")
    return differences


def compare_named_files(
    source: Path,
    target: Path,
    relative_paths: tuple[str, ...],
) -> list[str]:
    differences = []
    for relative_path in relative_paths:
        source_file = source / relative_path
        target_file = target / relative_path
        if not source_file.is_file():
            differences.append(f"missing canonical file: {relative_path}")
        elif not target_file.is_file():
            differences.append(f"missing embedded file: {relative_path}")
        elif file_sha256(source_file) != file_sha256(target_file):
            differences.append(f"content differs: {relative_path}")
    return differences


def read_properties(path: Path) -> dict[str, str]:
    properties = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        properties[key.strip()] = value.strip()
    return properties


def write_properties(path: Path, updates: dict[str, str]) -> None:
    lines = path.read_text(encoding="utf-8").splitlines()
    seen = set()
    output = []
    for line in lines:
        if "=" not in line or line.lstrip().startswith("#"):
            output.append(line)
            continue
        key = line.split("=", 1)[0].strip()
        if key in updates:
            output.append(f"{key}={updates[key]}")
            seen.add(key)
        else:
            output.append(line)
    for key in sorted(updates.keys() - seen):
        output.append(f"{key}={updates[key]}")
    path.write_text("\n".join(output) + "\n", encoding="utf-8")


def sync_schemas(app_schema_dir: Path, omniflow_schema_dir: Path, check: bool) -> list[str]:
    differences = []
    for schema_name in SCHEMA_NAMES:
        app_schema = app_schema_dir / schema_name
        omniflow_schema = omniflow_schema_dir / schema_name
        if not app_schema.is_file():
            raise SystemExit(f"Missing OpenOmniBot schema: {app_schema}")
        if check:
            if not omniflow_schema.is_file() or file_sha256(app_schema) != file_sha256(omniflow_schema):
                differences.append(f"OmniFlow schema differs: {schema_name}")
        else:
            omniflow_schema.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(app_schema, omniflow_schema)
    return differences


def check_embedded_snapshot(
    embedded_omniflow: Path,
    embedded_omnitransfer: Path,
    runtime_properties: Path,
) -> int:
    differences = []
    properties = read_properties(runtime_properties)
    expected_hashes = {
        "omniflow.source.sha256": directory_sha256(embedded_omniflow),
        "omnitransfer.source.sha256": directory_sha256(embedded_omnitransfer),
    }
    for key, expected in expected_hashes.items():
        if properties.get(key) != expected:
            differences.append(f"runtime.properties {key} differs")
    for key in ("omniflow.commit", "omnitransfer.commit"):
        if not re.fullmatch(r"[0-9a-f]{40}", properties.get(key, "")):
            differences.append(f"runtime.properties {key} is invalid")
    transfer_files = set(files_by_relative_path(embedded_omnitransfer))
    if transfer_files != set(OMNITRANSFER_RUNTIME_PATHS):
        differences.append("embedded OmniTransfer runtime file set differs")
    if not (embedded_omniflow / "bridge.py").is_file():
        differences.append("embedded OmniFlow bridge.py is missing")
    if differences:
        print("Embedded runtime integrity check failed:")
        for difference in differences:
            print(f"- {difference}")
        return 1
    print("Embedded OmniFlow and OmniTransfer runtime snapshot integrity verified.")
    return 0


def main() -> int:
    args = parse_args()
    repo_root = Path(__file__).resolve().parents[1]
    embedded_root = repo_root / "embedded/omniflow/python"
    embedded_omniflow = embedded_root / "omniflow"
    embedded_omnitransfer = embedded_root / "omnitransfer"
    embedded_omniflow_tests = repo_root / "embedded/omniflow/tests"
    runtime_properties = repo_root / "embedded/omniflow/runtime.properties"

    selected_modes = sum(
        bool(value)
        for value in (
            args.check,
            args.check_embedded,
            args.bootstrap_omniflow_from_embedded,
        )
    )
    if selected_modes > 1:
        raise SystemExit("Select only one check or bootstrap mode")
    if args.check_embedded:
        return check_embedded_snapshot(
            embedded_omniflow,
            embedded_omnitransfer,
            runtime_properties,
        )

    omniflow_repo = args.omniflow_repo.resolve()
    omnitransfer_repo = args.omnitransfer_repo.resolve()
    assert_source_paths(omniflow_repo, omnitransfer_repo)
    canonical_omniflow = omniflow_repo / "omniflow"
    canonical_omnitransfer = omnitransfer_repo / "src/omnitransfer"
    canonical_omniflow_tests = omniflow_repo / "tests/omniflow"

    if args.check:
        differences = []
        differences.extend(
            f"OmniFlow {difference}"
            for difference in compare_trees(canonical_omniflow, embedded_omniflow)
        )
        differences.extend(
            f"OmniTransfer {difference}"
            for difference in compare_selected(canonical_omnitransfer, embedded_omnitransfer)
        )
        differences.extend(
            f"OmniFlow test {difference}"
            for difference in compare_named_files(
                canonical_omniflow_tests,
                embedded_omniflow_tests,
                OMNIFLOW_TEST_PATHS,
            )
        )
        differences.extend(
            sync_schemas(repo_root / "schemas/oob", omniflow_repo / "schemas/oob", True)
        )
        expected_properties = {
            "omniflow.commit": git_commit(omniflow_repo),
            "omniflow.source.sha256": directory_sha256(embedded_omniflow),
            "omnitransfer.commit": git_commit(omnitransfer_repo),
            "omnitransfer.source.sha256": directory_sha256(embedded_omnitransfer),
            "omnitransfer.checkpoint": OMNITRANSFER_CHECKPOINT_PATH,
        }
        actual_properties = read_properties(runtime_properties)
        for key, expected in expected_properties.items():
            if actual_properties.get(key) != expected:
                differences.append(f"runtime.properties {key} differs")
        if differences:
            print("Embedded runtime is out of sync:")
            for difference in differences:
                print(f"- {difference}")
            return 1
        print("Embedded OmniFlow and OmniTransfer runtime sources are synchronized.")
        return 0

    if args.bootstrap_omniflow_from_embedded:
        copy_overlay(embedded_omniflow, canonical_omniflow)
    sync_schemas(repo_root / "schemas/oob", omniflow_repo / "schemas/oob", False)
    copy_tree(canonical_omniflow, embedded_omniflow)
    copy_selected(canonical_omnitransfer, embedded_omnitransfer, OMNITRANSFER_RUNTIME_PATHS)
    copy_named_files(
        canonical_omniflow_tests,
        embedded_omniflow_tests,
        OMNIFLOW_TEST_PATHS,
    )
    write_properties(
        runtime_properties,
        {
            "omniflow.commit": git_commit(omniflow_repo),
            "omniflow.source.sha256": directory_sha256(embedded_omniflow),
            "omnitransfer.commit": git_commit(omnitransfer_repo),
            "omnitransfer.source.sha256": directory_sha256(embedded_omnitransfer),
            "omnitransfer.checkpoint": OMNITRANSFER_CHECKPOINT_PATH,
        },
    )
    print("Synchronized canonical OmniFlow and OmniTransfer runtime sources.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
