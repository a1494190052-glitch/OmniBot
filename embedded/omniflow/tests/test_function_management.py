from __future__ import annotations

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
