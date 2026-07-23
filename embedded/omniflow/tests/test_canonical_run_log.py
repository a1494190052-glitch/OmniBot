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
                "step_index": 0,
                "before_state_id": "state-0",
                "action": {"tool": "wait", "args": {"duration_ms": 1000}},
                "result": {"success": True},
                "after_state_id": "state-1",
                "metadata": {
                    "step_id": "embedded-run-step-0",
                    "status": "succeeded",
                    "thinking": "The page is stable.",
                    "summary": "Waited for the page.",
                },
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


def test_embedded_runtime_rejects_noncanonical_step_identity_and_status() -> None:
    value = canonical_run_log()
    value["steps"][0]["step_id"] = "embedded-run-0"
    value["steps"][0]["status"] = "succeeded"

    with pytest.raises(ValueError, match="additionalProperties:step_id"):
        canonicalize_run_log(value)


def test_embedded_runtime_requires_one_complete_observed_action() -> None:
    value = canonical_run_log()
    value["steps"][0].pop("after_state_id")

    with pytest.raises(ValueError, match="required:after_state_id"):
        canonicalize_run_log(value)


def test_embedded_runtime_rejects_step_diagnostics_alias() -> None:
    value = canonical_run_log()
    value["steps"][0]["diagnostics"] = value["steps"][0].pop("metadata")

    with pytest.raises(ValueError, match="additionalProperties:diagnostics"):
        canonicalize_run_log(value)


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

    with pytest.raises(ValueError, match="additionalProperties:observation"):
        canonicalize_run_log(value)


def test_embedded_runtime_rejects_old_final_state_name() -> None:
    value = canonical_run_log()
    value["final_observation"] = {"state_id": value.pop("final_state_id")}

    with pytest.raises(ValueError, match="additionalProperties:final_observation"):
        canonicalize_run_log(value)


def test_run_status_success_relationship_comes_from_schema() -> None:
    value = canonical_run_log()
    value["success"] = False

    with pytest.raises(ValueError, match="run_log_schema_invalid:run_log.success:const"):
        canonicalize_run_log(value)


def test_step_index_uses_the_schema_integer_type() -> None:
    value = canonical_run_log()
    value["steps"][0]["step_index"] = 0.5

    with pytest.raises(ValueError, match="run_log.steps\\[0\\].step_index:type:integer"):
        canonicalize_run_log(value)
