# OmniFlow Tools

Use this reference to choose the canonical tool path.

For the end-to-end replay architecture and the list of concepts that should not
be reintroduced, read `unified-design.md`.

## Preferred Tools

Treat each Function as a composable reusable segment. It may be the whole answer
for a small user goal, or it may only advance one part of a larger goal. After
each Function result, inspect success/fallback/step evidence and continue with
the next Function, VLM path, or other tool if work remains.

1. Use `oob_run_log_list`, `oob_run_log_get`, and `oob_run_log_convert` for
   local RunLog discovery and RunLog-to-Function conversion.
2. Use `oob_function_list`, `oob_function_get`, `oob_function_register`,
   `oob_function_delete`, and `oob_function_clear` for Function lifecycle.
3. Use `update_function` for all Function modifications, including enhancement,
   repair, RunLog evidence analysis, checker metadata, and structural patches.
4. Use `oob_function_guard_check` before risky or user-visible replay when the
   Function source or target context is uncertain.
5. Use `oob_function_run` for replay. After fallback, the agent handles
   `failed_step_index`; then call `oob_function_run` with the returned
   `resume_from_step` to continue from the next remaining step.

When recall returns a Function with `inputSchema`, treat it like any other
agent tool: fill required arguments from the user goal, run guard checks, then
call `oob_function_run`. A parameterized Function should not be ignored just
because it needs arguments; the agent is responsible for filling them or asking
the user if the goal does not contain enough information.

## Legacy Direct MCP Names

`omniflow.recall`, `omniflow.call_tool`, `omniflow.ingest_run_log`, and
`omniflow.explore_replay` may exist in external MCP clients or older agentkit
flows. Inside OOB, do not emit those names for new replay. Use
`oob_function_run` with canonical `function_id` and `arguments`.

`start_step_index`, `startStepIndex`, and `resumeFromStep` are compatibility
spellings for `resume_from_step`. They do not change fallback semantics: retry a
failed step only when the caller explicitly passes `failed_step_index`; otherwise
the returned `resume_from_step` means "continue after the failed step has been
handled."

Inside the OOB app, prefer the `oob_*` tools above. Use legacy `omniflow.*`
tools only when those are the tools actually exposed and the `oob_*` tools are
not available. Do not design a second Function replay flow around any
`call_function` name.

## Tool Missing Rule

If a required tool is not exposed, say which capability is missing. Do not
substitute unrelated live GUI tools unless the task has explicitly moved into
agent fallback or first-run live collection.
