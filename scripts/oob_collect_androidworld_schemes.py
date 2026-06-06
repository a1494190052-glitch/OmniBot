#!/usr/bin/env python3
"""Collect AndroidWorld schemes that OOB can reuse from local result files.

The script mines `output/androidworld-*.json` files produced by prior AndroidWorld
runs and writes a compact catalog of tasks where AndroidWorld's own verifier
reported reward=1.0 for online VLM, Function replay, or recall repeat.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


def repo_root() -> Path:
    return Path(__file__).resolve().parents[1]


def as_dict(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def as_float(value: Any) -> float | None:
    if isinstance(value, (int, float)):
        return float(value)
    if isinstance(value, str):
        try:
            return float(value)
        except ValueError:
            return None
    return None


def first_non_blank(*values: Any) -> str:
    for value in values:
        text = str(value or "").strip()
        if text:
            return text
    return ""


def phase_summary(phase: dict[str, Any]) -> dict[str, Any]:
    raw = as_dict(phase.get("oob_vlm_raw") or phase.get("oob_function_raw"))
    compact = as_dict(phase.get("oob_vlm") or phase.get("oob_function"))
    convert = as_dict(raw.get("convert"))
    token = as_dict(raw.get("token_usage"))
    return {
        "androidworld_reward": phase.get("androidworld_reward"),
        "androidworld_success": phase.get("androidworld_success"),
        "goal": phase.get("goal"),
        "params": phase.get("params"),
        "package_name": phase.get("package_name"),
        "function_id": first_non_blank(
            phase.get("function_id"),
            compact.get("function_id"),
            raw.get("function_id"),
            convert.get("function_id"),
            convert.get("created_function_id"),
        ),
        "run_id": first_non_blank(compact.get("run_id"), raw.get("run_id")),
        "runlog_found": raw.get("runlog_found", compact.get("runlog_found")),
        "runlog_success": raw.get("runlog_success", compact.get("runlog_success")),
        "runlog_card_count": raw.get("runlog_card_count", compact.get("runlog_card_count")),
        "convert_success": convert.get("success", compact.get("convert_success")),
        "token_usage_total": raw.get("token_usage_total", compact.get("token_usage_total") or token.get("total_tokens")),
        "token_usage_call_count": token.get("call_count", compact.get("token_usage_call_count")),
        "reward_attempts": phase.get("androidworld_reward_attempts"),
    }


def collect_from_file(path: Path) -> list[dict[str, Any]]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return []
    rows: list[dict[str, Any]] = []
    for task in data.get("tasks") or []:
        if not isinstance(task, dict):
            continue
        task_name = str(task.get("task_name") or "")
        for phase_name in ("online_vlm", "replay", "recall_repeat"):
            phase = as_dict(task.get(phase_name))
            reward = as_float(phase.get("androidworld_reward"))
            if reward != 1.0:
                continue
            summary = phase_summary(phase)
            rows.append(
                {
                    "task_name": task_name,
                    "phase": phase_name,
                    "source_file": str(path),
                    "device": data.get("device"),
                    "model": data.get("model"),
                    "skill_id": data.get("skill_id"),
                    "duration_seconds": data.get("duration_seconds"),
                    **summary,
                }
            )
    return rows


def group_schemes(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    grouped: dict[str, dict[str, Any]] = {}
    for row in rows:
        task_name = row["task_name"]
        item = grouped.setdefault(
            task_name,
            {
                "task_name": task_name,
                "verified_phase_count": 0,
                "best_function_ids": [],
                "best_run_ids": [],
                "phases": [],
                "usable_strategy": "",
            },
        )
        item["verified_phase_count"] += 1
        phase = row["phase"]
        function_id = row.get("function_id") or ""
        run_id = row.get("run_id") or ""
        if function_id and function_id not in item["best_function_ids"]:
            item["best_function_ids"].append(function_id)
        if run_id and run_id not in item["best_run_ids"]:
            item["best_run_ids"].append(run_id)
        item["phases"].append(row)
    for item in grouped.values():
        phases = {phase["phase"] for phase in item["phases"]}
        if "replay" in phases and "recall_repeat" in phases:
            item["usable_strategy"] = "register_as_function_and_use_replay_or_recall"
        elif "replay" in phases:
            item["usable_strategy"] = "register_as_function_and_use_fixed_replay"
        elif "online_vlm" in phases:
            item["usable_strategy"] = "use_online_vlm_seed_then_convert_to_function"
        else:
            item["usable_strategy"] = "verified_but_needs_manual_review"
        item["phases"].sort(key=lambda row: (row["phase"], row["source_file"]))
    return sorted(grouped.values(), key=lambda item: (-item["verified_phase_count"], item["task_name"]))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-dir", type=Path, default=repo_root() / "output")
    parser.add_argument("--output", type=Path, default=repo_root() / "runtime" / "androidworld_schemes" / "usable_schemes.latest.json")
    parser.add_argument("--pattern", default="androidworld*.json")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    rows: list[dict[str, Any]] = []
    for path in sorted(args.input_dir.expanduser().resolve().glob(args.pattern)):
        rows.extend(collect_from_file(path))
    schemes = group_schemes(rows)
    payload = {
        "schema_version": "oob.androidworld.usable_schemes.v1",
        "input_dir": str(args.input_dir.expanduser().resolve()),
        "verified_phase_count": len(rows),
        "task_count": len(schemes),
        "schemes": schemes,
    }
    args.output.expanduser().resolve().parent.mkdir(parents=True, exist_ok=True)
    args.output.expanduser().resolve().write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
