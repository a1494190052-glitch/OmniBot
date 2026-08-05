package cn.com.omnimind.bot.omniflow

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class OmniVlmPlugin internal constructor(
    private val backend: OmniVlmBackend,
) {
    data class Request(
        val goal: String,
        val runId: String = "gui-${UUID.randomUUID()}",
        val stepSkillGuidance: String = DEFAULT_STEP_SKILL_GUIDANCE,
        val deferUserInput: Boolean = true,
        val maxSteps: Int = DEFAULT_MAX_STEPS,
    )

    data class Hooks(
        val beforeOperation: suspend () -> Unit = {},
        val stopRequested: () -> Boolean = { false },
        val onProgress: suspend (String, Map<String, Any?>) -> Unit = { _, _ -> },
        val afterExecution: suspend () -> Unit = {},
    )

    data class Result(
        val payload: Map<String, Any?>,
        val finalStateId: String?,
    )

    suspend fun execute(
        context: Context,
        request: Request,
        modelClient: OmniFlowModelClient,
        hooks: Hooks = Hooks(),
    ): Result {
        val goal = request.goal.trim()
        require(goal.isNotEmpty()) { "omni_vlm_goal_required" }
        val runId = request.runId.trim()
        require(runId.isNotEmpty()) { "omni_vlm_run_id_required" }
        return try {
            backend.execute(
                context = context,
                request = request.copy(goal = goal, runId = runId),
                modelClient = modelClient,
                hooks = hooks,
            )
        } finally {
            withContext(NonCancellable) {
                runCatching { hooks.afterExecution() }
            }
        }
    }

    fun stop(runId: String): Boolean {
        val normalizedRunId = runId.trim()
        require(normalizedRunId.isNotEmpty()) { "omni_vlm_run_id_required" }
        return backend.stop(normalizedRunId)
    }

    companion object {
        const val MODEL_SCENE = "scene.vlm.operation.primary"
        const val RUN_GUI_TOOL = "run_gui"
        const val RUN_LOG_TOOL = "vlm_task"
        const val DEFAULT_MAX_STEPS = 30
        internal const val DEFAULT_STEP_SKILL_GUIDANCE =
            "Prefer stable, reusable navigation: use search and type the requested text " +
                "directly before browsing long menus or swiping. Do not select history " +
                "suggestions when the requested text can be entered. Swipe only when no " +
                "usable search or direct target exists."
        private val shared = OmniVlmPlugin(DefaultOmniVlmBackend)

        suspend fun execute(
            context: Context,
            request: Request,
            modelClient: OmniFlowModelClient,
            hooks: Hooks = Hooks(),
        ): Result = shared.execute(context, request, modelClient, hooks)

        fun stop(runId: String): Boolean = shared.stop(runId)
    }
}

internal interface OmniVlmBackend {
    suspend fun execute(
        context: Context,
        request: OmniVlmPlugin.Request,
        modelClient: OmniFlowModelClient,
        hooks: OmniVlmPlugin.Hooks,
    ): OmniVlmPlugin.Result

    fun stop(runId: String): Boolean
}

private object DefaultOmniVlmBackend : OmniVlmBackend {
    override suspend fun execute(
        context: Context,
        request: OmniVlmPlugin.Request,
        modelClient: OmniFlowModelClient,
        hooks: OmniVlmPlugin.Hooks,
    ): OmniVlmPlugin.Result {
        check(OmniFlowPluginRuntime.isEnabled()) { "omniflow_plugin_not_enabled" }
        return executeRecallThenOnline(
            hooks = hooks,
            recall = {
                OmniFlowFunctionRecallRuntime.tryExecute(
                    context = context,
                    request = request,
                    modelClient = modelClient,
                    hooks = hooks,
                )
            },
            online = {
                val execution = OmniFlow.callTool(
                    context = context,
                    toolName = OmniVlmPlugin.RUN_GUI_TOOL,
                    arguments = request.runGuiArguments(),
                    goal = request.goal,
                    runId = request.runId,
                    source = "vlm",
                    runLogToolName = OmniVlmPlugin.RUN_LOG_TOOL,
                    modelClient = modelClient,
                    hooks = OmniFlow.Hooks(
                        beforeOperation = hooks.beforeOperation,
                        stopRequested = hooks.stopRequested,
                        onProgress = hooks.onProgress,
                    ),
                )
                OmniVlmPlugin.Result(execution.payload, execution.finalStateId)
            },
        )
    }

    override fun stop(runId: String): Boolean = OmniFlow.stop(runId)
}

internal suspend fun executeRecallThenOnline(
    hooks: OmniVlmPlugin.Hooks,
    recall: suspend () -> OmniVlmPlugin.Result?,
    online: suspend () -> OmniVlmPlugin.Result,
): OmniVlmPlugin.Result {
    val recalled = try {
        recall()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        hooks.onProgress(
            "复用指令不可用，切换在线视觉执行",
            mapOf(
                "recall_hit" to false,
                "recall_error" to error.message.orEmpty().ifBlank {
                    error.javaClass.simpleName
                },
                "fallback" to "online_vlm",
            ),
        )
        null
    }
    if (recalled == null) return online()
    if (recalled.payload["success"] == true) return recalled
    val doneReason = recalled.payload["done_reason"]?.toString().orEmpty()
    if (doneReason == "cancelled" || doneReason.endsWith("_stopped")) return recalled
    hooks.onProgress(
        "复用指令执行失败，继续在线视觉执行",
        mapOf(
            "recall_hit" to true,
            "recalled_function_id" to recalled.payload["recalled_function_id"],
            "replay_error" to listOf(
                recalled.payload["error_message"],
                recalled.payload["error_code"],
            ).firstOrNull { !it?.toString().isNullOrBlank() },
            "fallback" to "online_vlm",
        ).filterValues { it != null },
    )
    return online()
}

internal fun OmniVlmPlugin.Request.runGuiArguments(): Map<String, Any?> = mapOf(
    "goal" to goal,
    "model" to OmniVlmPlugin.MODEL_SCENE,
    "step_skill_guidance" to stepSkillGuidance.trim(),
    "defer_user_input" to deferUserInput,
    "max_steps" to maxSteps,
)
