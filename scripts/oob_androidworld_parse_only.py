#!/usr/bin/env python3
"""Run OOB parse-only checks on AndroidWorld task goals.

This script borrows AndroidWorld task content only. It does not create an
AndroidWorld env, reset the emulator, execute actions through AndroidWorld, or
run AndroidWorld validators. Each generated goal is sent to OOB's debug
vlm_task entry with parseOnly=true.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import subprocess
import sys
import time
from typing import Any


DEFAULT_ANDROID_WORLD_ROOT = Path.home() / "Projects" / "android_world"
DEFAULT_TASKS = [
    "OpenAppTaskEval",
    "SystemWifiTurnOn",
    "ClockStopWatchRunning",
]


def repo_root() -> Path:
    return Path(__file__).resolve().parents[1]


def load_androidworld(root: Path) -> None:
    if not root.exists():
        raise FileNotFoundError(f"AndroidWorld root not found: {root}")
    sys.path.insert(0, str(root))


def generate_goal(task_type: Any, seed: int) -> tuple[str, dict[str, Any]]:
    params = dict(task_type.generate_random_params())
    params.setdefault("seed", seed)
    task = task_type(params)
    return str(task.goal), dict(getattr(task, "params", params) or params)


def run_parse_only(args: argparse.Namespace, task_name: str, goal: str, output_dir: Path) -> dict[str, Any]:
    slug = "".join(ch.lower() if ch.isalnum() else "-" for ch in task_name).strip("-")
    output_path = output_dir / f"{time.strftime('%Y%m%d-%H%M%S')}-{slug}.json"
    command = [
        sys.executable,
        str(repo_root() / "scripts" / "oob_vlm_task.py"),
        "--device",
        args.device,
        "--goal",
        goal,
        "--parse-only",
        "--start-from-current",
        "--timeout",
        str(args.timeout),
        "--output-path",
        str(output_path),
    ]
    if args.start_oob:
        command.append("--start-oob")
        command += ["--start-wait-seconds", str(args.start_wait_seconds)]
        command += ["--start-settle-seconds", str(args.start_settle_seconds)]
        command += ["--start-timeout", str(args.start_timeout)]
    if args.start_skip_build:
        command.append("--start-skip-build")
    if args.start_skip_install:
        command.append("--start-skip-install")
    if args.start_no_clock_check:
        command.append("--start-no-clock-check")
    if args.model:
        command += ["--model", args.model]
    if args.disable_omniflow_recall:
        command.append("--disable-omniflow-recall")

    started = time.time()
    proc = subprocess.run(
        command,
        cwd=str(repo_root()),
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    summary = {}
    if proc.stdout.strip():
        try:
            summary = json.loads(proc.stdout)
        except json.JSONDecodeError:
            summary = {"raw_stdout": proc.stdout[-4000:]}
    return {
        "task_name": task_name,
        "goal": goal,
        "returncode": proc.returncode,
        "duration_seconds": round(time.time() - started, 3),
        "output_path": str(output_path),
        "summary": summary,
        "stderr_tail": proc.stderr[-4000:],
    }


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="OOB parse-only test on AndroidWorld task goals.")
    parser.add_argument("--android-world-root", default=str(DEFAULT_ANDROID_WORLD_ROOT))
    parser.add_argument("--task", action="append", help="AndroidWorld task name. Repeat for multiple tasks.")
    parser.add_argument("--simple-suite", action="store_true", help="Use a small built-in AndroidWorld-style suite.")
    parser.add_argument("--seed", type=int, default=30)
    parser.add_argument("--device", default="emulator-5556")
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--model", default="")
    parser.add_argument("--disable-omniflow-recall", action="store_true")
    parser.add_argument("--start-oob", action="store_true")
    parser.add_argument("--start-skip-build", action="store_true")
    parser.add_argument("--start-skip-install", action="store_true")
    parser.add_argument("--start-no-clock-check", action="store_true")
    parser.add_argument("--start-wait-seconds", type=int, default=45)
    parser.add_argument("--start-settle-seconds", type=int, default=3)
    parser.add_argument("--start-timeout", type=int, default=300)
    parser.add_argument("--output", default="")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    load_androidworld(Path(args.android_world_root).expanduser().resolve())
    from android_world import registry

    task_names = args.task or (DEFAULT_TASKS if args.simple_suite else [])
    if not task_names:
        raise SystemExit("--task is required unless --simple-suite is set")

    task_registry = registry.TaskRegistry().get_registry(registry.TaskRegistry.ANDROID_WORLD_FAMILY)
    output_path = Path(args.output).expanduser() if args.output else (
        repo_root() / "output" / f"oob-androidworld-parse-only-{time.strftime('%Y%m%d-%H%M%S')}.json"
    )
    if not output_path.is_absolute():
        output_path = repo_root() / output_path
    output_dir = output_path.with_suffix("")
    output_dir.mkdir(parents=True, exist_ok=True)

    results = []
    for index, task_name in enumerate(task_names):
        if task_name not in task_registry:
            raise KeyError(f"Task {task_name} not found in AndroidWorld registry")
        goal, params = generate_goal(task_registry[task_name], args.seed + index)
        print(f"== {task_name}: {goal} ==", file=sys.stderr)
        item = run_parse_only(args, task_name, goal, output_dir)
        item["params"] = params
        results.append(item)
        print(json.dumps({
            "task_name": task_name,
            "success": item.get("summary", {}).get("success"),
            "parsed": item.get("summary", {}).get("parsed"),
            "tool_name": item.get("summary", {}).get("tool_name"),
            "error": item.get("summary", {}).get("error"),
            "phase": item.get("summary", {}).get("phase"),
            "error_message": item.get("summary", {}).get("error_message"),
            "output_path": item.get("output_path"),
        }, ensure_ascii=False))

    payload = {
        "mode": "androidworld_content_parse_only",
        "device": args.device,
        "task_count": len(results),
        "parsed_count": sum(1 for item in results if item.get("summary", {}).get("parsed") is True),
        "success_count": sum(1 for item in results if item.get("summary", {}).get("success") is True),
        "results": results,
    }
    output_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote_result={output_path}", file=sys.stderr)
    return 0 if payload["parsed_count"] == payload["task_count"] else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
