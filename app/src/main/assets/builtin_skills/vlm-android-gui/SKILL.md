---
name: vlm-android-gui
description: Use for OOB VLM Android GUI automation, vlm_task execution, OmniFlow replay, reusable Function generation, and RunLog validation.
---

# VLM Android GUI Skill

## Scope

Use this skill when the task requires OOB to observe and operate the current Android UI, record a manual or VLM run, convert a RunLog into an OmniFlow Function, or replay a registered Function.

Do not use this skill for uploaded-image Q&A, static document analysis, generic web browsing, or non-device terminal work unless the device workflow explicitly needs it.

## GUI Action Policy

- Pass `package_name` when the target app is known; otherwise derive the package from installed-app or current-state evidence.
- Use one fresh observation for each action turn: current package, Accessibility XML, indexed UI evidence, screenshot, display size, previous action result, and short history.
- Choose exactly one executable UI action per step, then validate from the returned post-action observation before choosing the next step.
- Prefer stable UI evidence: visible label, role, `node_id`, `element_index`, or `scrollable_index`. Include absolute screen-pixel coordinates only as fallback.
- If an editable field is focused, use `input_text`. If it is visible but not focused, ground the field by label/index/coordinates before typing.
- For sliders, seekbars, drawers, and brightness controls, use drag/scroll actions instead of repeated taps.
- For numeric keypads, click visible digit buttons instead of sending free text.
- For permission or onboarding screens, choose safe visible actions such as Continue, Allow, OK, or Skip when that is consistent with the user goal.
- Do not finish after a single action unless the requested final state is directly visible. For multi-step goals, keep a checklist and finish only after the named targets are verified.

## RunLog And Function Flow

1. Start `vlm_task` for the user goal. The native runtime records an Internal RunLog with tool cards, token usage, timing, observations, and completion status.
2. Use RunLog evidence to inspect what actually happened. Prefer recorded observations and action results over guessed state.
3. Convert high-signal RunLogs into reusable OmniFlow Functions only when the task has stable intent, repeatable UI targets, and clear parameters.
4. Register or update Functions through the OOB Function tools. Keep Function specs small, parameterized, and tied to observed UI evidence.
5. Replay registered Functions when the current package and page evidence match. Fall back to live VLM execution when guards fail, targets are missing, or user intent diverges.

## Validation

- Verify at least two visible UI states for nontrivial workflows: before the action sequence and after the claimed completion.
- Treat black or blank screenshots as insufficient only when Accessibility and indexed evidence are also missing. If tree evidence clearly identifies the current page and target, continue from that evidence.
- If an action produces no expected state change, re-ground on the latest observation instead of repeating the same action.
- Keep claims tied to returned tool results, RunLog entries, or Function replay output.

## Useful Tooling

- `vlm_task`: live device automation.
- `oob_function_list`, `oob_function_get`, `oob_function_register`, `oob_function_update`, `oob_function_delete`, `oob_function_clear`: reusable Function lifecycle.
- `oob_run_log_list`, `oob_run_log_get`, `oob_run_log_convert`: RunLog inspection and conversion.
- `omniflow.recall`, `omniflow.ingest_run_log`, `omniflow.explore_replay`: OmniFlow recall, ingestion, and replay exploration.
