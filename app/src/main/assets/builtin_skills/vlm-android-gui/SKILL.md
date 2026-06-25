---
name: vlm-android-gui
description: Compatibility entry for OOB VLM Android GUI automation, AndroidWorld phone tasks, vlm_task, OmniFlow replay, reusable Function generation, and RunLog validation.
---

# VLM Android GUI

This is a compatibility entry for the unified `omniflow` skill.

For current behavior, use the `omniflow` skill:

- `omniflow/references/vlm-online-execution.md` for `vlm_task`, native tool_calls,
  indexed UI evidence, grounding, and live action dispatch.
- `omniflow/references/function-management.md` for RunLog registration and
  Function lifecycle.
- `omniflow/references/runlog-evidence.md` for `update_function({run_id})`
  analysis and RunLog evidence.
- `omniflow/references/replay-fallback.md` for Function failure and replay
  analysis.
- `omniflow/references/tools.md` for tool choice.
- `vlm-android-gui/references/androidworld-m3a-method.md` for AndroidWorld M3A
  evaluation method alignment and OOB Kotlin mapping.

Do not add new VLM step rules here. Add or update the owning omniflow reference
instead.
