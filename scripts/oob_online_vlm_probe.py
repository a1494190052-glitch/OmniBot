#!/usr/bin/env python3
"""Probe one OOB online VLM turn without executing the selected action.

This is a focused wrapper around scripts/oob_vlm_task.py. It starts from the
current device page by default, calls the real VLM API with parseOnly=true, and
prints the fields needed to debug grounding: prompt preview, indexed evidence,
tool list, raw model response, and parsed action.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import subprocess
import sys
import time
from typing import Any


def repo_root() -> Path:
    return Path(__file__).resolve().parents[1]


def run_command(command: list[str], timeout: int) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=timeout,
        check=False,
    )


def parse_json_stdout(stdout: str) -> dict[str, Any]:
    stripped = stdout.strip()
    if not stripped:
        return {}
    return json.loads(stripped)


def section_from_prompt(prompt: str, header: str, next_headers: list[str]) -> str:
    start = prompt.find(header)
    if start < 0:
        return ""
    body = prompt[start:]
    end = len(body)
    for next_header in next_headers:
        pos = body.find(next_header, len(header))
        if pos >= 0:
            end = min(end, pos)
    return body[:end].strip()


def print_probe(summary: dict[str, Any]) -> None:
    prompt = str(summary.get("current_user_text_preview") or "")
    page_evidence = section_from_prompt(
        prompt,
        "OOB indexed page evidence",
        ["【最近结果】", "[Recent Results]", "Unified action schema", "统一 action schema"],
    )
    print("== Result ==")
    print(json.dumps({
        "success": summary.get("success"),
        "parsed": summary.get("parsed"),
        "tool_name": summary.get("tool_name"),
        "action": summary.get("action"),
        "error": summary.get("error"),
        "finish_reason": summary.get("finish_reason"),
        "prompt_chars": summary.get("prompt_chars"),
    }, ensure_ascii=False, indent=2))
    print()
    print("== Tools ==")
    print(json.dumps({
        "tool_names": summary.get("tool_names") or [],
        "dynamic_function_tool_names": summary.get("dynamic_function_tool_names") or [],
    }, ensure_ascii=False, indent=2))
    print()
    print("== Indexed Evidence ==")
    print(page_evidence or "(not found in prompt preview)")
    print()
    print("== Page Diagnostics ==")
    print(json.dumps(summary.get("page_diagnostics") or {}, ensure_ascii=False, indent=2))
    print()
    print("== Model Raw Content ==")
    print(str(summary.get("raw_content_preview") or "").strip() or "(empty)")
    reasoning = str(summary.get("reasoning_preview") or "").strip()
    if reasoning:
        print()
        print("== Reasoning Preview ==")
        print(reasoning)
    print()
    print("== Phase ms ==")
    print(json.dumps(summary.get("phase_ms") or {}, ensure_ascii=False, indent=2))
    print()
    print(f"Full result: {summary.get('result_path')}")


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Probe OOB online VLM grounding with one parse-only turn.")
    parser.add_argument("--goal", "--task", required=True, help="Goal sent to vlm_task.")
    parser.add_argument("--device", default="", help="ADB device serial; defaults to oob_vlm_task.py default.")
    parser.add_argument("--model", default="", help="Optional model id.")
    parser.add_argument("--profile-id", default="", help="Optional provider profile id.")
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--output-path", default="")
    parser.add_argument("--start-oob", action="store_true")
    parser.add_argument("--start-skip-build", action="store_true")
    parser.add_argument("--start-skip-install", action="store_true")
    parser.add_argument("--start-no-clock-check", action="store_true")
    parser.add_argument("--start-wait-seconds", type=int, default=25)
    parser.add_argument("--disable-omniflow-recall", action="store_true")
    parser.add_argument("--package", dest="package_to_open", default="")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    output_path = args.output_path or str(
        repo_root() / "output" / f"oob-online-vlm-probe-{time.strftime('%Y%m%d-%H%M%S')}.json"
    )
    command = [
        sys.executable,
        str(repo_root() / "scripts" / "oob_vlm_task.py"),
        "--goal",
        args.goal,
        "--parse-only",
        "--start-from-current",
        "--summary-only",
        "--timeout",
        str(args.timeout),
        "--output-path",
        output_path,
    ]
    if args.device:
        command += ["--device", args.device]
    if args.model:
        command += ["--model", args.model]
    if args.profile_id:
        command += ["--profile-id", args.profile_id]
    if args.start_oob:
        command.append("--start-oob")
    if args.start_skip_build:
        command.append("--start-skip-build")
    if args.start_skip_install:
        command.append("--start-skip-install")
    if args.start_no_clock_check:
        command.append("--start-no-clock-check")
    if args.start_wait_seconds != 25:
        command += ["--start-wait-seconds", str(args.start_wait_seconds)]
    if args.disable_omniflow_recall:
        command.append("--disable-omniflow-recall")
    if args.package_to_open:
        command = [part for part in command if part != "--start-from-current"]
        command += ["--package", args.package_to_open]

    result = run_command(command, timeout=args.timeout + 30)
    if result.stderr.strip():
        print(result.stderr.strip(), file=sys.stderr)
    if result.returncode != 0 and not result.stdout.strip():
        print(result.stderr, file=sys.stderr)
        return result.returncode
    try:
        summary = parse_json_stdout(result.stdout)
    except json.JSONDecodeError:
        print(result.stdout)
        print(result.stderr, file=sys.stderr)
        return result.returncode or 1
    print_probe(summary)
    return result.returncode


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
