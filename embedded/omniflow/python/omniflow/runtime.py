from __future__ import annotations

import asyncio
import inspect
from pathlib import Path
from typing import Any

from omniflow.artifact import bind_function
from omniflow.config import Experiment, OmniFlowConfig
from omniflow.execute import execute_action, execute_function, trace_step
from omniflow.model import (
    Action,
    Function,
    FunctionResolver,
    Host,
    Observation,
    Planner,
    RunResult,
)
from omniflow.store import FunctionStore


class OmniFlow:
    def __init__(
        self,
        store_path: str | Path,
        *,
        host: Host | None = None,
        planner: Planner | None = None,
        resolver: FunctionResolver | None = None,
        config: OmniFlowConfig | None = None,
    ):
        self.config = config or OmniFlowConfig()
        self.store = FunctionStore(store_path)
        self.host = host
        self.planner = planner
        self.resolver = resolver
        self.plugins = self.config.resolved_plugins()

    async def arun(
        self,
        goal: str,
        *,
        experiment: Experiment | str | None = None,
    ) -> RunResult:
        if self.host is None:
            return RunResult(False, error="host_not_set")
        profile = _experiment(experiment)
        actions_executed = 0
        model_calls = 0
        fallback_steps = 0
        trace: list[dict[str, Any]] = []
        last_error = "function_recall_miss"
        resolution_detail: dict[str, Any] = {}
        failed_function_id: str | None = None
        observation = await self._observe(screenshot=False)

        functions = sorted(self.store.functions.values(), key=lambda item: item.id)
        selected_function: Function | None = None
        resolved_arguments: dict[str, Any] = {}
        if functions and self.resolver is not None:
            try:
                resolution = await _await(self.resolver.resolve(goal, functions))
                model_calls += max(0, int(resolution.model_calls))
                resolution_detail = dict(resolution.detail)
                selected_function = next(
                    (
                        function
                        for function in functions
                        if function.id == resolution.function_id
                    ),
                    None,
                )
                if resolution.function_id and selected_function is None:
                    last_error = "function_resolver_selected_unavailable_function"
                elif selected_function is None:
                    last_error = "function_resolver_miss"
                else:
                    resolved_arguments = dict(resolution.arguments)
            except Exception as error:  # noqa: BLE001
                last_error = f"function_resolver_failed:{error}"
        elif functions:
            last_error = "function_resolver_not_set"

        if selected_function is not None:
            replay = await self._execute_selected_function(
                selected_function,
                arguments=resolved_arguments,
                observation=observation,
                max_actions=self.config.runtime.max_steps,
            )
            actions_executed += replay.actions_executed
            trace.extend(replay.detail.get("trace") or ())
            if replay.success:
                return self._result(
                    True,
                    profile=profile,
                    trace=trace,
                    function_id=selected_function.id,
                    actions_executed=actions_executed,
                    model_calls=model_calls,
                    final_state=replay.final_state,
                    function_resolution=resolution_detail,
                )
            failed_function_id = selected_function.id
            observation = replay.final_state or observation
            if not replay.success:
                last_error = replay.error or "function_replay_failed"

        if self.planner is None:
            return self._result(
                False,
                profile=profile,
                trace=trace,
                function_id=failed_function_id,
                actions_executed=actions_executed,
                model_calls=model_calls,
                error=last_error,
                final_state=observation,
                function_resolution=resolution_detail,
            )

        planner_budget = max(
            0,
            self.config.runtime.max_steps - max(actions_executed, len(trace)),
        )
        planner_attempts = 0
        previous_action_error: str | None = (
            last_error if failed_function_id is not None else None
        )
        previous_action: Action | None = None
        while planner_attempts < planner_budget:
            observation = await self._observe(screenshot=True)
            recent_actions = _recent_actions(trace)
            if previous_action_error or recent_actions:
                observation = Observation(
                    xml=observation.xml,
                    package_name=observation.package_name,
                    activity_name=observation.activity_name,
                    image_base64=observation.image_base64,
                    extra={
                        **dict(observation.extra),
                        "previous_action_error": previous_action_error,
                        "previous_action": previous_action.to_dict()
                        if previous_action is not None
                        else None,
                        **(
                            {"recent_actions": recent_actions}
                            if recent_actions
                            else {}
                        ),
                    },
                )
            try:
                planned = Action.from_value(
                    await _await(self.planner.one_step_action(goal, observation))
                )
            except Exception as error:  # noqa: BLE001
                return self._result(
                    False,
                    profile=profile,
                    trace=trace,
                    function_id=failed_function_id,
                    actions_executed=actions_executed,
                    model_calls=model_calls,
                    fallback_steps=fallback_steps,
                    error=f"vlm_planner_failed:{error}",
                    final_state=observation,
                    function_resolution=resolution_detail,
                )
            model_calls += 1
            fallback_steps += 1
            planner_attempts += 1
            if planned.tool == "finished":
                return self._result(
                    True,
                    profile=profile,
                    trace=trace,
                    function_id=failed_function_id,
                    actions_executed=actions_executed,
                    model_calls=model_calls,
                    fallback_steps=fallback_steps,
                    final_state=observation,
                    function_resolution=resolution_detail,
                )
            step = await execute_action(
                planned,
                observation=observation,
                host=self.host,
                plugins=self.plugins,
                source_state=observation,
            )
            for executed_step in step.executed_steps or (step,):
                trace.append(trace_step(executed_step, len(trace)))
            actions_executed += step.actions_executed
            if not step.success:
                previous_action_error = step.error or "fallback_action_failed"
                previous_action = planned
                continue
            previous_action_error = None
            previous_action = None

        return self._result(
            False,
            profile=profile,
            trace=trace,
            function_id=failed_function_id,
            actions_executed=actions_executed,
            model_calls=model_calls,
            fallback_steps=fallback_steps,
            error=previous_action_error or "max_steps_exceeded",
            final_state=observation,
            function_resolution=resolution_detail,
        )

    async def _observe(self, *, screenshot: bool) -> Observation:
        return Observation.from_value(
            await _await(
                self.host.observe(
                    xml=True,
                    screenshot=screenshot,
                    app_info=True,
                )
            )
        )

    async def _execute_selected_function(
        self,
        function: Function,
        *,
        arguments: dict[str, Any],
        observation: Observation,
        max_actions: int,
    ) -> RunResult:
        try:
            bound_function = bind_function(function, arguments)
        except ValueError as error:
            return RunResult(
                False,
                function_id=function.id,
                error=str(error),
                final_state=observation,
            )
        return await execute_function(
            bound_function,
            host=self.host,
            plugins=self.plugins,
            observation=observation,
            max_actions=max_actions,
        )

    async def arun_function(
        self,
        function_id: str,
        *,
        arguments: dict[str, Any] | None = None,
    ) -> RunResult:
        if self.host is None:
            return RunResult(False, function_id=function_id, error="host_not_set")
        function = self.store.get_function(function_id)
        if function is None:
            return RunResult(False, function_id=function_id, error="function_not_found")
        observation = await self._observe(screenshot=False)
        return await self._execute_selected_function(
            function,
            arguments=dict(arguments or {}),
            observation=observation,
            max_actions=self.config.runtime.max_steps,
        )

    def run(
        self,
        goal: str,
        *,
        experiment: Experiment | str | None = None,
    ) -> RunResult:
        try:
            asyncio.get_running_loop()
        except RuntimeError:
            return asyncio.run(self.arun(goal, experiment=experiment))
        raise RuntimeError("OmniFlow.run cannot run inside an event loop; await arun")

    def _result(
        self,
        success: bool,
        *,
        profile: Experiment,
        trace: list[dict[str, Any]],
        function_id: str | None = None,
        actions_executed: int = 0,
        model_calls: int = 0,
        fallback_steps: int = 0,
        error: str | None = None,
        final_state: Observation | None = None,
        function_resolution: dict[str, Any] | None = None,
    ) -> RunResult:
        detail: dict[str, Any] = {
            "experiment": profile.name,
            "trace": list(trace),
        }
        if function_resolution:
            detail["function_resolution"] = dict(function_resolution)
        return RunResult(
            success,
            function_id,
            actions_executed,
            model_calls,
            fallback_steps,
            error,
            final_state,
            detail,
        )


def _experiment(value: Experiment | str | None) -> Experiment:
    if isinstance(value, Experiment):
        return value
    return Experiment.for_method(str(value or "ours"))


def _recent_actions(
    trace: list[dict[str, Any]],
    *,
    limit: int = 8,
) -> list[dict[str, Any]]:
    history: list[dict[str, Any]] = []
    for step in trace[-max(1, int(limit)) :]:
        if not isinstance(step, dict):
            continue
        action = step["action"]
        result = step["result"]
        metadata = step.get("metadata") or {}
        history.append(
            {
                "tool": str(action.get("tool") or ""),
                "args": dict(action.get("args") or {}),
                "success": result.get("success") is True,
                "error": result.get("error"),
                "function_id": metadata.get("function_id") or None,
            }
        )
    return history


async def _await(value: Any) -> Any:
    return await value if inspect.isawaitable(value) else value
