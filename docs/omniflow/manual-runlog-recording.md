# Manual RunLog Recording Policy

Manual RunLog recording prioritizes responsiveness and action order. Missing XML
or after evidence must not block the user, freeze recording, or make saving fail.
The canonical record is the ordered user operation: concrete touch trajectory,
optional before XML/screenshot, coordinate target, dispatch status, and final
anchored text input when present.

## Replayable Action Sources

Allowed replayable backends:

- `overlay_touch`
- `overlay_touch_text_input`
- `device_getevent`
- `device_getevent_text_input`

Forbidden replayable backend:

- `accessibility_event`

Accessibility events are evidence only. They may update counters and the final
text for an input that was anchored by a real touch, but they must not create
replayable `click`, `long_press`, or `swipe` steps by themselves. Focus/click
events must not drive overlay state.

## Required Per-Action Evidence

Every recorded action should have:

- a real touch or device input source from the allowed backend list
- a before XML snapshot when it can be captured quickly
- concrete action coordinates or text-input anchor metadata
- action timing metadata where the source can provide it

If before XML is missing, the action remains valid as coordinate-only evidence
and is marked with `source_context_mode = coordinate_only_no_xml` /
`missing_source_xml = true`. After evidence is not required for manual
recording. If dispatch or recording fails, the action diagnostics must preserve
the error instead of blocking the next operation.

## Overlay Recording Flow

The product overlay is the preferred manual capture path:

1. The overlay intercepts the user's touch.
2. The recorder attempts a short before XML/screenshot capture.
3. The overlay temporarily unlocks pass-through.
4. The recorder replays the concrete gesture through the device.
5. The overlay locks again through a single dispatch callback.
6. The action is appended with coordinates and dispatch diagnostics.
7. The UI shows success only when `executed == true` and `recorded == true`.

When execution succeeds but recording fails, the UI must show a recording
failure and keep the session locked/controlled. It must not count that action as
captured.

Text input follows the same rule. Keyboard key taps are not recorded. A
`TYPE_VIEW_TEXT_CHANGED` event can update only the latest pending `input_text`
for the real touch anchor that opened/focused the input; stop/pause/next action
flushes one final value.

When IME is open, the touch overlay is cropped to the foreground App's visible
bottom derived from the input-method-filtered App XML. The IME window frame and
IME child nodes are not geometry sources. If an overlay crop race still catches
a keyboard-area touch, that touch is replayed without XML/screenshot capture and
without a RunLog click action. While a real text-input anchor is active, the
keyboard black-box decision uses a conservative lower-screen fallback instead of
depending solely on `imeTop`.

## Debug And Script Validation

`scripts/oob-record-human-run.sh` audits manual recording artifacts with these
hard checks:

- `manual_recording.a11_replay_actions_enabled == false`
- no action uses `recording_backend = accessibility_event`
- every action backend is in the allowed backend list
- coordinate-only actions without XML are explicitly marked
- debug overlay gesture validation records only overlay-touch backends
- debug overlay gesture validation reports `guarantees_no_missing_clicks = true`

Use:

```bash
scripts/oob-device-manual-trace-validation.sh --device <serial>
```

This wrapper runs one debug overlay click and one debug overlay swipe, then
fails if either operation executes without being recorded or if the artifact
contains an A11-only replay action.

## Artifact Expectations

The audit file is:

```text
<artifact_dir>/audit/recording_audit.json
```

Important fields:

- `schema_version = "oob.manual_recording_audit.v2"`
- `manual_recording.a11_replay_actions_enabled`
- `recording_backend_counts`
- `missing_before_xml_steps`
- `coordinate_only_no_xml_steps`
- `unexplained_before_xml_steps`
- `a11_backend_steps`
- `unexpected_backend_steps`
- `debug_overlay_non_overlay_backend_steps`

`unexplained_before_xml_steps`, `a11_backend_steps`, `unexpected_backend_steps`,
and debug non-overlay backend errors must be empty for acceptance.

Long-term reliability notes and rejected approaches are tracked in
[recording_reliability.md](../recording_reliability.md).
