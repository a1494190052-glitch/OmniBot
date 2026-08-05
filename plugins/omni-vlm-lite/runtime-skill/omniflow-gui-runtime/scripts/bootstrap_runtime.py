#!/usr/bin/env python3

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import sys
import tarfile
from tempfile import TemporaryDirectory
import time
from urllib.request import Request, urlopen
from zipfile import ZipFile


SCHEMA_NAMES = (
    "README.md",
    "omniflow_android_bridge.v2.json",
    "omniflow_run_log.v1.json",
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
COMPAT_SOURCE_FILES = (
    "src/__init__.py",
    "src/integrations/__init__.py",
    "src/integrations/runlog.py",
)
COMPAT_RUNLOG_PATH = "src/integrations/runlog.py"
DOWNLOAD_TIMEOUT_SECONDS = 45
DOWNLOAD_ATTEMPTS = 2


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


def download(
    url: str,
    expected_sha256: str,
    target: Path,
    fallback_urls: tuple[str, ...] = (),
) -> None:
    started_at = time.monotonic()
    last_error: Exception | None = None
    for candidate_url in (url, *fallback_urls):
        for attempt in range(DOWNLOAD_ATTEMPTS):
            try:
                if target.is_file() and sha256_file(target) == expected_sha256:
                    stage(
                        "download_cached",
                        f"name={target.name} bytes={target.stat().st_size} "
                        f"durationMs={int((time.monotonic() - started_at) * 1000)}",
                    )
                    return
                offset = target.stat().st_size if target.is_file() else 0
                headers = {"User-Agent": "OpenOmniBot-OmniFlow-Skill"}
                if offset:
                    headers["Range"] = f"bytes={offset}-"
                request = Request(
                    candidate_url,
                    headers=headers,
                )
                with urlopen(request, timeout=DOWNLOAD_TIMEOUT_SECONDS) as source:
                    status = getattr(source, "status", source.getcode())
                    content_range = source.headers.get("Content-Range", "")
                    can_resume = offset > 0 and status == 206 and content_range.startswith(
                        f"bytes {offset}-"
                    )
                    expected_size = None
                    if can_resume and "/" in content_range:
                        total = content_range.rsplit("/", 1)[1]
                        if total.isdigit():
                            expected_size = int(total)
                    elif source.headers.get("Content-Length", "").isdigit():
                        expected_size = int(source.headers["Content-Length"])
                    with target.open("ab" if can_resume else "wb") as output:
                        shutil.copyfileobj(source, output)
                if expected_size is not None and target.stat().st_size < expected_size:
                    raise RuntimeError(
                        f"runtime_download_incomplete:{target.name}:"
                        f"{target.stat().st_size}:{expected_size}"
                    )
                actual_sha256 = sha256_file(target)
                if actual_sha256 != expected_sha256:
                    target.unlink(missing_ok=True)
                    raise RuntimeError(
                        f"runtime_download_checksum_mismatch:{target.name}:"
                        f"{expected_sha256}:{actual_sha256}"
                    )
                stage(
                    "download_saved",
                    f"name={target.name} bytes={target.stat().st_size} "
                    f"durationMs={int((time.monotonic() - started_at) * 1000)}",
                )
                return
            except Exception as error:
                last_error = error
                if attempt + 1 < DOWNLOAD_ATTEMPTS:
                    time.sleep(1)
        target.unlink(missing_ok=True)
    raise RuntimeError(f"runtime_download_failed:{target.name}:{last_error}") from last_error


def stage(name: str, detail: str = "") -> None:
    suffix = f" {detail}" if detail else ""
    print(f"OMNIFLOW_STAGE={name}{suffix}", flush=True)


def download_many(
    jobs: tuple[tuple[str, str, str, Path, tuple[str, ...]], ...],
) -> None:
    with ThreadPoolExecutor(max_workers=len(jobs)) as executor:
        futures = {
            executor.submit(download, url, sha256, target, fallbacks): name
            for name, url, sha256, target, fallbacks in jobs
        }
        for future in as_completed(futures):
            name = futures[future]
            try:
                future.result()
            except Exception as error:
                stage("download_failed", f"{name} error={error}")
                raise
            stage("download_ready", name)


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


def stage_compat_sources(
    skill_root: Path,
    python_target: Path,
    values: dict[str, str],
) -> None:
    compat_root = skill_root / "scripts/compat"
    for relative in COMPAT_SOURCE_FILES:
        source = compat_root / relative
        if not source.is_file():
            raise RuntimeError(f"runtime_compat_source_missing:{relative}")
        target = python_target / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    expected = required(values, "compat.androidworld_runlog.sha256")
    if sha256_file(python_target / COMPAT_RUNLOG_PATH) != expected:
        raise RuntimeError("runtime_compat_source_checksum_mismatch")


def verify_runtime_imports(
    python_root: Path,
    site_packages: Path,
    transfer_root: Path,
) -> None:
    stage("verify_import_start")
    environment = os.environ.copy()
    environment["PYTHONPATH"] = os.pathsep.join(
        (str(python_root), str(site_packages), str(transfer_root / "src"))
    )
    result = subprocess.run(
        [sys.executable, "-c", "import omniflow.bridge"],
        capture_output=True,
        check=False,
        env=environment,
        text=True,
        timeout=60,
    )
    if result.returncode != 0:
        detail = (result.stderr or result.stdout).strip()[-1200:]
        raise RuntimeError(f"runtime_bridge_import_failed:{detail}")
    stage("verify_import_ready")


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
    compat_runlog = python_root / COMPAT_RUNLOG_PATH
    if (
        not compat_runlog.is_file()
        or sha256_file(compat_runlog)
        != required(values, "compat.androidworld_runlog.sha256")
    ):
        return False
    verify_schema_files(python_root / "schemas/oob", values)
    return (
        (runtime_root / ".runtime/site-packages/numpy/__init__.py").is_file()
        and (runtime_root / ".runtime/site-packages/json_repair/__init__.py").is_file()
    )


def download_cache_root(skill_root: Path) -> Path:
    # Keep verified artifacts outside the versioned skill directory. The Android
    # manager replaces that directory on runtime updates, while these artifacts
    # remain valid as long as their SHA-256 matches the new manifest.
    return skill_root.parent.parent / "runtime-cache" / skill_root.name


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
        stage("runtime_ready_cached")
        return

    schema_source = skill_root / "schemas/oob"
    verify_schema_files(schema_source, values)
    runtime_root.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory(prefix=".bootstrap-", dir=runtime_root) as temporary:
        staging_root = Path(temporary)
        archives = staging_root / "archives"
        archives.mkdir()
        flow_archive = archives / "omniflow.tar.gz"
        transfer_archive = archives / "omnitransfer.tar.gz"
        checkpoint = archives / "omnitransfer-checkpoint.npz"
        numpy_wheel = archives / "numpy.whl"
        json_repair_wheel = archives / "json_repair.whl"
        cache_root = download_cache_root(skill_root)
        cache_root.mkdir(parents=True, exist_ok=True)
        cached_flow_archive = cache_root / flow_archive.name
        cached_transfer_archive = cache_root / transfer_archive.name
        cached_checkpoint = cache_root / checkpoint.name
        cached_numpy_wheel = cache_root / numpy_wheel.name
        cached_json_repair_wheel = cache_root / json_repair_wheel.name
        numpy_fallback_url = values.get("numpy.fallback_url", "").strip()
        stage("downloads_start", "count=5")
        download_many(
            (
                (
                    "omniflow_archive",
                    required(values, "omniflow.archive.url"),
                    required(values, "omniflow.archive.sha256"),
                    cached_flow_archive,
                    (),
                ),
                (
                    "omnitransfer_archive",
                    required(values, "omnitransfer.archive.url"),
                    required(values, "omnitransfer.archive.sha256"),
                    cached_transfer_archive,
                    (),
                ),
                (
                    "omnitransfer_checkpoint",
                    required(values, "omnitransfer.checkpoint.url"),
                    required(values, "omnitransfer.checkpoint.sha256"),
                    cached_checkpoint,
                    (),
                ),
                (
                    "numpy_wheel",
                    required(values, "numpy.url"),
                    required(values, "numpy.sha256"),
                    cached_numpy_wheel,
                    (numpy_fallback_url,) if numpy_fallback_url else (),
                ),
                (
                    "json_repair_wheel",
                    required(values, "json_repair.url"),
                    required(values, "json_repair.sha256"),
                    cached_json_repair_wheel,
                    (),
                ),
            )
        )
        for cached, target in (
            (cached_flow_archive, flow_archive),
            (cached_transfer_archive, transfer_archive),
            (cached_checkpoint, checkpoint),
            (cached_numpy_wheel, numpy_wheel),
            (cached_json_repair_wheel, json_repair_wheel),
        ):
            shutil.copyfile(cached, target)
        stage("downloads_ready")

        extracted_flow = safe_extract_tar(flow_archive, staging_root / "flow-source")
        extracted_transfer = safe_extract_tar(transfer_archive, staging_root / "transfer-source")
        python_target = staging_root / "python"
        shutil.copytree(extracted_flow / "omniflow", python_target / "omniflow")
        shutil.copytree(schema_source, python_target / "schemas/oob")
        stage_compat_sources(skill_root, python_target, values)

        transfer_source = extracted_transfer / "src/omnitransfer"
        transfer_target = staging_root / "runtime/omnitransfer/src/omnitransfer"
        transfer_target.mkdir(parents=True)
        for name in OMNITRANSFER_FILES:
            shutil.copyfile(transfer_source / name, transfer_target / name)
        checkpoint_target = transfer_target / required(values, "omnitransfer.checkpoint")
        checkpoint_target.parent.mkdir(parents=True)
        shutil.copyfile(checkpoint, checkpoint_target)

        site_packages = staging_root / "runtime/site-packages"
        site_packages.mkdir(parents=True)
        safe_extract_zip(numpy_wheel, site_packages)
        safe_extract_zip(json_repair_wheel, site_packages)
        if sha256_directory(python_target / "omniflow") != required(values, "omniflow.source.sha256"):
            raise RuntimeError("omniflow_source_checksum_mismatch")
        if sha256_directory(transfer_target) != required(values, "omnitransfer.source.sha256"):
            raise RuntimeError("omnitransfer_source_checksum_mismatch")
        verify_schema_files(python_target / "schemas/oob", values)
        verify_runtime_imports(python_target, site_packages, staging_root / "runtime/omnitransfer")
        (staging_root / "runtime/installed.json").write_text(
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
        replace_directory(staging_root / "runtime", runtime_root / ".runtime")
        replace_directory(python_target, runtime_root / "python")
    stage("runtime_install_ready")


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
