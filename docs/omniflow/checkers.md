# Runtime Checkers

Checkers handle unstable UI conditions that should not be part of the 100%
main path.

A checker is a conditional action injector. When its condition is observed in
the current accessibility XML, replay injects one deterministic action at the
configured phase. It does not retarget or replace the main-path action.

## Supported Rules

```text
ad_blocking + dismiss + pre_transfer
overlay_blocking + dismiss + pre_transfer
permission_dialog + allow + pre_transfer
keyboard_obscuring + hide_keyboard + pre_action
package_mismatch + open_app + pre_transfer
app_upgrade_prompt + dismiss + post_action
```

Examples:

```json
{
  "id": "dismiss_optional_overlay_before_action",
  "phase": "pre_transfer",
  "condition": "overlay_blocking",
  "action": "dismiss",
  "enabled": true,
  "params": {}
}
```

`ad_blocking` is a built-in global checker. It identifies ads from the current
accessibility XML by combining signals, not by one keyword alone:

- explicit ad words in text/resource/class: `广告`, `推广`, `sponsored`,
  `advert`, `splash`, `interstitial`
- dismiss controls: `跳过`, `跳过 3`, `skip`, `close ad`, `关闭广告`
- ad SDK/resource hints: `skip_ad`, `close_ad`, `tt_splash_skip`, `ksad_skip`,
  `gdt_skip`
- geometry: small enabled clickable control near the top-right of a splash or
  full-screen surface

Plain `关闭`/`x` is not enough unless the page also has an ad cue. This keeps
normal dialogs from being dismissed accidentally.

`app_upgrade_prompt` is a built-in global post-open-app checker. It handles
non-mandatory app upgrade/update prompts, including the Hi upgrade prompt, by
requiring upgrade evidence such as `新版本`, `版本更新`, `升级`,
`update available`, or `upgrade available`, then clicking only negative buttons such as
`以后再说`, `稍后再说`, `暂不升级`, `取消`, `not now`, or `later`. It must not click
positive choices such as `立即升级`, `立即更新`, `install`, or `download`.

`permission_dialog` is a built-in global pre-transfer checker for Android
permission controller dialogs. If the recorded source step already targets a
permission dialog control, such as `始终允许`, leave it as the main-path action so
action transfer can remap and click the matching current control. Use the
checker only as a fallback for unexpected permission prompts that block a
non-permission step; do not add per-app selectors or extra label priority rules.

## Conversion Rule

Recorded steps such as closing an ad, dismissing a coupon, granting a permission,
or hiding the keyboard should usually become checker rules instead of main-path
actions. The original step should keep an annotation explaining why it became a
checker candidate.

Only convert a recorded step into a checker when it represents the injected
obstacle-clearing action itself. If the recorded step is the intended main-path
action and action transfer can remap it, keep it executable and do not add a
checker that competes with it.

## Runtime Files

- Rule model: `OmniflowCheckerRule.kt`
- Rule execution: `OmniflowStepExecutor.kt`
- Function patching: `OobFunctionCheckerPatchService.kt`
- Agent maintenance workflow: `omniflow-checker-maintainer`
