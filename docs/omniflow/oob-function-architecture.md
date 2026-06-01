# OOB Function Architecture

This document records ownership boundaries for the RunLog to Function pipeline.

## Ownership

RunLog owns:

```text
raw event capture
run storage
manual recording artifacts
conversion entrypoint
```

OOB Function owns:

```text
Function spec shape
canonical actions
step annotations
update_function
runtime checkers
guard check
execution
UDEG indexing and recall
```

VLM/Agent owns:

```text
live perception fallback
ambiguous target repair
non-deterministic tool delegation
```

## Runtime Flow

```text
RunLog events
-> Function conversion
-> OobActionCodec canonicalization
-> cleanup and annotation
-> checker rule materialization
-> Function store
-> recall candidate payload
-> oob_function_guard_check
-> guard check
-> oob_function_run
-> OmniflowStepExecutor
-> fallback_context when replay fails
```

`update_function` uses the same canonicalization rules as initial enhancement.
It should not patch arbitrary JSON fields directly without revalidating actions,
roles, and checker rules.

## Refactor Rule

When logic asks "what action is this?" or "what role does this step have?", it
must call the shared codec/classifier instead of adding a local `when` block.

Do not add hidden replay queues, automatic step skipping, source-alignment skip
counters, or semantic/navigation recovery layers. If the current Function path
is wrong, return fallback context and repair the saved Function through
`update_function`.
