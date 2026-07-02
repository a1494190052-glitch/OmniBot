---
name: tune-vlm
description: Tune VLM automation behavior without recompiling. Use when the user wants to change VLM strategy, disable a gesture, switch model, adjust recall, modify planner prompt, 调整 VLM 行为, 修改 VLM 策略, 换模型, 禁用 swipe, 修改 recall 参数, 调 planner 的提示词, vlm 操作太激进/保守.
---

# Tune VLM

All VLM behavior is controlled by files in `.omnibot/agent/`. Edits take effect on the next VLM task — no recompile, no restart.

---

## Config Parameters — `.omnibot/agent/vlm_config.json`

Read this file first, then edit only the fields you need.

```json
{
  "primary_model": "scene.vlm.operation.primary",
  "distill_model": "scene.memory.rollup",
  "vlm_max_completion_tokens": 384,
  "vlm_temperature": 0.2,
  "vlm_history_rounds": 4,
  "vlm_history_action_chars": 160,
  "vlm_history_result_chars": 220,
  "vlm_tool_result_chars": 900,
  "vlm_default_max_steps": 12,
  "vlm_min_wait_timeout_ms": 30000,
  "vlm_max_wait_timeout_ms": 600000,
  "vlm_dry_run_prompt_preview_chars": 6000,
  "recall_enabled": true,
  "recall_max_candidates": 3,
  "recall_max_tools_per_step": 3,
  "recall_tool_name_prefix": "run_recalled_workflow",
  "recall_description_chars": 220,
  "recall_tool_description_chars": 520,
  "distill_min_trace_steps": 2,
  "distill_max_skill_chars": 400,
  "disabled_tools": []
}
```

| 字段 | 含义 | 合法值 |
|------|------|--------|
| `primary_model` | VLM 主模型 | 非空字符串 |
| `distill_model` | 经验蒸馏模型 | 非空字符串 |
| `vlm_max_completion_tokens` | 单步 VLM 最大输出 token | 64–2048 |
| `vlm_temperature` | 单步 VLM temperature | 0.0–2.0 |
| `vlm_history_rounds` | 带入最近多少轮已完成 action/result | 0–12 |
| `vlm_history_action_chars` | 单条历史 action 摘要字符上限 | 40–1000 |
| `vlm_history_result_chars` | 单条历史 result 摘要字符上限 | 40–2000 |
| `vlm_tool_result_chars` | 工具执行结果写入对话历史的字符上限 | 120–4000 |
| `vlm_default_max_steps` | 外部未传 `maxSteps` 时的默认最大步数 | 1–64 |
| `vlm_min_wait_timeout_ms` | 控制面等待超时下限 | 5000–600000 |
| `vlm_max_wait_timeout_ms` | 控制面默认/最大等待超时 | `vlm_min_wait_timeout_ms`–1800000 |
| `vlm_dry_run_prompt_preview_chars` | parse-only 返回 prompt preview 的字符上限 | 500–30000 |
| `recall_enabled` | 是否开启 recall 临时工具注入 | `true` / `false` |
| `recall_max_candidates` | 每步最多召回多少个 Function 候选 | 1–10 |
| `recall_max_tools_per_step` | 每步最多注入多少个 `run_recalled_workflow_N` 临时工具 | 0–10 |
| `recall_tool_name_prefix` | 临时工具名前缀 | 小写字母开头，运行时会清洗为合法 tool name |
| `recall_description_chars` | Function 描述摘要字符上限 | 40–1000 |
| `recall_tool_description_chars` | 临时工具完整 description 字符上限 | 120–2000 |
| `distill_min_trace_steps` | 至少执行几步才触发经验蒸馏 | 1–10 |
| `distill_max_skill_chars` | guidance skill 单条上限字符数 | 100–1200 |
| `disabled_tools` | 禁用的工具名列表 | 见下方合法工具名 |

合法工具名（可放入 `disabled_tools`）：
`click` `long_press` `input_text` `swipe` `open_app` `press_key` `wait` `finished` `info` `abort`

未知工具名会被忽略。超出范围的数值会被自动 clamp。

---

## Planner Prompt 追加 — `.omnibot/agent/vlm_strategies.md`

在文件末尾添加规则，每次 VLM 任务启动时自动追加到 system prompt 末尾。

```markdown
# 追加的策略规则
- 遇到广告弹窗立即关闭，不要等待
- 输入前先点击输入框确认已聚焦
- 每次 swipe 后等待 500ms 再继续
```

`#` 开头的行视为注释，不会注入 VLM。
文件不存在时 App 会自动创建模板。

---

## Per-Step Guidance Skill — `.omnibot/skills/vlm-guidance/SKILL.md`

每一步 VLM 调用前都会读取这个 skill 并注入 context。比 `vlm_strategies.md` 更精细，支持 `## Strategies` 和 `## Functions` 两个章节。

```markdown
---
name: vlm-guidance
description: VLM planner guidance
---

## Strategies
- 遇到登录弹窗先点击"稍后"按钮

## Functions
- fn_open_wechat_chat: 打开微信并进入聊天界面
```

`## Functions` 章节中的条目会注入 recall 选择 prompt，帮助模型识别已知功能。

App 会自动蒸馏成功任务的经验更新这个文件，也可以手动编辑。

---

## Per-App Guidance — `.omnibot/skills/vlm-app-{pkg}/SKILL.md`

`{pkg}` 是包名中的 `.` 替换为 `_`，最多 40 字符。例如：
- `vlm-app-com_tencent_mm` → 微信

格式与 `vlm-guidance` 相同，只在该 App 的任务中加载。

---

## 验证

改完文件后，执行任意 VLM 任务，查看 logcat：
- `VlmWorkspaceConfig` tag — 显示 `reloaded vlm_config.json`
- `VlmFunctionRecall` tag — 确认 recall 候选工具注入生效
- `VlmGuidanceManager` tag — 确认 guidance 已加载
