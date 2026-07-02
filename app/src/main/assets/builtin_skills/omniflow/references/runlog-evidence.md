# RunLog Evidence For update_function

Use this reference when a Function should learn from an existing RunLog,
especially after replay failure or after a successful run shows a better path.

## Evidence Flow

1. Resolve the Function id and RunLog id. Prefer a `run_id` returned by a
   Function replay/local runner result, `oob_run_log_list`, or `oob_run_log_get`.
2. Call `update_function` with `function_id` and `run_id` only.
3. Expect `needs_agent_analysis=true`, `analysis_context`, and `agent_prompt`.
4. Compare `analysis_context.function.steps` with
   `analysis_context.runlog.cards`.
5. Build `analysis` and the smallest safe `patch`.
6. Call `update_function` again with `function_id`, `run_id`, `analysis`, and
   optional `patch`.

## Required Output Shape

Return one JSON object with `analysis` and `patch`. `analysis` explains the
evidence in free-form structured text; `patch` is the smallest safe update that
makes the saved Function easier for a future agent/VLM to understand and call.

```json
{
  "analysis": {
    "summary": "这次 RunLog 说明 Function 为什么成功/失败",
    "evidence": [
      {
        "function_step_index": 1,
        "runlog_card_index": 3,
        "label": "点击外卖入口",
        "reason": "为什么这个证据支持或反对修改"
      }
    ]
  },
  "patch": {
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
    }
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
