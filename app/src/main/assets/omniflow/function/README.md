# OmniFlow Function Backend

This document records backend ownership for reusable Functions. Flutter owns
display, input state, and explicit save/update requests. Kotlin owns Function
conversion, storage, update, recall, and replay.

## Concepts

- Function: a reusable capability stored as workspace JSON.
- RunLog: evidence that can create or improve a Function.
- Step: one ordered Function execution unit.
- Action: a canonical device operation from the OOB action schema.
- Checker: optional runtime correction metadata.
- Recall: selection of stored Functions for the VLM loop.

Do not create separate Function executors, replay runtimes, backend registries,
or frontend replay compilers.

## Owners

`OmniFlowFunctionService`

- register, list, get, delete, and clear Functions
- use workspace JSON as the durable store
- update UDEG references and source RunLog bindings

`OmniFlowFunctionService.convertRunLog`

- read an explicit RunLog id
- compile it through `OmniFlowFunctionCompiler`
- apply explicit id/name/description overrides
- mirror source RunLog artifacts best-effort
- save through the same Function service path

`OmniFlowFunctionCompiler`

- filter successful replay evidence
- invoke card-to-step compilation
- run step noise cleanup
- assemble top-level Function fields
- leave parameter binding to offline `update_function`

`RunLogReplayStepCompiler`

- convert one card into one deterministic step or skip it
- build source context for action transfer
- emit canonical action names
- keep live planning/data lookup out of Function replay steps

`OmniFlowFunctionService`

- parse public Function management tool arguments
- expose recall, register, update, delete, clear, and RunLog conversion
- route durable IO through `OmniFlowFunctionStore`
- leave execution to `OmniFlowFunctionRun.runFunction`

`OmniFlowFunctionRun`

- run one Function by id/spec
- materialize arguments
- loop over steps
- call `ActionExecutor.act` for local UI actions
- report progress and result payloads

`ReplayHelper`

- provide checker/action-transfer adapter logic for `ActCheckConfig`
- never execute the main action itself

`ActionTransfer`

- pure source/current UI relocation logic
- no device access and no action execution

`OmniflowCheckerRule` and checker machinery

- define generic XML/runtime checker rules
- run through replay check callbacks
- avoid app-specific hard-coded flows

## Execution Path

Normal VLM:

`tool_call -> ActionExecutor.act -> DeviceOperator`

Function replay:

`Function step -> ActionExecutor.act(check) -> checker/actionTransfer -> DeviceOperator`

Function recall:

`VLM context -> recall candidates -> VLM chooses ordinary action or Function run`

Failed Function replay returns a structured failure. Online recovery belongs to
the VLM loop, not an internal Function resolve runner.

## Update And Enhancement

Initial RunLog conversion is literal and conservative. It does not infer
parameters. `update_function` may later apply explicit patches or
agent-authored structured analysis for:

- name
- description
- parameters
- argument bindings
- checker metadata
- step labels

Function update must preserve execution semantics unless a structured patch
explicitly changes them.

## Storage

Workspace JSON is the Function store. Do not reintroduce SharedPreferences
mirrors, dual writes, or prefs reads.

## Frontend Contract

Flutter may:

- display native Function payloads
- edit user-visible fields
- request register/update/delete/run operations

Flutter must not:

- compile RunLog cards into Function steps
- maintain a replay policy mirror
- write Function specs after native `update_function` already saved them
- decide replay executors
