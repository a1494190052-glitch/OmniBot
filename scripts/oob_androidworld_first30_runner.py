#!/usr/bin/env python3
"""Run selected Omniflow benchmark methods on the accepted first-30 RunLogs."""

from __future__ import annotations

import argparse
import datetime as dt
import json
from pathlib import Path
import re
import subprocess
import sys
import time
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
OMNIFLOW = Path("/Users/wuzewen/Projects/Omni/Omniflow")
ANDROID_WORLD = Path("/Users/wuzewen/Projects/android_world")
SUMMARIES = [
    ROOT / "runtime/androidworld_goal_reuse_batch/20260607T094312/summary.json",
    ROOT / "runtime/androidworld_goal_reuse_batch/20260607T102212/summary.json",
    ROOT / "runtime/androidworld_goal_reuse_batch/20260607T103322/summary.json",
    ROOT / "runtime/androidworld_goal_reuse_batch/20260607T114040/summary.json",
    ROOT / "runtime/androidworld_goal_reuse_batch/20260607T121506/summary.json",
]
APP_HINTS = (
    ("AudioRecorder", "audio recorder"),
    ("Camera", "camera"),
    ("Clock", "clock"),
    ("Contacts", "contacts"),
    ("Markor", "markor"),
    ("OpenApp", "settings"),
    ("Recipe", "broccoli"),
    ("SimpleCalendar", "calendar"),
    ("SimpleSms", "messenger"),
    ("System", "settings"),
    ("TurnOffWifi", "settings"),
    ("TurnOnWifi", "settings"),
)


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def slug(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]+", "_", value).strip("_") or "case"


def accepted(row: dict[str, Any]) -> bool:
    return (
        row.get("androidworld_success") is True
        and row.get("baseline_warm_memory_success") is True
        and row.get("oob_replay_uses_enhanced_function") is True
        and row.get("oob_replay_success") is True
        and (row.get("final_state") or {}).get("success") is True
        and row.get("collect_seed") != row.get("replay_seed")
        and row.get("canonical_run_log_path")
    )


def load_cases(limit: int) -> list[dict[str, Any]]:
    by_task: dict[str, dict[str, Any]] = {}
    for summary in SUMMARIES:
        for row in read_json(summary).get("results") or []:
            if accepted(row) and row.get("task") not in by_task:
                item = dict(row)
                item["accepted_summary_path"] = str(summary)
                by_task[str(row["task"])] = item
    rows = list(by_task.values())
    return rows[:limit] if limit > 0 else rows


def app_name(task: str) -> str:
    for prefix, name in APP_HINTS:
        if task.startswith(prefix):
            return name
    return "settings"


def run_cmd(command: list[str], cwd: Path, timeout: int) -> dict[str, Any]:
    start = time.monotonic()
    try:
        done = subprocess.run(
            command,
            cwd=str(cwd),
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
            check=False,
        )
        return {
            "returncode": done.returncode,
            "timed_out": False,
            "duration_ms": round((time.monotonic() - start) * 1000, 3),
            "stdout_tail": (done.stdout or "")[-12000:],
            "stderr_tail": (done.stderr or "")[-12000:],
        }
    except subprocess.TimeoutExpired as exc:
        stdout = exc.stdout.decode("utf-8", "ignore") if isinstance(exc.stdout, bytes) else (exc.stdout or "")
        stderr = exc.stderr.decode("utf-8", "ignore") if isinstance(exc.stderr, bytes) else (exc.stderr or "")
        return {
            "returncode": None,
            "timed_out": True,
            "duration_ms": round((time.monotonic() - start) * 1000, 3),
            "stdout_tail": stdout[-12000:],
            "stderr_tail": stderr[-12000:],
        }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--limit", type=int, default=30)
    parser.add_argument("--start-index", type=int, default=1, help="1-based accepted-case index to start from.")
    parser.add_argument("--methods", required=True)
    parser.add_argument("--device", default="emulator-5556")
    parser.add_argument("--console-port", type=int, default=5556)
    parser.add_argument("--seed-base", type=int, default=920000)
    parser.add_argument("--output-dir", type=Path, default=ROOT / "runtime/androidworld_first30_runner")
    parser.add_argument("--case-timeout-sec", type=int, default=1200)
    parser.add_argument("--method-timeout-sec", type=int, default=900)
    parser.add_argument("--autodroid-wrapper-timeout-sec", type=int, default=0)
    parser.add_argument("--mobilegpt-native-wait-sec", type=int, default=0)
    parser.add_argument("--max-steps", type=int, default=30)
    parser.add_argument("--oob-device-url", default="")
    parser.add_argument("--allow-real-device", action="store_true")
    args = parser.parse_args()

    batch = args.output_dir.expanduser().resolve() / dt.datetime.now().strftime("%Y%m%dT%H%M%S")
    cases = load_cases(args.limit)
    if args.start_index > 1:
        cases = cases[args.start_index - 1 :]
    results: list[dict[str, Any]] = []
    for offset, case in enumerate(cases, args.start_index):
        task = str(case["task"])
        index = offset
        case_dir = batch / f"{index:02d}_{slug(task)}"
        out = case_dir / "benchmark"
        cmd = [
            sys.executable,
            "scripts/runlog_reuse_benchmark.py",
            "--run-log-json",
            str(case["canonical_run_log_path"]),
            "--output-dir",
            str(out),
            "--android-world-root",
            str(ANDROID_WORLD),
            "--tasks",
            task,
            "--goal",
            str(case.get("goal") or ""),
            "--app-name",
            app_name(task),
            "--adb-serial",
            args.device,
            "--console-port",
            str(args.console_port),
            "--method",
            args.methods,
            "--n-task-combinations",
            "1",
            "--task-random-seed",
            str(args.seed_base + index),
            "--max-steps",
            str(args.max_steps),
            "--method-timeout-sec",
            str(args.method_timeout_sec),
        ]
        if args.autodroid_wrapper_timeout_sec > 0:
            cmd.extend(["--autodroid-wrapper-timeout-sec", str(args.autodroid_wrapper_timeout_sec)])
        if args.mobilegpt_native_wait_sec > 0:
            cmd.extend(["--mobilegpt-native-wait-sec", str(args.mobilegpt_native_wait_sec)])
        if args.oob_device_url:
            cmd.extend(["--oob-device-url", args.oob_device_url])
        if args.allow_real_device or not args.device.startswith("emulator-"):
            cmd.append("--allow-real-device")
        write_json(case_dir / "request.json", {"index": index, "task": task, "command": cmd, "case": case})
        run = run_cmd(cmd, OMNIFLOW, args.case_timeout_sec)
        summary_path = out / "summary.json"
        result = {
            "index": index,
            "task": task,
            "returncode": run.get("returncode"),
            "timed_out": run.get("timed_out"),
            "duration_ms": run.get("duration_ms"),
            "summary_path": str(summary_path) if summary_path.exists() else "",
            "summary": read_json(summary_path) if summary_path.exists() else None,
            "run": run,
        }
        write_json(case_dir / "result.json", result)
        results.append(result)
        print(json.dumps({k: result[k] for k in ("index", "task", "returncode", "timed_out", "summary_path")}, ensure_ascii=False), flush=True)
    final = {
        "batch_dir": str(batch),
        "case_count": len(results),
        "success_count": sum(1 for item in results if item.get("returncode") == 0),
        "methods": args.methods,
        "device": args.device,
        "results": results,
    }
    write_json(batch / "summary.json", final)
    print(json.dumps({"summary_path": str(batch / "summary.json"), "success_count": final["success_count"], "case_count": len(results)}, ensure_ascii=False, indent=2))
    return 0 if final["success_count"] == len(results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
