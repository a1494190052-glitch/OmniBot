package cn.com.omnimind.assists.task.vlmserver

import android.content.Context

data class VlmTaskEngineRequest(
    val context: Context,
    val runId: String,
    val goal: String,
    val model: String,
    val maxSteps: Int?,
    val packageName: String?,
    val stepSkillGuidance: String,
    val disableFunctionRecall: Boolean,
)

data class VlmTaskEngineResult(
    val success: Boolean,
    val error: String? = null,
    val doneReason: String? = null,
    val finishedContent: String? = null,
    val finalStateId: String? = null,
)

interface VlmTaskEngineHost {
    val deviceOperator: DeviceOperator

    suspend fun beforeStep()

    fun consumeExternalEvents(): List<Map<String, Any?>> = emptyList()

    suspend fun requestUserInput(question: String): String

    suspend fun onModelTurn(metadata: Map<String, Any?>)

    suspend fun onActionStarted(
        action: Map<String, Any?>,
        metadata: Map<String, Any?>,
    )

    suspend fun recordStep(step: Map<String, Any?>)
}

fun interface VlmTaskEngineExecutor {
    suspend fun execute(
        request: VlmTaskEngineRequest,
        host: VlmTaskEngineHost,
    ): VlmTaskEngineResult
}

object VlmTaskEngineRegistry {
    @Volatile
    private var executor: VlmTaskEngineExecutor? = null

    fun register(executor: VlmTaskEngineExecutor?) {
        this.executor = executor
    }

    fun require(): VlmTaskEngineExecutor =
        executor ?: error("vlm_task_engine_not_registered")
}
