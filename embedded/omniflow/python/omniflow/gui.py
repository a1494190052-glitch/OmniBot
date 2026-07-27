from __future__ import annotations

import base64
import json
from pathlib import Path
from typing import Any

from omniflow.model_adapter import adapt_tool_arguments
from omniflow.schemas import canonicalize_action, openai_action_tools


SYSTEM_PROMPT = """
You are an Android GUI agent. Complete the user goal from the current screenshot
and accessibility XML. Return exactly one native tool_call each turn. Never put
action JSON or tool syntax in assistant text. Choose one action, wait for its
result, then inspect the fresh state before choosing another action. Coordinates
are relative numbers in the 0..1000 range, not screen pixels. Every tool call
must include a concise summary explaining why that action is the best next step.
Use finished only when current evidence directly proves the goal is complete.
Use info only when user input is required.
""".strip()


class ModelToolCallError(ValueError):
    def __init__(
        self,
        message: str,
        *,
        tool_name: str = "",
        arguments: Any = None,
    ):
        self.tool_name = str(tool_name).strip()
        self.arguments = arguments
        super().__init__(message)


def build_model_turn_request(
    *,
    goal: str,
    model: str,
    state: dict[str, Any],
    max_steps: int,
    turn_index: int,
    target_package_name: str = "",
    step_skill_guidance: str = "",
    installed_apps: dict[str, str] | None = None,
    previous_screenshot_path: str = "",
    validation_error: str = "",
    retry_tool_name: str = "",
    rejected_tool_call: dict[str, Any] | None = None,
) -> dict[str, Any]:
    text = _turn_text(
        goal=goal,
        state=state,
        max_steps=max_steps,
        turn_index=turn_index,
        target_package_name=target_package_name,
        step_skill_guidance=step_skill_guidance,
        installed_apps=installed_apps or {},
        validation_error=validation_error,
        rejected_tool_call=rejected_tool_call,
    )
    content: list[dict[str, Any]] = [{"type": "text", "text": text}]
    include_images = not validation_error.strip()
    previous_image = _image_data_uri(previous_screenshot_path) if include_images else ""
    current_image = _state_image_data_uri(state) if include_images else ""
    if previous_image:
        content.extend(
            (
                {"type": "text", "text": "Previous screenshot before the last action:"},
                {"type": "image_url", "image_url": {"url": previous_image}},
            )
        )
        if current_image:
            content.append(
                {"type": "text", "text": "Current screenshot after the last action:"}
            )
    if current_image:
        content.append({"type": "image_url", "image_url": {"url": current_image}})
    tools = openai_action_tools(include_summary=True)
    if retry_tool_name:
        tools = [
            tool
            for tool in tools
            if tool.get("function", {}).get("name") == retry_tool_name
        ]
        if len(tools) != 1:
            raise ValueError(f"model_turn_retry_tool_not_visible:{retry_tool_name}")
    return {
        "model": str(model),
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": content},
        ],
        "max_completion_tokens": 4096,
        "temperature": 0,
        "stream": True,
        "stream_options": {"include_usage": True},
        "tools": tools,
        "tool_choice": "required",
        "parallel_tool_calls": False,
        "enable_thinking": False,
    }


def parse_model_turn_response(
    value: Any,
    *,
    requested_model: str,
    turn_index: int,
) -> tuple[dict[str, Any], dict[str, Any]]:
    if not isinstance(value, dict):
        raise ValueError("model_turn_response_invalid")
    if str(value.get("requested_model") or "").strip() != requested_model:
        raise ValueError("model_turn_requested_model_mismatch")
    tool_calls = value.get("tool_calls")
    if not isinstance(tool_calls, list):
        raise ModelToolCallError("model_turn_tool_calls_invalid")
    if len(tool_calls) != 1:
        raise ModelToolCallError(
            f"provider_tool_call_contract_violation:expected_one_native_tool_call:got_{len(tool_calls)}"
        )
    tool_call = tool_calls[0]
    if not isinstance(tool_call, dict):
        raise ModelToolCallError("model_turn_tool_call_invalid")
    function = tool_call.get("function")
    if not isinstance(function, dict):
        raise ModelToolCallError("model_turn_tool_call_function_invalid")
    tool = str(function.get("name") or "").strip()
    model_visible_tools = {
        str(item.get("function", {}).get("name") or "")
        for item in openai_action_tools()
        if isinstance(item, dict) and isinstance(item.get("function"), dict)
    }
    if tool not in model_visible_tools:
        raise ModelToolCallError(f"model_turn_tool_not_visible:{tool}")
    raw_arguments = function.get("arguments")
    if not isinstance(raw_arguments, str):
        raise ModelToolCallError(
            "model_turn_tool_arguments_invalid",
            tool_name=tool,
            arguments=raw_arguments,
        )
    try:
        arguments = json.loads(raw_arguments)
    except json.JSONDecodeError as error:
        raise ModelToolCallError(
            "model_turn_tool_arguments_must_be_json",
            tool_name=tool,
            arguments=raw_arguments,
        ) from error
    if not isinstance(arguments, dict):
        raise ModelToolCallError(
            "model_turn_tool_arguments_must_be_object",
            tool_name=tool,
            arguments=arguments,
        )
    rejected_arguments = dict(arguments)
    summary = str(arguments.pop("summary", "") or "").strip()
    if not summary:
        raise ModelToolCallError(
            "model_turn_summary_required",
            tool_name=tool,
            arguments=rejected_arguments,
        )
    resolved_model = str(value.get("resolved_model") or requested_model).strip()
    arguments, adapter_metadata = adapt_tool_arguments(
        tool=tool,
        arguments=arguments,
        requested_model=requested_model,
        resolved_model=resolved_model,
    )
    try:
        action = canonicalize_action(
            {"tool": tool, "args": arguments},
            persisted_only=False,
            allow_non_action=True,
        )
    except ValueError as error:
        raise ModelToolCallError(
            str(error),
            tool_name=tool,
            arguments=rejected_arguments,
        ) from error
    metadata: dict[str, Any] = {"summary": summary}
    if adapter_metadata is not None:
        metadata["model_adapter"] = adapter_metadata
    thinking = str(value.get("reasoning") or "").strip()
    if thinking:
        metadata["thinking"] = thinking
    usage = value.get("usage")
    if isinstance(usage, dict):
        metadata["token_usage"] = {
            **usage,
            "model": requested_model,
            "resolved_model": resolved_model,
            "turn_index": int(turn_index),
        }
    return action, metadata


def _turn_text(
    *,
    goal: str,
    state: dict[str, Any],
    max_steps: int,
    turn_index: int,
    target_package_name: str,
    step_skill_guidance: str,
    installed_apps: dict[str, str],
    validation_error: str,
    rejected_tool_call: dict[str, Any] | None,
) -> str:
    display = state.get("display") if isinstance(state.get("display"), dict) else {}
    lines = [
        f"Goal: {goal}",
        f"Progress: {turn_index}/{max_steps} model turns used",
        f"Current package: {state.get('package_name') or ''}",
        f"Current activity: {state.get('activity_name') or ''}",
        f"Display: {display.get('width') or ''}x{display.get('height') or ''}",
    ]
    if target_package_name:
        lines.append(f"Target package: {target_package_name}")
    if step_skill_guidance.strip():
        lines.extend(("Task guidance:", step_skill_guidance.strip()))
    if installed_apps:
        lines.extend(
            (
                "Installed apps (label=package):",
                "; ".join(f"{label}={package}" for label, package in installed_apps.items()),
            )
        )
    if validation_error.strip():
        lines.extend(
            (
                "Your previous native tool_call was rejected by the registered schema:",
                validation_error.strip(),
                "Return one corrected native tool_call using the schema exactly. Do not rename, wrap, combine, or infer fields.",
                "Coordinate fields such as x and y must each be one JSON number from 0 to 1000. Never use [x, y], an object, string, or boolean for a coordinate field.",
            )
        )
        if rejected_tool_call:
            lines.extend(
                (
                    "Rejected native tool_call from your immediately previous attempt (verbatim):",
                    json.dumps(
                        rejected_tool_call,
                        ensure_ascii=False,
                        separators=(",", ":"),
                    ),
                    "Do not repeat that argument shape. Return a new tool_call; do not explain or repair it in text.",
                    'Valid scalar coordinate shape: {"x":500,"y":464,"x1":500,"y1":800,"x2":500,"y2":400}. Invalid array shape: {"x":[500],"y":[464],"x1":[500,800]}.',
                    "If your rejected call placed one intended point in x as [X,Y], choose the scalars yourself and emit x:X and y:Y in the new call. The runtime will not transform the array for you.",
                )
            )
    extra = state.get("extra")
    if isinstance(extra, dict) and extra:
        recent_actions = extra.get("recent_actions")
        if isinstance(recent_actions, list) and any(
            isinstance(item, dict)
            and item.get("success") is True
            and str(item.get("function_id") or "").strip()
            for item in recent_actions
        ):
            lines.append(
                "A recalled Function completed successfully in the recent action "
                "history. If the goal asks to run, use, or execute that saved "
                "workflow once, the requested operation is already complete: "
                "choose finished now. Do not add extra GUI actions merely to verify it."
            )
        if extra.get("previous_action_error") or extra.get("recent_actions"):
            lines.append(
                "Use the recent action history and error before selecting again. "
                "Do not repeat the same action when it already succeeded or made no "
                "progress; choose a different schema-valid action, finish, or abort."
            )
        lines.extend(
            (
                "Recent execution context:",
                json.dumps(extra, ensure_ascii=False, separators=(",", ":")),
            )
        )
    lines.extend(("Accessibility XML:", str(state.get("xml") or "<empty/>")))
    return "\n".join(lines)


def _state_image_data_uri(state: dict[str, Any]) -> str:
    image = str(state.get("image_base64") or "").strip()
    if image:
        return image if image.startswith("data:image/") else f"data:image/jpeg;base64,{image}"
    return _image_data_uri(str(state.get("screenshot_path") or ""))


def _image_data_uri(path: str) -> str:
    candidate = Path(str(path or "").strip())
    if not candidate.is_file():
        return ""
    try:
        payload = candidate.read_bytes()
    except OSError:
        return ""
    mime_type = _image_mime_type(payload)
    if not mime_type:
        return ""
    encoded = base64.b64encode(payload).decode("ascii")
    return f"data:{mime_type};base64,{encoded}" if encoded else ""


def _image_mime_type(payload: bytes) -> str:
    if payload.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if payload.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if len(payload) >= 12 and payload[:4] == b"RIFF" and payload[8:12] == b"WEBP":
        return "image/webp"
    return ""


__all__ = [
    "ModelToolCallError",
    "SYSTEM_PROMPT",
    "build_model_turn_request",
    "parse_model_turn_response",
]
