---
name: omniflow
description: OmniFlow reusable Android GUI workflow skill. Use when the user wants to reuse, run, register, repair, enhance, analyze, or debug OOB RunLogs and saved Functions, including update_function, RunLog evidence, replay results, checker design, action cleanup, "应该点 A 而不是 B", "保存为复用指令", "增强 function", and ad/popup optional checkers.
---

# OmniFlow

Use OmniFlow for reusable Android GUI behavior in OOB: converting RunLogs into
Functions, managing saved Functions, enhancing or repairing Functions, replaying
Functions, and learning from replay evidence.

Native Kotlin/MCP code provides storage, replay, UDEG indexing, and tool
backends. The agent behavior belongs in this skill and its references.

## Route The Task

- Overall Function replay architecture, recall, fallback, or over-design
  cleanup: read `references/unified-design.md`.
- Function lifecycle or chat management: read `references/function-management.md`.
- Function enhancement, repair, or step labeling: read `references/function-enhancement.md`.
- RunLog success/failure evidence or `run_id`: read `references/runlog-evidence.md`.
- Replay failure or local runner result analysis: read `references/replay-fallback.md`.
- Ads, popups, permission nudges, skip/close buttons: read `references/checkers.md`.
- New or broken runtime checker implementation, including a global checker that
  needs Kotlin changes: use the `omniflow-checker-maintainer` skill.
- Noisy, duplicate, or unclear actions: read `references/canonical-actions.md`.
- Tool name choice or legacy compatibility: read `references/tools.md`.

Load only the reference needed for the current task. Do not load every reference
by default.

## Core Rules

- Treat Functions as saved mobile workflow tools. A Function may complete the
  user goal or only advance one part of it; after each run result, inspect
  `success` and `result`, then continue with the next Function, VLM path, or
  other tool when the goal remains unfinished.
- Prefer `oob_function_*`, `oob_run_log_*`, and `update_function` for in-app
  OmniFlow Function work.
- Do not explicitly call hidden Function replay tools from a normal agent-task.
  Function execution is selected by runtime recall/replay inside `vlm_task`;
  online fallback should output ordinary UI actions only.
- Use `update_function` for all saved Function modifications.
- Treat RunLogs as evidence. Do not invent RunLogs, Function ids, screenshots,
  XML, or tool results.
- Do not run a low-confidence Function. Recall is candidate context until the
  local runtime gate selects a concrete Function replay or returns a runner result.
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
