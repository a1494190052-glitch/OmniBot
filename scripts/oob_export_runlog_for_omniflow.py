#!/usr/bin/env python3
"""Export an OOB InternalRunLog JSON into OmniFlow canonical run_log shape."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


def _read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def _write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _payload(data: dict[str, Any]) -> dict[str, Any]:
    run_log = data.get("run_log")
    if isinstance(run_log, dict):
        data = run_log
    nested = data.get("payload")
    return nested if isinstance(nested, dict) else data


def _action_type(tool_name: str) -> str:
    aliases = {
        "press_key": "press_key",
        "open_app": "open_app",
        "input_text": "input_text",
        "click": "click",
        "swipe": "swipe",
        "long_press": "long_press",
    }
    return aliases.get(tool_name, tool_name)


def _compact_action_params(card: dict[str, Any]) -> dict[str, Any]:
    source_context = card.get("source_context") if isinstance(card.get("source_context"), dict) else {}
    action = source_context.get("action") if isinstance(source_context.get("action"), dict) else {}
    params = card.get("params") if isinstance(card.get("params"), dict) else {}
    tool_call = card.get("tool_call") if isinstance(card.get("tool_call"), dict) else {}
    arguments = tool_call.get("arguments") if isinstance(tool_call.get("arguments"), dict) else {}
    merged: dict[str, Any] = {}
    for source in (action, params, arguments):
        for key, value in source.items():
            if key == "tool":
                continue
            merged[key] = value
    return merged


def _source_context_for_card(card: dict[str, Any]) -> dict[str, Any]:
    source_context = card.get("source_context") if isinstance(card.get("source_context"), dict) else {}
    src_ctx = source_context.get("src_ctx") if isinstance(source_context.get("src_ctx"), dict) else {}
    params = _compact_action_params(card)
    context: dict[str, Any] = {}
    page = src_ctx.get("page") or (card.get("before") or {}).get("observation_xml")
    if isinstance(page, str) and page:
        context["page"] = page
    evidence = params.get("target_evidence")
    if isinstance(evidence, dict):
        element = {
            "text": evidence.get("text") or evidence.get("label"),
            "content_desc": evidence.get("content_desc") or evidence.get("content-desc"),
            "resource_id": evidence.get("resource_id") or evidence.get("resource-id"),
            "bounds": evidence.get("bounds"),
            "clickable": evidence.get("clickable"),
        }
        context["element"] = {key: value for key, value in element.items() if value not in (None, "", [])}
    return context


def convert_oob_runlog(oob: dict[str, Any]) -> dict[str, Any]:
    payload = _payload(oob)
    cards = [card for card in payload.get("cards", []) if isinstance(card, dict)]
    steps: list[dict[str, Any]] = []
    for index, card in enumerate(cards):
        tool_name = str(card.get("tool_name") or card.get("toolName") or card.get("action_type") or "").strip()
        if not tool_name:
            continue
        params = _compact_action_params(card)
        source_context = _source_context_for_card(card)
        if source_context and isinstance(params, dict):
            params = dict(params)
            params.setdefault("source_context", source_context)
        action = {
            "type": _action_type(tool_name),
            "params": params,
            "success": bool(card.get("success")),
            "description": str(card.get("title") or card.get("summary") or "").strip(),
        }
        before = card.get("before") if isinstance(card.get("before"), dict) else {}
        after = card.get("after") if isinstance(card.get("after"), dict) else {}
        target_element = params.get("target_evidence") if isinstance(params.get("target_evidence"), dict) else None
        step = {
            "step_index": int(card.get("step_index") if card.get("step_index") is not None else index),
            "status": card.get("status") or ("success" if card.get("success") else "failed"),
            "success": bool(card.get("success")),
            "tool_call": {
                "name": _action_type(tool_name),
                "params": params,
                "reason": str(card.get("title") or card.get("summary") or "").strip(),
            },
            "actions": [action],
            "executed_actions": [action],
            "observation_before_act": {
                "xml": before.get("observation_xml"),
                "package_name": before.get("package_name") or card.get("package_name"),
                "activity_name": before.get("activity_name"),
                "screenshot": before.get("screenshot"),
                "screenshot_path": before.get("screenshot_path"),
            },
            "observation_after_act": {
                "xml": after.get("observation_xml"),
                "package_name": after.get("package_name") or card.get("package_name"),
                "activity_name": after.get("activity_name"),
                "screenshot": after.get("screenshot"),
                "screenshot_path": after.get("screenshot_path"),
            },
        }
        if isinstance(target_element, dict):
            step["target_element"] = target_element
        steps.append(step)
    final_observation = {}
    if steps:
        final_observation = steps[-1].get("observation_after_act") or {}
    return {
        "schema_version": "omniflow.canonical_run_log.v1",
        "run_id": str(payload.get("run_id") or ""),
        "trace_id": str(payload.get("run_id") or ""),
        "source": "oob_androidworld_goal_reuse",
        "goal": str(payload.get("goal") or payload.get("operation_description") or ""),
        "success": bool(payload.get("success") or payload.get("run_success")),
        "done_reason": str(payload.get("done_reason") or ""),
        "started_at": str(payload.get("started_at") or ""),
        "finished_at": str(payload.get("finished_at") or ""),
        "step_count": len(steps),
        "steps": steps,
        "final_observation": final_observation,
        "extra": {
            "source_shape": "oob_internal_runlog_cards",
            "androidworld_task": payload.get("androidworld_task"),
            "androidworld_params": payload.get("androidworld_params"),
            "androidworld_reward": payload.get("androidworld_reward"),
            "androidworld_success": payload.get("androidworld_success"),
            "original_run_log_source": payload.get("source"),
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    converted = convert_oob_runlog(_read_json(Path(args.input).expanduser()))
    _write_json(Path(args.output).expanduser(), converted)
    print(json.dumps({
        "success": True,
        "input": args.input,
        "output": args.output,
        "run_id": converted.get("run_id"),
        "goal": converted.get("goal"),
        "step_count": converted.get("step_count"),
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
