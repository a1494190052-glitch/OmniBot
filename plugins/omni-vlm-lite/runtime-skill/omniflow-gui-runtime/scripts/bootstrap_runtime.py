#!/usr/bin/env python3

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil
import tarfile
from tempfile import TemporaryDirectory
from urllib.request import Request, urlopen
from zipfile import ZipFile


SCHEMA_NAMES = (
    "README.md",
    "omniflow_android_bridge.v2.json",
    "omniflow_canonical_run_log.v1.json",
    "omniflow_checker_rule.v1.json",
    "omniflow_function.v2.json",
    "oob_canonical_actions.v1.json",
)
OMNITRANSFER_FILES = (
    "__init__.py",
    "learned_matcher.py",
    "mutual_matcher.py",
    "numpy_matcher.py",
    "runtime.py",
    "schema.py",
    "ui_graph.py",
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--skill-root", type=Path, required=True)
    return parser.parse_args()


def read_properties(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if line and not line.startswith(("#", "!")) and "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def required(values: dict[str, str], key: str) -> str:
    value = values.get(key, "").strip()
    if not value:
        raise RuntimeError(f"runtime_property_missing:{key}")
    return value


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sha256_directory(root: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(item for item in root.rglob("*") if item.is_file()):
        if "__pycache__" in path.relative_to(root).parts or path.suffix in {".pyc", ".pyo"}:
            continue
        digest.update(path.relative_to(root).as_posix().encode("utf-8"))
        digest.update(b"\0")
        with path.open("rb") as source:
            for chunk in iter(lambda: source.read(8192), b""):
                digest.update(chunk)
    return digest.hexdigest()


def download(url: str, expected_sha256: str, target: Path) -> None:
    request = Request(url, headers={"User-Agent": "OpenOmniBot-OmniFlow-Skill"})
    with urlopen(request, timeout=180) as source, target.open("wb") as output:
        shutil.copyfileobj(source, output)
    actual_sha256 = sha256_file(target)
    if actual_sha256 != expected_sha256:
        raise RuntimeError(
            f"runtime_download_checksum_mismatch:{target.name}:"
            f"{expected_sha256}:{actual_sha256}"
        )


def safe_extract_tar(archive: Path, target: Path) -> Path:
    with tarfile.open(archive, "r:gz") as source:
        members = source.getmembers()
        for member in members:
            relative = PurePosixPath(member.name)
            if relative.is_absolute() or ".." in relative.parts or member.issym() or member.islnk():
                raise RuntimeError(f"runtime_archive_entry_invalid:{member.name}")
        source.extractall(target, members=members)
    roots = [item for item in target.iterdir() if item.is_dir()]
    if len(roots) != 1:
        raise RuntimeError("runtime_archive_root_invalid")
    return roots[0]


def safe_extract_zip(archive: Path, target: Path) -> None:
    with ZipFile(archive) as source:
        for member in source.infolist():
            relative = PurePosixPath(member.filename)
            if relative.is_absolute() or ".." in relative.parts:
                raise RuntimeError(f"runtime_wheel_entry_invalid:{member.filename}")
        source.extractall(target)


def verify_schema_files(schema_root: Path, values: dict[str, str]) -> None:
    for name in SCHEMA_NAMES:
        path = schema_root / name
        if not path.is_file():
            raise RuntimeError(f"runtime_schema_missing:{name}")
        expected = required(values, f"schema.{name}.sha256")
        if sha256_file(path) != expected:
            raise RuntimeError(f"runtime_schema_checksum_mismatch:{name}")


def runtime_ready(skill_root: Path, values: dict[str, str], fingerprint: str) -> bool:
    runtime_root = skill_root / "scripts/runtime"
    marker = runtime_root / ".runtime/installed.json"
    if not marker.is_file():
        return False
    payload = json.loads(marker.read_text(encoding="utf-8"))
    if payload.get("fingerprint") != fingerprint:
        return False
    python_root = runtime_root / "python"
    transfer_root = runtime_root / ".runtime/omnitransfer/src/omnitransfer"
    if sha256_directory(python_root / "omniflow") != required(values, "omniflow.source.sha256"):
        return False
    if sha256_directory(transfer_root) != required(values, "omnitransfer.source.sha256"):
        return False
    verify_schema_files(python_root / "schemas/oob", values)
    return (
        (runtime_root / ".runtime/site-packages/numpy/__init__.py").is_file()
        and (runtime_root / ".runtime/site-packages/json_repair/__init__.py").is_file()
    )


def replace_directory(source: Path, target: Path) -> None:
    backup = target.with_name(f".{target.name}.previous")
    shutil.rmtree(backup, ignore_errors=True)
    if target.exists():
        target.rename(backup)
    try:
        source.rename(target)
    except Exception:
        if backup.exists() and not target.exists():
            backup.rename(target)
        raise
    shutil.rmtree(backup, ignore_errors=True)


def install(skill_root: Path) -> None:
    skill_root = skill_root.expanduser().resolve()
    runtime_root = skill_root / "scripts/runtime"
    properties_path = runtime_root / "runtime.properties"
    values = read_properties(properties_path)
    fingerprint = sha256_file(properties_path)
    if runtime_ready(skill_root, values, fingerprint):
        return

    schema_source = skill_root / "schemas/oob"
    verify_schema_files(schema_source, values)
    runtime_root.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory(prefix=".bootstrap-", dir=runtime_root) as temporary:
        stage = Path(temporary)
        archives = stage / "archives"
        archives.mkdir()
        flow_archive = archives / "omniflow.tar.gz"
        transfer_archive = archives / "omnitransfer.tar.gz"
        checkpoint = archives / "omnitransfer-checkpoint.npz"
        numpy_wheel = archives / "numpy.whl"
        json_repair_wheel = archives / "json_repair.whl"
        download(required(values, "omniflow.archive.url"), required(values, "omniflow.archive.sha256"), flow_archive)
        download(required(values, "omnitransfer.archive.url"), required(values, "omnitransfer.archive.sha256"), transfer_archive)
        download(required(values, "omnitransfer.checkpoint.url"), required(values, "omnitransfer.checkpoint.sha256"), checkpoint)
        download(required(values, "numpy.url"), required(values, "numpy.sha256"), numpy_wheel)
        download(required(values, "json_repair.url"), required(values, "json_repair.sha256"), json_repair_wheel)

        extracted_flow = safe_extract_tar(flow_archive, stage / "flow-source")
        extracted_transfer = safe_extract_tar(transfer_archive, stage / "transfer-source")
        python_target = stage / "python"
        shutil.copytree(extracted_flow / "omniflow", python_target / "omniflow")
        shutil.copytree(schema_source, python_target / "schemas/oob")

        transfer_source = extracted_transfer / "src/omnitransfer"
        transfer_target = stage / "runtime/omnitransfer/src/omnitransfer"
        transfer_target.mkdir(parents=True)
        for name in OMNITRANSFER_FILES:
            shutil.copyfile(transfer_source / name, transfer_target / name)
        checkpoint_target = transfer_target / required(values, "omnitransfer.checkpoint")
        checkpoint_target.parent.mkdir(parents=True)
        shutil.copyfile(checkpoint, checkpoint_target)

        site_packages = stage / "runtime/site-packages"
        site_packages.mkdir(parents=True)
        safe_extract_zip(numpy_wheel, site_packages)
        safe_extract_zip(json_repair_wheel, site_packages)
        if sha256_directory(python_target / "omniflow") != required(values, "omniflow.source.sha256"):
            raise RuntimeError("omniflow_source_checksum_mismatch")
        if sha256_directory(transfer_target) != required(values, "omnitransfer.source.sha256"):
            raise RuntimeError("omnitransfer_source_checksum_mismatch")
        verify_schema_files(python_target / "schemas/oob", values)
        (stage / "runtime/installed.json").write_text(
            json.dumps(
                {
                    "fingerprint": fingerprint,
                    "runtime_version": required(values, "runtime.version"),
                    "omniflow_commit": required(values, "omniflow.commit"),
                    "omnitransfer_commit": required(values, "omnitransfer.commit"),
                },
                sort_keys=True,
            ),
            encoding="utf-8",
        )
        replace_directory(stage / "runtime", runtime_root / ".runtime")
        replace_directory(python_target, runtime_root / "python")


def main() -> int:
    try:
        install(parse_args().skill_root)
    except Exception as error:
        print(f"OMNIFLOW_SKILL_INSTALL=FAIL\n- {error}")
        return 1
    print("OMNIFLOW_SKILL_INSTALL=PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
