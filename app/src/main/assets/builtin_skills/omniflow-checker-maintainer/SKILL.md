---
name: omniflow-checker-maintainer
description: Implement and maintain OmniFlow runtime checkers for OOB Android GUI replay. Use when the user asks to add, generate, repair, or debug checker code, global checker, hi 升级 checker, upgrade/update popup checker, package/open-app checker, or supported checker_rules that need Kotlin runtime changes.
---

# OmniFlow Checker Maintainer

Use this when a checker must become real runtime behavior, not just Function
metadata.

## Mental Model

A checker injects a conditional action into replay. Its phase decides where that
extra action runs relative to the main-path step: before transfer, before the
action, or after the action.

Do not use a checker to retarget, replace, or compete with a normal replay
action. If the recorded action itself can be replayed through action transfer,
keep it in the main path. Add a checker only when the current XML proves an
unstable obstacle exists and replay needs one extra deterministic action to
clear it.

## Decide The Layer

- If the condition is already supported, update the Function through
  `update_function` and `metadata.checker_rules`.
- If the condition is not supported, add Kotlin runtime support first.
- If a popup appears immediately after `open_app`, use a `post_action` checker.
  Pre-transfer checkers do not run before an `open_app` step.

## Runtime Implementation

1. Add or update condition/action aliases in
   `app/src/main/java/cn/com/omnimind/bot/runlog/OmniflowCheckerRule.kt`.
2. Add the supported pair and default phase there. Global rules belong in
   `GLOBAL_PRE_TRANSFER`, `GLOBAL_PRE_ACTION`, or `GLOBAL_POST_ACTION`.
3. Implement XML-only detection and action execution in
   `app/src/main/java/cn/com/omnimind/bot/runlog/UIStepExecutor.kt`.
4. If agents may generate the checker through `update_function`, update
   `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionCheckerPatchService.kt`.
5. Keep checker actions local and deterministic. Do not add model calls,
   scripts, arbitrary selectors, network calls, or user-consent bypasses.

## Hi Upgrade Pattern

Use `app_upgrade_prompt + dismiss + post_action` for the Hi upgrade prompt.

Detection should require upgrade/update evidence such as `新版本`, `版本更新`,
`升级`, `update available`, or `upgrade available`. The action should click only
negative buttons such as `以后再说`, `稍后再说`, `暂不升级`, `取消`, `not now`, or
`later`. Never click positive buttons such as `立即升级`, `立即更新`, `install`,
or `download`.

## Permission Dialog Pattern

Use `permission_dialog + allow + pre_transfer` for Android permission prompts.

Do not turn recorded permission-dialog clicks into selector-heavy checker
logic. If the source step itself clicks a permission control such as `始终允许` /
`always allow`, keep the step as the main-path action and make the checker yield
so action transfer can remap it to the current dialog. The checker is only a
fallback for unexpected permission prompts that block a non-permission step.

## Contract Updates

When supported checker behavior changes, update all matching contracts:

- `app/src/main/assets/builtin_skills/omniflow/references/checkers.md`
- `docs/omniflow/checkers.md`
- `app/src/main/assets/builtin_skills/omniflow-function-enhancer/references/runtime-contract.md`
- `ui/assets/execution_history/omniflow_function_enhancer_contract.md`
- `ui/lib/features/task/run_log/run_log_reusable_function_converter.dart`

If a new built-in skill is added, update
`app/src/main/assets/builtin_skills/manifest.json`.

## Tests

Add focused tests for every new checker:

- `OmniflowCheckerRuleTest`: aliases, default action, default phase, supported
  pair.
- `UIStepExecutorTest`: XML fixture that should trigger and one that
  should not trigger when risk is high.
- `OobOmniFlowLoopAcceptanceTest`: `update_function` normalization when agents
  can author the checker rule.
