---
name: oob-function-management
description: Compatibility entry for OmniFlow Function management. Use for RunLog registration, "把上一条 runlog 注册了", "保存为复用指令", "复用记忆", "转换轨迹", update_function, list/get/delete reusable functions, variable-parameter Functions, and function_management profile prompts.
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
