# Function Replay Unified Design

This document records the current unified design for OOB Function replay,
recall, fallback, RunLog evidence, and `update_function`. It is the source of
truth for the simplification work: keep one main path, keep compatibility at
the edges, and avoid hidden planner-like replay behavior.

## Goal

Make Function replay easy to describe and hard to fork:

```text
RunLog -> Function -> recall candidates -> agent/VLM chooses oob_function_run
  -> guard -> deterministic replay step by step
  -> fallback_context on failure
  -> agent repairs or resumes with update_function / oob_function_run
```

The runtime should not maintain a second hidden plan, hidden pending queue, or
semantic/navigation recovery layer. If the Function definition is wrong, the
agent fixes the Function through `update_function`; the runner does not invent a
new path silently.

## One Sentence Architecture

OmniFlow Function is a saved, guard-checked, deterministic GUI replay asset:
RunLog and user corrections improve the asset, recall exposes candidates to the
agent/VLM, and `oob_function_run` executes the selected asset step by step with
structured fallback when the real UI no longer matches.

## Concept Unification

Use this vocabulary in code, docs, prompts, and tests:

| Concept | Meaning | Primary surface | Compatibility only |
| --- | --- | --- | --- |
| Function | Saved reusable GUI workflow | `oob_function_*` | old Command wording |
| RunLog | Evidence from an execution or manual recording | `oob_run_log_*` | inline legacy logs |
| Replay | Deterministic execution of explicit Function steps | `oob_function_run` | `call_function`, `run_function`, `omniflow.call_function` |
| Recall | Local candidate lookup before VLM/tool choice | guidance/page context | auto-execution before VLM |
| Repair/enhance | Saved Function mutation | `update_function` | direct JSON mutation |
| Fallback | Structured handoff after replay failure | `fallback_context` | hidden VLM fallback state |

Old `oob_command_*` tools are superseded by Function tools. They should not be
documented as a parallel product path. If a legacy client still has command
records, migrate or expose them as Functions.

Internal graph edges that represent callable Functions should use
`kind=function_call`. Legacy edge inputs with `call_function`, `run_function`,
or `function_transition` can still be normalized on ingestion, but new graph
payloads should not use a tool name as the edge kind.

## Core Concepts

### Function

A Function is a saved reusable GUI workflow compiled from a successful RunLog or
registered explicitly. Its `execution.steps` are the only replay sequence.

Allowed step execution is deterministic and local:

- `click`
- `long_press`
- `input_text`
- `swipe` / normalized scroll action
- `open_app`
- `press_key`
- `finished`
- nested Function replay through `oob_function_run` or compatibility
  `call_tool(function_id=...)`

Each executable step should carry enough agent-facing annotation to be useful on
the next run:

- `title`: short action label, such as `Tap takeout entry`.
- `summary` or description metadata: what the step achieves in the user flow.
- canonical action and arguments: parsed by `OobActionCodec`.
- selector or target hints when available: visible text, package, bounds,
  resource id, or source context.

The annotation is not decorative. Recall, VLM guidance, `update_function`, and
RunLog evidence analysis all depend on these labels to decide whether a step is
required, optional, noisy, duplicated, failed, or successful.

### OmniFlow Runner

The runner is a deterministic replay enhancer, not a planner. For each step it:

1. materializes arguments,
2. re-grounds the target against current UI evidence when possible,
3. performs the concrete action,
4. records the result,
5. moves to the next explicit step only after success.

It does not automatically skip step 2 and 3 because the page appears to be at
step 4. If an old path no longer matches the real app, replay fails with
`fallback_context`, and the agent decides whether to continue manually or repair
the Function.

The runner may skip only compile-time or classifier-level no-op cards, such as
observation-only `get_state`, failed cards, or a perception wrapper that has
already been replaced by a concrete action. This is cleanup, not runtime path
planning.

### Recall

Recall is local candidate retrieval. It should normally take milliseconds to
tens of milliseconds. It does not call the VLM model and it does not execute a
Function.

Recall output is context for agent/VLM decision:

```text
goal + current page context
  -> local Function/UDEG candidate lookup
  -> currentPageSummary / guidance payload
  -> VLM chooses click, scroll, input_text, or oob_function_run
```

Recall may return parameterized Functions. That is valid: the agent fills
arguments from the user goal using `inputSchema`, `function_profile`, and
`argument_policy`, just like a normal tool call.

Recall ownership is deliberately split:

- the local recall layer finds candidates and writes compact guidance,
- the VLM/agent decides whether a candidate matches the current goal,
- `oob_function_run` executes only after that decision.

This keeps strict local matching from blocking useful parameterized Functions
while still preventing automatic wrong-function execution.

### VLM Function Tool

The model-visible Function action is:

```text
oob_function_run(function_id, arguments?)
```

`call_function`, `run_function`, and `omniflow.call_function` are compatibility
names only. They may remain in parser, old RunLog, and old MCP adapter code, but
they must not be the primary agent-facing tool, prompt, or schema.

New compiled or registered Function steps that invoke another Function should
write `tool=oob_function_run` and `callable_tool=oob_function_run`. If the
source evidence came from `call_function`, keep that fact only in `source_tool`
or import metadata. This makes the main replay path read the same way in
Function specs, tool-card events, VLM guidance, and MCP schemas.

### Fallback

Replay failure returns structured context instead of silently switching to live
VLM:

```json
{
  "model_required": true,
  "failed_step_index": 2,
  "resume_from_step": 3,
  "fallback_context": {
    "failed_step": {},
    "remaining_steps": []
  }
}
```

`failed_step_index` is the step the agent must complete manually. After the
agent completes that failed step, `resume_from_step` points to the next local
step to replay. To retry the failed step itself, pass `failed_step_index`
explicitly as the start step.

The agent can:

- continue with bounded live VLM for the failed step,
- call `oob_function_run` again with the returned `resume_from_step`
  (`start_step_index` is only a compatibility alias),
- call `update_function` with RunLog evidence if the Function should be
  repaired.

Nested Function failures bubble up. If a parent step calls another Function and
the child Function needs agent fallback, the parent step is marked
`model_required=true` and includes nested fallback metadata. The parent
`oob_function_run` still returns one fallback handoff for the parent step; it
does not silently swallow the child failure.

If the agent's manual action fixes the failed step, it can resume from the
returned step index. If the manual action proves the Function is wrong, it
should save evidence through `update_function` instead of relying on the one-off
manual recovery.

## Tool Surface

### Preferred In-App Tools

Use these for current OOB Function work:

```text
oob_function_list
oob_function_get
oob_function_register
update_function
oob_function_guard_check
oob_function_run
oob_function_delete
oob_function_clear
oob_run_log_list
oob_run_log_get
oob_run_log_convert
```

### Legacy Adapter Tools

These may exist for external or older MCP clients:

```text
omniflow.recall
omniflow.call_tool
omniflow.ingest_run_log
omniflow.explore_replay
omniflow.call_function
call_function
run_function
```

They route to the same Function store and runner. Do not design a second replay
flow around them.

### Model-Visible Tool Policy

When a VLM task receives tool definitions, `oob_function_run` should appear next
to normal GUI tools such as click, scroll, and input. The model can choose it
and fill arguments when the recall guidance says a candidate Function is likely.

Do not expose `call_function` or `run_function` as primary model-visible tool
names. They are accepted only where compatibility is required:

- old RunLog cards,
- parser aliases,
- older MCP adapter clients,
- historical reports and tests that assert migration behavior.

## `update_function`

`update_function` is the only saved Function mutation path.

It supports two modes:

### Evidence Packaging

Input:

```json
{
  "functionId": "saved_function_id",
  "run_id": "runlog_id"
}
```

Output:

```json
{
  "needs_agent_analysis": true,
  "analysis_context": {},
  "agent_prompt": "..."
}
```

The Kotlin side only packages Function + RunLog evidence. It does not run a
complex rules engine.

### Agent Save

Input:

```json
{
  "functionId": "saved_function_id",
  "run_id": "runlog_id",
  "analysis": {},
  "patch": {}
}
```

The analysis is stored under Function metadata. If the patch is safe, the
Function is updated. If no patch is provided, evidence metadata is still saved.

### User Correction Mode

Natural-language corrections such as:

```text
应该点「外卖」而不是点「美食」
```

must be translated into structured analysis plus patch operations before save.
The tool may add, delete, or reorder actions only when structural changes are
explicitly allowed. Retargeting, step labels, descriptions, selector hints, and
checker additions are safer default patches.

### Offline Enhancement Mode

Initial enhancement is offline. It should not execute the Function. It should:

- clean deterministic noise,
- annotate every remaining action,
- enrich the Function description with what the Function does,
- add parameter descriptions and selector hints,
- classify transient blockers as optional checkers,
- save through the same `update_function` path when mutating an existing
  Function.

## RunLog Evidence Skill

The agent should analyze RunLog evidence using this structure:

```json
{
  "summary": "Why this RunLog succeeded or failed",
  "step_findings": [
    {
      "function_step_index": 1,
      "runlog_card_index": 3,
      "label": "点击外卖入口",
      "role": "required_action | optional_checker | noise | duplicate | failed_action | success_evidence",
      "reason": "Why this role is correct"
    }
  ],
  "failure_reason": {
    "code": "wrong_target | target_missing | ad_interruption | repeated_input | unstable_coordinate | unknown",
    "message": "Concrete reason"
  },
  "recommended_patch": {
    "ops": []
  }
}
```

Rules:

- Success RunLogs may improve description, step title, step summary, selector
  hints, and parameter descriptions.
- Failed RunLogs may only change steps when evidence is clear.
- Ads, skip buttons, popups, and permission nudges become optional checkers, not
  mandatory happy-path actions.
- `wait`, pure perception wrappers, failed action cards, and duplicate input
  cards are noise unless they carry specific evidence.
- If uncertain, save analysis only or produce a suggested patch, not a main-path
  structural change.

## Checkers

Checkers are optional controls around the deterministic path. They can detect
and dismiss transient blockers such as:

- ads,
- skip buttons,
- close buttons,
- permission nudges,
- keyboard covering the target,
- obvious modal overlays.

Checker logic must not become a hidden planner. If a checker is absent, the
main Function path should still attempt the explicit current step.

Ad and popup detection should prefer concrete UI evidence:

- visible text such as `跳过`, `关闭`, `广告`, `Skip`, `Close`, `Ad`,
- close/skip icon content descriptions,
- full-screen overlay bounds that block the target,
- package or view metadata known to be ad/permission surfaces,
- repeated replay failures caused by the same transient blocker.

These detections should become optional checkers. They should not be inserted as
mandatory happy-path steps unless the user explicitly wants that behavior.

## End-To-End Lifecycle

```text
1. Capture or import RunLog
2. Convert RunLog to Function
3. Canonicalize actions through OobActionCodec
4. Drop or merge deterministic noise
5. Annotate steps and optional checkers
6. Register Function
7. Recall candidate for a future goal/page
8. Agent/VLM chooses oob_function_run and fills arguments
9. Guard check runs
10. Runner executes Function.steps in order
11. Success records run_id
12. Failure returns fallback_context
13. Agent resumes or calls update_function with evidence
```

This is the only product-level loop. GUI bridge, MCP adapters, and legacy tool
names are entrypoints into this loop, not separate systems.

## Removed Or Deprecated Runtime Ideas

Do not reintroduce these as primary runtime fields:

- `PendingActionStack`
- `pending_action_stack`
- `source_alignment_enabled`
- `skipped_by_source_alignment_count`
- automatic step skipping because a later state looks satisfied
- semantic/navigation recovery as a parallel path
- `navigate_recovery_available`
- `blocked_executor`
- `fallback_available`
- generic `needs_agent` as a replay-state shortcut
- `omniflow_vlm_fallback` as a first-class executor/state

Important distinction: `needs_agent` may still exist as a guard decision or a
legacy compatibility value. It should not be used as a new runtime queue or
planner state.

## What To Keep

Keep these capabilities:

- deterministic local replay,
- per-step re-grounding,
- guard checks,
- optional checker handling,
- fallback context,
- resume from step,
- RunLog conversion,
- RunLog evidence analysis through agent skill,
- `update_function` as the only mutation path,
- compatibility parsing for old tool names.

## Code Ownership

For the detailed engineering map, including per-file responsibility,
compatibility boundaries, deprecated concepts, and maintenance rules, read
`IMPLEMENTATION_MAP.md`. The list below is the compact owner map.

Core Function backend:

- `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionRepository.kt`
- `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionRunner.kt`
- `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionUpdateService.kt`
- `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionRecallService.kt`
- `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionRunLogEvidencePackager.kt`
- `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionRunPolicy.kt`
- `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionToolNames.kt`
- `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionSkillProfile.kt`
- `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionSpecBuilder.kt`
- `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionStructuralPatchApplier.kt`
- `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionCheckerPatchService.kt`

Replay and RunLog policy:

- `app/src/main/java/cn/com/omnimind/bot/runlog/RunLogReplayPolicy.kt`
- `app/src/main/java/cn/com/omnimind/bot/runlog/OobActionCodec.kt`
- `app/src/main/java/cn/com/omnimind/bot/runlog/OobStepRoleClassifier.kt`
- `app/src/main/java/cn/com/omnimind/bot/runlog/RunLogReplayStepCompiler.kt`
- `app/src/main/java/cn/com/omnimind/bot/runlog/RunLogReplayStepNoiseNormalizer.kt`
- `app/src/main/java/cn/com/omnimind/bot/runlog/RunLogReusableFunctionCompiler.kt`
- `app/src/main/java/cn/com/omnimind/bot/runlog/OobFunctionSchemaBuilder.kt`
- `app/src/main/java/cn/com/omnimind/bot/runlog/OmniflowStepExecutor.kt`
- `app/src/main/java/cn/com/omnimind/bot/runlog/OobOmniFlowToolkitService.kt`

Agent/MCP tool surface:

- `app/src/main/java/cn/com/omnimind/bot/mcp/McpToolDefinitions.kt`
- `app/src/main/java/cn/com/omnimind/bot/mcp/McpRoutes.kt`
- `app/src/main/java/cn/com/omnimind/bot/agent/tool/AgentToolDefinitions.kt`
- `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/OobFunctionToolHandler.kt`

VLM recall and Function action:

- `app/src/main/java/cn/com/omnimind/bot/vlm/VlmToolCoordinator.kt`
- `app/src/main/java/cn/com/omnimind/bot/vlm/VlmRecallGuidanceBuilder.kt`
- `app/src/main/java/cn/com/omnimind/bot/vlm/OobVlmPageContextProvider.kt`
- `assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/VLMToolDefinitions.kt`
- `assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/VLMClient.kt`
- `assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/VLMFunctionRunRegistry.kt`
- `assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/ActionExecutor.kt`

Built-in skills:

- `app/src/main/assets/builtin_skills/omniflow/SKILL.md`
- `app/src/main/assets/builtin_skills/omniflow/references/`
- `app/src/main/assets/builtin_skills/oob-function-management/SKILL.md`
- `app/src/main/assets/builtin_skills/vlm-android-gui/SKILL.md`

## Documentation Map

Use this file for the unified product and architecture decision. Use the
specialized docs only for details:

- `README.md`: package entrypoint and external modes.
- `IMPLEMENTATION_MAP.md`: engineering maintenance map for main path, code
  ownership, tool naming, compatibility layers, removed concepts, fallback,
  checker handling, and future cleanup rules.
- `MCP_CONTRACT.md`: exact MCP surface and legacy adapter behavior.
- `FUNCTION_SPEC.md`: Function JSON shape and executor rules.
- `canonical-actions.md`: compact action vocabulary and aliases.
- `cleanup-rules.md`: deterministic RunLog noise cleanup.
- `checkers.md`: optional checker design.
- `update-function.md`: mutation contract and RunLog evidence save flow.
- `GUI_AGENT_PLAYBOOK.md`: external GUI-agent workflow.
- `oob-function-architecture.md`: ownership boundary.
- `app/src/main/assets/builtin_skills/omniflow/references/unified-design.md`:
  compact agent-facing version of this document.

## Cleanup Backlog

Use this list when continuing simplification:

- Keep `OobFunctionSpecBuilder`, `OobFunctionSchemaBuilder`, and
  `RunLogReplayStepCompiler` only if each has a single clear responsibility:
  spec construction, schema/profile export, and RunLog-card compilation.
- Remove local action-name `when` blocks when they duplicate
  `OobActionCodec`.
- Remove local step-role heuristics when they duplicate
  `OobStepRoleClassifier`.
- Keep `requires_agent_fallback` only as legacy/import metadata or a direct
  guard/tool-delegation result. New specs should write `has_agent_steps`; code
  that must read legacy specs should go through
  `OobFunctionSpecVocabulary.agentStepFlag`. Do not use it as a hidden replay
  queue.
- Keep source/target matching only when it directly improves the current step's
  re-grounding. Do not reintroduce source alignment skip counters or pending
  action stacks.

## Acceptance Checklist

- Model-visible VLM tools expose `oob_function_run`, not `call_function` or
  `run_function`.
- MCP fixed tools expose `oob_function_run`; `omniflow.call_function` is not a
  fixed primary tool.
- Recall returns candidate context and timing, not automatic execution.
- Parameterized recall candidates include enough schema/profile/policy for the
  agent to fill arguments.
- `oob_function_run` can resume from a step.
- Failed replay returns `fallback_context`, `failed_step_index`,
  `resume_from_step`, and `remaining_steps`.
- Runtime output does not expose `pending_action_stack`, `source_alignment`,
  `source_alignment_enabled`, or `skipped_by_source_alignment_count`.
- `update_function(functionId, run_id)` returns `needs_agent_analysis=true` and
  an agent prompt.
- `update_function(functionId, run_id, analysis, patch)` saves analysis metadata
  and safe patches.
- Active docs and built-in skills name `oob_function_run` as the main replay
  tool and mark `call_function` names as compatibility only.
