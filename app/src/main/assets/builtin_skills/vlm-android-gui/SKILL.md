---
name: vlm-android-gui
description: Use for OOB VLM Android GUI automation, AndroidWorld phone tasks, vlm_task, OmniFlow replay, reusable Function generation, and RunLog validation.
---

# VLM Android GUI Skill

## Step Guidance Essentials

- Quick policy phrases for injected guidance: Pass `package_name` when known; OOB indexed page evidence chooses visible labels/roles; focused editable input uses `input_text`; slider/seekbar uses `scroll` as a drag; use absolute screen-pixel coordinates from the current indexed evidence or XML grounding; x1 must be greater than x2 for leftward drag; Display brightness is a drag, do not click; Numeric keypad targets use visible digit clicks; Validate after at least two visible UI states; choose the simplest action.
- First-step policy lives here; choose the simplest action that changes one variable, then verify.
- AndroidWorld first-step policy: choose the first safe state-changing action,
  then validate before continuing.
- M3A/Mobilerun-style per-step loop: observe one fresh Accessibility tree /
  indexed UI state, current screenshot, short history, and previous tool result;
  choose one action, then use after-action feedback to correct the next step.
  Marked screenshots are optional fallback evidence, not the default.
- M3A-style per-step loop means one observation, one action, one validation
  result.
- Mobilerun-style structured loop is a reference pattern, not a runtime
  replacement: inject current device state, indexed page evidence, screenshot,
  and the previous tool result; require exactly one executable tool call; then
  feed structured action results back into the next turn.
- Protocol correction retries for the same unchanged screen may omit the
  screenshot when indexed Accessibility evidence is present; do not treat that
  as a new observation turn.
- If the screenshot is black or blank but Accessibility tree / indexed evidence
  lists the current page and target text, act from that tree evidence. Do not
  output refresh-state, wait, or no-op actions for the same unchanged page
  solely because pixels are black. Native observe refreshes state internally.
- OOB indexed page evidence: choose by visible label/role; include `element_index`
  or `scrollable_index` when available and emit absolute screen-pixel centers as fallback.
  The runtime will re-ground clicks/input/long-press by stable `node_id`,
  `element_index`, or unique target description against the latest XML before
  dispatch.
- Pass `package_name` when known; derive unknown packages from installed apps.
- Permission/onboarding: choose safe Continue/Allow/OK, not Deny/Delete/Pay.
- Focused editable input: use `input_text`; otherwise click intended edit/search field first.
- Visible but not focused editable input: use
  `input_text(target_description, text, element_index?, x, y)` so the field is
  grounded before typing.
- Slider/seekbar: use a horizontal drag toward the requested endpoint; do not
  repeat taps on the thumb or hard-code screen-specific coordinates.
- Numeric keypad targets: click visible digit buttons; do not use `input_text`.
- After each action, read `screen_changed`, `appeared_texts`,
  `disappeared_texts`, `after_visible_texts`, and `after_focused_editable` from
  the tool result before choosing the next action. If an action does not change
  the expected variable, re-ground instead of repeating it.
- Validate after at least two visible UI states before `finished`.
- Multi-target goals: keep ordered checklist; finish only after named targets verified.

## First-Step AndroidWorld Rules

AndroidWorld first-step policy:

- Do not encode assumptions into the first action. Pass `package_name` when
  known, otherwise derive it from installed app evidence.
- If the first screen is a permission, onboarding, or account prompt, handle the
  safe visible prompt before pursuing the target task.
- If an editable field is already focused, use `input_text` directly. If not, click or
  `input_text` the intended field first.
- For sliders, seekbars, and system panels, use drag actions. For Display brightness,
  drag near 90-95% of the current indexed/XML absolute pixel width. To move a
  horizontal panel left, use a leftward absolute-pixel drag where `x1` is greater
  than `x2`, not `click`.
- For on-screen numeric keypads, click the visible digit buttons.

Validation prompts must compare at least two UI states before finishing.

## Runtime Flow: VLM, UDEG, RunLog, Function

This is the canonical OOB execution flow. Keep the live VLM loop, UDEG recall,
RunLog conversion, Function enhancement, and deterministic Function replay
connected by artifacts, but do not merge their responsibilities.

1. Start `vlm_task` for the user's phone goal. The native runtime opens an
   Internal RunLog and records concrete tool cards, token usage, timing
   diagnostics, post-action observations, and terminal status.
2. For every action turn, read one fresh current-page snapshot: current package,
   Accessibility XML, indexed UI evidence, screenshot, display size, and
   timestamp. Reuse it only for tool-call format correction on the same
   unchanged screen. Before dispatching a precise action, read latest XML once
   and re-ground against it; do not fail the turn just because the page changed
   during model latency.
3. Build dynamic page context only from that fresh snapshot. UDEG recall is:
   `page match -> UDEG node -> node skill-like decision context -> VLM/tool decision`.
   The UDEG node skill is OOB's page-level app-card equivalent. Inject it into
   `currentPageSummary` as current-page decision context, not into pre-run
   `stepSkillGuidance`, not as task memory, and not by flat-scanning all
   Functions.
   Recall is local infrastructure: it should usually take milliseconds to tens
   of milliseconds, with slow debug/device cases measured in the returned
   timing payload. It does not call the VLM model.
4. Treat node-attached Functions as callable capability candidates. Online VLM
   receives high-confidence matches as native tools in the current turn's
   `tools[]`; the Function id is the tool name. Choose one exactly like
   `click`, `input_text`, or `scroll` when it clearly matches the user goal, and
   fill its arguments from the user request. Do not call hidden guard or replay
   tools from the VLM loop.
5. A Function tool may execute multiple phone actions. Its local runner owns
   checker handling, action transfer, replay safety, and RunLog cards. The model
   only sees the returned `success` and `result`, then the next turn starts from
   a fresh page observe.
6. If a Function tool fails, do not resume hidden replay or ask an outer Agent to
   take over. Continue with one normal bounded VLM step from the fresh current
   page and the failed tool result. If the saved Function itself is wrong, repair
   it later with `update_function` and RunLog evidence.
7. Convert/register a successful VLM or human-recorded RunLog only when it
   contains replayable concrete actions and finished successfully. Failed,
   unfinished, empty, perception-only, or diagnostic-only RunLogs must not
   become reusable Functions.
8. After registration, Function enhancement may improve name, description, step
   labels, runtime parameter metadata, and `agent_reuse`. It must not change
   executable step order, tool names, executors, concrete args, validation,
   fallback, or callable definitions.

RunLog registration is not the harness. Registration is
`RunLog -> compile -> Function store -> UDEG node attachment`. A harness is a
driver or validation wrapper that may launch VLM, collect a RunLog, register it,
enhance it, replay it, and assert device state. A Function is validated only
after guard-check plus real replay, or it remains an explicit candidate.

Tool ownership:

- RunLog inspect/list: `oob_run_log_get`, `oob_run_log_list`.
- RunLog conversion/registration: `oob_run_log_convert` or
  `convertInternalRunLogToOobFunction`.
- Function management: `oob_function_list/get/register/delete/clear`; direct
  Function replay is currently selected by VLM dynamic tools or internal
  runners, not normal agent-task tools.
- Function enhancement: `omniflow-function-enhancer` skill or background
  enhancement job, writing `metadata.oob_enhancement`.
- Function recall: UDEG current-page match returning node decision context and
  node-attached Function candidates.
- Agent-facing Function cards: show Function execution as its own reusable
  Function card, not as hidden JSON under the parent VLM result. Nested
  Function steps may still use legacy internal labels in raw diagnostics.

## Overview

Use this skill when the user wants OOB to operate an Android screen, run a
VLM task, validate an AndroidWorld-style scenario, replay a stored reusable
command, or debug why a phone task did not execute.

This skill is for OOB's executable phone runtime. Open-source model skills such
as LLaVA, BLIP-2, or CLIP are useful references for vision-language modeling,
but they do not replace OOB's `vlm_task`, accessibility actions, RunLog, or
OmniFlow replay path.

Mobilerun/Droidrun is also only a design reference for OOB, not an executable
dependency. Its FastAgent uses a Python host, Portal Android app, indexed
Accessibility tree, optional screenshot, XML-style tool calls, and structured
tool results. OOB must keep the native Kotlin VLM loop and its own
Accessibility, RunLog, reusable Function registration, recall, and replay path;
borrow the structured observation/result discipline without calling Portal,
installing Mobilerun runtime, or delegating actions to Python.

## Mobilerun Reference Flow

Record Mobilerun as a process reference only. The goal is to capture useful
workflow shape, then reimplement the matching OOB behavior in native Kotlin:

1. Fetch a fresh device state every turn: Accessibility tree, phone state,
   screen bounds, and screenshot.
2. Format the tree into indexed UI evidence with stable element indexes.
3. Build one LLM turn from the goal, current device state, optional screenshot,
   short memory/history, and the previous tool result.
4. Require one structured tool block per model response. Multiple concrete
   invokes are allowed only when they are clearly sequential on the same stable
   screen; OOB should normally keep one native action per turn.
5. Execute actions through a small registry: indexed click, coordinate click,
   input_text, scroll, open app, back/home, wait only as internal settling, and
   explicit completion.
6. Feed structured action results back into the next turn instead of relying on
   free-form chat history.
7. Persist trajectory artifacts for inspection: UI state, screenshot when
   enabled, tool call, tool result, success/failure, and token usage.

Borrow these advantages in OOB:

- Keep the VLM prompt grounded in indexed Accessibility evidence plus one
  current screenshot.
- Do not resend unchanged pixels for a tool-call protocol correction when the
  text Accessibility evidence is sufficient.
- Make tool result schemas explicit and stable, especially after-action page
  changes and failure reasons.
- Keep the action surface small and deterministic.
- Track short memory/history for facts that must survive navigation.
- Treat state fetch robustness as part of the agent loop: retry transient page
  read failures, then recover OOB Accessibility state before asking the model
  to act on stale evidence.
- Separate method/reference runners from the production runtime.

OOB mapping for the borrowed flow:

- Mobilerun `StateProvider` shape -> OOB `readCurrentPackage`,
  `readCurrentPage`, screenshot capture, and post-action observation.
- Mobilerun indexed tree formatter -> OOB indexed Accessibility evidence.
- Mobilerun tool registry -> OOB native `VLMToolDefinitions` and
  `DeviceOperator` actions.
- Mobilerun structured results -> OOB structured tool result and RunLog card
  post-action fields.
- Mobilerun trajectory artifacts -> OOB RunLog, reusable Function registration,
  replay, UDEG node recall, token usage, and timing diagnostics.

Do not borrow these parts as dependencies:

- Portal app installation, TCP/content-provider protocol, or Python driver.
- Mobilerun prompt templates as runtime prompts.
- Mobilerun macro replay format.
- A host-side agent loop that replaces OOB Kotlin `vlm_task`, RunLog, reusable
  Function registration, UDEG recall, or model-free replay.
- Mobilerun CLI/MCP, package import, or runtime installation in OOB validation.

## Activation

Activate when the user asks for any of these:

- `vlm_task`, VLM task, 小万视觉执行, screen automation, phone automation, or
  AndroidWorld validation.
- Click, scroll, input text, open app, or verify content on the current Android screen.
- A long phone task that must keep acting until a visible stop condition is met.
- Convert a successful VLM RunLog to a reusable Function.
- Run a stored reusable Function through an exposed dynamic Function tool or
  inspect why replay failed.
- Compare live VLM behavior with OmniFlow replay behavior.
- Register, list, inspect, delete, or clear OOB reusable Functions/复用指令.

Do not activate for ordinary image Q&A when the user only uploaded a picture and
does not ask to operate the phone screen.

## Execution Mode

Use `vlm_task` as the public online execution entrypoint. A saved Function is
just another tool that may be exposed to the VLM in a turn; it is not a second
online planner. If a Function runner fails, return its compact `success/result`
payload and let the next `vlm_task` turn do one fresh observe and one normal VLM
step. Manual takeover remains a user-recorded source, not a model fallback path.

If VLM creates a successful RunLog and the user wants reuse, convert that RunLog
to a reusable Function after the run.

RunLog source labels must describe how the step actually executed, not whether
the action is convertible:

- Agent/VLM: online `vlm_task` or `compile_kind=vlm_step`; VLM token fields are
  online generation cost.
- Human: `source=human_takeover` or `compile_kind=manual_recording`.
- OmniFlow Replay: direct reusable Function replay with
  `source/run_source=omniflow_replay` or `runner=oob_omniflow_replay`.

Concrete actions such as `click`, `input_text`, and `scroll` in an online VLM
RunLog are only OmniFlow-compatible; do not label them as offline replay unless
the replay runner metadata is present.

## Direct VLM Task

Use `vlm_task` for live Android GUI work:

```json
{
  "goal": "From the target app home screen, navigate to the requested page, verify the visible finish condition, then finish.",
  "package_name": "<target-package>",
  "startFromCurrent": false,
  "maxSteps": 12,
  "needSummary": true
}
```

Guidelines:

- Follow the canonical Runtime Flow above for fresh-page evidence, UDEG recall,
  Function candidate handling, and RunLog conversion boundaries.
- For bare online validation, set `disableOmniFlowRecall=true`.
- Include the app, starting point, target page, and visible finish condition.
- Set `startFromCurrent=true` only when the user explicitly wants the current
  page or the target package is already foreground.
- Pass `package_name` when the target app is known and the task should start from
  that app.
- Use `maxSteps` high enough for long tasks; prefer 8 to 20 for AndroidWorld
  validation instead of tiny click-only smoke tests.
- Require visible verification before `finished`.
- When the prompt includes `OOB indexed page evidence`, use it as grounded page
  evidence: match the pending target by visible label/role, copy its normalized
  center as action coordinates, include `element_index` for `#N` rows or
  `scrollable_index` for `S<N>` rows, and keep `target_description` tied to that
  row's label. Treat the screenshot as visual confirmation and the indexed tree
  as the coordinate source.
- The per-turn `Relevant installed apps` section is intentionally a compact
  focused list, not the full package inventory. Use an exact package from that
  list or the request `package_name` when opening an app; if the needed app is
  absent, observe or ask for clarification instead of guessing a hidden package.
- The per-turn prompt keeps stable tool/coordinate rules in the system prompt
  and sends only a compact reminder plus live page evidence. Do not compensate
  by adding long repeated protocol text to user goals; put validation criteria
  in the goal and rely on the tool schema/system prompt for the action contract.
- If the desired target is not present in the indexed element list and is not
  visually visible, scroll a listed scrollable region once, then re-observe. Do
  not tap the first unrelated row.
- For scrollable regions, use `scrollable_index` plus `direction` (`down` to
  reveal lower content, `up` to reveal previous content) and provide the listed
  absolute screen-pixel scroll coordinates as fallback.
- If an editable element is focused, use `input_text(text)` directly. If no input
  is focused, first click the intended editable/search field by indexed center,
  then use `input_text` on the next step after focus is confirmed.
- Before each action, reduce the decision to input, desired output, and the one
  UI variable that should change. After the tool result, compare the new visible
  state with that expected change and correct only that variable.
- For goals with several named targets such as "open A, verify A, go back, open
  B, verify B", keep an ordered checklist. Do not skip directly to B, and do not
  call `finished` until A and B were both visited or verified in order.
- Do not change destructive or privacy-sensitive settings without confirmation.

## Real Device Startup

Before judging live VLM or Function behavior, normalize the runtime with the
canonical one-click entrypoint:

```bash
OOB_MCP_TOKEN=<token> scripts/oob-start.sh
```

Use this instead of hand-editing Accessibility settings. The default profile is
`oob-5556`: it builds the standard debug APK, installs it on `emulator-5556`,
stops known UiAutomation conflicts, clears and rebinds OOB Accessibility,
launches OOB, forwards `127.0.0.1:28999` to device port `8899`, checks stale
emulator time, and probes MCP when a token is provided. Pass `--skip-build` to
reuse the last APK, or `--skip-install` when only rebinding/restarting the
runtime.

To inspect startup failure meanings without touching a device:

```bash
scripts/oob-start.sh --errors
```

When the AndroidWorld emulator pair itself must be restarted, use the OmniFlow
launcher first:

```bash
bash /Users/wuzewen/Projects/Omni/OmniFlow/scripts/start_androidworld_avds.sh
```

This starts `AndroidWorldAvd` on `emulator-5554` and `SmallPhone` on
`emulator-5556`. It intentionally kills existing emulators on those ports, so
use it only for restart, not while a validation run is active. It does not make
OOB ready by itself; after boot, always run:

```bash
OOB_MCP_TOKEN=<token> scripts/oob-start.sh --profile 5554 --skip-build
OOB_MCP_TOKEN=<token> scripts/oob-start.sh --profile 5556 --skip-build
```

The launcher uses snapshots by default, so stale emulator clocks can come back.
Let `oob-start` do its clock fix/check after every AVD restart, or launch with
`ANDROIDWORLD_USE_SNAPSHOT=0` when a clean no-snapshot restart is required.

For `emulator-5554`, keep AndroidWorld/Mobilerun state intact:

```bash
OOB_MCP_TOKEN=<token> scripts/oob-start.sh --profile 5554
```

The 5554 profile uses host port `28998`, preserves existing Accessibility
services, and does not stop Mobilerun/AndroidWorld processes.

Startup error summary:

- `startup_error=device_unavailable`: adb cannot reach the selected device.
  Start the emulator/device, confirm `adb devices -l`, or pass
  `--device <serial>`.
- `startup_error=build_failed`: Gradle failed before OOB runtime startup.
  Fix the compile/build error, or use `--skip-build` only when a valid APK
  already exists.
- `startup_error=apk_missing`: the selected APK path does not exist. Build
  first or pass an explicit APK path.
- `startup_error=apk_install_failed`: adb install failed. Check device state,
  storage, install compatibility, and the APK path.
- `startup_error=ui_automation_present`: another runner owns UiAutomation.
  Stop Mobilerun/Appium/AndroidWorld ownership or reboot the emulator before
  blaming VLM logic.
- `startup_error=enabled_but_not_bound`: Android lists OOB Accessibility as
  enabled but not bound. Rerun the one-click script so the secure setting is
  rewritten from a clean state.
- `startup_error=accessibility_not_bound`: OOB Accessibility did not bind
  inside the wait budget. Rerun with `--wait-seconds 30` or inspect
  `dumpsys accessibility`.
- `startup_error=mcp_auth_failed`: the token belongs to a different OOB
  instance/device. Copy the token from the target emulator and rerun with
  `OOB_MCP_TOKEN`.
- `startup_error=mcp_unreachable`: the app process, adb forward, or MCP server
  is not reachable.
- `startup_error=mcp_http_<status>` or
  `startup_error=mcp_probe_unexpected_payload`: MCP answered but did not return
  a usable tool list. Inspect OOB logs before testing VLM quality.
- `startup_error=app_not_running`: OOB launched then exited or did not start.
  Reinstall and inspect logcat for a startup crash.
- `startup_error=device_clock_stale`: the emulator clock is before the minimum
  TLS-safe year or too far from host UTC. Online VLM calls can fail with
  `Unacceptable certificate` / `CertificateNotYetValidException`; rerun with
  `--fix-device-clock`, check for an external runner resetting time, or sync
  the device clock.

The long-term startup runbook is
`docs/agent_context/OOB_STARTUP_RUNBOOK.md`.

If the first immediate VLM call still returns "please enable accessibility",
wait a few seconds or rerun the script; that means Android reported the service
as bound before the in-process bridge became available.

For `emulator-5554`, do not stop Mobilerun/AndroidWorld services unless the
validation explicitly targets OOB on that device.

`emulator-5556` defaults to clean OOB rebinding. Non-5556 devices default to
preserving existing Accessibility services so AndroidWorld/Mobilerun setup is
not removed unless `--clean-accessibility` is explicitly passed.

For emulator serials, startup fixes stale/skewed device clocks against host
epoch by default, checks both `date` and `dumpsys alarm nowRTC`, compares epoch
skew, and re-checks after app launch. It does not rewrite a clock that already
matches host epoch. This is required for online VLM because 5554 can otherwise
enter the model request with a 2023 clock and fail TLS before the model reasons.
Use `--fix-device-clock` to force a rewrite, or `--no-fix-device-clock` only
when you explicitly want stale clock to become a startup error.

The 5554 preserve path intentionally removes only OOB's Accessibility component
from `enabled_accessibility_services`, waits briefly, then appends it back. This
refreshes OOB when it appears in `Crashed services` while keeping Mobilerun and
the AndroidWorld accessibility forwarder enabled. If a live VLM RunLog shows
blank `before.package_name`, blank `after.package_name`, and repeated
`open_app`/`press_back`, inspect `dumpsys accessibility` before changing the
prompt: the model is likely acting without XML/page observations.

For real validation, record both the tool result and the actual device state:

```bash
adb -s emulator-5556 shell dumpsys activity activities | rg 'topResumedActivity|ResumedActivity' -m 3
adb -s emulator-5556 shell uiautomator dump /sdcard/oob_verify.xml >/dev/null
adb -s emulator-5556 shell cat /sdcard/oob_verify.xml | rg 'Display|Brightness|target visible text'
```

Treat the task as verified only when the VLM/Function result and the live
foreground package/page agree.

## First-Step Runtime Rules

Keep first-step behavior in this skill guidance. Do not encode benchmark-suite
or app-specific prompt policy in the core VLM first-step optimizer.

For the AndroidWorld/M3A alignment method, see
`references/androidworld-m3a-method.md`. This is a method record only: it does
not claim benchmark results and does not require running AndroidWorld episodes.

The live adapter should remain a thin verification shell. AndroidWorld may
initialize tasks and compute reward, but OOB owns the online VLM loop, RunLog
collection, convert, replay, and recall. Use `scripts/androidworld_oob_eval.py`
with `--run-live` only for explicit validation, and use the same simple task set
for online VLM, replay, and recall-repeat phases.

## OmniFlow UDEG Node Skill Decision Context

The Runtime Flow section owns UDEG policy. This section only adds VLM decision
safeguards for page-skill context:

- Do not call `finished` because recall returned `hit` or because a historical
  reusable Function finished. Finish only after the current visible page satisfies
  the user's requested end state.
- If the current screen does not match the recalled node/step, ignore that
  candidate, re-ground on the current screenshot/XML/indexed evidence, and
  continue with normal live VLM actions.
- For form tasks, keep each field's intended value tied to the visible field
  label. If a label row or spinner must be changed, click the label/spinner
  control before typing; never type a label value into the currently focused
  phone/email/name field.
- If a replay-like suggestion appears unsafe or ambiguous, choose a bounded live
  VLM action such as `press_back`, `scroll`, or a specific visible `click`
  rather than following historical coordinates.

## Function Management

Use real MCP/control-plane tools for reusable Function management. In normal
agent conversation these tools are exposed directly as agent workbench tools;
external clients can call the same names through MCP:

- `oob_function_list` to list local reusable Functions.
- `oob_function_get` to inspect one Function spec.
- `oob_function_register` to register/update a reusable instruction. Prefer
  the simple shape (`function_id`, `name`, `description`, `steps`,
  optional `sourcePage`) during conversation; use full `functionSpec` only when
  converting/importing an existing structured artifact. If `sourcePage` is
  omitted, OOB tries to capture the current Accessibility XML/package as the
  UDEG page anchor, so registration from the current screen can naturally become
  page-match recall context.
- `oob_function_delete` to delete one Function and remove UDEG node references.
- `oob_function_clear` with `confirm=true` to clear all Functions and detach
  all UDEG node Function references.
- Function replay is handled by VLM dynamic Function tools or internal runners.
  Legacy external adapters may still route `oob_function_run` to the same
  runner, but normal agent-task prompts should not invent that hidden call.

When validating direct Function replay, inspect `step_results`, not only the
top-level `success`. Replay no longer runs post-action page/package validation:
a deterministic step reports success when the native action backend accepts the
operation. If the visible foreground activity is wrong after replay, treat that
as replay/action-transfer behavior to debug from `step_results` and current
page evidence.

Use `oob_run_log_convert` with `register=true` to save a RunLog-derived
Function, and use `oob_function_*` for Function registration, inspection,
updates, deletion, and clearing.

Registration, model-tool exposure, recall, and replay policy are defined by the
Runtime Flow section. This section only names the management tools and
validation entrypoints. For a create/inspect workflow, inspect with
`oob_run_log_get`, convert/register with `oob_run_log_convert` or
`oob_function_register`; execute only through an explicitly exposed Function
tool or internal runner result.

When submitting a conversation through `agent_run` only to create, inspect, or
convert reusable instructions, pass
`toolProfile="function_management"`. That profile exposes only the Function,
RunLog, app lookup, and VLM task tools needed for this workflow, including
`oob_function_list/get/register/delete/clear`. This avoids
sending the full general Agent tool catalog to the model. For even tighter
validation, pass `allowedTools` with the exact tool names needed for that turn.
Do not use the focused profile for unrelated general Agent tasks. Use
`oob_function_clear(confirm=true)` only when the user explicitly asks to clear
all reusable instructions; otherwise delete temporary validation Functions one
by one.

The device validation path for this workflow must exercise the real agent tool
chain, not only `OobOmniFlowToolkitService` directly. Use:

```bash
scripts/oob-agent-function-management-validation.sh --device emulator-5556
```

This sends a debug broadcast to the installed app and verifies
`AgentToolRegistry -> AgentToolRouter -> WorkbenchToolHandler` can expose the
focused profile, register a simple Function, list it, guard-check it, run it on
the foreground device, and report the real post-run package. For 5554, normalize
startup first with the shared profile, then pass `--device emulator-5554`.
The script prints a compact summary by default; use `--raw-json` only when the
full app-side payload is needed for debugging.

To validate that an online Agent conversation can register and run a Function
by calling the tools itself, configure the provider for your target device and
run:

```bash
bash scripts/configure-oob-model-provider.sh --device <device-serial> --profile-id <profile-id> --model <model>
scripts/oob-agent-conversation-function-validation.sh --device <device-serial> --profile-id <profile-id> --model <model>
```

This validation starts a real in-app Agent run through `AgentRunService` with
`toolProfile=function_management` and the exact Function tool allowlist. The
model must call the tools; the receiver then checks that the Function exists
and can replay. If it fails with `validation_error=adb_unavailable` and the adb
body says `Operation not permitted` or `cannot connect to daemon`, the broadcast
never reached OOB because the current shell could not start adb. Restart adb
from an approved device context or rerun after the daemon is alive before
debugging prompts/tool schemas.

On emulator devices this validation keeps a host-side clock guard alive during
the online Agent run. Keep it enabled on shared 5554; AndroidWorld can reset the
device time back to 2023 after startup, and a later model round would otherwise
fail with a certificate error.

For online VLM plus RunLog conversion and deterministic replay, use:

```bash
bash scripts/demo-vlm-runlog-e2e.sh --device emulator-5554 --startup-profile 5554 --goal '<non-smoke Android task>'
```

This runs provider config, live VLM, RunLog collection, conversion, and Function
replay through the installed OOB app. On 5554 it uses `oob-start` preserve mode
so AndroidWorld/Mobilerun Accessibility services stay enabled. The default
output is intentionally compact: success, run id, Function id, token totals,
card/step counts, and replay duration. Use `--raw-json` only for debugging.
The script also keeps the emulator clock guarded during the online VLM phase.

MCP `agent_run` uses `userMessage` as the prompt field; do not send `message`.
Example wrapper:

```json
{
  "name": "agent_run",
  "arguments": {
    "userMessage": "Register the reusable instruction with oob_function_register, then report function_id and success.",
    "toolProfile": "function_management"
  }
}
```

Simple registration example:

```json
{
  "function_id": "open_target_app",
  "name": "Open Target App",
  "description": "Launch the target app from any app.",
  "steps": [
    {
      "tool": "open_app",
      "args": {
        "package_name": "<target-package>"
      }
    },
    {
      "tool": "finished",
      "args": {
        "content": "Target app opened"
      }
    }
  ]
}
```

For screen-local Functions, call `oob_function_register` while the source page is
visible and omit `sourcePage` unless you already have a captured XML. The stored
Function should then attach to the current UDEG node and appear later as an
optional candidate when the same page is matched.

For page-scoped recall, include:

```json
{
  "source_page": {
    "xml": "<hierarchy>...</hierarchy>",
    "package_name": "<target-package>"
  }
}
```

Canonical executable step schema is owned by
`cn.com.omnimind.baselib.runlog.OobCanonicalActionSchema`. Do not maintain a
second action list in skills or prompts. Every executable step must be shaped as
`{"tool": "<name>", "args": {...}}`; `input_text` uses `args.text`, `scroll`
uses `args.duration_ms`. Saved Function tools use the Function id as the tool
name and accept the arguments declared by that Function schema.

For real-device validation, also verify the current foreground package/page
outside the tool response. A `FINISHED` response alone is not enough if the
device has already left the requested target app.

Token control:

- Each online VLM step sends the current screenshot plus compact indexed page
  evidence. This is necessary for correctness; do not remove current screenshot
  evidence by default.
- History should stay compact: carry prior action/result/post-action visible
  text, not the full previous prompt or old Accessibility XML.
- Prefer goals with explicit target app, start page, and visible finish
  condition. Ambiguous goals increase rounds and token cost.
- RunLog token fields are diagnostic and should be used for testing/reporting,
  not shown as normal user-facing UI details.

- If the target app is known, pass `package_name` in `vlm_task` instead of asking
  the model to guess the package.
- If the target app is not known, derive it from the installed application list
  before calling `vlm_task`; do not invent common Android package names.
- If the current page is a permission, onboarding, or account prompt that blocks
  the task, the first action should unblock the flow with a safe continuation
  control such as Allow, While using the app, OK, Got it, Continue, Skip, or Not
  now. Do not choose Deny, Delete, Sign in, Pay, or other destructive/private
  controls unless the user explicitly requested it.
- If an editable field is already focused and the task asks to search, type, or
  enter specific text, the first action should be `input_text`; do not click the same
  input field again.
- If the desired editable field is visible but a different field is focused,
  prefer `input_text` over typing into the stale focus.
- For list pages, if the requested target is not visible, use a
  deliberate vertical scroll within the list and then re-check visible text. Do
  not tap the first unrelated row.
- If a searchable list still does not expose the requested target after bounded
  scanning, use a visible search affordance instead of continuing a scroll loop.
  Search for the pending target label, then click the matching result.
- For sliders, seekbars, and similar system panels, never repeat the same
  `click` on the slider. Use the `scroll` action as a horizontal drag within the
  live slider bounds or visible screen region: drag right for maximum and left
  for minimum. Coordinates must be derived from current XML/indexed evidence or
  the current screenshot, not from a hard-coded device profile.
- For nested list pages, if the task lists multiple rows, click the first
  pending named row even if a later row is also visible.
- For on-screen numeric keypads, enter values by clicking the visible digit
  buttons in order. Use `input_text` only when the focused node is an editable text
  field; a keypad made of clickable digit buttons is not an editable text field.
- If the last action did not change visible text, selected state, or system
  value, do not repeat it more than once. Re-ground on the current screenshot/XML
  and choose a different action such as scroll, back, or a specific visible
  control.
- Ignore OOB overlay controls such as 接管, 继续执行, 小万, and OmniBot when
  choosing the first phone action.

## Dynamic Function Tool Dispatch

Prefer a recalled Function tool when it is explicitly present in the current
turn's `tools[]` and matches the user goal. The Function id is the tool name;
do not wrap it in a separate hidden replay tool.

Rules:

- Start online Android GUI execution with `vlm_task`.
- When the VLM request exposes a recalled Function id in `tools[]`, the model may
  call that Function id directly with its schema arguments.
- Only call Function tools that are present in this turn's `tools[]`.
- Fill required parameters from the user goal. Never call a parameterized
  Function with empty arguments when required values are missing.
- Treat nested Function calls as normal local runner behavior; the model-facing
  result remains `success` and `result`.
- If the Function result is unsuccessful, continue with the next fresh VLM step
  from the current page; do not call hidden resume or guard tools.

## Nested Reusable Function Validation

When validating a nested reusable Function, do not only check registration or recall.
Run a parent reusable Function whose step calls another saved Function, and
verify that the result contains:

- parent step `executor=omniflow_function`
- `nested_function_id` equal to the expected child Function id
- one streamed `tool_started` and one `tool_completed` card for the nested
  Function replay
- nested `step_results` with concrete model-free actions such as `open_app`
- the same child reusable Function succeeds from at least two different current
  pages

## Offline Flow UI Contract

The user-facing flow is `RunLog -> reusable Function registration -> local
execution`. Keep this contract visible and separate from runtime tests:

- A RunLog detail surface should expose direct RunLog replay and reusable Function
  registration as adjacent actions.
- A reusable Function library surface should show that a Function is registered,
  which RunLog(s) it came from, the step count, parameter count, and a local
  execution action.
- Reusable Function execution results should keep diagnostic timing internal. Persist
  `duration_ms`, `started_at_ms`, `finished_at_ms`, and phase timings in RunLog
  and test artifacts, but do not expose these fields in user-facing UI.
- Offline replay cards should carry `run_source=omniflow_replay` and
  `runner=oob_omniflow_replay`. User UI may show a compact "离线重放 /
  OmniFlow Replay" tag, but must not show VLM token cost unless the replay
  explicitly fell back to VLM.
- Function cards should appear in the same RunLog as other tool cards. Users
  should see a compact reusable Function card and status; detailed nested
  `step_results` stay inside the card detail / raw result surfaces.
- Do not show internal route-building jargon to users. Keep legacy
  route-building field names only as compatibility keys.

## Validation Plan Separation

Keep user experience validation and actual phone execution validation separate:

- UX/widget validation: verify labels, buttons, disabled states, source RunLog
  badges, and reusable Function execution result sheets with mocked channel
  payloads.
  Timing telemetry should be parsed and asserted in tests, not shown to users.
  These tests must not start emulators, call VLM, or depend on AndroidWorld.
- Runtime/unit validation: verify RunLog collection, reusable Function
  generation, nested reusable Function calls, replay timing propagation, UDEG node
  recall, and no timing leakage into VLM prompts.
- Device validation: run bounded tasks on emulator-5554 or emulator-5556 only
  when explicitly requested. Record run id, package, goal, step count, success,
  `duration_ms`, token usage, replay result, and whether recall hit a UDEG node
  or reusable Function.
- AndroidWorld method validation: by default export or inspect the method only.
  Do not claim benchmark success unless a live runner initialized the task,
  OOB executed the task through the native VLM loop, and AndroidWorld evaluated
  the reward.

## No Wait Actions

Never emit or preserve `wait`, `sleep`, delay, pause, or idle as a VLM or
reusable Function action step. OOB handles page settling through its internal stability
algorithm. A valid action sequence should contain concrete actions such as:

- `click`
- `scroll`
- `input_text`
- `press_back`
- `press_home`
- `open_app`
- `finished`

If the page is not stable, let the runtime settle internally and then choose the
next concrete action.

## AndroidWorld Validation Pattern

A useful AndroidWorld-style test should be harder than a one-click smoke test:

1. Start from a known app or current screen.
2. Navigate across at least two visible UI states.
3. Use scrolling or search only when needed.
4. Verify a concrete final page or row by visible text.
5. Finish only after the visible verification succeeds.

Safe example goals:

- Settings: from home, scroll to About phone, open it, verify the page title or
  device information is visible, then finish.
- Settings: open Apps, enter Default apps, verify Browser app or Phone app is
  visible, then finish.
- Clock: open Alarms, verify the alarms list or empty state is visible, then
  finish without creating a new alarm.
- Contacts: search for an existing visible contact, open the result, verify the
  detail page is visible, then finish without editing or deleting anything.
- Chrome: open a page or search query only when network access is available;
  verify the page title, search result text, or address field state before
  finishing.

Avoid goals that toggle settings, send messages, buy items, delete data, or
grant permissions unless the user explicitly confirms.

Validation prompts should be written so success is observable from the current
screen. Prefer final checks based on page title, row label, tab label, empty
state, or stable visible text across at least two UI states. Do not mark success
only because one click or one scroll was executed.

## UDEG Recall Context

The Runtime Flow section is the source of truth for UDEG recall. Implementation
detail: encode the live Accessibility page into a local `PageVectorSet`,
page-match it to a UDEG node, read that node's skill-like decision context, and
consider only reusable Functions attached to that node as outgoing capability
candidates.

`omniflow.recall` defaults to an agent-compact payload. It should contain the
decision, node id/package, optional Function candidates, compact
step summaries, and decision policy. It should not include raw timing, full node
skill body, page vectors, or skill artifacts unless a test/debug caller sets
`include_debug=true`.

## RunLog and Reusable Function Handling

The Runtime Flow section owns conversion and replay policy. Operationally, after
a successful VLM or human-recorded run:

1. Check the RunLog contains concrete actions and a terminal `finished` marker.
2. Generate a reusable Function only when the task is reusable and not
   perception-only.
3. For VLM-only logs with no concrete action, do not create an empty reusable
   Function.
4. If generated, report the reusable Function id, guard decision, replay status,
   and run id.

For explicit replay:

1. Use the exposed dynamic Function tool or local runner entrypoint.
2. Respect guard decisions: `allow`, `needs_confirmation`, or `block`.
3. Report whether local replay ran and whether a following VLM step was needed.

During RunLog conversion, preserve real human/device actions and drop only
general startup-bridge noise. A transient startup bridge is an early automatic
click where the source page is a compact prompt/overlay-like page, the clicked
target text is absent from the source page, the post-action page matches the
next concrete step's source page, and the next page contains the target. This
keeps reusable Functions from replaying stale first-launch prompts while still
preserving manual takeover cards (`compile_kind=manual_recording` or
`source=human_takeover`). Inspect
`transient_startup_bridge_dropped_count` in the converted Function source when
debugging replay, but do not show that internal field in user-facing UI.

If direct replay appears to open the wrong app or stay on the wrong page, verify
the live foreground activity and UIAutomator XML before blaming the Function.
Debug from the native `step_results`, action arguments, and current page
evidence.

## Output Requirements

When the task finishes, report:

- mode: `VLM` or `OmniFlow`
- run id, if available
- reusable Function id, if created or replayed
- guard decision, if replayed
- number of concrete actions executed
- final visible result or failure reason
