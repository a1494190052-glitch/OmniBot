package cn.com.omnimind.bot.omniflow

import android.content.Context
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

    fun install(
        platform: OmniFlowPlatform,
        enabled: Boolean = true,
        runtimeProvider: OmniFlowRuntimeProvider = OmniFlowRuntimeProvider(),
    ) {
        backend.configure(platform, runtimeProvider)
        this.enabled = enabled
        installed = true
    }

    fun setEnabled(enabled: Boolean) {
        check(installed) { "omni_vlm_not_installed" }
        this.enabled = enabled
    }

    fun isEnabled(): Boolean = installed && enabled

    suspend fun uninstall() {
        if (!installed) return
        enabled = false
        backend.shutdown()
        installed = false
    }

    fun warmup(context: Context) {
        check(installed) { "omni_vlm_not_installed" }
        if (enabled) backend.warmup(context)
    }

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
        val execution = backend.execute(
            context = context,
            toolName = RUN_GUI_TOOL,
            arguments = mapOf(
                "goal" to goal,
                "model" to MODEL_SCENE,
                "step_skill_guidance" to request.stepSkillGuidance.trim(),
                "defer_user_input" to request.deferUserInput,
            ),
            goal = goal,
            runId = runId,
            modelClient = modelClient,
            hooks = OmniFlow.Hooks(
                beforeOperation = {
                    check(enabled) { "omni_vlm_disabled" }
                    hooks.beforeOperation()
                },
                stopRequested = { !enabled || hooks.stopRequested() },
                onProgress = hooks.onProgress,
            ),
        )
        return Result(execution.payload, execution.finalStateId)
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

        private val shared = OmniVlmPlugin(DefaultOmniVlmBackend)

        fun install(
            platform: OmniFlowPlatform,
            enabled: Boolean = true,
            runtimeProvider: OmniFlowRuntimeProvider = OmniFlowRuntimeProvider(),
        ) = shared.install(platform, enabled, runtimeProvider)

        fun setEnabled(enabled: Boolean) = shared.setEnabled(enabled)

        fun isEnabled(): Boolean = shared.isEnabled()

        suspend fun uninstall() = shared.uninstall()

        fun warmup(context: Context) = shared.warmup(context)

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
    fun configure(
        platform: OmniFlowPlatform,
        runtimeProvider: OmniFlowRuntimeProvider,
    )

    fun warmup(context: Context)

    suspend fun shutdown()

    suspend fun execute(
        context: Context,
        toolName: String,
        arguments: Map<String, Any?>,
        goal: String,
        runId: String,
        modelClient: OmniFlowModelClient,
        hooks: OmniFlow.Hooks,
    ): OmniFlow.Result

    fun stop(runId: String): Boolean
}

private object DefaultOmniVlmBackend : OmniVlmBackend {
    override fun configure(
        platform: OmniFlowPlatform,
        runtimeProvider: OmniFlowRuntimeProvider,
    ) = OmniFlow.configure(platform, runtimeProvider)

    override fun warmup(context: Context) = OmniFlow.warmup(context)

    override suspend fun shutdown() = OmniFlow.shutdown()

    override suspend fun execute(
        context: Context,
        toolName: String,
        arguments: Map<String, Any?>,
        goal: String,
        runId: String,
        modelClient: OmniFlowModelClient,
        hooks: OmniFlow.Hooks,
    ): OmniFlow.Result = OmniFlow.callTool(
        context = context,
        toolName = toolName,
        arguments = arguments,
        goal = goal,
        runId = runId,
        source = "vlm",
        runLogToolName = OmniVlmPlugin.RUN_LOG_TOOL,
        modelClient = modelClient,
        hooks = hooks,
    )

    override fun stop(runId: String): Boolean = OmniFlow.stop(runId)
}
