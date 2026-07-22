from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
from typing import Any

from omniflow.schemas import canonicalize_action
from omniflow.trajectory import canonicalize_run_log


def compile_runlog_to_store(
    run_log: str | Path | dict[str, Any],
    output_root: str | Path,
    *,
    function_bundle: dict[str, Any] | None = None,
    model: str | None = None,
    client: Any | None = None,
    prompt: str | None = None,
    timeout: float = 120.0,
) -> dict[str, Any]:
    """Register strict v2 Functions from one successful RunLog and save them."""
    from omniflow.artifact import bind_function, parse_function_artifact
    from omniflow.store import FunctionStore

    root = Path(output_root).expanduser().resolve()
    if root.exists() and any(root.iterdir()):
        raise FileExistsError(f"immutable output version already exists: {root}")

    if isinstance(run_log, dict):
        raw = dict(run_log)
    else:
        value = json.loads(Path(run_log).expanduser().resolve().read_text())
        if not isinstance(value, dict):
            raise ValueError("source_runlog_must_be_object")
        raw = value
    payload = canonicalize_run_log(raw)
    if payload["success"] is not True:
        raise ValueError("successful_source_runlog_required")
    goal = str(payload.get("goal") or "").strip()
    if not goal:
        raise ValueError("successful_source_goal_required")

    steps: list[dict[str, Any]] = []
    for step in payload["steps"]:
        if not isinstance(step, dict):
            continue
        diagnostics = step.get("diagnostics") if isinstance(step.get("diagnostics"), dict) else {}
        if diagnostics.get("origin") == "checker":
            continue
        result = step.get("result") if isinstance(step.get("result"), dict) else {}
        if result.get("success") is not True:
            continue
        value = step.get("action")
        if not isinstance(value, dict):
            continue
        try:
            action = canonicalize_action(value, replayable_only=True)
        except ValueError as error:
            if str(error).startswith("canonical_action_tool_not_replayable:"):
                continue
            raise
        description = str(diagnostics.get("action_description") or "").strip()
        steps.append(
            {
                "step_index": len(steps),
                "before_state_id": str(step.get("before_state_id") or ""),
                "action": action,
                "result": {"success": True},
                "after_state_id": str(step.get("after_state_id") or ""),
                "diagnostics": (
                    {"action_description": description} if description else {}
                ),
            }
        )
    if not steps:
        raise ValueError("successful_source_actions_required")
    facts = {
        "schema_version": "omniflow.canonical_run_log.v1",
        "run_id": str(payload.get("run_id") or "successful-source"),
        "goal": goal,
        "status": "succeeded",
        "success": True,
        "steps": steps,
    }
    default_bundle = _default_bundle(facts)
    authoring_prompt = (
        prompt
        or """Convert the successful GUI RunLog facts into reusable OmniFlow Functions.
Return exactly {"reason": string, "bundle": object|null}.

The bundle must use schema_version "omniflow.function-bundle.v2" and contain
run_id, arguments, and one or more ordinary
"omniflow.function.v2" Functions. Every Function contains exactly
schema_version, function_id, name, description, input_schema, bindings, steps,
checker_rules, and agent_visible.

Copy this exact JSON shape. Replace values but never move, rename, or omit keys:
{
  "reason": "why these Functions are reusable",
  "bundle": {
    "schema_version": "omniflow.function-bundle.v2",
    "run_id": "copy the supplied run_id exactly",
    "arguments": {
      "enter_requested_name": {"name": "Alice"}
    },
    "functions": [
      {
        "schema_version": "omniflow.function.v2",
        "function_id": "enter_requested_name",
        "name": "Enter requested name",
        "description": "Enter the name requested by the user.",
        "input_schema": {
          "type": "object",
          "properties": {"name": {"type": "string"}},
          "required": ["name"],
          "additionalProperties": false
        },
        "bindings": [
          {
            "source": "$.arguments.name",
            "target": "$.steps[0].action.args.text"
          }
        ],
        "steps": [
          {
            "step_index": 0,
            "source_state_id": "copy the matching before_state_id",
            "action": {"tool": "input_text", "args": {"text": ""}}
          }
        ],
        "checker_rules": [],
        "agent_visible": true
      }
    ]
  }
}

`arguments` is an object keyed by function_id. `bindings` is always an
array of {"source", "target"} objects. `steps` is always a non-empty array and
each step contains only step_index, source_state_id, and action. Every action
contains only tool and args. Every Function repeats schema_version
"omniflow.function.v2". Never place a JSON path or template in an action value;
bound action values use empty type-correct placeholders.

Create the complete reusable Function when every required value is available in
the fresh user goal. Also create useful reusable semantic contiguous action
subsequences. Do not create a Function merely because one recorded action exists.
A one-action Function is valid only when that action itself represents a named,
reusable semantic capability or recovery behavior.
If the complete task needs fresh UI discovery, a dynamic loop, visual
transcription, or a hidden runtime answer, omit that complete Function but keep
safe reusable subsequences. Never emit kind, parent, Root, Child, recovery, task
name, or routing metadata.

input_schema values are strict JSON Schema objects with additionalProperties=false.
Parameterize only action-ready values inferable from the fresh goal and consumed
by Function actions. Every required parameter must have direct bindings from
$.arguments.NAME or a fixed array index to an existing
$.steps[INDEX].action.args.FIELD. Put exact successful values in
arguments. Use empty type-correct placeholders in bound action fields.

Preserve selected source actions in order and do not invent actions or UI
evidence. Coordinate fields in the supplied facts are already normalized to
0..1000. Copy each supplied canonical action without adding fields. Return
bundle=null only when no safe reusable action-grounded Function exists.
"""
    )
    selected_model = str(model or "").strip() or None
    if function_bundle is not None:
        if selected_model is not None or client is not None or prompt is not None:
            raise ValueError("function_bundle_cannot_use_author_model_options")
        authored = {
            "reason": "Registered offline Codex-authored semantic Functions.",
            "bundle": json.loads(json.dumps(function_bundle, ensure_ascii=False)),
        }
    elif selected_model is None:
        if client is not None or prompt is not None:
            raise ValueError("author_model_required_for_author_options")
        if default_bundle is None:
            raise ValueError("default_bundle_actions_required")
        authored = {
            "reason": "Registered one complete recorded Function.",
            "bundle": default_bundle,
        }
    else:
        if client is None:
            try:
                from openai import OpenAI
            except ImportError as exc:
                raise RuntimeError("Install omniflow[llm] to compile RunLogs") from exc
            options: dict[str, Any] = {
                "api_key": os.getenv("OPENAI_API_KEY") or "not-required"
            }
            if os.getenv("OPENAI_BASE_URL"):
                options["base_url"] = os.environ["OPENAI_BASE_URL"]
            client = OpenAI(**options)
        response = client.chat.completions.create(
            model=selected_model,
            messages=[
                {"role": "system", "content": authoring_prompt},
                {"role": "user", "content": json.dumps(facts, ensure_ascii=False)},
            ],
            response_format={"type": "json_object"},
            max_tokens=16384,
            temperature=0,
            timeout=float(timeout),
        )
        authored = json.loads(str(response.choices[0].message.content or ""))
    if not isinstance(authored, dict) or set(authored) != {"reason", "bundle"}:
        raise ValueError("function_author_response_contract_invalid")
    if not isinstance(authored["reason"], str):
        raise ValueError("function_author_reason_must_be_string")

    bundle = authored["bundle"]
    if bundle is None:
        raise ValueError("semantic_functions_required")
    if not isinstance(bundle, dict):
        raise ValueError("function_author_bundle_must_be_object_or_null")
    if set(bundle) != {
        "schema_version",
        "run_id",
        "arguments",
        "functions",
    }:
        raise ValueError("function_bundle_contract_invalid")
    if bundle.get("schema_version") != "omniflow.function-bundle.v2":
        raise ValueError("unsupported_function_bundle_version")
    if str(bundle.get("run_id") or "") != facts["run_id"]:
        raise ValueError("function_bundle_run_id_mismatch")
    raw_functions = bundle.get("functions")
    arguments_by_function = bundle.get("arguments")
    if not isinstance(raw_functions, list) or not raw_functions:
        raise ValueError("function_bundle_functions_required")
    if not isinstance(arguments_by_function, dict):
        raise ValueError("function_bundle_source_arguments_invalid")
    functions = [parse_function_artifact(value) for value in raw_functions]
    function_ids = [function.id for function in functions]
    if len(function_ids) != len(set(function_ids)):
        raise ValueError("function_bundle_duplicate_function_id")
    if set(arguments_by_function) - set(function_ids):
        raise ValueError("function_bundle_source_arguments_unknown_function")
    for function in functions:
        arguments = arguments_by_function.get(function.id, {})
        if not isinstance(arguments, dict):
            raise ValueError("function_bundle_source_arguments_invalid")
        bind_function(function, arguments)

    root.mkdir(parents=True, exist_ok=True)
    store_path = root / "store.json"
    store = FunctionStore(store_path)
    for function in functions:
        store.put_function(function)
    report = {
        "schema_version": "omniflow.androidworld.function-gate.v2",
        "success": True,
        "live_probe_allowed": True,
        "classification": "ready_for_live_probe",
        "reason": authored["reason"],
        "model": selected_model,
        "prompt_sha256": (
            hashlib.sha256(authoring_prompt.encode()).hexdigest()
            if selected_model is not None
            else None
        ),
        "store_path": str(store_path),
        "function_ids": function_ids,
        "function_count": len(function_ids),
    }
    (root / "offline_enhancement.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    )
    return report


def _default_bundle(facts: dict[str, Any]) -> dict[str, Any] | None:
    source_steps = list(facts.get("steps") or ())
    if not source_steps:
        return None
    steps = [
        {
            "step_index": index,
            "source_state_id": str(step["before_state_id"]),
            "action": json.loads(json.dumps(step["action"], ensure_ascii=False)),
        }
        for index, step in enumerate(source_steps)
    ]
    digest = hashlib.sha256(
        json.dumps(
            {"goal": facts["goal"], "steps": steps},
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode()
    ).hexdigest()[:12]
    function_id = f"complete_run_{digest}"
    return {
        "schema_version": "omniflow.function-bundle.v2",
        "run_id": facts["run_id"],
        "arguments": {function_id: {}},
        "functions": [
            {
                "schema_version": "omniflow.function.v2",
                "function_id": function_id,
                "name": str(facts["goal"])[:120],
                "description": (
                    "Complete this exact user request with the full recorded "
                    f"action sequence: {facts['goal']}"
                ),
                "input_schema": {
                    "type": "object",
                    "properties": {},
                    "required": [],
                    "additionalProperties": False,
                },
                "bindings": [],
                "steps": steps,
                "checker_rules": [],
                "agent_visible": True,
            }
        ],
    }
