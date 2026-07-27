# Function Core

This package owns saved Function management and replay.

Keep the model simple:

```text
FunctionService = expose Android storage/model host calls
FunctionRun     = bridge one Function run to Python
Python OmniFlow = recall, compile, enhance, bind, transfer, check, replay
FunctionApi     = external tool names and schemas
```

## Entry Points

`FunctionService.executeTool(...)` is the thin management entry. Python owns Function decisions; Kotlin only exposes RunLog storage, model completion, Android observation, and background lifecycle.

`FunctionRun.runFunction(...)` is an Android adapter. Python loads, validates, binds, transfers, checks, and runs the Function; Kotlin exposes device operations, progress, and RunLog persistence.

## File Roles

- `FunctionApi.kt`: public tool names, tool schemas, and schema export.
- `FunctionService.kt`: thin Android host for Function management; Python decides when enhancement is scheduled.
- `FunctionChannel.kt`: owns the Flutter Function/RunLog/manual-recording channel adapter and delegates replay to `FunctionRun`.
- `FunctionRun.kt`: thin Function request adapter into the shared OmniFlow runtime.
- `omniflow-android/`: owns the single `OmniFlow.run` interface plus internal Python, Android action, RunLog, pause, stop, progress, and overlay implementation.
- `FunctionService.registerFunction`: accepts one canonical `function`; compilation stays in OmniFlow.

## Rules

- Do not add a second executor, runtime, backend, dispatcher, or registry for Function replay.
- Do not call an Android action executor from Function code. All interactive execution enters through `OmniFlow.run`.
- Use the canonical OmniFlow `run_id` as the only execution identity; do not add frontend or controller-specific run/task ids.
- Do not add Kotlin rules that guess user parameters. Parameters must come from explicit Function schema/bindings or agent-produced updates.
- Do not expose internal Function replay as a normal VLM action. Online VLM should output ordinary phone actions only.
- Keep Function storage in workspace JSON. Do not add SharedPreferences fallback or double-write paths.
- Accept canonical tool input in production. Legacy aliases belong only in explicit offline import/migration paths.
- Let OmniFlow bind arguments, validate, and execute every Function step; Kotlin only serves device operations.
- Let OmniFlow decide compile/register/enhance ordering; Kotlin only schedules the requested background operation.
- Store learned `checker_rules` at the Function top level. Offline RunLog enhancement may add only evidence-backed `trigger + source_state_id + action` rules; Python owns trigger evaluation and recovery execution.

## Extension Point

For new platforms or new action kinds, keep Function as recorded callable steps. Add platform-specific execution under the action execution layer, not as a new Function runner.
