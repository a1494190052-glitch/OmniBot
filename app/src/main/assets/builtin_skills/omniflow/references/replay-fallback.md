# Replay Resolve

Use this reference when a saved Function run fails or returns incomplete local
runner evidence.

## Normal Function Execution

1. Let recall select a compatible saved Function.
2. Inspect with `oob_function_get` if the Function is not already known.
3. Bind public parameters from the user request before replay.
4. Execute the Function only through the currently exposed runtime path:
   `vlm_task` runtime recall/replay for online tasks, or an explicitly exposed
   internal runner in debug/management flows.
5. The runtime expands it into primitive actions and executes each action
   through the same `observe -> checker -> action_transfer -> execute` loop.
6. Inspect the real result. If the user goal is complete, report it. If this
   Function only advanced part of the goal, continue through the next runtime
   recall/replay decision, VLM path, or other tool.

Each primitive action gets a fresh live observation. Do not infer that a later
step is safe from an earlier page snapshot.

## Failed Step

If a Function returns `success=false`, do not restart the whole Function and do
not ask the outer Agent to take over hidden replay.

1. Read the failed step, failed reason, and current screen evidence from the
   returned `result` or RunLog card.
2. Return the failure diagnostics to the caller. The Function runner does not
   run a hidden model repair step.
3. Let the ordinary VLM loop continue with normal UI actions if the user goal
   remains unfinished.
4. If the Function definition is wrong, use `update_function` with RunLog
   evidence after the run.

## Update Before Retry

If replay diagnostics show the Function definition is wrong, call
`update_function` before running again. Examples:

- The Function clicked "美食" but should click "外卖".
- The target selector points to stale text.
- A coordinate-only step no longer maps to the intended node.

## Stop Conditions

Stop and report the blocker when:

- The same step fails repeatedly.
- The current page evidence is insufficient to choose a safe next VLM step.
- The action is risky and needs confirmation.
- The Function needs structural repair and the user did not authorize it.
