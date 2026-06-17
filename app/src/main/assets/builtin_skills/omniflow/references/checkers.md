# Checkers

Use this reference for conditional obstacles such as ads, popups, permission
dialogs, skip buttons, banners, coupons, and keyboards.

## Core Rule

Conditional obstruction handling is an optional checker. It is not part of the
guaranteed happy path unless the user explicitly requests a structural repair.

A checker injects a conditional action into replay when the current XML proves
the obstacle exists. It does not retarget, replace, or compete with the
main-path action.

## How To Identify Ads Or Optional Obstructions

Treat a step as optional checker evidence when it:

- Clicks text like `跳过`, `关闭`, `稍后`, `取消`, `知道了`, `不再提示`, or a close
  icon.
- Dismisses content that is visually above or blocking the intended target.
- Appears in one RunLog but is not required in another successful RunLog for the
  same Function.
- Has no durable business meaning after it disappears.
- Is followed by the same main-path action that would have worked without the
  obstruction.

Do not use these signals alone to delete the action. Convert it into checker
metadata or evidence.

Convert only the obstacle-clearing action into checker metadata. If the recorded
step is still the intended main-path action and can be remapped by action
transfer, leave it executable.

## Supported Runtime Checker Rules

Use only supported runtime checker types:

- `overlay_blocking` + `dismiss`
- `permission_dialog` + `allow`
- `keyboard_obscuring` + `hide_keyboard`
- `package_mismatch` + `open_app`
- `app_upgrade_prompt` + `dismiss`

Do not invent checker conditions, scripts, selectors, or model calls.

Use `app_upgrade_prompt` for non-mandatory app upgrade/update prompts that
surface immediately after `open_app`, such as the Hi upgrade prompt. It should
click only negative choices like `以后再说`, `稍后再说`, `暂不升级`, `取消`,
`not now`, or `later`, and must not click `立即升级`, `立即更新`, `install`, or
`download`.

Use `permission_dialog` for Android system permission prompts. The runtime
must not replace a recorded permission-dialog action. If the recorded source
step clicks a permission control such as `始终允许`, keep that step in the
main path and let action transfer remap it to the current dialog. Add
`permission_dialog + allow` only as a fallback for unexpected prompts that block
a non-permission step. Do not create per-app selectors or extra label priority
rules.

## Patch Pattern

```json
{
  "steps": [
    {
      "index": 2,
      "title": "关闭可选广告弹窗",
      "description": "如果广告弹窗遮挡主路径，关闭它以继续后续操作。",
      "action_purpose": "处理可能出现的条件性遮挡物，不属于稳定主路径。",
      "importance": "optional",
      "cleanup_action": "optional_checker",
      "cleanup_reason": "广告弹窗不一定每次出现。",
      "optional_condition": "仅当广告弹窗实际遮挡目标区域时执行。"
    }
  ],
  "metadata": {
    "checker_rules": [
      {
        "id": "dismiss_optional_overlay",
        "condition": "overlay_blocking",
        "action": "dismiss",
        "enabled": true,
        "params": {}
      }
    ]
  },
  "agent_reuse": {
    "checker_assets": [
      {
        "checker_id": "dismiss_optional_overlay",
        "step_index": 2,
        "reason": "由录制中的关闭广告/弹窗动作提炼成条件 checker。"
      }
    ]
  }
}
```
