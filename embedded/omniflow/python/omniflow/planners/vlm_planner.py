from __future__ import annotations

import json
import os
from typing import Any

from omniflow.config import PromptSet
from omniflow.model import Action, Observation
from omniflow.schemas import openai_action_tools


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
        response = client.chat.completions.create(
            model=self.model,
            messages=[
                {"role": "system", "content": self.prompts.planner_system},
                {"role": "user", "content": content},
            ],
            tools=openai_action_tools(),
            tool_choice="required",
            temperature=0,
            timeout=self.timeout,
        )
        message = response.choices[0].message
        tool_calls = getattr(message, "tool_calls", None) or ()
        if not tool_calls:
            raise ValueError("planner_native_tool_call_required")
        call = tool_calls[0].function
        try:
            arguments = json.loads(call.arguments or "{}")
        except json.JSONDecodeError as exc:
            raise ValueError("planner_tool_arguments_must_be_json") from exc
        return Action(str(call.name or ""), dict(arguments))

    def _build_client(self):
        try:
            from openai import OpenAI
        except ImportError as exc:
            raise RuntimeError("Install omniflow[llm] to use VLMPlanner") from exc
        options: dict[str, Any] = {"api_key": self._api_key or "not-required"}
        if self._base_url:
            options["base_url"] = self._base_url
        return OpenAI(**options)
