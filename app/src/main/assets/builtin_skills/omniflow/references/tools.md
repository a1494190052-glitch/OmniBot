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
4. Use `call_tool` with `function_id` when Function execution is explicitly
   exposed. Do not invent another Function replay tool name.

When recall returns a Function with `inputSchema`, treat it as candidate context
and execute it through `call_tool(function_id, arguments)` when it matches the
current goal. A parameterized Function should not be ignored just because it
needs arguments.

## MCP Diagnostic Tools

`omniflow.recall`, `omniflow.ingest_run_log`, and `omniflow.explore_replay` are
diagnostic MCP tools for recall and RunLog ingestion. They do not define a
second Function execution language. Function execution remains `call_tool` with
`function_id`.

`start_step_index`, `startStepIndex`, and `resumeFromStep` are legacy
compatibility spellings. They are not part of the normal VLM online path.

Inside the OOB app, prefer the `oob_*` lifecycle tools above and `call_tool` for
execution. Do not design a second Function replay flow around another name.

## Tool Missing Rule

If a required tool is not exposed, say which capability is missing. Do not
substitute unrelated live GUI tools unless the task has explicitly moved into
first-run live collection.
