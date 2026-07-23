from __future__ import annotations

import asyncio
from dataclasses import dataclass, field

import pytest

from omniflow.checker import (
    default_checker,
    default_checker_trigger,
    match_checker_rule,
    validate_checker_rule,
)
from omniflow.config import PluginSet
from omniflow.execute import execute_action, trace_step
from omniflow.model import (
    Action,
    ActionResult,
    CheckerContext,
    Observation,
    TransferResult,
    Function,
)


def _state(
    package_name: str,
    *,
    xml: str = '<hierarchy width="100" height="200" />',
    state_id: str = "state",
) -> Observation:
    return Observation(
        xml=xml,
        package_name=package_name,
        extra={
            "state_id": state_id,
            "display": {"width": 100, "height": 200},
        },
    )


@dataclass
class SequencedHost:
    observations: list[Observation]
    action_results: list[ActionResult] = field(default_factory=list)
    actions: list[Action] = field(default_factory=list)
    states: dict[str, Observation] = field(default_factory=dict)
    loaded_state_ids: list[str] = field(default_factory=list)

    def act(self, action: Action) -> ActionResult:
        self.actions.append(action)
        if self.action_results:
            return self.action_results.pop(0)
        return ActionResult(True)

    def observe(self, **_kwargs) -> Observation:
        return self.observations.pop(0)

    def get_state(self, state_id: str) -> Observation:
        self.loaded_state_ids.append(state_id)
        return self.states[state_id]


@pytest.fixture
def action_settle_delays(monkeypatch) -> list[float]:
    delays: list[float] = []

    async def record_delay(seconds: float) -> None:
        delays.append(seconds)

    monkeypatch.setattr("omniflow.execute.asyncio.sleep", record_delay)
    return delays


def _rule(trigger: str = 'text_contains("跳过广告")') -> dict:
    return {
        "schema_version": "omniflow.checker_rule.v1",
        "trigger": trigger,
        "source_state_id": "checker-source",
        "action": {"tool": "click", "args": {"x": 900, "y": 100}},
    }


def test_learned_checker_matches_restricted_python_trigger() -> None:
    current = _state(
        "com.example",
        xml='<hierarchy><node text="跳过广告" /></hierarchy>',
    )

    recovery = match_checker_rule(
        CheckerContext(None, current, Action("wait", {"duration_ms": 1000})),
        [_rule()],
    )

    assert recovery is not None
    assert recovery.source_state_id == "checker-source"
    assert recovery.action == Action("click", {"x": 900, "y": 100})


def test_learned_checker_rejects_arbitrary_python() -> None:
    with pytest.raises(ValueError, match="checker_trigger"):
        validate_checker_rule(_rule('__import__("os").system("id")'))


def test_checker_reopens_recorded_source_app() -> None:
    context = CheckerContext(
        source=_state("com.example.source"),
        current=_state("com.example.other"),
        action=Action("click", {"x": 500, "y": 500}),
    )
    recovery = default_checker(context)

    assert recovery == Action(
        "open_app",
        {"package_name": "com.example.source"},
    )
    assert default_checker_trigger(context, recovery) == 'package_is("com.example.other")'


def test_checker_does_not_leave_permission_ui() -> None:
    recovery = default_checker(
        CheckerContext(
            source=_state("com.example.source"),
            current=_state("com.android.permissioncontroller"),
            action=Action("click", {"x": 500, "y": 500}),
        )
    )

    assert recovery is None


def test_checker_clicks_explicit_ad_close_parent() -> None:
    xml = (
        '<hierarchy width="100" height="200">'
        '<node text="广告" bounds="[0,0][100,200]">'
        '<node clickable="true" enabled="true" bounds="[80,10][100,30]">'
        '<node text="跳过广告" bounds="[82,12][98,28]" />'
        "</node></node></hierarchy>"
    )
    recovery = default_checker(
        CheckerContext(
            source=_state("com.example", xml=xml),
            current=_state("com.example", xml=xml),
            action=Action("click", {"x": 200, "y": 500}),
        )
    )

    assert recovery == Action(
        "click",
        {
            "target_description": "关闭广告",
            "x": 900.0,
            "y": 100.0,
        },
    )
    assert default_checker_trigger(
        CheckerContext(
            source=_state("com.example", xml=xml),
            current=_state("com.example", xml=xml),
            action=Action("click", {"x": 200, "y": 500}),
        ),
        recovery,
    ) == 'xml_contains("跳过广告")'


def test_checker_ignores_unrelated_close_button() -> None:
    xml = (
        '<hierarchy width="100" height="200">'
        '<node text="关闭" clickable="true" bounds="[80,10][100,30]" />'
        "</hierarchy>"
    )

    recovery = default_checker(
        CheckerContext(
            source=_state("com.example", xml=xml),
            current=_state("com.example", xml=xml),
            action=Action("wait", {"duration_ms": 1000}),
        )
    )

    assert recovery is None


def test_recovery_observes_again_before_transfer_and_original_action(
    action_settle_delays: list[float],
) -> None:
    source = _state("com.example.source", state_id="source")
    current = _state("com.example.other", state_id="current")
    refreshed = _state("com.example.source", state_id="refreshed")
    final = _state("com.example.source", state_id="final")
    host = SequencedHost([refreshed, final])
    transfer_calls: list[tuple[Action, Observation, Observation | None]] = []

    def transfer(
        action: Action,
        observation: Observation,
        source_state: Observation | None,
    ) -> TransferResult:
        transfer_calls.append((action, observation, source_state))
        return TransferResult(Action("click", {"x": 300, "y": 400}))

    step = asyncio.run(
        execute_action(
            Action("click", {"x": 500, "y": 500}),
            observation=current,
            source_state=source,
            host=host,
            plugins=PluginSet(checker=default_checker, transfer=transfer),
        )
    )

    assert step.success is True
    assert step.actions_executed == 2
    assert host.actions == [
        Action("open_app", {"package_name": "com.example.source"}),
        Action("click", {"x": 300, "y": 400}),
    ]
    assert transfer_calls == [
        (Action("click", {"x": 500, "y": 500}), refreshed, source)
    ]
    assert [item.origin for item in step.executed_steps] == ["checker", "action"]
    assert [
        trace_step(item, index)["metadata"]["origin"]
        for index, item in enumerate(step.executed_steps)
    ] == ["checker", "action"]
    assert trace_step(step.executed_steps[0], 0)["metadata"]["checker_trigger"] == (
        'package_is("com.example.other")'
    )
    assert action_settle_delays == [0.5, 0.5]


def test_learned_checker_transfers_recovery_then_retries_original_action(
    action_settle_delays: list[float],
) -> None:
    checker_source = _state("com.example", state_id="checker-source")
    original_source = _state("com.example", state_id="original-source")
    current = _state(
        "com.example",
        xml='<hierarchy><node text="跳过广告" /></hierarchy>',
        state_id="current",
    )
    refreshed = _state("com.example", state_id="refreshed")
    final = _state("com.example", state_id="final")
    host = SequencedHost(
        [refreshed, final],
        states={"checker-source": checker_source},
    )
    transfer_calls: list[tuple[Action, Observation, Observation | None]] = []

    def transfer(
        action: Action,
        observation: Observation,
        source_state: Observation | None,
    ) -> TransferResult:
        transfer_calls.append((action, observation, source_state))
        if source_state is checker_source:
            return TransferResult(Action("click", {"x": 850, "y": 90}))
        return TransferResult(Action("click", {"x": 300, "y": 400}))

    function = Function.from_dict(
        {
            "schema_version": "omniflow.function.v2",
            "function_id": "learned_recovery",
            "name": "Learned recovery",
            "description": "Recover then continue.",
            "input_schema": {
                "type": "object",
                "properties": {},
                "required": [],
                "additionalProperties": False,
            },
            "bindings": [],
            "steps": [],
            "checker_rules": [_rule()],
            "agent_visible": False,
        }
    )

    step = asyncio.run(
        execute_action(
            Action("click", {"x": 500, "y": 500}),
            observation=current,
            source_state=original_source,
            host=host,
            plugins=PluginSet(checker=default_checker, transfer=transfer),
            function=function,
        )
    )

    assert step.success is True
    assert host.loaded_state_ids == ["checker-source"]
    assert host.actions == [
        Action("click", {"x": 850, "y": 90}),
        Action("click", {"x": 300, "y": 400}),
    ]
    assert transfer_calls == [
        (Action("click", {"x": 900, "y": 100}), current, checker_source),
        (Action("click", {"x": 500, "y": 500}), refreshed, original_source),
    ]
    assert step.executed_steps[0].checker_trigger == 'text_contains("跳过广告")'
    assert action_settle_delays == [0.5, 0.5]


def test_failed_recovery_does_not_execute_original_action(
    action_settle_delays: list[float],
) -> None:
    source = _state("com.example.source")
    current = _state("com.example.other")
    host = SequencedHost(
        [],
        action_results=[ActionResult(False, error="launch_failed")],
    )

    step = asyncio.run(
        execute_action(
            Action("wait", {"duration_ms": 1000}),
            observation=current,
            source_state=source,
            host=host,
            plugins=PluginSet(
                checker=default_checker,
                transfer=lambda action, _current, _source: TransferResult(action),
            ),
        )
    )

    assert step.success is False
    assert step.error == "launch_failed"
    assert host.actions == [
        Action("open_app", {"package_name": "com.example.source"})
    ]
    assert [item.origin for item in step.executed_steps] == ["checker"]
    assert action_settle_delays == [0.5]
