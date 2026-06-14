# Unified Function Replay Design

Use this reference when choosing the main OmniFlow Function path or explaining
why old replay concepts should not be reintroduced.

## Main Path

```text
RunLog -> Function -> recall candidates -> runtime chooses high-confidence replay
  -> runtime resolve fills parameters or one current-step GUI action when repair is needed
  -> local runner executes checker/action-transfer/replay
  -> returns success/result
  -> next turn fresh observe decides the next tool
```

`Function.steps` is the only replay sequence. The runner is a deterministic
replay enhancer, not a planner. It re-grounds and executes each explicit step in
order. It does not maintain a hidden pending queue and does not skip middle
steps because a later page state appears satisfied.

Use one vocabulary everywhere: Function, RunLog, recall, replay, checker,
action transfer, update, and runtime execution.

Internal graph edges for callable Functions use `kind=function_call` and execute
through the local Function runner.

Every executable Function step should have an agent-useful title/summary,
canonical action, arguments, and target hints when available. Step annotation is
used by recall, VLM guidance, RunLog evidence analysis, and `update_function`.

## Tool Names

Use these names first for Function lifecycle and online execution:

- `vlm_task`
- `update_function`
- `oob_run_log_convert`
- `oob_function_list`
- `oob_function_get`

Do not expose a separate Function guard/check tool as a normal agent-task
decision. Online Function execution is runtime recall/replay inside `vlm_task`;
new Function specs and agent prompts should not write another model-callable
execution name.

## Recall

Recall is local candidate retrieval. It should usually take milliseconds to tens
of milliseconds. It writes candidate Functions into current-page context or
guidance. It does not call the VLM model and does not execute a Function.

Parameterized Function candidates are valid. The runtime resolves public
business arguments from the user goal before replay.

Recall finds candidates and writes guidance for diagnostics. The local runtime
decides whether a candidate is safe to replay; the VLM does not select or invoke
Function assets directly.

## Repair

If replay fails, runtime resolve may ask the online model for exactly one normal
GUI action for the current failed step. After that action, the runtime observes
again and local checker/action-transfer decides whether replay can resume. Do
not ask the outer Agent to resume hidden replay. If the saved Function is wrong,
call `update_function` later with RunLog evidence.

## update_function

`update_function` is the only saved Function mutation path.

- `update_function({function_id, run_id})` packages Function + RunLog evidence
  and returns `needs_agent_analysis=true`, `analysis_context`, and
  `agent_prompt`.
- `update_function({function_id, run_id, analysis, patch})` saves agent analysis
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
