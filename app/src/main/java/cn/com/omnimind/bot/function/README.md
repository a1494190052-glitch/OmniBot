# Function Core

This package owns saved Function management and replay.

Keep the model simple:

```text
FunctionService = manage Functions
FunctionRun     = run one Function
FunctionStore   = persist workspace JSON
FunctionSchema  = expose read-only Function views
FunctionApi     = external tool names and schemas
```

## Entry Points

`FunctionService.executeTool(...)` is the management-tool adapter. It handles list/get/save/update/delete/clear/recall/convert style calls. It should not become a replay executor.

`FunctionRun.runFunction(...)` executes one saved Function. It loads the Function, prepares arguments, replays steps, reports progress, and records results. UI actions inside a Function must go through `ActionExecutor.act(...)`.

## File Roles

- `FunctionApi.kt`: public tool names, tool schemas, schema export, and lightweight prompt/profile text.
- `FunctionService.kt`: Function management flow: list/get/save/update/delete/clear/recall/convert.
- `FunctionRun.kt`: Function execution flow.
- `FunctionStore.kt`: workspace JSON persistence only.
- `FunctionSchema.kt`: Function IDs, input schemas, source runs, and execution-step views.
- `OmniFlowPythonRuntime`: RunLog conversion, recall, argument materialization, checker policy, and action transfer.
- `FunctionActionEdits.kt`: explicit `delete` / `replace_args` mutations produced by the Function skill.
- `FunctionService.registerFunction`: accepts one canonical `function`; compilation stays in OmniFlow.
- `FunctionFrontendSessionController.kt`: progress/card/session updates for Function runs.
- `FunctionRunLogRecorder.kt`: Function run log persistence.
- `FunctionRunResultBuilder.kt`: result payload construction.
- `FunctionJson.kt`: small JSON helpers.

## Rules

- Do not add a second executor, runtime, backend, dispatcher, or registry for Function replay.
- Do not call `DeviceOperator` directly from Function replay. Use `ActionExecutor.act(...)`.
- Do not add Kotlin rules that guess user parameters. Parameters must come from explicit Function schema/bindings or agent-produced updates.
- Do not expose internal Function replay as a normal VLM action. Online VLM should output ordinary phone actions only.
- Keep Function storage in workspace JSON. Do not add SharedPreferences fallback or double-write paths.
- Accept canonical tool input in production. Legacy aliases belong only in explicit offline import/migration paths.
- Let OmniFlow materialize arguments and validate bindings before Kotlin executes steps.
- Pass Function `metadata.checker_rules` to OmniFlow unchanged; checker normalization and support decisions belong to Python.

## Extension Point

For new platforms or new action kinds, keep Function as recorded callable steps. Add platform-specific execution under the action execution layer, not as a new Function runner.
