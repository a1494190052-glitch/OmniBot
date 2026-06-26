package cn.com.omnimind.bot.agent.tool.handlers
import cn.com.omnimind.bot.runlog.firstNonBlank
import cn.com.omnimind.bot.runlog.longArg
import cn.com.omnimind.bot.runlog.resolveActionName

import cn.com.omnimind.baselib.runlog.OobActionSchema
import cn.com.omnimind.baselib.runlog.OobLocalActionLedger
import cn.com.omnimind.baselib.runlog.OobLocalActionRecord
import cn.com.omnimind.baselib.runlog.OobLocalActionRiskPolicy
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.runlog.OmniflowActionBackend
import cn.com.omnimind.bot.runlog.OmniflowActionRuntime
import cn.com.omnimind.bot.runlog.RunLogPagePackageInference
import cn.com.omnimind.omniintelligence.models.ScrollDirection
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/**
 * Unified act layer for both VLM online execution and Function replay.
 *
 * VLM:      observe → plan(action)            → VlmActExecutor.dispatch()
 * Function: observe → replay/transfer(action) → VlmActExecutor.dispatch()
 */
class VlmActExecutor(
    private val backendProvider: () -> OmniflowActionBackend = { OmniflowActionRuntime.backend },
    private val actionSource: String = SOURCE_AGENT_ACTION,
    private val openAppReadySettleDelayMs: Long = OPEN_APP_READY_SETTLE_DELAY_MS,
    private val openAppReadyTimeoutMs: Long = OPEN_APP_READY_TIMEOUT_MS,
    private val openAppReadyPollMs: Long = OPEN_APP_READY_POLL_MS,
) : ToolHandler {
    private val backend get() = backendProvider()

    override val toolNames: Set<String> = OobActionSchema.replayableToolNames

    override fun canHandle(toolName: String): Boolean = toolName in toolNames

    override suspend fun execute(
        toolCall: AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle,
    ): ToolExecutionResult {
        val action = toolCall.function.name
        val argsMap: Map<String, Any?> = runCatching {
            cn.com.omnimind.bot.agent.AgentToolJson.jsonObjectToMap(args)
        }.getOrDefault(emptyMap())

        return runCatching {
            dispatch(action, argsMap)
            ToolExecutionResult.ContextResult(
                toolName = action,
                summaryText = action,
                previewJson = "{}",
                rawResultJson = "{}",
                success = true,
            )
        }.getOrElse { e ->
            OmniLog.e(TAG, "VlmActExecutor failed: $action — ${e.message}", e)
            ToolExecutionResult.Error(action, e.message ?: "action failed")
        }
    }

    // -----------------------------------------------------------------------
    // Dispatch — exhaustive over OobActionSchema.replayableToolNames
    // -----------------------------------------------------------------------

    suspend fun dispatch(
        action: String,
        args: Map<String, Any?>,
        source: String = actionSource,
        diagnostics: Map<String, Any?> = emptyMap(),
    ) {
        val canonicalAction = resolveActionName(action)
            ?: OobActionSchema.normalizeToolName(action)
        val canonicalArgs = canonicalActionArgs(canonicalAction, args)
        val startedAtMs = System.currentTimeMillis()
        val snapshot = actionSnapshot()
        val risk = OobLocalActionRiskPolicy.evaluate(
            tool = canonicalAction,
            args = canonicalArgs,
            pageXml = snapshot.xml,
            packageName = snapshot.packageName,
            activityName = snapshot.activityName,
        )
        if (!risk.allowed) {
            val now = System.currentTimeMillis()
            recordLocalAction(
                source = source,
                action = canonicalAction,
                args = canonicalArgs,
                snapshot = snapshot,
                startedAtMs = startedAtMs,
                finishedAtMs = now,
                success = false,
                blocked = true,
                errorCode = risk.errorCode,
                errorMessage = risk.reason,
                diagnostics = diagnostics + risk.diagnostics(),
            )
            throw IllegalStateException("${risk.errorCode}: ${risk.reason}")
        }

        var success = false
        var errorCode = ""
        var errorMessage = ""
        try {
            dispatchUnchecked(canonicalAction, canonicalArgs)
            success = true
        } catch (error: Exception) {
            errorCode = (error as? LocalActionExecutionException)?.errorCode
                ?: "OOB_LOCAL_ACTION_EXCEPTION"
            errorMessage = error.message.orEmpty()
            throw error
        } finally {
            recordLocalAction(
                source = source,
                action = canonicalAction,
                args = canonicalArgs,
                snapshot = snapshot,
                startedAtMs = startedAtMs,
                success = success,
                blocked = false,
                errorCode = errorCode,
                errorMessage = errorMessage,
                diagnostics = diagnostics,
            )
        }
    }

    private suspend fun dispatchUnchecked(action: String, args: Map<String, Any?>) {
        fun float(key: String, default: Float = 0f) =
            args[key]?.toString()?.toFloatOrNull() ?: default
        fun long(key: String, default: Long = 0L) =
            args[key]?.toString()?.toLongOrNull() ?: default
        fun str(vararg keys: String): String =
            keys.firstNotNullOfOrNull { key -> args[key]?.toString()?.takeIf { it.isNotBlank() } }
                .orEmpty()

        when (action) {
            OobActionSchema.TOOL_CLICK -> {
                backend.click(
                    x = float("x"),
                    y = float("y"),
                    targetDescription = str("target_description"),
                    nodeResourceId = str("node_resource_id", "resource_id", "resource-id"),
                )
            }
            OobActionSchema.TOOL_LONG_PRESS -> {
                backend.longPress(
                    x = float("x"),
                    y = float("y"),
                    durationMs = long("duration_ms", 800L),
                    targetDescription = str("target_description"),
                    nodeResourceId = str("node_resource_id", "resource_id", "resource-id"),
                )
            }
            OobActionSchema.TOOL_INPUT_TEXT -> {
                val text = str("text")
                val targetDescription = str("target_description")
                val x = args["x"]?.toString()?.toFloatOrNull()
                val y = args["y"]?.toString()?.toFloatOrNull()
                val nodeResourceId = str("node_resource_id")
                if (str("input_mode").equals("typed", ignoreCase = true)) {
                    backend.inputTextByTyping(
                        text = text,
                        targetDescription = targetDescription,
                        x = x,
                        y = y,
                        nodeResourceId = nodeResourceId,
                    )
                } else {
                    backend.inputText(
                        text = text,
                        targetDescription = targetDescription,
                        x = x,
                        y = y,
                        nodeResourceId = nodeResourceId,
                    )
                }
            }
            OobActionSchema.TOOL_SWIPE -> {
                val direction = ScrollDirection.entries.firstOrNull {
                    it.name.equals(str("direction"), ignoreCase = true)
                } ?: ScrollDirection.DOWN
                val x1 = args["x1"]?.toString()?.toFloatOrNull()
                val y1 = args["y1"]?.toString()?.toFloatOrNull()
                val x2 = args["x2"]?.toString()?.toFloatOrNull()
                val y2 = args["y2"]?.toString()?.toFloatOrNull()
                if (x1 != null && y1 != null && x2 != null && y2 != null) {
                    backend.swipe(
                        startX = x1,
                        startY = y1,
                        endX = x2,
                        endY = y2,
                        durationMs = long("duration_ms", 300L),
                        targetDescription = str("target_description"),
                    )
                } else {
                    backend.scrollWithContext(
                        x = float("x"),
                        y = float("y"),
                        direction = direction,
                        distance = float("distance", 300f),
                        durationMs = long("duration_ms", 300L),
                        targetDescription = str("target_description"),
                    )
                }
            }
            OobActionSchema.TOOL_OPEN_APP -> {
                val packageName = str("package_name", "packageName", "package")
                if (packageName.isBlank()) {
                    throw IllegalArgumentException("open_app requires package_name")
                }
                backend.launchApplication(packageName = packageName)
                waitForOpenAppReady(packageName)
            }
            OobActionSchema.TOOL_PRESS_KEY -> {
                backend.pressHotKey(pressKey(str("key")))
            }
            OobActionSchema.TOOL_WAIT -> {
                delay(waitMs(args))
            }
            OobActionSchema.TOOL_FINISHED -> {
                // No-op: execution loop handles termination
            }
            else -> {
                OmniLog.w(TAG, "VlmActExecutor: no explicit handler for action=$action")
            }
        }
    }

    private fun canonicalActionArgs(
        action: String,
        args: Map<String, Any?>,
    ): Map<String, Any?> {
        if (action != OobActionSchema.TOOL_OPEN_APP) return args
        val packageName = firstNonBlank(args["package_name"], args["packageName"], args["package"])
        if (packageName.isBlank()) return args
        return linkedMapOf<String, Any?>().apply {
            putAll(args)
            put("package_name", packageName)
        }
    }

    private suspend fun waitForOpenAppReady(expectedPackage: String) {
        val normalizedExpected = expectedPackage.trim()
        if (normalizedExpected.isBlank()) return
        if (openAppReadySettleDelayMs > 0L) {
            delay(openAppReadySettleDelayMs)
        }

        val timeoutMs = openAppReadyTimeoutMs.coerceAtLeast(0L)
        val pollMs = openAppReadyPollMs.coerceAtLeast(1L)
        val deadlineMs = System.currentTimeMillis() + timeoutMs
        val maxAttempts = ((timeoutMs / pollMs) + 1L).coerceAtLeast(1L)
        var attempts = 0L
        var lastSnapshot: ActionSnapshot? = null
        while (true) {
            attempts += 1L
            val snapshot = actionSnapshot()
            lastSnapshot = snapshot
            if (openAppPackageMatches(snapshot, normalizedExpected)) {
                return
            }
            if (attempts >= maxAttempts || System.currentTimeMillis() >= deadlineMs) {
                break
            }
            val remainingMs = (deadlineMs - System.currentTimeMillis()).coerceAtLeast(0L)
            delay(minOf(pollMs, remainingMs))
        }

        val currentPackage = lastSnapshot?.packageName.orEmpty()
        val rawPackage = lastSnapshot?.rawPackageName.orEmpty()
        val activityName = lastSnapshot?.activityName.orEmpty()
        throw LocalActionExecutionException(
            errorCode = "OPEN_APP_NOT_READY",
            message = "OPEN_APP_NOT_READY: open_app did not reach target package: " +
                "$normalizedExpected current=$currentPackage raw=$rawPackage activity=$activityName"
        )
    }

    private fun openAppPackageMatches(
        snapshot: ActionSnapshot,
        expectedPackage: String,
    ): Boolean {
        val rawPackage = snapshot.rawPackageName.trim()
        val effectivePackage = snapshot.packageName.trim()
        val activityName = snapshot.activityName.trim()
        val activityPackage = RunLogPagePackageInference.packageFromActivity(activityName)
        return rawPackage == expectedPackage ||
            effectivePackage == expectedPackage ||
            activityPackage == expectedPackage ||
            activityName == expectedPackage ||
            activityName.startsWith("$expectedPackage/") ||
            activityName.startsWith("$expectedPackage.")
    }

    private class LocalActionExecutionException(
        val errorCode: String,
        message: String,
    ) : IllegalStateException(message)

    private companion object {
        const val SOURCE_AGENT_ACTION = "agent_local_action"
        const val TAG = "VlmActExecutor"
        const val OPEN_APP_READY_SETTLE_DELAY_MS = 800L
        const val OPEN_APP_READY_TIMEOUT_MS = 5000L
        const val OPEN_APP_READY_POLL_MS = 500L

        fun pressKey(raw: String): String =
            when (raw.trim().lowercase()) {
                "back" -> "BACK"
                "home" -> "HOME"
                "enter" -> "ENTER"
                else -> throw IllegalArgumentException("press_key requires key=back/home/enter")
            }

        fun waitMs(args: Map<String, Any?>): Long {
            val explicitMs = longArg(
                args["time_ms"],
                args["duration_ms"],
                defaultValue = -1L,
            )
            if (explicitMs >= 0L) return explicitMs.coerceIn(0L, 10_000L)
            val seconds = args["time_s"]?.toString()?.trim()?.toDoubleOrNull() ?: 1.0
            return (seconds.coerceAtLeast(0.0) * 1000.0).toLong().coerceIn(0L, 10_000L)
        }
    }

    private data class ActionSnapshot(
        val xml: String,
        val rawPackageName: String,
        val activityName: String,
    ) {
        val packageName: String =
            RunLogPagePackageInference.effectivePackage(rawPackageName, xml, activityName)
    }

    private fun actionSnapshot(): ActionSnapshot =
        ActionSnapshot(
            xml = runCatching { backend.currentXml()?.trim().orEmpty() }.getOrDefault(""),
            rawPackageName = runCatching { backend.currentPackageName()?.trim().orEmpty() }.getOrDefault(""),
            activityName = runCatching { backend.currentActivityName()?.trim().orEmpty() }.getOrDefault(""),
        )

    private fun recordLocalAction(
        source: String,
        action: String,
        args: Map<String, Any?>,
        snapshot: ActionSnapshot,
        startedAtMs: Long,
        success: Boolean,
        blocked: Boolean,
        errorCode: String,
        errorMessage: String,
        diagnostics: Map<String, Any?>,
        finishedAtMs: Long = System.currentTimeMillis(),
    ) {
        if (!OobLocalActionLedger.shouldRecordForPlanner(action)) return
        OobLocalActionLedger.record(
            OobLocalActionRecord(
                source = source,
                tool = action,
                args = args,
                taskId = firstNonBlank(args["task_id"], args["taskId"]),
                runId = firstNonBlank(args["run_id"], args["runId"]),
                functionId = firstNonBlank(args["function_id"], args["functionId"]),
                stepId = firstNonBlank(args["step_id"], args["stepId"]),
                packageName = snapshot.packageName,
                activityName = snapshot.activityName,
                beforeXmlSha256 = OobLocalActionLedger.xmlSha256(snapshot.xml),
                beforeXmlChars = snapshot.xml.length,
                startedAtMs = startedAtMs,
                finishedAtMs = finishedAtMs,
                success = success,
                blocked = blocked,
                errorCode = errorCode,
                errorMessage = errorMessage,
                diagnostics = diagnostics,
            )
        )
    }
}
