# OmniBot-Eta 二改说明：Eta 式手机助手能力 + Root 授权

> 基线：`omnimind-ai/OmniBot` main @ `63a0e8030`（v0.5.8.4 之后）。
> 二改分支：`feature/eta-agent-root`。

## 1. 目标

在 OmniBot 上补齐两类能力（对齐 Mangi-11/Eta 的设计思路，**概念借鉴、未复制代码**）：

1. **设备直达（无需 Root）**：用结构化 Android 系统接口完成设备操作，
   代替「截图 → 找按钮 → 猜坐标」的 GUI 路径。
2. **Root 直达**：直接探测并调用 `su`（Magisk / KernelSU / APatch），
   提供一次性命令、持久会话、应用管控、模拟输入与界面导出能力，
   与现有 Shizuku 特权层（ROOT/ADB 后端）**并列**。

分层关系（与 Eta 一致的「四条路径」）：

```
设备直达（系统 API，本二改） ──→ 无接口时：GUI（无障碍/omniflow，已有）
网页任务（browser_use，已有）    终端：Root Shell（本二改）/ Alpine（已有）/ Shizuku（已有）
```

## 2. 新增文件

| 文件 | 职责 |
|---|---|
| `app/src/main/java/cn/com/omnimind/bot/root/RootShell.kt` | Root 探测（后端识别、BusyBox）、一次性 `su -c` 执行、持久会话 + 会话管理器 |
| `app/src/main/java/cn/com/omnimind/bot/agent/tool/EtaToolDefinitions.kt` | `device_*` 与 `root_*` 工具 Schema（中英双语，沿用 `decorateToolDefinition`） |
| `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/EtaDeviceToolHandler.kt` | 设备直达工具执行器 |
| `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/RootToolHandler.kt` | Root 工具执行器（高风险动作需 `confirmed=true`） |
| `.github/workflows/build-apk.yml` | GitHub Actions 编译：compile-check / debug APK / 签名 release APK |

修改：

| 文件 | 改动 |
|---|---|
| `AgentToolRegistry.kt` | 设备工具常驻注册；检测到 Root 时追加 `root_*` 工具 |
| `AgentToolRouter.kt` | 挂载 `EtaDeviceToolHandler`、`RootToolHandler` |

## 3. 新增工具清单

### device_*（常驻，无需 Root）
- `device_get_status`：电池/内存/存储/系统版本/运行时长/网络
- `device_set_volume`：media/ring/notification/alarm 四通道音量（0-100%）
- `device_media_control`：play/pause/next/previous/stop（活跃媒体会话）
- `device_apps_query`：最占内存进程 / 最占存储应用（后者需「使用情况访问」）
- `device_launch_app`：按包名启动应用
- `device_clipboard_set`：写入剪贴板
- `device_notifications_read`：状态栏通知（需「通知使用权」，服务接入 TODO）
- `device_set_wifi` / `device_set_bluetooth`：Android 13+ 受限时给出 root_exec 回退提示

### root_*（检测到 su 时注册）
- `root_status`：后端/版本/su 路径/BusyBox；未 Root 时返回授权引导
- `root_exec`：一次性 root 命令（**必须 `confirmed=true`**）
- `root_session_start` / `root_session_exec` / `root_session_stop`：持久 root shell（cwd/env 保留）
- `root_app_control`：force_stop / disable_user（冻结）/ enable（恢复）；核心系统包受保护
- `root_input`：tap / swipe / text / keyevent（GUI 兜底，无障碍不可用时）
- `root_ui_dump`：uiautomator 导出界面结构 XML

## 4. Root 授权（用户设备：一加 13 / Android 16 / KernelSU）

1. 打开 **KernelSU 管理器 → 超级用户**，将 OmniBot-Eta 设为
   **「直接授权」**（避免每次 `su` 都弹窗打断 Agent loop）。
2. 应用内发送「root_status」即可验证（`forceRefresh=true` 强制重探测）。
3. 未授权时的行为：`root_status` 返回 `available=false` 与引导文案；
   `root_exec` 等工具不会注册进模型工具表。

## 5. 编译（GitHub Actions）

本机环境受 Flutter/Gradle 版本校验限制，改用 GitHub Actions 编译：

- 触发：任意分支 push / PR / 手动 `workflow_dispatch`
- `compile-check`：`:app:compileDevelopStandardDebugKotlin`（改动的编译门禁）
- `build-debug`：`:app:assembleDevelopStandardDebug` + 上传 APK 工件
- `build-release`：配置 secrets `RELEASE_KEYSTORE` / `RELEASE_PASSWORD` /
  `RELEASE_KEY_ALIAS` 时构建签名 release（`if: secrets.RELEASE_KEYSTORE != ''`）
- 固定版本：JDK 17 + Flutter 3.38.7 + NDK 28.2.13676358 + Gradle wrapper 8.13
  （与上游 ci.yml 完全一致）

推送方式（二选一）：

```bash
# A. 新建独立仓库（推荐）：先在 GitHub 建空仓库 a1494190052-glitch/OmniBot-Eta
git remote add eta https://github.com/a1494190052-glitch/OmniBot-Eta.git
git push -u eta feature/eta-agent-root

# B. 推到现有 OmniBot fork 的同名分支
git remote add fork https://github.com/a1494190052-glitch/OmniBot.git
git push -u fork feature/eta-agent-root
```

## 6. 安全边界（对齐 Eta 的防护理念）

- 高风险动作（`root_exec` / `root_app_control`）**必须 `confirmed=true`**（模型不得自行假设用户同意）
- 核心系统包（android / com.android.* / 本项目自身）在 `root_app_control` 中受保护
- 敏感输出（stdout/stderr）在写回上下文前截断
- 设备直达能力默认可用但受 Android 权限体系约束；敏感数据（通知等）依赖用户显式授权

## 7. TODO / 路线图

- [ ] `device_notifications_read`：接入 NotificationListenerService（对齐 Eta 的本机 7 天/1000 条通知历史）
- [ ] Root 授权状态 UI：设置页展示 KernelSU 授权引导入口（Flutter 侧）
- [ ] `root_input` / `root_ui_dump` 与现有 omniflow GUI 运行时的联动（无障碍 → root 双通道）
- [ ] `device_apps_query` 内存 PSS 的 PACKAGE_USAGE_STATS 优雅降级
- [ ] 设置开关：默认开启的设备能力分组（参考 Eta 的「设备直达/敏感读取/敏感操作」三组开关）
- [ ] 与 Shizuku 特权层的去重策略：同一能力优先走 device_*（无特权）、其次 Shizuku、最后直接 su

## 8. 许可说明

- 上游 OmniBot：非商业 AGPL v3 / 商业双许可（见 LICENSE）
- 参考项目 Eta（Mangi-11/Eta）：PolyForm Noncommercial 1.0.0——
  本二改**仅借鉴设计思路与工具命名风格，未复制其代码**；若后续移植 Eta 具体实现，
  需遵守其 Noncommercial 条款（个人使用 OK，商用需单独取得授权）
