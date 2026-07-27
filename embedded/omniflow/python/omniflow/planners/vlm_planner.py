from __future__ import annotations

import json
import os
import re
from typing import Any

from omniflow.config import PromptSet
from omniflow.llm_usage import LLMUsageTracker
from omniflow.model import Action, Observation
from omniflow.model_adapter import adapt_tool_arguments
from omniflow.schemas import canonicalize_action, vlm_action_tools
from omniflow.tool_argument_repair import load_tool_arguments
from omniflow.ui_projection import project_ui
from omniflow.vlm_coordinates import (
    display_size,
    screen_context_to_pixels,
    screen_pixel_args_to_canonical,
    screen_pixel_tools,
)

_ORPHANED_Y_COORDINATE = re.compile(
    r'^(?P<prefix>\{.*"x"\s*:\s*-?(?:\d+(?:\.\d*)?|\.\d+))'
    r"\s*,\s*(?P<y>-?(?:\d+(?:\.\d*)?|\.\d+))\s*\}$",
    re.DOTALL,
)


def _parse_tool_arguments(tool_name: str, raw_arguments: Any) -> dict[str, Any]:
    text = str(raw_arguments or "{}")
    try:
        arguments = json.loads(text)
    except json.JSONDecodeError as error:
        match = (
            _ORPHANED_Y_COORDINATE.fullmatch(text.strip())
            if tool_name in {"click", "long_press"}
            else None
        )
        if match is not None:
            repaired = f'{match.group("prefix")}, "y": {match.group("y")}}}'
            try:
                arguments = json.loads(repaired)
            except (json.JSONDecodeError, TypeError):
                raise error
        else:
            arguments, _repaired = load_tool_arguments(text)
    if not isinstance(arguments, dict):
        raise ValueError("planner_tool_arguments_must_be_object")
    return arguments


class VLMPlanner:
    def __init__(
        self,
        *,
        model: str,
        provider: str = "openai",
        api_key: str | None = None,
        base_url: str | None = None,
        timeout: float = 60.0,
        client: Any | None = None,
        prompts: PromptSet | None = None,
    ):
        if provider not in {"openai", "openai_compatible"}:
            raise ValueError("VLMPlanner supports OpenAI-compatible providers only")
        self.model = model
        self.timeout = timeout
        self._client = client
        self._api_key = api_key or os.getenv("OPENAI_API_KEY")
        self._base_url = base_url or os.getenv("OPENAI_BASE_URL")
        self.prompts = prompts or PromptSet()
        self._usage = LLMUsageTracker(component="planner", model=self.model)

    async def one_step_action(self, goal: str, observation: Observation) -> Action:
        client = self._client or self._build_client()
        projection = project_ui(str(observation.xml or ""), goal)
        display = (
            observation.extra.get("display")
            if isinstance(observation.extra.get("display"), dict)
            else None
        )
        width, height = display_size(display)
        screen_context = screen_context_to_pixels(
            {key: value for key, value in observation.extra.items() if key != "display"},
            display,
        )
        content: list[dict[str, Any]] = [
            {
                "type": "text",
                "text": json.dumps(
                    {
                        "goal": goal,
                        "relevant_ui_elements": projection.text,
                        "ui_candidate_count": projection.candidate_count,
                        "display": {"width": width, "height": height},
                        "coordinate_space": "current_display_pixels",
                        "screen_context": screen_context,
                    },
                    ensure_ascii=False,
                ),
            }
        ]
        if observation.image_base64 and projection.requires_screenshot:
            image = str(observation.image_base64)
            image_url = (
                image
                if image.startswith("data:image/")
                else f"data:image/png;base64,{image}"
            )
            content.append({"type": "image_url", "image_url": {"url": image_url}})
        messages: list[dict[str, Any]] = [
            {
                "role": "system",
                "content": (
                    f"{self.prompts.planner_system}\n\n"
                    "Mandatory coordinate contract: all tool coordinates are raw "
                    f"pixels in the current original Display (X 0..{int(width)}, "
                    f"Y 0..{int(height)}), never normalized 0..1000 values. XML "
                    "bounds use the same frame. Transport image resizing does not "
                    "change the coordinate frame."
                ),
            },
            {"role": "user", "content": content},
        ]
        for attempt in range(2):
            self._usage.start_call()
            try:
                response = client.chat.completions.create(
                    model=self.model,
                    messages=messages,
                    tools=screen_pixel_tools(vlm_action_tools(), display),
                    tool_choice="required",
                    temperature=0,
                    timeout=self.timeout,
                )
            except Exception:
                self._usage.record_failure()
                raise
            self._usage.record_response(response)
            message = response.choices[0].message
            tool_calls = getattr(message, "tool_calls", None) or ()
            if not tool_calls:
                raise ValueError("planner_native_tool_call_required")
            call = tool_calls[0].function
            try:
                arguments = _parse_tool_arguments(
                    str(call.name or ""),
                    call.arguments,
                )
                arguments, _adapter_metadata = adapt_tool_arguments(
                    tool=str(call.name or ""),
                    arguments=arguments,
                    requested_model=self.model,
                    resolved_model=self.model,
                    display=display,
                )
                arguments, _coordinate_metadata = screen_pixel_args_to_canonical(
                    tool=str(call.name or ""),
                    args=arguments,
                    display=display,
                )
                canonical = canonicalize_action(
                    {"tool": str(call.name or ""), "args": arguments},
                    persisted_only=False,
                    allow_non_action=True,
                )
            except (json.JSONDecodeError, TypeError, ValueError) as exc:
                if attempt == 0:
                    messages = [
                        *messages,
                        {
                            "role": "user",
                            "content": (
                                "The previous tool call arguments were invalid "
                                f"({exc}). "
                                "Return exactly one provided GUI tool call whose "
                                "arguments are valid JSON and satisfy its schema, "
                                "including raw current-Display pixel coordinates."
                            ),
                        },
                    ]
                    continue
                if isinstance(exc, json.JSONDecodeError):
                    raise ValueError("planner_tool_arguments_must_be_json") from exc
                raise
            return Action.from_value(canonical)
        raise AssertionError("unreachable")

    def take_usage(self) -> dict[str, Any]:
        return self._usage.take_usage()

    def _build_client(self):
        try:
            from openai import OpenAI
        except ImportError as exc:
            raise RuntimeError("Install omniflow[llm] to use VLMPlanner") from exc
        options: dict[str, Any] = {"api_key": self._api_key or "not-required"}
        if self._base_url:
            options["base_url"] = self._base_url
        return OpenAI(**options)
