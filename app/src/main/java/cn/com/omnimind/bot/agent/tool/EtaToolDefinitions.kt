package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.i18n.PromptLocale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Eta 风格工具定义（二改：OmniBot-Eta）。
 *
 * 概念对齐 Mangi-11/Eta（PolyForm Noncommercial 1.0.0，仅借鉴设计、不复制代码）：
 *  - 设备直达：有稳定系统接口的任务优先调用结构化设备工具（[deviceToolDefinitions]），
 *    不依赖截图/无障碍猜测坐标；
 *  - Root 直达：需要完整系统能力时使用 su（Magisk/KernelSU/APatch）执行
 *    （[rootToolDefinitions]），与 Shizuku 特权层并列但独立。
 */
object EtaToolDefinitions {

    private fun currentLocale(): PromptLocale = AppLocaleManager.currentPromptLocale()

    private fun text(locale: PromptLocale, zh: String, en: String): String =
        if (locale == PromptLocale.ZH_CN) zh else en

    fun deviceToolDefinitions(locale: PromptLocale = currentLocale()): List<JsonObject> =
        listOf(
            deviceGetStatusTool(locale),
            deviceSetVolumeTool(locale),
            deviceMediaControlTool(locale),
            deviceAppsQueryTool(locale),
            deviceLaunchAppTool(locale),
            deviceClipboardSetTool(locale),
            deviceNotificationsReadTool(locale),
            deviceSetWifiTool(locale),
            deviceSetBluetoothTool(locale)
        ).map { AgentToolDefinitions.decorateToolDefinition(it, locale) }

    fun rootToolDefinitions(locale: PromptLocale = currentLocale()): List<JsonObject> =
        listOf(
            rootStatusTool(locale),
            rootExecTool(locale),
            rootSessionStartTool(locale),
            rootSessionExecTool(locale),
            rootSessionStopTool(locale),
            rootAppControlTool(locale),
            rootInputTool(locale),
            rootUiDumpTool(locale)
        ).map { AgentToolDefinitions.decorateToolDefinition(it, locale) }

    // ------------------------------------------------------------------
    // 设备直达（无需 Root）
    // ------------------------------------------------------------------

    private fun deviceGetStatusTool(locale: PromptLocale): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "device_get_status")
            put("displayName", text(locale, "设备状态", "Device Status"))
            put("toolType", "device")
            put(
                "description",
                text(
                    locale,
                    "读取设备实时状态：电池电量与充电状态、内存、存储、系统版本、运行时长、网络与 Wi-Fi 状态。用于回答「手机还有多少电」「内存还够吗」「系统版本是多少」等问题。",
                    "Read live device status: battery level & charging state, memory, storage, system version, uptime, network and Wi-Fi state. Use it to answer questions like \"how much battery is left\", \"is memory sufficient\", or \"what Android version is this\"."
                )
            )
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("includeNetwork") {
                        put("type", "boolean")
                        put(
                            "description",
                            text(locale, "是否包含网络/Wi-Fi 状态（默认 true）。", "Whether to include network / Wi-Fi state (default true).")
                        )
                    }
                }
            }
        }
    }

    private fun deviceSetVolumeTool(locale: PromptLocale): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "device_set_volume")
            put("displayName", text(locale, "设置音量", "Set Volume"))
            put("toolType", "device")
            put(
                "description",
                text(
                    locale,
                    "设置指定通道的音量：media（媒体）、ring（铃声）、notification（通知）、alarm（闹钟）。level 为 0-100 的百分比；silent=true 表示静音（仅铃声/通知通道有效）。",
                    "Set the volume of a channel: media, ring, notification, or alarm. level is a percentage from 0 to 100; silent=true mutes the channel (ring/notification only)."
                )
            )
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("channel") {
                        put("type", "string")
                        put(
                            "description",
                            text(locale, "音量通道。", "Volume channel.")
                        )
                        putJsonArray("enum") {
                            add("media")
                            add("ring")
                            add("notification")
                            add("alarm")
                        }
                    }
                    putJsonObject("level") {
                        put("type", "integer")
                        put(
                            "description",
                            text(locale, "音量百分比 0-100。", "Volume percentage 0-100.")
                        )
                    }
                    putJsonObject("silent") {
                        put("type", "boolean")
                        put(
                            "description",
                            text(locale, "静音（仅 ring/notification 有效）。", "Mute (ring/notification only).")
                        )
                    }
                }
                putJsonArray("required") {
                    add("channel")
                    add("level")
                }
            }
        }
    }

    private fun deviceMediaControlTool(locale: PromptLocale): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "device_media_control")
            put("displayName", text(locale, "媒体控制", "Media Control"))
            put("toolType", "device")
            put(
                "description",
                text(
                    locale,
                    "控制当前活跃的媒体会话：play（播放）、pause（暂停）、next（下一首）、previous（上一首）、stop（停止）。",
                    "Control the currently active media session: play, pause, next, previous, or stop."
                )
            )
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("action") {
                        put("type", "string")
                        put("description", text(locale, "要执行的媒体动作。", "Media action to perform."))
                        putJsonArray("enum") {
                            add("play")
                            add("pause")
                            add("next")
                            add("previous")
                            add("stop")
                        }
                    }
                }
                putJsonArray("required") {
                    add("action")
                }
            }
        }
    }

    private fun deviceAppsQueryTool(locale: PromptLocale): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "device_apps_query")
            put("displayName", text(locale, "应用占用查询", "App Usage Query"))
            put("toolType", "device")
            put(
                "description",
                text(
                    locale,
                    "查询应用占用：scope=memory 返回当前最占内存的进程；scope=storage 返回最占存储的应用（需要「使用情况访问」权限，未授予时返回权限提示）。limit 控制返回条数（默认 10）。",
                    "Query app usage: scope=memory returns the top memory-consuming processes; scope=storage returns the top storage-consuming apps (requires \"Usage access\" permission, otherwise a permission hint is returned). limit controls the result count (default 10)."
                )
            )
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("scope") {
                        put("type", "string")
                        put("description", text(locale, "查询维度。", "Query scope."))
                        putJsonArray("enum") {
                            add("memory")
                            add("storage")
                        }
                    }
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("description", text(locale, "返回条数，默认 10。", "Result count, default 10."))
                    }
                }
                putJsonArray("required") {
                    add("scope")
                }
            }
        }
    }

    private fun deviceLaunchAppTool(locale: PromptLocale): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "device_launch_app")
            put("displayName", text(locale, "启动应用", "Launch App"))
            put("toolType", "device")
            put(
                "description",
                text(
                    locale,
                    "按包名启动应用（如 com.tencent.mm）。需要目标应用有可启动入口；可用 context_apps_query 先查询应用列表。",
                    "Launch an app by package name (e.g. com.tencent.mm). The target app needs a launchable entry; use context_apps_query to list installed apps first."
                )
            )
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("packageName") {
                        put("type", "string")
                        put("description", text(locale, "目标应用包名。", "Target app package name."))
                    }
                }
                putJsonArray("required") {
                    add("packageName")
                }
            }
        }
    }

    private fun deviceClipboardSetTool(locale: PromptLocale): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "device_clipboard_set")
            put("displayName", text(locale, "写入剪贴板", "Set Clipboard"))
            put("toolType", "device")
            put(
                "description",
                text(
                    locale,
                    "将指定文本写入系统剪贴板，供用户或后续操作粘贴。",
                    "Write the given text to the system clipboard for the user or later steps to paste."
                )
            )
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("text") {
                        put("type", "string")
                        put("description", text(locale, "要写入剪贴板的文本。", "Text to write to the clipboard."))
                    }
                }
                putJsonArray("required") {
                    add("text")
                }
            }
        }
    }

    private fun deviceNotificationsReadTool(locale: PromptLocale): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "device_notifications_read")
            put("displayName", text(locale, "读取通知", "Read Notifications"))
            put("toolType", "device")
            put(
                "description",
                text(
                    locale,
                    "读取当前状态栏通知（应用、标题、正文）。需要先授予「通知使用权」（设置 → 通知使用权 → 开启 OmniBot-Eta）。未授权时返回权限提示。",
                    "Read current status-bar notifications (app, title, body). Requires \"Notification access\" (Settings → Notification access → enable OmniBot-Eta). Returns a permission hint when not granted."
                )
            )
            putJsonObject("parameters") {
                put("type", "object")
                put("properties", JsonObject(emptyMap()))
            }
        }
    }

    private fun deviceSetWifiTool(locale: PromptLocale): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "device_set_wifi")
            put("displayName", text(locale, "开关 Wi-Fi", "Toggle Wi-Fi"))
            put("toolType", "device")
            put(
                "description",
                text(
                    locale,
                    "开启或关闭 Wi-Fi。Android 13+ 对第三方应用关闭 Wi-Fi 有限制：失败时会提示改用 root_exec（su cmd wifi set-wifi-enabled）或 android_privileged_action 的 device_control.set_wifi_enabled。",
                    "Turn Wi-Fi on or off. Android 13+ restricts third-party Wi-Fi toggling: on failure, fall back to root_exec (su cmd wifi set-wifi-enabled) or the android_privileged_action device_control.set_wifi_enabled action."
                )
            )
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("enabled") {
                        put("type", "boolean")
                        put("description", text(locale, "true 开启，false 关闭。", "true to enable, false to disable."))
                    }
                }
                putJsonArray("required") {
                    add("enabled")
                }
            }
        }
    }

    private fun deviceSetBluetoothTool(locale: PromptLocale): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "device_set_bluetooth")
            put("displayName", text(locale, "开关蓝牙", "Toggle Bluetooth"))
            put("toolType", "device")
            put(
                "description",
                text(
                    locale,
                    "开启或关闭蓝牙。Android 13+ 限制第三方应用直接开关蓝牙：失败时提示改用 root_exec（su cmd bluetooth_manager enable/disable）。",
                    "Turn Bluetooth on or off. Android 13+ restricts third-party Bluetooth toggling: on failure, use root_exec (su cmd bluetooth_manager enable/disable)."
                )
            )
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("enabled") {
                        put("type", "boolean")
                        put("description", text(locale, "true 开启，false 关闭。", "true to enable, false to disable."))
                    }
                }
                putJsonArray("required") {
                    add("enabled")
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Root 直达（su：Magisk / KernelSU / APatch）
    // ------------------------------------------------------------------

    private fun rootStatusTool(locale: PromptLocale): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "root_status")
            put("displayName", text(locale, "Root 状态", "Root Status"))
            put("toolType", "root")
            put(
                "description",
                text(
                    locale,
                    "检测并返回 Root 状态：是否可用、后端（Magisk/KernelSU/APatch/su）、版本、su 路径、BusyBox 是否可用。forceRefresh=true 时重新探测。未 Root 时返回引导说明（KernelSU/Magisk 管理器授权本应用）。",
                    "Detect and return Root status: availability, backend (Magisk/KernelSU/APatch/su), version, su path, and BusyBox availability. Use forceRefresh=true to re-probe. When not rooted, returns guidance on granting this app root access."
                )
            )
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("forceRefresh") {
                        put("type", "boolean")
                        put("description", text(locale, "是否强制重新探测（默认 false）。", "Force re-probe (default false)."))
                    }
                }
            }
        }
    }

    private fun rootExecTool(locale: PromptLocale): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "root_exec")
            put("displayName", text(locale, "Root 命令执行", "Root Shell Exec"))
            put("toolType", "root")
            put(
                "description",
                text(
                    locale,
                    "以 root 身份执行一次性 shell 命令（su -c）。需要 cwd/环境变量保持时请改用 root_session_start + root_session_exec。高风险命令（pm disable-user、rm -rf 等）必须在 confirmed=true 中显式确认。输出会被截断。",
                    "Run a one-shot shell command as root (su -c). When you need persistent cwd/environment, use root_session_start + root_session_exec instead. High-risk commands (pm disable-user, rm -rf, ...) require confirmed=true. Output is truncated."
                )
            )
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("command") {
                        put("type", "string")
                        put("description", text(locale, "要执行的 shell 命令。", "The shell command to execute."))
                    }
                    putJsonObject("timeoutSeconds") {
                        put("type", "integer")
                        put("description", text(locale, "超时秒数，默认 30。", "Timeout in seconds, default 30."))
                    }
                    putJsonObject("confirmed") {
                        put("type", "boolean")
                        put(
                            "description",
                            text(
                                locale,
                                "高风险命令须为用户明确同意后传 true。",
                                "Must be true after the user explicitly confirms high-risk commands."
                            )
                        )
                    }
                }
                putJsonArray("required") {
                    add("command")
                    add("confirmed")
                }
            }
        }
    }

    private fun rootSessionStartTool(locale: PromptLocale): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "root_session_start")
            put("displayName", text(locale, "Root 会话启动", "Root Session Start"))
            put("toolType", "root")
            put(
                "description",
                text(
                    locale,
                    "启动一个持久的 root shell 会话（保留 cwd 与环境变量），返回 sessionId。后续用 root_session_exec 执行命令、root_session_stop 结束。适合需要连续多步操作/诊断的场景。",
                    "Start a persistent root shell session (keeping cwd and environment), returning a sessionId. Use root_session_exec to run commands and root_session_stop to end it. Good for multi-step operations or diagnostics."
                )
            )
            putJsonObject("parameters") {
                put("type", "object")
                put("properties", JsonObject(emptyMap()))
            }
        }
    }

    private fun rootSessionExecTool(locale: PromptLocale): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "root_session_exec")
            put("displayName", text(locale, "Root 会话执行", "Root Session Exec"))
            put("toolType", "root")
            put(
                "description",
                text(
                    locale,
                    "在指定 root 会话中执行命令，返回该次输出（自动等待命令完成）。",
                    "Execute a command in the given root session and return its output (waits for the command to finish)."
                )
            )
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("sessionId") {
                        put("type", "string")
                        put("description", text(locale, "root_session_start 返回的会话 ID。", "Session ID returned by root_session_start."))
                    }
                    putJsonObject("command") {
                        put("type", "string")
                        put("description", text(locale, "要执行的命令。", "Command to execute."))
                    }
                    putJsonObject("timeoutSeconds") {
                        put("type", "integer")
                        put("description", text(locale, "超时秒数，默认 60。", "Timeout in seconds, default 60."))
                    }
                }
                putJsonArray("required") {
                    add("sessionId")
                    add("command")
                }
            }
        }
    }

    private fun rootSessionStopTool(locale: PromptLocale): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "root_session_stop")
            put("displayName", text(locale, "Root 会话结束", "Root Session Stop"))
            put("toolType", "root")
            put(
                "description",
                text(locale, "结束指定 root 会话，释放进程。", "Stop the given root session and release its process.")
            )
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("sessionId") {
                        put("type", "string")
                        put("description", text(locale, "要结束的会话 ID。", "Session ID to stop."))
                    }
                }
                putJsonArray("required") {
                    add("sessionId")
                }
            }
        }
    }

    private fun rootAppControlTool(locale: PromptLocale): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "root_app_control")
            put("displayName", text(locale, "应用管控", "App Control"))
            put("toolType", "root")
            put(
                "description",
                text(
                    locale,
                    "以 root 管控应用：force_stop（强制停止）、disable_user（冻结，对当前用户禁用）、enable（恢复）。核心系统包（android、com.android.* 等）默认受保护不可操作。所有操作必须 confirmed=true。",
                    "Control apps as root: force_stop, disable_user (freeze for current user), or enable (restore). Core system packages (android, com.android.*, ...) are protected by default. All actions require confirmed=true."
                )
            )
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("packageName") {
                        put("type", "string")
                        put("description", text(locale, "目标应用包名。", "Target app package name."))
                    }
                    putJsonObject("action") {
                        put("type", "string")
                        put("description", text(locale, "操作类型。", "Action type."))
                        putJsonArray("enum") {
                            add("force_stop")
                            add("disable_user")
                            add("enable")
                        }
                    }
                    putJsonObject("confirmed") {
                        put("type", "boolean")
                        put(
                            "description",
                            text(
                                locale,
                                "必须在用户明确同意后传 true。",
                                "Must be true after the user explicitly confirms."
                            )
                        )
                    }
                }
                putJsonArray("required") {
                    add("packageName")
                    add("action")
                    add("confirmed")
                }
            }
        }
    }

    private fun rootInputTool(locale: PromptLocale): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "root_input")
            put("displayName", text(locale, "模拟输入", "Inject Input"))
            put("toolType", "root")
            put(
                "description",
                text(
                    locale,
                    "通过系统 input 命令模拟输入（root 会话）：tap（点击 x,y）、swipe（滑动 x1,y1→x2,y2，duration 毫秒）、text（输入文本）、keyevent（按键码，如 4=返回 3=主页 26=电源）。用于无障碍不可用时的 GUI 兜底。",
                    "Inject input via the system input command (root session): tap (x,y), swipe (x1,y1→x2,y2 with duration ms), text, or keyevent (e.g. 4=back, 3=home, 26=power). Use as a GUI fallback when accessibility is unavailable."
                )
            )
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("type") {
                        put("type", "string")
                        put("description", text(locale, "输入类型。", "Input type."))
                        putJsonArray("enum") {
                            add("tap")
                            add("swipe")
                            add("text")
                            add("keyevent")
                        }
                    }
                    putJsonObject("x") {
                        put("type", "integer")
                        put("description", text(locale, "tap 的 x 坐标。", "x coordinate for tap."))
                    }
                    putJsonObject("y") {
                        put("type", "integer")
                        put("description", text(locale, "tap 的 y 坐标。", "y coordinate for tap."))
                    }
                    putJsonObject("x2") {
                        put("type", "integer")
                        put("description", text(locale, "swipe 终点 x。", "Swipe end x."))
                    }
                    putJsonObject("y2") {
                        put("type", "integer")
                        put("description", text(locale, "swipe 终点 y。", "Swipe end y."))
                    }
                    putJsonObject("duration") {
                        put("type", "integer")
                        put("description", text(locale, "swipe 时长毫秒。", "Swipe duration in ms."))
                    }
                    putJsonObject("text") {
                        put("type", "string")
                        put("description", text(locale, "text 类型要输入的文本。", "Text to input for type=text."))
                    }
                    putJsonObject("keyCode") {
                        put("type", "integer")
                        put("description", text(locale, "keyevent 的按键码。", "Key code for keyevent."))
                    }
                }
                putJsonArray("required") {
                    add("type")
                }
            }
        }
    }

    private fun rootUiDumpTool(locale: PromptLocale): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "root_ui_dump")
            put("displayName", text(locale, "界面结构导出", "UI Hierarchy Dump"))
            put("toolType", "root")
            put(
                "description",
                text(
                    locale,
                    "通过 uiautomator 导出当前界面控件树 XML（root），供 GUI 分析使用。输出会被截断；失败时提示改用无障碍服务方案。",
                    "Dump the current UI hierarchy as XML via uiautomator (root) for GUI analysis. Output is truncated; on failure, fall back to the accessibility-service approach."
                )
            )
            putJsonObject("parameters") {
                put("type", "object")
                put("properties", JsonObject(emptyMap()))
            }
        }
    }
}
