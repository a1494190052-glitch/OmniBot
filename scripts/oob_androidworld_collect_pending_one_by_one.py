#!/usr/bin/env python3
"""Run pending AndroidWorld OOB cases one at a time and package artifacts.

This is a thin resumable wrapper around oob_androidworld_goal_reuse_batch.py.
It does not change collection/replay behavior; it only skips already accepted
tasks, runs one case per batch, and copies each case's artifacts into a stable
package directory.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CASES_PATH = REPO_ROOT / "runtime" / "androidworld_all_tasks_cases_1729.json"
DEFAULT_BATCH_ROOT = REPO_ROOT / "runtime" / "androidworld_goal_reuse_batch"
DEFAULT_PACKAGE_ROOT = REPO_ROOT / "runtime" / "androidworld_case_packages"


def _read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def _write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _is_accepted(row: dict[str, Any]) -> bool:
    return (
        row.get("androidworld_success") is True
        and row.get("oob_replay_uses_enhanced_function") is True
        and row.get("oob_replay_success") is True
        and (row.get("final_state") or {}).get("success") is True
        and row.get("collect_seed") != row.get("replay_seed")
        and bool(row.get("canonical_run_log_path"))
    )


def load_accepted_tasks(batch_root: Path) -> dict[str, dict[str, Any]]:
    accepted: dict[str, dict[str, Any]] = {}
    for summary_path in sorted(batch_root.glob("*/summary.json")):
        try:
            rows = _read_json(summary_path).get("results", [])
        except Exception:
            continue
        for row in rows:
            task = str(row.get("task") or "")
            if task and _is_accepted(row):
                accepted[task] = row
    return accepted


def latest_batch_dir(batch_root: Path, before: set[Path]) -> Path | None:
    candidates = [p for p in batch_root.iterdir() if p.is_dir() and p not in before]
    if not candidates:
        return None
    return max(candidates, key=lambda p: p.stat().st_mtime)


def copy_path(src_value: Any, dst_dir: Path, label: str) -> str | None:
    if not src_value:
        return None
    src = Path(str(src_value)).expanduser()
    if not src.exists():
        return None
    dst = dst_dir / label
    if src.is_dir():
        if dst.exists():
            shutil.rmtree(dst)
        shutil.copytree(src, dst)
    else:
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)
    return str(dst)


def package_result(result: dict[str, Any], batch_dir: Path, package_root: Path) -> Path:
    task = str(result.get("task") or "unknown_task")
    case_id = str(result.get("case_id") or f"{task}_{dt.datetime.now().strftime('%Y%m%dT%H%M%S')}")
    package_dir = package_root / task / case_id
    package_dir.mkdir(parents=True, exist_ok=True)

    copied = {
        "run_log": copy_path(result.get("run_log_path"), package_dir, "run_log.json"),
        "artifact_dir": copy_path(result.get("artifact_dir"), package_dir, "artifacts"),
        "canonical_run_log": copy_path(result.get("canonical_run_log_path"), package_dir, "canonical.run_log.json"),
        "function": copy_path(result.get("function_path"), package_dir, "function.json"),
        "enhanced_replay": copy_path(result.get("oob_replay_result_path"), package_dir, "enhanced_replay.json"),
        "final_state": copy_path(result.get("final_state_path"), package_dir, "final_state.json"),
        "memory_card": copy_path(result.get("memory_path"), package_dir, "memory.md"),
    }
    baseline_dir = batch_dir / "baseline_warm_memory" / case_id
    copied["baseline_warm_memory"] = copy_path(baseline_dir, package_dir, "baseline_warm_memory")
    copied["batch_summary"] = copy_path(batch_dir / "summary.json", package_dir, "batch_summary.json")

    manifest = {
        "schema_version": "oob_androidworld_case_package.v1",
        "created_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "batch_dir": str(batch_dir),
        "package_dir": str(package_dir),
        "accepted": _is_accepted(result),
        "result": result,
        "copied_artifacts": copied,
    }
    _write_json(package_dir / "manifest.json", manifest)
    return package_dir


def run_one(case: dict[str, Any], args: argparse.Namespace) -> tuple[int, Path | None, dict[str, Any] | None]:
    before = {p for p in args.batch_root.iterdir() if p.is_dir()} if args.batch_root.exists() else set()
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".json", delete=False) as f:
        json.dump([case], f, ensure_ascii=False)
        cases_path = Path(f.name)
    command = [
        sys.executable,
        str(REPO_ROOT / "scripts" / "oob_androidworld_goal_reuse_batch.py"),
        "--cases-json",
        str(cases_path),
        "--device",
        args.device,
        "--console-port",
        str(args.console_port),
        "--grpc-port",
        str(args.grpc_port),
        "--port",
        str(args.port),
        "--image-quality",
        args.image_quality,
        "--case-timeout-sec",
        str(args.case_timeout_sec),
        "--replay-timeout-sec",
        str(args.replay_timeout_sec),
        "--seed-base",
        str(args.seed_base),
        "--replay-seed-offset",
        str(args.replay_seed_offset),
        "--output-dir",
        str(args.batch_root),
    ]
    if args.build_baseline_warm_memory:
        command.append("--build-baseline-warm-memory")
        command.extend(["--warm-memory-timeout-sec", str(args.warm_memory_timeout_sec)])
    try:
        completed = subprocess.run(
            command,
            cwd=str(REPO_ROOT),
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=args.single_timeout_sec,
            check=False,
        )
    finally:
        cases_path.unlink(missing_ok=True)

    batch_dir = latest_batch_dir(args.batch_root, before)
    result = None
    if batch_dir and (batch_dir / "summary.json").exists():
        try:
            rows = _read_json(batch_dir / "summary.json").get("results", [])
            result = rows[0] if rows else None
        except Exception:
            result = None
    if result is None:
        result = {
            "task": case.get("task"),
            "params": case.get("params") or {},
            "completed": False,
            "wrapper_error": {
                "returncode": completed.returncode,
                "stdout_tail": completed.stdout[-4000:],
                "stderr_tail": completed.stderr[-4000:],
            },
        }
    return completed.returncode, batch_dir, result


def main() -> int:
    parser = argparse.ArgumentParser(description="Run pending AndroidWorld OOB cases one by one.")
    parser.add_argument("--cases-json", type=Path, default=DEFAULT_CASES_PATH)
    parser.add_argument("--batch-root", type=Path, default=DEFAULT_BATCH_ROOT)
    parser.add_argument("--package-root", type=Path, default=DEFAULT_PACKAGE_ROOT)
    parser.add_argument("--device", default="emulator-5556")
    parser.add_argument("--console-port", type=int, default=5556)
    parser.add_argument("--grpc-port", type=int, default=8556)
    parser.add_argument("--port", type=int, default=8910)
    parser.add_argument("--image-quality", choices=["low", "medium", "high"], default="low")
    parser.add_argument("--case-timeout-sec", type=int, default=360)
    parser.add_argument("--replay-timeout-sec", type=int, default=160)
    parser.add_argument("--warm-memory-timeout-sec", type=int, default=300)
    parser.add_argument("--single-timeout-sec", type=int, default=1200)
    parser.add_argument("--seed-base", type=int, default=1729)
    parser.add_argument("--replay-seed-offset", type=int, default=1000003)
    parser.add_argument("--limit", type=int, default=1)
    parser.add_argument("--build-baseline-warm-memory", action="store_true")
    args = parser.parse_args()

    cases = _read_json(args.cases_json.expanduser())
    accepted = load_accepted_tasks(args.batch_root.expanduser())
    pending = [case for case in cases if str(case.get("task") or "") not in accepted]
    if args.limit > 0:
        pending = pending[: args.limit]
    run_manifest: list[dict[str, Any]] = []
    for case in pending:
        print(json.dumps({"event": "start_case", "task": case.get("task"), "params": case.get("params")}, ensure_ascii=False), flush=True)
        returncode, batch_dir, result = run_one(case, args)
        package_dir = package_result(result, batch_dir or args.package_root, args.package_root.expanduser())
        item = {
            "task": case.get("task"),
            "returncode": returncode,
            "batch_dir": str(batch_dir) if batch_dir else None,
            "package_dir": str(package_dir),
            "accepted": _is_accepted(result),
            "completed": result.get("completed"),
            "androidworld_success": result.get("androidworld_success"),
            "oob_replay_success": result.get("oob_replay_success"),
            "final_state_success": (result.get("final_state") or {}).get("success"),
            "collect_error": result.get("collect_error") or result.get("wrapper_error"),
        }
        run_manifest.append(item)
        print(json.dumps({"event": "finish_case", **item}, ensure_ascii=False), flush=True)
    stamp = dt.datetime.now().strftime("%Y%m%dT%H%M%S")
    _write_json(args.package_root.expanduser() / f"one_by_one_run_{stamp}.json", run_manifest)
    return 0 if all(item.get("accepted") for item in run_manifest) else 1


if __name__ == "__main__":
    raise SystemExit(main())
