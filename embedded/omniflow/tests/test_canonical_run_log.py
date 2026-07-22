from __future__ import annotations

import pytest

from omniflow.trajectory import canonicalize_run_log


def canonical_run_log() -> dict:
    return {
        "schema_version": "omniflow.canonical_run_log.v1",
        "run_id": "embedded-run",
        "goal": "wait",
        "status": "succeeded",
        "success": True,
        "steps": [
            {
                "step_id": "embedded-run-0",
                "step_index": 0,
                "status": "succeeded",
                "before_state_id": "state-0",
                "action": {"tool": "wait", "args": {"duration_ms": 1000}},
                "result": {"success": True},
                "after_state_id": "state-1",
            }
        ],
        "final_state_id": "state-final",
    }


def test_embedded_runtime_uses_the_canonical_run_log_shape() -> None:
    assert canonicalize_run_log(canonical_run_log()) == canonical_run_log()


def test_embedded_runtime_keeps_only_persisted_action_args() -> None:
    value = canonical_run_log()
    value["steps"][0]["action"] = {
        "tool": "click",
        "args": {
            "target_description": "Compose",
            "node_id": "42",
            "node_resource_id": "compose_button",
            "x": 500,
            "y": 250,
        },
    }

    canonical = canonicalize_run_log(value)

    assert canonical["steps"][0]["action"] == {
        "tool": "click",
        "args": {"x": 500, "y": 250},
    }


def test_embedded_runtime_normalizes_legacy_step_identity_and_status() -> None:
    value = canonical_run_log()
    value["steps"][0].pop("step_id")
    value["steps"][0].pop("status")

    canonical = canonicalize_run_log(value)

    assert canonical["steps"][0]["step_id"] == "embedded-run-0"
    assert canonical["steps"][0]["status"] == "succeeded"


def test_embedded_runtime_keeps_non_replayable_and_diagnostic_steps() -> None:
    value = canonical_run_log()
    value["steps"] = [
        {
            "step_index": 0,
            "status": "succeeded",
            "thinking": "Need a fresh observation",
            "action": {"tool": "get_state", "args": {"reason": "refresh"}},
            "result": {"success": True},
        },
        {
            "step_index": 1,
            "status": "failed",
            "summary": "Model response could not be parsed",
            "result": {"success": False, "error": "parse_failed"},
        },
    ]

    canonical = canonicalize_run_log(value)

    assert canonical["steps"][0]["action"]["tool"] == "get_state"
    assert "action" not in canonical["steps"][1]


def test_embedded_runtime_rejects_unknown_action_args() -> None:
    value = canonical_run_log()
    value["steps"][0]["action"] = {
        "tool": "click",
        "args": {"x": 500, "y": 250, "screenshot_path": "/tmp/source.png"},
    }

    with pytest.raises(ValueError, match="canonical_action_args_unknown:click:screenshot_path"):
        canonicalize_run_log(value)


def test_embedded_runtime_rejects_unknown_action_tool() -> None:
    value = canonical_run_log()
    value["steps"][0]["action"] = {"tool": "teleport", "args": {}}

    with pytest.raises(ValueError, match="canonical_action_tool_unsupported"):
        canonicalize_run_log(value)


def test_embedded_runtime_rejects_old_page_names() -> None:
    value = canonical_run_log()
    value["steps"][0]["observation"] = {"state_id": "state-0"}

    with pytest.raises(ValueError, match="run_log_step_unknown_fields:observation"):
        canonicalize_run_log(value)


def test_embedded_runtime_rejects_old_final_state_name() -> None:
    value = canonical_run_log()
    value["final_observation"] = {"state_id": value.pop("final_state_id")}

    with pytest.raises(ValueError, match="run_log_unknown_fields:final_observation"):
        canonicalize_run_log(value)
