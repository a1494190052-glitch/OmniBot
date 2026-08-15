package cn.com.omnimind.bot.agent.tool.handlers

import android.app.ActivityManager
import android.app.usage.StorageStatsManager
import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.os.SystemClock
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Eta 风格「设备直达」工具执行器（二改：OmniBot-Eta）。
 *
 * 使用稳定的 Android 系统接口（而非截图/无障碍猜测）完成设备操作：
 * 状态读取、音量、媒体控制、应用占用、启动应用、剪贴板、通知（待授权）、Wi-Fi/蓝牙开关。
 * 需要更深系统能力时交给 RootToolHandler（root_*）。
 */
class EtaDeviceToolHandler(
    private val helper: SharedHelper
) : ToolHandler {

    override val toolNames: Set<String> = setOf(
        "device_get_status",
        "device_set_volume",
        "device_media_control",
        "device_apps_query",
        "device_launch_app",
        "device_clipboard_set",
        "device_notifications_read",
        "device_set_wifi",
        "device_set_bluetooth"
    )

    override suspend fun execute(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        return try {
            when (toolCall.function.name) {
                "device_get_status" -> executeDeviceGetStatus(args, callback)
                "device_set_volume" -> executeDeviceSetVolume(args, callback)
                "device_media_control" -> executeDeviceMediaControl(args, callback)
                "device_apps_query" -> executeDeviceAppsQuery(args, callback)
                "device_launch_app" -> executeDeviceLaunchApp(args, callback)
                "device_clipboard_set" -> executeDeviceClipboardSet(args, callback)
                "device_notifications_read" -> executeDeviceNotificationsRead(args, callback)
                "device_set_wifi" -> executeDeviceSetWifi(args, callback)
                "device_set_bluetooth" -> executeDeviceSetBluetooth(args, callback)
                else -> helper.errorResult(toolCall.function.name, null, "未知设备工具")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.errorResult(toolCall.function.name, e.message, "设备工具执行失败")
        }
    }

    private suspend fun executeDeviceGetStatus(
        args: JsonObject,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "device_get_status"
        return try {
            helper.ensureRunActive()
            helper.reportToolProgress(callback, toolName, "正在读取设备状态")
            val context = helper.context
            val payload = linkedMapOf<String, Any?>()

            // 电池
            val batteryIntent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            if (batteryIntent != null) {
                val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                payload["battery"] = linkedMapOf<String, Any?>(
                    "levelPercent" to (if (scale > 0) level * 100 / scale else level),
                    "charging" to (
                        status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL
                        ),
                    "pluggedType" to batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                )
            }

            // 内存
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            payload["memory"] = linkedMapOf<String, Any?>(
                "totalBytes" to memInfo.totalMem,
                "availableBytes" to memInfo.availMem,
                "lowMemory" to memInfo.lowMemory,
                "thresholdBytes" to memInfo.threshold
            )

            // 存储（数据分区）
            val stat = StatFs(Environment.getDataDirectory().path)
            payload["storage"] = linkedMapOf<String, Any?>(
                "totalBytes" to stat.totalBytes,
                "availableBytes" to stat.availableBytes
            )

            // 系统
            payload["system"] = linkedMapOf<String, Any?>(
                "androidVersion" to Build.VERSION.RELEASE,
                "apiLevel" to Build.VERSION.SDK_INT,
                "model" to Build.MODEL,
                "manufacturer" to Build.MANUFACTURER,
                "uptimeSeconds" to SystemClock.elapsedRealtime() / 1000
            )

            if (args["includeNetwork"]?.jsonPrimitive?.booleanOrNull != false) {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val activeNetwork = cm.activeNetwork
                val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val wifiInfo = wifiManager.connectionInfo
                payload["network"] = linkedMapOf<String, Any?>(
                    "connected" to (activeNetwork != null),
                    "wifiEnabled" to wifiManager.isWifiEnabled,
                    "wifiSsid" to (wifiInfo?.ssid?.takeIf { it != "<unknown ssid>" } ?: "<not connected>")
                )
            }

            val payloadJson = helper.encodeLocalizedPayload(payload)
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized("已读取设备实时状态。"),
                previewJson = payloadJson,
                rawResultJson = payloadJson,
                success = true
            )
        } catch (e: Exception) {
            helper.errorResult(toolName, e.message, "读取设备状态失败")
        }
    }

    private suspend fun executeDeviceSetVolume(
        args: JsonObject,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "device_set_volume"
        val channel = args["channel"]?.jsonPrimitive?.contentOrNull
        val level = args["level"]?.jsonPrimitive?.intOrNull
        val silent = args["silent"]?.jsonPrimitive?.booleanOrNull == true
        if (channel == null || level == null) {
            return helper.errorResult(toolName, null, "缺少 channel/level 参数")
        }
        if (level !in 0..100) {
            return helper.errorResult(toolName, null, "level 必须在 0-100 之间")
        }
        val audio = helper.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = when (channel) {
            "media" -> AudioManager.STREAM_MUSIC
            "ring" -> AudioManager.STREAM_RING
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "alarm" -> AudioManager.STREAM_ALARM
            else -> return helper.errorResult(toolName, null, "未知音量通道：$channel")
        }
        val isRingerLike = stream == AudioManager.STREAM_RING ||
            stream == AudioManager.STREAM_NOTIFICATION
        if (silent && isRingerLike) {
            audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
        } else if (!silent && isRingerLike) {
            audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
        }
        val max = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
        val target = (level * max / 100).coerceIn(0, max)
        audio.setStreamVolume(stream, target, 0)
        val current = audio.getStreamVolume(stream)
        val payload = linkedMapOf<String, Any?>(
            "channel" to channel,
            "requestedPercent" to level,
            "currentLevel" to current,
            "maxLevel" to max,
            "muted" to audio.isStreamMute(stream)
        )
        val payloadJson = helper.encodeLocalizedPayload(payload)
        return ToolExecutionResult.ContextResult(
            toolName = toolName,
            summaryText = helper.localized("已设置 $channel 音量为 $level%。"),
            previewJson = payloadJson,
            rawResultJson = payloadJson,
            success = true
        )
    }

    private suspend fun executeDeviceMediaControl(
        args: JsonObject,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "device_media_control"
        val action = args["action"]?.jsonPrimitive?.contentOrNull
            ?: return helper.errorResult(toolName, null, "缺少 action 参数")
        val msManager = helper.context.getSystemService(
            Context.MEDIA_SESSION_SERVICE
        ) as MediaSessionManager
        val controllers = msManager.getActiveSessions(null)
        val controller = controllers.firstOrNull { it.playbackState != null || it.metadata != null }
            ?: controllers.firstOrNull()
        if (controller == null) {
            return helper.errorResult(toolName, null, "当前没有活跃的媒体会话")
        }
        val transport = controller.transportControls
        when (action) {
            "play" -> transport.play()
            "pause" -> transport.pause()
            "next" -> transport.skipToNext()
            "previous" -> transport.skipToPrevious()
            "stop" -> transport.stop()
            else -> return helper.errorResult(toolName, null, "未知媒体动作：$action")
        }
        val payload = linkedMapOf<String, Any?>(
            "action" to action,
            "sessionPackage" to controller.packageName
        )
        val payloadJson = helper.encodeLocalizedPayload(payload)
        return ToolExecutionResult.ContextResult(
            toolName = toolName,
            summaryText = helper.localized("已向媒体会话发送 $action。"),
            previewJson = payloadJson,
            rawResultJson = payloadJson,
            success = true
        )
    }

    private suspend fun executeDeviceAppsQuery(
        args: JsonObject,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "device_apps_query"
        val scope = args["scope"]?.jsonPrimitive?.contentOrNull
            ?: return helper.errorResult(toolName, null, "缺少 scope 参数")
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 50)
        helper.reportToolProgress(callback, toolName, "正在查询应用占用")
        val context = helper.context
        return when (scope) {
            "memory" -> {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val processes = am.runningAppProcesses ?: emptyList()
                val pids = processes.map { it.pid }
                val pssMap = runCatching {
                    am.getProcessMemoryInfo(pids.toIntArray())
                }.getOrNull()
                val items = processes.mapIndexed { index, proc ->
                    linkedMapOf<String, Any?>(
                        "process" to proc.processName,
                        "importance" to proc.importance,
                        "pssKb" to (pssMap?.getOrNull(index)?.totalPss ?: -1)
                    )
                }.sortedByDescending { it["pssKb"] as? Int ?: -1 }.take(limit)
                val payload = linkedMapOf<String, Any?>(
                    "scope" to "memory",
                    "count" to items.size,
                    "items" to items
                )
                val payloadJson = helper.encodeLocalizedPayload(payload)
                ToolExecutionResult.ContextResult(
                    toolName, helper.localized("已查询最占内存的应用。"), payloadJson, payloadJson, true
                )
            }
            "storage" -> {
                try {
                    val pm = context.packageManager
                    val ssm = context.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
                    val apps = pm.getInstalledApplications(0)
                    val items = apps.mapNotNull { app ->
                        runCatching {
                            ssm.queryStatsForPackage(
                                app.storageUuid,
                                app.packageName,
                                android.os.Process.myUserHandle()
                            )
                        }.getOrNull()?.let { stats ->
                            linkedMapOf<String, Any?>(
                                "packageName" to app.packageName,
                                "appBytes" to stats.appBytes,
                                "dataBytes" to stats.dataBytes,
                                "cacheBytes" to stats.cacheBytes
                            )
                        }
                    }.sortedByDescending { item ->
                        (item["appBytes"] as? Long ?: 0L) + (item["dataBytes"] as? Long ?: 0L)
                    }.take(limit)
                    val payload = linkedMapOf<String, Any?>(
                        "scope" to "storage",
                        "count" to items.size,
                        "items" to items
                    )
                    val payloadJson = helper.encodeLocalizedPayload(payload)
                    ToolExecutionResult.ContextResult(
                        toolName, helper.localized("已查询最占存储的应用。"), payloadJson, payloadJson, true
                    )
                } catch (e: SecurityException) {
                    helper.permissionRequiredResult(
                        callback,
                        listOf("使用情况访问权限（设置 → 应用 → 特殊应用权限 → 使用情况访问）")
                    )
                } catch (e: Exception) {
                    helper.errorResult(toolName, e.message, "查询存储占用失败")
                }
            }
            else -> helper.errorResult(toolName, null, "未知查询维度：$scope")
        }
    }

    private suspend fun executeDeviceLaunchApp(
        args: JsonObject,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "device_launch_app"
        val packageName = args["packageName"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return helper.errorResult(toolName, null, "缺少 packageName 参数")
        val pm = helper.context.packageManager
        val intent = pm.getLaunchIntentForPackage(packageName)
            ?: return helper.errorResult(toolName, null, "应用没有可启动入口：$packageName")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        helper.context.startActivity(intent)
        val payload = linkedMapOf<String, Any?>("packageName" to packageName)
        val payloadJson = helper.encodeLocalizedPayload(payload)
        return ToolExecutionResult.ContextResult(
            toolName, helper.localized("已启动应用 $packageName。"), payloadJson, payloadJson, true
        )
    }

    private suspend fun executeDeviceClipboardSet(
        args: JsonObject,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "device_clipboard_set"
        val text = args["text"]?.jsonPrimitive?.contentOrNull
            ?: return helper.errorResult(toolName, null, "缺少 text 参数")
        val clipboard = helper.context.getSystemService(
            Context.CLIPBOARD_SERVICE
        ) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("omnibot-eta", text))
        val payload = linkedMapOf<String, Any?>(
            "charCount" to text.length
        )
        val payloadJson = helper.encodeLocalizedPayload(payload)
        return ToolExecutionResult.ContextResult(
            toolName, helper.localized("已将 ${text.length} 字符写入剪贴板。"), payloadJson, payloadJson, true
        )
    }

    private suspend fun executeDeviceNotificationsRead(
        args: JsonObject,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "device_notifications_read"
        val enabledListeners = Settings.Secure.getString(
            helper.context.contentResolver,
            "enabled_notification_listeners"
        ).orEmpty()
        val granted = enabledListeners
            .split(':')
            .any { listener ->
                listener.substringBefore('/') == helper.context.packageName
            }
        if (!granted) {
            return helper.permissionRequiredResult(
                callback,
                listOf("通知使用权（设置 → 通知使用权 → 开启 OmniBot-Eta）")
            )
        }
        // TODO(eta): 接入 NotificationListenerService 后在此返回最近通知（对齐 Eta 的通知历史：本机 7 天/1000 条）
        return helper.errorResult(
            toolName,
            null,
            "通知监听服务尚未接入，请等待后续版本（权限已就绪）"
        )
    }

    private suspend fun executeDeviceSetWifi(
        args: JsonObject,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "device_set_wifi"
        val enabled = args["enabled"]?.jsonPrimitive?.booleanOrNull
            ?: return helper.errorResult(toolName, null, "缺少 enabled 参数")
        val wifi = helper.context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ok = runCatching { wifi.setWifiEnabled(enabled) }.getOrDefault(false)
        if (!ok) {
            // Android 13+ 禁止第三方直接开关 Wi-Fi
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                "Android 13+ 限制第三方开关 Wi-Fi：请改用 root_exec 执行 " +
                    "'cmd wifi set-wifi-enabled ${if (enabled) "true" else "false"}'，" +
                    "或 android_privileged_action 的 device_control.set_wifi_enabled"
            } else {
                "Wi-Fi 开关失败，请检查权限"
            }
            return helper.errorResult(toolName, null, hint)
        }
        val payload = linkedMapOf<String, Any?>("enabled" to enabled)
        val payloadJson = helper.encodeLocalizedPayload(payload)
        return ToolExecutionResult.ContextResult(
            toolName, helper.localized("Wi-Fi 已${if (enabled) "开启" else "关闭"}。"), payloadJson, payloadJson, true
        )
    }

    private suspend fun executeDeviceSetBluetooth(
        args: JsonObject,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "device_set_bluetooth"
        val enabled = args["enabled"]?.jsonPrimitive?.booleanOrNull
            ?: return helper.errorResult(toolName, null, "缺少 enabled 参数")
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return helper.errorResult(toolName, null, "设备不支持蓝牙")
        val ok = runCatching {
            if (enabled) adapter.enable() else adapter.disable()
        }.getOrDefault(false)
        if (!ok) {
            return helper.errorResult(
                toolName,
                null,
                "蓝牙开关失败（Android 13+ 限制）：请改用 root_exec 执行 " +
                    "'cmd bluetooth_manager ${if (enabled) "enable" else "disable"}'"
            )
        }
        val payload = linkedMapOf<String, Any?>("enabled" to enabled)
        val payloadJson = helper.encodeLocalizedPayload(payload)
        return ToolExecutionResult.ContextResult(
            toolName, helper.localized("蓝牙已${if (enabled) "开启" else "关闭"}。"), payloadJson, payloadJson, true
        )
    }
}
