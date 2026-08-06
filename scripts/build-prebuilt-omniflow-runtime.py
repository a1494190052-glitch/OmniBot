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
    "runtime.py",
    "schema.py",
    "ui_graph.py",
)

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
            "Refresh OmniFlow while retaining the already pinned canonical "
            "OmniTransfer source and portable checkpoint in the archive."
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


def copy_tree(source: Path, target: Path) -> None:
    shutil.copytree(
        source,
        target,
        ignore=shutil.ignore_patterns("__pycache__", "*.pyc", "*.pyo", ".DS_Store"),
    )


def replace_once(path: Path, old: str, new: str) -> None:
    source = path.read_text(encoding="utf-8")
    if source.count(old) != 1:
        raise RuntimeError(f"omniflow_runtime_patch_context_mismatch:{path.name}")
    path.write_text(source.replace(old, new, 1), encoding="utf-8")


def apply_completion_biased_transfer_thresholds(transfer_target: Path) -> None:
    """Bundle the no-abstain coordinate fallback from canonical OmniTransfer."""
    runtime = transfer_target / "runtime.py"
    source = runtime.read_text(encoding="utf-8")
    probability_pattern = re.compile(
        r"^_DEFAULT_MATCHER_MIN_PROBABILITY\s*=\s*[0-9.]+\s*$",
        re.MULTILINE,
    )
    margin_pattern = re.compile(
        r"^_DEFAULT_MATCHER_MIN_MARGIN\s*=\s*[0-9.]+\s*$",
        re.MULTILINE,
    )
    if len(probability_pattern.findall(source)) != 1:
        raise RuntimeError("omnitransfer_probability_threshold_context_mismatch")
    if len(margin_pattern.findall(source)) != 1:
        raise RuntimeError("omnitransfer_margin_threshold_context_mismatch")
    source = probability_pattern.sub(
        "_DEFAULT_MATCHER_MIN_PROBABILITY = 0.01",
        source,
        count=1,
    )
    source = margin_pattern.sub(
        "_DEFAULT_MATCHER_MIN_MARGIN = 0.0",
        source,
        count=1,
    )
    if "_coordinate_stretch_result" not in source:
        matcher_error = '''    except Exception as error:
        return {
            "mapped": False,
            "mapping_mode": _MATCHER_MODE,
            "matcher_release": _MATCHER_RELEASE,
            "reason": "matcher_unavailable",
            "error": str(error) or type(error).__name__,
            "src_element": _node_dict(source_node),
            "source_size": _graph_size(source),
            "target_size": _graph_size(target),
        }
'''
        matcher_error_fallback = '''    except Exception as error:
        return _coordinate_stretch_result(
            source=source,
            target=target,
            source_node=source_node,
            source_point=source_point,
            fallback_reason="matcher_unavailable",
            error=str(error) or type(error).__name__,
            action_type=action_type,
            source_activity_name=source_activity_name,
            target_activity_name=target_activity_name,
        )
'''
        if source.count(matcher_error) != 1:
            raise RuntimeError("omnitransfer_matcher_error_context_mismatch")
        source = source.replace(matcher_error, matcher_error_fallback, 1)

        abstain = '''    ranked = _learned_candidates(target, match.scores)
    target_node = match.target_node
    completion_biased_top_candidate = target_node is None and bool(ranked)
    if completion_biased_top_candidate:
        target_node = ranked[0][1]
    if target_node is None or target_node.bbox is None:
        return {
            "mapped": False,
            "mapping_mode": _MATCHER_MODE,
            "reason": str(match.reason or "matcher_abstained"),
            "src_element": _node_dict(source_node),
            "source_size": _graph_size(source),
            "target_size": _graph_size(target),
            "score": float(match.probability),
            "pair_confidence": float(match.probability),
            "rank_probability": _top_rank_probability(ranked),
            "margin": float(match.margin),
            "top_candidates": _candidate_dicts(ranked, top_k),
            **matcher_metadata,
        }
'''
        abstain_fallback = '''    ranked = _learned_candidates(target, match.scores)
    target_node = match.target_node
    if target_node is None or target_node.bbox is None:
        return _coordinate_stretch_result(
            source=source,
            target=target,
            source_node=source_node,
            source_point=source_point,
            fallback_reason=str(match.reason or "matcher_abstained"),
            score=float(match.probability),
            margin=float(match.margin),
            ranked=ranked,
            top_k=top_k,
            action_type=action_type,
            source_activity_name=source_activity_name,
            target_activity_name=target_activity_name,
            matcher_metadata=matcher_metadata,
        )
'''
        if source.count(abstain) != 1:
            raise RuntimeError("omnitransfer_abstain_context_mismatch")
        source = source.replace(abstain, abstain_fallback, 1)

        completion_mode = '''        mapping_mode=(
            f"{_MATCHER_MODE}_completion_biased_top_candidate"
            if completion_biased_top_candidate
            else _MATCHER_MODE
        ),
'''
        if source.count(completion_mode) != 1:
            raise RuntimeError("omnitransfer_completion_mode_context_mismatch")
        source = source.replace(completion_mode, "        mapping_mode=_MATCHER_MODE,\n", 1)

        helper_anchor = '''    return result


def _get_matcher()'''
        helper = '''    return result


def _coordinate_stretch_result(
    *,
    source: UIGraph,
    target: UIGraph,
    source_node: UINode,
    source_point: tuple[float, float] | None,
    fallback_reason: str,
    score: float = 0.0,
    margin: float = 0.0,
    ranked: list[tuple[float, UINode]] | None = None,
    top_k: int = 1,
    error: str = "",
    action_type: str,
    source_activity_name: str | None,
    target_activity_name: str | None,
    matcher_metadata: dict[str, str] | None = None,
) -> dict[str, Any]:
    """Turn matcher abstention into deterministic relative-coordinate replay."""

    source_size = _graph_size(source)
    target_size = _graph_size(target)
    if source_point is None or source_size is None or target_size is None:
        return {
            "mapped": False,
            "mapping_mode": _MATCHER_MODE,
            "reason": "coordinate_stretch_unavailable",
            "fallback_reason": fallback_reason,
        }
    source_width, source_height = source_size
    target_width, target_height = target_size
    new_x = _clamp(float(source_point[0]) / source_width) * target_width
    new_y = _clamp(float(source_point[1]) / source_height) * target_height
    candidates = list(ranked or ())
    result: dict[str, Any] = {
        "mapped": True,
        "mapping_mode": f"{_MATCHER_MODE}_coordinate_stretch_fallback",
        "new_x": new_x,
        "new_y": new_y,
        "src_element": _node_dict(source_node),
        "source_size": source_size,
        "target_size": target_size,
        "score": float(score),
        "pair_confidence": float(score),
        "rank_probability": _top_rank_probability(candidates),
        "margin": float(margin),
        "top_candidates": _candidate_dicts(candidates, top_k),
        "fallback_reason": fallback_reason,
        "action_type": action_type,
        "source_activity_name": str(source_activity_name or ""),
        "target_activity_name": str(target_activity_name or ""),
    }
    if error:
        result["error"] = error
    if matcher_metadata:
        result.update(matcher_metadata)
    return result


def _get_matcher()'''
        if source.count(helper_anchor) != 1:
            raise RuntimeError("omnitransfer_coordinate_helper_context_mismatch")
        source = source.replace(helper_anchor, helper, 1)
    runtime.write_text(source, encoding="utf-8")


def apply_openomnibot_runtime_patches(flow_target: Path) -> None:
    """Apply small Android-host policy patches without mutating canonical OmniFlow."""
    write_builtin_assets(flow_target)

    compiler = flow_target / "functions/compiler.py"
    replace_once(
        compiler,
        '''                {
                    "step_index": len(steps),
                    "before_state_id": before_state_id,
                    "action": action,
                    "result": {"success": True},
                    "after_state_id": after_state_id,
                    "metadata": semantic_metadata,
                }
''',
        '''                {
                    "step_index": len(steps),
                    "before_state_id": before_state_id,
                    "action": action,
                    "result": {"success": True},
                    "after_state_id": after_state_id,
                    "metadata": semantic_metadata,
                    "_source_package": str(observation.get("package_name") or ""),
                }
''',
    )
    replace_once(
        compiler,
        '''    if not steps:
        raise ValueError("successful_source_actions_required")
    facts = {
''',
        '''    steps = _trim_leading_setup_steps(steps)
    for step in steps:
        step.pop("_source_package", None)
    if not steps:
        raise ValueError("successful_source_actions_required")
    facts = {
''',
    )
    replace_once(
        compiler,
        '''def _referenced_source_state_ids(functions: list[Any]) -> list[str]:
''',
        '''def _trim_leading_setup_steps(
    steps: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    """Drop only OpenOmniBot UI setup captured before the first target app launch."""
    first_open_app = next(
        (
            index
            for index, step in enumerate(steps)
            if str((step.get("action") or {}).get("tool") or "") == "open_app"
        ),
        None,
    )
    if first_open_app is None or first_open_app == 0:
        return steps
    prefix = steps[:first_open_app]
    if not all(
        str(step.get("_source_package") or "").startswith("cn.com.omnimind.bot")
        for step in prefix
    ):
        return steps
    return [
        {**step, "step_index": index}
        for index, step in enumerate(steps[first_open_app:])
    ]


def _referenced_source_state_ids(functions: list[Any]) -> list[str]:
''',
    )

    execution = flow_target / "runtime/execution.py"
    replace_once(
        execution,
        '''    steps = tuple(
        step for step in function.steps if step.step_index >= int(start_step_index)
    )
    if max_actions is not None and len(steps) > max_actions:
''',
        '''    steps = tuple(
        step for step in function.steps if step.step_index >= int(start_step_index)
    )
    steps = await _trim_legacy_host_setup_steps(host, steps)
    if max_actions is not None and len(steps) > max_actions:
        ''',
    )
    replace_once(
        execution,
        '''async def align_function_resume(
''',
        '''async def _trim_legacy_host_setup_steps(
    host: Host,
    steps: tuple[Any, ...],
) -> tuple[Any, ...]:
    """Migrate old recordings that captured OpenOmniBot setup before open_app."""
    first_open_app = next(
        (
            index
            for index, step in enumerate(steps)
            if str(step.action.tool or "") == "open_app"
        ),
        None,
    )
    if first_open_app is None or first_open_app == 0:
        return steps
    for step in steps[:first_open_app]:
        source_state = await _load_state(host, step.source_state_id)
        source_package = (
            str(source_state.package_name or "") if source_state is not None else ""
        )
        if not source_package.startswith("cn.com.omnimind.bot"):
            return steps
    return steps[first_open_app:]


async def align_function_resume(
''',
    )
    replace_once(
        execution,
        '''    action_result = ActionResult.from_value(await _await(host.act(action)))
    await asyncio.sleep(_ACTION_SETTLE_SECONDS)
    if not action_result.success:
''',
        '''    action_result = ActionResult.from_value(await _await(host.act(action)))
    diagnostics = action_result.extra.get("diagnostics")
    host_stabilized = (
        isinstance(diagnostics, dict)
        and diagnostics.get("state_stabilization") == "host_completed"
    )
    if not host_stabilized:
        await asyncio.sleep(_ACTION_SETTLE_SECONDS)
    if not action_result.success:
''',
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
    return updates


def checkpoint_relative_path(transfer_package: Path) -> str:
    runtime_source = (transfer_package / "runtime.py").read_text(encoding="utf-8")
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
    checkpoint = transfer_package / relative
    if not checkpoint.is_file():
        raise RuntimeError(f"canonical_omnitransfer_checkpoint_missing:{relative}")
    if checkpoint.suffix == ".npz":
        return relative
    portable_checkpoint = checkpoint.with_suffix(".npz")
    if (
        "NumpyMutualGraphMatcher" not in runtime_source
        or not portable_checkpoint.is_file()
    ):
        raise RuntimeError(
            "canonical_omnitransfer_android_runtime_requires_numpy_checkpoint:"
            f"{relative}"
        )
    return portable_checkpoint.relative_to(transfer_package).as_posix()


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
    if args.reuse_packaged_omnitransfer:
        checkpoint = packaged_values.get("omnitransfer.checkpoint", "").strip()
        transfer_commit = packaged_values.get("omnitransfer.commit", "").strip()
        if not checkpoint or not transfer_commit:
            raise RuntimeError("packaged_omnitransfer_manifest_incomplete")
    else:
        checkpoint = checkpoint_relative_path(transfer_package)
        transfer_commit = git_head(transfer_root)
    with TemporaryDirectory(prefix="oob-runtime-") as temporary:
        staging = Path(temporary)
        with ZipFile(args.archive) as archive:
            archive.extractall(staging)
        flow_target = staging / "python/omniflow"
        shutil.rmtree(flow_target)
        copy_tree(flow_package, flow_target)
        schema_target = staging / "python/schemas/oob"
        shutil.rmtree(schema_target)
        copy_tree(args.catalog.parent / "omni-vlm-lite/schemas/oob", schema_target)
        apply_openomnibot_runtime_patches(flow_target)
        transfer_target = staging / ".runtime/omnitransfer/src/omnitransfer"
        if args.reuse_packaged_omnitransfer:
            checkpoint_target = transfer_target / checkpoint
            if not checkpoint_target.is_file():
                raise RuntimeError(
                    f"packaged_omnitransfer_checkpoint_missing:{checkpoint}"
                )
        else:
            shutil.rmtree(transfer_target)
            transfer_target.mkdir(parents=True)
            for name in TRANSFER_FILES:
                shutil.copy2(transfer_package / name, transfer_target / name)
            checkpoint_target = transfer_target / checkpoint
            checkpoint_target.parent.mkdir(parents=True)
            shutil.copy2(transfer_package / checkpoint, checkpoint_target)
        apply_completion_biased_transfer_thresholds(transfer_target)

        flow_sha = sha256_directory(flow_target)
        transfer_sha = sha256_directory(transfer_target)
        catalog_pointer = json.loads(
            (flow_target / "catalog/default.json").read_text(encoding="utf-8")
        )
        catalog_release = str(catalog_pointer["release_id"])
        catalog_manifest = (
            flow_target / "catalog/releases" / catalog_release / "manifest.json"
        )
        runtime_version = (
            "2026.08.05.local."
            f"{flow_sha[:8]}.{transfer_sha[:8]}.{flow_commit[:8]}"
        )
        updates = {
            "runtime.version": runtime_version,
            "omniflow.commit": flow_commit,
            "omniflow.source.sha256": flow_sha,
            "omniflow.catalog.release": catalog_release,
            "omniflow.catalog.manifest.sha256": sha256_file(catalog_manifest),
            "omnitransfer.commit": transfer_commit,
            "omnitransfer.source.sha256": transfer_sha,
            "omnitransfer.checkpoint": checkpoint,
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
    print(f"PREBUILT_OMNIFLOW_RUNTIME=PASS")
    print(f"runtime_version={runtime_version}")
    print(f"omnitransfer_checkpoint={checkpoint}")
    print(f"archive_sha256={archive_sha}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
