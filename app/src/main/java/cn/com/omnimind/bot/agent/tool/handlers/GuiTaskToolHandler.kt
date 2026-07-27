package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentLlmClient
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolNames
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.HttpAgentLlmClient
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.omniflow.OmniFlow
import cn.com.omnimind.bot.omniflow.asOmniFlowModelClient
import cn.com.omnimind.bot.runlog.firstNonBlank
import cn.com.omnimind.bot.runlog.mapArg
import cn.com.omnimind.bot.util.AndroidAutomationPermissionGate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class GuiTaskToolHandler(
    private val helper: SharedHelper,
    private val llmClientFactory: (CoroutineScope) -> AgentLlmClient = ::HttpAgentLlmClient,
) : ToolHandler {
    override val toolNames: Set<String> = setOf(AgentToolNames.VLM_TASK)

    override suspend fun execute(
        toolCall: AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle,
    ): ToolExecutionResult {
        helper.ensureRunActive()
        val goal = args.string("goal") ?: return ToolExecutionResult.Error(
            AgentToolNames.VLM_TASK,
            helper.localized("缺少 goal"),
        )
        val permission = AndroidAutomationPermissionGate.check(helper.context)
        if (!permission.granted) {
            return helper.permissionRequiredResult(callback, permission.displayNames)
        }
        modelCapabilityViolation()?.let { violation ->
            return ToolExecutionResult.Error(AgentToolNames.VLM_TASK, violation)
        }
        val runId = "gui-${UUID.randomUUID()}"
        val startedAtMs = System.currentTimeMillis()
        var doneReason = "error"
        var errorMessage: String? = null
        var finalStateId: String? = null
        var success = false
        toolHandle.bindStopAction { OmniFlow.stop(runId) }
        try {
            val runtimeOptions = env.runtimeOptions[AgentToolNames.VLM_TASK]
                .let { it as? Map<*, *> }
                .orEmpty()
            val execution = OmniFlow.run(
                context = helper.context,
                request = OmniFlow.Run(
                    id = runId,
                    goal = goal,
                    source = "vlm",
                    toolName = AgentToolNames.VLM_TASK,
                    input = mapOf(
                        "goal" to goal,
                        "model" to GUI_MODEL_SCENE,
                        "started_at_ms" to startedAtMs,
                        "step_skill_guidance" to env.resolvedSkills
                            .map { it.guiStepGuidance() }
                            .filter(String::isNotBlank)
                            .distinct()
                            .joinToString("\n\n"),
                        "defer_user_input" to true,
                    ) + if (runtimeOptions["disable_function_recall"] == true) {
                        mapOf("disable_function_recall" to true)
                    } else {
                        emptyMap()
                    },
                    startedAtMs = startedAtMs,
                ),
                modelClient = llmClientFactory(CoroutineScope(currentCoroutineContext()))
                    .asOmniFlowModelClient(),
                hooks = OmniFlow.Hooks(
                    beforeOperation = {
                        helper.ensureRunActive()
                        toolHandle.throwIfStopRequested()
                    },
                    stopRequested = toolHandle::isManualStopRequested,
                    onProgress = { progress, extras ->
                        helper.reportToolProgress(
                            callback = callback,
                            toolName = AgentToolNames.VLM_TASK,
                            progress = progress,
                            extras = extras + ("run_id" to runId),
                            toolHandle = toolHandle,
                        )
                    },
                ),
            )
            val result = execution.payload
            finalStateId = firstNonBlank(
                mapArg(result["final_state"])["state_id"],
                execution.finalStateId,
            ).takeIf(String::isNotBlank)
            doneReason = firstNonBlank(result["done_reason"]).ifBlank {
                if (result["success"] == true) "finished" else "error"
            }
            val content = firstNonBlank(result["finished_content"])
            if (doneReason == "waiting_input") {
                val question = content.ifBlank { "请提供继续执行所需的信息。" }
                errorMessage = question
                callback.onClarifyRequired(question, null)
                return ToolExecutionResult.Clarify(question, null)
            }
            success = result["success"] == true
            if (!success) {
                errorMessage = firstNonBlank(result["error_message"], result["error_code"])
                    .ifBlank { "gui_task_failed" }
                return ToolExecutionResult.Error(
                    AgentToolNames.VLM_TASK,
                    helper.localized(errorMessage),
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
            return ToolExecutionResult.ContextResult(
                toolName = AgentToolNames.VLM_TASK,
                summaryText = helper.localized(completed),
                previewJson = encoded,
                rawResultJson = encoded,
                success = true,
            )
        } catch (error: CancellationException) {
            doneReason = "cancelled"
            errorMessage = error.message
            throw error
        } catch (error: Exception) {
            errorMessage = error.message.orEmpty().ifBlank { error.javaClass.simpleName }
            return ToolExecutionResult.Error(
                AgentToolNames.VLM_TASK,
                helper.localized(errorMessage),
            )
        }
    }

    private fun modelCapabilityViolation(): String? {
        val binding = runCatching {
            SceneModelBindingStore.getBinding(GUI_MODEL_SCENE)
        }.getOrNull()
        return "model_native_tool_calls_unsupported: selected model declares toolCall=false"
            .takeIf { binding?.toolCall == false }
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

    private companion object {
        const val GUI_MODEL_SCENE = "scene.vlm.operation.primary"
    }
}
