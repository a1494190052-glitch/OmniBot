from __future__ import annotations

import json
import os
import re
from typing import Any

from omniflow.config import PromptSet
from omniflow.llm_usage import LLMUsageTracker
from omniflow.model import Action, Observation
from omniflow.schemas import canonicalize_action, openai_action_tools

_ORPHANED_Y_COORDINATE = re.compile(
    r'^(?P<prefix>\{.*"x"\s*:\s*-?(?:\d+(?:\.\d*)?|\.\d+))'
    r'\s*,\s*(?P<y>-?(?:\d+(?:\.\d*)?|\.\d+))\s*\}$',
    re.DOTALL,
)


def _parse_tool_arguments(tool_name: str, raw_arguments: Any) -> dict[str, Any]:
    text = str(raw_arguments or "{}")
    try:
        arguments = json.loads(text)
    except json.JSONDecodeError as error:
        if tool_name not in {"click", "long_press"}:
            raise
        match = _ORPHANED_Y_COORDINATE.fullmatch(text.strip())
        if match is None:
            raise
        repaired = f'{match.group("prefix")}, "y": {match.group("y")}}}'
        try:
            arguments = json.loads(repaired)
            canonicalize_action(
                {"tool": tool_name, "args": arguments},
                persisted_only=False,
            )
        except (json.JSONDecodeError, ValueError, TypeError):
            raise error
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
        content: list[dict[str, Any]] = [
            {
                "type": "text",
                "text": json.dumps(
                    {
                        "goal": goal,
                        "visible_ui_xml": str(observation.xml or "")[:30000],
                        "screen_context": observation.extra,
                    },
                    ensure_ascii=False,
                ),
            }
        ]
        if observation.image_base64:
            image = str(observation.image_base64)
            image_url = (
                image
                if image.startswith("data:image/")
                else f"data:image/png;base64,{image}"
            )
            content.append({"type": "image_url", "image_url": {"url": image_url}})
        messages: list[dict[str, Any]] = [
            {"role": "system", "content": self.prompts.planner_system},
            {"role": "user", "content": content},
        ]
        for attempt in range(2):
            self._usage.start_call()
            try:
                response = client.chat.completions.create(
                    model=self.model,
                    messages=messages,
                    tools=openai_action_tools(),
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
                                "including 0..1000 relative coordinates."
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
