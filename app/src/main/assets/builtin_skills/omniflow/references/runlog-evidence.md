# RunLog Evidence For update_function

Use this reference when a Function should learn from an existing RunLog,
especially after replay failure or after a successful run shows a better path.

## Evidence Flow

1. Resolve the Function id and RunLog id. Prefer a `run_id` returned by a
   Function replay/local runner result, `oob_run_log_list`, or `oob_run_log_get`.
2. Read the current Function and the RunLog evidence.
3. Compare the Function steps with the RunLog cards.
4. Return one complete revised Function JSON object.
5. Call `update_function` with `function_spec` and optional `run_id`.

## Required Output Shape

Return one complete Function JSON object. It should preserve `function_id` and
`execution.steps`; improve only safe metadata, descriptions, parameters,
bindings, checker rules, or evidence fields.

```json
{
  "schema_version": "oob.reusable_function.v1",
  "function_id": "existing_function_id",
  "name": "更清楚的 Function 名称",
  "description": "何时调用、会做什么、需要什么输入、成功标志",
  "parameters": {
    "type": "object",
    "properties": {
      "query": {
        "type": "string",
        "description": "用户要搜索的内容",
        "x_oob_bindings": ["$.execution.steps[2].args.text"]
      }
    },
    "required": ["query"],
    "additionalProperties": false
  },
  "execution": {
    "steps": ["保留原 Function 的完整 steps"]
  }
}
```

## Evidence Rules

- If unsure, do not change the main path.
- Successful RunLogs may improve descriptions, step titles, summaries, success
  signals, and evidence metadata.
- Failed RunLogs may improve descriptions, checker hints, and success/avoid
  guidance when the evidence is explicit.
- Public parameters must include explicit JSONPath bindings to existing step
  args. Do not infer bindings from parameter names.
- Do not change coordinates, XML paths, resource ids, or source_context.
