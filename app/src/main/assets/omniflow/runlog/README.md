# OOB RunLog

OmniFlow is the pipeline from RunLog to reusable Function matching, execution,
UDEG recall, checker handling, and action transfer.
Product-facing behavior is exposed through skills; this contract only defines
the storage, conversion, and replay primitives those skills call. There is no
separate OmniFlow runtime or controller outside the skill system for normal OOB
phone execution. The optional `omniflow_dev` package in the built-in Alpine
environment installs the external OmniFlow Python CLI/provider/MCP tooling for
development, evaluation, asset import/export, and diagnostics; it does not own
or replace native OOB Function replay.

RunLog is a runtime contract, not just a UI feature. Keep these boundaries aligned:

1. Native records tool cards into `InternalRunLogStore`.
2. Flutter displays the timeline and converts cards into a reusable Function.
3. Native stores and materializes reusable Functions through workspace JSON.
4. `OobFunctionToolHandler` replays deterministic local steps and returns failure payloads when replay cannot finish; online recovery belongs to the VLM loop.
5. Workspace Function save must follow the same executor policy as Flutter conversion.

Read `references/runlog-contract.md` before changing conversion or replay behavior.

## Concept Model

RunLog code should preserve these concepts:

- Record: the append-only runtime evidence in `InternalRunLogStore`.
- Card: one recorded observation/tool/action event. Cards may be useful
  evidence, but they are not automatically replay steps.
- Step: the canonical Function execution unit produced by conversion.
- Action: the local device operation inside a deterministic step. The action
  vocabulary and aliases live in the generated
  `baselib/src/main/java/cn/com/omnimind/baselib/runlog/OobActionSchema.kt`
  plus `RunLogActionParser`.
- Action family: reusable groups such as point-target actions also live in
  `OobActionSchema`; replay/update/fallback code should not rebuild their own
  `click`/`long_press` sets.
- Executor: the runtime owner for a step. `RunLogReplayPolicy` classifies
  `omniflow`, `tool`, and `agent`; this is separate from the action name.
- Checker: optional conditional handling for ads, popups, permissions,
  keyboards, package mismatches, and resolver sheets. Checker candidates should
  stay as metadata/evidence unless the user or agent has explicit proof that
  they are required path steps.
- Evidence analysis: agent-authored reasoning stored by `update_function`.
  Analysis may justify labels, summaries, checker candidates, and small
  patches, but Kotlin conversion should not silently infer a new main path from
  weak or failed evidence.
- Source context: replay repair evidence for coordinate actions. XML page
  context is preferred, but manual keyboard flows may emit coordinate-only
  context with screenshot/package evidence and
  `source_context_mode=coordinate_only_no_xml`; keep that as degraded evidence
  instead of dropping the step.

Do not merge code just because two places touch the same string. Merge only
when they own the same concept with the same compatibility rules. Keep
route-specific alias parsing, schema projection, and storage compatibility local
when their semantics differ from replay conversion.

Default home input does not expose RunLog or Function shortcuts. Manual
recording remains available through explicit command routing, such as sending
`录制轨迹` or `Record trajectory`, and through Agent/runtime flows. Do not
restore a persistent action panel or default composer shortcut.

## Storage Model

`InternalRunLogStore` stores every mutation as append-only NDJSON events using
`schema_version=oob.run_log_event.v1`, then writes compact JSON snapshots for
existing timeline APIs. High-frequency running-card updates append events first
and snapshot at terminal boundaries or after a short interval, so live terminal
and browser runs avoid whole-file rewrites on every progress tick.

Readers must treat the JSON snapshot plus later NDJSON events as one logical
record. Do not read only the snapshot when correctness matters.

## Code Map

- Native storage: `baselib/src/main/java/cn/com/omnimind/baselib/runlog/InternalRunLogStore.kt`
- Shared replay policy: `app/src/main/assets/omniflow/runlog/replay_policy.json`
- VLM recall loop example:
  `app/src/main/assets/omniflow/runlog/examples/vlm-task-recall-loop.md`
- Machine-readable VLM call-shape fixture:
  `app/src/main/assets/omniflow/runlog/examples/vlm-task-recall-loop.json`
- Machine-readable entry-surface contract:
  `app/src/main/assets/omniflow/runlog/examples/unified-entry-surfaces.json`
- Reusable Function storage owner: `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionRepository.kt`
- Function payload/value codec: `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionJson.kt`
- Function spec/schema normalization: `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionSchemaBuilder.kt`
- Function update/evidence service: `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionUpdateService.kt`
  This owns structural patching, metadata/evidence patching, and checker patch
  normalization; do not recreate the old split patch-applier classes.
- Function RunLog evidence packager: `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionRunLogEvidencePackager.kt`
- Function target repair source matcher: `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionTargetSourceMatcher.kt`
- Function recall policy: `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionRecallService.kt`
- VLM runtime recall hook:
  `app/src/main/java/cn/com/omnimind/bot/vlm/VlmToolCoordinator.kt`
- VLM recall context contract:
  `assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/VLMRecallContextProvider.kt`
- Function execution runner: `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/OobFunctionToolHandler.kt`
- Canonical in-app Function/RunLog tool names: `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionToolNames.kt`
- Function call timing: `app/src/main/java/cn/com/omnimind/bot/runlog/OobFunctionCallTiming.kt`
- OmniFlow skill profile: `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionSkillProfile.kt`
- Agent-facing tool JSON projection: `app/src/main/java/cn/com/omnimind/bot/agent/AgentToolJson.kt`
- Dynamic Function tool schema builder: `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionSchemaBuilder.kt`
- MCP Function tool schema: `app/src/main/java/cn/com/omnimind/bot/mcp/McpToolDefinitions.kt`
- MCP Function/tool call adapter: `app/src/main/java/cn/com/omnimind/bot/mcp/McpToolExecutors.kt`
- Native timeline and method channel handlers: `app/src/main/java/cn/com/omnimind/bot/manager/AssistsCoreManager.kt`
- RunLog replay step noise normalizer: `app/src/main/java/cn/com/omnimind/bot/runlog/RunLogReplayStepNoiseNormalizer.kt`
- RunLog reusable Function compiler: `app/src/main/java/cn/com/omnimind/bot/runlog/RunLogReusableFunctionCompiler.kt`
- RunLog card-to-step compiler: `app/src/main/java/cn/com/omnimind/bot/runlog/RunLogReplayStepCompiler.kt`
- RunLog card field/JSON accessors: `app/src/main/java/cn/com/omnimind/bot/runlog/RunLogCardAccessors.kt`
- RunLog action/value parser: `app/src/main/java/cn/com/omnimind/bot/runlog/RunLogActionParser.kt`
- Function execution startup: `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/OobFunctionToolHandler.kt`
- Replay step runner: `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/OobFunctionToolHandler.kt`
- Replay frontend session controller: `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/OobFunctionFrontendSessionController.kt`
- Function step routing: `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/OobFunctionToolHandler.kt`
  This owns `call_tool` Function-call dispatch, supports `call_tool(function_id)`,
  and rejects generic
  tool steps during replay; do not reintroduce separate step executors
  unless the ownership boundary changes.
- Replay run result builder: `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/OobFunctionRunResultBuilder.kt`
- Function call card presenter: `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/OobFunctionCallCardPresenter.kt`
- Native replay policy and reusable Function conversion: `app/src/main/java/cn/com/omnimind/bot/runlog/`
- RunLog conversion: `app/src/main/java/cn/com/omnimind/bot/runlog/OobRunLogFunctionConverter.kt`
  It converts RunLogs into Function specs or manual Function assets; Function CRUD belongs in
  `OobFunctionRepository`. RunLog conversion must not make the result agent-visible by default:
  `agent_visible=false` / `visibility=manual_function` means it is saved for editing or direct id-based
  execution, but excluded from UDEG recall, dynamic tools, and the normal reusable Function list. Only an
  explicit registration/publish action should set `agent_visible=true`. Conversion responses should expose diagnostics such
  as card counts and compiled step counts; workspace RunLog mirroring is
  best-effort and must not replace Function registration status.
- Agent/MCP Function management tools: `app/src/main/java/cn/com/omnimind/bot/runlog/OobFunctionManagementService.kt`
- Canonical action executor: `assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/ActionExecutor.kt`
- Replay strategy helpers: `app/src/main/java/cn/com/omnimind/bot/runlog/ReplayHelper.kt`
  `ActionExecutor.act` is the only act entry; replay only supplies ActCheckConfig callbacks for page guard, checker, and action transfer before canonical dispatch.
- Local checker rules: `app/src/main/java/cn/com/omnimind/bot/runlog/OmniflowCheckerRule.kt`
  Checker detection/execution lives behind `ActionExecutor.act` check callbacks; the
  `omniflow` skill references are the active agent maintenance checklist. The
  retired focused checker skill is no longer bundled.
- Page/package inference helper: `app/src/main/java/cn/com/omnimind/bot/runlog/RunLogPagePackageInference.kt`
- Function backend ownership: `app/src/main/assets/omniflow/function/README.md`
- Workspace Function save: `app/src/main/java/cn/com/omnimind/bot/omniflow/WorkspaceFunctionStore.kt`
- Flutter timeline: `ui/lib/features/task/pages/execution_history/run_log_timeline_page.dart`
- Flutter reusable Function card: `ui/lib/features/task/pages/execution_history/widgets/reusable_function_card.dart`
- Flutter converter: `ui/lib/features/task/run_log/run_log_reusable_function_converter.dart`
- Flutter service bridge: `ui/lib/services/assists_core_service.dart`
  Product-facing Function run calls should use
  `runOobReusableFunction`, which delegates to native `OobFunctionToolHandler.runFunction`.

## Executor Policy

The executor lists live in `replay_policy.json` and are mirrored by Kotlin and
Dart policy classes with parity tests. Update the JSON and both mirrors
together.
Kotlin executor string constants live in `RunLogReplayPolicy`; compiler,
runtime, and guard code should use those constants instead of scattering
literal `"omniflow"`, `"tool"`, or `"agent"` checks in core replay paths.
Use the constants for both step construction and executor comparisons. Keep
component-specific diagnostic labels, for example `agent_tool`,
`omniflow_graph`, or `omniflow_function`, outside this taxonomy unless they
become first-class replay executors.
Fields that carry the deterministic replay marker, such as `coordinate_hook`,
should also reference `RunLogReplayPolicy.EXECUTOR_OMNIFLOW` instead of
spelling the string locally.
Replay-engine markers such as `omniflow_utg` also belong in
`RunLogReplayPolicy`; runtime checks should reference the policy constant
instead of retyping the marker.
`replay_policy.json` is the external policy data source and may spell concrete
tool names directly. Do not treat those JSON literals as competing Kotlin
owners; keep Kotlin code and tests pointing at `OobFunctionToolNames`,
`AgentToolNames`, or `RunLogReplayPolicy` as appropriate.
Replay tool names such as `call_tool`, `go_to_node`, and `oob.agent.run` also
live in `RunLogReplayPolicy` when they are used by stored RunLog import,
Function schema materialization, recall, or replay routing.
Agent-facing docs and tools should present saved Function reuse as runtime
recall plus replay inside `vlm_task`; ordinary VLM/Agent output must not
emit `call_tool(function_id, arguments)` or hidden Function tools. UDEG
edge-kind names and diagnostic counter keys remain graph-storage vocabulary
owned by `OobUdegNodeStore`.
Canonical in-app Function and RunLog management tool names such as
`oob_function_list`, `oob_function_get`, `oob_function_register`,
`update_function`, and `oob_run_log_convert` live in `OobFunctionToolNames`;
`RunLogReplayPolicy` may classify those tools but should not duplicate their
string definitions.
Canonical generic agent tool names such as `vlm_task`, `browser_use`,
`web_search`, and `android_privileged_action` live in `AgentToolNames`.
Tool definitions, handlers, MCP adapters, agent run-log card construction, and
RunLog classifiers should reference that owner instead of scattering literals.
When a new generic agent tool affects replay, `RunLogReplayPolicy` may classify
the `AgentToolNames` constant, but it should not redefine the string. If the
tool is only a live agent capability and does not appear in reusable replay
steps, leave RunLog policy unchanged.
Agent-facing docs should name the OmniFlow Function lifecycle tools first. Function
execution for online tasks goes through local runtime recall/replay; normal VLM
output should stay in ordinary UI actions.

- `executor=omniflow`: deterministic local replay only. Allowed actions are
  the OOB local set: `click`, `long_press`, `input_text`, `swipe`, `open_app`,
  `press_key`, `wait`, and `finished`. OOB-native
  OmniFlow graph/function calls such as `go_to_node`, `click_node`, and
  internal Function replay calls also use this executor and are dispatched by
  `OobFunctionToolHandler`.
- `executor=tool`: direct tool call only when a live `AgentToolRouter` exists and the tool output is not live data needed by later steps.
- `executor=agent`: live planning or perception. Use for VLM-only cards, `browser_use`, `web_search`, memory lookup, and RunLog lookup.

Do not hard replay `browser_use` or `web_search`; their outputs are live context and can be stale.

## Conversion Rules

- VLM-only logs must not become empty functions. Emit one `executor=agent` step with reason `perception_only_step_without_recorded_actions`.
- If a VLM wrapper card is followed by concrete recorded actions, skip the perception wrapper and keep the recorded `omniflow` steps.
- Failed recorded action cards must not count as concrete replay evidence; keep
  compact failure diagnostics if the only local action failed.
- `android_privileged_action` cards that wrap a supported local UI action should
  flatten nested `arguments` into the emitted OmniFlow step args.
- Treat legacy `type` as an import alias for `input_text`; do not emit it as a
  final replay tool. Drop adjacent duplicate input-text steps when noisy
  accessibility events report the same final text on the same target.
- Keep compiled step noise cleanup in `RunLogReplayStepNoiseNormalizer`.
  It owns repeated input collapse and redundant click-before-input removal.
  Startup package correction is a replay page-guard concern, not a compile-time
  injected `open_app` step.
- Cleanup code that compares replay actions must use `OobActionCodec`
  constants; natural-language token lists may still contain literal words like
  `click` because those are UI labels, not action names.
- Keep single-card action semantics in `RunLogReplayStepCompiler`. It owns
  whether a card becomes `executor=omniflow`, `executor=tool`, or
  `executor=agent`, plus step titles and source-context repair.
- Keep RunLog card field extraction and JSON coercion in `RunLogCardAccessors`.
  Do not duplicate `tool_call`/`header`/observation parsing across compiler,
  startup cleanup, or future analysis code.
- Keep step role normalization in `OobStepRoleClassifier`. Replay alignment,
  UDEG indexing, and Function checker patching should share checker-candidate
  role aliases instead of each service carrying its own `optional_checker` /
  `ad_checker` table.
- Keep generic RunLog action/value coercion in `OobActionCodec`. Tool routes
  such as `OobFunctionManagementService` and small payload helpers such as
  `OobFunctionCallTiming`, schema/parameterization helpers, explorer utilities,
  UDEG scalar readers, and cleanup services should call it instead of adding private
  `mapArg`/`listArg`/`firstNonBlank`/`intArg`/`longArg`/`boolArg` copies when
  behavior is equivalent. Execution code such as `ActionExecutor` should
  follow the same rule for generic argument coercion. Prefer direct calls or
  member imports from `OobActionCodec`; do not add one-line local forwarding helpers. In
  particular, schema projection and Function parameterization should not carry
  private `boolArg`/`asMap` equivalents.
- Keep generic Function payload coercion in `OobFunctionJson`. Function
  register/update/run/recall services, Function replay argument compatibility,
  and Function run-result timing payload merge should use it instead of adding
  local `mapArg`/`listArg`/`firstNonBlank`/`intArg`/`longArg`/`boolArg` copies.
  It is a mechanical JSON/value helper only; action aliases and RunLog-card
  semantics stay in `OobActionCodec` and `RunLogCardAccessors`. Prefer direct
  calls or member imports from `OobFunctionJson`; do not add one-line local
  forwarding helpers.
- Keep agent-facing tool JSON projection in `AgentToolJson`. Runtime tool
  handlers should call it directly for generic map/list/scalar payloads instead
  of routing through `SharedHelper` forwarding methods.
- Do not force-merge helpers with intentionally different compatibility
  behavior. `OobFunctionSchemaBuilder.boolArg` is stricter for schema fields,
  `RunLogCardAccessors` owns card-field extraction helpers rather
  than generic action coercion, and MCP route/executor helpers may keep
  multi-key argument alias/default parsing local when the semantics are
  route-specific.
- Keep Function storage in workspace JSON. `baselib` runlog persistence should
  only consume persisted diagnostics/source bindings supplied by app-layer
  Function code.
- Leave unrelated VLM, agent-config, and Assists string/default helpers in
  their owning features. They are not RunLog card conversion or Function
  payload helpers unless their feature boundary is intentionally changed.
- Keep RunLog conversion literal by default. Parameter names, descriptions, and
  bindings are added only by offline agent enhancement through `update_function`;
  do not put deterministic parameter inference back into the compiler or runtime
  replay handler.
- Keep parameter bindings aligned with actual `execution.steps` indexes after skipping wrapper cards.
- For agent steps, bind runtime parameters into both `step.args` and `step.agent_call.args.original_args`.
- AI normalization may rename and parameterize, but must not change executor policy. Normalize data-flow tools back to `executor=agent`.
- Agent enhancement may refine the reusable Function name, description, per-step
  descriptions, parameter names/descriptions/bindings, and `agent_reuse`
  metadata. It must not rewrite `execution.steps`, tool names, executors,
  step order, validation, fallback, or concrete tool args. New parameter
  bindings are accepted only when they point to existing non-coordinate leaf
  args, so a recorded "妈妈 + 手机号" flow can become a reusable
  "联系人 + 手机号" Function without changing the replay structure.
- The enhancement prompt should send a compact digest with step summaries and
  `candidate_bindings`, plus a valid example output JSON. Do not send the full
  executable spec, source XML, screenshots, or `source_context` to the label
  enhancer.
- `agent_reuse.key_actions` is planning metadata only. It helps later agent
  selection but is not an executable split.
- OmniFlow graph/reusable Function tools convert to `kind=omniflow_graph` or
  `kind=omniflow_function`, `executor=omniflow`, and `model_free=true`.
- RunLog evidence should feed the same RunLog -> Function conversion path. Do
  not add a local explorer or replay tool that directly executes phone actions.
- `RunLogPagePackageInference` owns package-name inference from recorded
  activity/XML evidence. Keep this separate from card compilation and replay
  execution so package heuristics do not leak into unrelated conversion rules.

## Replay Rules

Direct UI execution is two phase:

1. Execute deterministic local prefix.
2. If a tool/data-flow/agent step is reached, return failure diagnostics; do
   not delegate the remaining Function spec to a second execution path.

Data-flow/perception-only steps should be handled by the ordinary VLM loop after
the Function result returns, not by blindly calling the original tool.
OmniFlow reusable Function calls are resolved against the local compatible
Function stores and execute recursively with a bounded call stack. OmniFlow graph calls
execute explicit `path` entries or UTG edges by lowering them to supported
primitive local actions.

Manual human recording shows a compact top control only after the recorder has
started, but action capture is initially paused. The user can navigate to the
target screen first; tapping the same pause/resume control starts capture and
refreshes the current screen baseline before recording the first action. The
manual recording flow does not show the lower cat learning popup; the compact
top control is the only recording control surface. The control can be dragged;
drag movement temporarily pauses capture, does not enter the RunLog, and resumes
by capturing the current screen again so the next recorded step uses a fresh
source context. Manual recording can also be paused
explicitly while transient UI issues are handled. The top control's
`截图` action is a full get-state capture, not a replay action: it hides the
control, persists XML, screenshot, PageVector observation, page analysis,
decision context, node skill, and state manifest through `OobUdegNodeStore`,
then appends a skipped `get_state` evidence card to the active RunLog.
Debug builds may enable per-action marked screenshots for manual recording.
Those screenshots are diagnostic-only private JPEG artifacts with the actual
touch position annotated; normal product recording remains XML/touch based and
does not store screenshots by default.
Registered reusable Function steps can be edited from the Function library and
are saved back under the same `function_id`. Manual action cards retain
`event_context` for conversion diagnostics. Replayable manual actions should
prefer concrete input backends: `overlay_touch`, `overlay_touch_text_input`,
`device_getevent`, or `device_getevent_text_input`. Accessibility events are
supporting evidence, not a general action backend: they may anchor IME text
input or a submit-style post-input click only while keyboard routing prevents
the overlay from observing the action. Manual actions should carry a before XML
snapshot whenever one is available. Keyboard/IME bypass flows may instead keep
coordinate evidence with screenshot/package context and
`source_context_mode=coordinate_only_no_xml`; treat that as degraded repair
evidence, not as a stable selector or a required Function main-path guarantee.

RunLog save results, the Function library page, and the memory-center embedded
Function list share the same reusable Function summary card. Keep the primary
run entry on that card; secondary controls may enhance or schedule the Function,
but must not introduce another summary-card layout or duplicate run button. If
the RunLog already maps to a registered reusable Function, open that Function
directly instead of registering it again. Saving and direct replay are the
primary path; `增强` is an explicit offline/background action that may run after
the current Function is already usable. It asks Agent to improve the reusable
Function name, description, per-step descriptions, safe runtime parameter slots,
and non-executable `agent_reuse` metadata, then saves the enhanced spec back to
the same `function_id`. Enhancement never changes executor/tool/args/validation/
fallback or step order. It must not block VLM auto-registration, recall-hit
replay, direct run buttons, or debug convert-and-replay smoke tests. While the
Agent/update work is pending, the enhancement control shows `后台增强中`, but
the current registered Function remains runnable as-is. Terminal states are
explicit: `已增强`, `已检查`, `部分增强`, or `重试增强`. The result is persisted
under `metadata.oob_enhancement` with status `enhanced`, `unchanged`, `partial`,
or `failed`, so reopening the Function does not require guessing whether the
prior click changed anything. A save button should only appear after unsaved
manual edits. Raw JSON and agent prompt details stay under the advanced section
by default.

## OmniFlow Compatibility Boundary

Port into OOB:

- Canonical action names and aliases: `input_text`, `swipe`, `press_key`,
  `finished`, plus common aliases such as `tap`, `type_text`, `scroll_*`,
  `launch_app`, and `done`.
- `source_context.page` as an input alias for OOB's
  `source_context.src_ctx.page` coordinate remap shape.
- Reusable Function metadata that keeps `source.run_id`, `source_run_ids`, execution
  counts, and local runner state available for future provider import/export.

Keep in OmniFlow/provider for now:

- Provider HTTP/MCP lifecycle, cloud push/pull, and provider-side retry
  semantics.
- SQLite `RunStore`, background enrich, semantic dedup, L1/L2 cache writeback,
  and multi-user registry.
- Cloud/provider graph optimization beyond the local path/edge data embedded in
  OOB function specs.

## Verification

Run focused tests after RunLog changes:

Replay UI progress is a mandatory regression check. Every change that touches
Function replay, RunLog conversion, replay result payloads, or direct UI replay
must verify that the frontend shows the current step as `第 X/Y 步` while replay
is running and still shows `执行到第 X/Y 步` in the terminal result payload. Do
not rely on the floating cat/overlay for this signal; Flutter must receive the
progress event even when overlay permission is unavailable.

```bash
cd ui
flutter test test/features/task/pages/execution_history/run_log_reusable_function_converter_test.dart
flutter test test/services/agent_stream_reducer_test.dart
dart analyze lib/features/task/run_log/run_log_reusable_function_converter.dart lib/features/task/pages/execution_history/run_log_timeline_page.dart test/features/task/pages/execution_history/run_log_reusable_function_converter_test.dart
```

```bash
./gradlew :app:testDevelopStandardDebugUnitTest --tests cn.com.omnimind.bot.runlog.InternalRunLogStoreTest
```

Add tests for new tool classes before changing executor policy.
