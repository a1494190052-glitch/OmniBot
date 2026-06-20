# `vlm_task` Recall Loop Smoke Test

This is the debug-device path for validating the OOB-native loop:

1. First `vlm_task` observes the current Android screen and executes ordinary UI actions.
2. The successful VLM RunLog is converted into an agent-visible reusable Function.
3. A second matching `vlm_task` performs fresh observe plus recall, then uses native replay instead of another VLM action loop.

The Python OmniFlow package installed in the built-in Alpine environment is not the phone executor for this loop. It is a development/evaluation/provider toolchain. Android execution, permission checks, fresh observe, recall, argument resolution, and replay stay in Kotlin.

## User-Facing Examples

These examples are safe to show in UI, docs, and smoke fixtures. They describe
the `vlm_task` entry shape only; the model output contract remains a single
OpenAI native tool call for ordinary UI actions.

- `打开网络设置` / `Open network settings`
- `点击设置搜索框` / `Tap the Settings search field`
- `在小红书搜索猫咪` / `Search cats in Xiaohongshu`

Do not teach users or models to output `function_id`, `call_tool`, or legacy
text-action JSON as the VLM action response.

## Preconditions

- Install a `develop` debug build.
- Enable accessibility and overlay permissions.
- Configure a working VLM model binding if the first run needs live VLM inference.
- Put the device on the target page or pass `packageName` and let the debug receiver prelaunch the app.

## One-Command Strict Smoke

Prefer the checked smoke harness when validating a PR on a real device:

```bash
scripts/oob-vlm-recall-loop-smoke.sh \
  --device <serial> \
  --goal "打开网络设置" \
  --target-package com.android.settings
```

The script runs the same debug receivers listed below and exits non-zero unless:

- first `RUN_VLM_RUNLOG` succeeds, stores a RunLog, and registers a Function
- the registered Function has `metadata.enhancement_policy=offline_only`
- `RUN_OOB_RECALL` returns a strict direct `hit`
- `RUN_VLM_RECALL_HIT` executes through `omniflow_recall_hit`
- the second `RUN_VLM_RUNLOG --ez startFromCurrent true` also uses
  `omniflow_recall_hit`
- `CONVERT_RUNLOG_AND_RUN_FUNCTION --ez enhance true` reports offline-only
  enhancement and does not replay an enhanced Function

## First VLM Run

```bash
adb shell am broadcast \
  -a cn.com.omnimind.bot.debug.RUN_VLM_RUNLOG \
  --es goal "打开网络设置" \
  --es packageName "com.android.settings" \
  --ei maxSteps 3 \
  --ez register true
```

Read the result:

```bash
adb shell run-as cn.com.omnimind.bot cat files/debug-vlm-runlog-result.json
```

Expected fields:

- `success=true`
- `runlog_success=true`
- `runlog_card_count>0`
- `convert_success=true`
- `convert.function_spec.metadata.enhancement_policy=offline_only`

## Recall Check

Run recall from the same or equivalent page:

```bash
adb shell am broadcast \
  -a cn.com.omnimind.bot.debug.RUN_OOB_RECALL \
  --es goal "打开网络设置" \
  --es current_package "com.android.settings" \
  --ez auto_execute true
```

Read the result:

```bash
adb shell run-as cn.com.omnimind.bot cat files/debug-oob-recall-result.json
```

Expected fields:

- `recall.success=true`
- `recall.decision=hit` for direct execution, or `recall.decision=recall` while tuning thresholds
- direct hits include `recall.hit.function_id`

## Second Fast Execution

Execute the same `vlm_task` again from the matched page:

```bash
adb shell am broadcast \
  -a cn.com.omnimind.bot.debug.RUN_VLM_RUNLOG \
  --es goal "打开网络设置" \
  --es packageName "com.android.settings" \
  --ei maxSteps 3 \
  --ez startFromCurrent true
```

Read the result:

```bash
adb shell run-as cn.com.omnimind.bot cat files/debug-vlm-runlog-result.json
```

Expected fields:

- `outcome.executionRoute` starts with `omniflow_recall_hit`
- `outcome.omniflowExecutionSummary.success=true`
- no new VLM multi-step action loop is needed after the recall hit

## Recall-Hit-Only Debug Path

To isolate the fast path without starting a new ordinary VLM loop, call the
debug receiver that invokes `VlmToolCoordinator.tryExecuteRecallHitOnly`
directly:

```bash
adb shell am broadcast \
  -a cn.com.omnimind.bot.debug.RUN_VLM_RECALL_HIT \
  --es goal "打开网络设置" \
  --es packageName "com.android.settings" \
  --ei timeoutSeconds 30
```

Read the result:

```bash
adb shell run-as cn.com.omnimind.bot cat files/debug-vlm-recall-hit-result.json
```

Expected fields for a strict hit:

- `success=true`
- `phase=executed`
- `executionRoute` starts with `omniflow_recall_hit`
- `outcome.omniflowExecutionSummary.success=true`

If `phase=no_direct_hit`, recall did not produce a strict auto-executable hit
from the current page. Use the `RUN_OOB_RECALL` output to inspect candidates and
thresholds.

## Direct Replay Fallback

If recall identifies a Function but the second `vlm_task` path still needs debugging, run the Function directly:

```bash
adb shell am broadcast \
  -a cn.com.omnimind.bot.debug.RUN_OOB_FUNCTION \
  --es function_id "<function_id_from_recall>" \
  --es goal "打开网络设置"
```

Read the result:

```bash
adb shell run-as cn.com.omnimind.bot cat files/debug-oob-function-run-result.json
```

Expected fields:

- `success=true`
- `runner` or `execution_route` indicates local OOB replay

## Convert-And-Replay With Enhance Flag

The debug convert-and-replay receiver accepts an `enhance` flag for compatibility
with older smoke scripts. That flag must not run `update_function` before replay.
It should only report that enhancement is queued/skipped as an offline step:

```bash
adb shell am broadcast \
  -a cn.com.omnimind.bot.debug.CONVERT_RUNLOG_AND_RUN_FUNCTION \
  --es run_id "<successful_vlm_run_id>" \
  --es goal "打开网络设置" \
  --ez run true \
  --ez enhance true
```

Read the result:

```bash
adb shell run-as cn.com.omnimind.bot cat files/debug-runlog-function-replay-result.json
```

Expected fields:

- `enhance_requested=true`
- `enhancement_policy=offline_only`
- `enhance.policy=offline_only`
- `enhance.status=queued` or `enhance.status=skipped`
- `replay_uses_enhanced_function=false`
- no `enhanced_function_spec_hash` field
- replay success/failure is independent from enhancement status

## Boundary

- Kotlin owns live phone execution: accessibility, overlay, screen observe, page matching, recall decision, replay, and RunLog.
- Shared schema owns the Function JSON contract and MCP/HTTP tool payloads.
- OmniFlow Python can install inside Alpine as `omniflow_dev` for provider/MCP/dev/eval workflows. It should not become a second Android action executor.
- Enhancement is offline-only: auto-registration must only save the replayable Function. Semantic enrichment runs later through explicit upgrade/background jobs.
