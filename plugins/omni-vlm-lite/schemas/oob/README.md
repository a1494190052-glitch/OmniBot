# Online VLM Schemas

The built-in Android GUI plugin uses two canonical contracts:

- `oob_canonical_actions.v1.json` defines executable actions as `{tool, args}`.
- `omniflow_canonical_run_log.v1.json` defines captured online execution steps.

Enabling the plugin does not download an archive, Python runtime, OmniTransfer,
Function library, checker rules, or NumPy.

Canonical Actions use `0..1000` relative coordinates, but the VLM wire boundary
uses raw pixels in the current original device display frame so it matches XML
bounds. `omniflow.vlm_coordinates` is the only VLM conversion owner: it converts
canonical recent-action context to pixels before the call and converts validated
raw-pixel tool arguments back to canonical coordinates after the call. Manual
touch capture performs its raw-pixel-to-canonical conversion when the Action is
created. Screenshot transport resizing never changes the declared VLM coordinate
frame.

Android writers persist the canonical five truth fields plus optional
`metadata` directly. Kotlin storage validates the contract before every append
or upsert.

RunLog step truth stays in the five required fields. Optional extensions use
only `metadata`; `step_id`, `status`, `thinking`, and `summary` are metadata,
never step-level aliases. Step success is always read from `result.success`.

Canonical action constraints include:

- `oob_canonical_actions.v1.json` is the only action-field rule source.
- Every RunLog action passes through the schema-driven canonical action
  converter before persistence.
- The converter keeps only arguments whose schema entry does not set
  `persisted: false`; runtime grounding hints such as `target_description`,
  node ids, resource ids, screenshots, and target evidence are never saved.
- Unsupported tools, invalid persisted values, and missing required persisted
  arguments fail conversion; all other non-persisted input is omitted.

The only saved arguments are:

- `click`: `x`, `y`.
- `long_press`: `x`, `y`, optional `duration_ms`.
- `input_text`: `text`.
- `swipe`: `direction`, `x1`, `y1`, `x2`, `y2`, optional `duration_ms`.
- `open_app`: `package_name`.
- `press_key`: `key`.
- `wait`: `duration_ms`.
