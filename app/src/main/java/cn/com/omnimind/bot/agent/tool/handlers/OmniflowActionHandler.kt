package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.baselib.runlog.OobCanonicalActionSchema
import cn.com.omnimind.baselib.runlog.OobPrimitiveActionLedger
import cn.com.omnimind.baselib.runlog.OobPrimitiveActionRecord
import cn.com.omnimind.baselib.runlog.OobPrimitiveActionRiskPolicy
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.runlog.OobActionCodec
import cn.com.omnimind.bot.runlog.OmniflowActionBackend
import cn.com.omnimind.bot.runlog.OmniflowActionRuntime
import cn.com.omnimind.bot.runlog.RunLogPagePackageInference
import cn.com.omnimind.omniintelligence.models.ScrollDirection
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/**
 * ToolHandler implementation for all omniflow primitive actions
 * (click, swipe, input_text, open_app, press_key, finished).
 *
 * Replaces the 300-line dispatch when-block in UIStepExecutor.
 * Registered in AgentToolRegistry at startup.
 */
class OmniflowActionHandler(
    private val backendProvider: () -> OmniflowActionBackend = { OmniflowActionRuntime.backend },
    private val primitiveSource: String = SOURCE_AGENT_ACTION,
) : ToolHandler {
    private val backend get() = backendProvider()

    override val toolNames: Set<String> = OobCanonicalActionSchema.replayableToolNames

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
            OmniLog.e(TAG, "OmniflowActionHandler failed: $action — ${e.message}", e)
            ToolExecutionResult.Error(action, e.message ?: "action failed")
        }
    }

    // -----------------------------------------------------------------------
    // Dispatch — exhaustive over OobCanonicalActionSchema.replayableToolNames
    // -----------------------------------------------------------------------

    suspend fun dispatch(
        action: String,
        args: Map<String, Any?>,
        source: String = primitiveSource,
        diagnostics: Map<String, Any?> = emptyMap(),
    ) {
        val canonicalAction = OobActionCodec.canonicalActionForName(action)
            ?: OobActionCodec.normalizeName(action)
        val startedAtMs = System.currentTimeMillis()
        val snapshot = primitiveSnapshot()
        val risk = OobPrimitiveActionRiskPolicy.evaluate(
            tool = canonicalAction,
            args = args,
            pageXml = snapshot.xml,
            packageName = snapshot.packageName,
            activityName = snapshot.activityName,
        )
        if (!risk.allowed) {
            val now = System.currentTimeMillis()
            recordPrimitiveAction(
                source = source,
                action = canonicalAction,
                args = args,
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
            dispatchUnchecked(canonicalAction, args)
            success = true
        } catch (error: Exception) {
            errorCode = "OOB_PRIMITIVE_ACTION_EXCEPTION"
            errorMessage = error.message.orEmpty()
            throw error
        } finally {
            recordPrimitiveAction(
                source = source,
                action = canonicalAction,
                args = args,
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
            OobActionCodec.ACTION_CLICK -> {
                backend.click(
                    x = float("x"),
                    y = float("y"),
                    targetDescription = str("target_description"),
                    nodeResourceId = str("node_resource_id", "resource_id", "resource-id"),
                )
            }
            OobActionCodec.ACTION_LONG_PRESS -> {
                backend.longPress(
                    x = float("x"),
                    y = float("y"),
                    durationMs = long("duration_ms", 800L),
                    targetDescription = str("target_description"),
                    nodeResourceId = str("node_resource_id", "resource_id", "resource-id"),
                )
            }
            OobActionCodec.ACTION_INPUT_TEXT -> {
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
            OobActionCodec.ACTION_SWIPE -> {
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
            OobActionCodec.ACTION_OPEN_APP -> {
                backend.launchApplication(packageName = str("package_name", "packageName", "package"))
            }
            OobActionCodec.ACTION_PRESS_KEY -> {
                backend.pressHotKey(pressKey(str("key")))
            }
            OobActionCodec.ACTION_WAIT -> {
                delay(waitMs(args))
            }
            OobActionCodec.ACTION_FINISHED -> {
                // No-op: execution loop handles termination
            }
            else -> {
                // Fallback for any new actions added to replayableToolNames
                OmniLog.w(TAG, "OmniflowActionHandler: no explicit handler for action=$action")
            }
        }
    }

    private companion object {
        const val SOURCE_AGENT_ACTION = "agent_primitive_action"
        const val TAG = "OmniflowActionHandler"

        fun pressKey(raw: String): String =
            when (raw.trim().lowercase()) {
                "back" -> "BACK"
                "home" -> "HOME"
                "enter" -> "ENTER"
                else -> throw IllegalArgumentException("press_key requires key=back/home/enter")
            }

        fun waitMs(args: Map<String, Any?>): Long {
            val explicitMs = OobActionCodec.longArg(
                args["time_ms"],
                args["duration_ms"],
                defaultValue = -1L,
            )
            if (explicitMs >= 0L) return explicitMs.coerceIn(0L, 10_000L)
            val seconds = args["time_s"]?.toString()?.trim()?.toDoubleOrNull() ?: 1.0
            return (seconds.coerceAtLeast(0.0) * 1000.0).toLong().coerceIn(0L, 10_000L)
        }
    }

    private data class PrimitiveSnapshot(
        val xml: String,
        val rawPackageName: String,
        val activityName: String,
    ) {
        val packageName: String =
            RunLogPagePackageInference.effectivePackage(rawPackageName, xml, activityName)
    }

    private fun primitiveSnapshot(): PrimitiveSnapshot =
        PrimitiveSnapshot(
            xml = runCatching { backend.currentXml()?.trim().orEmpty() }.getOrDefault(""),
            rawPackageName = runCatching { backend.currentPackageName()?.trim().orEmpty() }.getOrDefault(""),
            activityName = runCatching { backend.currentActivityName()?.trim().orEmpty() }.getOrDefault(""),
        )

    private fun recordPrimitiveAction(
        source: String,
        action: String,
        args: Map<String, Any?>,
        snapshot: PrimitiveSnapshot,
        startedAtMs: Long,
        success: Boolean,
        blocked: Boolean,
        errorCode: String,
        errorMessage: String,
        diagnostics: Map<String, Any?>,
        finishedAtMs: Long = System.currentTimeMillis(),
    ) {
        if (!OobPrimitiveActionLedger.shouldRecordForPlanner(action)) return
        OobPrimitiveActionLedger.record(
            OobPrimitiveActionRecord(
                source = source,
                tool = action,
                args = args,
                taskId = OobActionCodec.firstNonBlank(args["task_id"], args["taskId"]),
                runId = OobActionCodec.firstNonBlank(args["run_id"], args["runId"]),
                functionId = OobActionCodec.firstNonBlank(args["function_id"], args["functionId"]),
                stepId = OobActionCodec.firstNonBlank(args["step_id"], args["stepId"]),
                packageName = snapshot.packageName,
                activityName = snapshot.activityName,
                beforeXmlSha256 = OobPrimitiveActionLedger.xmlSha256(snapshot.xml),
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
