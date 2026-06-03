# OOB Canonical Action Schema

`oob_canonical_actions.v1.json` is the single source of truth for OOB executable
actions, VLM-visible tools, editor-visible actions, and action argument names.

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
- `scroll` uses `duration_ms`, not `duration`.
- `open_app` uses only `package_name`.
- Back/Home are `press_back` and `press_home`; there is no generic `press_key`.
- Function calls use `oob_function_run(function_id, arguments)`.
