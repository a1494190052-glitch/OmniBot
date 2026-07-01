# OOB RunLog

RunLog is recorded evidence. A reusable Function is created only after native
Kotlin conversion accepts that evidence as deterministic replay steps.

## Boundaries

1. Native code records cards into `InternalRunLogStore`.
2. Flutter displays timeline data and sends explicit save/update requests.
3. Native Kotlin owns RunLog-to-Function conversion.
4. Workspace JSON is the only durable Function store.
5. Function replay executes UI steps through `ActionExecutor.act`.

Do not add a second replay policy in Flutter, assets, or prompts. Kotlin code is
the runtime source of truth for import compatibility and step construction.

## Concepts

- Record: append-only runtime evidence in `InternalRunLogStore`.
- Card: one observed tool/action event. A card is not automatically a replay
  step.
- Step: one Function execution unit produced by native conversion.
- Action: a canonical local device operation from `OobActionSchema`.
- Source context: XML, screenshot path, package, and recorded target evidence
  used by action transfer.
- Checker: optional runtime correction metadata. Checker execution runs only
  through `ActionExecutor.ActCheckConfig`.

## Storage

`InternalRunLogStore` stores append-only NDJSON events and compact JSON
snapshots. The snapshot is a cache for timeline/list APIs; correctness-sensitive
readers should treat snapshot plus later events as one logical record.

Function specs live under the workspace store and are accessed through
`OmniFlowFunctionService`.

## Code Map

- RunLog storage: `baselib/src/main/java/cn/com/omnimind/baselib/runlog/InternalRunLogStore.kt`
- RunLog conversion entry: `app/src/main/java/cn/com/omnimind/bot/omniflow/function/OmniFlowFunctionService.kt`
- Function assembly: `app/src/main/java/cn/com/omnimind/bot/omniflow/function/OmniFlowFunctionCompiler.kt`
- Card-to-step compilation: `app/src/main/java/cn/com/omnimind/bot/runlog/RunLogReplayStepCompiler.kt`
- Step cleanup: `app/src/main/java/cn/com/omnimind/bot/runlog/RunLogReplayStepNoiseNormalizer.kt`
- Function management tools: `app/src/main/java/cn/com/omnimind/bot/omniflow/function/OmniFlowFunctionService.kt`
- Function storage: `app/src/main/java/cn/com/omnimind/bot/omniflow/function/OmniFlowFunctionStore.kt`
- Function replay: `app/src/main/java/cn/com/omnimind/bot/omniflow/function/OmniFlowFunctionRun.kt`
- Canonical action executor: `assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/ActionExecutor.kt`
- Replay helper callbacks: `app/src/main/java/cn/com/omnimind/bot/runlog/ReplayHelper.kt`
- Action transfer: `app/src/main/java/cn/com/omnimind/bot/runlog/ActionTransfer.kt`
- Checker rules: `app/src/main/java/cn/com/omnimind/bot/runlog/OmniflowCheckerRule.kt`
- Flutter timeline UI: `ui/lib/features/task/pages/execution_history/run_log_timeline_page.dart`
- Flutter display model: `ui/lib/features/task/run_log/omniflow_function_spec.dart`

## Conversion Rules

- If a VLM wrapper card is followed by concrete recorded UI actions, keep the
  UI actions and skip the wrapper.
- Failed action cards are not concrete replay evidence.
- `android_privileged_action` cards may flatten supported local UI actions into
  canonical action args.
- Legacy action aliases are import compatibility only; emitted replay actions
  must use canonical names.
- Startup package correction is a replay checker/page-state concern, not a
  compile-time injected `open_app` step.
- Parameter names, descriptions, and bindings are produced by offline
  `update_function` enhancement, not by deterministic RunLog conversion.

## Replay

The main path is:

`Function step -> ActionExecutor.act -> checker/actionTransfer -> DeviceOperator`

Replay-specific behavior is injected through `ActCheckConfig`. Do not introduce
new executors, runtimes, backends, or UI replay compilers.
