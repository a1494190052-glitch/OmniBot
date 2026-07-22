from __future__ import annotations

from dataclasses import dataclass, field

from omniflow.model import (
    Checker,
    Transfer,
)

DEFAULT_PLANNER_SYSTEM_PROMPT = (
    "Continue the user's complete goal from the current screen by choosing exactly "
    "one provided GUI tool. Use 0..1000 relative coordinates. Call finished only "
    "when the complete goal is visibly satisfied. For open_app, use the exact "
    "package_name supplied by the runtime and never guess one. If "
    "screen_context contains previous_action_error, correct that action through "
    "the same normal tool path. Use recent_actions to advance the goal and never "
    "repeat an already successful action on an unchanged screen. Never call a "
    "stored Function directly."
)

@dataclass(frozen=True)
class Experiment:
    name: str = "ours"

    @classmethod
    def for_method(cls, name: str) -> "Experiment":
        return cls(name=str(name or "ours"))


@dataclass(frozen=True)
class PromptSet:
    planner_system: str = DEFAULT_PLANNER_SYSTEM_PROMPT


@dataclass(frozen=True)
class PluginSet:
    checker: Checker | None = None
    transfer: Transfer | None = None


@dataclass(frozen=True)
class RuntimeSettings:
    max_steps: int = 20


@dataclass(frozen=True)
class OmniFlowConfig:
    prompts: PromptSet = field(default_factory=PromptSet)
    runtime: RuntimeSettings = field(default_factory=RuntimeSettings)
    plugins: PluginSet = field(default_factory=PluginSet)

    def resolved_plugins(self) -> PluginSet:
        from omniflow.execute import (
            default_checker,
            default_transfer,
        )
        configured = self.plugins
        return PluginSet(
            checker=configured.checker or default_checker,
            transfer=configured.transfer or default_transfer,
        )
