# OmniFlow Function Backend

This document records the backend ownership rules for OmniFlow reusable Functions.
It is intentionally about core logic only; Flutter cards and display behavior
belong in UI documentation.

## Concept Model

Function code should be unified by concept, not by where a string happens to
appear:

- A Function is a reusable capability. Its durable shape is the Function spec,
  execution steps, parameters, checker metadata, evidence metadata, and recall
  hints.
- A RunLog is evidence. It can create or improve a Function, but raw cards,
  failed attempts, perception wrappers, and cleanup clicks are not themselves
  Function steps until the RunLog compiler accepts them.
- An action is a device operation such as `click`, `input_text`, or `open_app`.
  Action vocabulary belongs to `OobActionCodec`; do not redefine action aliases
  in Function update, recall, or replay services.
- Code that branches on canonical action names must use `OobActionCodec`
  constants. Literal strings are acceptable only in raw JSON fixtures,
  compatibility alias lists, or user-facing prose.
- Code that needs an action family, such as point-target actions, should use
  the sets exposed by `OobActionCodec` instead of rebuilding local
  `click`/`long_press` lists.
- Runtime decisions must be action-driven. Main replay and route safety should
  use `OobActionCodec` predicates or replay policy, not explanatory role labels.
- An executor is a replay classification, not an action. `omniflow`, `tool`,
  and `agent` belong to `RunLogReplayPolicy`; use them to decide who executes a
  step, not to describe what the step does.
- A tool name is an agent/MCP surface. Function lifecycle tools belong to
  `OobFunctionToolNames`; generic agent tools belong to `AgentToolNames`;
  replay bridge names such as `call_tool` belong to `RunLogReplayPolicy`.
- A checker is conditional environment handling. Ads, popups, permission
  prompts, resolver sheets, and "skip" buttons should be represented as checker
  metadata or evidence, not inserted as mandatory Function path steps.
- Checker rule condition/action vocabulary and alias normalization belong
  to `OmniflowCheckerRule`; patch appliers should delegate to it instead of
  maintaining local `dismiss`/`allow`/`skip` alias tables.

When adding code, first decide which concept it belongs to. If the new code
needs two concepts, wire the existing owners together instead of creating a new
helper with mixed semantics.

## Ownership

`OobFunctionRepository` is the single owner for Function storage:

- register, list, get, delete, and clear Functions
- use workspace JSON as the only durable Function store
- report workspace write failures as structured diagnostics
- update UDEG Function references
- bind registered Functions back to source RunLogs
- clear Function-as-tool exposure when the last Function is removed

`OobRunLogFunctionConverter` owns RunLog conversion:

- read finished `InternalRunLogRecord` entries
- compile records through `RunLogReusableFunctionCompiler`
- rely on `RunLogReplayStepNoiseNormalizer` for compiled-step noise cleanup
- apply explicit name/id/description overrides
- mirror source RunLogs into the workspace as a best-effort portable artifact
- return conversion diagnostics such as card count and compiled step count
- delegate all Function persistence to `OobFunctionRepository`; callers that
  already own a repository should inject it so conversion and management calls
  share the same storage owner instance

`InternalRunLogStore` owns native RunLog persistence:

- append every durable run mutation to the per-run event log
- save JSON snapshots only as a read-performance/cache artifact, not as the
  source of truth for terminal run status
- rebuild timeline payloads by loading the latest snapshot and replaying later
  events so event-only cards and finish events remain recoverable
- keep `finishRun(saveSnapshot=false)` available for recording modes that must
  avoid overwriting richer event-log evidence with a sparse terminal snapshot
- keep conversion and Function compilation outside the storage layer

`RunLogReusableFunctionCompiler` owns RunLog card-to-Function assembly:

- filter successful replayable cards before conversion
- coordinate startup bridge cleanup, single-card step compilation, step noise
  cleanup, and raw action compatibility output
- assemble top-level reusable Function fields and metadata
- leave parameter names, descriptions, and bindings to offline agent enhancement

`RunLogReplayStepCompiler` owns single-card replay semantics:

- convert one RunLog card into one canonical execution step or skip it
- decide whether the emitted step uses `executor=omniflow`, `executor=tool`, or
  `executor=agent`
- generate concise step titles from recorded tool/action arguments
- build source-context repair data for coordinate remapping
- keep card action translation out of top-level Function assembly

Replay page guard owns startup package correction:

- registered Function specs do not inject a synthetic first `open_app` step
- replay checks the current package against the step source package at runtime
- package correction remains separate from card action semantics

`RunLogCardAccessors` owns RunLog card field extraction:

- coerce card fields, headers, tool calls, args, results, and observations into
  stable Kotlin map values
- centralize JSON-safe conversion helpers used by conversion and cleanup code
- prevent duplicate ad hoc card parsing across compiler services

`OobFunctionManagementService` owns Function management tool routing:

- parse public tool arguments
- expose recall, register, update, delete, clear, and RunLog conversion
- route all Function storage operations through `OobFunctionRepository`
- route Function recall and direct-hit decisions through
  `OobFunctionRecallService`
- leave Function execution to `OobFunctionToolHandler.runFunction`
- route simple Function registration step normalization through
  `OobFunctionStepNormalizer`
- route `update_function` evidence analysis and patches through
  `OobFunctionUpdateService`

`McpToolDefinitions` and `McpToolExecutors` own the external MCP adapter:

- expose the public MCP schema for OOB tools, including Function tools
- validate MCP arguments before dispatching into the agent/tool runtime
- route explicit diagnostic Function execution through the internal Function runner
- never implement Function storage, recall, update, or replay policy

`OobFunctionSkillProfile` owns the native `omniflow` skill profile:

- expose the small static tool set used by the `omniflow` skill's focused
  `omniflow` profile
- expose registered Functions as management assets, not normal VLM action tools
- build compact prompt candidates for agent tool selection
- never execute Functions or mutate Function specs

`OobFunctionToolNames` owns canonical in-app agent tool names for Function and
RunLog lifecycle:

- define `oob_function_*`, `update_function`, and `oob_run_log_*` lifecycle
  names used by the native skill profile and MCP OmniFlow Function schema
- keep replay-step executor/tool taxonomy in `RunLogReplayPolicy`
- never own tool descriptions, schemas, execution, recall, update, or replay
  behavior

`AgentToolNames` owns canonical in-app names for generic agent tools:

- define stable names such as `vlm_task`, `browser_use`, `web_search`, and
  `android_privileged_action`
- share those names across agent tool definitions, handlers, MCP adapters,
  agent run-log card construction, and RunLog classifiers
- never own OmniFlow Function lifecycle names or replay execution taxonomy such as
  `call_tool`

When adding or migrating a generic agent tool name:

- add the string once in `AgentToolNames`
- use that constant in the tool definition, handler routing, MCP route/schema
  adapter, fallback call sites, and run-log card construction
- update `RunLogReplayPolicy` only when replay classification must recognize
  the tool; do not move Function lifecycle names into `AgentToolNames`
- keep user-facing skill/tool descriptions near the existing tool schema owner,
  not in `AgentToolNames`
- add or update a route/schema test when the tool is exposed through MCP

`AgentToolJson` owns agent-facing JSON projection helpers:

- convert Kotlin maps/lists/scalars into `JsonElement` for tool definitions and
  tool payloads
- support dynamic Function tool definitions, remote MCP tool schemas, and
  runtime tool result payloads
- stay policy-free; schema meaning and Function behavior belong to the caller

`OobFunctionSchemaBuilder` owns model-tool schema projection:

- convert reusable Function specs into JSON-schema shaped tool input contracts
- derive dynamic Function tool ids and parameter names from canonical or legacy
  Function fields
- materialize legacy action specs into canonical execution-step shapes for
  schema/tool compatibility only
- emit canonical local action names through `OobActionCodec` when rebuilding
  execution steps from legacy action specs
- never decide recall ranking, replay policy, or update patches

`OobFunctionStepNormalizer` owns simple inserted-step construction:

- normalize simple register-request steps into canonical execution-step maps
- normalize inserted steps for `update_function`
- compute durable execution capability counts from canonical steps
- keep durable capabilities limited to replay counts; runtime fallback state
  belongs to run payloads, not durable Function capabilities
- do not expose `model_free`/`scriptable` capability counts; those are step-level
  compatibility flags and are derivable from executor policy
- use `OobActionCodec` and `RunLogReplayPolicy` for action and replay-tool
  vocabulary instead of creating local aliases

`OobFunctionUpdateService` owns the `update_function` contract:

- orchestrate Function loading, update mode decisions, dry-run/save behavior,
  and returned tool payloads
- accept explicit patch operations from request payloads or agent-authored
  structured patches; natural-language instructions are evidence/audit text
- own consolidated metadata, step label, evidence, checker, parameter, agent
  reuse, audit, target-repair, insert-step, delete-step, and execution reindex
  patching
- delegate inserted-step normalization to `OobFunctionStepNormalizer`
- delegate source XML target matching for repair patches to
  `OobFunctionTargetSourceMatcher`
- delegate Function + RunLog evidence context and agent prompt packaging to
  `OobFunctionRunLogEvidencePackager`
- do not recreate split patch-applier classes unless there is a real ownership
  boundary that cannot live in the current update service

`OmniflowCheckerRule` owns runtime checker rule vocabulary:

- define checker conditions, actions, and global built-in checker rules
- normalize checker condition/action aliases such as `skip_ad`, `click_allow`,
  `always_open`, and `dismiss_keyboard`
- expose the supported condition/action matrix for the single checker pass
- keep checker vocabulary out of `update_function` patch appliers and step
  execution services

`OobStepRoleClassifier` owns checker/noise role normalization:

- classify cleanup annotations and explicit checker/noise hints for offline
  analysis and UDEG metadata
- expose checker-candidate role alias detection used by `OobFunctionUpdateService`
- keep role labels such as `optional_checker`, `runtime_checker`,
  `checker_candidate`, and `ad_checker` out of checker-specific local alias
  tables
- never emit or consume explanatory main-path labels; whether a replay step is
  executable, key/user-facing, or route-safe belongs to `OobActionCodec` and
  replay policy

`OobFunctionRunLogEvidencePackager` owns update evidence packaging:

- build the read-only Function + RunLog analysis context for `update_function`
- generate the built-in agent prompt that tells the agent how to mark required
  actions, optional checkers, noise, duplicate steps, failed actions, and
  success evidence
- use `OobFunctionRunLogAnalysisContract` for the agent-facing analysis field
  names, role labels, and failure codes embedded in that prompt
- keep evidence-analysis prompt contracts outside Function mutation code
- never save Functions or apply patches

`OobFunctionJson` owns mechanical Function payload coercion:

- normalize public tool payload maps/lists into stable Kotlin value shapes
- build mutable JSON-compatible maps and lists for Function patch/update services
- provide shared scalar coercion helpers used by Function register/update/run/recall
  and replay-handler argument compatibility code
- stay policy-free; Function behavior rules belong in the service using the
  coerced values

`OobFunctionTargetSourceMatcher` owns target repair source matching:

- extract source XML from a step's recorded `source_context`
- parse source XML safely for target repair only
- score candidate nodes by text, content-desc, resource id, visibility, and
  clickability
- return coordinates, bounds, and selector hints for `replace_target` patches

`OobFunctionRecallService` owns recall policy:

- read current page/package context for Function recall
- ask `OobUdegNodeStore` for page/node matches
- rank attached Function capabilities against the agent goal
- expose recalled Functions as runtime diagnostic candidates; normal VLM output
  does not call them as tools
- compact recall payloads for normal agent use while preserving debug mode
- share mechanical Function payload coercion with VLM recall/page-context
  guidance through `OobFunctionJson`

`OobFunctionToolHandler` owns run-time Function execution:

- load and materialize the requested Function
- validate missing required arguments
- execute fixed replay through `OobFunctionToolHandler`
- return compact failure diagnostics when deterministic replay
  cannot execute the current step
- keep `Function.steps` as the only pending sequence. Function replay should
  re-localize and attempt each active step in order; it must not skip action
  steps because a terminal postcondition appears satisfied or because the page
  seems to have advanced. If the current step cannot be executed, return
  `success=false` with a compact `{success, result}` payload. Online recovery
  after a replay failure belongs to the ordinary VLM loop, not the Function
  runner.

`OobFunctionToolHandler` owns runtime execution startup:

- load the Function spec from `OobFunctionRepository`
- validate and materialize runtime arguments
- create the local `OobFunctionToolHandler`
- pass execution controls into the replay handler
- merge execution timing into the returned payload

`OobFunctionCallTiming` owns Function call timing payloads:

- measure guard and execution timing for Function calls
- merge call-level timing into runner timing without changing run results
- keep timing payload shape outside public tool routes

`ActionExecutor`, `ReplayHelper`, and `OmniflowCheckerRule` own primitive
local action execution:

- dispatch canonical local UI actions to `DeviceOperator`
- evaluate global, Function-level, and node-level checker rules around each step
- perform source-context coordinate remapping and recovery snapshots
- keep primitive execution separate from Function storage, recall, and update

`OobFunctionEntryPackageGuard` owns pre-replay app restoration:

- infer the Function entry package from explicit `open_app` steps or source context
- skip restoration when replay already starts with `open_app`
- launch the expected package when the foreground app drifted before replay
- keep package recovery outside the main step loop
- callers that infer an entry package from Function steps should use canonical
  action names from `OobActionCodec`; new specs must write `open_app` directly

`OobFunctionFrontendSessionController` owns transient replay UI state:

- start, update, and finish the local Function execution overlay
- wire user stop/complete requests into the replay loop
- keep main-thread UI calls outside the deterministic step executor
- skip Function calls so only the top-level replay owns the overlay

- keep recovery text outside the replay loop; it must not start a hidden Agent or
  VLM task by itself

`OobFunctionToolHandler` owns replay/tool-call argument resolution and step
routing:

- extract executable args from current Function steps and recorded RunLog cards
- resolve `call_tool(function_id)` targets for Function calls
- identify legacy/noise steps that replay should skip
- resolve the canonical Function execution tool for a step
- decide whether a step is locally executable as UI action or Function call
- hand off Function calls through `OobFunctionCallCardPresenter`
- reject generic `call_tool(tool_name)` delegation during Function replay
- keep recorded argument-shape compatibility outside the runtime replay loop
- skip only explicit observation/no-op legacy steps. Do not introduce
  `already_satisfied` or `optional_not_present` runtime skips for action steps.

`OobFunctionRunResultBuilder` owns replay result payloads:

- build stable per-step failure records for guard and replay errors
- build failed and completed Function run payloads
- merge runner timing into existing failure payloads
- keep output schema and timing accounting outside the runtime replay loop

`OobFunctionCallCardPresenter` owns Function-call tool-card payloads:

- create stable card ids for Function calls
- format running/completed summaries for reusable Function cards
- shape UI-facing args/result preview payloads for Function calls
- keep card text and JSON presentation out of Function execution

`AssistsCoreManager` owns method-channel wiring only:

- call `OobFunctionRepository` for Function register/list/get/delete and direct
  UI run lookup
- call `OobRunLogFunctionConverter` only for RunLog conversion and recent RunLog
  auto-registration
- never implement Function persistence or indexing rules inline

Do not add new Function CRUD paths directly into `AssistsCoreManager`,
`OobFunctionManagementService`, or `OobRunLogFunctionConverter`. Add them to
`OobFunctionRepository`, then call the repository from the route that needs the
operation.

## Current Shape

```text
Agent/MCP tool surface
  -> McpToolDefinitions / McpToolExecutors # external MCP schema/argument adapter
  -> OobFunctionSkillProfile # OmniFlow profile and runtime recall metadata
      -> OobFunctionSchemaBuilder # Function spec -> call_tool argument schema
  -> OobFunctionToolHandler       # load/materialize/execute Functions
      -> OobFunctionToolHandler   # deterministic replay
          -> OobFunctionFrontendSessionController # replay overlay/session
          -> OobFunctionToolHandler # replay/call_tool args and step routing
          -> OobFunctionRunResultBuilder # run result/timing payloads
          -> OobFunctionCallCardPresenter # Function-call card payloads
          -> ActionExecutor.act # primitive local UI action execution
              -> DeviceOperator # physical device port
              -> OmniflowCheckerRule # global/function/node checker metadata
  -> OobFunctionManagementService
      -> OobFunctionRepository       # storage/index/source bindings
      -> OobFunctionStepNormalizer   # simple register/insert-step normalization
          -> OobFunctionJson # shared value coercion for Function payloads
      -> OobFunctionUpdateService    # update_function evidence and patches
          -> OobFunctionJson # shared value coercion for Function payloads
          -> OobFunctionStepNormalizer # inserted step normalization
          -> OobFunctionTargetSourceMatcher # source XML repair matching
          -> OobFunctionRunLogEvidencePackager # Function + RunLog agent context
      -> OobFunctionRecallService    # page/node recall and direct-hit policy
          -> OobFunctionJson # shared value coercion for Function payloads
          -> OobUdegNodeStore        # page/node recall index
      -> VLM recall/page context guidance # render runtime-safe recall hints
          -> OobFunctionJson # shared value coercion for Function payloads
      -> OobRunLogFunctionConverter      # RunLog -> Function conversion
          -> OobFunctionRepository   # injected storage owner for registration
          -> RunLogReusableFunctionCompiler # cards -> reusable Function spec
              -> RunLogReplayStepCompiler # single-card action -> replay step
                  -> RunLogCardAccessors # card field/JSON extraction helpers
              -> RunLogReplayStepNoiseNormalizer # compiled step noise cleanup

Flutter method channel
  -> AssistsCoreManager
      -> OobFunctionRepository       # Function CRUD and direct run lookup
      -> OobRunLogFunctionConverter      # conversion and auto-register only
```

`OobRunLogFunctionConverter` does not expose Function CRUD. New and legacy callers
must use `OobFunctionRepository` for storage and use `OobRunLogFunctionConverter`
only for `convertRunLog` and `autoRegisterRecentRunLogs`.

## What Not To Merge

Keep these pieces separate:

- `OobFunctionRepository`: persistent Function records and index synchronization
- `OobFunctionSchemaBuilder`: public Function input-schema projection,
  parameter-name extraction, step summaries, and legacy action materialization
- `OobFunctionStepNormalizer`: simple public/inserted step maps -> canonical
  execution steps and static execution capability counts
- `OobFunctionUpdateService`: update_function orchestration, permission gates,
  dry-run/save behavior, metadata/evidence/checker/retarget/insert/delete
  patching, execution reindexing, and tool response shaping
- `OobFunctionRunLogEvidencePackager`: Function + RunLog evidence context and
  agent prompt packaging
- `OobFunctionRunLogAnalysisContract`: agent-facing analysis JSON field names,
  evidence role labels, and failure code vocabulary used by
  `OobFunctionRunLogEvidencePackager`; this is not runtime replay role policy
- `OobFunctionJson`: mechanical JSON/map/list/scalar coercion shared by Function
  register/update/run/recall services; do not hide policy or mutation behavior here
- `OobFunctionTargetSourceMatcher`: source XML parsing and node scoring for
  target-repair patches
- `OobFunctionRecallService`: page/node recall, ranking, direct-hit policy, and
  compact recall payload shaping
- `OobFunctionCallTiming`: Function call timing payload construction
- `RunLogReusableFunctionCompiler`: offline RunLog-to-Function assembly and
  conversion orchestration; initial specs keep recorded args literal and expose
  an empty parameter schema until agent enhancement updates them
- `RunLogReplayStepCompiler`: single-card action semantics, executor selection,
  step titles, and source-context repair
- `RunLogCardAccessors`: shared RunLog card field, tool-call, observation, and
  JSON-safe extraction helpers
- `RunLogReplayStepNoiseNormalizer`: repeated input and redundant compiled-step
  cleanup after card-to-step conversion
- `OobFunctionToolHandler`: Function loading, materialization, and execution timing
- `OobFunctionToolHandler` and `ActionExecutor`: runtime step execution
- `OobFunctionFrontendSessionController`: top-level replay overlay lifecycle
  and stop signal handling
- `OobFunctionToolHandler`: replay step args, `call_tool` target resolution,
  Function-call argument extraction, skip-step detection, OmniFlow
  execution-tool resolution, Function-call classification, and unsupported
  tool-step rejection
- `OobFunctionRunResultBuilder`: stable run payload schema, failure step
  records, and runner timing accounting
- `OobFunctionCallCardPresenter`: Function-call tool-card ids,
  summaries, args payloads, and result preview payloads
- `ActionExecutor`, `ReplayHelper`, and `OmniflowCheckerRule`: primitive local
  action dispatch, checker evaluation, coordinate remapping, and recovery
  snapshots
- `McpToolDefinitions` and `McpToolExecutors`: external MCP schema and argument
  alias adapter before dispatch into Function run or management calls
- `OobFunctionSkillProfile`: native `omniflow` skill profile,
  dynamic Function tool exposure, and compact prompt candidates
- `AgentToolJson`: agent-facing map/list/scalar to `JsonElement` projection for
  tool definitions and payloads
- `OobFunctionSchemaBuilder`: Function spec projection into model-tool schemas
  and compatibility materialization for that projection
- `OobFunctionManagementService`: Function management tool routing and response shaping
- builtin skill prompts: agent instructions, not executable policy
- `omniflow` checker references: agent-facing checklist for implementing
  runtime checker code, contracts, and tests. Retired focused checker skills
  are not bundled; executable checker policy still belongs to
  `OmniflowCheckerRule`, `ReplayHelper`, and `ActionExecutor`

Merging these would make it harder to tell whether a change affects storage,
conversion, execution, or agent patching.

## Cleanup Direction

`OobFunctionManagementService` should stay a thin management service. New Function behavior should
land in one of the owned services above before adding more private helper blocks
to the management service. Keep `OobFunctionToolHandler` intentionally small: it loads,
materializes, and executes Functions; it does not own patching.

When changing run-time execution or recovery behavior, update
`OobFunctionToolHandler`/`OobFunctionToolHandler` first and keep the public response
contract stable at the tool route. Do not add ad hoc guard, retry, or agent
prompt helpers back into `OobFunctionManagementService`.

When changing Function register/update/run/recall payload handling, use
`OobFunctionJson` for mechanical payload coercion instead of adding another
private `mapArg`/`listArg`/`firstNonBlank`/`intArg`/`longArg`/`boolArg`/
`mutableJsonMap` copy. Runtime replay helpers may also use it for
argument-shape compatibility, but execution policy must remain in the replay
service that owns the decision. Keep it limited to shape conversion; new rules
should live in the owning update service or replay component. Prefer direct
calls or member imports from the owner object over local one-line forwarding
helpers; thin wrappers make ownership harder to audit.

## Documentation Maintenance

When Function or RunLog behavior changes, update the nearest owner document in
the same commit as the code change:

- Tool surface or activation wording: update the built-in skill docs under
  `app/src/main/assets/builtin_skills/omniflow/` and this backend map when the
  native owner changes.
- Function storage, update, recall, run, checker, continuation, or replay ownership:
  update this file.
- RunLog conversion, card filtering, action aliases, executor categories,
  canonical replay tools, or noise cleanup: update
  `app/src/main/assets/omniflow/runlog/README.md`.
- Agent-facing repair/enhancement behavior: update the relevant skill reference
  and keep `update_function` as the only saved Function mutation path.
- Do not document a second owner for the same rule. If a new component must own
  behavior currently listed here, move the ownership bullet instead of copying
  it.

Use canonical OmniFlow Function lifecycle tools in agent-facing docs:
`oob_function_list`, `oob_function_get`, `oob_function_register`,
`update_function`, `oob_function_delete`, `oob_function_clear`,
`oob_run_log_list`,
`oob_run_log_get`, and `oob_run_log_convert`. In Kotlin, route lifecycle names
through `OobFunctionToolNames`; keep `call_tool` only as an internal replay
bridge in `RunLogReplayPolicy`, not as a normal model-visible Function
execution tool.

## Helper Maintenance Audit

Use these owner rules when removing duplicated helper code:

- Function payload shape helpers belong in `OobFunctionJson`. This includes
  generic map/list/string/int/long/bool coercion and JSON-safe sanitization used
  by register, update, recall, run payloads, timing merge payloads, and Function
  replay argument compatibility. Repository storage, summary, and projection
  code should also call this owner for mechanical coercion instead of growing
  storage-local copies. It must stay policy-free. Call this owner directly
  instead of adding local forwarding helpers with the same names.
- RunLog action/value helpers belong in `OobActionCodec`. This includes action
  aliases, low-level action argument extraction, and generic coercion used while
  converting RunLog cards or building RunLog-derived compatibility payloads.
  Call this owner directly instead of adding local forwarding helpers with the
  same names.
- Replay executor names belong in `RunLogReplayPolicy` constants. Core Function
  run policy, RunLog compilation, and local replay checks should use those
  constants instead of local string literals for `omniflow`, `tool`, or `agent`.
  This applies to generated step specs, result payloads that report the
  executor category, and runtime comparisons. Do not add generated-step marker
  fields for facts the runtime can derive from `executor`, action, source
  context, or UTG data. Legacy markers such as `coordinate_hook` and
  `replay_engine` may be read for compatibility, but new specs should not write
  them. Diagnostic labels such as `agent_tool`, `omniflow_graph`, or
  `omniflow_function` are not executor categories and should stay local to the
  component that emits them.
- Canonical replay tool names such as `call_tool`, `go_to_node`, `click_node`,
  and `oob.agent.run` also belong in
  `RunLogReplayPolicy` constants when they are used as replay tool taxonomy.
  Compatibility replay types such as `wait` and `external_tool` also belong
  there when Function compilation or schema projection needs to preserve them.
  Replay-only data-flow names such as `oob_agent_run`, `omniflow.recall`,
  and `omniflow.ingest_run_log` should be named there when
  RunLog conversion or guard policy classifies them.
  UDEG edge-kind field names and diagnostic counter keys are graph-storage
  vocabulary and should remain with `OobUdegNodeStore`.
- Generic agent tool names such as `vlm_task`, `browser_use`, `web_search`,
  and `android_privileged_action` belong in `AgentToolNames`. Use that owner
  for registration, routing, fallback calls, and run-log card construction.
- RunLog card-field extraction belongs in `RunLogCardAccessors`. Do not add
  another local parser for `tool_call`, card headers, results, observations, or
  card payload JSON.
- Step role aliases belong in `OobStepRoleClassifier`. Checker patching may
  consume those roles, but should not maintain a separate optional-checker role
  alias table.
- Checker condition/action aliases belong in `OmniflowCheckerRule`.
  `OobFunctionUpdateService` may infer checker metadata from optional
  cleanup annotations, but it must delegate explicit checker rule
  normalization there instead of maintaining a second checker alias table.
  When a checker patch references a real local action such as `click` or
  `open_app`, it must canonicalize through `OobActionCodec` before mapping to
  checker-only actions such as dismiss, allow, or reopen-app.
- Function update policy belongs in `OobFunctionUpdateService`. Do not move
  checker, evidence, audit, retarget, insert, delete, or reindex rules into
  `OobFunctionJson`, and do not recreate the old split patch-applier classes
  without a real ownership boundary.
- Runtime replay policy belongs in the replay components under
  `OobFunctionToolHandler`. Do not move skip/fallback/delegation decisions into
  mechanical helper objects, and do not reintroduce separate `call_tool` or
  Function-call step executors for routing already owned by the handler.
- Function run result payload shape belongs in `OobFunctionRunResultBuilder`.
  Runtime components may decide that a step failed or was delegated, but they
  should call this owner for stable fields such as
  `step_id`, `executor`, `model_required`, `error_code`, and timing payloads
  instead of hand-building equivalent maps in each executor. Old per-step
  fallback aliases are not part of new run payloads. Disabled-fallback terminal
  steps are replay decisions; the builder owns only the result shape.
- Agent-facing tool JSON projection belongs in `AgentToolJson`. Use it when
  building tool definitions or serializing generic tool payloads, instead of
  adding another local `mapToJsonElement` copy or a forwarding method on
  `SharedHelper`.

Known helper exceptions that should not be force-merged without a semantic
change:

- `OobFunctionSchemaBuilder.boolArg` is stricter for schema fields and
  intentionally does not accept every runtime truthy alias.
- `RunLogCardAccessors.asMap` and `RunLogCardAccessors.firstNonBlank` are the
  RunLog card-field extraction API, not duplicate action codecs.
- `OobFunctionJson.boolArgOrDefault` owns default-aware Function boolean
  coercion for checker/update payloads. Do not add checker-local copies unless
  a patch needs intentionally different semantics.
- `OobUdegNodeStore` should use `OobActionCodec` for generic scalar coercion
  such as graph indexes, timestamps, and booleans. It may keep graph-export
  `mapArg`/`listArg`/`firstNonBlank` helpers local only where those helpers
  sanitize stored UDEG graph values rather than merely coercing Function or
  RunLog payloads.
- `McpToolExecutors.intArg`, `McpToolExecutors.longArg`,
  `McpToolExecutors.boolArg`, and `McpToolExecutors.boolArgOrDefault` read
  multi-key MCP argument aliases and defaults; keep them local unless a shared
  MCP argument adapter with identical semantics exists.
- `McpRoutes.mapArg` and `McpRoutes.listArg` support legacy/debug HTTP route
  request parsing, including nullable maps and comma-separated string lists.
  They are not Function payload helpers.
- `OobPageVectorSet.firstNonBlank` is a low-risk local helper in vector
  internals; merge it only when touching the surrounding code for another
  reason.
- Function storage lives in workspace JSON. Keep `baselib` runlog persistence
  decoupled from app-layer Function JSON helpers except through explicit
  source bindings/diagnostics supplied by app code.
- `VlmToolCoordinator.firstNonBlank`, `VlmRecallGuidanceBuilder.boolArg`,
  `AgentAiCapabilityConfigSync.firstNonBlank`, and
  `AssistsCoreManager.firstNonBlankString` are outside Function payload
  ownership. Do not merge them into Function helpers unless their surrounding
  feature is explicitly migrated to the Function/RunLog backend.

## Verification

After backend Function changes, run focused tests:

```bash
./gradlew --no-daemon :app:compileDevelopStandardDebugKotlin -Pkotlin.incremental=false
./gradlew --no-daemon :app:testDevelopStandardDebugUnitTest -Pkotlin.incremental=false \
  --tests 'cn.com.omnimind.bot.agent.AgentToolRegistryOobFunctionTest' \
  --tests 'cn.com.omnimind.bot.agent.AgentSystemPromptTest' \
  --tests 'cn.com.omnimind.bot.mcp.McpToolDefinitionsTest' \
  --tests 'cn.com.omnimind.bot.runlog.RunLogReusableFunctionCompilerTest' \
  --tests 'cn.com.omnimind.bot.agent.tool.handlers.OobFunctionToolHandlerOmniFlowExecutionTest' \
  --tests 'cn.com.omnimind.bot.runlog.InternalRunLogStoreTest'
```
