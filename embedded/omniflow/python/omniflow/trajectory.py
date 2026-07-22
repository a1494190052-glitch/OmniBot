from __future__ import annotations

import json
from typing import Any

from omniflow.schemas import canonicalize_action

CANONICAL_RUN_LOG_SCHEMA_VERSION = "omniflow.canonical_run_log.v1"

_ROOT_FIELDS = {
    "schema_version",
    "run_id",
    "goal",
    "status",
    "success",
    "error",
    "started_at_ms",
    "finished_at_ms",
    "steps",
    "final_state_id",
    "diagnostics",
}
_STEP_FIELDS = {
    "step_id",
    "step_index",
    "status",
    "thinking",
    "summary",
    "before_state_id",
    "action",
    "result",
    "after_state_id",
    "diagnostics",
}
_STATE_FIELDS = {
    "state_id",
    "package_name",
    "activity_name",
    "display",
}


def canonicalize_run_log(value: dict[str, Any]) -> dict[str, Any]:
    """Validate and copy the single runtime RunLog representation."""
    if not isinstance(value, dict):
        raise ValueError("run_log_must_be_object")
    _reject_unknown(value, _ROOT_FIELDS, "run_log")
    if value.get("schema_version") != CANONICAL_RUN_LOG_SCHEMA_VERSION:
        raise ValueError("unsupported_run_log_version")
    run_id = _required_string(value.get("run_id"), "run_id_required")
    goal = value.get("goal")
    if not isinstance(goal, str):
        raise ValueError("run_log_goal_must_be_string")
    if not isinstance(value.get("success"), bool):
        raise ValueError("run_log_success_must_be_boolean")
    status = value.get("status")
    if status not in {"running", "succeeded", "failed", "cancelled"}:
        raise ValueError("run_log_status_invalid")
    if (status == "succeeded") != value["success"]:
        raise ValueError("run_log_status_success_mismatch")
    raw_steps = value.get("steps")
    if not isinstance(raw_steps, list):
        raise ValueError("run_log_steps_must_be_array")
    steps = [
        canonicalize_run_log_step(raw_step, expected_index=index, run_id=run_id)
        for index, raw_step in enumerate(raw_steps)
    ]
    diagnostics = value.get("diagnostics", {})
    if not isinstance(diagnostics, dict):
        raise ValueError("run_log_diagnostics_must_be_object")
    return {
        "schema_version": CANONICAL_RUN_LOG_SCHEMA_VERSION,
        "run_id": run_id,
        "goal": goal,
        "status": status,
        "success": value["success"],
        **({"error": _string(value["error"], "run_log_error_must_be_string")} if "error" in value else {}),
        **({"started_at_ms": _timestamp(value["started_at_ms"], "run_log_started_at_ms_invalid")} if "started_at_ms" in value else {}),
        **({"finished_at_ms": _timestamp(value["finished_at_ms"], "run_log_finished_at_ms_invalid")} if "finished_at_ms" in value else {}),
        "steps": steps,
        **(
            {"final_state_id": _required_string(value["final_state_id"], "final_state_id_required")}
            if "final_state_id" in value
            else {}
        ),
        **({"diagnostics": _copy(diagnostics)} if "diagnostics" in value else {}),
    }


def canonicalize_run_log_step(
    value: Any,
    *,
    expected_index: int | None = None,
    run_id: str | None = None,
) -> dict[str, Any]:
    """Normalize one observable VLM/agent decision without replay filtering."""
    if not isinstance(value, dict):
        raise ValueError("run_log_step_must_be_object")
    _reject_unknown(value, _STEP_FIELDS, "run_log_step")
    legacy_diagnostics = value.get("diagnostics")
    if isinstance(legacy_diagnostics, dict):
        value = dict(value)
        if "step_id" not in value and isinstance(legacy_diagnostics.get("step_id"), str):
            value["step_id"] = legacy_diagnostics["step_id"]
        if "status" not in value and isinstance(legacy_diagnostics.get("status"), str):
            value["status"] = legacy_diagnostics["status"]
    step_index = value.get("step_index")
    if not isinstance(step_index, int) or isinstance(step_index, bool) or step_index < 0:
        raise ValueError("run_log_step_index_invalid")
    if expected_index is not None and step_index != expected_index:
        raise ValueError("run_log_step_index_invalid")
    step_id = value.get("step_id")
    if step_id is None:
        step_id = f"{run_id or 'step'}-{step_index}"
    step_id = _required_string(step_id, "run_log_step_id_required")
    result = _result(value["result"]) if "result" in value else None
    status = value.get("status")
    if status is None:
        if result is None:
            status = "running"
        else:
            status = "succeeded" if result["success"] else "failed"
    status = {"success": "succeeded", "error": "failed"}.get(status, status)
    if status not in {"running", "succeeded", "failed", "waiting_user"}:
        raise ValueError("run_log_step_status_invalid")
    if status == "succeeded" and result is not None and result["success"] is not True:
        raise ValueError("run_log_step_status_result_mismatch")
    if status == "failed" and result is not None and result["success"] is not False:
        raise ValueError("run_log_step_status_result_mismatch")
    diagnostics = value.get("diagnostics", {})
    if not isinstance(diagnostics, dict):
        raise ValueError("run_log_step_diagnostics_must_be_object")
    return {
        "step_id": step_id,
        "step_index": step_index,
        "status": status,
        **({"thinking": _string(value["thinking"], "run_log_step_thinking_must_be_string")} if "thinking" in value else {}),
        **({"summary": _string(value["summary"], "run_log_step_summary_must_be_string")} if "summary" in value else {}),
        **(
            {"before_state_id": _required_string(value["before_state_id"], "run_log_before_state_id_required")}
            if "before_state_id" in value
            else {}
        ),
        **(
            {"action": canonicalize_action(value["action"], allow_non_action=True)}
            if "action" in value
            else {}
        ),
        **({"result": result} if result is not None else {}),
        **(
            {"after_state_id": _required_string(value["after_state_id"], "run_log_after_state_id_required")}
            if "after_state_id" in value
            else {}
        ),
        **({"diagnostics": _copy(diagnostics)} if "diagnostics" in value else {}),
    }


def _result(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict) or "success" not in value:
        raise ValueError("run_log_step_result_must_be_object")
    _reject_unknown(value, {"success", "error"}, "run_log_step_result")
    if not isinstance(value.get("success"), bool):
        raise ValueError("run_log_step_result_success_must_be_boolean")
    result = {"success": value["success"]}
    if "error" in value:
        result["error"] = _string(value["error"], "run_log_step_result_error_must_be_string")
    return result


def canonicalize_state(value: Any) -> dict[str, Any]:
    """Validate and copy the one state representation used by every core boundary."""
    if not isinstance(value, dict):
        raise ValueError("run_log_state_must_be_object")
    _reject_unknown(value, _STATE_FIELDS, "run_log_state")
    state_id = _required_string(value.get("state_id"), "run_log_state_id_required")
    state = {"state_id": state_id}
    for key, item in value.items():
        if key == "state_id":
            continue
        if key == "display":
            if not isinstance(item, dict) or set(item) != {"width", "height"}:
                raise ValueError("run_log_state_display_invalid")
            for dimension in ("width", "height"):
                number = item.get(dimension)
                if not isinstance(number, int) or isinstance(number, bool) or number <= 0:
                    raise ValueError("run_log_state_display_invalid")
        elif not isinstance(item, str):
            raise ValueError(f"run_log_state_{key}_must_be_string")
        state[key] = item
    return state


def _reject_unknown(value: dict[str, Any], allowed: set[str], prefix: str) -> None:
    unknown = sorted(set(value) - allowed)
    if unknown:
        raise ValueError(f"{prefix}_unknown_fields:{','.join(unknown)}")


def _required_string(value: Any, error: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(error)
    return value


def _string(value: Any, error: str) -> str:
    if not isinstance(value, str):
        raise ValueError(error)
    return value


def _timestamp(value: Any, error: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        raise ValueError(error)
    return value


def _copy(value: Any) -> Any:
    return json.loads(json.dumps(value, ensure_ascii=False))


__all__ = [
    "CANONICAL_RUN_LOG_SCHEMA_VERSION",
    "canonicalize_run_log",
    "canonicalize_run_log_step",
]
