package cn.com.omnimind.bot.vlm

import cn.com.omnimind.baselib.llm.OfficialVlmOperationConfig
import cn.com.omnimind.baselib.llm.OfficialVlmOperationConfigStore
import cn.com.omnimind.baselib.llm.OpenAiWireApi
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.BuildConfig

object BundledVlmOperationConfig {
    private const val TAG = "BundledVlmConfig"

    fun install() {
        val config = create(
            apiBase = BuildConfig.BUNDLED_VLM_API_BASE,
            apiKey = BuildConfig.BUNDLED_VLM_API_KEY,
            model = BuildConfig.BUNDLED_VLM_MODEL,
        )
        OfficialVlmOperationConfigStore.setBundledDefault(config)
        OmniLog.i(TAG, "bundled default configured=${config != null}")
    }

    internal fun create(
        apiBase: String,
        apiKey: String,
        model: String,
    ): OfficialVlmOperationConfig? {
        return OfficialVlmOperationConfig(
            enabled = true,
            apiBase = apiBase,
            apiKey = apiKey,
            model = model,
            wireApi = OpenAiWireApi.RESPONSES,
        ).takeIf(OfficialVlmOperationConfig::isConfigured)
    }
}
