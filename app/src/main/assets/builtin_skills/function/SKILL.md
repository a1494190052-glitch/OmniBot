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

- A RunLog is the complete execution record. Each step contains
  `before_state_id`, one canonical `action`, its `result`, `after_state_id`, and
  metadata; the run may end with one `final_state_id`. Raw XML is stored behind
  `state_id` and loaded only when needed.
- A Function is the reusable executable result. It contains `function_id`,
  parameters, bindings, and steps of `source_state_id + action`. It does not
  retain a source RunLog id or copy source state.
- Read raw evidence with `oob_run_log_get({run_id})` only when needed. Never
  copy XML, screenshots, bounds, state, or source state into a Function
  patch.

## Save A Recording

For requests such as “保存刚才的操作” or “把上一条轨迹变成复用指令”:

1. Call `oob_run_log_list` when the RunLog id is unknown.
2. Choose the newest successful RunLog that matches the user request.
3. Call `oob_run_log_convert` with its `run_id` and `register=true`.
4. Treat the returned base Function as registered immediately. If
   `enhancement_status=enhancing`, offline enhancement is still running.
5. Report the real `function_id` and action count.

Conversion registers the original compiled Function first, returns without
waiting for a model, and queues offline enhancement by default. Pass
`enhance=false` only when the user explicitly chooses to skip it. The queued
enhancement may later overwrite that same Function with refined name,
description, semantic parameters, bindings, and evidence-backed checker rules.
If enhancement fails, the original registered Function remains available;
retry with `update_function` using `mode=enhance`. Enhancement preserves every
successful recorded action and never merges input, removes behavior, or
invents recovery evidence. Use `update_function` only for later edits or to
retry enhancement of an existing Function.

## Enhance Or Repair

1. Read the saved Function with `oob_function_get`.
2. Use an explicitly selected RunLog only when the user is repairing from a
   known recording; do not infer a hidden source RunLog from the Function.
3. Compare Function steps with RunLog steps in order when both are available.
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
- Checker generation runs during offline RunLog enhancement. A rule may be
  created only from a recorded successful recovery step and contains exactly
  `schema_version`, a restricted Python `trigger`, that step's
  `before_state_id` as `source_state_id`, and the same canonical `action`.
  When a built-in recovery records `metadata.checker_trigger`, copy it exactly;
  the fast RunLog conversion writes that Checker immediately.
  Insufficient evidence produces no rule; never invent a trigger, state id,
  selector, coordinate, or recovery action.
- Use `replace_args` for an explicit user correction or unambiguous RunLog
  evidence. Do not invent coordinates, selectors, or target text.
- If evidence is ambiguous, leave the Function unchanged and report why.

## Safety And Identity

- Preserve `function_id`.
- Keep raw state in the RunLog store. Function patches may reference only the
  Function, step indexes, and minimal coordinate semantics.
- Never execute a Function merely because it was enhanced.
- Use `oob_function_delete` only for a specific Function. Use
  `oob_function_clear` only after the user explicitly asks to clear all.
