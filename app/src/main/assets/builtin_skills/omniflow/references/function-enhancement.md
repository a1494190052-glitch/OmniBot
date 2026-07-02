# Function Enhancement And Correction

Use this reference when improving an existing saved Function.

## Core Rule

All saved changes go through `update_function`. Do not rewrite and re-register a
full Function JSON by hand.

Enhancement is an explicit offline/background maintenance pass. Do not run it
inline before VLM RunLog auto-registration, recall-hit replay, direct Function
execution, or debug convert-and-replay. If a Function is already saved, it
remains runnable as-is while enhancement is pending.

## Enhancement Mode

Default mode is `enhance`: improve reuse clarity without silently changing
execution.

The output must make the Function callable by a future agent. Treat the
Function description as capability documentation, not a short human label.

Allowed changes:

- Rewrite `name` and `description` so the Function reads like a reusable
  Function. Include visible operations, where it applies, runtime inputs, and
  success signal when known.
- Rewrite per-step `title`, `summary`, or `description`.
- For every executable step, state what the action does and why it exists.
- Add `cleanup_annotation.action_purpose` for durable step purpose labels.
- Add runtime parameter metadata from existing non-coordinate leaf args only.
  Every public runtime parameter must include explicit JSONPath bindings to
  existing step args through `x_oob_bindings` or `bindings`.
- Add `agent_reuse` metadata: `reuse_when`, `avoid_when`, `success_signal`, and
  `key_actions`.
- Mark deterministic noise, merge candidates, drop candidates, and optional
  checkers as metadata.

Required reusable description structure:

- `适用场景 / Use when`: what user goals should trigger this Function, including
  app/page/context when known.
- `会做什么 / Does`: the concrete visible operation sequence, for example
  "opens Meituan, taps 外卖, selects the target address, submits search".
- `运行参数 / Inputs`: user-provided values or inferred parameters. Say "no
  explicit params" when there are none.
- `成功标志 / Success signal`: what screen/result means the Function completed.
- `不适用 / Avoid when`: cases where the Function should not be used, including
  different app, missing login, ambiguous target, or flows requiring live
  judgement.

Every executable step must have agent-usable annotations:

- `title`: short imperative label, e.g. `点击外卖入口`.
- `summary` or `description`: what the action changes on screen.
- `cleanup_annotation.action_purpose`: one stable phrase explaining why the
  action exists, e.g. `进入外卖频道`, `填写收货地址`, `确认提交`.
- If the step is a checker or popup handler, label it as optional checker in
  metadata; do not describe it as a required path.

Weak descriptions reduce future tool selection. If an enhancement only changes
the name but does not explain when to call it, what it does, and how success is
recognized, treat the enhancement as incomplete.

Forbidden in `enhance` mode:

- Do not change `function_id`.
- Do not reorder, insert, delete, or split executable steps.
- Do not change tool names, executors, concrete args, validation, fallback, or
  callable tool definitions.
- Do not bind parameters to coordinates, bounds, width, height, screenshots,
  XML nodes, or invented JSON paths.
- Do not rely on parameter names such as `query` or `input_text_1` to imply a
  binding. If the binding path is not explicit and present in the Function JSON,
  omit the parameter.

## Correction Scope

`update_function` is not a replay editor. It may improve user-facing Function
semantics and metadata, but it must not rewrite executable targets, insert
actions, delete actions, or invent coordinates. If the recorded path is wrong,
save a new recording or ask for a new explicit Function instead of mutating the
old execution stack.

## Status Contract

Every enhancement attempt ends in exactly one status:

- `enhanced`: meaningful safe changes were saved.
- `unchanged`: checked and found no safe useful change.
- `partial`: some safe changes were saved, but part of the enhancement failed
  or was skipped.
- `failed`: no usable enhancement was produced.

Use `enhanced` only when at least one saved section changed the Function in a
way that a future user can actually benefit from, and every required section
returned a valid patch.

Use `unchanged` when patches are valid but normalize to the same Function, or
when every proposed change is too vague to improve recall or replay robustness.

Use `partial` when a useful patch was applied but one or more sections failed,
returned invalid JSON, referenced missing step indexes, or attempted unsafe
bindings.

Use `failed` when no safe patch remains after validation, the model returns no
parseable JSON, or the save operation cannot preserve the existing Function.

## Runtime Contract

- This is the saved Function enhancement pass; RunLog is provenance only.
- Enhancement is offline editing only; do not execute the Function while
  enhancing it.
- Model-backed enhancement belongs to the normal Agent conversation. The Agent
  should return one complete revised Function JSON object; `update_function`
  only validates identity/schema and saves it.
- A Function is an executable action stack. At runtime, each UI action goes
  through `ActionExecutor.act`, where replay checkers and action transfer may run
  before the physical device action.
- Do not describe a Function as a one-shot fixed replay. Each action must be
  grounded against a fresh live observation before it is accepted.
- Header enhancement must write a compact but detailed reusable description
  that helps the Agent decide when to call the Function later. Include the
  user-visible operation sequence, required app/page conditions, runtime
  inputs, and success signal when known; avoid coordinates and internal
  implementation details.
- Per-step enhancement must label every executable step/action with what it
  does and why it exists in the trajectory. Each step needs a concise title, a
  concrete description/action_purpose, importance, cleanup_action, and
  cleanup_reason.
- Per-step enhancement may mark steps with useful/merge/drop/noise/
  optional_checker metadata, but this metadata must not rewrite executable
  steps by itself.
- If there is no safe useful improvement for a section, return the current or
  fallback shape for that section rather than inventing content.

## Parameter Rubric

Good runtime parameter candidates:

- Contact or recipient names: `contact_name`, `recipient_name`.
- Phone numbers: `phone_number`.
- Search queries: `search_query`.
- Message bodies: `message_text`.
- Dates or times typed by the user: `target_date`, `target_time`.
- URLs or domains: `target_url`, `website`.
- Object names visible in a task: `target_item`, `playlist_name`,
  `document_name`.

Reject:

- Coordinates: `x`, `y`, `start_x`, `end_y`.
- Bounds and dimensions: `bounds`, `left`, `top`, `width`, `height`.
- Screenshot, XML, node id, or page vector values.
- Paths that do not exist in the current Function.

If an enhancement only changes the name but does not explain when to call it,
what it does, and how success is recognized, treat the enhancement as
incomplete.
