package cn.com.omnimind.baselib.llm

import cn.com.omnimind.baselib.util.OmniLog
import com.google.gson.Gson
import com.tencent.mmkv.MMKV

object OfficialVlmOperationConfigStore {
    private const val TAG = "OfficialVlmOperationConfigStore"
    private const val KEY_OFFICIAL_VLM_OPERATION_CONFIG =
        "official_vlm_operation_config_v1"

    private val gson = Gson()
    private val defaultConfig = OfficialVlmOperationConfig()

    fun getConfig(): OfficialVlmOperationConfig {
        val raw = MMKV.defaultMMKV()
            ?.decodeString(KEY_OFFICIAL_VLM_OPERATION_CONFIG)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return defaultConfig
        return parse(raw) ?: defaultConfig
    }

    fun saveConfig(config: OfficialVlmOperationConfig): OfficialVlmOperationConfig {
        val normalized = normalize(config)
        MMKV.defaultMMKV()?.encode(
            KEY_OFFICIAL_VLM_OPERATION_CONFIG,
            gson.toJson(normalized)
        )
        return normalized
    }

    fun normalize(config: OfficialVlmOperationConfig): OfficialVlmOperationConfig {
        return OfficialVlmOperationConfig(
            enabled = config.enabled,
            apiBase = config.apiBase.trim().trimEnd('/'),
            apiKey = config.apiKey.trim(),
            model = config.model.trim(),
            wireApi = OpenAiWireApi.normalize(config.wireApi)
        )
    }

    internal fun parse(raw: String): OfficialVlmOperationConfig? {
        return runCatching {
            gson.fromJson(raw, OfficialVlmOperationConfig::class.java)
        }.onFailure {
            OmniLog.w(TAG, "parse official VLM config failed: ${it.message}")
        }.getOrNull()?.let(::normalize)
    }
}
