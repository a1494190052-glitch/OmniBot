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
  "recall_selector_model": "scene.vlm.operation.primary",
  "distill_model": "scene.memory.rollup",
  "recall_enabled": true,
  "recall_max_candidates": 3,
  "distill_min_trace_steps": 2,
  "distill_max_skill_chars": 400,
  "disabled_tools": []
}
```

| 字段 | 含义 | 合法值 |
|------|------|--------|
| `primary_model` | VLM 主模型 | 非空字符串 |
| `recall_selector_model` | recall 候选选择模型 | 非空字符串 |
| `distill_model` | 经验蒸馏模型 | 非空字符串 |
| `recall_enabled` | 是否开启 recall 快速路径 | `true` / `false` |
| `recall_max_candidates` | recall 最多返回几个候选 | 1–10 |
| `distill_min_trace_steps` | 至少执行几步才触发经验蒸馏 | 1–10 |
| `distill_max_skill_chars` | guidance skill 单条上限字符数 | 100–1200 |
| `disabled_tools` | 禁用的工具名列表 | 见下方合法工具名 |

合法工具名（可放入 `disabled_tools`）：
`click` `long_press` `input_text` `swipe` `open_app` `press_key` `wait` `finished` `info` `feedback` `abort` `require_user_choice` `require_user_confirmation`

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
- `VlmRecallFunctionSelector` tag — 确认候选数量等参数生效
- `VlmGuidanceManager` tag — 确认 guidance 已加载
