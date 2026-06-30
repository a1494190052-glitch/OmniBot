---
name: omniflow
description: Single OOB automation skill for Android VLM execution, reusable commands, execution records, and checker management. Use for Android vlm_task execution, native tool_calls, prompt/tool schema debugging, indexed UI evidence, grounding, live action dispatch, latency/accuracy validation, and 复用指令 / 轨迹 / execution-record workflows: save, run, register, update, enhance, repair, analyze, or debug reusable commands, RunLogs, and saved Functions, including update_function, automatic checker creation/management, RunLog evidence, replay results, runtime checker, global checker, hi 升级 checker, upgrade/update popup checker, package/open-app checker, action cleanup, "应该点 A 而不是 B", "保存为复用指令", "复用记忆", "增强 function", and ad/popup optional checkers.
---

# OOB Automation

Use this one skill for OOB Android GUI behavior: online VLM execution, saving
execution records as 复用指令, managing saved reusable commands, enhancing or
correcting them, running them through runtime recall/resolve, and learning from
execution evidence. RunLog and Function are internal storage/runtime names for
执行记录 and 复用指令.

Native Kotlin/MCP code provides storage, replay, UDEG indexing, and tool
backends. The agent behavior belongs in this skill and its references.

## Single Entry Point

This skill is the one agent-facing entry for OOB Android GUI automation. All
VLM Android GUI work, 复用指令 lifecycle, enhancement, correction, checker
design, execution-record evidence, Python dev compatibility, and replay
debugging route through this skill.

Older focused entries such as VLM Android GUI, Function lifecycle management,
Function enhancement, and checker maintenance are folded into this skill. Keep
their behavior here or in the `references/` files instead of adding another
model-visible skill.

## Unified Skill Map

- **VLM online execution** lives in `references/vlm-online-execution.md`. Use
  it for `vlm_task`, native `tool_calls`, prompt/tool schema issues, indexed UI
  evidence, grounding, action dispatch, latency, and accuracy.
- **Reusable command runtime** lives in `references/unified-design.md`,
  `references/function-management.md`, `references/function-enhancement.md`,
  `references/runlog-evidence.md`, and `references/replay-fallback.md`. Use
  these for 复用指令 registration, recall, replay, update, and
  evidence-based repair.
- **Checker management** lives in `references/checkers.md`. Use it for
  automatic checker creation/management, runtime checker, global checker,
  upgrade/update popup checker, package/open-app checker, permission prompts,
  ads, and optional obstruction cleanup.
- **Action cleanup and schema discipline** live in
  `references/canonical-actions.md` and `references/tools.md`. Use them to keep
  canonical action names, tool names, and legacy compatibility boundaries
  consistent.

Do not create a separate built-in VLM skill, checker skill, Function skill, or
Python OmniFlow skill. Add or update a reference file under this skill when the
agent needs more guidance.

## Checker Auto Management

When the user asks to create, fix, add, remove, or automatically manage a
checker, keep the work inside this skill:

1. Inspect current 复用指令 metadata, execution evidence, and
   `references/checkers.md`.
2. If the checker is already expressible, update checker metadata through
   `update_function`; do not add new runtime code.
3. If runtime support is missing, add only a generic XML-backed checker type,
   update the canonical checker schema, and add focused runtime tests.
4. Keep checker actions optional and conditional. They may clear ads, popups,
   permission prompts, update prompts, or package/open-app interruptions only
   when live page evidence proves the obstruction exists.
5. Never create app-specific shortcuts, password/captcha bypasses, hidden user
   consent bypasses, network calls, or model-only checker decisions.

## Runtime Boundary

Keep these two meanings separate:

- **OOB-native OmniFlow** is the product runtime for 复用指令, 轨迹, execution
  records, replay, checkers, action transfer, and `vlm_task` recall/resolve.
  User-facing 复用指令 execution must stay on this path.
- **OmniFlow Python CLI/provider in Alpine** is an optional developer and
  evaluation tool installed as `omniflow_dev` in the built-in Linux environment.
  It may run `omniflow-provider`, `omniflow-mcp`, offline evaluation, asset
  import/export, or diagnostics, but it must not replace the native OOB replay
  runtime for normal phone tasks.

When a user asks whether Xiaowan can install OmniFlow, answer yes for the
optional Alpine developer toolchain and explicitly say that saved 复用指令 still
run through OOB-native OmniFlow.

## Route The Task

- Overall Function replay architecture, recall, failure handling, or over-design
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
  failed Function replay returns diagnostics and the ordinary VLM loop may
  continue with normal UI actions.
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
