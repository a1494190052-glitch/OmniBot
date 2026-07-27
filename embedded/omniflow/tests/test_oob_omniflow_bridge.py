from __future__ import annotations

from io import StringIO
import json
from pathlib import Path
from types import SimpleNamespace

from omniflow import transfer as transfer_module
from omniflow.bridge import JsonLineBridge
from omnitransfer import action_transfer
from oob_omniflow_bridge import (
    BRIDGE_CONTRACT,
    CAPABILITIES,
    CONTRACT_SHA256,
    PROTOCOL_VERSION,
    OobOmniFlowBridge,
)
import pytest


def model_turn(tool: str, args: dict, summary: str) -> dict:
    return {
        "requested_model": "selected-vlm-model",
        "resolved_model": "selected-vlm-model",
        "tool_calls": [
            {
                "id": f"call-{tool}",
                "type": "function",
                "function": {
                    "name": tool,
                    "arguments": json.dumps(
                        {"summary": summary, **args},
                        ensure_ascii=False,
                    ),
                },
            }
        ],
    }


def function() -> dict:
    return {
        "schema_version": "omniflow.function.v2",
        "function_id": "enter_name",
        "name": "Enter name",
        "description": "Enter one contact name",
        "input_schema": {
            "type": "object",
            "properties": {"name": {"type": "string"}},
            "required": ["name"],
            "additionalProperties": False,
        },
        "bindings": [
            {
                "source": "$.arguments.name",
                "target": "$.steps[0].action.args.text",
            }
        ],
        "steps": [
            {
                "step_index": 0,
                "source_state_id": "state-0",
                "action": {"tool": "input_text", "args": {"text": ""}},
            }
        ],
        "checker_rules": [],
        "agent_visible": True,
    }


def test_transfer_uses_bundled_omnitransfer_without_repository(monkeypatch) -> None:
    bundled = SimpleNamespace(action_transfer=lambda **_kwargs: {"mapped": False})
    monkeypatch.delenv("OMNITRANSFER_ROOT", raising=False)
    monkeypatch.setattr(
        transfer_module.importlib,
        "import_module",
        lambda name: bundled if name == "omnitransfer" else None,
    )

    assert transfer_module.load_omnitransfer() is bundled


def test_health_advertises_the_complete_oob_contract(tmp_path: Path) -> None:
    bridge = OobOmniFlowBridge(tmp_path / "store.json")

    health = bridge._handle("health", "health", {})

    assert set(health["capabilities"]) == set(CAPABILITIES)
    assert health["protocol_version"] == PROTOCOL_VERSION
    assert health["contract_sha256"] == CONTRACT_SHA256
    assert health["omnitransfer_ready"] is True
    assert health["omnitransfer_backend"] in {"numpy", "pytorch"}

    assert set(BRIDGE_CONTRACT["operations"]) == {
        "function_tool",
        "health",
        "run",
        "shutdown",
    }
    function_request = BRIDGE_CONTRACT["operations"]["function_tool"]["request"]
    assert function_request["required"] == ["tool"]
    assert function_request["optional"] == ["args"]
    assert "function.recall" in function_request["tools"]
    assert "record_step" not in BRIDGE_CONTRACT["operations"]
    assert BRIDGE_CONTRACT["host_call"]["record_step_payload"] == {
        "required": ["fact"],
        "fact": {
            "required": [
                "before_state_id",
                "action",
                "result",
                "after_state_id",
            ],
            "optional": ["metadata"],
            "result_schema_ref": "omniflow_canonical_run_log.v1.json#/$defs/result",
            "action_schema_ref": "oob_canonical_actions.v1.json",
        },
    }
    assert BRIDGE_CONTRACT["host_call"]["record_step_response"] == {
        "required": ["step"],
        "step_schema_ref": "omniflow_canonical_run_log.v1.json#/$defs/step",
    }
    invariants = BRIDGE_CONTRACT["invariants"]
    assert invariants["action_coordinate_space"] == "relative_0_1000"
    assert invariants["vlm_tool_coordinate_space"] == "current_display_pixels"
    assert invariants["vlm_coordinate_conversion_owner"] == (
        "omniflow.vlm_coordinates"
    )
    assert invariants["manual_coordinate_conversion_owner"] == (
        "canonicalManualScreenAction"
    )


def test_health_loads_functions_after_action_canonicalization(tmp_path: Path) -> None:
    valid = function()
    invalid = function()
    invalid["function_id"] = "legacy_target"
    invalid["steps"][0]["action"]["args"]["target"] = {"text": "legacy"}
    store_path = tmp_path / "store.json"
    store_path.write_text(
        json.dumps(
            {
                "schema_version": "omniflow.store.v2",
                "functions": {
                    valid["function_id"]: valid,
                    invalid["function_id"]: invalid,
                },
            }
        )
    )

    health = OobOmniFlowBridge(store_path)._handle("health", "health", {})

    assert health["functions"] == 1
    assert health["invalid_functions"] == {
        "legacy_target": "function_action_target_forbidden:0"
    }


def test_goal_run_uses_explicit_model_and_records_step(tmp_path: Path) -> None:
    state_0 = {
        "state_id": "live-0",
        "package_name": "demo.app",
        "xml": "<hierarchy />",
        "display": {"width": 1080, "height": 2400},
    }
    state_1 = {**state_0, "state_id": "live-1"}

    def line(value: dict) -> str:
        return json.dumps(value) + "\n"

    reader = StringIO(
        line(
            {
                "id": "run",
                "op": "run",
                "payload": {
                    "goal": "tap search",
                    "model": "selected-vlm-model",
                    "max_steps": 4,
                    "disable_function_recall": True,
                    "run_id": "run-1",
                },
            }
        )
        + line(
            {
                "id": "run",
                "call_id": "run:1",
                "ok": True,
                "result": {"apps": {"Demo": "demo.app"}},
            }
        )
        + line({"id": "run", "call_id": "run:2", "ok": True, "result": state_0})
        + line({"id": "run", "call_id": "run:3", "ok": True, "result": state_0})
        + line(
            {
                "id": "run",
                "call_id": "run:4",
                "ok": True,
                "result": model_turn(
                    "click",
                    {"x": 500, "y": 300, "target_description": "Search"},
                    "Tap search",
                ),
            }
        )
        + line({"id": "run", "call_id": "run:5", "ok": True, "result": {"success": True}})
        + line({"id": "run", "call_id": "run:6", "ok": True, "result": state_1})
        + line(
            {
                "id": "run",
                "call_id": "run:7",
                "ok": True,
                "result": {
                    "step": {
                        "step_index": 0,
                        "before_state_id": "live-0",
                        "action": {
                            "tool": "click",
                            "args": {
                                "x": 500,
                                "y": 300,
                                "target_description": "Search",
                            },
                        },
                        "result": {"success": True},
                        "after_state_id": "live-1",
                        "metadata": {},
                    }
                },
            }
        )
        + line({"id": "run", "call_id": "run:8", "ok": True, "result": state_1})
        + line(
            {
                "id": "run",
                "call_id": "run:9",
                "ok": True,
                "result": model_turn("finished", {"content": "Search opened"}, "Done"),
            }
        )
    )
    writer = StringIO()
    JsonLineBridge(tmp_path / "store.json", reader=reader, writer=writer).serve_once()
    messages = [json.loads(value) for value in writer.getvalue().splitlines()]

    calls = [message for message in messages if message.get("event") == "host_call"]
    assert [message["method"] for message in calls] == [
        "installed_apps",
        "observe",
        "observe",
        "model_turn",
        "act",
        "observe",
        "record_step",
        "observe",
        "model_turn",
    ]
    assert calls[3]["payload"]["model"] == "selected-vlm-model"
    assert calls[6]["payload"]["fact"]["metadata"]["summary"] == "Tap search"
    assert calls[6]["payload"]["fact"]["result"] == {"success": True}
    assert "step_index" not in calls[6]["payload"]["fact"]
    result = next(message["result"] for message in messages if "ok" in message)
    assert result["success"] is True
    assert result["finished_content"] == "Search opened"


def test_goal_run_corrects_hidden_info_to_finished_without_request_input(
    tmp_path: Path,
) -> None:
    state = {
        "state_id": "live-0",
        "package_name": "demo.app",
        "xml": "<hierarchy />",
        "display": {"width": 1080, "height": 2400},
    }

    def line(value: dict) -> str:
        return json.dumps(value) + "\n"

    reader = StringIO(
        line(
            {
                "id": "run",
                "op": "run",
                "payload": {
                    "goal": "ask before continuing",
                    "model": "selected-vlm-model",
                    "max_steps": 4,
                    "disable_function_recall": True,
                },
            }
        )
        + line(
            {
                "id": "run",
                "call_id": "run:1",
                "ok": True,
                "result": {"apps": {"Demo": "demo.app"}},
            }
        )
        + line({"id": "run", "call_id": "run:2", "ok": True, "result": state})
        + line({"id": "run", "call_id": "run:3", "ok": True, "result": state})
        + line(
            {
                "id": "run",
                "call_id": "run:4",
                "ok": True,
                "result": model_turn("info", {"value": "Continue?"}, "Ask permission"),
            }
        )
        + line(
            {
                "id": "run",
                "call_id": "run:5",
                "ok": True,
                "result": model_turn("finished", {"content": "Confirmed"}, "Done"),
            }
        )
    )
    writer = StringIO()
    JsonLineBridge(tmp_path / "store.json", reader=reader, writer=writer).serve_once()
    messages = [json.loads(value) for value in writer.getvalue().splitlines()]

    calls = [message for message in messages if message.get("event") == "host_call"]
    assert [message["method"] for message in calls] == [
        "installed_apps",
        "observe",
        "observe",
        "model_turn",
        "model_turn",
    ]
    result = next(message["result"] for message in messages if "ok" in message)
    assert result["success"] is True
    assert result["finished_content"] == "Confirmed"


def test_catalog_and_recall_round_trip(tmp_path: Path) -> None:
    bridge = OobOmniFlowBridge(tmp_path / "store.json")
    stored = bridge._handle(
        "put",
        "function_tool",
        {
            "tool": "oob_function_register",
            "args": {"function": function()},
        },
    )
    recalled = bridge._handle(
        "recall",
        "function_tool",
        {
            "tool": "function.recall",
            "args": {"goal": "enter name", "state": {"state_id": "live-state"}},
        },
    )

    candidate = recalled["candidates"][0]

    assert stored["function_id"] == "enter_name"
    assert recalled["success"] is True
    assert recalled["retrieval_state"] == "has_candidates"
    assert recalled["count"] == 1
    assert candidate["function_id"] == "enter_name"
    assert candidate["tool"]["function"]["name"] == "recalled_function_1"
    assert candidate["tool"]["function"]["parameters"]["required"] == [
        "summary",
        "name",
    ]
    assert candidate["retrieval"] == {
        "score": 1.0,
        "source": "goal_token_jaccard",
        "rank": 1,
    }


def test_recall_matches_chinese_when_punctuation_differs(tmp_path: Path) -> None:
    bridge = OobOmniFlowBridge(tmp_path / "store.json")
    chinese_function = function()
    chinese_function.update(
        {
            "function_id": "open_app_settings",
            "name": "点击设置首页中的“应用”并进入应用设置列表",
            "description": "打开 Android 应用设置列表",
        }
    )
    bridge._handle(
        "put",
        "function_tool",
        {
            "tool": "oob_function_register",
            "args": {"function": chinese_function},
        },
    )
    unrelated_function = function()
    unrelated_function.update(
        {
            "function_id": "open_network_settings",
            "name": "打开网络与互联网设置",
            "description": "进入网络设置页面",
        }
    )
    bridge._handle(
        "put",
        "function_tool",
        {
            "tool": "oob_function_register",
            "args": {"function": unrelated_function},
        },
    )

    recalled = bridge._handle(
        "recall",
        "function_tool",
        {
            "tool": "function.recall",
            "args": {
                "goal": "点击设置首页中的应用并进入应用设置列表",
                "state": {"state_id": "live-state"},
            },
        },
    )

    assert recalled["retrieval_state"] == "has_candidates"
    assert [
        candidate["function_id"] for candidate in recalled["candidates"]
    ] == ["open_app_settings"]
    assert recalled["candidates"][0]["retrieval"]["score"] == 1.0


def test_compile_registers_base_function_without_default_enhancement(
    tmp_path: Path,
) -> None:
    run_log = {
        "schema_version": "omniflow.canonical_run_log.v1",
        "run_id": "source",
        "goal": "wait once",
        "status": "succeeded",
        "success": True,
        "steps": [
            {
                "step_index": 0,
                "before_state_id": "state-0",
                "action": {"tool": "wait", "args": {"duration_ms": 1000}},
                "result": {"success": True},
                "after_state_id": "state-1",
                "metadata": {},
            }
        ],
    }
    reader = StringIO(
        json.dumps(
            {"call_id": "compile:1", "ok": True, "result": run_log}
        )
        + "\n"
        + json.dumps(
            {
                "call_id": "compile:2",
                "ok": True,
                "result": {"state_id": "state-0"},
            }
        )
        + "\n"
    )
    writer = StringIO()
    bridge = OobOmniFlowBridge(
        tmp_path / "store.json",
        reader=reader,
        writer=writer,
    )

    result = bridge._handle(
        "compile",
        "function_tool",
        {
            "tool": "oob_run_log_convert",
            "args": {
                "run_id": "source",
                "register": True,
                "agent_visible": True,
                "function_id": "wait_once",
            },
        },
    )

    calls = [json.loads(line) for line in writer.getvalue().splitlines()]
    assert [call["method"] for call in calls] == ["get_run_log", "get_state"]
    assert result["registered"] is True
    assert result["function_id"] == "wait_once"
    assert result["enhancement_status"] == "none"
    assert result["changes"] == []
    registered = bridge.flow.store.get_function("wait_once")
    assert registered is not None
    assert registered.to_dict() == result["function"]


def test_compile_rejects_function_with_missing_source_state(tmp_path: Path) -> None:
    run_log = {
        "schema_version": "omniflow.canonical_run_log.v1",
        "run_id": "source",
        "goal": "open settings",
        "status": "succeeded",
        "success": True,
        "steps": [
            {
                "step_index": 0,
                "before_state_id": "missing-state",
                "action": {
                    "tool": "open_app",
                    "args": {"package_name": "com.android.settings"},
                },
                "result": {"success": True},
                "after_state_id": "after-state",
                "metadata": {},
            }
        ],
    }
    reader = StringIO(
        json.dumps({"call_id": "compile:1", "ok": True, "result": run_log})
        + "\n"
        + json.dumps(
            {
                "call_id": "compile:2",
                "ok": False,
                "error": {"code": "state_not_found:missing-state"},
            }
        )
        + "\n"
    )
    bridge = OobOmniFlowBridge(
        tmp_path / "store.json",
        reader=reader,
        writer=StringIO(),
    )

    result = bridge._handle(
        "compile",
        "function_tool",
        {
            "tool": "oob_run_log_convert",
            "args": {"run_id": "source", "register": True, "enhance": False},
        },
    )

    assert result["success"] is False
    assert result["registered"] is False
    assert result["error_code"] == "FUNCTION_SOURCE_STATE_NOT_FOUND"
    assert result["error_message"] == "state_not_found:missing-state"
    assert bridge.flow.store.functions == {}


def test_compile_registers_base_function_and_schedules_enhancement(tmp_path: Path) -> None:
    run_log = {
        "schema_version": "omniflow.canonical_run_log.v1",
        "run_id": "source",
        "goal": "enter a contact name",
        "status": "succeeded",
        "success": True,
        "steps": [
            {
                "step_index": 0,
                "before_state_id": "state-0",
                "action": {"tool": "input_text", "args": {"text": "Alice"}},
                "result": {"success": True},
                "after_state_id": "state-1",
                "metadata": {},
            }
        ],
    }
    reader = StringIO(
        json.dumps({"call_id": "compile:1", "ok": True, "result": run_log})
        + "\n"
        + json.dumps(
            {
                "call_id": "compile:2",
                "ok": True,
                "result": {"state_id": "state-0"},
            }
        )
        + "\n"
        + json.dumps(
            {
                "call_id": "compile:3",
                "ok": True,
                "result": {"accepted": True},
            }
        )
        + "\n"
    )
    writer = StringIO()
    bridge = OobOmniFlowBridge(
        tmp_path / "store.json",
        reader=reader,
        writer=writer,
    )

    result = bridge._handle(
        "compile",
        "function_tool",
        {
            "tool": "oob_run_log_convert",
            "args": {
                "run_id": "source",
                "register": True,
                "function_id": "enter_name",
                "enhance": True,
            },
        },
    )

    registered = bridge.flow.store.get_function("enter_name")
    calls = [json.loads(line) for line in writer.getvalue().splitlines()]
    assert [call["method"] for call in calls] == [
        "get_run_log",
        "get_state",
        "schedule_operation",
    ]
    assert result["enhancement_status"] == "enhancing"
    assert result["changes"] == []
    assert registered is not None
    assert registered.bindings == ()
    assert registered.steps[0].action.args == {"text": "Alice"}
    assert calls[-1]["payload"] == {
        "operation": "function_tool",
        "payload": {
            "tool": "update_function",
            "args": {
                "function_id": "enter_name",
                "mode": "enhance",
                "run_id": "source",
            },
        },
    }


def test_compile_can_skip_optional_enhancement(tmp_path: Path) -> None:
    run_log = {
        "schema_version": "omniflow.canonical_run_log.v1",
        "run_id": "source",
        "goal": "wait once",
        "status": "succeeded",
        "success": True,
        "steps": [
            {
                "step_index": 0,
                "before_state_id": "state-0",
                "action": {"tool": "wait", "args": {"duration_ms": 1000}},
                "result": {"success": True},
                "after_state_id": "state-1",
                "metadata": {},
            }
        ],
    }
    reader = StringIO(
        json.dumps(
            {"call_id": "compile:1", "ok": True, "result": run_log}
        )
        + "\n"
        + json.dumps(
            {
                "call_id": "compile:2",
                "ok": True,
                "result": {"state_id": "state-0"},
            }
        )
        + "\n"
    )
    writer = StringIO()
    bridge = OobOmniFlowBridge(
        tmp_path / "store.json",
        reader=reader,
        writer=writer,
    )

    result = bridge._handle(
        "compile",
        "function_tool",
        {
            "tool": "oob_run_log_convert",
            "args": {
                "run_id": "source",
                "register": True,
                "function_id": "wait_once",
                "enhance": False,
            },
        },
    )

    calls = [json.loads(line) for line in writer.getvalue().splitlines()]
    assert [call["method"] for call in calls] == ["get_run_log", "get_state"]
    assert result["success"] is True
    assert result["registered"] is True
    assert result["enhancement_status"] == "none"
    assert result["changes"] == []


def test_compile_keeps_function_when_background_schedule_fails(tmp_path: Path) -> None:
    run_log = {
        "schema_version": "omniflow.canonical_run_log.v1",
        "run_id": "source",
        "goal": "wait once",
        "status": "succeeded",
        "success": True,
        "steps": [
            {
                "step_index": 0,
                "before_state_id": "state-0",
                "action": {"tool": "wait", "args": {"duration_ms": 1000}},
                "result": {"success": True},
                "after_state_id": "state-1",
                "metadata": {},
            }
        ],
    }
    reader = StringIO(
        json.dumps(
            {"call_id": "compile:1", "ok": True, "result": run_log}
        )
        + "\n"
        + json.dumps(
            {
                "call_id": "compile:2",
                "ok": True,
                "result": {"state_id": "state-0"},
            }
        )
        + "\n"
        + json.dumps(
            {
                "call_id": "compile:3",
                "ok": False,
                "error": {"code": "background_scheduler_unavailable"},
            }
        )
        + "\n"
    )
    writer = StringIO()
    bridge = OobOmniFlowBridge(
        tmp_path / "store.json",
        reader=reader,
        writer=writer,
    )

    result = bridge._handle(
        "compile",
        "function_tool",
        {
            "tool": "oob_run_log_convert",
            "args": {
                "run_id": "source",
                "register": True,
                "function_id": "wait_once",
                "enhance": True,
            },
        },
    )

    calls = [json.loads(line) for line in writer.getvalue().splitlines()]
    assert [call["method"] for call in calls] == [
        "get_run_log",
        "get_state",
        "schedule_operation",
    ]
    assert result["success"] is True
    assert result["registered"] is True
    assert result["enhancement_status"] == "failed"
    assert result["changes"] == []
    assert result["message"] == "background_scheduler_unavailable"
    assert bridge.flow.store.get_function("wait_once") is not None


def test_update_function_uses_the_single_enhancement_interface(tmp_path: Path) -> None:
    reader = StringIO(
        json.dumps(
            {
                "call_id": "update:1",
                "ok": True,
                "result": {"updated": True},
            }
        )
        + "\n"
        + json.dumps(
            {
                "call_id": "update:2",
                "ok": True,
                "result": {"run_id": "source", "goal": "enter name", "steps": []},
            }
        )
        + "\n"
        + json.dumps(
            {
                "call_id": "update:3",
                "ok": True,
                "result": {
                    "content": json.dumps(
                        {"name": "Enter a contact name", "checker_rules": []}
                    )
                },
            }
        )
        + "\n"
        + json.dumps(
            {
                "call_id": "update:4",
                "ok": True,
                "result": {"updated": True},
            }
        )
        + "\n"
    )
    writer = StringIO()
    bridge = OobOmniFlowBridge(
        tmp_path / "store.json",
        reader=reader,
        writer=writer,
    )
    bridge._handle(
        "put",
        "function_tool",
        {"tool": "oob_function_register", "args": {"function": function()}},
    )

    result = bridge._handle(
        "update",
        "function_tool",
        {
            "tool": "update_function",
            "args": {
                "function_id": "enter_name",
                "mode": "enhance",
                "run_id": "source",
            },
        },
    )

    calls = [json.loads(line) for line in writer.getvalue().splitlines()]
    assert [call["method"] for call in calls] == [
        "update_run_log_diagnostics",
        "get_run_log",
        "complete_json",
        "update_run_log_diagnostics",
    ]
    assert result["success"] is True
    assert result["updated_function"]["name"] == "Enter a contact name"
    assert result["enhancement_status"] == "enhanced"


def test_update_function_does_not_overwrite_concurrent_change(
    tmp_path: Path,
    monkeypatch,
) -> None:
    store_path = tmp_path / "store.json"
    bridge = OobOmniFlowBridge(store_path)
    bridge._handle(
        "put",
        "function_tool",
        {"tool": "oob_function_register", "args": {"function": function()}},
    )

    def complete_json(_request_id: str, _prompt: str) -> str:
        concurrent = function()
        concurrent["description"] = "Changed while enhancement was running."
        OobOmniFlowBridge(store_path)._handle(
            "put",
            "function_tool",
            {
                "tool": "oob_function_register",
                "args": {"function": concurrent},
            },
        )
        return json.dumps(
            {"name": "Enter a contact name", "checker_rules": []}
        )

    monkeypatch.setattr(bridge, "_complete_json", complete_json)
    result = bridge._handle(
        "update",
        "function_tool",
        {
            "tool": "update_function",
            "args": {"function_id": "enter_name", "mode": "enhance"},
        },
    )

    assert result["success"] is False
    assert result["saved"] is False
    assert result["error_code"] == "FUNCTION_ENHANCEMENT_CONFLICT"
    bridge.flow.store.reload()
    assert bridge.flow.store.get_function("enter_name").description == (
        "Changed while enhancement was running."
    )


def test_update_function_dry_run_returns_preview_without_saving(tmp_path: Path) -> None:
    reader = StringIO(
        json.dumps(
            {
                "call_id": "update:1",
                "ok": True,
                "result": {"updated": True},
            }
        )
        + "\n"
        + json.dumps(
            {
                "call_id": "update:2",
                "ok": True,
                "result": {"run_id": "source", "goal": "enter name", "steps": []},
            }
        )
        + "\n"
        + json.dumps(
            {
                "call_id": "update:3",
                "ok": True,
                "result": {
                    "content": json.dumps(
                        {"name": "Enter a contact name", "checker_rules": []}
                    )
                },
            }
        )
        + "\n"
        + json.dumps(
            {
                "call_id": "update:4",
                "ok": True,
                "result": {"updated": True},
            }
        )
        + "\n"
    )
    bridge = OobOmniFlowBridge(
        tmp_path / "store.json",
        reader=reader,
        writer=StringIO(),
    )
    bridge._handle(
        "put",
        "function_tool",
        {"tool": "oob_function_register", "args": {"function": function()}},
    )

    result = bridge._handle(
        "update",
        "function_tool",
        {
            "tool": "update_function",
            "args": {
                "function_id": "enter_name",
                "mode": "enhance",
                "run_id": "source",
                "dry_run": True,
            },
        },
    )

    assert result["success"] is True
    assert result["saved"] is False
    assert result["dry_run"] is True
    assert result["updated_function"]["name"] == "Enter a contact name"
    assert bridge.flow.store.get_function("enter_name").name == "Enter name"


def test_catalog_put_rejects_stale_enhancement_preview(tmp_path: Path) -> None:
    bridge = OobOmniFlowBridge(tmp_path / "store.json")
    original = function()
    bridge._handle(
        "put",
        "function_tool",
        {"tool": "oob_function_register", "args": {"function": original}},
    )
    edited = {**original, "name": "User edited name"}
    bridge._handle(
        "edit",
        "function_tool",
        {"tool": "oob_function_register", "args": {"function": edited}},
    )
    enhanced = {**original, "description": "Enhanced description"}

    result = bridge._handle(
        "commit",
        "function_tool",
        {
            "tool": "oob_function_register",
            "args": {
                "function": enhanced,
                "expected_function": original,
            },
        },
    )

    assert result["success"] is False
    assert result["error_code"] == "FUNCTION_ENHANCEMENT_CONFLICT"
    assert bridge.flow.store.get_function("enter_name").name == "User edited name"


def test_omnitransfer_fails_closed_when_matcher_is_unavailable(monkeypatch) -> None:
    import omnitransfer.runtime as runtime

    def unavailable():
        raise RuntimeError("checkpoint unavailable on this runtime")

    monkeypatch.setattr(runtime, "_get_matcher", unavailable, raising=False)
    source = '<hierarchy><node clickable="true" bounds="[0,0][100,100]" /></hierarchy>'
    target = (
        '<hierarchy><node clickable="true" bounds="[0,0][80,80]" />'
        '<node clickable="true" bounds="[120,120][200,200]" /></hierarchy>'
    )

    result = action_transfer(
        source_xml=source,
        target_xml=target,
        source_point=(50, 50),
        action_type="click",
    )

    assert result["mapped"] is False
    assert result["mapping_mode"] == "mutual_graph_matcher_no_null_v3"
    assert result["reason"] == "matcher_unavailable"


def test_omnitransfer_maps_equivalent_ui_graph_without_matcher(monkeypatch) -> None:
    import omnitransfer.runtime as runtime

    monkeypatch.setattr(
        runtime,
        "_get_matcher",
        lambda: pytest.fail("equivalent graphs must not invoke the learned matcher"),
    )
    xml = (
        '<hierarchy bounds="[0,0][100,100]">'
        '<node resource-id="demo:id/search" text="Search" '
        'clickable="true" enabled="true" bounds="[10,20][50,60]" />'
        '</hierarchy>'
    )

    result = action_transfer(
        source_xml=xml,
        target_xml=xml,
        source_point=(30, 40),
        source_package_name="demo",
        target_package_name="demo",
    )

    assert result["mapped"] is True
    assert result["mapping_mode"] == "equivalent_ui_graph"
    assert (result["new_x"], result["new_y"]) == (30.0, 40.0)


@pytest.mark.parametrize(
    "operation",
    [
        "catalog",
        "compile",
        "control_act",
        "prepare_action",
        "recall",
        "update_function",
    ],
)
def test_bridge_rejects_removed_one_step_operations(
    tmp_path: Path,
    operation: str,
) -> None:
    bridge = OobOmniFlowBridge(tmp_path / "store.json")

    with pytest.raises(ValueError, match=f"unsupported_operation:{operation}"):
        bridge._handle("removed", operation, {})
