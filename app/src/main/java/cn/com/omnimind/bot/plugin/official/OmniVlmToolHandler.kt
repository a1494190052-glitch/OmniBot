package cn.com.omnimind.bot.plugin.official

import android.content.Context
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.HttpAgentLlmClient
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.agent.tool.handlers.SharedHelper
import cn.com.omnimind.bot.agent.tool.handlers.ToolHandler
import cn.com.omnimind.bot.omniflow.OmniVlmPlugin
import cn.com.omnimind.bot.omniflow.asOmniFlowModelClient
import cn.com.omnimind.bot.runlog.firstNonBlank
import cn.com.omnimind.bot.runlog.mapArg
import cn.com.omnimind.bot.util.AndroidAutomationPermissionGate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class OmniVlmToolHandler(context: Context) : ToolHandler {
    private val helper = SharedHelper(
        context = context.applicationContext,
        json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
    )

    override val toolNames: Set<String> = setOf(OmniVlmLiteProvider.TOOL_NAME)

    override suspend fun execute(
        toolCall: AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle,
    ): ToolExecutionResult {
        helper.ensureRunActive()
        val goal = args["goal"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return ToolExecutionResult.Error(
                OmniVlmLiteProvider.TOOL_NAME,
                helper.localized("缺少 goal"),
            )
        val permission = AndroidAutomationPermissionGate.check(helper.context)
        if (!permission.granted) {
            return helper.permissionRequiredResult(callback, permission.displayNames)
        }

        val runId = "gui-${UUID.randomUUID()}"
        toolHandle.bindStopAction {
            OmniVlmPlugin.stop(runId)
            Unit
        }
        return try {
            val execution = OmniVlmPlugin.execute(
                context = helper.context,
                request = OmniVlmPlugin.Request(
                    goal = goal,
                    runId = runId,
                    stepSkillGuidance = env.resolvedSkills
                        .map { it.promptSummary(1_200) }
                        .filter(String::isNotBlank)
                        .distinct()
                        .joinToString("\n\n"),
                ),
                modelClient = HttpAgentLlmClient(CoroutineScope(currentCoroutineContext()))
                    .asOmniFlowModelClient(),
                hooks = OmniVlmPlugin.Hooks(
                    beforeOperation = {
                        helper.ensureRunActive()
                        toolHandle.throwIfStopRequested()
                    },
                    stopRequested = toolHandle::isManualStopRequested,
                    onProgress = { progress, extras ->
                        helper.reportToolProgress(
                            callback = callback,
                            toolName = OmniVlmLiteProvider.TOOL_NAME,
                            progress = progress,
                            extras = extras + ("run_id" to runId),
                            toolHandle = toolHandle,
                        )
                    },
                ),
            )
            val result = execution.payload
            val finalStateId = firstNonBlank(
                mapArg(result["final_state"])["state_id"],
                execution.finalStateId,
            ).takeIf(String::isNotBlank)
            val doneReason = firstNonBlank(result["done_reason"]).ifBlank {
                if (result["success"] == true) "finished" else "error"
            }
            val content = firstNonBlank(result["finished_content"])
            if (doneReason == "waiting_input") {
                val question = content.ifBlank { "请提供继续执行所需的信息。" }
                callback.onClarifyRequired(question, null)
                return ToolExecutionResult.Clarify(question, null)
            }
            if (result["success"] != true) {
                val message = firstNonBlank(result["error_message"], result["error_code"])
                    .ifBlank { "gui_task_failed" }
                return ToolExecutionResult.Error(
                    OmniVlmLiteProvider.TOOL_NAME,
                    helper.localized(message),
                )
            }
            val completed = content.ifBlank { "视觉任务已完成" }
            val payload = linkedMapOf<String, Any?>(
                "run_id" to runId,
                "success" to true,
                "done_reason" to doneReason,
                "content" to completed,
                "final_state_id" to finalStateId,
            ).filterValues { it != null }
            val encoded = helper.encodeLocalizedPayload(payload)
            ToolExecutionResult.ContextResult(
                toolName = OmniVlmLiteProvider.TOOL_NAME,
                summaryText = helper.localized(completed),
                previewJson = encoded,
                rawResultJson = encoded,
                success = true,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ToolExecutionResult.Error(
                OmniVlmLiteProvider.TOOL_NAME,
                helper.localized(error.message.orEmpty().ifBlank { error.javaClass.simpleName }),
            )
        }
    }
}
