# OOB OmniFlow Mainline Execution Plan

This note is the engineering plan for keeping the current OOB main flow working
while aligning with OmniFlow Python without creating a second Android executor.
It complements the runtime asset plan in
`app/src/main/assets/omniflow/runlog/unified-execution-plan.md`.

## Current Evidence

Verified in this branch:

- Android build: `./gradlew --no-daemon --no-parallel :app:assembleDevelopStandardDebug -Ptarget=lib/main_standard.dart`
- Recall execution unit gate:
  `./gradlew --no-daemon --no-parallel :app:testDevelopStandardDebugUnitTest --tests cn.com.omnimind.bot.vlm.VlmToolCoordinatorRecallExecutionTest`
- Latest focused unit gates after parse-only fast-path alignment:
  `./gradlew --no-daemon :app:testDevelopStandardDebugUnitTest --tests cn.com.omnimind.bot.vlm.VlmToolCoordinatorRecallExecutionTest`
  and
  `./gradlew --no-daemon :assists:testDebugUnitTest --tests cn.com.omnimind.assists.task.vlmserver.VLMIndexedActionProposerTest --tests cn.com.omnimind.assists.task.vlmserver.VLMIndexedPageContextTest`.
- Real phone manual recording smoke rerun on `ABNU025605001996`:
  `scripts/oob-manual-recording-function-smoke.sh --device ABNU025605001996 --timeout 90`
  passed on 2026-06-19 23:40, recording two gestures, creating RunLog
  `human_1781883644120_c478ed4f`, converting it, and registering Function
  `oob_fn_human_trajectory_c478ed4f`.
- Real phone offline-seeded VLM recall loop rerun on `ABNU025605001996`:
  `runtime/real-device-vlm-recall-loop/20260619T234105-offline-seed-post-parseonly/vlm-accuracy-report.json`
  passed first RunLog registration, native strict recall hit, recall-hit-only
  native replay, second `vlm_task` fast path, and offline-only enhance policy.
- Real phone parse-only indexed fast path:
  `runtime/runlogs/20260619-233911.vlm.parse_only.after_indexed_parse_only.json`
  returned `indexed_action_proposal=matched`, `tool_name=click`,
  `prompt_chars=0`, no `vlm_stream_ms`, and `duration_ms=393` for
  `点击设置搜索框`.
- Real phone manual recording smoke on `ABNU025605001996`:
  `runtime/mainline-acceptance/20260619T225501-real-device-after-tool-budget/manual_recording_device_smoke.log`,
  RunLog `human_1781880940582_cd058af2`, Function
  `oob_fn_human_trajectory_cd058af2`.
- Real phone composed gate:
  `runtime/mainline-acceptance/20260619T225501-real-device-after-tool-budget/mainline-acceptance-report.json`
  passed Android unit, Flutter UI/l10n, OmniFlow Python offline contract,
  manual recording smoke, and VLM recall-loop smoke.
- Real phone online VLM recall loop after tool-budget narrowing:
  `runtime/real-device-vlm-recall-loop/20260619T224919-online-after-tool-budget/vlm-accuracy-report.json`
  passed the same first online VLM execution, RunLog auto-registration, strict
  recall hit, native replay, second fast path, and offline-only enhance policy.
- Latest real-provider parse-only probe:
  `runtime/runlogs/20260619-225120.vlm.parse_only.after_tool_budget.json`
  sent only `click,finished,feedback,abort`, kept `prompt_chars=2161`, returned
  a native `click` tool call, and recorded `vlm_stream_ms=1976`,
  `build_request_ms=5`, and `parse_response_ms=7`.
- Online provider diagnostics now distinguish
  `provider_auth_or_configuration_failed`, `provider_network_failed`,
  `provider_tool_schema_rejected`, and native response contract violations.

Observed online bottlenecks from existing RunLogs:

- Latest focused performance report over real-device online/meituan logs plus
  parse-only fast path still shows ordinary provider calls as the dominant
  latency: `vlm_stream_ms` p50 `2389.5ms`, p95 `3234.1ms`; local observe and
  prompt work p50 `274ms`; `request_build_ms` p50 `7ms`; `parse_response_ms`
  p50 `11ms`.
- The fastest improvement is to avoid provider calls via native recall, current
  page goal completion, and indexed evidence. Prompt slimming is still useful,
  but it is no longer the primary latency lever for the measured path.
- Successful click execution still has a smaller second latency bucket in
  action dispatch/settle delay. Keep this behind page-stability guarantees
  before reducing fixed waits.
- In the latest parse-only probe, provider streaming still dominated the only
  ordinary online model call: local observe/build/parse was about `171ms`,
  while `vlm_stream_ms=1976ms`.
- Aggregate historical RunLogs show the same p50 pattern: online provider
  streaming dominates ordinary online VLM steps when native indexed evidence or
  recall cannot avoid the provider call.
- Historical p95 outliers are mostly `call_tool` / Function replay rows around
  5-6s. Treat those separately from prompt slimming and provider latency.
- Tool-budget narrowing reduced an ordinary click request to 4 model-visible
  tools. Compact guidance reduced the same settings target from historical
  `current_user_text_chars=5244` to `3649`; the latest parse-only request is
  `2161` chars. The remaining ordinary online bottleneck is still provider
  streaming, not local observe, prompt construction, or parsing.
- Parse-only outputs now include request diagnostics, context budget, response
  diagnostics, and phase timing so the performance report can aggregate online
  probes directly.

## Non-Negotiable Runtime Boundary

Keep in Kotlin/OOB native:

- Android observe: package, XML, screenshot, accessibility readiness, overlay.
- Live actions: click, swipe, input text, app launch, key press, wait.
- Manual trajectory recording and RunLog creation.
- VLM online execution loop and native OpenAI tool-call parsing.
- RunLog auto-registration into reusable Functions.
- Strict recall-hit selection, bounded runtime argument resolve, and replay.
- Stream/progress events, UI card payloads, and l10n-facing result envelopes.

Use OmniFlow Python only for offline/dev work:

- Canonical schema validation.
- Fixture generation and AndroidWorld/offline replay evaluation.
- Provider/MCP development tools.
- `omniflow.recall`, `omniflow.ingest_run_log`, and `omniflow.update_function`
  as offline/cache/maintenance tools.
- Semantic enhancement experiments and patch generation.

Do not move to OmniFlow Python:

- OOB in-app `vlm_task` live execution.
- Android accessibility actions.
- Runtime strict-hit replay.
- Manual recording.
- User-facing stream card ownership.

If a Python process wants this phone to execute a Function, it must call OOB's
native MCP/HTTP/debug surface. It must not click the phone directly.

## Reuse From OmniFlow Python Without Duplicating Android Runtime

Use the local checkout at `~/Projects/Omni/OmniFlow` as the offline/reference
implementation for contracts and evaluation, not as an embedded live executor.
Reusable pieces:

- `schemas/oob/oob_canonical_actions.v1.json`: canonical action/tool names and
  parameter contracts. OOB Kotlin/Dart/Python tests should load or compare
  against this schema instead of inventing aliases.
- `omniflow/schemas.py`, `src/utg/core/oob_contract.py`, and
  `src/omniflow/action_schema.py`: offline schema loaders and contract
  validators.
- `src/integrations/mcp_server.py`: reference MCP shape for
  `omniflow.recall`, `omniflow.ingest_run_log`, and offline
  `omniflow.update_function`.
- AndroidWorld/vector scripts and tests: offline accuracy, recall, action
  transfer, and fixture replay evaluation.
- Provider tooling such as `omniflow-provider`: provider diagnostics and
  development outside OOB's live Android executor.

Do not reuse Python modules that own Android action execution for the OOB app
runtime. AndroidWorld native runners and action sequence executors are
benchmark/dev tools. In OOB, live actions must stay behind Kotlin accessibility
services so permissions, overlays, RunLog, stream cards, and user cancellation
remain coherent.

Compatibility rule:

```text
OmniFlow Python validates, scores, recalls, ingests, or proposes patches.
OOB Kotlin observes, decides strict-hit execution, resolves live arguments, and
executes phone actions.
```

## Unified Execution Flow

### `vlm_task`

The canonical runtime flow should remain:

1. Fresh observe current Android page.
2. Build indexed evidence and current page summary.
3. Check native recall guidance.
4. If recall is a strict direct hit:
   - If the Function has an explicit no-argument contract, execute locally with
     `{}` and `resolve_calls=0`.
   - If public arguments are required, run bounded runtime resolve only for
     those public arguments.
   - Execute via `OobOmniFlowToolkitService.runFunction`.
5. If no strict hit, call the online VLM.
6. Online VLM must return exactly one native OpenAI tool call.
7. Convert the native tool call to canonical `{tool,args}` and then to Kotlin
   `UIAction`.
8. Execute the action, append RunLog cards, and auto-register successful runs.

The VLM must not output or see a normal model-visible Function execution tool.
`function_id` is an internal recall/replay field only.

### Manual Recording

The manual recording mainline is:

1. UI/debug starts `DebugHumanRunRecordingReceiver` or the equivalent native
   recording manager.
2. Gestures are captured through the OOB native accessibility/overlay path.
3. Finish creates a human trajectory RunLog.
4. The RunLog converts to a reusable Function.
5. The Function is registered with `metadata.visibility=manual_function` and
   `metadata.enhancement_policy=offline_only`.

The Flutter UI may show and start recording, but it must not interpret or replay
Function steps in Dart.

### Direct Function Run

All direct Function execution surfaces should converge on the same native
facade:

- Flutter/MethodChannel direct run.
- MCP lifecycle tools.
- HTTP/debug Function run.
- `RUN_VLM_RECALL_HIT`.
- `vlm_task` recall fast path.

The shared payload shape is:

```json
{
  "function_id": "<id>",
  "arguments": {},
  "goal": "<optional goal>",
  "packageName": "<optional package>"
}
```

The shared result envelope is:

```json
{
  "success": true,
  "function_id": "<id>",
  "execution_route": "omniflow_recall_hit:<id>",
  "execution_summary": {
    "success": true,
    "steps": 1,
    "resolve_calls": 0,
    "model_calls": 0,
    "tokens": 0
  },
  "run_id": "<optional replay run id>"
}
```

## MCP / HTTP / Direct Function Compatibility

Use adapters, not separate semantics:

| Surface | Input | Owner | Phone actions |
| --- | --- | --- | --- |
| `vlm_task` | goal, package, max steps | `VlmToolCoordinator` | Kotlin only |
| Flutter Function run | `function_id`, public args | native manager -> `OobOmniFlowToolkitService` | Kotlin only |
| MCP tools | lifecycle payloads | MCP adapter -> `OobOmniFlowToolkitService` | Kotlin only after native dispatch |
| HTTP/debug run | debug payload | receiver -> native toolkit | Kotlin only |
| Python `omniflow-mcp` | recall/ingest/update | OmniFlow Python | no live OOB phone action |
| `update_function` / enhance | patch/evidence | native update service or Python offline tool | no replay |

Forbidden online/model-visible execution tools:

- `call_tool`
- `run_function`
- `oob_function_run`
- `omniflow.call_function`
- `omniflow.run_function`
- `omniflow.execute_function`

These names may appear in internal tests or offline migration tools, but not as
ordinary VLM action choices.

### Native Facade Contract

All live-entry adapters should converge on one native facade method family
instead of each surface inventing its own replay path:

```text
UI / MethodChannel
MCP route
HTTP/debug receiver
VLM recall hit
        |
        v
OobOmniFlowToolkitService
        |
        v
OobFunctionRepository -> OobFunctionRunner -> Kotlin ActionExecutor
```

Minimum shared request fields:

- `function_id`: required for direct Function execution and update.
- `arguments`: public arguments as a JSON object; nested under `arguments`, not
  flattened into the top-level payload.
- `goal`: optional user-facing goal for diagnostics and replay summary.
- `packageName` / `current_package`: optional context; never a substitute for
  fresh observe when replay needs page evidence.
- `mode`: only for lifecycle/update tools such as `enhance`; never used to
  smuggle live replay into enhancement.

Minimum shared result fields:

- `success`
- `function_id`
- `execution_route`
- `execution_summary.success`
- `execution_summary.steps`
- `execution_summary.resolve_calls`
- `execution_summary.model_calls`
- `execution_summary.tokens`
- structured `error` / `failure_reason` on failure

Facade invariants:

- Direct Function execution requires a concrete `function_id`.
- Natural-language goals enter through `vlm_task`, not direct replay.
- `update_function` never executes phone actions.
- Python callers that want live execution call the native adapter and receive
  the same result envelope as UI/MCP/debug callers.

## Enhancement Policy

Enhancement is an offline step. It may improve description, public parameters,
labels, checker metadata, or patch suggestions, but it must not block:

- VLM RunLog registration.
- Strict recall-hit execution.
- Direct Function replay.
- Manual recording conversion.

The online mainline saves a replayable Function first. Enhancement can run later
from UI, MCP, HTTP/debug, or Python offline tooling.

## L10n and UI Requirements

Product wording:

- Chinese display label: `复用指令`
- English display label: `Reusable command`
- Compatibility alias: `复用记忆`

`复用记忆` may be accepted as a routing/legacy alias, but visible product copy
should keep `复用指令` so UI, l10n, and stream cards stay consistent.

UI surfaces that must remain available:

- Function library / reusable command library.
- Stream/tool cards that show recall hit and execution summary.
- Chat input trajectory menu, including `录制轨迹`.
- Offline enhancement hint: enhancement does not block VLM auto-registration or
  next-run fast execution.

`vlm_task` user-facing examples should be localized and should not expose
internal action schema details. Required visible examples:

- Chinese: `打开网络设置`, `点击设置搜索框`, `在小红书搜索猫咪`
- English: `Open network settings`, `Tap the Settings search field`,
  `Search cats in Xiaohongshu`

Example documentation and fixtures should show the same call shape:

```json
{
  "tool": "vlm_task",
  "arguments": {
    "goal": "打开网络设置",
    "packageName": "com.android.settings",
    "allowOmniFlowFunctionAutoExecute": true,
    "maxSteps": 3
  }
}
```

The actual VLM action step remains native tool-call only and derives from the
canonical action schema. Examples must not teach `function_id`, `call_tool`, or
legacy text JSON wrappers as model output.

## Acceptance Gates

Before claiming the mainline is release-ready, collect evidence for each gate:

1. Unit gates:
   - `VlmToolCoordinatorRecallExecutionTest`
   - manual recording recovery tests
   - VLM auto-registrar tests
2. Flutter/l10n gates:
   - `ui/test/l10n/app_text_localizer_test.dart`
   - command overlay and Function library tests
   - workspace memory / reusable command surfaces
3. Python offline contract:
   - `scripts/oob-omniflow-python-offline-contract-smoke.py`
4. Device manual recording:
   - `scripts/oob-manual-recording-function-smoke.sh --device <serial>`
5. Device VLM recall loop:
   - `scripts/oob-vlm-recall-loop-smoke.sh --device <serial> --offline-seed`
   - For online accuracy, rerun with `--online-vlm` and a native-tool-call
     provider.
6. Composed gate:
   - `scripts/oob-mainline-acceptance.sh --device <serial>`
   - `--skip-device` is acceptable only for offline/unit/UI contract checks.

Evidence strength:

- Unit tests prove adapters and contracts.
- Flutter tests prove visible routing/l10n surfaces.
- Python offline tests prove schema compatibility and offline recall/eval.
- Real-device manual recording smoke proves the recording path.
- Real-device online VLM recall loop proves provider -> native tool-call ->
  RunLog -> auto-register -> recall -> replay.
- Performance reports prove bottleneck location only for the captured run; they
  are not accuracy evidence by themselves.

## Next Work

Short-term, without broad refactor:

1. Keep the real-phone offline and online smoke paths green after each runtime
   change.
2. Finish native facade convergence with narrow adapter changes:
   - UI direct Function run, MCP lifecycle run, HTTP/debug run, and
     `RUN_VLM_RECALL_HIT` all preserve the shared request/result envelope.
   - Add tests that direct calls require `function_id`, keep nested
     `arguments`, and do not mutate Functions.
   - Add tests that `update_function(mode=enhance)` has
     `may_execute_phone_actions=false`.
3. Keep Python OmniFlow integration as offline/schema/eval/provider tooling:
   - add or keep a Python contract smoke that loads OOB's canonical action
     schema, the `vlm_task` example fixture, and the entry-surface matrix;
   - assert Python MCP exposes recall/ingest/offline update only for OOB live
     execution compatibility.
4. Preserve l10n and UI surface names:
   - visible label remains `复用指令` / `Reusable command`;
   - `复用记忆` remains a routing/legacy alias only;
   - `录制轨迹` stays visible and tested.
5. Continue online request cost trimming only after correctness gates are green:
   - broaden generic indexed evidence coverage before provider calls, with
     negative ambiguity tests for every new matcher;
   - keep parse-only and real execution on the same pre-stream decision path so
     performance reports do not over-count provider bottlenecks;
   - task-aware tool subset;
   - shorter current page summary when indexed evidence is enough;
   - provider-side streaming diagnostics;
   - avoid synthetic local completion cards being counted as extra model calls.
6. Make post-action settle delay conditional, not removed:
   - measure action execution separately from settle delay;
   - use XML/package/page-stability checks before shortening waits;
   - keep a rollback path for pages that animate or load slowly.
7. Keep AndroidWorld as offline breadth evidence:
   - one-case smoke proves import/replay wiring;
   - first30 or named task sets are required before making broad benchmark
     claims.

Current working definition of completion:

- Manual recording smoke passes on a real device and registers a Function.
- `vlm_task -> RunLog -> Function auto/register -> recall hit -> native replay
  -> second vlm_task fast path` passes on a real device.
- Online provider runs either return native tool calls or fail with classified
  provider/contract diagnostics; legacy text-action fallback remains disabled.
- UI surfaces still expose `复用指令` and `录制轨迹`; stream cards derive from the
  native envelope, not Dart-side Function interpretation.
- Python OmniFlow compatibility is covered by offline schema/fixture/contract
  tests and never becomes the OOB live Android executor.
