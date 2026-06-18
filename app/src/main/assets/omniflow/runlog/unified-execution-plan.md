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

### UI / MethodChannel

Flutter should stay a presentation and request surface:

- Direct Function run buttons call `AssistsMessageService` and then the native
  manager.
- RunLog save/detail/enhancement UI calls the same native conversion/update
  services.
- UI never replays a Function by interpreting steps in Dart.
- Background enhancement jobs are allowed, but they call `update_function`
  through native and remain off the live `vlm_task` path.

### MCP / HTTP / Debug

External and debug entry points should be thin adapters:

- MCP schema lives in `McpToolDefinitions`; dispatch goes through
  `McpToolExecutors`/native toolkit services.
- HTTP/debug receivers should decode arguments, call the same native service,
  and write a result JSON file or response body.
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

The detailed `adb` commands live in
`app/src/main/assets/omniflow/runlog/examples/vlm-task-recall-loop.md`.

Current limitation: if `adb devices` returns no connected device, local tests can
prove conversion, recall policy, UI entry points, and strict-hit delegation, but
they cannot prove Android permission/UI smoke. Do not mark the end-to-end goal
complete until the device smoke above has been run on a real device.

## Follow-Up Work

- Add a small offline Python CLI check that validates exported Function JSON
  against the shared schema, without calling Android runtime actions.
- Keep UI changes minimal and aligned with existing main-branch presentation:
  cards may show status, but execution semantics must stay in the native runtime.
