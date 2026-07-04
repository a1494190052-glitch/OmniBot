# OmniFlow Shared Schemas

`oob_canonical_actions.v1.json` is the single source of truth for OmniFlow
executable actions, VLM-visible tools, editor-visible actions, and action
argument names.

`omniflow_checker_rule.v1.json` is the shared checker rule contract for
`metadata.checker_rules`. Keep it byte-for-byte aligned with the OmniFlow repo
copy:

- `/Users/wuzewen/Projects/Omni/OpenOmniBot/schemas/oob/omniflow_checker_rule.v1.json`
- `/Users/wuzewen/Projects/Omni/OmniFlow/schemas/oob/omniflow_checker_rule.v1.json`

Native OmniFlow reads checker records through `OmniflowCheckerRule`; Python
OmniFlow must adapt the same schema instead of adding a second checker/trigger
schema.

Do not duplicate action names or argument lists in Kotlin or Dart. Update the
schema first, then regenerate consumers:

```bash
python3 scripts/generate-oob-action-schema.py
```

Generated files:

- `baselib/src/main/java/cn/com/omnimind/baselib/runlog/OobCanonicalActionSchema.kt`
- `ui/lib/features/task/run_log/oob_canonical_action_schema.dart`

Current canonical constraints:

- `input_text` uses only `args.text`.
- `swipe` uses `duration_ms`, not `duration`, and carries `direction`.
- `open_app` uses only `package_name`.
- Back/Home/Enter use `press_key(key)` with `key=back|home|enter`.
- Saved Function recall and replay are selected by the runtime; normal VLM/Agent output must not emit `call_tool(function_id, arguments)`.
