from __future__ import annotations

from dataclasses import replace
import hashlib
import inspect
import json
import math
import re
from typing import Any
import xml.etree.ElementTree as ET

from omniflow.config import PluginSet
from omniflow.model import (
    Action,
    ActionDecision,
    ActionResult,
    CheckResult,
    Function,
    Host,
    Observation,
    RunResult,
    StepResult,
    TransferResult,
)
from omniflow.transfer import transfer_action


async def execute_function(
    function: Function,
    *,
    host: Host,
    plugins: PluginSet,
    observation: Observation | None = None,
    max_actions: int | None = None,
) -> RunResult:
    current = observation or Observation.from_value(
        await _await(host.observe(xml=True, app_info=True))
    )
    if max_actions is not None and len(function.actions) > max_actions:
        return RunResult(
            False,
            function.id,
            0,
            error="function_exceeds_action_budget",
            final_state=current,
            detail={
                "trace": [],
                "required_actions": len(function.actions),
                "max_actions": max_actions,
            },
        )
    executed = 0
    trace: list[dict[str, Any]] = []
    for function_step in function.steps:
        action = function_step.action
        if max_actions is not None and executed >= max_actions:
            return RunResult(
                False,
                function.id,
                executed,
                error="max_steps_exceeded",
                final_state=current,
                detail={"trace": trace},
            )
        source_state = await _load_state(host, function_step.source_state_id)
        step = await execute_action(
            action,
            observation=current,
            host=host,
            plugins=plugins,
            function=function,
            source_state=source_state,
        )
        executed += step.actions_executed
        trace.append(trace_step(step, len(trace)))
        current = step.after or step.before or current
        if not step.success:
            return RunResult(
                False,
                function.id,
                executed,
                error=step.error,
                final_state=current,
                detail={"trace": trace},
            )
    return RunResult(
        True,
        function.id,
        executed,
        final_state=current,
        detail={"trace": trace},
    )


async def execute_action(
    action: Action,
    *,
    observation: Observation,
    host: Host,
    plugins: PluginSet,
    function: Function | None = None,
    source_state: Observation | None = None,
) -> StepResult:
    decision = await prepare_action(
        action,
        observation=observation,
        plugins=plugins,
        function=function,
        source_state=source_state,
    )
    if decision.kind == "block" or decision.action is None:
        return StepResult(
            False,
            action=action,
            before=observation,
            error=decision.reason or "action_blocked",
            origin="blocked",
            function_id=function.id if function is not None else None,
            detail=decision.detail,
        )
    result = await _dispatch_prepared(
        decision.action,
        observation=observation,
        host=host,
    )
    return replace(
        result,
        function_id=function.id if function is not None else None,
    )


async def prepare_action(
    action: Action,
    *,
    observation: Observation,
    plugins: PluginSet,
    function: Function | None = None,
    source_state: Observation | None = None,
) -> ActionDecision:
    candidate = action
    checker = plugins.checker
    if checker is None:
        return ActionDecision("block", reason="checker_not_configured")
    check = await _await(checker(function, action, observation))
    if not check.allowed:
        return ActionDecision(
            "block",
            reason=check.reason or "checker_blocked",
        )
    if function is None and source_state is None:
        return ActionDecision("ready", action=candidate)
    transfer_fn = plugins.transfer
    if transfer_fn is None:
        return ActionDecision("block", reason="transfer_not_configured")
    transfer = await _await(transfer_fn(candidate, observation, source_state))
    if transfer.action is None:
        return ActionDecision(
            "block",
            reason=transfer.reason or "transfer_failed",
            detail=transfer.detail,
        )
    return ActionDecision(
        "ready",
        action=transfer.action,
        reason=transfer.reason,
    )


async def _dispatch_prepared(
    action: Action,
    *,
    observation: Observation,
    host: Host,
) -> StepResult:
    action_result = ActionResult.from_value(await _await(host.act(action)))
    if not action_result.success:
        return StepResult(
            False,
            action=action,
            before=observation,
            result=action_result,
            error=action_result.error or "action_failed",
        )
    after = Observation.from_value(await _await(host.observe(xml=True, app_info=True)))
    return StepResult(
        True,
        action=action,
        before=observation,
        after=after,
        result=action_result,
        actions_executed=1,
    )


def trace_step(step: StepResult, step_index: int) -> dict[str, Any]:
    action = step.action or Action("")
    before = _state(step.before or Observation())
    after = _state(step.after or step.before or Observation())
    diagnostics: dict[str, Any] = {"origin": step.origin}
    if step.function_id:
        diagnostics["function_id"] = step.function_id
    action_result = step.result or ActionResult(step.success, step.error)
    if action_result.extra:
        diagnostics["action_result"] = dict(action_result.extra)
    if step.detail:
        diagnostics["transfer"] = dict(step.detail)
    result: dict[str, Any] = {"success": step.success}
    if step.error:
        result["error"] = step.error
    return {
        "step_index": step_index,
        "before_state_id": before["state_id"],
        "action": action.to_dict(),
        "result": result,
        "after_state_id": after["state_id"],
        "diagnostics": diagnostics,
    }


def _state(value: Observation) -> dict[str, Any]:
    state = {
        key: item
        for key, item in value.to_dict().items()
        if key in {"xml", "package_name", "activity_name"}
        and item not in {None, ""}
    }
    state.update(
        {
            key: item
            for key, item in value.extra.items()
            if key in {"display", "screenshot_path"}
            and item is not None
            and item != ""
        }
    )
    identity = json.dumps(state, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return {
        "state_id": "state_" + hashlib.sha256(identity.encode()).hexdigest()[:20],
        **state,
    }


def default_checker(
    function: Function | None,
    action: Action,
    observation: Observation,
) -> CheckResult:
    if function is None:
        return CheckResult()
    rules = sorted(
        (
            canonical_checker_rule(rule)
            for rule in function.checker_rules
            if isinstance(rule, dict)
        ),
        key=lambda rule: int(rule.get("priority") or 0),
        reverse=True,
    )
    for rule in rules:
        if rule.get("enabled") is False or not checker_rule_matches(
            rule,
            function=function,
            action=action,
            observation=observation,
        ):
            continue
        rule_id = str(rule.get("id") or "checker")
        return CheckResult(False, reason=rule_id)
    return CheckResult()


def checker_rule_matches(
    rule: dict[str, Any],
    *,
    function: Function,
    action: Action,
    observation: Observation,
) -> bool:
    del function
    rule = canonical_checker_rule(rule)
    scope = rule.get("scope") if isinstance(rule.get("scope"), dict) else {}
    if not _scope_contains(scope.get("action_types"), action.tool):
        return False
    if not _scope_contains(scope.get("package_names"), observation.package_name or ""):
        return False

    condition = dict(rule.get("condition") or {})
    package = str(observation.package_name or "")
    xml_text = str(observation.xml or "").lower()
    elements = _elements(str(observation.xml or ""))
    checks = [
        _field_matches(condition.get("xml_contains_any"), [xml_text], contains=True),
        _field_matches(condition.get("text_any"), [item["text"] for item in elements]),
        _field_matches(
            condition.get("text_contains_any"),
            [item["text"] for item in elements],
            contains=True,
        ),
        _field_matches(
            condition.get("content_desc_any"),
            [item["description"] for item in elements],
        ),
        _field_matches(
            condition.get("content_desc_contains_any"),
            [item["description"] for item in elements],
            contains=True,
        ),
        _field_matches(
            condition.get("resource_id_any"), [item["resource_id"] for item in elements]
        ),
        _field_matches(
            condition.get("resource_id_contains_any"),
            [item["resource_id"] for item in elements],
            contains=True,
        ),
        _field_matches(condition.get("package_any"), [package]),
    ]
    package_not = _strings(condition.get("package_not"))
    if package_not:
        checks.append(package.lower() not in package_not)
    specified = [matched for matched in checks if matched is not None]
    return bool(specified) and all(specified)


def canonical_checker_rule(value: dict[str, Any]) -> dict[str, Any]:
    return {
        "schema_version": "omniflow.checker_rule.v1",
        "id": str(value.get("id") or "checker").strip() or "checker",
        "enabled": value.get("enabled") is not False,
        "scope": dict(value.get("scope") or {}),
        "condition": dict(value.get("condition") or {}),
        "priority": int(value.get("priority") or 0),
    }


def _scope_contains(value: Any, current: str) -> bool:
    allowed = _strings(value)
    return not allowed or current in allowed


def _field_matches(
    value: Any, candidates: list[str], *, contains: bool = False
) -> bool | None:
    expected = _strings(value)
    if not expected:
        return None
    normalized = [item.lower() for item in candidates]
    if contains:
        return any(
            wanted in candidate for wanted in expected for candidate in normalized
        )
    return any(wanted == candidate for wanted in expected for candidate in normalized)


def _strings(value: Any) -> list[str]:
    raw = value if isinstance(value, list) else [value]
    return [str(item).strip().lower() for item in raw if str(item or "").strip()]


def default_transfer(
    action: Action,
    observation: Observation,
    source_state: Observation | None = None,
) -> TransferResult:
    if action.tool == "input_text" and not all(
        action.args.get(key) is not None for key in ("x", "y")
    ):
        return TransferResult(action)
    if action.tool == "swipe" and all(
        action.args.get(key) is not None
        for key in ("x1", "y1", "x2", "y2")
    ):
        return _transfer_swipe(action, observation, source_state)
    if action.tool not in {"click", "input_text", "long_press"}:
        return TransferResult(action)
    if not all(action.args.get(key) is not None for key in ("x", "y")):
        return TransferResult(None, reason="omnitransfer_invalid_source_point")
    target_xml = str(observation.xml or "")
    if not target_xml:
        return TransferResult(None, reason="omnitransfer_missing_target_page")
    elements = _elements(target_xml)
    display_size = _display_size(observation, elements)
    if display_size is None:
        return TransferResult(None, reason=_display_size_error(observation, elements))
    source_xml = str(source_state.xml or "") if source_state is not None else ""
    if not source_xml:
        return TransferResult(None, reason="omnitransfer_source_state_missing")
    request: dict[str, Any] = {
        "source_xml": source_xml,
        "target_xml": target_xml,
        "source_package_name": source_state.package_name,
        "target_package_name": observation.package_name,
        "source_activity_name": source_state.activity_name,
        "target_activity_name": observation.activity_name,
        "action_type": action.tool,
        "top_k": 3,
    }
    try:
        request["source_point"] = _relative_source_point(
            source_xml,
            float(action.args["x"]),
            float(action.args["y"]),
        )
    except (KeyError, TypeError, ValueError):
        return TransferResult(None, reason="omnitransfer_invalid_source_point")
    try:
        result = transfer_action(**request)
    except Exception as exc:
        return TransferResult(None, reason=f"omnitransfer_error:{exc}")
    if result.get("mapped") is not True:
        reason = result.get("reason") or result.get("mapping_mode") or "failed"
        return TransferResult(
            None,
            reason=f"omnitransfer_{reason}",
            detail=_transfer_detail(result),
        )
    try:
        target_x = float(result["new_x"])
        target_y = float(result["new_y"])
    except (KeyError, TypeError, ValueError):
        return TransferResult(None, reason="omnitransfer_invalid_target")
    if not math.isfinite(target_x) or not math.isfinite(target_y):
        return TransferResult(None, reason="omnitransfer_invalid_target")
    width, height = display_size
    params = dict(action.args)
    params.pop("node_id", None)
    params.pop("node_resource_id", None)
    params["x"] = target_x / width * 1000.0
    params["y"] = target_y / height * 1000.0
    return TransferResult(
        Action(action.tool, params),
        reason=str(result.get("mapping_mode") or "omnitransfer_mapped"),
    )


def _transfer_swipe(
    action: Action,
    observation: Observation,
    source_state: Observation | None,
) -> TransferResult:
    target_xml = str(observation.xml or "")
    if not target_xml:
        return TransferResult(None, reason="omnitransfer_missing_target_page")
    source_xml = str(source_state.xml or "") if source_state is not None else ""
    if not source_xml:
        return TransferResult(None, reason="omnitransfer_source_state_missing")
    elements = _elements(target_xml)
    display_size = _display_size(observation, elements)
    if display_size is None:
        return TransferResult(None, reason=_display_size_error(observation, elements))
    width, height = display_size
    params = dict(action.args)
    mapping_modes: list[str] = []
    for index, (x_key, y_key) in enumerate(
        (("x1", "y1"), ("x2", "y2"))
    ):
        try:
            source_point = _relative_source_point(
                source_xml,
                float(params[x_key]),
                float(params[y_key]),
            )
        except (KeyError, TypeError, ValueError):
            return TransferResult(None, reason="omnitransfer_invalid_source_point")
        try:
            request: dict[str, Any] = {
                "target_xml": target_xml,
                "source_xml": source_xml,
                "source_package_name": source_state.package_name,
                "target_package_name": observation.package_name,
                "source_activity_name": source_state.activity_name,
                "target_activity_name": observation.activity_name,
                "action_type": action.tool,
                "top_k": 3,
            }
            request["source_point"] = source_point
            result = transfer_action(
                **request,
            )
        except Exception as exc:
            return TransferResult(None, reason=f"omnitransfer_error:{exc}")
        if result.get("mapped") is not True:
            reason = result.get("reason") or result.get("mapping_mode") or "failed"
            return TransferResult(
                None,
                reason=f"omnitransfer_{reason}",
                detail=_transfer_detail(result),
            )
        try:
            target_x = float(result["new_x"])
            target_y = float(result["new_y"])
        except (KeyError, TypeError, ValueError):
            return TransferResult(None, reason="omnitransfer_invalid_target")
        if not math.isfinite(target_x) or not math.isfinite(target_y):
            return TransferResult(None, reason="omnitransfer_invalid_target")
        params[x_key] = target_x / width * 1000.0
        params[y_key] = target_y / height * 1000.0
        mapping_modes.append(str(result.get("mapping_mode") or "omnitransfer_mapped"))
    reason = mapping_modes[0] if len(set(mapping_modes)) == 1 else "omnitransfer_mapped"
    return TransferResult(Action(action.tool, params), reason=reason)


def _transfer_detail(result: dict[str, Any]) -> dict[str, Any]:
    source = _element_detail(result.get("src_element"))
    source_display = _display_detail(result.get("source_size"))
    if source_display:
        source["display"] = source_display
    target: dict[str, Any] = {}
    target_display = _display_detail(result.get("target_size"))
    if target_display:
        target["display"] = target_display
    candidates = []
    for rank, raw in enumerate(result.get("top_candidates") or (), start=1):
        if not isinstance(raw, dict):
            continue
        candidate = _element_detail(raw)
        candidate["rank"] = rank
        candidate["bounds"] = list(raw.get("bbox") or ())
        candidate["score"] = raw.get("score")
        candidates.append(candidate)
    return {
        "mapping_mode": str(result.get("mapping_mode") or ""),
        "source": source,
        "target": target,
        "candidates": candidates,
    }


def _element_detail(value: Any) -> dict[str, Any]:
    raw = value if isinstance(value, dict) else {}
    return {
        key: raw[key]
        for key in (
            "resource_id",
            "text",
            "content_desc",
            "class",
            "bounds",
        )
        if raw.get(key) not in (None, "", [])
    }


def _display_detail(value: Any) -> dict[str, float]:
    if not isinstance(value, (list, tuple)) or len(value) != 2:
        return {}
    try:
        width, height = float(value[0]), float(value[1])
    except (TypeError, ValueError):
        return {}
    if width <= 0 or height <= 0:
        return {}
    return {"width": width, "height": height}


async def _load_state(host: Host, source_state_id: str | None) -> Observation | None:
    if not source_state_id:
        return None
    loader = getattr(host, "get_state", None)
    if not callable(loader):
        return None
    return Observation.from_value(await _await(loader(source_state_id)))


def _relative_source_point(xml_text: str, x: float, y: float) -> tuple[float, float]:
    display = _xml_size(_elements(xml_text))
    if display is None:
        raise ValueError("source_display_size_missing")
    return x / 1000.0 * display[0], y / 1000.0 * display[1]

async def _await(value: Any) -> Any:
    return await value if inspect.isawaitable(value) else value


def _elements(xml_text: str) -> list[dict[str, Any]]:
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError:
        return []
    elements: list[dict[str, Any]] = []
    for element in root.iter():
        bounds = _bounds(element.attrib.get("bounds"))
        if bounds is None:
            continue
        elements.append(
            {
                "node_id": str(element.attrib.get("id") or ""),
                "bounds": bounds,
                "resource_id": str(element.attrib.get("resource-id") or "").rsplit(
                    "/", 1
                )[-1],
                "text": _text(element.attrib.get("text")),
                "description": _text(element.attrib.get("content-desc")),
                "class": str(element.attrib.get("class") or element.tag).rsplit(".", 1)[
                    -1
                ],
                "clickable": str(element.attrib.get("clickable") or "").lower()
                == "true",
            }
        )
    return elements


def _display_size(
    observation: Observation,
    elements: list[dict[str, Any]],
) -> tuple[float, float] | None:
    xml_size = _xml_size(elements)
    if xml_size is not None:
        return xml_size
    display = observation.extra.get("display")
    if not isinstance(display, dict) or set(display) != {"width", "height"}:
        return None
    try:
        width = float(display.get("width") or 0)
        height = float(display.get("height") or 0)
    except (TypeError, ValueError):
        return None
    if width <= 0 or height <= 0:
        return None
    return width, height


def _xml_size(elements: list[dict[str, Any]]) -> tuple[float, float] | None:
    if not elements:
        return None
    width = max(float(element["bounds"][2]) for element in elements)
    height = max(float(element["bounds"][3]) for element in elements)
    return (width, height) if width > 0 and height > 0 else None


def _display_size_error(
    observation: Observation,
    elements: list[dict[str, Any]],
) -> str:
    return "omnitransfer_display_size_missing"


def _bounds(value: Any) -> tuple[int, int, int, int] | None:
    numbers = [int(item) for item in re.findall(r"-?\d+", str(value or ""))]
    if len(numbers) != 4 or numbers[2] <= numbers[0] or numbers[3] <= numbers[1]:
        return None
    return numbers[0], numbers[1], numbers[2], numbers[3]


def _text(value: Any) -> str:
    return " ".join(str(value or "").lower().split())
