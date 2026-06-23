package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.util.OmniLog

data class VLMRecallContextRequest(
    val context: UIContext,
    val currentXml: String?,
    val currentPackageName: String?,
    val screenshotBase64: String?,
    val stepIndex: Int,
    val snapshot: VLMCurrentPageSnapshot? = null,
    val disableOmniFlowRecall: Boolean = false,
)

data class VLMCurrentPageSnapshot(
    val packageName: String?,
    val xml: String?,
    val screenshotBase64: String?,
    val displayWidth: Int,
    val displayHeight: Int,
    val capturedAtMs: Long,
)

interface VLMRecallContextProvider {
    suspend fun enrich(request: VLMRecallContextRequest): UIContext
}

object VLMRecallContextProviderRegistry {
    private const val TAG = "VLMRecallContextProvider"

    @Volatile
    private var provider: VLMRecallContextProvider? = null

    fun register(provider: VLMRecallContextProvider?) {
        this.provider = provider
    }

    fun clear() {
        provider = null
    }

    suspend fun enrich(request: VLMRecallContextRequest): UIContext {
        val activeProvider = provider ?: return request.context
        return runCatching { activeProvider.enrich(request) }
            .onFailure { error ->
                OmniLog.w(TAG, "recall context provider failed: ${error.message}")
            }
            .getOrDefault(request.context)
    }
}

interface VLMPostTaskHook {
    suspend fun onTaskCompleted(
        goal: String,
        packageName: String?,
        executedFunctionId: String?,
        success: Boolean,
        executionTrace: List<UIStep>,
    )
}

object VLMPostTaskHookRegistry {
    private const val TAG = "VLMPostTaskHook"

    @Volatile
    private var hook: VLMPostTaskHook? = null

    fun register(hook: VLMPostTaskHook?) { this.hook = hook }
    fun clear() { hook = null }

    suspend fun notify(
        goal: String,
        packageName: String?,
        executedFunctionId: String?,
        success: Boolean,
        executionTrace: List<UIStep>,
    ) {
        val active = hook ?: return
        runCatching { active.onTaskCompleted(goal, packageName, executedFunctionId, success, executionTrace) }
            .onFailure { OmniLog.w(TAG, "post-task hook failed: ${it.message}") }
    }
}

object VLMSystemPromptRegistry {
    @Volatile
    private var strategiesExtra: String? = null

    fun set(extra: String?) {
        strategiesExtra = extra?.trim()?.takeIf { it.isNotBlank() }
    }

    fun get(): String? = strategiesExtra
}
