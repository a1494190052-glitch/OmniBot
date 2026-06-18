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

## Follow-Up Work

- Add a small offline Python CLI check that validates exported Function JSON
  against the shared schema, without calling Android runtime actions.
- Keep UI changes minimal and aligned with existing main-branch presentation:
  cards may show status, but execution semantics must stay in the native runtime.
