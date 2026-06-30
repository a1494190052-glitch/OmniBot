# OmniFlow Tools

Use this reference to choose the canonical tool path.

For the end-to-end replay architecture and the list of concepts that should not
be reintroduced, read `unified-design.md`.

## Preferred Tools

Treat each Function as a saved mobile workflow asset. It may be the whole answer
for a small user goal, or it may only advance one part of a larger goal. Online
execution should enter through `vlm_task`; local runtime recall decides whether
to replay a Function before ordinary VLM actions.

1. Use `oob_run_log_list`, `oob_run_log_get`, and `oob_run_log_convert` for
   local RunLog discovery and RunLog-to-Function conversion.
2. Use `oob_function_list`, `oob_function_get`, `oob_function_register`,
   `oob_function_delete`, and `oob_function_clear` for Function lifecycle.
3. Use `update_function` for all Function modifications, including enhancement,
   repair, RunLog evidence analysis, checker metadata, and structural patches.
4. Use `vlm_task` for online execution. Function recall, argument binding, and
   replay are internal runtime steps, not normal model-selected tools.

When recall returns a Function with `inputSchema`, treat it as candidate context.
The runtime may bind business arguments and replay it. If replay fails, the
Function returns diagnostics and the outer `vlm_task` loop may continue with
ordinary UI actions.
Do not create extra model-visible tools or names for Function parameters or
failed replay steps.

## MCP Diagnostic Tools

`omniflow.recall` and `omniflow.ingest_run_log` are diagnostic MCP tools for
recall and RunLog ingestion. They do not define a second Function execution
language. Online Function execution remains runtime recall/replay inside
`vlm_task`.

`start_step_index`, `startStepIndex`, and `resumeFromStep` are legacy
compatibility spellings. They are not part of the normal VLM online path.

Inside the OOB app, prefer the `oob_*` lifecycle tools above for asset
management and `vlm_task` for online execution. Do not design a second Function
replay flow around another model-visible name.

## Tool Missing Rule

If a required tool is not exposed, say which capability is missing. Do not
substitute unrelated live GUI tools unless the task has explicitly moved into
first-run live collection.
