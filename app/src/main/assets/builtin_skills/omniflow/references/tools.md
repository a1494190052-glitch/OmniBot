# OmniFlow Tools

Use this reference to choose the canonical tool path.

For the end-to-end replay architecture and the list of concepts that should not
be reintroduced, read `unified-design.md`.

## Preferred Tools

Treat each Function as a saved mobile workflow tool. It may be the whole answer
for a small user goal, or it may only advance one part of a larger goal. After
each Function result, inspect `success` and `result`, then continue with the
next Function, VLM path, or other tool if work remains.

1. Use `oob_run_log_list`, `oob_run_log_get`, and `oob_run_log_convert` for
   local RunLog discovery and RunLog-to-Function conversion.
2. Use `oob_function_list`, `oob_function_get`, `oob_function_register`,
   `oob_function_delete`, and `oob_function_clear` for Function lifecycle.
3. Use `update_function` for all Function modifications, including enhancement,
   repair, RunLog evidence analysis, checker metadata, and structural patches.
4. Do not explicitly call `oob_function_guard_check` from a normal agent-task.
   Guard checks are local runner logic, not a model-facing action.
5. Do not explicitly call hidden Function replay tools. Prefer VLM task dynamic
   Function tools or the local Function runner path when execution is explicitly
   exposed.

When recall returns a Function with `inputSchema`, treat it as candidate context
unless a concrete Function tool is exposed in the current runtime. A
parameterized Function should not be ignored just because it needs arguments,
but a normal agent-task should not invent a hidden replay call.

## Legacy Direct MCP Names

`omniflow.recall`, `omniflow.call_tool`, `omniflow.ingest_run_log`,
`omniflow.explore_replay`, and direct `oob_function_run` may exist in external
MCP clients or older agentkit flows. Inside OOB online execution, do not emit
those names for new VLM replay. Recalled Functions should appear as native VLM
tools with their own Function id as the tool name.

`start_step_index`, `startStepIndex`, and `resumeFromStep` are legacy
compatibility spellings. They are not part of the normal VLM online path.

Inside the OOB app, prefer the `oob_*` tools above. Use legacy `omniflow.*`
tools only when those are the tools actually exposed and the `oob_*` tools are
not available. Do not design a second Function replay flow around any
`call_function` name.

## Tool Missing Rule

If a required tool is not exposed, say which capability is missing. Do not
substitute unrelated live GUI tools unless the task has explicitly moved into
first-run live collection.
