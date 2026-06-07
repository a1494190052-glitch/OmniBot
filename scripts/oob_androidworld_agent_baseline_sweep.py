#!/usr/bin/env python3
"""Prepare Codex-collected AndroidWorld OOB RunLogs for baseline evaluators.

This script does not run `oob_vlm_task.py` and does not contain task-specific
action adapters. AndroidWorld resets and initializes the task, then Codex (or a
human operator using Codex) plans from the live goal/state and produces a real
OOB RunLog by any valid OOB execution path. The script consumes that RunLog,
converts it to OmniFlow canonical schema, and records the artifact paths.
AndroidWorld is only valid for reset/init and final verification. Baseline
runners must use the OOB local device HTTP host for intermediate observe/act.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import subprocess
import sys
import time
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
OMNIFLOW_ROOT = Path("/Users/wuzewen/Projects/Omni/Omniflow")
PAPER_EXPORT_ROOT = Path("/Users/wuzewen/Projects/Omni/omniflow-paper-export")
ANDROID_WORLD_ROOT = Path.home() / "Projects" / "android_world"
DEFAULT_PACKAGE = "cn.com.omnimind.bot.debug"
DEFAULT_METHODS = (
    "ours-full,"
    "ours-no-checker,"
    "ours-no-control"
)
OOB_HOST_METHODS = {"ours-full", "ours-no-checker", "ours-no-control"}


DEFAULT_CASES = [
    {"task": "ClockTimerEntry", "params": {"hours": 0, "minutes": 0, "seconds": 45}},
    {"task": "ClockTimerEntry", "params": {"hours": 0, "minutes": 2, "seconds": 30}},
    {"task": "ClockStopWatchRunning", "params": {}},
]


def _slug(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]+", "_", value).strip("_") or "case"


def _run(
    command: list[str],
    *,
    cwd: Path = REPO_ROOT,
    timeout: int = 900,
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        cwd=str(cwd),
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=timeout,
        check=False,
    )


def _write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def _json_from_stdout(stdout: str) -> dict[str, Any]:
    text = stdout.strip()
    start = text.rfind("\n{")
    if start >= 0:
        text = text[start + 1 :]
    return json.loads(text)


def _androidworld_reset_init(case: dict[str, Any], args: argparse.Namespace) -> dict[str, Any]:
    task = str(case["task"])
    params = dict(case.get("params") or {})
    code = f"""
import json, sys
from pathlib import Path
ANDROID_WORLD_ROOT = Path({str(ANDROID_WORLD_ROOT)!r}).expanduser()
sys.path.insert(0, str(ANDROID_WORLD_ROOT))
from android_world import registry
from android_world.env import android_world_controller, env_launcher
original_controller = android_world_controller.AndroidWorldController
android_world_controller.AndroidWorldController = lambda env: original_controller(
    env,
    a11y_method=android_world_controller.A11yMethod.NONE,
    install_a11y_forwarding_app=False,
)
env = env_launcher.load_and_setup_env(
    console_port={int(args.console_port)},
    grpc_port={int(args.grpc_port)},
    adb_path={str(args.adb_path)!r},
    emulator_setup=False,
    freeze_datetime=True,
)
try:
    env.reset(go_home=True)
    task_registry = registry.TaskRegistry()
    aw_registry = task_registry.get_registry(task_registry.ANDROID_WORLD_FAMILY)
    task_type = aw_registry[{task!r}]
    task_type.set_device_time(env)
    aw_task = task_type({params!r})
    aw_task.initialize_task(env)
    goal = str(aw_task.goal)
    print(json.dumps({{
        "success": True,
        "task": {task!r},
        "params": {params!r},
        "goal": goal,
        "androidworld_initialized": True,
        "collection_mode": "codex_external",
    }}, ensure_ascii=False))
finally:
    env.close()
"""
    result = _run([sys.executable, "-c", code], timeout=args.case_timeout_sec)
    if result.returncode != 0:
        return {
            "success": False,
            "task": task,
            "params": params,
            "stdout": result.stdout[-8000:],
            "stderr": result.stderr[-8000:],
            "returncode": result.returncode,
            "androidworld_initialized": False,
        }
    return _json_from_stdout(result.stdout)


def _normalize_oob_run_log(input_path: Path, output_path: Path) -> dict[str, Any]:
    data = _read_json(input_path)
    run_log = data.get("run_log") if isinstance(data, dict) else None
    if not isinstance(run_log, dict):
        run_log = data
    if not isinstance(run_log, dict):
        raise RuntimeError(f"RunLog JSON must be an object: {input_path}")
    payload = run_log.get("payload") if isinstance(run_log.get("payload"), dict) else run_log
    cards = payload.get("cards") if isinstance(payload, dict) else None
    if not isinstance(cards, list) or not cards:
        raise RuntimeError(f"RunLog has no cards/actions: {input_path}")
    _write_json(output_path, run_log)
    return run_log


def _convert_to_omniflow(oob_run_log_path: Path, canonical_path: Path) -> dict[str, Any]:
    result = _run(
        [
            sys.executable,
            "scripts/oob_export_runlog_for_omniflow.py",
            "--input",
            str(oob_run_log_path),
            "--output",
            str(canonical_path),
        ],
        timeout=90,
    )
    if result.returncode != 0:
        raise RuntimeError(f"canonical export failed\nstdout:\n{result.stdout}\nstderr:\n{result.stderr}")
    return _json_from_stdout(result.stdout)


def _run_baselines(canonical_path: Path, collect_result: dict[str, Any], args: argparse.Namespace, case_dir: Path) -> dict[str, Any]:
    requested_methods = {
        item.strip()
        for raw in str(args.methods or "").split(",")
        for item in raw.split(",")
        if item.strip()
    }
    unsupported_methods = sorted(requested_methods - OOB_HOST_METHODS)
    if unsupported_methods and not args.allow_androidworld_observe_runner:
        return {
            "returncode": None,
            "summary_path": None,
            "summary": None,
            "skipped": True,
            "reason": (
                "baseline fan-out skipped: requested methods are not wired to the "
                f"OOB local device host yet: {unsupported_methods}. Keep the default "
                "ours-* methods, or pass --allow-androidworld-observe-runner only "
                "for legacy debugging."
            ),
        }
    if not args.oob_device_url and not args.allow_androidworld_observe_runner:
        return {
            "returncode": None,
            "summary_path": None,
            "summary": None,
            "skipped": True,
            "reason": (
                "baseline fan-out skipped: no OOB /get_state runner URL was provided. "
                "Pass --oob-device-url, or pass --allow-androidworld-observe-runner "
                "only for legacy debugging."
            ),
        }
    output_dir = case_dir / "baselines"
    command = [
        sys.executable,
        "scripts/runlog_reuse_benchmark.py",
        "--run-log-json",
        str(canonical_path),
        "--output-dir",
        str(output_dir),
        "--android-world-root",
        str(ANDROID_WORLD_ROOT),
        "--tasks",
        str(collect_result["task"]),
        "--goal",
        str(collect_result.get("goal") or ""),
        "--app-name",
        args.app_name,
        "--adb-serial",
        args.device,
        "--console-port",
        str(args.console_port),
        "--method",
        args.methods,
        "--n-task-combinations",
        "1",
        "--task-random-seed",
        str(args.task_random_seed),
        "--max-steps",
        str(args.baseline_max_steps),
        "--method-timeout-sec",
        str(args.method_timeout_sec),
        "--omniflow-replay-timeout-sec",
        str(args.omniflow_replay_timeout_sec),
    ]
    if args.oob_device_url:
        command.extend(["--oob-device-url", args.oob_device_url])
    if args.prepare_inputs_only:
        command.append("--prepare-inputs-only")
    if args.dry_run_baselines:
        command.append("--dry-run")
    result = _run(command, cwd=OMNIFLOW_ROOT, timeout=args.baseline_timeout_sec)
    summary_path = output_dir / "summary.json"
    summary = _read_json(summary_path) if summary_path.exists() else None
    return {
        "command": command,
        "returncode": result.returncode,
        "stdout_tail": result.stdout[-8000:],
        "stderr_tail": result.stderr[-8000:],
        "summary_path": str(summary_path) if summary_path.exists() else None,
        "summary": summary,
    }


def _memory_card(result: dict[str, Any]) -> str:
    return f"""# {result['case_id']}

Status: Codex-collected AndroidWorld trajectory, engineering memory.
Date: {dt.date.today().isoformat()}

## Goal

{result.get('goal', '')}

## Artifacts

- Source RunLog: `{result.get('source_run_log_path')}`
- OOB RunLog: `{result.get('oob_run_log_path')}`
- OmniFlow canonical run_log: `{result.get('canonical_run_log_path')}`
- Baseline summary: `{result.get('baseline_summary_path')}`

## Verification

- AndroidWorld initialized: `{result.get('androidworld_initialized')}`
- Baseline command returncode: `{result.get('baseline_returncode')}`

## Rule

The RunLog was collected externally by Codex from the AndroidWorld goal and live
device state. Baselines consume the converted OmniFlow canonical run_log and run
through their own AndroidWorld validator-scored paths.
"""


def _write_memory(result: dict[str, Any]) -> Path:
    memory_root = PAPER_EXPORT_ROOT / "long_term_memory" / "androidworld_trajectories"
    memory_root.mkdir(parents=True, exist_ok=True)
    path = memory_root / f"{result['case_id']}.md"
    path.write_text(_memory_card(result), encoding="utf-8")
    index = memory_root / "INDEX.md"
    if index.exists() and path.name not in index.read_text(encoding="utf-8"):
        line = (
            f"| {dt.date.today().isoformat()} | `{result.get('task')}` | "
            f"{str(result.get('goal') or '').replace('|', '/')} | "
            f"{result.get('step_count') or ''} | initialized `{result.get('androidworld_initialized')}` | "
            f"baseline rc `{result.get('baseline_returncode')}` | [{result['case_id']}]({path.name}) |\n"
        )
        text = index.read_text(encoding="utf-8")
        marker = "## Update Rule\n"
        text = text.replace(marker, line + "\n" + marker) if marker in text else text + "\n" + line
        index.write_text(text, encoding="utf-8")
    return path


def _case_run_log_path(case: dict[str, Any], args: argparse.Namespace) -> Path | None:
    raw = case.get("run_log_path") or case.get("oob_run_log_path")
    if raw:
        return Path(str(raw)).expanduser().resolve()
    if args.codex_run_log_path:
        return Path(args.codex_run_log_path).expanduser().resolve()
    return None


def run_case(case: dict[str, Any], args: argparse.Namespace, batch_dir: Path) -> dict[str, Any]:
    case_id = f"codex_{_slug(str(case['task']))}_{_slug(json.dumps(case.get('params') or {}, sort_keys=True))}_{int(time.time())}"
    case_dir = batch_dir / case_id
    case_dir.mkdir(parents=True, exist_ok=True)
    collect = _androidworld_reset_init(case, args)
    request = {
        **collect,
        "collection_instruction": (
            "Codex should now inspect live OOB state, plan actions, execute through OOB, "
            "and provide a real OOB RunLog path via --codex-run-log-path or cases_json[].run_log_path."
        ),
    }
    _write_json(case_dir / "collection_request.json", request)
    if args.init_only:
        result = {
            "case_id": case_id,
            "task": collect.get("task"),
            "params": collect.get("params"),
            "goal": collect.get("goal"),
            "androidworld_initialized": collect.get("androidworld_initialized"),
            "collection_request_path": str(case_dir / "collection_request.json"),
            "status": "initialized_waiting_for_codex_runlog",
        }
        _write_json(case_dir / "case_summary.json", result)
        return result
    source_run_log_path = _case_run_log_path(case, args)
    if source_run_log_path is None:
        raise RuntimeError(
            "AndroidWorld task is initialized, but no Codex-collected RunLog was provided. "
            f"Goal: {collect.get('goal')!r}. "
            f"Collection request: {case_dir / 'collection_request.json'}"
        )
    if not source_run_log_path.exists():
        raise RuntimeError(f"Codex-collected RunLog does not exist: {source_run_log_path}")
    oob_run_log_path = case_dir / "oob.run_log.json"
    run_log = _normalize_oob_run_log(source_run_log_path, oob_run_log_path)
    canonical_path = case_dir / "omniflow.canonical.run_log.json"
    convert = _convert_to_omniflow(oob_run_log_path, canonical_path)
    baseline = _run_baselines(canonical_path, collect, args, case_dir)
    result = {
        "case_id": case_id,
        "task": collect.get("task"),
        "params": collect.get("params"),
        "goal": collect.get("goal"),
        "androidworld_initialized": collect.get("androidworld_initialized"),
        "source_run_log_path": str(source_run_log_path),
        "oob_run_log_path": str(oob_run_log_path),
        "canonical_run_log_path": str(canonical_path),
        "canonical_export": convert,
        "step_count": convert.get("step_count") or (run_log.get("payload") or {}).get("step_count"),
        "baseline_returncode": baseline.get("returncode"),
        "baseline_summary_path": baseline.get("summary_path"),
        "baseline": baseline,
    }
    memory_path = _write_memory(result)
    result["memory_path"] = str(memory_path)
    _write_json(case_dir / "case_summary.json", result)
    return result


def parse_cases(raw: str | None) -> list[dict[str, Any]]:
    if not raw:
        return list(DEFAULT_CASES)
    path = Path(raw).expanduser()
    data = json.loads(path.read_text(encoding="utf-8") if path.exists() else raw)
    if not isinstance(data, list):
        raise SystemExit("--cases-json must be a JSON list or a path containing one")
    return [dict(item) for item in data]


def find_adb() -> str:
    for candidate in (
        Path.home() / "Library" / "Android" / "sdk" / "platform-tools" / "adb",
        Path.home() / "Android" / "Sdk" / "platform-tools" / "adb",
    ):
        if candidate.exists():
            return str(candidate)
    return "adb"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases-json", help="JSON list or path. Defaults to a small Clock suite.")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--device", default="emulator-5556")
    parser.add_argument("--console-port", type=int, default=5556)
    parser.add_argument("--grpc-port", type=int, default=8556)
    parser.add_argument("--adb-path", default=find_adb())
    parser.add_argument("--package", default=DEFAULT_PACKAGE)
    parser.add_argument("--case-timeout-sec", type=int, default=520)
    parser.add_argument("--init-only", action="store_true", help="Only reset/init AndroidWorld and write collection_request.json.")
    parser.add_argument("--codex-run-log-path", default="", help="Single Codex-collected OOB RunLog path for --limit 1 or one case.")
    parser.add_argument("--methods", default=DEFAULT_METHODS)
    parser.add_argument(
        "--oob-device-url",
        default="http://127.0.0.1:8910",
        help="OOB local device HTTP host used by baseline replay observe/act.",
    )
    parser.add_argument("--app-name", default="Clock")
    parser.add_argument("--task-random-seed", type=int, default=42)
    parser.add_argument("--baseline-max-steps", type=int, default=8)
    parser.add_argument("--method-timeout-sec", type=int, default=1200)
    parser.add_argument("--omniflow-replay-timeout-sec", type=int, default=720)
    parser.add_argument("--baseline-timeout-sec", type=int, default=3600)
    parser.add_argument("--prepare-inputs-only", action="store_true")
    parser.add_argument("--dry-run-baselines", action="store_true")
    parser.add_argument(
        "--allow-androidworld-observe-runner",
        action="store_true",
        help="Legacy/debug only: allow runner paths that use AndroidWorld observe/uiautomator during replay.",
    )
    parser.add_argument("--output-dir", type=Path, default=REPO_ROOT / "runtime" / "androidworld_codex_baseline_sweep")
    args = parser.parse_args()

    cases = parse_cases(args.cases_json)
    if args.limit > 0:
        cases = cases[: args.limit]
    batch_dir = args.output_dir.expanduser().resolve() / dt.datetime.now().strftime("%Y%m%dT%H%M%S")
    results = []
    for case in cases:
        result = run_case(case, args, batch_dir)
        results.append(result)
        print(json.dumps({
            "case_id": result["case_id"],
            "task": result.get("task"),
            "androidworld_initialized": result.get("androidworld_initialized"),
            "baseline_returncode": result.get("baseline_returncode"),
            "collection_request_path": result.get("collection_request_path"),
            "canonical_run_log_path": result.get("canonical_run_log_path"),
        }, ensure_ascii=False))
    success_count = sum(
        1 for item in results
        if item.get("androidworld_initialized") is True
        and (args.init_only or item.get("baseline_returncode") == 0)
    )
    summary = {
        "schema_version": "oob.androidworld_codex_baseline_sweep.v1",
        "batch_dir": str(batch_dir),
        "case_count": len(results),
        "success_count": success_count,
        "results": results,
    }
    _write_json(batch_dir / "summary.json", summary)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0 if success_count == len(results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
