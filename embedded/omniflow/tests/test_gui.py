from __future__ import annotations

import json
from pathlib import Path
import re

from omniflow.bridge import _BridgePlanner
from omniflow.gui import (
    build_model_turn_request,
)
from omniflow.gui import (
    parse_model_turn_response as _parse_model_turn_response,
)
from omniflow.model import Observation
from omniflow.schemas import VLM_ACTION_TOOL_NAMES, load_canonical_action_schema
import pytest

_DATASET = json.loads(
    (Path(__file__).with_name("data") / "vlm_tool_call_cases.v1.json").read_text(
        encoding="utf-8"
    )
)
_VALID_TOOL_CALLS = _DATASET["valid_tool_calls"]
_VLM_TOOL_CALLS = [
    case for case in _VALID_TOOL_CALLS if case["tool"] in VLM_ACTION_TOOL_NAMES
]
_INVALID_TOOL_CALLS = _DATASET["invalid_tool_calls"]
_ADAPTED_TOOL_CALLS = _DATASET["adapted_tool_calls"]
_ADAPTER_REJECTION_CASES = _DATASET["adapter_rejection_cases"]


def parse_model_turn_response(value, **kwargs):
    kwargs.setdefault("display", {"width": 1000, "height": 1000})
    return _parse_model_turn_response(value, **kwargs)


def _response(
    tool: str,
    arguments: dict,
    *,
    requested_model: str = "gui-model",
    resolved_model: str = "resolved-gui-model",
) -> dict:
    return _raw_response(
        tool,
        json.dumps(arguments, ensure_ascii=False),
        requested_model=requested_model,
        resolved_model=resolved_model,
    )


def _raw_response(
    tool: str,
    raw_arguments: str,
    *,
    requested_model: str = "gui-model",
    resolved_model: str = "resolved-gui-model",
) -> dict:
    return {
        "requested_model": requested_model,
        "resolved_model": resolved_model,
        "tool_calls": [
            {
                "id": "call-1",
                "type": "function",
                "function": {
                    "name": tool,
                    "arguments": raw_arguments,
                },
            }
        ],
        "reasoning": "search is visible",
        "usage": {"prompt_tokens": 10, "completion_tokens": 4, "total_tokens": 14},
    }


def _visible_tool_specs() -> dict[str, dict]:
    return {
        str(tool["name"]): tool
        for tool in load_canonical_action_schema()["tools"]
        if tool.get("model_visible") is not False
    }


def _wrong_type(argument_type: str):
    return {
        "string": 7,
        "number": "500",
        "integer": 1.5,
        "boolean": "true",
        "object": [],
        "string_array": "option",
    }[argument_type]


def _invalid_schema_cases() -> list:
    specs_by_tool = _visible_tool_specs()
    cases = []
    for seed in _VLM_TOOL_CALLS:
        tool = seed["tool"]
        arguments = dict(seed["arguments"])
        tool_spec = specs_by_tool[tool]
        cases.append(
            pytest.param(
                tool,
                {key: value for key, value in arguments.items() if key != "summary"},
                "model_turn_summary_required",
                id=f"{tool}-missing-summary",
            )
        )
        cases.append(
            pytest.param(
                tool,
                {**arguments, "unexpected_field": "invalid"},
                f"canonical_action_args_unknown:{tool}:unexpected_field",
                id=f"{tool}-unknown-field",
            )
        )
        for argument_spec in tool_spec.get("args") or ():
            name = str(argument_spec["name"])
            if argument_spec.get("required"):
                missing = dict(arguments)
                missing.pop(name, None)
                cases.append(
                    pytest.param(
                        tool,
                        missing,
                        f"canonical_action_required_args_missing:{tool}:{name}",
                        id=f"{tool}-{name}-required",
                    )
                )
            if name in arguments:
                cases.append(
                    pytest.param(
                        tool,
                        {**arguments, name: _wrong_type(str(argument_spec["type"]))},
                        f"canonical_action_arg_type_invalid:{name}",
                        id=f"{tool}-{name}-type",
                    )
                )
            if argument_spec.get("enum_values"):
                cases.append(
                    pytest.param(
                        tool,
                        {**arguments, name: "__invalid_enum__"},
                        f"canonical_action_arg_enum_invalid:{name}",
                        id=f"{tool}-{name}-enum",
                    )
                )
            if argument_spec.get("minimum") is not None:
                cases.append(
                    pytest.param(
                        tool,
                        {**arguments, name: argument_spec["minimum"] - 1},
                        f"canonical_action_arg_range_invalid:{name}",
                        id=f"{tool}-{name}-minimum",
                    )
                )
            if argument_spec.get("maximum") is not None:
                cases.append(
                    pytest.param(
                        tool,
                        {**arguments, name: argument_spec["maximum"] + 1},
                        f"canonical_action_arg_range_invalid:{name}",
                        id=f"{tool}-{name}-maximum",
                    )
                )
    return cases


def _valid_boundary_cases() -> list:
    specs_by_tool = _visible_tool_specs()
    cases = []
    for seed in _VLM_TOOL_CALLS:
        tool = seed["tool"]
        arguments = dict(seed["arguments"])
        for argument_spec in specs_by_tool[tool].get("args") or ():
            name = str(argument_spec["name"])
            for enum_value in argument_spec.get("enum_values") or ():
                cases.append(
                    pytest.param(
                        tool,
                        {**arguments, name: enum_value},
                        id=f"{tool}-{name}-enum-{enum_value}",
                    )
                )
            for boundary_name in ("minimum", "maximum"):
                if argument_spec.get(boundary_name) is not None:
                    cases.append(
                        pytest.param(
                            tool,
                            {**arguments, name: argument_spec[boundary_name]},
                            id=f"{tool}-{name}-{boundary_name}",
                        )
                    )
    return cases


def test_model_request_uses_canonical_tools_and_one_native_call() -> None:
    request = build_model_turn_request(
        goal="search for tea",
        model="gui-model",
        state={
            "state_id": "state-1",
            "package_name": "browser",
            "activity_name": "Main",
            "display": {"width": 1080, "height": 2400},
            "xml": "<hierarchy />",
        },
        max_steps=12,
        turn_index=1,
        installed_apps={"Browser": "browser"},
    )

    click = next(
        tool for tool in request["tools"] if tool["function"]["name"] == "click"
    )
    tool_names = {tool["function"]["name"] for tool in request["tools"]}
    parameters = click["function"]["parameters"]
    assert all(tool["function"]["strict"] is True for tool in request["tools"])
    assert parameters["required"] == ["summary", "x", "y"]
    assert "target_description" not in parameters["properties"]
    assert parameters["properties"]["x"]["maximum"] == 1080
    assert parameters["properties"]["y"]["maximum"] == 2400
    assert request["tool_choice"] == "required"
    assert request["parallel_tool_calls"] is False
    assert [tool["function"]["name"] for tool in request["tools"]] == [
        "click",
        "input_text",
        "swipe",
        "press_key",
        "finished",
    ]
    assert {
        tool["function"]["name"]: list(
            tool["function"]["parameters"]["properties"]
        )
        for tool in request["tools"]
    } == {
        "click": ["summary", "x", "y"],
        "input_text": ["summary", "text", "x", "y"],
        "swipe": ["summary", "direction", "x1", "y1", "x2", "y2"],
        "press_key": ["summary", "key"],
        "finished": ["summary", "content"],
    }
    assert "wait" not in tool_names
    assert "Installed apps" not in request["messages"][1]["content"][0]["text"]
    system_prompt = request["messages"][0]["content"]
    assert "raw pixels in the current original Display" in system_prompt
    turn_text = request["messages"][1]["content"][0]["text"]
    assert "X 0..1080, Y 0..2400" in turn_text
    assert "Do not output normalized 0..1000 coordinates" in turn_text


def test_bridge_planner_opens_goal_app_locally_without_model_turn() -> None:
    class NoModelBridge:
        def host_call(self, *_args, **_kwargs) -> dict:
            raise AssertionError("model_turn_not_expected")

    host = type("Host", (), {"current_action_metadata": {}})()
    planner = _BridgePlanner(
        NoModelBridge(),
        "request-1",
        host,
        model="gui-model",
        target_package_name="",
        step_skill_guidance="",
        installed_apps={"百度地图": "com.baidu.BaiduMap", "浏览器": "browser"},
        max_steps=12,
    )

    action = planner.one_step_action(
        "打开百度地图搜索西湖路线",
        Observation(
            xml="<hierarchy />",
            package_name="cn.com.omnimind.bot.debug",
            activity_name="MainActivity",
            extra={"state_id": "state-1"},
        ),
    )

    assert action.to_dict() == {
        "tool": "open_app",
        "args": {"package_name": "com.baidu.BaiduMap"},
    }
    assert planner.take_metadata() == {
        "summary": "打开百度地图",
        "execution": "local_app_bootstrap",
    }


def test_model_request_tells_vlm_to_reselect_after_stalled_history() -> None:
    request = build_model_turn_request(
        goal="Open Settings",
        model="gui-model",
        state={
            "state_id": "state-2",
            "package_name": "cn.com.omnimind.bot.debug",
            "activity_name": "MainActivity",
            "display": {"width": 1080, "height": 2400},
            "xml": "<hierarchy />",
            "extra": {
                "previous_action_error": "repeated_action_without_progress",
                "previous_action": {
                    "tool": "open_app",
                    "args": {"package_name": "com.android.settings"},
                },
                "recent_actions": [
                    {
                        "tool": "open_app",
                        "args": {"package_name": "com.android.settings"},
                        "success": True,
                    }
                ],
            },
        },
        max_steps=12,
        turn_index=3,
    )

    text = request["messages"][1]["content"][0]["text"]
    assert "Do not repeat the same action" in text
    assert "repeated_action_without_progress" in text


def test_model_request_tells_vlm_to_finish_after_successful_recalled_function() -> None:
    request = build_model_turn_request(
        goal="Use the saved workflow once",
        model="gui-model",
        state={
            "state_id": "state-3",
            "package_name": "com.android.settings",
            "activity_name": "Settings",
            "display": {"width": 1080, "height": 2400},
            "xml": "<hierarchy />",
            "extra": {
                "recent_actions": [
                    {
                        "tool": "click",
                        "args": {"x": 333, "y": 150},
                        "success": True,
                        "error": None,
                        "function_id": "saved_workflow",
                    }
                ]
            },
        },
        max_steps=12,
        turn_index=2,
    )

    text = request["messages"][1]["content"][0]["text"]
    assert "A recalled Function completed successfully" in text
    assert "choose finished now" in text


def test_schema_retry_uses_verbatim_bad_call_without_images(tmp_path: Path) -> None:
    screenshot = tmp_path / "state.jpg"
    screenshot.write_bytes(b"\xff\xd8\xffmock-jpeg")

    request = build_model_turn_request(
        goal="Tap once",
        model="gui-model",
        state={
            "package_name": "browser",
            "activity_name": "Main",
            "display": {"width": 1080, "height": 2400},
            "xml": "<hierarchy />",
            "screenshot_path": str(screenshot),
        },
        max_steps=3,
        turn_index=2,
        previous_screenshot_path=str(screenshot),
        validation_error="canonical_action_arg_type_invalid:x",
        retry_tool_name="click",
        rejected_tool_call={
            "tool": "click",
            "arguments": {"x": [67, 83], "summary": "Tap back"},
        },
    )

    content = request["messages"][1]["content"]
    assert [item["type"] for item in content] == ["text"]
    text = content[0]["text"]
    assert '"x":[67,83]' in text
    assert "emit x:X and y:Y" in text


def test_model_request_skips_invalid_screenshot_payload(tmp_path: Path) -> None:
    screenshot = tmp_path / "state.jpg"
    screenshot.write_text("screenshot capture failed", encoding="utf-8")

    request = build_model_turn_request(
        goal="Open Settings",
        model="gui-model",
        state={
            "package_name": "browser",
            "activity_name": "Main",
            "display": {"width": 1080, "height": 2400},
            "xml": "<hierarchy />",
            "screenshot_path": str(screenshot),
        },
        max_steps=3,
        turn_index=1,
    )

    assert [item["type"] for item in request["messages"][1]["content"]] == ["text"]


def test_model_request_uses_top_50_relevant_ui_nodes_without_screenshot(
    tmp_path: Path,
) -> None:
    current = tmp_path / "current.jpg"
    previous = tmp_path / "previous.jpg"
    current.write_bytes(b"\xff\xd8\xffcurrent")
    previous.write_bytes(b"\xff\xd8\xffprevious")
    nodes = "".join(
        f'<node id="{index}" text="项目{index}" clickable="true" '
        f'bounds="[0,{index}][100,{index + 1}]" />'
        for index in range(75)
    )
    nodes += (
        '<node id="route" text="路线搜索" '
        'resource-id="com.baidu:id/frame_btn_route" clickable="true" '
        'bounds="[40,80][200,160]" />'
    )

    request = build_model_turn_request(
        goal="搜索西湖路线",
        model="gui-model",
        state={
            "package_name": "com.baidu.BaiduMap",
            "activity_name": "Main",
            "display": {"width": 1080, "height": 2400},
            "xml": f"<hierarchy>{nodes}</hierarchy>",
            "screenshot_path": str(current),
        },
        max_steps=12,
        turn_index=1,
        previous_screenshot_path=str(previous),
    )

    content = request["messages"][1]["content"]
    assert [item["type"] for item in content] == ["text"]
    text = content[0]["text"]
    assert "Relevant UI elements (1-50)" in text
    assert '"t":"路线搜索"' in text
    assert '"r":"com.baidu:id/frame_btn_route"' in text
    assert "<hierarchy" not in text
    assert len([line for line in text.splitlines() if line.startswith("{")]) <= 50


def test_model_request_sends_only_current_screenshot_for_visual_ad_goal(
    tmp_path: Path,
) -> None:
    current = tmp_path / "current.jpg"
    previous = tmp_path / "previous.jpg"
    current.write_bytes(b"\xff\xd8\xffcurrent")
    previous.write_bytes(b"\xff\xd8\xffprevious")

    request = build_model_turn_request(
        goal="关闭地图广告",
        model="gui-model",
        state={
            "package_name": "com.baidu.BaiduMap",
            "activity_name": "Main",
            "display": {"width": 1080, "height": 2400},
            "xml": (
                '<hierarchy><node text="广告" clickable="true" '
                'bounds="[0,0][100,100]" /></hierarchy>'
            ),
            "screenshot_path": str(current),
        },
        max_steps=12,
        turn_index=1,
        previous_screenshot_path=str(previous),
    )

    content = request["messages"][1]["content"]
    assert [item["type"] for item in content] == ["text", "image_url"]
    assert content[1]["image_url"]["url"].endswith("Y3VycmVudA==")


def test_dataset_covers_every_model_visible_tool_exactly_once() -> None:
    visible_tools = set(_visible_tool_specs())
    dataset_tools = [case["tool"] for case in _VALID_TOOL_CALLS]

    assert set(dataset_tools) == visible_tools
    assert len(dataset_tools) == len(visible_tools)
    assert _DATASET["schema_version"] == "oob.vlm_tool_call_cases.v1"


@pytest.mark.parametrize(
    "case",
    _VLM_TOOL_CALLS,
    ids=[case["id"] for case in _VLM_TOOL_CALLS],
)
def test_valid_tool_call_dataset_binds_every_vlm_action(case: dict) -> None:
    action, metadata = parse_model_turn_response(
        _response(case["tool"], case["arguments"]),
        requested_model="gui-model",
        turn_index=2,
    )

    assert action == {
        "tool": case["tool"],
        "args": {
            key: value for key, value in case["arguments"].items() if key != "summary"
        },
    }
    assert metadata["summary"] == case["arguments"]["summary"]


@pytest.mark.parametrize(
    "case",
    [case for case in _INVALID_TOOL_CALLS if case["tool"] in VLM_ACTION_TOOL_NAMES],
    ids=[
        case["id"]
        for case in _INVALID_TOOL_CALLS
        if case["tool"] in VLM_ACTION_TOOL_NAMES
    ],
)
def test_invalid_tool_call_dataset_is_rejected_without_dialect_fallback(
    case: dict,
) -> None:
    with pytest.raises(ValueError, match=re.escape(case["error"])):
        parse_model_turn_response(
            _response(case["tool"], case["arguments"]),
            requested_model="gui-model",
            turn_index=2,
        )


@pytest.mark.parametrize(
    "case",
    [case for case in _ADAPTED_TOOL_CALLS if case["tool"] in VLM_ACTION_TOOL_NAMES],
    ids=[
        case["id"]
        for case in _ADAPTED_TOOL_CALLS
        if case["tool"] in VLM_ACTION_TOOL_NAMES
    ],
)
def test_qwen_vl_coordinate_dialect_is_adapted_before_canonical_validation(
    case: dict,
) -> None:
    action, metadata = parse_model_turn_response(
        _response(
            case["tool"],
            case["arguments"],
            requested_model=case["requested_model"],
            resolved_model=case["resolved_model"],
        ),
        requested_model=case["requested_model"],
        turn_index=2,
    )

    assert action == {"tool": case["tool"], "args": case["expected_args"]}
    assert metadata["model_adapter"] == {
        "name": "qwen_vl_coordinate_arrays.v1",
        "model": case["adapter_model"],
        "tool": case["tool"],
        "changes": case["expected_changes"],
    }


def test_qwen_vl_absolute_pixel_point_is_repaired_then_converted_at_boundary() -> None:
    action, metadata = parse_model_turn_response(
        _response(
            "click",
            {
                "summary": "点击路线按钮",
                "x": [126, 2649],
                "y": [2673],
            },
            requested_model="scene.vlm.operation.primary",
            resolved_model="Qwen3-VL-235B-A22B-Instruct",
        ),
        requested_model="scene.vlm.operation.primary",
        turn_index=1,
        display={"width": 1260, "height": 2800},
    )

    assert action["tool"] == "click"
    assert action["args"]["x"] == 100
    assert action["args"]["y"] == pytest.approx(946.071429)
    assert metadata["model_adapter"]["changes"][-1]["source_shape"] == (
        "pixel_point_with_trailing_y"
    )
    assert metadata["coordinate_conversion"]["name"] == (
        "screen_pixels_to_relative_0_1000.v1"
    )


@pytest.mark.parametrize(
    "case",
    [
        case
        for case in _ADAPTER_REJECTION_CASES
        if case["tool"] in VLM_ACTION_TOOL_NAMES
    ],
    ids=[
        case["id"]
        for case in _ADAPTER_REJECTION_CASES
        if case["tool"] in VLM_ACTION_TOOL_NAMES
    ],
)
def test_model_adapter_rejects_unapproved_or_ambiguous_dialects(case: dict) -> None:
    with pytest.raises(ValueError, match=re.escape(case["error"])):
        parse_model_turn_response(
            _response(
                case["tool"],
                case["arguments"],
                requested_model=case["requested_model"],
                resolved_model=case["resolved_model"],
            ),
            requested_model=case["requested_model"],
            turn_index=2,
        )


@pytest.mark.parametrize(
    ("tool", "arguments", "error"),
    _invalid_schema_cases(),
)
def test_every_schema_constraint_rejects_invalid_model_arguments(
    tool: str,
    arguments: dict,
    error: str,
) -> None:
    with pytest.raises(ValueError, match=re.escape(error)):
        parse_model_turn_response(
            _response(tool, arguments),
            requested_model="gui-model",
            turn_index=2,
        )


@pytest.mark.parametrize(
    ("tool", "arguments"),
    _valid_boundary_cases(),
)
def test_every_enum_and_numeric_boundary_binds_successfully(
    tool: str,
    arguments: dict,
) -> None:
    action, _metadata = parse_model_turn_response(
        _response(tool, arguments),
        requested_model="gui-model",
        turn_index=2,
    )

    assert action["tool"] == tool
    assert action["args"] == {
        key: value for key, value in arguments.items() if key != "summary"
    }


def test_model_response_binds_schema_arguments_and_extracts_metadata() -> None:
    action, metadata = parse_model_turn_response(
        _response(
            "click",
            {
                "summary": "点击搜索",
                "x": 500,
                "y": 300,
                "target_description": "Search",
            },
        ),
        requested_model="gui-model",
        turn_index=2,
    )

    assert action == {
        "tool": "click",
        "args": {"x": 500, "y": 300, "target_description": "Search"},
    }
    assert metadata["summary"] == "点击搜索"
    assert metadata["thinking"] == "search is visible"
    assert metadata["token_usage"]["resolved_model"] == "resolved-gui-model"
    assert metadata["token_usage"]["turn_index"] == 2


def test_model_response_repairs_truncated_qwen_json_before_validation() -> None:
    action, metadata = parse_model_turn_response(
        _raw_response(
            "click",
            '{"summary":"点击始终打开","x":[637,908],"y":[908}',
            requested_model="scene.vlm.operation.primary",
            resolved_model="Qwen3-VL-235B-A22B-Instruct",
        ),
        requested_model="scene.vlm.operation.primary",
        turn_index=2,
    )

    assert action == {"tool": "click", "args": {"x": 637, "y": 908}}
    assert metadata["json_repair"] == {
        "name": "json_repair",
        "applied": True,
    }
    assert metadata["model_adapter"]["name"] == "qwen_vl_coordinate_arrays.v1"


def test_repaired_json_still_must_fit_current_display() -> None:
    with pytest.raises(ValueError, match="canonical_action_arg_range_invalid:y"):
        parse_model_turn_response(
            _raw_response(
                "click",
                '{"summary":"点击始终打开","x":630,"y":2124',
            ),
            requested_model="gui-model",
            turn_index=2,
            display={"width": 1080, "height": 1920},
        )


def test_model_response_does_not_repair_truncated_string_content() -> None:
    with pytest.raises(ValueError, match="model_turn_tool_arguments_must_be_json"):
        parse_model_turn_response(
            _raw_response(
                "input_text",
                '{"summary":"输入笔记","text":"unfinished}',
            ),
            requested_model="gui-model",
            turn_index=2,
        )


def test_model_response_rejects_non_schema_coordinate_dialect() -> None:
    with pytest.raises(ValueError, match="canonical_action_arg_type_invalid:x"):
        parse_model_turn_response(
            _response(
                "click",
                {
                    "summary": "点击搜索",
                    "x": [500, 300],
                    "target_description": "Search",
                },
            ),
            requested_model="gui-model",
            turn_index=2,
        )


def test_model_response_rejects_tools_hidden_from_vlm() -> None:
    with pytest.raises(ValueError, match="model_turn_tool_not_visible:wait"):
        parse_model_turn_response(
            _response("wait", {"summary": "等待", "duration_ms": 1000}),
            requested_model="gui-model",
            turn_index=2,
        )


@pytest.mark.parametrize(
    "tool",
    sorted(set(_visible_tool_specs()) - set(VLM_ACTION_TOOL_NAMES)),
)
def test_model_response_rejects_canonical_tools_not_in_compact_vlm_set(
    tool: str,
) -> None:
    case = next(item for item in _VALID_TOOL_CALLS if item["tool"] == tool)
    with pytest.raises(ValueError, match=f"model_turn_tool_not_visible:{tool}"):
        parse_model_turn_response(
            _response(tool, case["arguments"]),
            requested_model="gui-model",
            turn_index=2,
        )


def test_model_response_allows_coordinate_action_without_grounding_description() -> (
    None
):
    action, metadata = parse_model_turn_response(
        _response(
            "click",
            {"summary": "点击搜索", "x": 500, "y": 300},
        ),
        requested_model="gui-model",
        turn_index=3,
    )

    assert action == {"tool": "click", "args": {"x": 500, "y": 300}}
    assert metadata["summary"] == "点击搜索"


def test_model_response_rejects_missing_or_multiple_native_calls() -> None:
    for tool_calls in (
        [],
        [_response("wait", {"summary": "等待", "duration_ms": 1})["tool_calls"][0]] * 2,
    ):
        response = _response("wait", {"summary": "等待", "duration_ms": 1})
        response["tool_calls"] = tool_calls
        with pytest.raises(ValueError, match="provider_tool_call_contract_violation"):
            parse_model_turn_response(
                response,
                requested_model="gui-model",
                turn_index=1,
            )


def test_bridge_planner_retries_rejected_tool_call_with_only_its_canonical_schema() -> (
    None
):
    class FakeBridge:
        def __init__(self) -> None:
            self.responses = [
                _response("click", {"summary": "点击搜索", "x": [500, 300]}),
                _response("click", {"summary": "点击搜索", "x": 500, "y": 300}),
            ]
            self.requests: list[dict] = []

        def host_call(
            self,
            _request_id: str,
            method: str,
            payload: dict,
        ) -> dict:
            assert method == "model_turn"
            self.requests.append(payload["request"])
            return self.responses.pop(0)

    bridge = FakeBridge()
    host = type("Host", (), {"current_action_metadata": {}})()
    planner = _BridgePlanner(
        bridge,
        "request-1",
        host,
        model="gui-model",
        target_package_name="browser",
        step_skill_guidance="",
        installed_apps={"Browser": "browser"},
        max_steps=12,
    )

    action = planner.one_step_action(
        "search for tea",
        Observation(
            xml="<hierarchy />",
            package_name="browser",
            activity_name="Main",
            extra={
                "state_id": "state-1",
                "display": {"width": 1000, "height": 1000},
            },
        ),
    )

    assert action.to_dict() == {"tool": "click", "args": {"x": 500, "y": 300}}
    assert len(bridge.requests) == 2
    retry_tools = bridge.requests[1]["tools"]
    assert [tool["function"]["name"] for tool in retry_tools] == ["click"]
    assert retry_tools[0]["function"]["strict"] is True
    assert bridge.requests[1]["tool_choice"] == "required"
    assert bridge.requests[1]["parallel_tool_calls"] is False
    correction_text = bridge.requests[1]["messages"][1]["content"][0]["text"]
    assert "canonical_action_arg_type_invalid:x" in correction_text
    assert "Coordinate fields such as x and y must each be one raw-pixel" in correction_text


def test_bridge_planner_accepts_qwen_adapter_without_retrying_or_forking_execution() -> (
    None
):
    class FakeBridge:
        def __init__(self) -> None:
            self.requests: list[dict] = []

        def host_call(
            self,
            _request_id: str,
            method: str,
            payload: dict,
        ) -> dict:
            assert method == "model_turn"
            self.requests.append(payload["request"])
            return _response(
                "click",
                {"summary": "点击搜索", "x": [500, 300]},
                requested_model="scene.vlm.operation.primary",
                resolved_model="Qwen3-VL-235B-A22B-Instruct",
            )

    bridge = FakeBridge()
    host = type("Host", (), {"current_action_metadata": {}})()
    planner = _BridgePlanner(
        bridge,
        "request-1",
        host,
        model="scene.vlm.operation.primary",
        target_package_name="browser",
        step_skill_guidance="",
        installed_apps={"Browser": "browser"},
        max_steps=12,
    )

    action = planner.one_step_action(
        "search for tea",
        Observation(
            xml="<hierarchy />",
            package_name="browser",
            activity_name="Main",
            extra={
                "state_id": "state-1",
                "display": {"width": 1000, "height": 1000},
            },
        ),
    )

    assert action.to_dict() == {"tool": "click", "args": {"x": 500, "y": 300}}
    assert len(bridge.requests) == 1
    metadata = planner.take_metadata()
    assert metadata["model_adapter"]["name"] == "qwen_vl_coordinate_arrays.v1"
    assert "rejected_tool_calls" not in metadata


def test_bridge_planner_uses_lightweight_request_after_missing_tool_call() -> (
    None
):
    class FakeBridge:
        def __init__(self) -> None:
            self.requests: list[dict] = []
            missing = _response(
                "click",
                {"summary": "点击搜索", "x": 500, "y": 300},
            )
            missing["tool_calls"] = []
            self.responses = [
                missing,
                _response(
                    "click",
                    {"summary": "点击搜索", "x": 500, "y": 300},
                ),
            ]

        def host_call(
            self,
            _request_id: str,
            method: str,
            payload: dict,
        ) -> dict:
            assert method == "model_turn"
            self.requests.append(payload["request"])
            return self.responses.pop(0)

    bridge = FakeBridge()
    host = type("Host", (), {"current_action_metadata": {}})()
    planner = _BridgePlanner(
        bridge,
        "request-1",
        host,
        model="gui-model",
        target_package_name="browser",
        step_skill_guidance="Prefer visible targets.",
        installed_apps={"Browser": "browser"},
        max_steps=12,
    )

    action = planner.one_step_action(
        "search for tea",
        Observation(
            xml='<hierarchy text="search" />',
            package_name="browser",
            activity_name="Main",
            image_base64="/9j/2Q==",
            extra={
                "state_id": "state-1",
                "display": {"width": 1000, "height": 1000},
                "recent_actions": [{"tool": "open_app", "success": True}],
            },
        ),
    )

    assert action.to_dict() == {"tool": "click", "args": {"x": 500, "y": 300}}
    assert len(bridge.requests) == 2
    assert [
        item["type"] for item in bridge.requests[0]["messages"][1]["content"]
    ] == ["text"]
    retry_content = bridge.requests[1]["messages"][1]["content"]
    assert [item["type"] for item in retry_content] == ["text"]
    retry_text = retry_content[0]["text"]
    assert "Goal: search for tea" in retry_text
    assert "expected_one_native_tool_call:got_0" in retry_text
    assert "Installed apps" not in retry_text
    assert "Accessibility XML" not in retry_text
    assert "<hierarchy" not in retry_text
    assert "Recent execution context" not in retry_text


def test_bridge_planner_retries_missing_tool_call_only_once() -> None:
    class FakeBridge:
        def __init__(self) -> None:
            self.requests: list[dict] = []

        def host_call(
            self,
            _request_id: str,
            method: str,
            payload: dict,
        ) -> dict:
            assert method == "model_turn"
            self.requests.append(payload["request"])
            response = _response(
                "click",
                {"summary": "点击搜索", "x": 500, "y": 300},
            )
            response["tool_calls"] = []
            return response

    bridge = FakeBridge()
    host = type("Host", (), {"current_action_metadata": {}})()
    planner = _BridgePlanner(
        bridge,
        "request-1",
        host,
        model="gui-model",
        target_package_name="browser",
        step_skill_guidance="",
        installed_apps={"Browser": "browser"},
        max_steps=12,
    )

    with pytest.raises(
        ValueError,
        match="provider_tool_call_contract_violation:expected_one_native_tool_call:got_0",
    ):
        planner.one_step_action(
            "search for tea",
            Observation(
                xml='<hierarchy text="search" />',
                package_name="browser",
                activity_name="Main",
                extra={
                    "state_id": "state-1",
                    "display": {"width": 1000, "height": 1000},
                },
            ),
        )

    assert len(bridge.requests) == 2
    assert planner.take_metadata()["rejected_tool_calls"] == [
        {
            "turn_index": turn_index,
            "tool": None,
            "error": "provider_tool_call_contract_violation:expected_one_native_tool_call:got_0",
        }
        for turn_index in (1, 2)
    ]


@pytest.mark.parametrize(
    "case",
    [
        case
        for case in _INVALID_TOOL_CALLS
        if case["tool"] in VLM_ACTION_TOOL_NAMES
    ],
    ids=[
        case["id"]
        for case in _INVALID_TOOL_CALLS
        if case["tool"] in VLM_ACTION_TOOL_NAMES
    ],
)
def test_rejected_argument_dataset_reselects_with_one_canonical_tool(
    case: dict,
) -> None:
    valid_case = next(
        seed for seed in _VLM_TOOL_CALLS if seed["tool"] == case["tool"]
    )

    class FakeBridge:
        def __init__(self) -> None:
            self.responses = [
                _response(case["tool"], case["arguments"]),
                _response(valid_case["tool"], valid_case["arguments"]),
            ]
            self.requests: list[dict] = []

        def host_call(
            self,
            _request_id: str,
            method: str,
            payload: dict,
        ) -> dict:
            assert method == "model_turn"
            self.requests.append(payload["request"])
            return self.responses.pop(0)

    bridge = FakeBridge()
    host = type("Host", (), {"current_action_metadata": {}})()
    planner = _BridgePlanner(
        bridge,
        "request-1",
        host,
        model="gui-model",
        target_package_name="browser",
        step_skill_guidance="",
        installed_apps={"Browser": "browser"},
        max_steps=12,
    )

    action = planner.one_step_action(
        "reselect from historical state",
        Observation(
            xml="<hierarchy />",
            package_name="browser",
            activity_name="Main",
            extra={
                "state_id": "historical-state-1",
                "display": {"width": 1000, "height": 1000},
            },
        ),
    )

    assert action.to_dict() == {
        "tool": valid_case["tool"],
        "args": {
            key: value
            for key, value in valid_case["arguments"].items()
            if key != "summary"
        },
    }
    retry_tools = bridge.requests[1]["tools"]
    assert [tool["function"]["name"] for tool in retry_tools] == [case["tool"]]
    assert retry_tools[0]["function"]["strict"] is True


def test_bridge_planner_stops_after_one_invalid_tool_call_correction() -> None:
    class FakeBridge:
        def __init__(self) -> None:
            self.requests: list[dict] = []

        def host_call(
            self,
            _request_id: str,
            method: str,
            payload: dict,
        ) -> dict:
            assert method == "model_turn"
            self.requests.append(payload["request"])
            return _response(
                "click",
                {"summary": "点击搜索", "x": [500, 300]},
            )

    bridge = FakeBridge()
    host = type("Host", (), {"current_action_metadata": {}})()
    planner = _BridgePlanner(
        bridge,
        "request-1",
        host,
        model="gui-model",
        target_package_name="browser",
        step_skill_guidance="",
        installed_apps={"Browser": "browser"},
        max_steps=12,
    )

    with pytest.raises(
        ValueError,
        match="canonical_action_arg_type_invalid:x",
    ):
        planner.one_step_action(
            "search for tea",
            Observation(
                xml="<hierarchy />",
                package_name="browser",
                activity_name="Main",
                extra={
                    "state_id": "state-1",
                    "display": {"width": 1000, "height": 1000},
                },
            ),
        )

    assert planner.take_metadata()["rejected_tool_calls"] == [
        {
            "turn_index": turn_index,
            "tool": "click",
            "error": "canonical_action_arg_type_invalid:x",
            "arguments": {"summary": "点击搜索", "x": [500, 300]},
        }
        for turn_index in (1, 2)
    ]
    assert len(bridge.requests) == 2
    assert len(bridge.requests[0]["tools"]) > 1
    assert all(
        [tool["function"]["name"] for tool in request["tools"]] == ["click"]
        for request in bridge.requests[1:]
    )
    assert all(
        "wait" not in {tool["function"]["name"] for tool in request["tools"]}
        for request in bridge.requests
    )
    for request in bridge.requests[1:]:
        correction_text = request["messages"][1]["content"][0]["text"]
        assert "canonical_action_arg_type_invalid:x" in correction_text
        assert '"x":[500,300]' in correction_text
        assert 'Invalid array shape: {"x":[500]' in correction_text
