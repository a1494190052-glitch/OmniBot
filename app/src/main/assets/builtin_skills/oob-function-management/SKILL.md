---
name: oob-function-management
description: Compatibility-only retired entry for old OmniFlow Function management prompts. Do not trigger this skill directly; use omniflow for RunLog registration, "保存为复用指令", "复用记忆", update_function, and reusable Function lifecycle work.
---

# OmniFlow Function Management

This is a compatibility entry for the focused `function_management` tool
profile and old prompts. The canonical layered skill is `omniflow`.

For current behavior, use the `omniflow` skill:

- `omniflow/references/function-management.md` for RunLog registration and
  Function lifecycle.
- `omniflow/references/function-enhancement.md` for enhancement and repair.
- `omniflow/references/runlog-evidence.md` for `update_function({run_id})`
  analysis.
- `omniflow/references/replay-fallback.md` for Function failure result analysis.
- `omniflow/references/tools.md` for tool choice.

Do not add new rules here. Add or update the owning OmniFlow reference instead.
