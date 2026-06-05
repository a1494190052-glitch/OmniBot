package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.baselib.runlog.OobCanonicalActionSchema
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.runlog.OobActionCodec
import cn.com.omnimind.bot.runlog.OmniflowActionBackend
import cn.com.omnimind.bot.runlog.OmniflowActionRuntime
import cn.com.omnimind.omniintelligence.models.ScrollDirection
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.util.OmniLog
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

    suspend fun dispatch(action: String, args: Map<String, Any?>) {
        fun float(key: String, default: Float = 0f) =
            args[key]?.toString()?.toFloatOrNull() ?: default
        fun long(key: String, default: Long = 0L) =
            args[key]?.toString()?.toLongOrNull() ?: default
        fun str(key: String) = args[key]?.toString().orEmpty()

        when (action) {
            OobActionCodec.ACTION_CLICK -> {
                backend.click(x = float("x"), y = float("y"))
            }
            OobActionCodec.ACTION_LONG_PRESS -> {
                backend.longPress(
                    x = float("x"),
                    y = float("y"),
                    durationMs = long("duration_ms", 800L),
                )
            }
            OobActionCodec.ACTION_INPUT_TEXT -> {
                backend.inputText(
                    text = str("text"),
                    targetDescription = str("target_description"),
                    x = args["x"]?.toString()?.toFloatOrNull(),
                    y = args["y"]?.toString()?.toFloatOrNull(),
                    nodeResourceId = str("node_resource_id"),
                )
            }
            OobActionCodec.ACTION_SWIPE -> {
                val direction = ScrollDirection.entries.firstOrNull {
                    it.name.equals(str("direction"), ignoreCase = true)
                } ?: ScrollDirection.DOWN
                backend.scrollWithContext(
                    x = float("x"),
                    y = float("y"),
                    direction = direction,
                    distance = float("distance", 300f),
                    durationMs = long("duration_ms", 300L),
                    targetDescription = str("target_description"),
                )
            }
            OobActionCodec.ACTION_OPEN_APP -> {
                backend.launchApplication(packageName = str("package_name"))
            }
            OobActionCodec.ACTION_PRESS_KEY -> {
                backend.pressHotKey(pressKey(str("key")))
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
        const val TAG = "OmniflowActionHandler"

        fun pressKey(raw: String): String =
            when (raw.trim().lowercase()) {
                "back" -> "BACK"
                "home" -> "HOME"
                "enter" -> "ENTER"
                else -> throw IllegalArgumentException("press_key requires key=back/home/enter")
            }
    }
}
