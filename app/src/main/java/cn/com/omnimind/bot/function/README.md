# Function Core

This package owns saved Function management and replay.

Keep the model simple:

```text
FunctionService = manage Functions
FunctionRun     = bridge one Function run to Python
Python catalog  = validate and persist Function JSON
FunctionApi     = external tool names and schemas
```

## Entry Points

`FunctionService.executeTool(...)` is the management-tool adapter. It handles list/get/save/update/delete/clear/recall/convert style calls. It should not become a replay executor.

`FunctionRun.runFunction(...)` is an Android adapter. Python loads, validates, binds, transfers, checks, and runs the Function; Kotlin exposes device operations, progress, and RunLog persistence.

## File Roles

- `FunctionApi.kt`: public tool names, tool schemas, and schema export.
- `FunctionService.kt`: Function management flow: list/get/save/update/delete/clear/recall/convert.
- `FunctionRun.kt`: Android host, progress, and RunLog adapter for Python execution.
- `OmniFlowPythonRuntime`: RunLog conversion, recall, binding, checker policy, and action transfer.
- `FunctionService.registerFunction`: accepts one canonical `function`; compilation stays in OmniFlow.
- `FunctionFrontendSessionController.kt`: progress/card/session updates for Function runs.

## Rules

- Do not add a second executor, runtime, backend, dispatcher, or registry for Function replay.
- Do not call `DeviceOperator` directly from Function replay. Use `ActionExecutor.act(...)`.
- Do not add Kotlin rules that guess user parameters. Parameters must come from explicit Function schema/bindings or agent-produced updates.
- Do not expose internal Function replay as a normal VLM action. Online VLM should output ordinary phone actions only.
- Keep Function storage in workspace JSON. Do not add SharedPreferences fallback or double-write paths.
- Accept canonical tool input in production. Legacy aliases belong only in explicit offline import/migration paths.
- Let OmniFlow bind arguments, validate, and execute every Function step; Kotlin only serves device operations.
- Store learned `checker_rules` at the Function top level. Offline RunLog enhancement may add only evidence-backed `trigger + source_state_id + action` rules; Python owns trigger evaluation and recovery execution.

## Extension Point

For new platforms or new action kinds, keep Function as recorded callable steps. Add platform-specific execution under the action execution layer, not as a new Function runner.
