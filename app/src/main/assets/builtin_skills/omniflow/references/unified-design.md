# Unified Function Replay Design

Use this reference when choosing the main OmniFlow Function path or explaining
why old replay concepts should not be reintroduced.

## Main Path

```text
RunLog -> Function -> recall candidates -> agent/VLM chooses oob_function_run
  -> guard -> deterministic replay step by step
  -> fallback_context on failure
  -> agent repairs or resumes with update_function / oob_function_run
```

`Function.steps` is the only replay sequence. The runner is a deterministic
replay enhancer, not a planner. It re-grounds and executes each explicit step in
order. It does not maintain a hidden pending queue and does not skip middle
steps because a later page state appears satisfied.

Use one vocabulary everywhere: Function, RunLog, recall, replay, checker,
fallback, and update. Old Command wording and `call_function` names are
compatibility only.

Internal graph edges for callable Functions use `kind=function_call`; old
`call_function` edge inputs are compatibility only.

Every executable Function step should have an agent-useful title/summary,
canonical action, arguments, and target hints when available. Step annotation is
used by recall, VLM guidance, RunLog evidence analysis, and `update_function`.

## Tool Names

Use these names first:

- `oob_function_run`
- `oob_function_guard_check`
- `update_function`
- `oob_run_log_convert`
- `oob_function_list`
- `oob_function_get`

Legacy names such as `call_function`, `run_function`, and
`omniflow.call_function` are compatibility aliases only. They may be parsed from
old RunLogs or old MCP clients, but they are not the model-visible replay tool.

## Recall

Recall is local candidate retrieval. It should usually take milliseconds to tens
of milliseconds. It writes candidate Functions into current-page context or
guidance. It does not call the VLM model and does not execute a Function.

Parameterized Function candidates are valid. Read `inputSchema`,
`function_profile`, and `argument_policy`, then fill arguments from the user
goal like a normal tool call before `oob_function_run`.

Recall finds candidates and writes guidance. The agent/VLM decides whether a
candidate matches. `oob_function_run` executes only after that decision.

## Fallback

If replay fails, return or use `fallback_context`, `failed_step_index`,
`resume_from_step`, and `remaining_steps`. The agent can continue with bounded
live VLM or call `update_function` with RunLog evidence. Do not silently fall
back inside offline replay.

## update_function

`update_function` is the only saved Function mutation path.

- `update_function({functionId, run_id})` packages Function + RunLog evidence
  and returns `needs_agent_analysis=true`, `analysis_context`, and
  `agent_prompt`.
- `update_function({functionId, run_id, analysis, patch})` saves agent analysis
  metadata and applies safe patches.

The Kotlin side packages evidence and applies patches. The analysis skill owns
failure/success reasoning.

Use the same path for offline enhancement and user corrections such as
`应该点「外卖」而不是点「美食」`. Translate natural language into structured
analysis plus minimal patch before saving.

## Cleanup Rules

- Ads, skip buttons, permission nudges, keyboard blockers, and close buttons are
  optional checkers, not required happy-path steps.
- `wait`, pure perception wrappers, failed action cards, and duplicate inputs
  are noise unless they carry specific evidence.
- Success RunLogs may improve descriptions, step labels, selector hints, and
  parameter metadata.
- Failed RunLogs may only patch steps with clear evidence.

## Do Not Reintroduce

- `PendingActionStack`
- `pending_action_stack`
- `source_alignment_enabled`
- `skipped_by_source_alignment_count`
- automatic step skipping
- semantic/navigation recovery as a parallel path
- `navigate_recovery_available`
- `blocked_executor`
- `fallback_available`
- generic `needs_agent` as a replay-state shortcut
- `omniflow_vlm_fallback` as a first-class executor/state

`needs_agent` can still appear as a guard decision or legacy compatibility
value. It should not become a hidden runtime queue or planner state.

New Function specs write `has_agent_steps`. `requires_agent_fallback` is legacy
input only and should be normalized before it reaches agent-facing output.
