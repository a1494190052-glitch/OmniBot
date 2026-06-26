# VLM Recall 延迟分析与对照实验方案

日期：2026-06-23

本文解释为什么现有 recall 路径会慢，`wzw-dev` 路径和当前分支的差异是什么，以及下一版如何加速。目标是把判断从“感觉慢”变成可复现实验：同一台手机、同一批任务、同一套 runlog 指标，对比纯 VLM、`wzw-dev` 风格、当前 fast path、以及推荐的新 step-level 轻量 recall planner 方案。

## 结论

最近手机 runlog 里，慢点不是 observe。

可见数据：

| 阶段 | 观测耗时 |
| --- | ---: |
| `fresh_observe_ms` | 约 210-274 ms |
| `indexed_evidence_ms` | 约 25-90 ms |
| 第一 VLM step 的 `recall_action_ms` | 5436 ms |
| 普通 VLM step 的 `vlm_stream_ms` | 约 3953-4033 ms |
| `vlm_task` 总耗时 | 72208 ms |

这说明主要延迟来自两类叠加：

1. recall 选择不是纯本地命中时，会额外跑一次 selector model，耗时接近一次小 plan。
2. recall 命中的 Function replay 如果失败或未完成，后面还会回退到普通 VLM 多步执行，变成 `recall selector + replay + VLM fallback` 的串行叠加。

推荐方向：

```text
每个 VLM step
-> fresh observe
-> indexed evidence
-> lightweight local recall
-> 如果有明确最相关 Function，生成一个当前 step 临时工具
-> VLM planner 在普通 UI action 和这个临时工具之间选择
-> 工具内部映射到 Function replay
-> 执行后进入下一 step，下一 step 基于新页面重新 observe/recall
```

这本质上继承 `wzw-dev` 的“recall 作为 planner 候选”路线，但要把接口从裸 `call_tool(function_id)` 收敛成当前 step 的临时语义工具，并去掉额外 selector model。

## 三种已有路径对比

### 1. 纯 VLM

执行流：

```text
observe -> indexed evidence -> VLM tool_call -> schema validation -> grounding -> execute -> runlog
```

特点：

- 每步都要等在线模型。
- 最近普通 step 的 `vlm_stream_ms` 大约 4s。
- observe/index 只有几百毫秒，不是主瓶颈。
- 稳定性依赖模型每步选对 action。

适合作为 baseline。

### 2. `wzw-dev` 风格：step-level recall 注入 planner

关键实现：

- `OobVlmFunctionRecallProvider`
- `VlmRecallGuidanceBuilder`
- `VLMRecallContextProviderRegistry.enrich(...)`
- `VLMClient.buildUIOperationRequest(...)` 合并 base tools 和 `dynamicToolDefinitions`

执行流：

```text
每个 VLM step:
observe
-> page context
-> function recall
-> 把 recall 候选写进 guidance / call_tool context
-> VLM planner 选择普通 UI action 或 call_tool(function_id)
-> execute
```

优点：

- recall 不直接抢执行，planner 可以结合当前屏幕判断。
- 命中候选时，模型有机会用一个 Function 代替多步 GUI 操作。

问题：

- recall 信息写进 prompt，会增加 prompt 长度。
- `call_tool(function_id=...)` 对在线 VLM 可见，违反当前更收紧的边界：`function_id` 应该是 Function replay 内部字段，不是普通在线 action。
- preferred rewrite 和裸 `call_tool` 语义让 planner 负担变重：模型要理解 `call_tool`、`function_id`、Function 参数、replay 语义。
- 每 step recall 本身不是问题；问题是每 step recall 不能再额外跑 selector model，也不能注入过长上下文。

所以 `wzw-dev` 的方向是对的，但不宜原样恢复。

### 3. 旧当前分支：step-level pre-selected FunctionRunAction

关键实现：

- `VlmRecallFunctionSelector`（旧实现，已由新方案移除）
- `VLMRecallActionProviderRegistry.selectAction(...)`（旧实现，已由新方案移除）
- `FunctionRunAction`
- `VlmFunctionRunHandlerImpl`

执行流：

```text
每个 VLM step:
observe
-> indexed evidence
-> recall selector
   -> direct hit: 返回 FunctionRunAction
   -> recall candidates: 再调 recall_selector_model 选择
   -> miss: 返回 null
-> 如果有 FunctionRunAction，跳过 VLM，直接 replay
-> 如果没有，走普通 VLM
```

优点：

- direct hit 且 replay 成功时，可以跳过一次 VLM。
- 不再让在线 VLM 输出 `call_tool/function_id`。
- Function replay 留在 OmniFlow runtime，边界更清晰。

问题：

- 非 direct hit 会额外调 selector model。最近 runlog 第一 step `recall_action_ms=5436ms`，已经接近一次 plan。
- pre-selected action 会绕过 planner；如果 recall 命中不适合当前页面，会先 replay，再失败回退，导致总耗时更长。
- Function replay 失败原因目前不够细，runlog 难以区分 page guard、action transfer、checker、primitive action 哪一层失败。

最近 runlog 的慢就是这个组合：

```text
observe/index 很快
-> recall selector 花 5.4s
-> 命中 saved function
-> Function replay 未完成/失败
-> 继续普通 VLM 多步，每步约 4s streaming
```

## 推荐方案：Step-Level Lightweight Recall Tool Injection

核心原则：

- recall 只做候选召回，不直接决定执行。
- recall 保持 step-level，因为 Function 是否适合依赖当前页面。
- planner 在当前 step 看到最多 3 个已召回候选工具，与普通 UI action 同级选择。
- Function replay 仍是本地 runtime 责任。
- 不把裸 `function_id` 暴露给普通在线 action。
- 不引入复杂 topK、blacklist、失败重试策略；执行完自然进入下一 step。

建议执行流：

```text
每个 VLM step:
-> fresh observe
-> indexed evidence
-> local recall
-> 如果有相关 Function 候选:
      build up to three ephemeral tools:
      run_recalled_workflow_1
      run_recalled_workflow_2
      run_recalled_workflow_3
-> VLM planner call with ordinary UI tools + optional ephemeral recalled tools
-> if planner selects recalled tool:
      runtime maps tool name -> internal function_id
      execute OobOmniFlowToolkitService.runFunction(...)
      record replay diagnostics
   else:
      execute ordinary UI action
-> next step repeats fresh observe and lightweight recall
```

### 临时工具形态

模型可见：

```json
{
  "type": "function",
  "function": {
    "name": "run_recalled_workflow_1",
    "description": "Run a saved workflow that opens Xiaohongshu and searches for the requested query.",
    "parameters": {
      "type": "object",
      "properties": {
        "query": {
          "type": "string",
          "description": "Search keyword from the user request."
        }
      },
      "required": ["query"]
    }
  }
}
```

runtime 内部映射：

```json
{
  "tool_name": "run_recalled_workflow_1",
  "function_id": "oob_fn_vlm_task_05671eec",
  "score": 0.93,
  "source": "omniflow_recall"
}
```

模型永远不需要看到 `function_id`。在线 VLM 只选择一个普通工具名，例如 `run_recalled_workflow_1`。执行时由本地 runtime 转成 `FunctionRunAction(functionId=...)` 或直接调用 `OobOmniFlowToolkitService.runFunction(...)`。

### Recall 策略

默认：

- `k=3`。
- 只取前三个去重后的 Function。
- 有明确候选就生成最多三个临时工具。
- 没有明确候选就不注入工具，直接走普通 VLM。
- 不调 selector model；是否调用由本轮 VLM planner 决定。

建议阈值：

```text
recall_enabled = true
recall_max_candidates = 3
recall_max_tools_per_step = 3
recall_decision_mode = context_only
```

当前实现里，默认可测试开关集中在 workspace 的 `.omnibot/agent/vlm_config.json`，不需要改 Kotlin 再打包才能切换常规策略：

```json
{
  "primary_model": "scene.vlm.operation.primary",
  "vlm_max_completion_tokens": 384,
  "vlm_temperature": 0.2,
  "vlm_history_rounds": 4,
  "vlm_default_max_steps": 12,
  "vlm_min_wait_timeout_ms": 30000,
  "vlm_max_wait_timeout_ms": 600000,
  "recall_enabled": true,
  "recall_max_candidates": 3,
  "recall_max_tools_per_step": 3,
  "recall_decision_mode": "context_only",
  "recall_tool_name_prefix": "run_recalled_workflow"
}
```

实验时优先改这份文件：

- 纯 VLM baseline：`recall_enabled=false`。
- recall 注入 planner：`recall_enabled=true` 且 `recall_max_tools_per_step=3`。
- 测 recall 注入开销：固定 `recall_max_candidates`，只改 `recall_max_tools_per_step` 为 0/1/3。
- 测 VLM 长度/速度：改 `vlm_history_rounds`、`vlm_max_completion_tokens`、`vlm_tool_result_chars`。
- 测任务控制面等待：改 `vlm_default_max_steps`、`vlm_max_wait_timeout_ms`。

### 失败后处理

Function replay 失败时，runlog 至少要输出：

```json
{
  "function_replay_success": false,
  "function_replay_error_phase": "page_guard_failed | transfer_failed | checker_failed | primitive_failed | timeout | unknown",
  "function_replay_step_index": 2,
  "function_replay_elapsed_ms": 1820,
  "function_id_hash": "..."
}
```

后续 planner 需要看到简短状态：

```text
Saved workflow failed at transfer_failed; continue with ordinary current-screen UI actions.
```

## 预期加速点

| 加速点 | 当前问题 | 新方案 |
| --- | --- | --- |
| recall 形态 | 每 step recall 后可能 selector 或抢执行 | 每 step 轻量 recall，只提供最多三个候选工具 |
| selector model | candidates 时额外调用 | 默认不用 selector，交给 planner |
| planner 参与 | 当前 pre-selected 会绕过 planner | planner 当前 step 选择是否使用临时工具 |
| replay 失败 | replay 后再 fallback | 记录原因后自然进入下一步 |
| prompt 膨胀 | `wzw-dev` guidance 注入较长 | 临时 tool schema + 短 description |
| 安全边界 | `wzw-dev` 暴露 `function_id` | 模型只见 ephemeral tool name |

理想情况下：

- direct Function 成功：少掉后续多步 VLM streaming。
- recall 不适用：只多一个轻量本地 recall，避免 selector model。
- recall 失败：记录 replay 失败原因，下一步基于新页面继续。

## 对照实验设计

### 实验目标

验证四个问题：

1. 慢是否主要来自 observe/index。
2. 当前 slow run 是否来自 selector model + replay fallback。
3. `wzw-dev` 风格是否比当前 pre-selected 更稳。
4. step-level 轻量临时工具是否能降低 p50/p95 总耗时和 selector model 耗时。

### 实验组

| 组别 | 分支/配置 | 说明 |
| --- | --- | --- |
| A | 当前分支，`recall_enabled=false` | 纯 VLM baseline |
| B | 当前分支，`recall_enabled=true` | 当前 pre-selected FunctionRunAction |
| C | `wzw-dev` | step-level recall 注入 planner / call_tool |
| D | 新实现 | step-level lightweight recall + up to three ephemeral recalled tools |

如果短期无法实现 D，先跑 A/B/C，D 用实现后补跑。

### 固定条件

- 同一台物理手机。
- 同一个 APK variant：`developStandardDebug`。
- 同一网络、同一 provider、同一 model config。
- 测试前清理目标 App 到固定初始状态。
- 每个任务每组至少跑 10 次。
- 每次 run 前等待 5 秒，确保页面稳定。
- 记录失败 run，不只统计成功 run。

### 任务集

至少覆盖三类：

| 类别 | 示例任务 | 目的 |
| --- | --- | --- |
| 高置信可复用 | 打开小红书，搜索“猫猫” | 测 Function replay 是否真的省 VLM |
| 普通 GUI | 打开系统设置并搜索“无障碍” | 测 recall miss 时额外开销 |
| 易失败 replay | 打开存在弹窗/页面漂移的 App 并执行历史 Function | 测失败 fallback 成本 |

每个任务要保证已有对应 Function 的版本和无 Function 的 baseline 都能跑。

### 指标

每次 run 提取：

| 指标 | 含义 |
| --- | --- |
| `task_total_ms` | agent tool 从 started 到 finished |
| `vlm_task_ms` | 子 VLM task 总耗时 |
| `step_count` | VLM step 数 |
| `observe_total_ms` | 所有 `fresh_observe_ms` 求和 |
| `indexed_total_ms` | 所有 `indexed_evidence_ms` 求和 |
| `recall_total_ms` | 新实现看 `recall_context_ms`/`recall_context_lookup_ms`，旧实现看 `recall_action_ms` 或 `function_recall_ms` |
| `recall_count` | recall 调用次数 |
| `selector_model_calls` | selector model 调用次数 |
| `vlm_stream_total_ms` | 所有 `vlm_stream_ms` 求和 |
| `vlm_call_count` | 有 `vlm_stream_ms` 的 step 数 |
| `function_replay_count` | Function replay 尝试次数 |
| `function_replay_success` | replay 是否成功 |
| `fallback_after_replay` | replay 后是否继续普通 VLM |
| `token_total` | 所有 step token 求和 |
| `success` | 任务最终是否完成 |

### 判定标准

D 相比 B 的目标：

- 成功率不下降。
- `selector_model_calls` p50 为 0。
- `task_total_ms` p50 降低 25% 以上。
- 高置信可复用任务的 `vlm_call_count` 降低 30% 以上。
- replay 失败任务的 p95 不高于 B，因为不再额外支付 selector model。

D 相比 A 的目标：

- 高置信可复用任务更快。
- recall miss 任务 p50 不明显变慢，允许小于 500ms 的轻量 recall 开销。

C 相比 B 的观察点：

- 如果 C 更稳，说明 planner 参与选择有价值。
- 如果 C 更慢，多半来自每 step recall + prompt 注入 + call_tool 暴露过重。

## RunLog 提取命令

设备：

```bash
adb devices
```

列出最近 runlog：

```bash
adb -s <device> shell "run-as cn.com.omnimind.bot.debug sh -c 'ls -lt files/internal_run_logs | head -40'"
adb -s <device> shell "run-as cn.com.omnimind.bot.debug sh -c 'ls -lt workspace/run_logs | head -20'"
```

导出某个 run：

```bash
adb -s <device> exec-out run-as cn.com.omnimind.bot.debug cat files/internal_run_logs/<run>.json > /tmp/<run>.json
adb -s <device> exec-out run-as cn.com.omnimind.bot.debug cat files/internal_run_logs/<run>.events.ndjson > /tmp/<run>.events.ndjson
```

提取 step 级诊断：

```bash
jq -r '
  def parsed:
    ((.raw_result_json // .result // "{}") | try fromjson catch {});

  ["idx","tool","status","duration_ms","source","title",
   "fresh_observe_ms","indexed_evidence_ms","recall_context_ms",
   "recall_context_lookup_ms","recall_action_ms","function_recall_ms",
   "vlm_stream_ms","action_dispatch_ms",
   "action_executor_action_ms","action_executor_post_delay_ms",
   "tokens","message"] | @tsv,

  (.cards[]? |
    [(.header.step_index // ""),
     (.tool_name // .toolName // .header.tool_name // ""),
     (.status // .header.status // ""),
     (.duration_ms // .header.duration_ms // ""),
     (.source // .run_source // .selection_source // ""),
     ((.title // .header.title // "") | gsub("[\n\t]"; " ")),
     ((parsed.page_diagnostics.fresh_observe_ms // "") | tostring),
     ((parsed.page_diagnostics.indexed_evidence_ms // "") | tostring),
     ((parsed.page_diagnostics.recall_context_ms // "") | tostring),
     ((parsed.page_diagnostics.recall_context_lookup_ms // "") | tostring),
     ((parsed.page_diagnostics.recall_action_ms // "") | tostring),
     ((parsed.page_diagnostics.function_recall_ms // "") | tostring),
     ((parsed.page_diagnostics.vlm_stream_ms // "") | tostring),
     ((parsed.page_diagnostics.action_dispatch_ms // "") | tostring),
     ((parsed.page_diagnostics.action_executor_action_ms // "") | tostring),
     ((parsed.page_diagnostics.action_executor_post_delay_ms // "") | tostring),
     ((parsed.token_usage.total_tokens // .token_usage.total_tokens // "") | tostring),
     ((parsed.message // parsed.reason // .summary // "") | tostring | gsub("[\n\t]"; " ") | .[0:180])]
    | @tsv)
' /tmp/<run>.json
```

汇总一个实验目录：

```bash
for f in /tmp/oob-recall-exp/*.json; do
  jq -r '
    def parsed:
      ((.raw_result_json // .result // "{}") | try fromjson catch {});
    [
      input_filename,
      (.runId // ""),
      (.goal // ""),
      ((.finishedAtMs - .startedAtMs) // ""),
      (.success // false),
      ([.cards[]? | parsed.page_diagnostics.fresh_observe_ms? | tonumber?] | add // 0),
      ([.cards[]? | parsed.page_diagnostics.indexed_evidence_ms? | tonumber?] | add // 0),
      ([.cards[]? | parsed.page_diagnostics.recall_context_ms? | tonumber?] | add // 0),
      ([.cards[]? | parsed.page_diagnostics.recall_context_lookup_ms? | tonumber?] | add // 0),
      ([.cards[]? | parsed.page_diagnostics.recall_action_ms? | tonumber?] | add // 0),
      ([.cards[]? | parsed.page_diagnostics.function_recall_ms? | tonumber?] | add // 0),
      ([.cards[]? | parsed.page_diagnostics.vlm_stream_ms? | tonumber?] | add // 0),
      ([.cards[]? | select((parsed.page_diagnostics.vlm_stream_ms? // "") != "")] | length),
      ([.cards[]? | select((.tool_name // .toolName // "") == "call_tool")] | length)
    ] | @tsv
  ' "$f"
done
```

## 实现拆分建议

第一阶段：只加观测能力。

- 新实现记录 `recall_context_ms` 和 provider 内 `recall_context_lookup_ms`；旧 selector 路径如继续对照实验，再拆 `recall_action_ms` 为 `recall_lookup_ms`、`recall_selector_model_ms`、`recall_prompt_build_ms`、`recall_parse_ms`。
- Function replay 结果增加 `function_replay_error_phase`。

第二阶段：禁用 selector model 默认路径。

- direct hit 仍可内部 fast path。
- candidates 不再调 selector model。
- workspace config 采用当前字段名：

```json
{
  "recall_enabled": true,
  "recall_max_candidates": 3,
  "recall_max_tools_per_step": 3,
  "recall_decision_mode": "context_only"
}
```

第三阶段：实现 step-level ephemeral tool。

- 在每个 step 的 observe/index 后做轻量 recall。
- 如果有明确候选，给 `UIContext.dynamicToolDefinitions` 注入最多三个临时工具。
- 在 action parser/executor 层把临时工具名映射回内部 Function replay。
- 每个 step 重新生成临时工具，不保留复杂运行状态。

第四阶段：跑 A/B/C/D 实验。

- 每组每任务 10 次。
- 输出 `recall-latency-report.json` 和 `recall-latency-report.md`。
- 只有 D 同时满足成功率和延迟目标，才替换当前默认策略。

## 风险与边界

- 不要恢复在线 VLM 文本 action fallback。
- 不要让模型输出裸 `function_id` 或 `call_tool(function_id)` 作为普通 action。
- 不要把 Function replay/checker/page guard 逻辑搬进 prompt。
- 不要用 recall 结果绕过 dangerous-operation risk gate。
- 不要因为追求速度去掉 fresh observe；可以减少 recall 次数，但执行前仍要有当前页校验。

## 推荐最终默认策略

```text
recall_enabled = true
recall_max_candidates = 3
recall_max_tools_per_step = 3
recall_decision_mode = context_only
```

一句话：当前慢不是因为 observe，而是 recall selector 和 replay fallback 与 VLM streaming 串行叠加。下一版应该把 recall 从“每步预选执行”改成“每步轻量候选工具注入”，让 planner 基于当前页面决定是否调用，同时保留 Function replay 的本地安全边界。
