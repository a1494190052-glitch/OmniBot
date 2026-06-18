#!/usr/bin/env python3
"""Summarize OOB VLM recall-loop evidence into accuracy metrics.

This script is intentionally offline. It never talks to adb and never executes a
Function. Device evidence must come from the native Kotlin smoke scripts first;
this report only aggregates their JSON outputs so CI/PR review can compare the
same metrics across runs.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
from pathlib import Path
import statistics
import sys
from typing import Any


SCHEMA_VERSION = "oob.vlm_accuracy_report.v1"
SMOKE_FILES = {
    "first_vlm": "first-vlm.json",
    "recall": "recall.json",
    "recall_hit": "recall-hit.json",
    "second_vlm": "second-vlm.json",
    "enhance_offline": "enhance-offline.json",
}


def read_json(path: Path | None) -> dict[str, Any]:
    if path is None:
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_markdown(path: Path, report: dict[str, Any]) -> None:
    aggregate = as_map(report.get("aggregate"))
    lines = [
        "# OOB VLM Accuracy Report",
        "",
        f"- schema: `{report.get('schema_version')}`",
        f"- generated_at: `{report.get('generated_at')}`",
        f"- case_count: `{report.get('case_count')}`",
        f"- real_device_smoke_passed: `{as_map(report.get('merge_gate')).get('real_device_smoke_passed')}`",
        "",
        "## Aggregate",
        "",
    ]
    for key in [
        "task_success_rate",
        "action_success_rate",
        "first_run_registration_rate",
        "recall_hit_rate",
        "recall_hit_replay_rate",
        "second_fast_path_rate",
        "offline_enhance_policy_rate",
    ]:
        value = aggregate.get(key)
        lines.append(f"- {key}: `{value}`")
    latency = as_map(aggregate.get("latency_ms"))
    if latency:
        lines.extend(["", "## Latency Ms", ""])
        for phase, values in latency.items():
            lines.append(f"- {phase}: `{values}`")
    lines.extend(["", "## Cases", ""])
    for case in as_list(report.get("cases")):
        checks = as_map(case.get("checks"))
        metrics = as_map(case.get("metrics"))
        failed = [key for key, value in checks.items() if value is False]
        lines.append(
            f"- {case.get('case_name')}: passed=`{case.get('passed')}`, "
            f"function_id=`{metrics.get('function_id')}`, failed_checks=`{failed}`"
        )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def as_map(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def as_list(value: Any) -> list[Any]:
    return value if isinstance(value, list) else []


def first_non_blank(*values: Any) -> str:
    for value in values:
        if value is None:
            continue
        text = str(value).strip()
        if text:
            return text
    return ""


def int_value(value: Any, default: int = 0) -> int:
    try:
        if isinstance(value, bool):
            return int(value)
        if isinstance(value, (int, float)):
            return int(value)
        text = str(value).strip()
        return int(float(text)) if text else default
    except (TypeError, ValueError):
        return default


def float_value(value: Any) -> float | None:
    try:
        if isinstance(value, bool):
            return None
        if isinstance(value, (int, float)):
            return float(value)
        text = str(value).strip()
        return float(text) if text else None
    except (TypeError, ValueError):
        return None


def rate(numerator: int, denominator: int) -> float | None:
    if denominator <= 0:
        return None
    return round(numerator / denominator, 6)


def stable_phase_paths(smoke_dir: Path) -> dict[str, Path]:
    return {phase: smoke_dir / filename for phase, filename in SMOKE_FILES.items()}


def load_case(name: str, paths: dict[str, Path | None]) -> dict[str, Any]:
    payloads = {phase: read_json(path) for phase, path in paths.items() if path and path.exists()}
    first = as_map(payloads.get("first_vlm"))
    recall = as_map(payloads.get("recall"))
    recall_hit = as_map(payloads.get("recall_hit"))
    second = as_map(payloads.get("second_vlm"))
    enhance = as_map(payloads.get("enhance_offline"))

    first_convert = as_map(first.get("convert"))
    first_result = as_map(first_convert.get("result"))
    first_spec = as_map(first_convert.get("function_spec")) or as_map(first_result.get("function_spec"))
    first_metadata = as_map(first_spec.get("metadata"))
    function_id = first_non_blank(
        first_convert.get("function_id"),
        first_convert.get("created_function_id"),
        first_spec.get("function_id"),
    )

    recall_payload = as_map(recall.get("recall"))
    recall_hit_payload = as_map(recall_payload.get("hit"))
    recall_hit_outcome = as_map(recall_hit.get("outcome"))
    recall_hit_summary = as_map(recall_hit_outcome.get("omniflowExecutionSummary"))
    second_outcome = as_map(second.get("outcome"))
    second_summary = as_map(second_outcome.get("omniflowExecutionSummary"))
    enhance_payload = as_map(enhance.get("enhance"))

    recall_hit_route = first_non_blank(
        recall_hit.get("executionRoute"),
        recall_hit_outcome.get("executionRoute"),
        recall_hit_outcome.get("execution_route"),
    )
    second_route = first_non_blank(
        second_outcome.get("executionRoute"),
        second_outcome.get("execution_route"),
        second.get("executionRoute"),
    )

    checks = {
        "first_vlm_json_present": "first_vlm" in payloads,
        "first_vlm_success": first.get("success") is True,
        "first_vlm_runlog_success": first.get("runlog_success") is True,
        "first_vlm_has_cards": int_value(first.get("runlog_card_count")) > 0,
        "first_vlm_registered_function": bool(function_id),
        "first_vlm_enhancement_policy_offline": first_metadata.get("enhancement_policy") == "offline_only",
        "recall_json_present": "recall" in payloads,
        "recall_success": recall_payload.get("success") is True,
        "recall_decision_hit": recall_payload.get("decision") == "hit",
        "recall_function_id_matches": not function_id or recall_hit_payload.get("function_id") == function_id,
        "recall_hit_json_present": "recall_hit" in payloads,
        "recall_hit_replay_success": recall_hit.get("success") is True and recall_hit_summary.get("success") is True,
        "recall_hit_route": recall_hit_route.startswith("omniflow_recall_hit"),
        "second_vlm_json_present": "second_vlm" in payloads,
        "second_vlm_success": second.get("success") is True,
        "second_vlm_fast_path": second_route.startswith("omniflow_recall_hit"),
        "second_vlm_replay_success": second_summary.get("success") is True,
        "enhance_json_present": "enhance_offline" in payloads,
        "enhance_requested": enhance.get("enhance_requested") is True,
        "enhance_policy_offline": enhance.get("enhancement_policy") == "offline_only"
        and enhance_payload.get("policy") == "offline_only",
        "enhance_does_not_feed_replay": enhance.get("replay_uses_enhanced_function") is False
        and "enhanced_function_spec_hash" not in enhance,
    }

    action_counts = collect_action_counts(first) + collect_action_counts(second) + collect_action_counts(recall_hit)
    action_success = sum(item[0] for item in action_counts)
    action_total = sum(item[1] for item in action_counts)
    latency = {
        "first_vlm": collect_latency(first),
        "recall": collect_latency(recall),
        "recall_hit": collect_latency(recall_hit),
        "second_vlm": collect_latency(second),
        "enhance_offline": collect_latency(enhance),
    }
    metrics = {
        "function_id": function_id,
        "first_run_registered": checks["first_vlm_registered_function"],
        "recall_hit": checks["recall_decision_hit"],
        "recall_hit_replay": checks["recall_hit_replay_success"] and checks["recall_hit_route"],
        "second_fast_path": checks["second_vlm_fast_path"] and checks["second_vlm_replay_success"],
        "offline_enhance_policy": checks["enhance_policy_offline"] and checks["enhance_does_not_feed_replay"],
        "task_success_count": sum(1 for ok in [checks["first_vlm_success"], checks["second_vlm_success"]] if ok),
        "task_attempt_count": sum(1 for present in [checks["first_vlm_json_present"], checks["second_vlm_json_present"]] if present),
        "action_success_count": action_success,
        "action_attempt_count": action_total,
    }
    required = [
        "first_vlm_success",
        "first_vlm_runlog_success",
        "first_vlm_registered_function",
        "first_vlm_enhancement_policy_offline",
        "recall_success",
        "recall_decision_hit",
        "recall_function_id_matches",
        "recall_hit_replay_success",
        "recall_hit_route",
        "second_vlm_success",
        "second_vlm_fast_path",
        "second_vlm_replay_success",
        "enhance_policy_offline",
        "enhance_does_not_feed_replay",
    ]
    return {
        "case_name": name,
        "paths": {phase: str(path) for phase, path in paths.items() if path},
        "checks": checks,
        "metrics": metrics,
        "latency_ms": latency,
        "passed": all(checks.get(key) is True for key in required),
    }


def collect_action_counts(payload: dict[str, Any]) -> list[tuple[int, int]]:
    counts: list[tuple[int, int]] = []
    for card in iter_nested_maps(payload):
        tool_name = first_non_blank(
            card.get("tool_name"),
            card.get("toolName"),
            as_map(card.get("tool_call")).get("name"),
            as_map(card.get("toolCall")).get("name"),
            card.get("tool"),
        )
        if not tool_name:
            continue
        if tool_name in {"vlm_task", "omniflow.recall", "update_function"}:
            continue
        if not looks_like_action_tool(tool_name):
            continue
        success = card.get("success")
        if success is None:
            success = as_map(card.get("header")).get("success")
        if success is None:
            success = as_map(card.get("result")).get("success")
        if success is None:
            continue
        counts.append((1 if success is not False else 0, 1))
    return counts


def looks_like_action_tool(tool_name: str) -> bool:
    return tool_name in {
        "click",
        "long_press",
        "input_text",
        "swipe",
        "open_app",
        "press_key",
        "wait",
        "call_tool",
        "android_privileged_action",
        "run_function",
        "oob_function_run",
    }


def iter_nested_maps(value: Any) -> list[dict[str, Any]]:
    found: list[dict[str, Any]] = []
    stack = [value]
    while stack:
        current = stack.pop()
        if isinstance(current, dict):
            found.append(current)
            stack.extend(current.values())
        elif isinstance(current, list):
            stack.extend(current)
    return found


def collect_latency(payload: dict[str, Any]) -> dict[str, float]:
    latency: dict[str, float] = {}
    for source in [
        payload,
        as_map(payload.get("page_diagnostics")),
        as_map(payload.get("timing")),
        as_map(payload.get("recall")).get("timing"),
        as_map(payload.get("replay")).get("timing"),
        as_map(as_map(payload.get("outcome")).get("omniflowExecutionSummary")).get("timing"),
    ]:
        for key, value in as_map(source).items():
            if not key.endswith("_ms") and key not in {"duration_ms", "runner_duration_ms"}:
                continue
            numeric = float_value(value)
            if numeric is not None:
                latency[key] = round(numeric, 3)
    return latency


def aggregate_cases(cases: list[dict[str, Any]]) -> dict[str, Any]:
    task_success = sum(int(as_map(case.get("metrics")).get("task_success_count") or 0) for case in cases)
    task_attempts = sum(int(as_map(case.get("metrics")).get("task_attempt_count") or 0) for case in cases)
    action_success = sum(int(as_map(case.get("metrics")).get("action_success_count") or 0) for case in cases)
    action_attempts = sum(int(as_map(case.get("metrics")).get("action_attempt_count") or 0) for case in cases)
    latency_values: dict[str, dict[str, list[float]]] = {}
    for case in cases:
        for phase, phase_latency in as_map(case.get("latency_ms")).items():
            for key, value in as_map(phase_latency).items():
                latency_values.setdefault(phase, {}).setdefault(key, []).append(float(value))
    return {
        "task_success_rate": rate(task_success, task_attempts),
        "action_success_rate": rate(action_success, action_attempts),
        "first_run_registration_rate": rate(
            sum(1 for case in cases if as_map(case.get("metrics")).get("first_run_registered") is True),
            len(cases),
        ),
        "recall_hit_rate": rate(
            sum(1 for case in cases if as_map(case.get("metrics")).get("recall_hit") is True),
            len(cases),
        ),
        "recall_hit_replay_rate": rate(
            sum(1 for case in cases if as_map(case.get("metrics")).get("recall_hit_replay") is True),
            len(cases),
        ),
        "second_fast_path_rate": rate(
            sum(1 for case in cases if as_map(case.get("metrics")).get("second_fast_path") is True),
            len(cases),
        ),
        "offline_enhance_policy_rate": rate(
            sum(1 for case in cases if as_map(case.get("metrics")).get("offline_enhance_policy") is True),
            len(cases),
        ),
        "latency_ms": summarize_latency(latency_values),
    }


def summarize_latency(values: dict[str, dict[str, list[float]]]) -> dict[str, dict[str, dict[str, float]]]:
    output: dict[str, dict[str, dict[str, float]]] = {}
    for phase, phase_values in sorted(values.items()):
        output[phase] = {}
        for key, samples in sorted(phase_values.items()):
            if not samples:
                continue
            output[phase][key] = {
                "count": len(samples),
                "mean": round(statistics.fmean(samples), 3),
                "min": round(min(samples), 3),
                "max": round(max(samples), 3),
            }
    return output


def build_report(args: argparse.Namespace) -> dict[str, Any]:
    cases: list[dict[str, Any]] = []
    for smoke_dir in args.smoke_dir:
        paths = stable_phase_paths(smoke_dir.expanduser())
        cases.append(load_case(args.case_name or smoke_dir.name, paths))
    if any(getattr(args, name) for name in ["first_vlm", "recall", "recall_hit", "second_vlm", "enhance_offline"]):
        paths = {
            "first_vlm": args.first_vlm,
            "recall": args.recall,
            "recall_hit": args.recall_hit,
            "second_vlm": args.second_vlm,
            "enhance_offline": args.enhance_offline,
        }
        cases.append(load_case(args.case_name or "explicit_case", paths))
    if not cases:
        raise SystemExit("Provide --smoke-dir or explicit phase JSON paths.")
    aggregate = aggregate_cases(cases)
    return {
        "schema_version": SCHEMA_VERSION,
        "generated_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "case_count": len(cases),
        "cases": cases,
        "aggregate": aggregate,
        "merge_gate": {
            "real_device_smoke_passed": all(case.get("passed") is True for case in cases),
            "offline_python_compatibility_required_separately": True,
            "notes": [
                "This report aggregates native OOB evidence only.",
                "OmniFlow Python eval may explain schema/recall/action-transfer compatibility, but it cannot certify phone execution.",
            ],
        },
    }


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--smoke-dir", type=Path, action="append", default=[], help="Directory with first-vlm.json, recall.json, recall-hit.json, second-vlm.json, and enhance-offline.json.")
    parser.add_argument("--case-name", default="", help="Optional display name for one explicit case or smoke dir.")
    parser.add_argument("--first-vlm", type=Path)
    parser.add_argument("--recall", type=Path)
    parser.add_argument("--recall-hit", type=Path)
    parser.add_argument("--second-vlm", type=Path)
    parser.add_argument("--enhance-offline", type=Path)
    parser.add_argument("--output", type=Path, help="Write report JSON to this path.")
    parser.add_argument("--markdown", type=Path, help="Write a compact Markdown summary to this path.")
    parser.add_argument("--strict", action="store_true", help="Exit non-zero when any native smoke gate fails.")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    report = build_report(args)
    if args.output:
        write_json(args.output.expanduser(), report)
    if args.markdown:
        write_markdown(args.markdown.expanduser(), report)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if args.strict and as_map(report.get("merge_gate")).get("real_device_smoke_passed") is not True:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
