# Replay Failure Handling

Use this reference when a saved Function run fails or returns incomplete local
runner evidence.

## Normal Function Execution

1. Resolve the Function id.
2. Inspect with `oob_function_get` if the Function is not already known.
3. Fill required runtime parameters from the user request.
4. Execute the Function only through the currently exposed runtime path: usually
   a dynamic VLM Function tool, or an explicitly exposed internal runner in
   debug/management flows.
5. The runtime expands it into primitive actions and executes each action
   through the same `observe -> checker -> action_transfer -> execute` loop.
6. Inspect the real result. If the user goal is complete, report it. If this
   Function only advanced part of the goal, continue with the next Function, VLM
   path, or other tool.

Each primitive action gets a fresh live observation. Do not infer that a later
step is safe from an earlier page snapshot.

## Failure Return To VLM

If a Function returns `success=false`, do not restart the whole Function
immediately and do not ask the outer Agent to resume hidden replay.

1. Read the failed step, failed reason, and current screen evidence from the
   returned `result` or RunLog card.
2. Start the next VLM turn from a fresh current-page observe.
3. Let that VLM turn choose one normal GUI tool or another exposed Function
   tool. This is one `vlm_step`, not a separate Agent fallback loop.
4. If the Function definition is wrong, use `update_function` with RunLog
   evidence after the run.

## Repair Before Retry

If the fallback shows the Function definition is wrong, call `update_function`
before running again. Examples:

- The Function clicked "美食" but should click "外卖".
- The target selector points to stale text.
- A coordinate-only step no longer maps to the intended node.

## Stop Conditions

Stop and report the blocker when:

- The same step fails repeatedly.
- The current page evidence is insufficient to choose a safe next VLM step.
- The action is risky and needs confirmation.
- The Function needs structural repair and the user did not authorize it.
