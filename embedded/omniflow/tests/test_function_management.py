from __future__ import annotations

import pytest

from omniflow.artifact import bind_function, parse_function_artifact
from omniflow.function_management import edit_function, enhance_function


def function() -> dict:
    return {
        "schema_version": "omniflow.function.v2",
        "function_id": "search_product",
        "name": "Search product",
        "description": "Search for one product",
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
                "source_state_id": "state-0",
                "action": {
                    "tool": "input_text",
                    "args": {"text": "coffee"},
                },
            },
            {
                "step_index": 1,
                "source_state_id": "state-1",
                "action": {"tool": "wait", "args": {"duration_ms": 100}},
            },
        ],
        "checker_rules": [],
        "agent_visible": True,
    }


def test_action_edits_use_the_python_action_converter() -> None:
    updated, changes = edit_function(
        function(),
        [
            {"op": "replace_args", "index": 0, "args": {"text": "tea"}},
            {"op": "delete", "index": 1},
        ],
    )

    assert updated["steps"] == [
        {
            "step_index": 0,
            "source_state_id": "state-0",
            "action": {
                "tool": "input_text",
                    "args": {"text": "tea"},
            },
        }
    ]
    assert [change["op"] for change in changes] == ["replace_args", "delete"]


def test_enhancement_changes_only_metadata() -> None:
    original = function()
    updated, changes, status = enhance_function(
        original,
        {},
        lambda _prompt: '{"name":"Search store","checker_rules":[]}',
    )

    assert status == "enhanced"
    assert updated["name"] == "Search store"
    assert updated["steps"] == original["steps"]
    assert changes == [{"part": "function", "field": "name"}]


def test_enhancement_parameterizes_recorded_input_text() -> None:
    original = function()
    run_log = {
        "schema_version": "omniflow.canonical_run_log.v1",
        "run_id": "run-search",
        "goal": "Search for coffee",
        "steps": [
            {
                "step_index": 0,
                "before_state_id": "state-0",
                "action": {"tool": "input_text", "args": {"text": "coffee"}},
                "result": {"success": True},
                "after_state_id": "state-1",
            }
        ],
    }

    updated, changes, status = enhance_function(
        original,
        run_log,
        lambda _prompt: """{
          "parameters": [{
            "name": "query",
            "description": "Product to search for",
            "step_index": 0,
            "arg_name": "text"
          }]
        }""",
    )

    assert status == "enhanced"
    assert updated["input_schema"] == {
        "type": "object",
        "properties": {
            "query": {
                "type": "string",
                "description": "Product to search for",
            }
        },
        "required": ["query"],
        "additionalProperties": False,
    }
    assert updated["bindings"] == [
        {
            "source": "$.arguments.query",
            "target": "$.steps[0].action.args.text",
        }
    ]
    assert updated["steps"][0]["action"]["args"]["text"] == ""
    assert {change["field"] for change in changes} == {"parameters"}
    assert bind_function(
        parse_function_artifact(updated),
        {"query": "tea"},
    ).steps[0].action.args == {
        "text": "tea"
    }


def test_enhancement_rejects_parameter_without_successful_runlog_evidence() -> None:
    with pytest.raises(
        ValueError,
        match="function_enhancement_parameter_evidence_missing",
    ):
        enhance_function(
            function(),
            {},
            lambda _prompt: """{
              "parameters": [{
                "name": "query",
                "step_index": 0,
                "arg_name": "text"
              }]
            }""",
        )
