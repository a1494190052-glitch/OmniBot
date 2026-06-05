package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.util.OmniLog

data class VLMActionPipelineRequest(
    val context: UIContext,
    val step: VLMStep,
    val currentXml: String?,
    val currentPackageName: String?,
    val displayWidth: Int,
    val displayHeight: Int,
    val stepIndex: Int,
    val snapshot: VLMCurrentPageSnapshot? = null,
)

data class VLMActionPipelineResult(
    val step: VLMStep,
    val diagnostics: Map<String, String> = emptyMap(),
    val currentXml: String? = null,
    val currentPackageName: String? = null,
)

interface VLMActionPipeline {
    suspend fun preflight(request: VLMActionPipelineRequest): VLMActionPipelineResult
}

object VLMActionPipelineRegistry {
    private const val TAG = "VLMActionPipeline"

    @Volatile
    private var pipeline: VLMActionPipeline? = null

    fun register(pipeline: VLMActionPipeline?) {
        this.pipeline = pipeline
    }

    fun clear() {
        pipeline = null
    }

    suspend fun preflight(request: VLMActionPipelineRequest): VLMActionPipelineResult {
        val activePipeline = pipeline ?: return VLMActionPipelineResult(step = request.step)
        return runCatching { activePipeline.preflight(request) }
            .onFailure { error ->
                OmniLog.w(TAG, "action pipeline failed: ${error.message}")
            }
            .getOrDefault(VLMActionPipelineResult(step = request.step))
    }
}
