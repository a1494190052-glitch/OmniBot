package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.util.OmniLog

interface VLMRecallActionProvider {
    suspend fun selectAction(
        goal: String,
        packageName: String?,
        disableFunctionRecall: Boolean,
        streamClient: VLMStreamClient,
    ): FunctionRunAction?
}

object VLMRecallActionProviderRegistry {
    private const val TAG = "VLMRecallActionProvider"

    @Volatile
    private var provider: VLMRecallActionProvider? = null

    fun register(provider: VLMRecallActionProvider?) {
        this.provider = provider
    }

    fun clear() {
        provider = null
    }

    suspend fun selectAction(
        goal: String,
        packageName: String?,
        disableFunctionRecall: Boolean,
        streamClient: VLMStreamClient,
    ): FunctionRunAction? {
        val activeProvider = provider ?: return null
        return runCatching {
            activeProvider.selectAction(goal, packageName, disableFunctionRecall, streamClient)
        }.onFailure { OmniLog.w(TAG, "recall action provider failed: ${it.message}") }
            .getOrNull()
    }
}
