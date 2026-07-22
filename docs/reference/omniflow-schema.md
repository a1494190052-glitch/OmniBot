# OmniFlow Runtime Schema

OpenOmniBot and OmniFlow share the contracts under `schemas/oob`. The runtime
has three persisted values and one action value object:

- `state`: one captured page state identified only by `state_id`. XML and
  screenshots live in the Android state store and are loaded by id when a
  transfer or diagnostic needs them.
- `RunLog`: the facts of one execution. A step is
  `step_index/before_state_id/action/result/after_state_id`, with optional
  metadata. It never contains XML, screenshots, cards, headers, or storage
  paths.
- `Function`: a reusable program. Each step is
  `step_index/source_state_id/action`; it never copies RunLog evidence.
- `Action`: exactly `{tool, args}`. VLM, manual recording, Function replay,
  and AndroidWorld adapters all produce this shape.

The Android Bridge is JSON Lines and is defined only by
`schemas/oob/omniflow_android_bridge.v2.json`. In particular:

- `record_step` receives one canonical RunLog step and returns the same
  canonical step. Kotlin persists the associated states separately.
- `get_run_log` returns `omniflow.canonical_run_log.v1` directly.
- `get_state` resolves one `state_id` and may include XML for the Python
  transfer operation; that XML is not copied into a RunLog or Function.
- `control_act` is the only replay action loop. Coordinate transfer happens in
  OmniFlow through the canonical OmniTransfer implementation. A failed mapping
  is a failed action and must continue through the normal VLM fallback; source
  coordinates are never replayed unchanged.

Runtime readers reject unknown protocol fields. Legacy aliases such as
`cards`, `header`, `tool_call`, `params`, `arguments` for an Action, camelCase
identifiers, and stringified JSON objects are not accepted by the production
RunLog/Function/UI path. If an import surface needs old data, normalize it
before entering the canonical runtime boundary.

Transfer and startup have explicit fail-closed boundaries. OmniTransfer rejects
non-unique target identities before selecting a candidate, and `control_act`
returns no action on that result. A caller that supplied a complete model route
does not require MMKV/config-store initialization; default stores are read only
when the route actually needs scene or provider configuration.

The generated Kotlin and Dart action accessors are derived from
`oob_canonical_actions.v1.json`; do not edit those generated files directly.
