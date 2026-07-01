# RunLog Contract

## Data Shape

RunLog storage has two layers:

- Event log: append-only NDJSON with `schema_version = oob.run_log_event.v1`.
- Snapshot: compact JSON projection used by timeline/list APIs.

The snapshot is a cache. Reconstruct a complete run by applying events with
`event_seq` greater than the snapshot sequence.

RunLog cards should preserve:

- tool name
- tool arguments
- result payload
- status/success fields
- before-state XML when coordinate remap is possible
- screenshot path and package evidence when XML is unavailable

Reusable Function specs use:

- `schema_version = oob.reusable_function.v1`
- `function_id`
- `name`
- `description`
- `parameters`
- `source.kind = run_log`
- `execution.kind = tool_sequence`
- `execution.steps[*].tool`
- `execution.steps[*].args`
- `execution.steps[*].source_context`

Do not require a separate skill manifest, script wrapper, runtime target
wrapper, or external replay policy artifact for RunLog-derived Functions.

## Execution Contract

Function replay lowers each deterministic step to one canonical action and
executes it through `ActionExecutor.act`.

Allowed local replay actions are the canonical OOB action schema names, including
`click`, `long_press`, `input_text`, `swipe`, `open_app`, `press_key`, `wait`,
and `finished`.

`call_tool(function_id=...)` is an internal Function-call bridge. Generic
`call_tool` without a Function id is not a replay step.

## Source Context

`source_context.src_ctx.page` is the preferred XML source for action transfer.
Manual recording may fall back to coordinate-only context with package and
screenshot evidence. Keep degraded evidence visible in diagnostics instead of
silently dropping the step.

## Failure Modes To Guard

- Wrapper card becomes the replay step even though concrete UI actions exist.
- Failed local action is treated as successful replay evidence.
- Old action aliases are emitted as final replay tools.
- Source XML is missing but diagnostics hide that action transfer was skipped.
- Flutter or an asset file starts compiling replay steps again.
- Function replay bypasses `ActionExecutor.act` for Android UI actions.

## Required Tests

- VLM wrapper plus click keeps only the click as a local replay step.
- `android_privileged_action` local UI wrappers flatten nested `arguments`.
- Failed local actions do not suppress failed-step diagnostics.
- Source context is preserved for coordinate actions.
- Startup package correction is handled by replay checks, not by injected
  compile-time steps.
