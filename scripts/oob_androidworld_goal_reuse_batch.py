#!/usr/bin/env python3
"""Batch AndroidWorld goal-derived OOB collection, replay, and memory export."""

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
from urllib.parse import urlencode
from urllib.request import urlopen


REPO_ROOT = Path(__file__).resolve().parents[1]
PAPER_EXPORT_ROOT = Path("/Users/wuzewen/Projects/Omni/omniflow-paper-export")
ANDROID_WORLD_ROOT = Path.home() / "Projects" / "android_world"
DEFAULT_PACKAGE = "cn.com.omnimind.bot.debug"


DEFAULT_CASES = [
    {"task": "ClockTimerEntry", "params": {"hours": 0, "minutes": 0, "seconds": 45}},
    {"task": "ClockTimerEntry", "params": {"hours": 0, "minutes": 2, "seconds": 30}},
    {"task": "ClockTimerEntry", "params": {"hours": 0, "minutes": 10, "seconds": 5}},
    {"task": "ClockStopWatchRunning", "params": {}},
]


def _slug(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]+", "_", value).strip("_") or "case"


def _run(command: list[str], *, cwd: Path = REPO_ROOT, timeout: int = 900) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        command,
        cwd=str(cwd),
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=timeout,
        check=False,
    )
    return result


def _json_from_stdout(stdout: str) -> dict[str, Any]:
    text = stdout.strip()
    start = text.rfind("\n{")
    if start >= 0:
        text = text[start + 1 :]
    return json.loads(text)


def _write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _get_state(port: int) -> dict[str, Any]:
    query = urlencode({
        "includeXml": "true",
        "includeScreenshot": "false",
        "filterOverlay": "true",
        "maxXmlChars": "0",
    })
    with urlopen(f"http://127.0.0.1:{port}/get_state?{query}", timeout=10) as response:
        return json.loads(response.read().decode("utf-8"))


def _expected_timer_text(params: dict[str, Any]) -> str:
    return f"{int(params.get('hours') or 0):02d}h {int(params.get('minutes') or 0):02d}m {int(params.get('seconds') or 0):02d}s"


def _expected_timer_desc(params: dict[str, Any]) -> str:
    hours = int(params.get("hours") or 0)
    minutes = int(params.get("minutes") or 0)
    seconds = int(params.get("seconds") or 0)
    h = "hour" if hours == 1 else "hours"
    m = "minute" if minutes == 1 else "minutes"
    s = "second" if seconds == 1 else "seconds"
    return f"{hours} {h}, {minutes} {m}, {seconds} {s}"


def _verify_final_state(task: str, params: dict[str, Any], state: dict[str, Any]) -> dict[str, Any]:
    xml = str(state.get("xml") or "")
    text = json.dumps(state, ensure_ascii=False)
    if task == "ClockTimerEntry":
        expected_text = _expected_timer_text(params)
        expected_desc = _expected_timer_desc(params)
        return {
            "success": expected_text in xml and expected_desc in xml and "Pause" not in text,
            "package_name": state.get("package_name"),
            "has_timer_page": "Timer" in text,
            "expected_timer_text": expected_text,
            "has_expected_timer_text": expected_text in xml,
            "expected_timer_content_desc": expected_desc,
            "has_expected_timer_content_desc": expected_desc in xml,
            "has_pause": "Pause" in text,
            "has_start_button": 'content-desc="Start"' in xml or "Start" in text,
        }
    if task == "ClockStopWatchRunning":
        return {
            "success": 'content-desc="Pause"' in xml and 'content-desc="Lap"' in xml,
            "package_name": state.get("package_name"),
            "has_stopwatch": "Stopwatch" in text,
            "has_pause": 'content-desc="Pause"' in xml or "Pause" in text,
            "has_lap": 'content-desc="Lap"' in xml or "Lap" in text,
            "has_start": 'content-desc="Start"' in xml or "Start" in text,
        }
    return {"success": False, "reason": f"unsupported_final_verifier:{task}"}


def _reset_androidworld_task(task: str, params: dict[str, Any], *, console_port: int, grpc_port: int) -> dict[str, Any]:
    code = f"""
import json, sys
from pathlib import Path
ANDROID_WORLD_ROOT = Path.home() / 'Projects' / 'android_world'
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
    console_port={console_port},
    grpc_port={grpc_port},
    adb_path=str(Path.home() / 'Library' / 'Android' / 'sdk' / 'platform-tools' / 'adb'),
    emulator_setup=False,
    freeze_datetime=True,
)
try:
    env.reset(go_home=True)
    task_registry = registry.TaskRegistry()
    aw_registry = task_registry.get_registry(task_registry.ANDROID_WORLD_FAMILY)
    task_type = aw_registry[{task!r}]
    task_type.set_device_time(env)
    task = task_type({params!r})
    task.initialize_task(env)
    print(json.dumps({{'success': True, 'goal': str(task.goal), 'params': {params!r}}}, ensure_ascii=False))
finally:
    env.close()
"""
    result = _run([sys.executable, "-c", code], timeout=240)
    if result.returncode != 0:
        return {"success": False, "stdout": result.stdout, "stderr": result.stderr, "returncode": result.returncode}
    return _json_from_stdout(result.stdout)


def _prepare_oob(device: str, package_name: str, port: int) -> None:
    _run(["adb", "-s", device, "shell", "settings", "put", "secure", "accessibility_enabled", "1"], timeout=20)
    _run(["adb", "-s", device, "shell", "appops", "set", package_name, "SYSTEM_ALERT_WINDOW", "allow"], timeout=20)
    _run(["adb", "-s", device, "shell", "monkey", "-p", package_name, "-c", "android.intent.category.LAUNCHER", "1"], timeout=30)
    _run(["adb", "-s", device, "forward", f"tcp:{port}", f"tcp:{port}"], timeout=20)


def _memory_card(case: dict[str, Any], result: dict[str, Any]) -> str:
    return f"""# {result['task']} {result['case_id']}

Status: Completed engineering memory, not frozen paper evaluation.
Date: {dt.date.today().isoformat()}

## Goal

{result.get('goal', '')}

## Source Artifacts

- RunLog: `{result.get('run_log_path')}`
- Artifacts: `{result.get('artifact_dir')}`
- Function: `{result.get('function_path')}`
- OmniFlow canonical run_log: `{result.get('canonical_run_log_path')}`
- Final state summary: `{result.get('final_state_path')}`

## Action Sequence

{chr(10).join(f'{i + 1}. `{step.get("tool")}`: {step.get("title")}' for i, step in enumerate(result.get('action_sequence', [])))}

## Verification

- AndroidWorld reward: `{result.get('androidworld_reward')}`
- AndroidWorld success: `{result.get('androidworld_success')}`
- OOB replay success: `{result.get('oob_replay_success')}`
- Replay steps: `{result.get('oob_success_step_count')}/{result.get('oob_step_count')}`
- Final state success: `{(result.get('final_state') or {}).get('success')}`
- Hit rate for this Function replay: `{1 if result.get('oob_replay_success') and (result.get('final_state') or {}).get('success') else 0}/1`

## Notes

The trajectory was derived from the AndroidWorld goal and live OOB observations,
then saved as OOB RunLog and exported to OmniFlow canonical run_log schema for
baseline/reuse harnesses.
"""


def _update_memory_index(memory_root: Path, result: dict[str, Any]) -> None:
    index = memory_root / "INDEX.md"
    if not index.exists():
        return
    line = (
        f"| {dt.date.today().isoformat()} | `{result['task']}` | "
        f"{result.get('goal', '').replace('|', '/')} | {result.get('step_count')} | "
        f"reward `{result.get('androidworld_reward')}` | "
        f"{'success' if result.get('oob_replay_success') else 'failed'} | "
        f"[{result['case_id']}]({Path(result['memory_path']).name}) |\n"
    )
    text = index.read_text(encoding="utf-8")
    if Path(result["memory_path"]).name in text:
        return
    marker = "## Update Rule\n"
    if marker in text:
        text = text.replace(marker, line + "\n" + marker)
    else:
        text += "\n" + line
    index.write_text(text, encoding="utf-8")


def run_case(case: dict[str, Any], args: argparse.Namespace, batch_dir: Path) -> dict[str, Any]:
    task = str(case["task"])
    params = dict(case.get("params") or {})
    case_id = f"{_slug(task)}_{_slug(json.dumps(params, sort_keys=True))}_{int(time.time())}"
    collect_cmd = [
        sys.executable,
        "scripts/oob_collect_androidworld_verified_trajectory.py",
        "--task",
        task,
        "--device",
        args.device,
        "--androidworld-a11y-method",
        "none",
        "--verify-state-source",
        "oob_xml",
        "--image-quality",
        args.image_quality,
        "--output-dir",
        str(REPO_ROOT / "runtime" / "androidworld_verified_trajectories"),
    ]
    if params:
        collect_cmd.extend(["--task-params-json", json.dumps(params, ensure_ascii=False, separators=(",", ":"))])
    collect = _run(collect_cmd, timeout=args.case_timeout_sec)
    collect_result = _json_from_stdout(collect.stdout) if collect.returncode == 0 else {
        "androidworld_success": False,
        "stdout": collect.stdout,
        "stderr": collect.stderr,
    }
    run_log_path = Path(collect_result.get("output_path") or "")
    canonical_path = batch_dir / "canonical_run_logs" / f"{run_log_path.stem}.omniflow.run_log.json"
    if run_log_path.exists():
        export = _run([
            sys.executable,
            "scripts/oob_export_runlog_for_omniflow.py",
            "--input",
            str(run_log_path),
            "--output",
            str(canonical_path),
        ], timeout=60)
    else:
        export = subprocess.CompletedProcess([], 1, "", "missing run_log")

    reset_result = _reset_androidworld_task(task, params, console_port=args.console_port, grpc_port=args.grpc_port)
    _prepare_oob(args.device, args.package, args.port)

    function_path = REPO_ROOT / "runtime" / "functions" / f"{run_log_path.stem}.batch.function.json"
    replay_cmd = [
        "scripts/oob-convert-runlog.sh",
        "--device",
        args.device,
        "--run-log-path",
        str(run_log_path),
        "--run",
        "--name",
        f"AndroidWorld {task} {case_id}",
        "--description",
        str(collect_result.get("goal") or ""),
        "--output-path",
        str(function_path),
        "--timeout",
        str(args.replay_timeout_sec),
    ]
    replay = _run(replay_cmd, timeout=args.replay_timeout_sec + 30)
    replay_result = _json_from_stdout(replay.stdout) if replay.returncode == 0 else {
        "success": False,
        "stdout": replay.stdout,
        "stderr": replay.stderr,
    }
    final_state = _verify_final_state(task, params, _get_state(args.port))
    final_state_path = batch_dir / "final_states" / f"{case_id}.final_state.json"
    _write_json(final_state_path, final_state)

    action_sequence = []
    if run_log_path.exists():
        payload = json.loads(run_log_path.read_text(encoding="utf-8")).get("payload", {})
        for card in payload.get("cards", []):
            action_sequence.append({
                "tool": card.get("tool_name"),
                "title": card.get("title"),
                "success": card.get("success"),
            })
    replay_payload = replay_result.get("replay") if isinstance(replay_result.get("replay"), dict) else {}
    result = {
        "case_id": case_id,
        "task": task,
        "params": params,
        "goal": collect_result.get("goal"),
        "step_count": collect_result.get("step_count"),
        "androidworld_reward": collect_result.get("androidworld_reward"),
        "androidworld_success": collect_result.get("androidworld_success"),
        "action_success": collect_result.get("action_success"),
        "run_log_path": str(run_log_path),
        "artifact_dir": collect_result.get("artifact_dir"),
        "canonical_run_log_path": str(canonical_path) if canonical_path.exists() else None,
        "canonical_export_success": export.returncode == 0,
        "reset_success": reset_result.get("success"),
        "function_path": str(function_path) if function_path.exists() else None,
        "oob_replay_success": replay_payload.get("success") is True,
        "oob_step_count": replay_payload.get("step_count"),
        "oob_success_step_count": replay_payload.get("success_step_count"),
        "final_state": final_state,
        "final_state_path": str(final_state_path),
        "action_sequence": action_sequence,
        "stdout_paths": {},
    }
    memory_root = PAPER_EXPORT_ROOT / "long_term_memory" / "androidworld_trajectories"
    memory_path = memory_root / f"{case_id}.md"
    memory_path.parent.mkdir(parents=True, exist_ok=True)
    memory_path.write_text(_memory_card(case, result), encoding="utf-8")
    result["memory_path"] = str(memory_path)
    _update_memory_index(memory_root, result)
    return result


def parse_cases(raw: str | None) -> list[dict[str, Any]]:
    if not raw:
        return list(DEFAULT_CASES)
    path = Path(raw).expanduser()
    if path.exists():
        data = json.loads(path.read_text(encoding="utf-8"))
    else:
        data = json.loads(raw)
    if not isinstance(data, list):
        raise SystemExit("cases must be a JSON list")
    return [dict(item) for item in data]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases-json", help="JSON list or path. Defaults to built-in clock smoke suite.")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--device", default="emulator-5556")
    parser.add_argument("--package", default=DEFAULT_PACKAGE)
    parser.add_argument("--port", type=int, default=8910)
    parser.add_argument("--console-port", type=int, default=5556)
    parser.add_argument("--grpc-port", type=int, default=8556)
    parser.add_argument("--image-quality", default="low", choices=["low", "medium", "high"])
    parser.add_argument("--case-timeout-sec", type=int, default=360)
    parser.add_argument("--replay-timeout-sec", type=int, default=160)
    parser.add_argument("--output-dir", type=Path, default=REPO_ROOT / "runtime" / "androidworld_goal_reuse_batch")
    args = parser.parse_args()
    cases = parse_cases(args.cases_json)
    if args.limit > 0:
        cases = cases[: args.limit]
    batch_id = dt.datetime.now().strftime("%Y%m%dT%H%M%S")
    batch_dir = args.output_dir.expanduser().resolve() / batch_id
    results = []
    for case in cases:
        result = run_case(case, args, batch_dir)
        results.append(result)
        print(json.dumps({
            "case_id": result["case_id"],
            "task": result["task"],
            "androidworld_success": result["androidworld_success"],
            "oob_replay_success": result["oob_replay_success"],
            "final_state_success": (result.get("final_state") or {}).get("success"),
        }, ensure_ascii=False))
    success_count = sum(
        1 for item in results
        if item.get("androidworld_success") and item.get("oob_replay_success") and (item.get("final_state") or {}).get("success")
    )
    summary = {
        "schema_version": "oob_androidworld_goal_reuse_batch.v1",
        "batch_id": batch_id,
        "case_count": len(results),
        "success_count": success_count,
        "hit_rate": success_count / len(results) if results else 0.0,
        "results": results,
    }
    _write_json(batch_dir / "summary.json", summary)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0 if success_count == len(results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
