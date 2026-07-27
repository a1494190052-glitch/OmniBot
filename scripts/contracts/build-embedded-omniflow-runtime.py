#!/usr/bin/env python3

from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil
import subprocess
from tempfile import TemporaryDirectory
from urllib.request import urlopen
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

IGNORED_NAMES = {".DS_Store"}
IGNORED_SUFFIXES = {".pyc", ".pyo"}
SCHEMA_NAMES = (
    "README.md",
    "oob_canonical_actions.v1.json",
    "omniflow_canonical_run_log.v1.json",
    "omniflow_function.v2.json",
    "omniflow_checker_rule.v1.json",
    "omniflow_android_bridge.v2.json",
)


@dataclass(frozen=True)
class RuntimeSource:
    package: Path
    commit: str
    dirty: bool
    mode: str


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Build the versioned OmniFlow runtime assets consumed by Android."
    )
    parser.add_argument("--repo-root", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--cache-dir", type=Path, required=True)
    parser.add_argument("--omniflow-source", type=Path)
    parser.add_argument("--omnitransfer-source", type=Path)
    parser.add_argument("--allow-dirty", action="store_true")
    return parser.parse_args()


def read_properties(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if line and not line.startswith(("#", "!")) and "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def required(values: dict[str, str], key: str, source: Path) -> str:
    value = values.get(key, "").strip()
    if not value:
        raise RuntimeError(f"{source} missing {key}")
    return value


def included_files(root: Path, *, omit_pt: bool = False) -> list[Path]:
    return sorted(
        path
        for path in root.rglob("*")
        if path.is_file()
        and "__pycache__" not in path.relative_to(root).parts
        and path.name not in IGNORED_NAMES
        and path.suffix not in IGNORED_SUFFIXES
        and not (omit_pt and path.suffix == ".pt")
    )


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sha256_directory(root: Path) -> str:
    digest = hashlib.sha256()
    for path in included_files(root):
        digest.update(path.relative_to(root).as_posix().encode("utf-8"))
        digest.update(b"\0")
        with path.open("rb") as source:
            for chunk in iter(lambda: source.read(8192), b""):
                digest.update(chunk)
    return digest.hexdigest()


def git_output(package: Path, *arguments: str) -> str | None:
    result = subprocess.run(
        ["git", *arguments],
        cwd=package,
        capture_output=True,
        text=True,
        check=False,
    )
    return result.stdout.strip() if result.returncode == 0 else None


def resolve_override(root: Path, relative: str, package_name: str) -> Path:
    root = root.expanduser().resolve()
    nested = root / relative
    if nested.is_dir():
        return nested
    if root.name == package_name and root.is_dir():
        return root
    raise RuntimeError(f"source does not contain {relative}: {root}")


def runtime_source(
    *,
    override: Path | None,
    relative: str,
    package_name: str,
    embedded: Path,
    embedded_commit: str,
    embedded_sha256: str,
    allow_dirty: bool,
    label: str,
) -> RuntimeSource:
    if override is None:
        actual_sha256 = sha256_directory(embedded)
        if actual_sha256 != embedded_sha256:
            raise RuntimeError(
                f"{label} embedded source drifted: "
                f"expected={embedded_sha256} actual={actual_sha256}"
            )
        return RuntimeSource(embedded, embedded_commit, False, "embedded")

    package = resolve_override(override, relative, package_name)
    commit = git_output(package, "rev-parse", "HEAD") or "unversioned"
    dirty_output = git_output(
        package,
        "status",
        "--porcelain",
        "--untracked-files=all",
        "--",
        ".",
    )
    dirty = dirty_output is None or bool(dirty_output)
    if dirty and not allow_dirty:
        raise RuntimeError(
            f"{label} source is dirty or unversioned: {package}. "
            "Commit it first or pass --allow-dirty."
        )
    return RuntimeSource(package, commit, dirty, "override")


def copy_package(
    source: Path,
    target: Path,
    *,
    omit_pt: bool = False,
    checkpoint: PurePosixPath | None = None,
) -> None:
    for path in included_files(source, omit_pt=omit_pt):
        relative = PurePosixPath(path.relative_to(source).as_posix())
        if (
            checkpoint is not None
            and relative.parts[:1] == ("checkpoints",)
            and relative.suffix in {".npz", ".pt"}
            and relative != checkpoint
        ):
            continue
        destination = target / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, destination)


def download_verified(target: Path, url: str, expected_sha256: str) -> None:
    if target.is_file() and sha256_file(target) == expected_sha256:
        return
    target.unlink(missing_ok=True)
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + ".part")
    temporary.unlink(missing_ok=True)
    with urlopen(url) as source, temporary.open("wb") as destination:
        shutil.copyfileobj(source, destination)
    actual_sha256 = sha256_file(temporary)
    if actual_sha256 != expected_sha256:
        temporary.unlink(missing_ok=True)
        raise RuntimeError(
            f"runtime download checksum mismatch: "
            f"expected={expected_sha256} actual={actual_sha256}"
        )
    temporary.replace(target)


def extract_zip(archive: Path, target: Path) -> None:
    with ZipFile(archive) as source:
        for entry in source.infolist():
            relative = PurePosixPath(entry.filename)
            if relative.is_absolute() or ".." in relative.parts:
                raise RuntimeError(
                    f"zip entry escapes runtime directory: {entry.filename}"
                )
        source.extractall(target)


def write_reproducible_zip(source: Path, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(target, "w", compression=ZIP_DEFLATED, compresslevel=9) as archive:
        for path in included_files(source):
            relative = path.relative_to(source).as_posix()
            entry = ZipInfo(relative, date_time=(1980, 1, 1, 0, 0, 0))
            entry.compress_type = ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            archive.writestr(entry, path.read_bytes(), compresslevel=9)


def build(args: argparse.Namespace) -> dict[str, str]:
    repo = args.repo_root.resolve()
    runtime_root = repo / "embedded/omniflow"
    runtime_properties_file = runtime_root / "runtime.properties"
    properties = read_properties(runtime_properties_file)
    embedded_root = runtime_root / "python"
    omniflow = runtime_source(
        override=args.omniflow_source,
        relative="omniflow",
        package_name="omniflow",
        embedded=embedded_root / "omniflow",
        embedded_commit=required(
            properties, "omniflow.commit", runtime_properties_file
        ),
        embedded_sha256=required(
            properties, "omniflow.source.sha256", runtime_properties_file
        ),
        allow_dirty=args.allow_dirty,
        label="OmniFlow",
    )
    omnitransfer = runtime_source(
        override=args.omnitransfer_source,
        relative="src/omnitransfer",
        package_name="omnitransfer",
        embedded=embedded_root / "omnitransfer",
        embedded_commit=required(
            properties, "omnitransfer.commit", runtime_properties_file
        ),
        embedded_sha256=required(
            properties, "omnitransfer.source.sha256", runtime_properties_file
        ),
        allow_dirty=args.allow_dirty,
        label="OmniTransfer",
    )

    contract_file = repo / "schemas/oob/omniflow_android_bridge.v2.json"
    contract = json.loads(contract_file.read_text(encoding="utf-8"))
    capabilities = sorted((contract.get("operations") or {}).keys())
    if not capabilities:
        raise RuntimeError("Android bridge contract operations are required")

    output = args.output_dir.resolve()
    cache = args.cache_dir.resolve()
    checkpoint = required(
        properties, "omnitransfer.checkpoint", runtime_properties_file
    )
    checkpoint_path = PurePosixPath(checkpoint)
    if checkpoint_path.is_absolute() or ".." in checkpoint_path.parts:
        raise RuntimeError("OmniTransfer checkpoint path must be package-relative")

    numpy_url = required(properties, "numpy.url", runtime_properties_file)
    numpy_sha256 = required(properties, "numpy.sha256", runtime_properties_file)
    numpy_wheel = cache / f"numpy-{numpy_sha256}.whl"
    download_verified(numpy_wheel, numpy_url, numpy_sha256)
    json_repair_url = required(properties, "json_repair.url", runtime_properties_file)
    json_repair_sha256 = required(
        properties,
        "json_repair.sha256",
        runtime_properties_file,
    )
    json_repair_wheel = cache / f"json-repair-{json_repair_sha256}.whl"
    download_verified(json_repair_wheel, json_repair_url, json_repair_sha256)

    with TemporaryDirectory(prefix="oob-omniflow-runtime-") as temporary:
        staging = Path(temporary)
        site_packages = staging / "site-packages"
        site_packages.mkdir()
        shutil.copyfile(
            embedded_root / "oob_omniflow_bridge.py",
            site_packages / "oob_omniflow_bridge.py",
        )
        copy_package(omniflow.package, site_packages / "omniflow")
        copy_package(
            omnitransfer.package,
            site_packages / "omnitransfer",
            omit_pt=True,
            checkpoint=checkpoint_path,
        )
        for required_file in (
            site_packages / "omniflow/bridge.py",
            site_packages / "omnitransfer/runtime.py",
            site_packages / "omnitransfer/numpy_matcher.py",
            site_packages / f"omnitransfer/{checkpoint}",
        ):
            if not required_file.is_file():
                raise RuntimeError(f"runtime source is incomplete: {required_file}")

        schema_target = site_packages / "schemas/oob"
        schema_target.mkdir(parents=True)
        for name in SCHEMA_NAMES:
            shutil.copyfile(repo / f"schemas/oob/{name}", schema_target / name)
        extract_zip(numpy_wheel, site_packages)
        extract_zip(json_repair_wheel, site_packages)
        for required_file in (
            site_packages / "json_repair/__init__.py",
            site_packages
            / f"json_repair-{required(properties, 'json_repair.version', runtime_properties_file)}.dist-info/licenses/LICENSE",
        ):
            if not required_file.is_file():
                raise RuntimeError(f"runtime dependency is incomplete: {required_file}")

        effective_properties = dict(properties)
        effective_properties.update(
            {
                "omniflow.commit": omniflow.commit,
                "omniflow.source.mode": omniflow.mode,
                "omniflow.source.dirty": str(omniflow.dirty).lower(),
                "omniflow.source.sha256": sha256_directory(site_packages / "omniflow"),
                "omnitransfer.commit": omnitransfer.commit,
                "omnitransfer.source.mode": omnitransfer.mode,
                "omnitransfer.source.dirty": str(omnitransfer.dirty).lower(),
                "omnitransfer.source.sha256": sha256_directory(
                    site_packages / "omnitransfer"
                ),
            }
        )
        (staging / "runtime.properties").write_text(
            "".join(
                f"{key}={effective_properties[key]}\n"
                for key in sorted(effective_properties)
            ),
            encoding="utf-8",
        )

        output.mkdir(parents=True, exist_ok=True)
        bundle = output / "bundle.zip"
        write_reproducible_zip(staging, bundle)
        manifest = {
            "runtime.version": required(
                properties, "runtime.version", runtime_properties_file
            ),
            "runtime.protocol": str(contract.get("protocol_version") or ""),
            "runtime.capabilities": ",".join(capabilities),
            "runtime.python": required(
                properties, "runtime.python", runtime_properties_file
            ),
            "runtime.platform": required(
                properties, "runtime.platform", runtime_properties_file
            ),
            "bridge.contract.sha256": sha256_file(contract_file),
            "omniflow.commit": omniflow.commit,
            "omniflow.source.sha256": effective_properties["omniflow.source.sha256"],
            "omnitransfer.commit": omnitransfer.commit,
            "omnitransfer.source.sha256": effective_properties[
                "omnitransfer.source.sha256"
            ],
            "omnitransfer.checkpoint": checkpoint,
            "numpy.version": required(
                properties, "numpy.version", runtime_properties_file
            ),
            "json_repair.version": required(
                properties,
                "json_repair.version",
                runtime_properties_file,
            ),
            "bundle.sha256": sha256_file(bundle),
        }
        if not manifest["runtime.protocol"]:
            raise RuntimeError("Android bridge contract protocol_version is required")
        (output / "manifest.properties").write_text(
            "".join(f"{key}={value}\n" for key, value in manifest.items()),
            encoding="utf-8",
        )
    return manifest


def main() -> int:
    args = parse_args()
    try:
        manifest = build(args)
    except Exception as error:
        print(f"OMNIFLOW_RUNTIME_BUILD=FAIL\n- {error}")
        return 1
    print("OMNIFLOW_RUNTIME_BUILD=PASS")
    print(json.dumps(manifest, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
