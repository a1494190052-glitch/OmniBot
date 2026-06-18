# Unified OOB OmniFlow Execution Plan

This plan keeps the current OOB mainline working without a broad rewrite. The
goal is one execution pipeline with multiple entry surfaces, not multiple phone
executors.

## Current Mainline

1. `vlm_task` runs in Kotlin and performs live Android observation/action through
   accessibility.
2. Successful VLM execution writes an internal RunLog.
3. `OobVlmRunLogAutoRegistrar` converts the successful VLM RunLog into an
   agent-visible reusable Function.
4. A later matching `vlm_task` performs fresh observe, calls native recall, and
   if the recall result is a strict direct hit, runs the Function locally through
   `OobOmniFlowToolkitService.runFunction`.
5. If there is no strict hit, the task falls back to ordinary VLM execution.

Enhancement is deliberately out of this critical path. Auto-registration must
save the replayable Function first; semantic upgrade/enhancement is a later
offline/background step started explicitly from UI, MCP, or HTTP tooling.

## Single Runtime Boundary

Keep in Kotlin/OOB-native:

- Android accessibility and overlay permission checks.
- Fresh page observe, current package, XML, screenshot capture, and page
  stability waiting.
- RunLog begin/append/finish and conversion to reusable Function specs.
- UDEG/page recall, strict-hit selection, runtime argument resolution, and
  replay execution.
- User-facing progress events and stream/tool cards.

Can reuse from OmniFlow Python inside the built-in Alpine environment:

- Function schema compatibility checks and offline validation.
- Provider/MCP dev tools such as `omniflow-provider` and `omniflow-mcp`.
  The current standalone Python MCP server exposes `omniflow.recall` and
  `omniflow.ingest_run_log`; those are cache/ingest/dev tools, not phone action
  executors.
- Evaluation harnesses, fixture generation, export/import linting, and semantic
  enhancement experiments.
- Offline analysis of RunLogs and Functions when no live Android action is
  required.

Do not move to OmniFlow Python:

- Live phone clicking, swiping, text input, app launch, or page observe.
- Runtime replay guards that depend on current Android accessibility state.
- The fast second-run path after `vlm_task` recall.

## Entry Surface Unification

All entry surfaces should call the same native Function facade:

- MCP tool call: `run_function`/Function lifecycle tools call
  `OobOmniFlowToolkitService`.
- HTTP/debug receiver: calls the same toolkit service with the same payload.
- Direct in-app UI action: calls `AssistsMessageService` -> native manager ->
  the same toolkit service.
- `vlm_task` recall fast path: calls `VlmToolCoordinator.tryExecuteRecallHitOnly`
  or `executeNewTask`, which delegates Function execution to the same toolkit
  service.

The shared contract is the Function JSON schema and `{tool,args}` execution
steps. The owner of actual Android execution remains the Kotlin replay runner.

Python OmniFlow compatibility should be one-way for live tasks:

- OOB can export RunLogs/Functions to Python for validation, ingest, recall
  experiments, and dashboard/provider workflows.
- Python can return candidates, diagnostics, or offline patches.
- Python must not call Android accessibility actions directly for the in-app
  `vlm_task` flow. If an external Python tool wants to execute on this phone, it
  must call OOB's native HTTP/MCP/debug surface, which then delegates to
  `OobOmniFlowToolkitService`.
- There is no model-visible `function_id` execution tool in the normal agent
  prompt. Saved Function recall, runtime resolve, and replay stay runtime-owned.

## Compatible Surface Design

The compatibility target is one native execution contract with multiple adapters:

```text
UI / MethodChannel
MCP / HTTP / debug receiver
Python OmniFlow provider or MCP dev tool
        |
        v
OOB native Function facade
        |
        v
OobOmniFlowToolkitService
        |
        +-- OobFunctionRepository        # Function storage/index
        +-- OobFunctionRecallService     # page/node recall and hit policy
        +-- OobFunctionRunner            # Android replay through Kotlin
        +-- OobFunctionUpdateService     # offline update_function patches
        +-- OobRunLogReplayService       # RunLog -> Function conversion
```

The adapter may differ, but the payload must not:

- Function identity: `function_id`.
- Goal/context: `goal`, `packageName`/`current_package`, current page XML when
  available, and optional public runtime arguments.
- Execution switch: `auto_execute`/`allowOmniFlowFunctionAutoExecute` is only a
  request to the native runtime. It does not make a model-visible Function tool.
- Enhancement switch: `mode=enhance` or `update_function` is offline
  maintenance. It may inspect RunLog evidence and save metadata patches, but it
  must never call live Android actions.
- Result envelope: `success`, `function_id`, `execution_route`,
  `execution_summary`, `run_id`, and explicit error fields. UI cards, MCP
  responses, and debug result files should all project from this envelope.

## Adapter Matrix

This matrix is the implementation boundary for avoiding two execution systems:

| Surface | Allowed Input | Owner | May Execute Phone Actions | Notes |
| --- | --- | --- | --- | --- |
| `vlm_task` | natural-language goal, optional package, max steps | `VlmToolCoordinator` | yes, through Kotlin only | Performs fresh observe, native recall, strict-hit replay, or ordinary VLM action loop. |
| UI Function run | concrete `function_id`, public arguments | Flutter -> native manager -> `OobOmniFlowToolkitService` | yes, through Kotlin only | Flutter must never interpret Function steps or call Android actions itself. |
| MCP Function tools | concrete Function lifecycle payloads | `McpToolExecutors` -> `OobOmniFlowToolkitService` | yes, only after native dispatch | The MCP layer is an adapter; it must not own replay semantics. |
| HTTP/debug Function run | concrete debug payloads | debug receiver -> `OobOmniFlowToolkitService` | yes, through Kotlin only | Useful for smoke tests and diagnostics. It should not become a separate product mode. |
| `RUN_VLM_RECALL_HIT` | natural-language goal for strict-hit validation | debug receiver -> `VlmToolCoordinator.tryExecuteRecallHitOnly` | yes, through Kotlin only | Exists to isolate the second-run fast path without starting a fresh ordinary VLM loop. |
| `update_function` / enhance | concrete `function_id`, RunLog evidence, patch | `OobFunctionUpdateService` | no | Offline maintenance only. It may edit metadata/labels/checkers, but must not replay. |
| Python `omniflow-mcp` in Alpine | `omniflow.recall`, `omniflow.ingest_run_log` | OmniFlow Python provider/toolchain | no for OOB phone runtime | The upstream standalone MCP server exposes recall/ingest cache tools only; OOB phone execution still goes through native surfaces. |
| Python AndroidWorld action sequence | benchmark/eval manifests | OmniFlow Python experiments | no for OOB app runtime | Useful reference for fixtures/evaluation. Do not wire it into OOB's live accessibility path. |

Concrete rule: a caller may be UI, MCP, HTTP, debug, or Python, but live phone
execution must eventually enter the same native facade and Kotlin replay runner.
If a proposed change adds another component that can click, swipe, input text,
launch apps, observe XML, or decide strict direct execution outside this chain,
it is creating the second execution system this plan rejects.

## Call Shapes

Use these as the compatibility target for new adapters:

```json
{
  "tool": "vlm_task",
  "goal": "打开网络设置",
  "packageName": "com.android.settings",
  "allowOmniFlowFunctionAutoExecute": true
}
```

`vlm_task` may auto-register a successful RunLog and may short-circuit through a
strict recall hit on a later run. The model should still see only ordinary UI
actions if execution reaches the VLM action loop.

```json
{
  "tool": "run_function",
  "function_id": "open_network_settings",
  "arguments": {},
  "goal": "打开网络设置"
}
```

Direct Function calls require a concrete `function_id`. They are valid for UI
buttons, MCP tools, and debug tools, but they do not search by natural-language
goal and do not mutate the saved Function.

```json
{
  "tool": "update_function",
  "function_id": "open_network_settings",
  "mode": "enhance",
  "offline_job": true,
  "background_enhancement": true,
  "auto_analyze_with_model": true,
  "run_id": "vlm-run-123",
  "analysis": {},
  "patch": {}
}
```

Enhancement is offline. It may produce a better description, public parameters,
step labels, checker metadata, or repair patches when explicitly requested. It
must not block auto-registration, second-run recall, or replay. A bare
`update_function(function_id, run_id, mode=enhance)` returns the analysis
context and prompt only; model-backed enhancement requires an explicit
`offline_job=true` plus `auto_analyze_with_model=true` request from a
background job, debug update, or offline tool. Passing only one of those flags
still returns the offline analysis context without invoking the model.
The UI background enhancement job also sets `background_enhancement=true`; that
route uses the native stepwise offline enhancer for small description and
parameter passes, while ordinary MCP/debug `update_function` calls keep the
existing evidence/patch contract.

For local MCP/debug tooling, prefer the same payload shape:

```json
{
  "tool": "update_function",
  "function_id": "open_network_settings",
  "run_id": "vlm-run-123",
  "mode": "enhance",
  "offline_job": true,
  "background_enhancement": true,
  "auto_analyze_with_model": true
}
```

## Shared Acceptance Gates

Before a PR claims this mainline is working, verify each gate with matching
evidence:

- Manual recording: a visible `录制轨迹` entry starts recording, completes from
  the floating assistant, and returns a RunLog plus reusable Function result.
  Unit tests only prove command routing; a real Android device proves this gate.
- First VLM run: `vlm_task` succeeds, writes an Internal RunLog, and conversion
  registers an agent-visible Function with
  `metadata.enhancement_policy=offline_only`.
- Second VLM run: from the same or equivalent page, native recall returns a
  strict hit and `VlmToolCoordinator` runs `OobOmniFlowToolkitService.runFunction`
  once, producing an `executionRoute` that starts with `omniflow_recall_hit`.
- No inline enhancement: no VLM auto-registration, recall-hit path, direct
  Function run, or debug replay path should call `update_function` before
  replay. Explicit UI/background/MCP update requests are the only enhancement
  entry.
- Python compatibility: Alpine-installed OmniFlow may validate schemas, run
  `omniflow-provider`, expose `omniflow.recall` and
  `omniflow.ingest_run_log`, and generate offline patches. It must call OOB's
  native HTTP/MCP/debug surface if it wants this phone to execute anything.
- UI stability: Function library, memory/reuse entries, chat input trajectory
  menu, and stream/tool cards continue to render with main-branch style and
  route into native services rather than Dart-side replay.
- Localization/product wording: ARB/l10n user-visible labels keep
  `复用指令` as the product name and keep the offline enhancement hint. User
  input such as `复用记忆` is accepted only as compatibility wording in routing
  and builtin skills; it should not replace the displayed product label.

## VLM Accuracy Measurement

Measure `vlm_task` quality at two levels. Do not use one narrow metric as proof
that the whole loop works.

Online device metrics come from OOB native RunLog/debug result envelopes:

- Task success rate: `success=true` and final RunLog status is successful.
- Action success rate: each executed action card reports a successful dispatch
  or a structured failure reason.
- First-run registration rate: successful `vlm_task` RunLogs convert into an
  agent-visible Function with `metadata.enhancement_policy=offline_only`.
- Recall hit rate: second run from an equivalent page returns
  `recall.decision=hit`.
- Fast-path execution rate: second `vlm_task` or `RUN_VLM_RECALL_HIT` produces
  `executionRoute` starting with `omniflow_recall_hit`.
- Latency split: track observe, prompt build, VLM stream, parse, action dispatch,
  conversion/register, recall, and replay from the existing diagnostics fields.

Offline compatibility metrics may use OmniFlow Python:

- Schema validity of exported RunLog/Function JSON through `schemas.py`,
  `src/utg/core/oob_contract.py`, or equivalent schema loaders.
- Recall top-1 / recall@k / margin on exported Function pools and fixed goal
  fixtures.
- Page-match and action-transfer accuracy using the AndroidWorld/vector
  harnesses such as `tests/vector/benchmarks/page_match_accuracy.py`,
  `tests/vector/benchmarks/action_accuracy.py`, and
  `scripts/runlog_reuse_benchmark.py`.
- Fixture regression for generated `vlm_task` examples without touching Android
  accessibility. The machine-readable example is
  `app/src/main/assets/omniflow/runlog/examples/vlm-task-recall-loop.json`;
  the adb smoke walkthrough is
  `app/src/main/assets/omniflow/runlog/examples/vlm-task-recall-loop.md`.

The merge gate is a pair:

1. Offline Python eval proves schema/recall/action-transfer compatibility on
   exported fixtures.
2. Real-device OOB smoke proves native Android execution, RunLog registration,
   strict recall hit, and fast replay.

Python eval can explain why recall or transfer failed; it cannot certify live
phone execution by itself.

### UI / MethodChannel

Flutter should stay a presentation and request surface:

- Direct Function run buttons call `AssistsMessageService` and then the native
  manager.
- RunLog save/detail/enhancement UI calls the same native conversion/update
  services.
- UI never replays a Function by interpreting steps in Dart.
- Background enhancement jobs are allowed, but they call `update_function`
  through native and remain off the live `vlm_task` path.
- A running background enhancement job must not disable direct replay of the
  already registered Function. Replay uses the currently saved Function as-is;
  enhancement completion only affects future metadata/label reuse.

### MCP / HTTP / Debug

External and debug entry points should be thin adapters:

- MCP schema lives in `McpToolDefinitions`; dispatch goes through
  `McpToolExecutors`/native toolkit services.
- HTTP/debug receivers should decode arguments, call the same native service,
  and write a result JSON file or response body.
- Debug HTTP exposes `/omniflow/tool` for Function lifecycle tools and
  `/omniflow/function/run` for concrete `function_id` execution. Both routes
  delegate to `OobOmniFlowToolkitService`; `/act` remains only a single-step
  action endpoint for recorder/debug tooling.
- Diagnostic direct Function execution is permitted only when the caller gives a
  concrete `function_id`; ordinary user goals should still enter through
  `vlm_task`, so recall and replay remain runtime-owned.
- `RUN_VLM_RECALL_HIT` exists only to test the strict-hit native path; it should
  not become another product execution mode.

### Python OmniFlow In Alpine

The Python package can be installed in the built-in Alpine environment as
`omniflow-provider`/`omniflow-mcp`, but its role is deliberately offline or
provider-side:

- Keep: schema validation, `oob_contract.py` replay-policy checks, fixture
  generation, offline recall experiments, `omniflow.recall`, and
  `omniflow.ingest_run_log`.
- Keep: provider dashboards, cache inspection, exported RunLog/Function linting,
  and semantic enhancement experiments that return patches.
- Do not use for: Android accessibility actions, overlay permission checks,
  current screen observation, runtime parameter resolve against live XML, or the
  fast second-run path.
- If Python needs to execute on this phone for a developer workflow, it must call
  OOB's native MCP/HTTP/debug surface. Native Kotlin still performs the action.

### Direct Function Calls

Direct calls are useful for debug and UI buttons, but they must be scoped:

- Input is `function_id` plus public arguments, not a natural-language goal.
- The call loads the saved Function through `OobFunctionRepository`.
- Replay happens through `OobFunctionRunner` and Kotlin action codecs.
- Failure returns a structured result and does not silently fall back to a fresh
  VLM loop. The caller may decide to start a new `vlm_task` after inspecting the
  failure.
- Direct calls should not mutate the Function. Repairs use `update_function`
  separately and remain offline.

## Bugs Fixed In This Pass

- The visible recording shortcut label `录制轨迹` is now accepted as a manual
  recording command instead of falling back to normal chat.
- VLM RunLog auto-registration no longer runs `update_function`/`enhance`
  inline after registration.
- Agent-visible auto-registered Functions now carry
  `metadata.enhancement_policy=offline_only`.
- Function details show a localized hint that direct replay is ready while
  semantic upgrade is offline/background.
- Frontend routing and builtin skills accept `复用记忆` as compatibility wording,
  while localized UI continues to display `复用指令`.
- Debug builds now expose `RUN_VLM_RECALL_HIT`, which directly exercises
  `VlmToolCoordinator.tryExecuteRecallHitOnly` and writes
  `debug-vlm-recall-hit-result.json` for faster device validation.

## Next Test Plan

Local unit/widget tests:

- Manual recording command alias test.
- RunLog detail/enhancement UI tests.
- VLM auto-register conversion and recall tests.
- VLM coordinator recall execution tests.
- Drawer entry tests for Project, Memory Center, Function Library, and scheduled
  section state.

Required local commands before PR handoff:

```bash
./gradlew --no-daemon :app:testDevelopStandardDebugUnitTest \
  --tests cn.com.omnimind.bot.runlog.ManualRecordingRunLogRecoveryTest \
  --tests cn.com.omnimind.bot.runlog.OobVlmRunLogAutoRegistrarTest \
  --tests cn.com.omnimind.bot.vlm.VlmToolCoordinatorRecallExecutionTest

cd ui
"/Users/wuzewen/Desktop/项目与代码/flutter/bin/flutter" test --no-pub \
  test/features/home/widgets/home_drawer_test.dart \
  test/features/task/pages/execution_history/function_library_page_test.dart \
  test/features/home/pages/settings/workspace_memory_setting_page_test.dart \
  test/features/home/pages/chat/utils/omniflow_tool_profile_router_test.dart \
  test/features/home/pages/command_overlay/services/manual_recording_flow_controller_test.dart \
  test/features/home/pages/command_overlay/widgets/chat_input_area_test.dart \
  test/widgets/manual_recording_result_card_test.dart \
  test/features/task/pages/execution_history/run_log_timeline_page_test.dart
```

Device smoke tests, requiring a connected Android device:

1. Install a `develop` debug build.
2. Enable accessibility and overlay permissions.
3. Start a manual recording from the visible `录制轨迹` shortcut and complete it
   from the floating assistant.
4. Confirm the result card reports a RunLog and reusable Function.
5. Run the first VLM debug task, confirm RunLog success and Function conversion.
6. Run recall from the same page with `auto_execute=true`.
7. Run `RUN_VLM_RECALL_HIT` to isolate the strict-hit native replay path.
8. Run the second VLM debug task and confirm `executionRoute` starts with
   `omniflow_recall_hit`.

For the VLM loop, prefer the strict checked harness:

```bash
scripts/oob-vlm-recall-loop-smoke.sh --device <serial> --goal "打开网络设置"
```

For the manual recording loop, prefer the strict checked harness:

```bash
scripts/oob-manual-recording-function-smoke.sh --device <serial>
```

The detailed `adb` commands live in
`app/src/main/assets/omniflow/runlog/examples/vlm-task-recall-loop.md`; the
same call-shape contract is mirrored in the JSON fixture
`app/src/main/assets/omniflow/runlog/examples/vlm-task-recall-loop.json`.

Current limitation: if `adb devices` returns no connected device, local tests can
prove conversion, recall policy, UI entry points, and strict-hit delegation, but
they cannot prove Android permission/UI smoke. Do not mark the end-to-end goal
complete until the device smoke above has been run on a real device.

## Follow-Up Work

- Add a small offline Python CLI check that validates exported Function JSON
  against the shared schema, without calling Android runtime actions. The first
  checked version is `scripts/oob-omniflow-python-offline-contract-smoke.py`;
  it reads the local `~/Projects/Omni/OmniFlow` checkout, verifies standalone
  MCP exposes only `omniflow.recall` / `omniflow.ingest_run_log`, and validates
  the `vlm_task` recall-loop fixture still declares Kotlin as the live runtime
  owner.
- Keep UI changes minimal and aligned with existing main-branch presentation:
  cards may show status, but execution semantics must stay in the native runtime.
