# OmniFlow / Function / RunLog Implementation Map

本文档把当前 OmniFlow、OOB Function、RunLog replay、VLM recall、`update_function`
相关内容落到一个工程维护入口里。它回答三个问题：

- 主线到底是什么。
- 核心后端代码在哪里，各自负责什么。
- 哪些名字和分支是兼容层或废弃层，不应该继续扩展。

## 一句话主线

```text
RunLog
  -> Function conversion / enhancement
  -> Function store + UDEG recall index
  -> recall guidance 写入 VLM/page context
  -> agent/VLM 选择 oob_function_run 并填写参数
  -> guard check
  -> Function.steps 顺序 replay
  -> 失败返回 fallback_context
  -> agent 接管失败 step，或用 update_function 基于 RunLog evidence 修复 Function
```

OmniFlow 在这里是确定性 replay enhancer，不是 planner。它不维护第二套 pending
queue，不根据页面状态自动跳过中间步骤，不做 semantic/navigation recovery。真实页面变化
导致 replay 失败时，runner 返回结构化 fallback，上层 agent 决定继续操作或修复 Function。

## 概念边界

| 概念 | 主职责 | 主入口 | 不再扩展 |
| --- | --- | --- | --- |
| RunLog | 记录一次真实执行或人工录制的证据 | `oob_run_log_*` | inline 临时 replay 规则 |
| Function | 可复用 GUI 工作流资产 | `oob_function_*` | legacy reusable-workflow wording |
| Replay | 顺序执行 `Function.steps` | `oob_function_run` | `call_tool` / `run_function` 主路径 |
| Recall | 本地候选检索并写入上下文 | VLM page context / guidance | recall 前置强规则自动执行 |
| Checker | 可选条件处理，如广告/弹窗/权限 | Function metadata / checker rules | 必经 happy path step |
| Fallback | replay 失败后的结构化交接 | `fallback_context` | 隐式 VLM fallback 状态机 |
| Update | Function 的唯一保存修改入口 | `update_function` | 直接改 JSON 或重新注册覆盖 |

## 工具面

### 当前主工具

```text
oob_function_list
oob_function_get
oob_function_register
update_function
oob_function_guard_check
oob_function_run
oob_function_delete
oob_function_clear
oob_run_log_list
oob_run_log_get
oob_run_log_convert
```

`oob_function_run` 是 model-visible replay tool。带参数 Function 也走这个工具：
recall 返回 `inputSchema`、`function_profile`、`argument_policy`，agent/VLM 像普通
tool 一样填参数再调用。

### 外部/直接工具名

这些名字按边界使用：`call_tool` 是统一调用语言，`oob_function_run` 是直接本地执行工具。

```text
call_tool
run_function
omniflow.call_tool
omniflow.recall
omniflow.ingest_run_log
omniflow.explore_replay
```

新 Function spec、VLM guidance、MCP schema、tool card、测试断言都应该优先使用
`oob_function_run`。如果输入证据来自旧名字，只保存在 `source_tool` 或 import
metadata，不把旧名字写回主路径。

## 后端代码位置

### Function 核心

| 文件 | 责任 | 维护规则 |
| --- | --- | --- |
| `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionRepository.kt` | Function 存取、列表、删除、UDEG 同步入口 | 只做 repository/facade，不放 replay 规则 |
| `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionRunner.kt` | Function 执行入口，按 step 调 runner/guard/fallback | 保持顺序执行，不加 hidden queue |
| `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionRunPolicy.kt` | run 参数、resume、执行策略解析 | 只描述当前 run，不做 planner |
| `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionToolNames.kt` | Function 工具名常量 | `FUNCTION_RUN` 必须是 `oob_function_run` |
| `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionSpecVocabulary.kt` | spec 字段兼容词汇 | 旧字段只在这里集中归一 |
| `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionJson.kt` | JSON map/list 安全读取和 sanitize | 不放业务语义 |

### Function 生成、增强、修复

| 文件 | 责任 | 维护规则 |
| --- | --- | --- |
| `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionSpecBuilder.kt` | 手工/简单 Function 注册时构造 spec | 只负责注册 spec 构造，不复制 RunLog compiler |
| `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionUpdateService.kt` | `update_function` 入口、patch 保存、evidence 保存 | 所有保存修改走这里 |
| `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionUpdateIntentParser.kt` | 自然语言 correction 到结构化意图 | 只产意图，不直接写 spec |
| `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionStructuralPatchApplier.kt` | insert/delete/reorder 等结构 patch | 必须显式允许结构变化 |
| `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionMetadataPatchApplier.kt` | 描述、标签、metadata patch | 默认增强优先走 metadata/annotation |
| `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionCheckerPatchService.kt` | checker patch 验证和保存 | 广告/弹窗类逻辑变 optional checker |
| `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionRunLogAnalysisContract.kt` | RunLog evidence analysis JSON contract | skill prompt 和 Kotlin schema 对齐 |
| `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionRunLogEvidencePackager.kt` | 打包 Function + RunLog 给 agent 分析 | 不做复杂自动推理 |

### RunLog 转 Function

| 文件 | 责任 | 维护规则 |
| --- | --- | --- |
| `app/src/main/java/cn/com/omnimind/bot/runlog/RunLogReusableFunctionCompiler.kt` | RunLog 到 Function 的主编译流程 | 编排 codec/classifier/schema builder |
| `app/src/main/java/cn/com/omnimind/bot/runlog/RunLogReplayStepCompiler.kt` | 单个 RunLog card 到 replay step | 不做 store/update/replay |
| `app/src/main/java/cn/com/omnimind/bot/runlog/RunLogReplayStepNoiseNormalizer.kt` | wait、失败 card、重复输入等确定性清理 | 清理发生在编译期，不做运行时跳步 |
| `app/src/main/java/cn/com/omnimind/bot/runlog/OobFunctionSchemaBuilder.kt` | 生成 Function schema/profile/export 字段 | 不做 action 判定 |
| `app/src/main/java/cn/com/omnimind/bot/runlog/RunLogReplayPolicy.kt` | RunLog replay tool/action 策略常量 | 统一 `call_tool` 与直接 replay 工具边界 |
| `app/src/main/java/cn/com/omnimind/bot/runlog/RunLogCardAccessors.kt` | RunLog card 字段读取 | 避免各处散落 JSON 访问 |

### Action 和 Step 统一判断

| 文件 | 责任 | 维护规则 |
| --- | --- | --- |
| `app/src/main/java/cn/com/omnimind/bot/runlog/OobActionCodec.kt` | action 名称、参数、source context 的唯一解析器 | 不能新增本地重复 `when(action)` |
| `app/src/main/java/cn/com/omnimind/bot/runlog/OobStepRoleClassifier.kt` | required/noise/duplicate/checker 等 step role 判断 | 不在 compiler/runner 里复制 role 规则 |

如果代码要回答“这是什么 action”或“这个 step 有什么角色”，默认应该先调用这两个类。

### Replay 执行

| 文件 | 责任 | 维护规则 |
| --- | --- | --- |
| `app/src/main/java/cn/com/omnimind/bot/runlog/UIStepExecutor.kt` | 具体 GUI action 执行和 re-grounding | 只执行当前 step，不决定跳过未来 step |
| `app/src/main/java/cn/com/omnimind/bot/runlog/OmniflowActionBackend.kt` | 底层 action backend 抽象 | 不放 Function spec 语义 |
| `app/src/main/java/cn/com/omnimind/bot/runlog/OmniflowCheckerRule.kt` | checker rule 表达 | checker 是条件处理，不是 planner |
| `app/src/main/java/cn/com/omnimind/bot/runlog/OobRunLogReplayService.kt` | RunLog replay service 入口 | 作为兼容/工具服务，不分叉 Function runner |
| `app/src/main/java/cn/com/omnimind/bot/runlog/OobOmniFlowToolkitService.kt` | legacy `omniflow.*` adapter 服务 | 适配到 Function/RunLog 主工具 |

### Recall / UDEG

| 文件 | 责任 | 维护规则 |
| --- | --- | --- |
| `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionRecallService.kt` | Function recall 服务 | 只返回候选和 context，不执行 |
| `app/src/main/java/cn/com/omnimind/bot/runlog/OobUdegNodeStore.kt` | 页面节点、Function edge、page observation、recall index | edge kind 用 `function_call`，不恢复 source alignment skip |
| `app/src/main/java/cn/com/omnimind/bot/runlog/OobPageVectorSet.kt` | 页面向量/信号匹配 | 只用于 recall/ranking |
| `app/src/main/java/cn/com/omnimind/bot/runlog/RunLogPagePackageInference.kt` | 从 RunLog 推断 package/page | 只做 evidence enrichment |
| `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionTargetSourceMatcher.kt` | 当前 step 目标 source matching | 只改进当前 step re-grounding，不做自动跳步 |

### VLM / Agent 接入

| 文件 | 责任 | 维护规则 |
| --- | --- | --- |
| `app/src/main/java/cn/com/omnimind/bot/vlm/VlmToolCoordinator.kt` | VLM task 编排、recall guidance 注入、可选 strict hit 自动执行 | 默认 recall 是上下文；自动执行只在显式高置信策略下 |
| `app/src/main/java/cn/com/omnimind/bot/vlm/VlmRecallGuidanceBuilder.kt` | 把 recall candidate 渲染成 VLM 可读 guidance | 必须包含参数 schema/profile/policy |
| `app/src/main/java/cn/com/omnimind/bot/vlm/OobVlmPageContextProvider.kt` | 当前页面 UDEG capability context | 只给 VLM 决策，不直接执行 |
| `assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/VLMToolDefinitions.kt` | VLM 可见 tool 定义 | 暴露 `oob_function_run` |
| `assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/VLMClient.kt` | VLM action 解析 | 可接受旧名，但规范输出 `oob_function_run` |
| `assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/VLMFunctionRunRegistry.kt` | VLM function run action 注册/回调 | 连接 VLM action 到 native runner |
| `assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/ActionExecutor.kt` | VLM action executor | Function action 走 registry，不手写 replay |

### Agent / MCP 工具入口

| 文件 | 责任 | 维护规则 |
| --- | --- | --- |
| `app/src/main/java/cn/com/omnimind/bot/agent/tool/AgentToolDefinitions.kt` | agent tool schema | `oob_function_run` 是主工具说明 |
| `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/OobFunctionToolHandler.kt` | Function tools handler 主入口 | list/get/register/update/guard/run/delete/clear 汇聚 |
| `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/OobFunctionCallRequestResolver.kt` | run 请求解析、旧字段兼容 | 不产生第二套 call_tool 语义 |
| `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/OobFunctionGraphStepRunner.kt` | graph/function step 运行 | 不维护 pending stack |
| `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/OobFunctionRunResultBuilder.kt` | run 输出和 fallback 输出 | 失败时输出 `fallback_context`/resume 字段 |
| `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/OobFunctionNestedFunctionExecutor.kt` | nested Function step 执行 | 调 `oob_function_run` |
| `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/OobFunctionNestedCallCardPresenter.kt` | nested Function tool card 展示 | card/tool 名使用 `oob_function_run` |
| `app/src/main/java/cn/com/omnimind/bot/mcp/McpToolDefinitions.kt` | MCP tool schema 和 prompt guidance | 新 schema 主推 `oob_function_run` |
| `app/src/main/java/cn/com/omnimind/bot/mcp/McpRoutes.kt` | MCP route 到 service/handler | legacy adapter 只转发 |
| `app/src/main/java/cn/com/omnimind/bot/mcp/McpToolExecutors.kt` | MCP tool 执行编排 | 不绕过 Function handler |
| `app/src/main/java/cn/com/omnimind/bot/manager/AssistsCoreManager.kt` | Android runtime tool meta / stream 映射 | 旧名归到 `oob_function` meta |

## `update_function` 工作流

### 默认增强

增强是 offline 行为，不执行 Function。它应该：

- 清理或标注确定性噪声。
- 给 Function 写更详细的简介。
- 标注每个动作做什么、为什么存在。
- 补充 selector hints、参数说明、success signal。
- 把广告、跳过、关闭弹窗、权限提示整理为 optional checker。
- 保存时走 `update_function`。

### 用户纠错

用户说“应该点「外卖」而不是点「美食」”时，agent 应该：

1. `oob_function_get` 读取 Function。
2. 将自然语言纠错翻译成结构化 analysis + patch。
3. 默认做 retarget/label/selector hint patch。
4. 只有用户明确允许时才 insert/delete/reorder executable steps。
5. 调 `update_function` 保存。

### RunLog evidence

`update_function({functionId, run_id})` 只打包证据：

```json
{
  "needs_agent_analysis": true,
  "analysis_context": {},
  "agent_prompt": "..."
}
```

agent 根据 prompt 产出：

```json
{
  "summary": "这次 RunLog 说明 Function 为什么成功/失败",
  "step_findings": [
    {
      "function_step_index": 1,
      "runlog_card_index": 3,
      "label": "点击外卖入口",
      "role": "required_action | optional_checker | noise | duplicate | failed_action | success_evidence",
      "reason": "为什么这样判断"
    }
  ],
  "failure_reason": {
    "code": "wrong_target | target_missing | ad_interruption | repeated_input | unstable_coordinate | unknown",
    "message": "具体原因"
  },
  "recommended_patch": {
    "ops": []
  }
}
```

然后再调 `update_function({functionId, run_id, analysis, patch})` 保存 evidence
metadata 和安全 patch。

## Replay fallback 契约

失败时不要静默切到 live VLM。`oob_function_run` 返回结构化上下文：

```json
{
  "model_required": true,
  "failed_step_index": 2,
  "resume_from_step": 3,
  "fallback_context": {
    "failed_step": {},
    "remaining_steps": []
  }
}
```

agent 可以做两件事：

- 用 live VLM/人工路径完成失败 step，然后带返回的
  `resume_from_step` 再调 `oob_function_run`。这里 `failed_step_index` 是失败步，
  `resume_from_step` 是 agent 完成失败步后要继续的下一步；如果要重试失败步本身，
  显式传 `failed_step_index` 作为起点。`start_step_index` 只是
  `resume_from_step` 的兼容拼写。
- 如果证据说明 Function 本身错了，先用 `update_function` 修复，再运行。

嵌套 Function 的失败不能被父级吞掉。如果父 step 调用子 Function，而子 Function
需要 agent fallback，父 step 必须标记 `model_required=true` 并保留
`nested_*` fallback 证据；顶层 `oob_function_run` 仍返回父 step 的结构化
fallback handoff。

## 广告和弹窗

广告、跳过、关闭按钮、权限弹窗、优惠券弹窗、键盘遮挡不是 100% happy path。默认处理：

- 标为 `optional_checker`。
- 写入 checker metadata。
- 不插入必经 executable step。
- 如果 checker 不出现，主路径继续执行当前 step。

可用识别信号：

- 文本：`跳过`、`关闭`、`稍后`、`取消`、`知道了`、`Skip`、`Close`、`Ad`。
- close/skip content description。
- 遮挡目标区域的全屏或半屏 overlay。
- 同一 Function 成功 RunLog 中不总是出现。
- 关闭后继续同一个主路径动作。

## 编译期清理，不是运行时跳步

这些可以在 RunLog 转 Function 或 enhancement 阶段标注/合并/删除：

- 无条件 `wait`。
- 被具体 action 替代的 perception wrapper。
- 已失败且后面有成功 action 的 card。
- 同一字段同一内容的重复输入。
- debug/log/provider wrapper card。

这些不应该成为运行时“智能跳步”逻辑。运行时只执行 `Function.steps` 当前 step。

## 明确不要恢复的内容

不要作为主路径字段或新能力恢复：

```text
PendingActionStack
pending_action_stack
source_alignment_enabled
skipped_by_source_alignment_count
automatic step skipping
semantic/navigation recovery
navigate_recovery_available
blocked_executor
fallback_available
generic needs_agent as replay state
omniflow_vlm_fallback as executor/state
```

说明：

- `needs_agent` 可以作为 guard decision 或 legacy value 出现，但不是 replay 状态机。
- `requires_agent_fallback` 只做 legacy/import metadata。新 spec 用
  `has_agent_steps` 表达是否含 agent step。
- source/target matching 只用于当前 step 重定位，不用于判断 2、3 步已满足并跳过。

## 文档和 Skill 位置

工程维护文档：

- `docs/omniflow/README.md`：文档包入口。
- `docs/omniflow/function-replay-unified-design.md`：当前统一设计 source of truth。
- `docs/omniflow/IMPLEMENTATION_MAP.md`：本文，代码和概念索引。
- `docs/omniflow/MCP_CONTRACT.md`：外部 MCP 工具契约。
- `docs/omniflow/FUNCTION_SPEC.md`：Function JSON 结构。
- `docs/omniflow/update-function.md`：`update_function` contract。
- `docs/omniflow/checkers.md`：checker 规则。
- `docs/omniflow/cleanup-rules.md`：RunLog 清理规则。
- `docs/omniflow/canonical-actions.md`：标准 action 词表。
- `docs/omniflow/oob-function-architecture.md`：高层 ownership。

Agent 内置 skill：

- `app/src/main/assets/builtin_skills/omniflow/SKILL.md`：当前 canonical skill。
- `app/src/main/assets/builtin_skills/omniflow/references/`：分层 skill references。
- `app/src/main/assets/builtin_skills/oob-function-management/SKILL.md`：兼容入口，规则应回写到 `omniflow` references。
- `app/src/main/assets/builtin_skills/omniflow-function-enhancer/SKILL.md`：旧增强入口，后续应继续向 `omniflow/references/function-enhancement.md` 收敛。

## 后续维护规则

- 新能力先判断它属于 Function、RunLog、recall、checker、fallback、update 中哪一个。
- 如果只是旧名兼容，集中放到 tool name/vocabulary/resolver，不扩散到业务逻辑。
- 如果是 action/step 判断，优先改 `OobActionCodec` 或 `OobStepRoleClassifier`。
- 如果是 saved Function 修改，必须走 `update_function`。
- 如果是 replay 失败处理，必须输出 fallback context，而不是新增隐式 executor。
- 如果是广告/弹窗/权限/键盘处理，默认做 optional checker。
- 如果要删除或合并 step，只能在编译/增强/update 阶段显式记录 evidence，不能运行时偷偷跳过。
