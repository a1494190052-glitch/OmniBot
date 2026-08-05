from __future__ import annotations

import json
from typing import Any

from omniflow.core.schemas import canonicalize_action
from omniflow.core.trajectory import observation_display


def project_androidworld_step_actions(value: dict[str, Any]) -> list[dict[str, Any]]:
    if not isinstance(value, dict) or not isinstance(value.get("observation"), dict):
        raise ValueError("androidworld_run_log_step_required")
    action = dict(value.get("action") or {})
    projected: list[dict[str, Any]] = []
    point = _androidworld_action_point(action, value["observation"])
    if action.get("action_type") == "input_text" and point is not None:
        projected.append(
            canonicalize_action(
                {"tool": "click", "args": point},
                replayable_only=True,
            )
        )
    projected.append(
        _androidworld_action_to_omniflow(
            action,
            observation=value["observation"],
        )
    )
    return projected


def _androidworld_action_to_omniflow(
    value: Any,
    *,
    observation: dict[str, Any],
) -> dict[str, Any]:
    action = dict(value) if isinstance(value, dict) else {}
    action_type = str(action.get("action_type") or "").strip()
    if action_type in {"click", "double_tap"}:
        projected = {
            "tool": "click",
            "args": _required_androidworld_action_point(action, observation),
        }
    elif action_type == "long_press":
        projected = {
            "tool": "long_press",
            "args": _required_androidworld_action_point(action, observation),
        }
    elif action_type == "input_text":
        projected = {"tool": "input_text", "args": {"text": action.get("text", "")}}
    elif action_type in {"scroll", "swipe"}:
        projected = {
            "tool": "swipe",
            "args": {
                "direction": str(action.get("direction") or ""),
                **_androidworld_standard_swipe(
                    action_type,
                    str(action.get("direction") or ""),
                ),
            },
        }
    elif action_type == "open_app":
        projected = {
            "tool": "open_app",
            "args": {"package_name": str(action.get("app_name") or "")},
        }
    elif action_type == "navigate_back":
        projected = {"tool": "press_key", "args": {"key": "back"}}
    elif action_type == "navigate_home":
        projected = {"tool": "press_key", "args": {"key": "home"}}
    elif action_type == "keyboard_enter":
        projected = {"tool": "press_key", "args": {"key": "enter"}}
    elif action_type == "wait":
        projected = {"tool": "wait", "args": {"duration_ms": 1000}}
    else:
        raise ValueError(f"androidworld_action_not_executable:{action_type}")
    return canonicalize_action(projected, replayable_only=True, allow_non_action=True)


def _required_androidworld_action_point(
    action: dict[str, Any],
    observation: dict[str, Any],
) -> dict[str, float]:
    point = _androidworld_action_point(action, observation)
    if point is None:
        raise ValueError("androidworld_action_point_not_transferable")
    return point


def _androidworld_action_point(
    action: dict[str, Any],
    observation: dict[str, Any],
) -> dict[str, float] | None:
    x = action.get("x")
    y = action.get("y")
    if x is None or y is None:
        index = action.get("index")
        elements = observation.get("ui_elements")
        if (
            not isinstance(index, int)
            or isinstance(index, bool)
            or not isinstance(elements, list)
            or index < 0
            or index >= len(elements)
        ):
            return None
        bounds = _ui_element_bounds(elements[index])
        if bounds is None:
            return None
        left, top, right, bottom = bounds
        x = (left + right) / 2.0
        y = (top + bottom) / 2.0
    display = observation_display(observation)
    if display is None:
        raise ValueError("androidworld_action_display_required")
    width, height = display
    return {
        "x": float(x) / width * 1000.0,
        "y": float(y) / height * 1000.0,
    }


def _ui_element_bounds(value: Any) -> tuple[float, float, float, float] | None:
    element = _map(value)
    bounds = _map(element.get("bbox_pixels")) or _map(element.get("bbox"))
    aliases = (
        ("x_min", "y_min", "x_max", "y_max"),
        ("left", "top", "right", "bottom"),
    )
    for keys in aliases:
        try:
            left, top, right, bottom = (float(bounds[key]) for key in keys)
        except (KeyError, TypeError, ValueError):
            continue
        if right > left and bottom > top:
            return left, top, right, bottom
    return None


def _androidworld_standard_swipe(
    action_type: str,
    direction: str,
) -> dict[str, float]:
    gestures = {
        "scroll": {
            "down": (500.0, 500.0, 500.0, 0.0),
            "up": (500.0, 500.0, 500.0, 1000.0),
            "right": (500.0, 500.0, 0.0, 500.0),
            "left": (500.0, 500.0, 1000.0, 500.0),
        },
        "swipe": {
            "down": (500.0, 0.0, 500.0, 1000.0),
            "up": (500.0, 1000.0, 500.0, 0.0),
            "left": (0.0, 500.0, 1000.0, 500.0),
            "right": (1000.0, 500.0, 0.0, 500.0),
        },
    }
    try:
        x1, y1, x2, y2 = gestures[action_type][direction]
    except KeyError as error:
        raise ValueError(
            f"androidworld_action_direction_required:{action_type}"
        ) from error
    return {"x1": x1, "y1": y1, "x2": x2, "y2": y2}


def _map(value: Any) -> dict[str, Any]:
    if isinstance(value, str):
        try:
            value = json.loads(value)
        except json.JSONDecodeError:
            return {}
    return dict(value) if isinstance(value, dict) else {}


__all__ = ["project_androidworld_step_actions"]
