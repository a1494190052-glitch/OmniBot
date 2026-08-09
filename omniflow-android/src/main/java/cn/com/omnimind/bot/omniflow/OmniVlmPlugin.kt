package cn.com.omnimind.bot.omniflow

import android.content.Context
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import java.util.UUID

class OmniVlmPlugin internal constructor(
    private val backend: OmniVlmBackend,
) {
    data class Request(
        val goal: String,
        val runId: String = "gui-${UUID.randomUUID()}",
        val stepSkillGuidance: String = "",
        val deferUserInput: Boolean = true,
    )

    data class Hooks(
        val beforeOperation: suspend () -> Unit = {},
        val stopRequested: () -> Boolean = { false },
        val onProgress: suspend (String, Map<String, Any?>) -> Unit = { _, _ -> },
    )

    data class Result(
        val payload: Map<String, Any?>,
        val finalStateId: String?,
    )

    @Volatile
    private var installed = false

    @Volatile
    private var enabled = false

    fun install(enabled: Boolean = true) {
        this.enabled = enabled
        installed = true
    }

    suspend fun setEnabled(enabled: Boolean) {
        check(installed) { "omni_vlm_not_installed" }
        this.enabled = enabled
        if (!enabled) backend.shutdown()
    }

    fun isEnabled(): Boolean = installed && enabled

    suspend fun execute(
        context: Context,
        request: Request,
        modelClient: OmniFlowModelClient,
        hooks: Hooks = Hooks(),
    ): Result {
        check(installed) { "omni_vlm_not_installed" }
        check(enabled) { "omni_vlm_disabled" }
        val goal = request.goal.trim()
        require(goal.isNotEmpty()) { "omni_vlm_goal_required" }
        val runId = request.runId.trim()
        require(runId.isNotEmpty()) { "omni_vlm_run_id_required" }
        return backend.execute(
            context = context,
            request = request.copy(goal = goal, runId = runId),
            modelClient = modelClient,
            hooks = Hooks(
                beforeOperation = {
                    check(enabled) { "omni_vlm_disabled" }
                    hooks.beforeOperation()
                },
                stopRequested = { !enabled || hooks.stopRequested() },
                onProgress = hooks.onProgress,
            ),
        )
    }

    fun stop(runId: String): Boolean {
        val normalizedRunId = runId.trim()
        require(normalizedRunId.isNotEmpty()) { "omni_vlm_run_id_required" }
        return backend.stop(normalizedRunId)
    }

    companion object {
        const val MODEL_SCENE = "scene.vlm.operation.primary"
        const val RUN_LOG_TOOL = "vlm_task"

        private val shared = OmniVlmPlugin(DefaultOmniVlmBackend)

        fun install(enabled: Boolean = true) = shared.install(enabled)

        suspend fun setEnabled(enabled: Boolean) = shared.setEnabled(enabled)

        fun isEnabled(): Boolean = shared.isEnabled()

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
    suspend fun shutdown()

    suspend fun execute(
        context: Context,
        request: OmniVlmPlugin.Request,
        modelClient: OmniFlowModelClient,
        hooks: OmniVlmPlugin.Hooks,
    ): OmniVlmPlugin.Result

    fun stop(runId: String): Boolean
}

private object DefaultOmniVlmBackend : OmniVlmBackend {
    override suspend fun shutdown() = OnlineVlmRuntime.shutdown()

    override suspend fun execute(
        context: Context,
        request: OmniVlmPlugin.Request,
        modelClient: OmniFlowModelClient,
        hooks: OmniVlmPlugin.Hooks,
    ): OmniVlmPlugin.Result = OnlineVlmRuntime.execute(
        context = context,
        request = request,
        modelClient = modelClient,
        hooks = hooks,
    )

    override fun stop(runId: String): Boolean = OnlineVlmRuntime.stop(runId)
}

interface OmniFlowModelClient {
    suspend fun streamTurn(
        request: ChatCompletionRequest,
        onReasoningUpdate: (suspend (String) -> Unit)? = null,
    ): ChatCompletionTurn
}
