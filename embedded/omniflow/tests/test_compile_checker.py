from __future__ import annotations

import json
from types import SimpleNamespace

import pytest

from omniflow.compile import compile_runlog_to_store
from omniflow.store import FunctionStore


@pytest.mark.parametrize("status", ["failed", "cancelled"])
def test_embedded_compile_keeps_successful_actions_from_incomplete_run(
    tmp_path,
    status: str,
) -> None:
    run_log = {
        "schema_version": "omniflow.canonical_run_log.v1",
        "run_id": f"manual-{status}-run",
        "goal": "Search for lemonade",
        "status": status,
        "success": False,
        "error": "任务已取消",
        "steps": [
            {
                "step_index": 0,
                "before_state_id": "state-0",
                "action": {"tool": "click", "args": {"x": 500, "y": 132}},
                "result": {"success": True},
                "after_state_id": "state-1",
                "metadata": {"status": "succeeded"},
            },
            {
                "step_index": 1,
                "before_state_id": "state-1",
                "action": {"tool": "click", "args": {"x": 780, "y": 760}},
                "result": {"success": False, "error": "任务已取消"},
                "after_state_id": "state-2",
                "metadata": {"status": "failed"},
            },
            {
                "step_index": 2,
                "before_state_id": "state-2",
                "action": {"tool": "abort", "args": {"value": "任务已取消"}},
                "result": {"success": False, "error": "任务已取消"},
                "after_state_id": "state-2",
                "metadata": {"status": "failed"},
            },
        ],
    }

    report = compile_runlog_to_store(run_log, tmp_path / f"compiled-{status}")
    function = FunctionStore(report["store_path"]).list_functions()[0]

    assert [action.tool for action in function.actions] == ["click"]


def test_embedded_compile_rejects_run_without_successful_actions(tmp_path) -> None:
    run_log = {
        "schema_version": "omniflow.canonical_run_log.v1",
        "run_id": "failed-without-actions",
        "goal": "Search for lemonade",
        "status": "failed",
        "success": False,
        "error": "执行失败",
        "steps": [
            {
                "step_index": 0,
                "before_state_id": "state-0",
                "action": {"tool": "click", "args": {"x": 500, "y": 132}},
                "result": {"success": False, "error": "执行失败"},
                "after_state_id": "state-1",
                "metadata": {"status": "failed"},
            }
        ],
    }

    with pytest.raises(ValueError, match="successful_source_actions_required"):
        compile_runlog_to_store(run_log, tmp_path / "compiled-no-actions")


def test_embedded_compile_keeps_only_replayable_runlog_steps(tmp_path) -> None:
    run_log = {
        "schema_version": "omniflow.canonical_run_log.v1",
        "run_id": "run-with-finished",
        "goal": "Wait and finish",
        "status": "succeeded",
        "success": True,
        "steps": [
            {
                "step_index": 0,
                "before_state_id": "state-0",
                "action": {"tool": "wait", "args": {"duration_ms": 1}},
                "result": {"success": True},
                "after_state_id": "state-1",
                "metadata": {},
            },
            {
                "step_index": 1,
                "before_state_id": "state-1",
                "action": {"tool": "finished", "args": {"content": "Done"}},
                "result": {"success": True},
                "after_state_id": "state-2",
                "metadata": {},
            },
        ],
    }

    report = compile_runlog_to_store(run_log, tmp_path / "compiled")
    function = FunctionStore(report["store_path"]).list_functions()[0]

    assert [action.tool for action in function.actions] == ["wait"]


def test_embedded_offline_compile_writes_explicit_recovery_checker(tmp_path) -> None:
    request: dict = {}
    rule = {
        "schema_version": "omniflow.checker_rule.v1",
        "trigger": 'text_contains("跳过广告")',
        "source_state_id": "state-ad",
        "action": {"tool": "click", "args": {"x": 900, "y": 100}},
    }
    bundle = {
        "schema_version": "omniflow.function-bundle.v2",
        "run_id": "run-ad",
        "arguments": {"continue_after_ad": {}},
        "functions": [
            {
                "schema_version": "omniflow.function.v2",
                "function_id": "continue_after_ad",
                "name": "Continue after ad",
                "description": "Close a known ad interruption, then continue.",
                "input_schema": {
                    "type": "object",
                    "properties": {},
                    "required": [],
                    "additionalProperties": False,
                },
                "bindings": [],
                "steps": [
                    {
                        "step_index": 0,
                        "source_state_id": "state-main",
                        "action": {"tool": "wait", "args": {"duration_ms": 1000}},
                    }
                ],
                "checker_rules": [rule],
                "agent_visible": True,
            }
        ],
    }

    class Completions:
        def create(self, **kwargs):
            request.update(kwargs)
            return SimpleNamespace(
                choices=[
                    SimpleNamespace(
                        message=SimpleNamespace(
                            content=json.dumps({"reason": "Explicit recovery.", "bundle": bundle})
                        )
                    )
                ]
            )

    run_log = {
        "schema_version": "omniflow.canonical_run_log.v1",
        "run_id": "run-ad",
        "goal": "Continue after ad",
        "status": "succeeded",
        "success": True,
        "steps": [
            {
                "step_index": 0,
                "before_state_id": "state-ad",
                "action": {"tool": "click", "args": {"x": 900, "y": 100}},
                "result": {"success": True},
                "after_state_id": "state-main",
                "metadata": {"origin": "checker", "summary": "关闭跳过广告浮层"},
            },
            {
                "step_index": 1,
                "before_state_id": "state-main",
                "action": {"tool": "wait", "args": {"duration_ms": 1000}},
                "result": {"success": True},
                "after_state_id": "state-final",
                "metadata": {},
            },
        ],
    }

    report = compile_runlog_to_store(
        run_log,
        tmp_path / "compiled",
        model="function-author-model",
        client=SimpleNamespace(chat=SimpleNamespace(completions=Completions())),
    )

    model_input = json.loads(request["messages"][1]["content"])
    assert model_input["recovery_examples"][0]["source_state_id"] == "state-ad"
    function = FunctionStore(report["store_path"]).list_functions()[0]
    assert list(function.checker_rules) == [rule]


def test_embedded_default_compile_writes_captured_checker(tmp_path) -> None:
    rule = {
        "schema_version": "omniflow.checker_rule.v1",
        "trigger": 'xml_contains("跳过广告")',
        "source_state_id": "state-ad",
        "action": {"tool": "click", "args": {"x": 900, "y": 100}},
    }
    run_log = {
        "schema_version": "omniflow.canonical_run_log.v1",
        "run_id": "run-captured-ad",
        "goal": "Continue after ad",
        "status": "succeeded",
        "success": True,
        "steps": [
            {
                "step_index": 0,
                "before_state_id": "state-ad",
                "action": rule["action"],
                "result": {"success": True},
                "after_state_id": "state-main",
                "metadata": {
                    "origin": "checker",
                    "checker_trigger": rule["trigger"],
                },
            },
            {
                "step_index": 1,
                "before_state_id": "state-main",
                "action": {"tool": "wait", "args": {"duration_ms": 1000}},
                "result": {"success": True},
                "after_state_id": "state-final",
                "metadata": {},
            },
        ],
    }

    report = compile_runlog_to_store(run_log, tmp_path / "compiled")
    function = FunctionStore(report["store_path"]).list_functions()[0]

    assert [action.tool for action in function.actions] == ["wait"]
    assert list(function.checker_rules) == [rule]


def test_embedded_offline_compile_rejects_invented_checker_evidence(tmp_path) -> None:
    function = {
        "schema_version": "omniflow.function.v2",
        "function_id": "invented_recovery",
        "name": "Invented recovery",
        "description": "Must not be stored.",
        "input_schema": {
            "type": "object",
            "properties": {},
            "required": [],
            "additionalProperties": False,
        },
        "bindings": [],
        "steps": [
            {
                "step_index": 0,
                "source_state_id": "state-main",
                "action": {"tool": "wait", "args": {"duration_ms": 1000}},
            }
        ],
        "checker_rules": [
            {
                "schema_version": "omniflow.checker_rule.v1",
                "trigger": 'text_contains("跳过广告")',
                "source_state_id": "invented-state",
                "action": {"tool": "click", "args": {"x": 900, "y": 100}},
            }
        ],
        "agent_visible": True,
    }
    run_log = {
        "schema_version": "omniflow.canonical_run_log.v1",
        "run_id": "run-main",
        "goal": "Continue",
        "status": "succeeded",
        "success": True,
        "steps": [
            {
                "step_index": 0,
                "before_state_id": "state-main",
                "action": {"tool": "wait", "args": {"duration_ms": 1000}},
                "result": {"success": True},
                "after_state_id": "state-final",
                "metadata": {},
            }
        ],
    }

    with pytest.raises(ValueError, match="function_checker_rule_missing_recovery_evidence"):
        compile_runlog_to_store(
            run_log,
            tmp_path / "compiled",
            function_bundle={
                "schema_version": "omniflow.function-bundle.v2",
                "run_id": "run-main",
                "arguments": {"invented_recovery": {}},
                "functions": [function],
            },
        )
