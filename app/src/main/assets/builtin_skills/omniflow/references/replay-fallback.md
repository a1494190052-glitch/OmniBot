# Replay Fallback And Resume

Use this reference when `oob_function_run` fails or returns agent fallback
context.

## Normal Function Execution

1. Resolve the Function id.
2. Inspect with `oob_function_get` if the Function is not already known.
3. Fill required runtime parameters from the user request.
4. Run the Function with `oob_function_run`.
5. Treat the Function as an action stack, not as one fixed trajectory replay.
   The runtime expands it into primitive actions and executes each action through
   the same `observe -> checker -> action_transfer -> execute` loop.
6. Inspect the real result. If the user goal is complete, report it. If this
   Function only advanced part of the goal, continue with the next Function, VLM
   path, or other tool.

Each primitive action gets a fresh live observation. Do not infer that a later
step is safe from an earlier page snapshot.

## Fallback To Agent

If `oob_function_run` returns `fallback_context`, do not restart the whole
Function immediately.

1. Read the failed step, failed reason, current screen context,
   `failed_step_index`, and `resume_from_step`.
2. Complete only the failed action using the live phone state or the bounded VLM
   path available to the caller.
3. Call `oob_function_run` again with the provided resume data. After the agent
   has completed the failed step, `resume_from_step` points to the next local
   step. Use `failed_step_index` only when retrying the failed step itself.
   If the failed step is a nested Function call, inspect its `nested_*`
   fallback fields as evidence, but still resume the parent Function from the
   returned parent `resume_from_step`.

```json
{
  "function_id": "<id>",
  "resume_from_step": 4,
  "fallback_session_id": "<session>",
  "fallback_attempt": 1
}
```

4. Continue from the next step when the fallback succeeds.

## Repair Before Retry

If the fallback shows the Function definition is wrong, call `update_function`
before running again. Examples:

- The Function clicked "美食" but should click "外卖".
- The target selector points to stale text.
- A coordinate-only step no longer maps to the intended node.

## Stop Conditions

Stop and report the blocker when:

- The same step fails repeatedly.
- No `fallback_context` or resume step is available.
- The action is risky and needs confirmation.
- The Function needs structural repair and the user did not authorize it.
