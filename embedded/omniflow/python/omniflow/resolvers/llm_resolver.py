from __future__ import annotations

import json
import os
from typing import Any

from omniflow.artifact import validate_arguments
from omniflow.llm_usage import LLMUsageTracker
from omniflow.model import Function, FunctionResolution

MAX_RESOLUTION_OUTPUT_TOKENS = 1024

SYSTEM_PROMPT = """Select one reusable Function for the user's goal and extract its arguments.
Return JSON only: {"function_id": string|null, "arguments": object}.
Rules:
- Use only the supplied goal and Function metadata.
- Select a Function only when its complete intent matches the goal.
- A Function is a complete goal plan, not a preparatory or intermediate step.
  If its actions only open, prepare, inspect, or navigate to something that the
  goal still asks the agent to use, read, enter, change, or save, return null.
  Do not select a useful partial Function merely because it is related to the
  goal; the caller will continue through the normal VLM path when no complete
  Function exists.
- Extract every required argument from the goal itself.
- Return only arguments declared by the selected Function JSON Schema.
- Preserve exact user values, spelling, punctuation, numbers, and file extensions.
- A zero-parameter Function with fixed values matches only when the goal requests
  those exact values; never treat a recorded action as a generic action template.
- Never infer values from examples, defaults, action coordinates, or hidden state.
- If no Function matches or any required value is absent, return null and {}.
"""


class LLMFunctionResolver:
    def __init__(
        self,
        *,
        model: str,
        provider: str = "openai",
        api_key: str | None = None,
        base_url: str | None = None,
        timeout: float = 30.0,
        client: Any | None = None,
        prompt: str = SYSTEM_PROMPT,
    ):
        if provider not in {"openai", "openai_compatible"}:
            raise ValueError(
                "LLMFunctionResolver supports OpenAI-compatible providers only"
            )
        if not str(model or "").strip():
            raise ValueError("function_resolver_model_required")
        self.model = str(model).strip()
        self.timeout = float(timeout)
        self.prompt = str(prompt)
        self._client = client
        self._api_key = api_key or os.getenv("OPENAI_API_KEY")
        self._base_url = base_url or os.getenv("OPENAI_BASE_URL")
        self._usage = LLMUsageTracker(component="resolver", model=self.model)

    def resolve(
        self,
        goal: str,
        functions: list[Function],
    ) -> FunctionResolution:
        candidates = [
            {
                "function_id": function.id,
                "description": function.description,
                "input_schema": function.input_schema,
            }
            for function in functions
            if function.actions
        ]
        if not candidates:
            return FunctionResolution(
                function_id=None,
                arguments={},
                model_calls=0,
                detail={"reason": "no_candidates", "model": self.model},
            )
        client = self._client or self._build_client()
        self._usage.start_call()
        try:
            response = client.chat.completions.create(
                model=self.model,
                messages=[
                    {"role": "system", "content": self.prompt},
                    {
                        "role": "user",
                        "content": json.dumps(
                            {"goal": str(goal), "functions": candidates},
                            ensure_ascii=False,
                        ),
                    },
                ],
                response_format={"type": "json_object"},
                max_tokens=MAX_RESOLUTION_OUTPUT_TOKENS,
                temperature=0,
                timeout=self.timeout,
            )
        except Exception:
            self._usage.record_failure()
            raise
        self._usage.record_response(response)
        content = response.choices[0].message.content
        function_id, arguments = parse_resolution(content, functions)
        return FunctionResolution(
            function_id=function_id,
            arguments=arguments,
            model_calls=1,
            detail={
                "model": self.model,
                "candidate_count": len(candidates),
                "arguments": arguments,
            },
        )

    def take_usage(self) -> dict[str, Any]:
        return self._usage.take_usage()

    def _build_client(self):
        try:
            from openai import OpenAI
        except ImportError as exc:
            raise RuntimeError(
                "Install omniflow[llm] to use LLMFunctionResolver"
            ) from exc
        options: dict[str, Any] = {"api_key": self._api_key or "not-required"}
        if self._base_url:
            options["base_url"] = self._base_url
        return OpenAI(**options)


def parse_resolution(
    value: Any,
    functions: list[Function],
) -> tuple[str | None, dict[str, Any]]:
    if isinstance(value, dict):
        payload = value
    else:
        try:
            payload = json.loads(str(value or ""))
        except json.JSONDecodeError as exc:
            raise ValueError("function_resolver_response_must_be_json") from exc
    if not isinstance(payload, dict):
        raise ValueError("function_resolver_response_must_be_object")
    function_id = payload.get("function_id")
    arguments = payload.get("arguments")
    if function_id is None:
        if arguments not in ({}, None):
            raise ValueError("function_resolver_null_selection_has_arguments")
        return None, {}
    function_id = str(function_id).strip()
    candidates = {function.id: function for function in functions}
    if function_id not in candidates:
        raise ValueError("function_resolver_selected_unknown_function")
    if not isinstance(arguments, dict):
        raise ValueError("function_resolver_arguments_must_be_object")
    validate_arguments(candidates[function_id].input_schema, arguments)
    return function_id, dict(arguments)
