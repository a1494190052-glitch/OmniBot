---
name: function
description: Manage and improve Omnibot reusable Functions from recorded RunLogs. Use whenever the user asks to save, register, inspect, clean, enhance, repair, parameterize, or delete a 复用指令 / Function / recorded trajectory, including duplicate input cleanup, noisy actions, optional popup checkers, "保存刚才的操作", "增强这个 Function", and "应该点 A 而不是 B".
---

# Function

Use this skill for the agent decisions around recorded RunLogs and reusable
Functions. Kotlin owns Android RunLog access, Function files, and physical
action execution. OmniFlow owns compilation, recall, materialization, action
transfer, and checker policy. This skill owns evidence interpretation and
explicit action edits.

## Storage Model

- A RunLog is the complete execution record. It may contain XML, screenshots,
  before/after observations, failures, and diagnostics.
- A Function is the reusable executable result. It contains canonical actions,
  parameters, minimal coordinate semantics, and source RunLog ids.
- Read raw evidence with `oob_run_log_get({run_id})` only when needed. Never
  copy XML, screenshots, bounds, observations, or source state into a Function
  patch.

## Save A Recording

For requests such as “保存刚才的操作” or “把上一条轨迹变成复用指令”:

1. Call `oob_run_log_list` when the RunLog id is unknown.
2. Choose the newest successful RunLog that matches the user request.
3. Call `oob_run_log_convert` with its `run_id` and `register=true`.
4. Report the real `function_id` and action count.

The baseline conversion intentionally preserves every successful recorded
action. Do not expect the compiler to merge input or remove behavior.

## Enhance Or Repair

1. Read the saved Function with `oob_function_get`.
2. Resolve its source `run_id`, then read that RunLog with `oob_run_log_get`.
3. Compare Function actions with RunLog cards in order.
4. Build the smallest evidence-backed `patch.action_edits`.
5. Call `update_function` with `dry_run=true` and inspect the preview.
6. If the preview preserves the intended workflow, call the same patch without
   `dry_run`.

Use `patch.action_edits` for executable cleanup:

```json
{
  "function_id": "fn_example",
  "patch": {
    "action_edits": [
      {
        "op": "delete",
        "index": 2,
        "expected_tool": "input_text",
        "reason": "The next input_text writes the complete value to the same target."
      }
    ]
  },
  "dry_run": true
}
```

Supported action edits are deliberately small:

- `delete`: remove one proven redundant or conditional action.
- `replace_args`: merge corrected arguments into one existing action.

Unspecified actions remain unchanged. Do not reorder actions or rewrite the
whole Function.

## Evidence Rules

- Treat every recorded action as intentional until evidence proves otherwise.
- A manual `wait` is intentional by default. Delete it only when the RunLog
  proves it is redundant; otherwise adjust its single duration argument.
- Delete an earlier incremental `input_text` only when a later `input_text`
  targets the same field and fully supersedes it.
- Treat ads, permission prompts, update prompts, and transient popups as
  checker evidence, not mandatory happy-path actions. Do not encode checker
  rules through `update_function`.
- Use `replace_args` for an explicit user correction or unambiguous RunLog
  evidence. Do not invent coordinates, selectors, or target text.
- If evidence is ambiguous, leave the Function unchanged and report why.

## Safety And Identity

- Preserve `function_id`.
- Keep raw evidence in the RunLog. Function patches may reference only
  `run_id`, action indexes, and minimal coordinate semantics.
- Never execute a Function merely because it was enhanced.
- Use `oob_function_delete` only for a specific Function. Use
  `oob_function_clear` only after the user explicitly asks to clear all.
