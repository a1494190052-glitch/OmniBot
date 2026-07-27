from __future__ import annotations

import asyncio
import inspect
from pathlib import Path
from typing import Any

from omniflow.artifact import bind_function
from omniflow.config import Experiment, OmniFlowConfig
from omniflow.execute import (
    align_function_resume,
    execute_action,
    execute_function,
    record_execution,
)
from omniflow.llm_usage import merge_usage, token_usage_status
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


class InputRequired(RuntimeError):
    def __init__(self, question: str):
        self.question = str(question).strip()
        super().__init__(self.question or "input_required")


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
        llm_usage: dict[str, Any] = {}
        failed_function_id: str | None = None
        replayed_function_id: str | None = None
        bound_function: Function | None = None
        failed_step_index: int | None = None
        fallback_observations: list[Observation] = []
        observation = await self._observe(screenshot=False)

        functions = sorted(self.store.functions.values(), key=lambda item: item.id)
        selected_function: Function | None = None
        resolved_arguments: dict[str, Any] = {}
        if functions and self.resolver is not None:
            try:
                resolution = await _await(self.resolver.resolve(goal, functions))
                resolver_usage = _take_llm_usage(self.resolver)
                merge_usage(llm_usage, resolver_usage, component="resolver")
                model_calls += _usage_model_calls(
                    resolver_usage,
                    fallback=max(0, int(resolution.model_calls)),
                )
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
                resolver_usage = _take_llm_usage(self.resolver)
                merge_usage(llm_usage, resolver_usage, component="resolver")
                model_calls += _usage_model_calls(resolver_usage, fallback=0)
                last_error = f"function_resolver_failed:{error}"
        elif functions:
            last_error = "function_resolver_not_set"

        if selected_function is not None:
            replayed_function_id = selected_function.id
            try:
                bound_function = bind_function(selected_function, resolved_arguments)
            except ValueError as error:
                replay = RunResult(
                    False,
                    function_id=selected_function.id,
                    error=str(error),
                    final_state=observation,
                )
            else:
                replay = await execute_function(
                    bound_function,
                    host=self.host,
                    plugins=self.plugins,
                    observation=observation,
                    max_actions=self.config.runtime.max_steps,
                )
            actions_executed += replay.actions_executed
            trace.extend(replay.detail.get("trace") or ())
            if replay.success:
                observation = replay.final_state or observation
                last_error = "function_replay_completed_e2e_unverified"
            else:
                failed_function_id = selected_function.id
            observation = replay.final_state or observation
            if not replay.success:
                last_error = replay.error or "function_replay_failed"
                failed_step_index = _optional_step_index(
                    replay.detail.get("failed_step_index")
                )
                if bound_function is not None and failed_step_index is not None:
                    fallback_observations = [observation]

        if self.planner is None:
            return self._result(
                False,
                profile=profile,
                trace=trace,
                function_id=replayed_function_id or failed_function_id,
                actions_executed=actions_executed,
                model_calls=model_calls,
                llm_usage=llm_usage,
                error=last_error,
                final_state=observation,
                function_resolution=resolution_detail,
            )

        runtime_steps_used = max(actions_executed, len(trace))
        previous_action_error: str | None = (
            last_error if failed_function_id is not None else None
        )
        previous_action: Action | None = None
        stalled_action: Action | None = None
        pending_user_input: str | None = None
        planner_diagnostics: dict[str, Any] = {}
        while runtime_steps_used < self.config.runtime.max_steps:
            observation = await self._observe(screenshot=True)
            recent_actions = _recent_actions(trace)
            if previous_action_error or recent_actions or pending_user_input:
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
                            {"recent_actions": recent_actions} if recent_actions else {}
                        ),
                        **(
                            {"user_input": pending_user_input}
                            if pending_user_input
                            else {}
                        ),
                    },
                )
            pending_user_input = None
            try:
                planned = Action.from_value(
                    await _await(self.planner.one_step_action(goal, observation))
                )
            except Exception as error:  # noqa: BLE001
                planner_metadata = _take_planner_metadata(self.planner)
                _merge_planner_diagnostics(planner_diagnostics, planner_metadata)
                planner_usage = _take_llm_usage(self.planner)
                merge_usage(llm_usage, planner_usage, component="planner")
                model_calls += _usage_model_calls(planner_usage, fallback=1)
                return self._result(
                    False,
                    profile=profile,
                    trace=trace,
                    function_id=replayed_function_id or failed_function_id,
                    actions_executed=actions_executed,
                    model_calls=model_calls,
                    llm_usage=llm_usage,
                    fallback_steps=fallback_steps,
                    error=f"vlm_planner_failed:{error}",
                    final_state=observation,
                    function_resolution=resolution_detail,
                    planner_diagnostics=planner_diagnostics,
                )
            planner_usage = _take_llm_usage(self.planner)
            merge_usage(llm_usage, planner_usage, component="planner")
            model_calls += _usage_model_calls(planner_usage, fallback=1)
            fallback_steps += 1
            runtime_steps_used += 1
            planner_metadata = _take_planner_metadata(self.planner)
            _merge_planner_diagnostics(planner_diagnostics, planner_metadata)
            if stalled_action is not None and planned == stalled_action:
                previous_action_error = "repeated_action_without_progress"
                previous_action = planned
                continue
            stalled_action = None
            if planned.tool == "finished":
                return self._result(
                    True,
                    profile=profile,
                    trace=trace,
                    function_id=replayed_function_id or failed_function_id,
                    actions_executed=actions_executed,
                    model_calls=model_calls,
                    llm_usage=llm_usage,
                    fallback_steps=fallback_steps,
                    final_state=observation,
                    function_resolution=resolution_detail,
                    planner_diagnostics=planner_diagnostics,
                    terminal_detail={
                        "done_reason": "finished",
                        "finished_content": str(planned.args.get("content") or ""),
                    },
                )
            if planned.tool == "abort":
                message = str(planned.args.get("value") or "").strip() or "vlm_aborted"
                return self._result(
                    False,
                    profile=profile,
                    trace=trace,
                    function_id=replayed_function_id or failed_function_id,
                    actions_executed=actions_executed,
                    model_calls=model_calls,
                    llm_usage=llm_usage,
                    fallback_steps=fallback_steps,
                    error=message,
                    final_state=observation,
                    function_resolution=resolution_detail,
                    planner_diagnostics=planner_diagnostics,
                    terminal_detail={"done_reason": "abort"},
                )
            if planned.tool == "info":
                question = str(planned.args.get("value") or "").strip()
                if not question:
                    previous_action_error = "info_question_required"
                    previous_action = planned
                    continue
                try:
                    pending_user_input = str(
                        await _await(_request_input(self.host, question))
                    )
                except InputRequired as error:
                    return self._result(
                        False,
                        profile=profile,
                        trace=trace,
                        function_id=replayed_function_id or failed_function_id,
                        actions_executed=actions_executed,
                        model_calls=model_calls,
                        llm_usage=llm_usage,
                        fallback_steps=fallback_steps,
                        error="input_required",
                        final_state=observation,
                        function_resolution=resolution_detail,
                        planner_diagnostics=planner_diagnostics,
                        terminal_detail={
                            "done_reason": "waiting_input",
                            "finished_content": error.question,
                        },
                    )
                except Exception as error:  # noqa: BLE001
                    return self._result(
                        False,
                        profile=profile,
                        trace=trace,
                        function_id=replayed_function_id or failed_function_id,
                        actions_executed=actions_executed,
                        model_calls=model_calls,
                        llm_usage=llm_usage,
                        fallback_steps=fallback_steps,
                        error=f"request_input_failed:{error}",
                        final_state=observation,
                        function_resolution=resolution_detail,
                        planner_diagnostics=planner_diagnostics,
                    )
                previous_action_error = None
                previous_action = None
                continue
            if planned.tool == "get_state":
                previous_action_error = None
                previous_action = None
                continue
            step = await execute_action(
                planned,
                observation=observation,
                host=self.host,
                plugins=self.plugins,
            )
            trace.extend(
                await record_execution(
                    self.host,
                    step,
                    trace_start_index=len(trace),
                    metadata=planner_metadata,
                )
            )
            actions_executed += step.actions_executed
            if not step.success:
                previous_action_error = step.error or "fallback_action_failed"
                previous_action = planned
                continue
            observation = step.after or observation
            if bound_function is not None and failed_step_index is not None:
                fallback_observations.append(observation)
                alignment = await align_function_resume(
                    bound_function,
                    host=self.host,
                    plugins=self.plugins,
                    observations=fallback_observations,
                    start_step_index=failed_step_index,
                )
                if alignment is not None:
                    replay = await execute_function(
                        bound_function,
                        host=self.host,
                        plugins=self.plugins,
                        observation=observation,
                        max_actions=max(
                            0,
                            self.config.runtime.max_steps - runtime_steps_used,
                        ),
                        start_step_index=int(alignment["resume_step_index"]),
                        trace_start_index=len(trace),
                        resume_metadata=alignment,
                    )
                    actions_executed += replay.actions_executed
                    replay_trace = list(replay.detail.get("trace") or ())
                    trace.extend(replay_trace)
                    runtime_steps_used += max(
                        replay.actions_executed,
                        len(replay_trace),
                    )
                    observation = replay.final_state or observation
                    if replay.success:
                        failed_function_id = None
                        failed_step_index = None
                        fallback_observations = []
                        last_error = "function_replay_completed_e2e_unverified"
                        previous_action_error = None
                        previous_action = None
                    else:
                        failed_function_id = bound_function.id
                        last_error = replay.error or "function_replay_failed"
                        failed_step_index = _optional_step_index(
                            replay.detail.get("failed_step_index")
                        )
                        fallback_observations = (
                            [observation] if failed_step_index is not None else []
                        )
                        previous_action_error = last_error
                        previous_action = None
                    continue
            if _same_observation(step.before, step.after):
                previous_action_error = "action_completed_without_state_change"
                previous_action = planned
                stalled_action = planned
            else:
                previous_action_error = None
                previous_action = None

        return self._result(
            False,
            profile=profile,
            trace=trace,
            function_id=replayed_function_id or failed_function_id,
            actions_executed=actions_executed,
            model_calls=model_calls,
            llm_usage=llm_usage,
            fallback_steps=fallback_steps,
            error=previous_action_error or "max_steps_exceeded",
            final_state=observation,
            function_resolution=resolution_detail,
            planner_diagnostics=planner_diagnostics,
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
        llm_usage: dict[str, Any] | None = None,
        fallback_steps: int = 0,
        error: str | None = None,
        final_state: Observation | None = None,
        function_resolution: dict[str, Any] | None = None,
        planner_diagnostics: dict[str, Any] | None = None,
        terminal_detail: dict[str, Any] | None = None,
    ) -> RunResult:
        detail: dict[str, Any] = {
            "experiment": profile.name,
            "trace": list(trace),
        }
        if function_resolution:
            detail["function_resolution"] = dict(function_resolution)
        usage = dict(llm_usage or {})
        tracked_model_calls = _usage_model_calls(usage, fallback=0)
        if tracked_model_calls < model_calls:
            usage["untracked_model_calls"] = model_calls - tracked_model_calls
            usage["model_calls"] = model_calls
        usage["token_usage_status"] = token_usage_status(usage)
        detail["llm_usage"] = usage
        if planner_diagnostics:
            detail["planner_diagnostics"] = dict(planner_diagnostics)
        if terminal_detail:
            detail.update(terminal_detail)
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


def _same_observation(
    before: Observation | None,
    after: Observation | None,
) -> bool:
    if before is None or after is None:
        return False
    return (
        before.package_name,
        before.activity_name,
        before.xml,
        before.image_base64,
    ) == (
        after.package_name,
        after.activity_name,
        after.xml,
        after.image_base64,
    )


def _optional_step_index(value: Any) -> int | None:
    try:
        step_index = int(value)
    except (TypeError, ValueError):
        return None
    return step_index if step_index >= 0 else None


async def _await(value: Any) -> Any:
    return await value if inspect.isawaitable(value) else value


def _take_planner_metadata(planner: Planner) -> dict[str, Any]:
    take_metadata = getattr(planner, "take_metadata", None)
    if not callable(take_metadata):
        return {}
    value = take_metadata()
    return dict(value) if isinstance(value, dict) else {}


def _merge_planner_diagnostics(
    diagnostics: dict[str, Any],
    metadata: dict[str, Any],
) -> None:
    rejected_calls = metadata.get("rejected_tool_calls")
    if not isinstance(rejected_calls, list):
        return
    accumulated = diagnostics.setdefault("rejected_tool_calls", [])
    for value in rejected_calls:
        if not isinstance(value, dict):
            continue
        error = str(value.get("error") or "").strip()
        if not error:
            continue
        item: dict[str, Any] = {"error": error}
        try:
            turn_index = int(value.get("turn_index"))
        except (TypeError, ValueError):
            turn_index = -1
        if turn_index >= 0:
            item["turn_index"] = turn_index
        tool = str(value.get("tool") or "").strip()
        if tool:
            item["tool"] = tool
        if "arguments" in value:
            item["arguments"] = value.get("arguments")
        if item not in accumulated:
            accumulated.append(item)


def _take_llm_usage(component: Any) -> dict[str, Any] | None:
    take_usage = getattr(component, "take_usage", None)
    if not callable(take_usage):
        return None
    value = take_usage()
    return dict(value) if isinstance(value, dict) else {}


def _usage_model_calls(
    usage: dict[str, Any] | None,
    *,
    fallback: int,
) -> int:
    if usage is None:
        return max(0, int(fallback))
    try:
        return max(0, int(usage.get("model_calls") or 0))
    except (TypeError, ValueError):
        return max(0, int(fallback))


async def _request_input(host: Host, question: str) -> str:
    request_input = getattr(host, "request_input", None)
    if not callable(request_input):
        raise RuntimeError("request_input_not_supported")
    return str(await _await(request_input(question)))
