---
name: omniflow
description: Single OOB Android GUI automation and OmniFlow reusable workflow skill. Use for online VLM Android GUI automation, vlm_task execution, native tool_calls, prompt/tool schema debugging, indexed UI evidence, grounding, live action dispatch, VLM RunLog latency/accuracy validation, and when the user wants to reuse, run, register, update, enhance, repair, analyze, or debug OOB RunLogs and saved Functions, including update_function, RunLog evidence, replay results, checker design, runtime checker, global checker, hi 升级 checker, upgrade/update popup checker, package/open-app checker, action cleanup, "应该点 A 而不是 B", "保存为复用指令", "复用记忆", "增强 function", and ad/popup optional checkers.
---

# OmniFlow

Use OmniFlow for OOB Android GUI behavior: online VLM execution, converting
RunLogs into Functions, managing saved Functions, enhancing or correcting
Functions, replaying Functions through runtime recall/resolve, and learning
from replay evidence.

Native Kotlin/MCP code provides storage, replay, UDEG indexing, and tool
backends. The agent behavior belongs in this skill and its references.

## Single Entry Point

This skill is the one agent-facing entry for OOB Android GUI automation and
OmniFlow Function lifecycle, enhancement, correction, checker design, RunLog
evidence, and replay debugging. Older focused entries such as VLM Android GUI,
Function lifecycle management, Function enhancement, and checker maintenance are folded
into this skill. Keep their behavior here or in the `references/` files instead
of adding another model-visible skill.

## Runtime Boundary

Keep these two meanings separate:

- **OOB-native OmniFlow** is the product runtime for 复用指令, 轨迹, RunLog,
  replay, checkers, action transfer, and `vlm_task` recall/resolve. User-facing
  Function execution must stay on this path.
- **OmniFlow Python CLI/provider in Alpine** is an optional developer and
  evaluation tool installed as `omniflow_dev` in the built-in Linux environment.
  It may run `omniflow-provider`, `omniflow-mcp`, offline evaluation, asset
  import/export, or diagnostics, but it must not replace the native OOB replay
  runtime for normal phone tasks.

When a user asks whether Xiaowan can install OmniFlow, answer yes for the
optional Alpine developer toolchain and explicitly say that saved 复用指令 still
run through OOB-native OmniFlow.

## Route The Task

- Overall Function replay architecture, recall, runtime resolve, or over-design
  cleanup: read `references/unified-design.md`.
- Online VLM execution, `vlm_task`, native `tool_calls`, prompt/tool schema
  debugging, indexed UI evidence, grounding, live action dispatch, or VLM
  RunLog latency/accuracy validation: read `references/vlm-online-execution.md`.
- Saved Function lifecycle or chat-driven registration: read `references/function-management.md`.
- Function enhancement, repair, or step labeling: read `references/function-enhancement.md`.
- RunLog success/failure evidence or `run_id`: read `references/runlog-evidence.md`.
- Replay failure or local runner result analysis: read `references/replay-fallback.md`.
- Ads, popups, permission nudges, skip/close buttons: read `references/checkers.md`.
- New or broken runtime checker implementation, including a global checker,
  upgrade/update popup checker, package/open-app checker, or Kotlin runtime
  change: read `references/checkers.md`, then update the runtime checker code
  and focused tests.
- Noisy, duplicate, or unclear actions: read `references/canonical-actions.md`.
- Tool name choice or legacy compatibility: read `references/tools.md`.

Load only the reference needed for the current task. Do not load every reference
by default.

## Core Rules

- Treat Functions as saved mobile workflow tools. A Function may complete the
  user goal or only advance one part of it; after each run result, inspect
  `success` and `result`, then continue with the next Function, VLM path, or
  other tool when the goal remains unfinished.
- Use `vlm_task` for online phone-screen automation. The VLM provider must
  return native OpenAI-compatible `tool_calls`; text actions or `function_id`
  wrappers are contract violations, not executable actions.
- Prefer `oob_function_*`, `oob_run_log_*`, and `update_function` for in-app
  OmniFlow Function work.
- Do not explicitly call hidden Function replay tools from a normal agent-task.
  Function execution is selected by runtime recall/replay inside `vlm_task`;
  runtime resolve is the single internal model-assist path. It may fill public
  Function parameters before replay or output only one ordinary UI action for
  the current failed step.
- Use `update_function` for all saved Function modifications.
- Treat Function enhancement as explicit offline/background maintenance. It
  must not run inline before VLM auto-registration, recall-hit replay, direct
  Function execution, or debug convert-and-replay. If enhancement is running,
  the current saved Function can still be replayed as-is.
- Treat RunLogs as evidence. Do not invent RunLogs, Function ids, screenshots,
  XML, or tool results.
- Do not run a low-confidence Function. Recall is candidate context until the
  local runtime selects a concrete Function replay or returns a runner result.
- Mark transient obstruction handling as optional checkers, not mandatory happy
  path actions.
- Do not add, delete, or reorder executable steps unless the user explicitly
  requests a structural repair.
- Design minimally. Do not introduce new names, framework layers, state types,
  or tool concepts unless existing OOB concepts cannot express the change.
  Prefer reusing the current Function, RunLog, UDEG node, checker, action
  transfer, and VLM tool logic. Before changing code, decide whether the need is
  only a filter, ranking rule, validation, or parameterization of existing
  logic. Deterministic local checks should stay local; do not ask the model to
  infer system reachability or replay safety.

## Output Discipline

When reporting to the user, use product language: "复用指令", "轨迹",
"执行结果", "已增强", "需要继续处理". Avoid exposing raw MCP/tool plumbing
unless the user asks for implementation details.
If a user says "复用记忆", treat it as a compatibility phrase for saved
OmniFlow 复用指令, but keep the product wording as "复用指令" in replies.
