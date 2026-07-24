package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.util.OmniLog

data class VLMRecallContextRequest(
    val context: UIContext,
    val currentXml: String?,
    val currentPackageName: String?,
    val screenshotBase64: String?,
    val stepIndex: Int,
    val snapshot: VLMCurrentPageSnapshot? = null,
    val disableFunctionRecall: Boolean = false,
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

object VLMSystemPromptRegistry {
    @Volatile
    private var strategiesExtra: String? = null

    fun set(extra: String?) {
        strategiesExtra = extra?.trim()?.takeIf { it.isNotBlank() }
    }

    fun get(): String? = strategiesExtra
}
