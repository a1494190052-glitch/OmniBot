package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.root.RootShell
import cn.com.omnimind.bot.root.RootShellSessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Root 直达工具执行器（二改：OmniBot-Eta）。
 *
 * 直接使用 su（Magisk / KernelSU / APatch），不依赖 Shizuku：
 *  - root_status：探测 Root 状态与后端
 *  - root_exec：一次性 root 命令（高风险需 confirmed=true）
 *  - root_session_*：持久 root shell 会话
 *  - root_app_control：强制停止 / 冻结 / 恢复应用（核心系统包受保护）
 *  - root_input：模拟点击/滑动/文本/按键（GUI 兜底）
 *  - root_ui_dump：uiautomator 导出界面结构
 */
class RootToolHandler(
    private val helper: SharedHelper
) : ToolHandler {

    override val toolNames: Set<String> = setOf(
        "root_status",
        "root_exec",
        "root_session_start",
        "root_session_exec",
        "root_session_stop",
        "root_app_control",
        "root_input",
        "root_ui_dump"
    )

    private val protectedPackages = listOf(
        "android",
        "com.android.settings",
        "com.android.systemui",
        "com.android.launcher3",
        "com.android.permissioncontroller",
        "com.android.providers.settings"
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
                "root_status" -> executeRootStatus(args)
                "root_exec" -> executeRootExec(args, callback)
                "root_session_start" -> executeRootSessionStart(callback)
                "root_session_exec" -> executeRootSessionExec(args, callback)
                "root_session_stop" -> executeRootSessionStop(args)
                "root_app_control" -> executeRootAppControl(args, callback)
                "root_input" -> executeRootInput(args, callback)
                "root_ui_dump" -> executeRootUiDump(args, callback)
                else -> helper.errorResult(toolCall.function.name, null, "未知 root 工具")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.errorResult(toolCall.function.name, e.message, "root 工具执行失败")
        }
    }

    override suspend fun dispose() {
        RootShellSessionManager.stopAll()
    }

    private fun executeRootStatus(args: JsonObject): ToolExecutionResult {
        val toolName = "root_status"
        val forceRefresh = args["forceRefresh"]?.jsonPrimitive?.booleanOrNull == true
        val status = RootShell.detect(forceRefresh = forceRefresh)
        val payload = linkedMapOf<String, Any?>(
            "available" to status.available,
            "backend" to status.backend?.label,
            "version" to status.version,
            "suPath" to status.suPath,
            "busybox" to status.busybox,
            "detail" to status.detail
        )
        val payloadJson = helper.encodeLocalizedPayload(payload)
        val summary = if (status.available) {
            helper.localized(
                "Root 可用：${status.backend?.label ?: "su"}（${status.version ?: "版本未知"}）"
            )
        } else {
            helper.localized(
                "未检测到 Root。如已安装 KernelSU / Magisk / APatch，请在对应管理器中授权本应用" +
                    "（KernelSU 建议设为「直接授权」），然后重试。"
            )
        }
        return ToolExecutionResult.ContextResult(
            toolName = toolName,
            summaryText = summary,
            previewJson = payloadJson,
            rawResultJson = payloadJson,
            success = status.available
        )
    }

    private suspend fun executeRootExec(
        args: JsonObject,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "root_exec"
        val command = args["command"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return helper.errorResult(toolName, null, "缺少 command 参数")
        if (!helper.parseConfirmedFlag(args["confirmed"]?.jsonPrimitive)) {
            return helper.errorResult(
                toolName,
                null,
                "root_exec 需要用户在界面上确认后传入 confirmed=true（高风险命令）"
            )
        }
        val timeout = (args["timeoutSeconds"]?.jsonPrimitive?.intOrNull ?: 30).coerceIn(1, 300)
        helper.reportToolProgress(callback, toolName, "正在以 root 执行命令")
        val result = RootShell.exec(command, timeoutSeconds = timeout)
        return buildShellContextResult(
            toolName = toolName,
            command = command,
            result = result
        )
    }

    private suspend fun executeRootSessionStart(callback: AgentCallback): ToolExecutionResult {
        val toolName = "root_session_start"
        helper.reportToolProgress(callback, toolName, "正在启动 root 会话")
        val sessionId = RootShellSessionManager.startSession()
            ?: return ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized("无法启动 root 会话（Root 不可用或未授权）"),
                previewJson = "{}",
                rawResultJson = "{}",
                success = false
            )
        val payload = linkedMapOf<String, Any?>("sessionId" to sessionId)
        val payloadJson = helper.encodeLocalizedPayload(payload)
        return ToolExecutionResult.ContextResult(
            toolName = toolName,
            summaryText = helper.localized("root 会话已启动：$sessionId"),
            previewJson = payloadJson,
            rawResultJson = payloadJson,
            success = true
        )
    }

    private suspend fun executeRootSessionExec(
        args: JsonObject,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "root_session_exec"
        val sessionId = args["sessionId"]?.jsonPrimitive?.contentOrNull
            ?: return helper.errorResult(toolName, null, "缺少 sessionId 参数")
        val command = args["command"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return helper.errorResult(toolName, null, "缺少 command 参数")
        val timeout = (args["timeoutSeconds"]?.jsonPrimitive?.intOrNull ?: 60).coerceIn(1, 600)
        helper.reportToolProgress(callback, toolName, "正在 root 会话中执行命令")
        val result = RootShellSessionManager.execInSession(sessionId, command, timeout)
        return buildShellContextResult(
            toolName = toolName,
            command = command,
            result = result
        )
    }

    private fun executeRootSessionStop(args: JsonObject): ToolExecutionResult {
        val toolName = "root_session_stop"
        val sessionId = args["sessionId"]?.jsonPrimitive?.contentOrNull
            ?: return helper.errorResult(toolName, null, "缺少 sessionId 参数")
        val stopped = RootShellSessionManager.stopSession(sessionId)
        val payload = linkedMapOf<String, Any?>(
            "sessionId" to sessionId,
            "stopped" to stopped
        )
        val payloadJson = helper.encodeLocalizedPayload(payload)
        return ToolExecutionResult.ContextResult(
            toolName = toolName,
            summaryText = helper.localized(
                if (stopped) "root 会话 $sessionId 已结束。" else "会话 $sessionId 不存在或已结束。"
            ),
            previewJson = payloadJson,
            rawResultJson = payloadJson,
            success = stopped
        )
    }

    private suspend fun executeRootAppControl(
        args: JsonObject,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "root_app_control"
        val packageName = args["packageName"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return helper.errorResult(toolName, null, "缺少 packageName 参数")
        val action = args["action"]?.jsonPrimitive?.contentOrNull
            ?: return helper.errorResult(toolName, null, "缺少 action 参数")
        if (!helper.parseConfirmedFlag(args["confirmed"]?.jsonPrimitive)) {
            return helper.errorResult(
                toolName,
                null,
                "root_app_control 需要用户在界面上确认后传入 confirmed=true"
            )
        }
        if (isProtectedPackage(packageName)) {
            return helper.errorResult(toolName, null, "核心系统包受保护，禁止操作：$packageName")
        }
        val command = when (action) {
            "force_stop" -> "am force-stop $packageName"
            "disable_user" -> "pm disable-user --user 0 $packageName"
            "enable" -> "pm enable $packageName"
            else -> return helper.errorResult(toolName, null, "未知操作：$action")
        }
        helper.reportToolProgress(callback, toolName, "正在执行 $action：$packageName")
        val result = RootShell.exec(command, timeoutSeconds = 30)
        return buildShellContextResult(toolName, command, result)
    }

    private suspend fun executeRootInput(
        args: JsonObject,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "root_input"
        val type = args["type"]?.jsonPrimitive?.contentOrNull
            ?: return helper.errorResult(toolName, null, "缺少 type 参数")
        val command = when (type) {
            "tap" -> {
                val x = args["x"]?.jsonPrimitive?.intOrNull
                    ?: return helper.errorResult(toolName, null, "tap 需要 x 坐标")
                val y = args["y"]?.jsonPrimitive?.intOrNull
                    ?: return helper.errorResult(toolName, null, "tap 需要 y 坐标")
                "input tap $x $y"
            }
            "swipe" -> {
                val x1 = args["x"]?.jsonPrimitive?.intOrNull
                    ?: return helper.errorResult(toolName, null, "swipe 需要起点 x")
                val y1 = args["y"]?.jsonPrimitive?.intOrNull
                    ?: return helper.errorResult(toolName, null, "swipe 需要起点 y")
                val x2 = args["x2"]?.jsonPrimitive?.intOrNull
                    ?: return helper.errorResult(toolName, null, "swipe 需要终点 x2")
                val y2 = args["y2"]?.jsonPrimitive?.intOrNull
                    ?: return helper.errorResult(toolName, null, "swipe 需要终点 y2")
                val duration = args["duration"]?.jsonPrimitive?.intOrNull ?: 300
                "input swipe $x1 $y1 $x2 $y2 $duration"
            }
            "text" -> {
                val text = args["text"]?.jsonPrimitive?.contentOrNull
                    ?: return helper.errorResult(toolName, null, "text 需要 text 内容")
                "input text '${escapeShellSingleQuoted(text)}'"
            }
            "keyevent" -> {
                val keyCode = args["keyCode"]?.jsonPrimitive?.intOrNull
                    ?: return helper.errorResult(toolName, null, "keyevent 需要 keyCode")
                "input keyevent $keyCode"
            }
            else -> return helper.errorResult(toolName, null, "未知输入类型：$type")
        }
        helper.reportToolProgress(callback, toolName, "正在模拟输入：$type")
        val result = RootShell.exec(command, timeoutSeconds = 15)
        return buildShellContextResult(toolName, command, result)
    }

    private suspend fun executeRootUiDump(
        args: JsonObject,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "root_ui_dump"
        helper.reportToolProgress(callback, toolName, "正在导出界面结构")
        val command = "uiautomator dump /data/local/tmp/eta_ui.xml && cat /data/local/tmp/eta_ui.xml"
        val result = RootShell.exec(command, timeoutSeconds = 30)
        if (!result.success) {
            return helper.errorResult(
                toolName,
                null,
                "uiautomator 导出失败（${result.stderr.trim().ifEmpty { "未知原因" }}）：" +
                    "可尝试使用无障碍服务方案或确认 root 未被限制"
            )
        }
        return buildShellContextResult(toolName, command, result)
    }

    private fun buildShellContextResult(
        toolName: String,
        command: String,
        result: RootShell.ExecResult
    ): ToolExecutionResult {
        val stdout = helper.truncateText(result.stdout, 20_000)
        val stderr = helper.truncateText(result.stderr, 4_000)
        val payload = linkedMapOf<String, Any?>(
            "command" to command,
            "exitCode" to result.exitCode,
            "timedOut" to result.timedOut,
            "stdout" to stdout,
            "stderr" to stderr
        )
        val payloadJson = helper.encodeLocalizedPayload(payload)
        val summary = if (result.success) {
            helper.localized("命令执行成功（exit=${result.exitCode}）")
        } else if (result.timedOut) {
            helper.localized("命令执行超时")
        } else {
            helper.localized("命令执行失败（exit=${result.exitCode}）")
        }
        return ToolExecutionResult.ContextResult(
            toolName = toolName,
            summaryText = summary,
            previewJson = payloadJson,
            rawResultJson = payloadJson,
            success = result.success
        )
    }

    private fun isProtectedPackage(packageName: String): Boolean {
        if (packageName == "android") return true
        if (packageName.startsWith("com.android.")) return true
        if (packageName in protectedPackages) return true
        // 保护 OmniBot 自身
        return packageName.startsWith("cn.com.omnimind")
    }

    private fun escapeShellSingleQuoted(text: String): String =
        text.replace("'", "'\\''")
}
