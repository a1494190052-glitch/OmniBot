from __future__ import annotations

from io import StringIO
import json
from pathlib import Path
from types import SimpleNamespace
import pytest

from omniflow.bridge import JsonLineBridge
from omniflow import transfer as transfer_module
from omnitransfer import action_transfer
from oob_omniflow_bridge import (
    BRIDGE_CONTRACT,
    CAPABILITIES,
    CONTRACT_SHA256,
    PROTOCOL_VERSION,
    OobOmniFlowBridge,
)


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

    recall_response = BRIDGE_CONTRACT["operations"]["recall"]["response"]
    assert recall_response["required"] == [
        "success",
        "retrieval_state",
        "candidates",
        "count",
        "reason",
        "runtime_source",
        "duration_ms",
    ]
    assert recall_response["candidate"]["required"] == ["function", "retrieval"]
    assert recall_response["candidate"]["retrieval"]["required"] == [
        "score",
        "source",
        "rank",
    ]
    assert "record_step" not in BRIDGE_CONTRACT["operations"]
    assert BRIDGE_CONTRACT["host_call"]["record_step_payload"] == {
        "required": ["step"],
        "step_schema_ref": "omniflow_canonical_run_log.v1.json#/$defs/step",
    }


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
        + line({"id": "run", "call_id": "run:1", "ok": True, "result": state_0})
        + line({"id": "run", "call_id": "run:2", "ok": True, "result": state_0})
        + line(
            {
                "id": "run",
                "call_id": "run:3",
                "ok": True,
                "result": {
                    "requested_model": "selected-vlm-model",
                    "action": {
                        "tool": "click",
                        "args": {"x": 500, "y": 300, "target_description": "Search"},
                    },
                    "metadata": {"summary": "Tap search"},
                },
            }
        )
        + line({"id": "run", "call_id": "run:4", "ok": True, "result": {"success": True}})
        + line({"id": "run", "call_id": "run:5", "ok": True, "result": state_1})
        + line({"id": "run", "call_id": "run:6", "ok": True, "result": {"recorded": True}})
        + line({"id": "run", "call_id": "run:7", "ok": True, "result": state_1})
        + line(
            {
                "id": "run",
                "call_id": "run:8",
                "ok": True,
                "result": {
                    "requested_model": "selected-vlm-model",
                    "action": {"tool": "finished", "args": {"content": "Search opened"}},
                    "metadata": {"summary": "Done"},
                },
            }
        )
    )
    writer = StringIO()
    JsonLineBridge(tmp_path / "store.json", reader=reader, writer=writer).serve_once()
    messages = [json.loads(value) for value in writer.getvalue().splitlines()]

    calls = [message for message in messages if message.get("event") == "host_call"]
    assert [message["method"] for message in calls] == [
        "observe",
        "observe",
        "model_turn",
        "act",
        "observe",
        "record_step",
        "observe",
        "model_turn",
    ]
    assert calls[2]["payload"]["model"] == "selected-vlm-model"
    assert calls[5]["payload"]["step"]["metadata"]["summary"] == "Tap search"
    result = next(message["result"] for message in messages if "ok" in message)
    assert result["success"] is True
    assert result["finished_content"] == "Search opened"


def test_goal_run_returns_info_answer_to_the_next_model_turn(tmp_path: Path) -> None:
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
        + line({"id": "run", "call_id": "run:1", "ok": True, "result": state})
        + line({"id": "run", "call_id": "run:2", "ok": True, "result": state})
        + line(
            {
                "id": "run",
                "call_id": "run:3",
                "ok": True,
                "result": {
                    "requested_model": "selected-vlm-model",
                    "action": {"tool": "info", "args": {"value": "Continue?"}},
                    "metadata": {"summary": "Ask permission"},
                },
            }
        )
        + line({"id": "run", "call_id": "run:4", "ok": True, "result": {"value": "yes"}})
        + line({"id": "run", "call_id": "run:5", "ok": True, "result": state})
        + line(
            {
                "id": "run",
                "call_id": "run:6",
                "ok": True,
                "result": {
                    "requested_model": "selected-vlm-model",
                    "action": {"tool": "finished", "args": {"content": "Confirmed"}},
                    "metadata": {"summary": "Done"},
                },
            }
        )
    )
    writer = StringIO()
    JsonLineBridge(tmp_path / "store.json", reader=reader, writer=writer).serve_once()
    messages = [json.loads(value) for value in writer.getvalue().splitlines()]

    calls = [message for message in messages if message.get("event") == "host_call"]
    assert [message["method"] for message in calls] == [
        "observe",
        "observe",
        "model_turn",
        "request_input",
        "observe",
        "model_turn",
    ]
    assert calls[5]["payload"]["state"]["extra"]["user_input"] == "yes"
    result = next(message["result"] for message in messages if "ok" in message)
    assert result["success"] is True
    assert result["finished_content"] == "Confirmed"


def test_catalog_and_recall_round_trip(tmp_path: Path) -> None:
    bridge = OobOmniFlowBridge(tmp_path / "store.json")
    stored = bridge._handle(
        "put",
        "catalog",
        {"action": "put", "function": function()},
    )
    recalled = bridge._handle(
        "recall",
        "recall",
        {"goal": "enter name", "state": {"state_id": "live-state"}},
    )

    candidate = recalled["candidates"][0]

    assert stored["function_id"] == "enter_name"
    assert recalled["success"] is True
    assert recalled["retrieval_state"] == "has_candidates"
    assert recalled["count"] == 1
    assert candidate["function"]["function_id"] == "enter_name"
    assert "score" not in candidate["function"]
    assert candidate["retrieval"] == {
        "score": 0.5,
        "source": "goal_token_jaccard",
        "rank": 1,
    }


def test_compile_registers_without_calling_enhancement(tmp_path: Path) -> None:
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
    )
    writer = StringIO()
    bridge = OobOmniFlowBridge(
        tmp_path / "store.json",
        reader=reader,
        writer=writer,
    )

    result = bridge._handle(
        "compile",
        "compile",
        {
            "run_id": "source",
            "register": True,
            "agent_visible": True,
            "function_id": "wait_once",
        },
    )

    calls = [json.loads(line) for line in writer.getvalue().splitlines()]
    assert [call["method"] for call in calls] == ["get_run_log"]
    assert result["registered"] is True
    assert result["function_id"] == "wait_once"
    assert bridge.flow.store.get_function("wait_once") is not None


def test_update_function_uses_the_single_enhancement_interface(tmp_path: Path) -> None:
    reader = StringIO(
        json.dumps(
            {
                "call_id": "update:1",
                "ok": True,
                "result": {"run_id": "source", "goal": "enter name", "steps": []},
            }
        )
        + "\n"
        + json.dumps(
            {
                "call_id": "update:2",
                "ok": True,
                "result": {
                    "content": json.dumps(
                        {"name": "Enter a contact name", "checker_rules": []}
                    )
                },
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
    bridge._handle("put", "catalog", {"action": "put", "function": function()})

    result = bridge._handle(
        "update",
        "update_function",
        {"function_id": "enter_name", "mode": "enhance", "run_id": "source"},
    )

    calls = [json.loads(line) for line in writer.getvalue().splitlines()]
    assert [call["method"] for call in calls] == ["get_run_log", "complete_json"]
    assert result["success"] is True
    assert result["updated_function"]["name"] == "Enter a contact name"
    assert result["enhancement_status"] == "enhanced"


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


def test_prepare_action_uses_source_state_id_and_real_omnitransfer(
    tmp_path: Path,
    monkeypatch,
) -> None:
    import omnitransfer.runtime as runtime

    class Matcher:
        def predict(self, source, target, **_kwargs):
            target_node = next(
                node for node in target.nodes if node.resource_id == "demo:id/search"
            )
            return SimpleNamespace(
                target_node=target_node,
                probability=0.99,
                margin=0.8,
                reason="learned_match",
                scores=((target_node.node_id, 0.99), ("__NULL__", 0.01)),
            )

    monkeypatch.setattr(runtime, "_get_matcher", lambda: Matcher())
    source_xml = (
        '<hierarchy bounds="[0,0][100,100]">'
        '<node resource-id="demo:id/search" text="Search" '
        'class="android.widget.Button" clickable="true" enabled="true" '
        'bounds="[10,20][50,60]" />'
        '</hierarchy>'
    )
    target_xml = (
        '<hierarchy bounds="[0,0][200,400]">'
        '<node resource-id="demo:id/search" text="Search" '
        'class="android.widget.Button" clickable="true" enabled="true" '
        'bounds="[40,100][120,260]" />'
        '</hierarchy>'
    )
    reader = StringIO(
        json.dumps(
            {
                "call_id": "prepare:1",
                "ok": True,
                "result": {
                    "state_id": "state-0",
                    "xml": source_xml,
                    "display": {"width": 100, "height": 100},
                },
            }
        )
        + "\n"
    )
    bridge = OobOmniFlowBridge(
        tmp_path / "store.json",
        reader=reader,
        writer=StringIO(),
    )

    prepared = bridge._handle(
        "prepare",
        "prepare_action",
        {
            "function_id": "tap_search",
            "source_state_id": "state-0",
            "action": {"tool": "click", "args": {"x": 300, "y": 400}},
            "state": {
                "state_id": "live-state",
                "xml": target_xml,
                "display": {"width": 200, "height": 400},
            },
        },
    )

    assert prepared["success"] is True
    assert prepared["decision"] == "ready"
    assert "coordinate_space" not in prepared
    assert prepared["action"]["args"]["x"] == 400
    assert prepared["action"]["args"]["y"] == 450


def test_control_act_owns_direct_action_loop(tmp_path: Path) -> None:
    xml = '<hierarchy><node bounds="[0,0][100,200]" /></hierarchy>'
    reader = StringIO(
        json.dumps(
            {"call_id": "control:1", "ok": True, "result": {"success": True}}
        )
        + "\n"
        + json.dumps(
            {
                "call_id": "control:2",
                "ok": True,
                "result": {
                    "state_id": "live-1",
                    "xml": xml,
                    "display": {"width": 100, "height": 200},
                },
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
        "control",
        "control_act",
        {
            "action": {
                "tool": "click",
                "args": {"target_description": "Search", "x": 250, "y": 750},
            },
            "state": {
                "state_id": "live-0",
                "xml": xml,
                "display": {"width": 100, "height": 200},
            },
        },
    )

    calls = [json.loads(line) for line in writer.getvalue().splitlines()]
    assert [call["method"] for call in calls] == ["act", "observe"]
    assert result["success"] is True
    assert result["before_state"]["state_id"] == "live-0"
    assert result["before_state"]["xml"] == xml
    assert result["after_state"]["state_id"] == "live-1"
    assert result["after_state"]["xml"] == xml


def test_control_act_keeps_runtime_input_target_coordinates(tmp_path: Path) -> None:
    xml = '<hierarchy><node bounds="[0,0][100,200]" /></hierarchy>'
    reader = StringIO(
        json.dumps(
            {"call_id": "control:1", "ok": True, "result": {"success": True}}
        )
        + "\n"
        + json.dumps(
            {
                "call_id": "control:2",
                "ok": True,
                "result": {
                    "state_id": "live-1",
                    "xml": xml,
                    "display": {"width": 100, "height": 200},
                },
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
        "control",
        "control_act",
        {
            "action": {
                "tool": "input_text",
                "args": {
                    "target_description": "Search",
                    "text": "coffee",
                    "x": 500,
                    "y": 250,
                },
            },
            "state": {
                "state_id": "live-0",
                "xml": xml,
                "display": {"width": 100, "height": 200},
            },
        },
    )

    calls = [json.loads(line) for line in writer.getvalue().splitlines()]
    assert calls[0]["payload"] == {
        "action": {
            "tool": "input_text",
            "args": {
                "target_description": "Search",
                "text": "coffee",
                "x": 500,
                "y": 250,
            },
        },
        "state": {
            "state_id": "live-0",
            "xml": xml,
            "display": {"width": 100, "height": 200},
        },
        "metadata": {},
    }


def test_prepare_action_blocks_without_source_state(tmp_path: Path) -> None:
    result = OobOmniFlowBridge(tmp_path / "store.json")._handle(
        "prepare",
        "prepare_action",
        {
            "function_id": "tap_search",
            "source_state_id": "",
            "action": {"tool": "click", "args": {"x": 300, "y": 400}},
            "state": {"state_id": "live", "xml": "<hierarchy />"},
        },
    )

    assert result["decision"] == "block"
    assert result["action"] is None


def test_prepare_action_reports_source_and_top_three_targets(
    tmp_path: Path,
    monkeypatch,
) -> None:
    import omnitransfer.runtime as runtime

    class Matcher:
        def predict(self, _source, target, **_kwargs):
            candidates = [node for node in target.nodes if node.text == "Date"]
            return SimpleNamespace(
                target_node=None,
                probability=0.45,
                margin=0.0,
                reason="learned_low_confidence",
                scores=tuple(
                    (node.node_id, 0.45 - index * 0.05)
                    for index, node in enumerate(candidates)
                ),
            )

    monkeypatch.setattr(runtime, "_get_matcher", lambda: Matcher())
    source_xml = (
        '<hierarchy bounds="[0,0][100,100]">'
        '<node text="Date" clickable="true" bounds="[10,10][90,90]" />'
        '</hierarchy>'
    )
    target_xml = (
        '<hierarchy bounds="[0,0][300,300]">'
        '<node text="Date" clickable="true" bounds="[10,10][90,90]" />'
        '<node text="Date" clickable="true" bounds="[110,10][190,90]" />'
        '<node text="Date" clickable="true" bounds="[210,10][290,90]" />'
        '</hierarchy>'
    )
    reader = StringIO(
        json.dumps(
            {
                "call_id": "prepare:1",
                "ok": True,
                "result": {"state_id": "state-0", "xml": source_xml},
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
        "prepare",
        "prepare_action",
        {
            "function_id": "tap_date",
            "source_state_id": "state-0",
            "action": {"tool": "click", "args": {"x": 500, "y": 500}},
            "state": {
                "state_id": "live",
                "xml": target_xml,
                "display": {"width": 300, "height": 300},
            },
        },
    )

    assert result["decision"] == "block"
    assert result["reason"] == "omnitransfer_learned_low_confidence"
    assert result["transfer"]["source"]["text"] == "Date"
    assert result["transfer"]["source"]["display"] == {
        "width": 100.0,
        "height": 100.0,
    }
    assert len(result["transfer"]["candidates"]) == 3
    assert [item["rank"] for item in result["transfer"]["candidates"]] == [1, 2, 3]
