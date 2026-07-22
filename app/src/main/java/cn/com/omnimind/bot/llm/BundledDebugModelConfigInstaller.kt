package cn.com.omnimind.bot.llm

import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.OpenAiWireApi
import cn.com.omnimind.baselib.llm.SceneOperationConfig
import cn.com.omnimind.baselib.llm.SceneOperationConfigStore
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.BuildConfig
import com.tencent.mmkv.MMKV

object BundledDebugModelConfigInstaller {
    private const val TAG = "BundledDebugModelConfig"
    private const val KEY_INSTALL_ATTEMPTED = "bundled_debug_model_config_installed_v6"
    private const val SCENE_DISPATCH = "scene.dispatch.model"
    private const val SCENE_VLM_OPERATION = "scene.vlm.operation.primary"
    private const val SCENE_COMPACTOR = "scene.compactor.context.chat"
    private const val SCENE_LOADING = "scene.loading.sprite"
    private const val SCENE_MEMORY_ROLLUP = "scene.memory.rollup"

    private val textScenes = listOf(
        SCENE_DISPATCH,
        SCENE_COMPACTOR,
        SCENE_LOADING,
        SCENE_MEMORY_ROLLUP
    )

    fun installIfNeeded(): Boolean {
        if (!BuildConfig.DEBUG) {
            return false
        }
        val apiBase = BuildConfig.BUNDLED_LLM_BASE_URL.trim()
        val apiKey = BuildConfig.BUNDLED_LLM_API_KEY.trim()
        val agentModel = BuildConfig.BUNDLED_AGENT_MODEL.trim()
        val vlmModel = BuildConfig.BUNDLED_VLM_MODEL.trim()
        if (
            apiBase.isEmpty() ||
            apiKey.isEmpty() ||
            agentModel.isEmpty() ||
            vlmModel.isEmpty()
        ) {
            OmniLog.i(TAG, "Bundled debug model config is incomplete; skip seeding")
            return false
        }
        if (!ModelProviderConfigStore.isValidBaseUrl(apiBase)) {
            OmniLog.w(TAG, "Bundled debug model API base is invalid; skip seeding")
            return false
        }

        val mmkv = MMKV.defaultMMKV() ?: return false
        if (mmkv.decodeBool(KEY_INSTALL_ATTEMPTED, false)) {
            return false
        }

        val profileName = BuildConfig.BUNDLED_LLM_PROFILE_NAME.trim().ifEmpty {
            "Bundled Debug LLM"
        }
        val profiles = ModelProviderConfigStore.listProfiles()
        val bundledProfile = profiles.firstOrNull {
            !it.readOnly &&
                (it.name == profileName || it.id == "debug-runtime-provider") &&
                ModelProviderConfigStore.normalizeBaseUrl(it.baseUrl) ==
                ModelProviderConfigStore.normalizeBaseUrl(apiBase)
        }
        if (bundledProfile == null && profiles.any {
                it.apiKey.isNotBlank() ||
                    (it.sourceType == "custom" && it.baseUrl.isNotBlank())
            }
        ) {
            mmkv.encode(KEY_INSTALL_ATTEMPTED, true)
            OmniLog.i(TAG, "Existing model provider config found; keep user settings")
            return false
        }

        val editingProfileId = ModelProviderConfigStore.getEditingProfileId()
        val editableProfile = bundledProfile ?: profiles.firstOrNull { !it.readOnly }
        val seededProfile = ModelProviderConfigStore.saveProfile(
            id = editableProfile?.id,
            name = profileName,
            baseUrl = apiBase,
            apiKey = apiKey,
            sourceType = "custom",
            protocolType = "openai_compatible",
            wireApi = OpenAiWireApi.CHAT_COMPLETIONS
        )
        if (bundledProfile != null && editingProfileId != seededProfile.id) {
            ModelProviderConfigStore.setEditingProfile(editingProfileId)
        }

        textScenes.forEach { sceneId ->
            seedBindingIfBundledOrMissing(sceneId, seededProfile.id, agentModel)
        }
        seedBindingIfBundledOrMissing(SCENE_VLM_OPERATION, seededProfile.id, vlmModel)
        SceneOperationConfigStore.saveConfig(
            SceneOperationConfig(useOfficialService = false)
        )
        mmkv.encode(KEY_INSTALL_ATTEMPTED, true)
        OmniLog.i(TAG, "Bundled debug model config seeded")
        return true
    }

    private fun seedBindingIfBundledOrMissing(
        sceneId: String,
        providerProfileId: String,
        modelId: String
    ) {
        val existingBinding = SceneModelBindingStore.getBinding(sceneId)
        if (existingBinding != null && existingBinding.providerProfileId != providerProfileId) {
            return
        }
        SceneModelBindingStore.saveBinding(
            sceneId = sceneId,
            providerProfileId = providerProfileId,
            modelId = modelId
        )
    }
}
