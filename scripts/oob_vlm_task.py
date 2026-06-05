#!/usr/bin/env python3
"""Run one OOB online VLM task from the command line.

This is the Python equivalent of scripts/oob-run-vlm-task.sh, kept small so it
can be used as a stable evaluation entrypoint. It only talks to the debug APK
through adb broadcasts; the Android app owns VLM execution, recall, action
execution, and RunLog collection.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from typing import Any
import uuid


DEFAULT_PACKAGE = "cn.com.omnimind.bot.debug"
DEFAULT_DEVICE = os.environ.get("ANDROID_SERIAL") or "emulator-5556"
VLM_ACTION = "cn.com.omnimind.bot.debug.RUN_VLM_RUNLOG"
CANCEL_ACTION = "cn.com.omnimind.bot.debug.CANCEL_VLM_TASK"
VLM_RECEIVER = "cn.com.omnimind.bot.debug.DebugVlmRunLogReceiver"
CANCEL_RECEIVER = "cn.com.omnimind.bot.debug.DebugVlmCancelReceiver"
RESULT_FILE = "files/debug-vlm-runlog-result.json"
CANCEL_RESULT_FILE = "files/debug-vlm-cancel-result.json"


def repo_root() -> Path:
    return Path(__file__).resolve().parents[1]


def find_adb() -> str:
    candidates = [
        Path.home() / "Library" / "Android" / "sdk" / "platform-tools" / "adb",
        Path.home() / "Android" / "Sdk" / "platform-tools" / "adb",
    ]
    for candidate in candidates:
        if candidate.exists():
            return str(candidate)
    return "adb"


def run_command(
    command: list[str],
    *,
    timeout: float = 60,
    check: bool = True,
) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        command,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=timeout,
        check=False,
    )
    if check and result.returncode != 0:
        raise RuntimeError(
            f"Command failed ({result.returncode}): {' '.join(command)}\n"
            f"stdout:\n{result.stdout}\n"
            f"stderr:\n{result.stderr}"
        )
    return result


class OobVlmTaskClient:
    def __init__(self, adb: str, device: str, package_name: str) -> None:
        self.adb_path = adb
        self.device = device
        self.package_name = package_name
        self.receiver = f"{package_name}/{VLM_RECEIVER}"
        self.cancel_receiver = f"{package_name}/{CANCEL_RECEIVER}"

    def adb(self, *args: str, timeout: float = 60, check: bool = True) -> subprocess.CompletedProcess[str]:
        return run_command(
            [self.adb_path, "-s", self.device, *args],
            timeout=timeout,
            check=check,
        )

    def check_device(self) -> None:
        self.adb("get-state", timeout=10)

    def check_oob_ready(self) -> None:
        run_as = self.adb(
            "shell",
            "run-as",
            self.package_name,
            "pwd",
            timeout=10,
            check=False,
        )
        if run_as.returncode != 0:
            raise RuntimeError(
                f"OOB package is not ready for debug run-as: {self.package_name}\n"
                f"stdout:\n{run_as.stdout}\n"
                f"stderr:\n{run_as.stderr}\n"
                "Install the develop debug APK first, or run with --start-oob."
            )
        pid = self.adb(
            "shell",
            "pidof",
            self.package_name,
            timeout=10,
            check=False,
        ).stdout.strip()
        if not pid:
            raise RuntimeError(
                f"OOB app process is not running on {self.device}: {self.package_name}\n"
                "Run with --start-oob, or start OOB before launching the VLM task."
            )

    def clear_result_files(self) -> None:
        self.adb(
            "shell",
            "run-as",
            self.package_name,
            "rm",
            "-f",
            RESULT_FILE,
            CANCEL_RESULT_FILE,
            timeout=10,
            check=False,
        )

    def start_oob(self, args: argparse.Namespace) -> None:
        script = repo_root() / "scripts" / "oob-start.sh"
        command = [
            "bash",
            str(script),
            "--device",
            self.device,
            "--wait-seconds",
            str(args.start_wait_seconds),
            "--settle-seconds",
            str(args.start_settle_seconds),
        ]
        if args.start_profile:
            command += ["--profile", args.start_profile]
        if args.start_skip_build:
            command.append("--skip-build")
        if args.start_skip_install:
            command.append("--skip-install")
        if args.start_no_clock_check:
            command.append("--no-clock-check")
        print("Starting OOB with: " + " ".join(command), file=sys.stderr)
        run_command(command, timeout=args.start_timeout)

    def read_app_file(self, path: str) -> str:
        result = self.adb(
            "shell",
            "run-as",
            self.package_name,
            "cat",
            path,
            timeout=5,
            check=False,
        )
        if result.returncode != 0:
            return ""
        return result.stdout.strip()

    def broadcast_start(self, args: argparse.Namespace) -> None:
        broadcast_args = [
            "shell",
            "am",
            "broadcast",
            "-a",
            VLM_ACTION,
            "-n",
            self.receiver,
            "--es",
            "requestId",
            args.request_id,
            "--es",
            "goalBase64",
            encode_text(args.goal),
            "--ei",
            "maxSteps",
            str(args.max_steps),
            "--ez",
            "register",
            bool_text(args.register),
            "--ez",
            "parseOnly",
            bool_text(args.parse_only),
        ]
        if args.warm_memory:
            broadcast_args += ["--es", "stepSkillGuidanceBase64", encode_text(args.warm_memory)]
        if args.skill_id:
            broadcast_args += ["--es", "skillId", args.skill_id]
        if args.profile_id:
            broadcast_args += ["--es", "profileId", args.profile_id]
        if args.model_id:
            broadcast_args += ["--es", "modelId", args.model_id]
        if args.disable_omniflow_recall:
            broadcast_args += ["--ez", "disableOmniFlowRecall", "true"]
        if args.start_from_current:
            broadcast_args += ["--ez", "startFromCurrent", "true", "--ez", "skipGoHome", "true"]
        else:
            broadcast_args += ["--es", "packageName", args.package_to_open]
        if args.wait_timeout_ms is not None:
            broadcast_args += ["--el", "waitTimeoutMs", str(args.wait_timeout_ms)]
        self.adb(*broadcast_args, timeout=30)

    def broadcast_cancel(self) -> dict[str, Any] | None:
        self.adb(
            "shell",
            "am",
            "broadcast",
            "-a",
            CANCEL_ACTION,
            "-n",
            self.cancel_receiver,
            "--es",
            "reason",
            "cli_interrupt",
            timeout=10,
            check=False,
        )
        time.sleep(1)
        raw = self.read_app_file(CANCEL_RESULT_FILE)
        if not raw:
            return None
        return json.loads(raw)

    def wait_for_result(self, timeout_seconds: int, poll_seconds: float, request_id: str, goal: str) -> dict[str, Any]:
        deadline = time.monotonic() + timeout_seconds
        last_error: Exception | None = None
        last_mismatched_request_id = ""
        expected_goal = goal.strip()
        while time.monotonic() < deadline:
            raw = self.read_app_file(RESULT_FILE)
            if raw:
                try:
                    parsed = json.loads(raw)
                    result_request_id = str(parsed.get("request_id") or "")
                    if result_request_id == request_id:
                        return parsed
                    # Older installed debug APKs do not echo request_id. Keep
                    # parse-only usable without rebuilding by accepting an exact
                    # goal match, while still rejecting stale results for other
                    # goals.
                    if not result_request_id and str(parsed.get("goal") or "").strip() == expected_goal:
                        return parsed
                    last_mismatched_request_id = result_request_id
                except json.JSONDecodeError as exc:
                    last_error = exc
            time.sleep(poll_seconds)
        if last_error is not None:
            raise TimeoutError(f"Timed out waiting for valid JSON result; last JSON error: {last_error}")
        if last_mismatched_request_id:
            raise TimeoutError(
                "Timed out waiting for matching VLM result "
                f"request_id={request_id}; last_result_request_id={last_mismatched_request_id}"
            )
        raise TimeoutError(f"Timed out waiting for VLM result on {self.device}")


def encode_text(value: str) -> str:
    return base64.b64encode(value.encode("utf-8")).decode("ascii")


def bool_text(value: bool) -> str:
    return "true" if value else "false"


def default_output_path(parse_only: bool) -> Path:
    stamp = time.strftime("%Y%m%d-%H%M%S")
    suffix = "parse_only.json" if parse_only else "run_log.json"
    return repo_root() / "runtime" / "runlogs" / f"{stamp}.vlm.{suffix}"


def summarize_result(data: dict[str, Any], output_path: Path, device: str) -> dict[str, Any]:
    if data.get("parse_only") is True:
        parse_result = data.get("parse_result") or {}
        return {
            "success": data.get("success") is True,
            "target": device,
            "parse_only": True,
            "executed": False,
            "tool_name": parse_result.get("tool_name"),
            "action": parse_result.get("action"),
            "parsed": parse_result.get("parsed"),
            "model": parse_result.get("model"),
            "package_name": parse_result.get("package_name"),
            "screenshot_included": parse_result.get("screenshot_included"),
            "prompt_chars": parse_result.get("prompt_chars"),
            "finish_reason": parse_result.get("finish_reason"),
            "raw_content_preview": parse_result.get("raw_content_preview"),
            "reasoning_preview": parse_result.get("reasoning_preview"),
            "observation_preview": parse_result.get("observation_preview"),
            "thought_preview": parse_result.get("thought_preview"),
            "summary_preview": parse_result.get("summary_preview"),
            "tool_names": parse_result.get("tool_names"),
            "dynamic_function_tool_names": parse_result.get("dynamic_function_tool_names"),
            "current_user_text_preview": parse_result.get("current_user_text_preview"),
            "page_diagnostics": parse_result.get("page_diagnostics"),
            "phase_ms": parse_result.get("phase_ms"),
            "error": parse_result.get("error"),
            "result_path": str(output_path),
        }

    run_log = data.get("run_log") or {}
    return {
        "success": data.get("success") is True,
        "target": device,
        "parse_only": False,
        "run_id": data.get("run_id") or (run_log.get("run_id") if isinstance(run_log, dict) else None),
        "run_log_path": str(output_path) if isinstance(run_log, dict) and run_log else None,
        "runlog_found": data.get("runlog_found"),
        "runlog_success": data.get("runlog_success"),
        "runlog_card_count": data.get("runlog_card_count"),
        "token_usage_total": data.get("token_usage_total"),
        "outcome": data.get("outcome"),
        "convert": data.get("convert"),
        "error_message": data.get("error_message") or (data.get("outcome") or {}).get("errorMessage"),
    }


def write_result(data: dict[str, Any], output_path: Path) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run one OOB online VLM task through the debug APK.")
    parser.add_argument("--device", "--target", default=DEFAULT_DEVICE, help=f"ADB serial. Default: {DEFAULT_DEVICE}")
    parser.add_argument("--adb", default=find_adb(), help="adb executable path.")
    parser.add_argument("--app-package", default=DEFAULT_PACKAGE, help=f"Debug APK package. Default: {DEFAULT_PACKAGE}")
    parser.add_argument("--goal", "--task", "--description", dest="goal", default="", help="vlm_task goal.")
    parser.add_argument("--output-path", default="", help="Where to write the full debug result JSON.")
    parser.add_argument("--max-steps", type=int, default=int(os.environ.get("MAX_STEPS", "6")))
    parser.add_argument("--timeout", type=int, default=int(os.environ.get("TIMEOUT_SECONDS", "180")))
    parser.add_argument("--poll-seconds", type=float, default=1.0)
    parser.add_argument("--start-oob", action="store_true", help="Start OOB with scripts/oob-start.sh before running.")
    parser.add_argument("--start-profile", default="", help="Profile passed to scripts/oob-start.sh.")
    parser.add_argument("--start-skip-build", action="store_true", help="Pass --skip-build to scripts/oob-start.sh.")
    parser.add_argument("--start-skip-install", action="store_true", help="Pass --skip-install to scripts/oob-start.sh.")
    parser.add_argument("--start-wait-seconds", type=int, default=25)
    parser.add_argument("--start-settle-seconds", type=int, default=2)
    parser.add_argument("--start-timeout", type=int, default=300)
    parser.add_argument("--start-no-clock-check", action="store_true", help="Pass --no-clock-check to scripts/oob-start.sh.")
    parser.add_argument("--start-from-current", action="store_true", help="Do not prelaunch an app.")
    parser.add_argument("--package", dest="package_to_open", default=os.environ.get("PACKAGE_TO_OPEN", "com.android.settings"))
    parser.add_argument("--register", action="store_true", help="Convert/register a successful run.")
    parser.add_argument("--parse-only", "--dry-run", action="store_true", help="Call VLM and parse one tool without executing.")
    parser.add_argument("--warm-memory", "--step-skill-guidance", dest="warm_memory", default="")
    parser.add_argument("--skill-id", default="")
    parser.add_argument("--profile-id", default="")
    parser.add_argument("--model-id", "--model", dest="model_id", default="")
    parser.add_argument("--disable-omniflow-recall", action="store_true")
    parser.add_argument("--wait-timeout-ms", type=int, default=None)
    parser.add_argument("--summary-only", action="store_true", help="Only print summary JSON.")
    args = parser.parse_args(argv)
    args.request_id = uuid.uuid4().hex
    if not args.goal.strip():
        try:
            args.goal = input("vlm_task goal> ").strip()
        except EOFError:
            args.goal = ""
    if not args.goal:
        parser.error("vlm_task goal is required")
    if args.max_steps <= 0:
        parser.error("--max-steps must be positive")
    if args.timeout <= 0:
        parser.error("--timeout must be positive")
    if args.start_wait_seconds < 0:
        parser.error("--start-wait-seconds must be non-negative")
    if args.start_settle_seconds < 0:
        parser.error("--start-settle-seconds must be non-negative")
    if args.start_timeout <= 0:
        parser.error("--start-timeout must be positive")
    return args


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    output_path = Path(args.output_path).expanduser() if args.output_path else default_output_path(args.parse_only)
    if not output_path.is_absolute():
        output_path = repo_root() / output_path

    client = OobVlmTaskClient(args.adb, args.device, args.app_package)
    client.check_device()
    if args.start_oob:
        client.start_oob(args)
    client.check_oob_ready()
    client.clear_result_files()

    print(f"Starting VLM task on {args.device}: {args.goal}", file=sys.stderr)
    print("Press Ctrl-C to stop.", file=sys.stderr)
    try:
        client.broadcast_start(args)
        result = client.wait_for_result(args.timeout, args.poll_seconds, args.request_id, args.goal)
    except KeyboardInterrupt:
        print(f"\nStopping VLM task on {args.device}...", file=sys.stderr)
        cancel_result = client.broadcast_cancel()
        if cancel_result:
            print(json.dumps(cancel_result, ensure_ascii=False, indent=2), file=sys.stderr)
        return 130

    write_result(result, output_path)
    summary = summarize_result(result, output_path, args.device)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0 if summary.get("success") is True or summary.get("runlog_found") is True else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
