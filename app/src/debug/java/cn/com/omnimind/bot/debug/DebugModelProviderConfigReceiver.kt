package cn.com.omnimind.bot.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.SceneModelBindingEntry
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.baselib.util.OmniLog
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class DebugModelProviderConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val operation = intent.stringExtra("operation").ifBlank { OPERATION_CONFIGURE }
        val baseUrl = intent.stringExtra("baseUrl", "base_url")
        val apiKey = intent.stringExtra("apiKey", "api_key")
        val modelId = intent.stringExtra("modelId", "model_id")
        val profileId = intent.stringExtra("profileId", "profile_id")
            .ifBlank { DEFAULT_PROFILE_ID }
        val name = intent.stringExtra("name")
            .ifBlank { DEFAULT_PROFILE_NAME }
        val protocolType = intent.stringExtra("protocolType", "protocol_type")
            .ifBlank { "openai_compatible" }
        val rawSceneIds = intent.stringExtra("sceneIds", "scene_ids")
        val useDefaultSceneIds = rawSceneIds.isBlank()
        val sceneIds = parseSceneIds(rawSceneIds)

        scope.launch {
            try {
                val result = runCatching {
                    when (operation) {
                        OPERATION_QUERY -> queryState()
                        OPERATION_SNAPSHOT -> snapshotState(appContext, profileId, sceneIds)
                        OPERATION_RESTORE -> restoreState(appContext)
                        OPERATION_CONFIGURE -> configure(
                            context = appContext,
                            profileId = profileId,
                            name = name,
                            baseUrl = baseUrl,
                            apiKey = apiKey,
                            modelId = modelId,
                            protocolType = protocolType,
                            sceneIds = sceneIds,
                            clearLegacyDefaultDebugBindings = useDefaultSceneIds,
                        )
                        else -> error("unsupported operation: $operation")
                    }
                }.getOrElse { error ->
                    linkedMapOf<String, Any?>(
                        "success" to false,
                        "phase" to "exception",
                        "error_message" to error.message.orEmpty(),
                        "error_type" to error.javaClass.name,
                    )
                }
                val json = gson.toJson(result)
                File(appContext.filesDir, RESULT_FILE).writeText(json)
                OmniLog.i(TAG, json)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun queryState(): Map<String, Any?> = linkedMapOf(
        "success" to true,
        "editingProfileId" to ModelProviderConfigStore.getEditingProfileId(),
        "profiles" to ModelProviderConfigStore.listProfiles().map { it.toSafePayload() },
        "sceneBindings" to SceneModelBindingStore.getBindingEntries().map { it.toPayload() },
    )

    private fun snapshotState(
        context: Context,
        profileId: String,
        sceneIds: List<String>,
    ): Map<String, Any?> {
        val preferences = context.getSharedPreferences(
            FLUTTER_PREFERENCES,
            Context.MODE_PRIVATE,
        )
        val snapshot = ProviderStateSnapshot(
            profileId = profileId,
            profile = ModelProviderConfigStore.getProfile(profileId),
            editingProfileId = ModelProviderConfigStore.getEditingProfileId(),
            sceneIds = sceneIds,
            bindings = sceneIds.mapNotNull(SceneModelBindingStore::getBinding),
            manualModelIds = preferences.getString(FLUTTER_MANUAL_MODEL_IDS_KEY, null),
        )
        File(context.filesDir, SNAPSHOT_FILE).writeText(gson.toJson(snapshot))
        return queryState() + mapOf("snapshotStored" to true)
    }

    private fun restoreState(context: Context): Map<String, Any?> {
        val file = File(context.filesDir, SNAPSHOT_FILE)
        require(file.isFile) { "provider state snapshot is missing" }
        val snapshot = gson.fromJson(file.readText(), ProviderStateSnapshot::class.java)
        val previousProfile = snapshot.profile
        val currentProfile = ModelProviderConfigStore.getProfile(snapshot.profileId)
        if (previousProfile != null) {
            ModelProviderConfigStore.saveProfile(
                id = previousProfile.id,
                name = previousProfile.name,
                baseUrl = previousProfile.baseUrl,
                apiKey = previousProfile.apiKey,
                customHeaders = previousProfile.customHeaders,
                sourceType = previousProfile.sourceType,
                protocolType = previousProfile.protocolType,
                wireApi = previousProfile.wireApi,
            )
        } else if (currentProfile != null && ModelProviderConfigStore.listProfiles().size > 1) {
            ModelProviderConfigStore.deleteProfile(snapshot.profileId)
        }

        val previousBindings = snapshot.bindings.associateBy(SceneModelBindingEntry::sceneId)
        snapshot.sceneIds.forEach { sceneId ->
            val binding = previousBindings[sceneId]
            if (binding == null) {
                SceneModelBindingStore.clearBinding(sceneId)
            } else {
                SceneModelBindingStore.saveBinding(
                    sceneId = binding.sceneId,
                    providerProfileId = binding.providerProfileId,
                    modelId = binding.modelId,
                    toolCall = binding.toolCall,
                )
            }
        }
        if (ModelProviderConfigStore.getProfile(snapshot.editingProfileId) != null) {
            ModelProviderConfigStore.setEditingProfile(snapshot.editingProfileId)
        }
        context.getSharedPreferences(FLUTTER_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (snapshot.manualModelIds == null) {
                    remove(FLUTTER_MANUAL_MODEL_IDS_KEY)
                } else {
                    putString(FLUTTER_MANUAL_MODEL_IDS_KEY, snapshot.manualModelIds)
                }
            }
            .apply()
        check(file.delete()) { "provider state snapshot cleanup failed" }
        return queryState() + mapOf("restored" to true)
    }

    private fun configure(
        context: Context,
        profileId: String,
        name: String,
        baseUrl: String,
        apiKey: String,
        modelId: String,
        protocolType: String,
        sceneIds: List<String>,
        clearLegacyDefaultDebugBindings: Boolean,
    ): Map<String, Any?> {
        require(baseUrl.isNotBlank()) { "baseUrl is empty" }
        require(apiKey.isNotBlank()) { "apiKey is empty" }
        require(modelId.isNotBlank()) { "modelId is empty" }
        require(sceneIds.isNotEmpty()) { "sceneIds is empty" }

        val profile = ModelProviderConfigStore.saveProfile(
            id = profileId,
            name = name,
            baseUrl = baseUrl,
            apiKey = apiKey,
            protocolType = protocolType,
        )
        sceneIds.forEach { sceneId ->
            SceneModelBindingStore.saveBinding(
                sceneId = sceneId,
                providerProfileId = profile.id,
                modelId = modelId,
            )
        }
        val clearedSceneIds = if (clearLegacyDefaultDebugBindings) {
            clearLegacyDebugBindings(profile.id, sceneIds)
        } else {
            emptyList()
        }
        seedFlutterManualModelId(context, profile.id, modelId)

        return linkedMapOf(
            "success" to true,
            "profile" to profile.toSafePayload(),
            "modelId" to modelId,
            "configuredSceneIds" to sceneIds,
            "clearedSceneIds" to clearedSceneIds,
            "sceneBindings" to SceneModelBindingStore.getBindingEntries().map { it.toPayload() },
        )
    }

    private fun clearLegacyDebugBindings(profileId: String, keepSceneIds: List<String>): List<String> {
        val keep = keepSceneIds.map { it.trim() }.toSet()
        return LEGACY_DEFAULT_DEBUG_SCENE_IDS
            .filterNot { it in keep }
            .filter { sceneId ->
                SceneModelBindingStore.getBinding(sceneId)?.providerProfileId == profileId
            }
            .onEach(SceneModelBindingStore::clearBinding)
    }

    private fun seedFlutterManualModelId(context: Context, profileId: String, modelId: String) {
        val normalizedProfileId = profileId.trim()
        val normalizedModelId = modelId.trim()
        if (normalizedProfileId.isEmpty() || normalizedModelId.isEmpty()) return

        val prefs = context.getSharedPreferences(FLUTTER_PREFERENCES, Context.MODE_PRIVATE)
        val key = FLUTTER_MANUAL_MODEL_IDS_KEY
        val current = runCatching {
            JSONObject(prefs.getString(key, null).orEmpty())
        }.getOrElse {
            JSONObject()
        }
        val ids = current.optJSONArray(normalizedProfileId) ?: JSONArray()
        val exists = (0 until ids.length()).any { index ->
            ids.optString(index).trim() == normalizedModelId
        }
        if (!exists) {
            ids.put(normalizedModelId)
        }
        current.put(normalizedProfileId, ids)
        prefs.edit().putString(key, current.toString()).apply()
    }

    private fun Intent?.stringExtra(vararg names: String): String {
        if (this == null) return ""
        names.forEach { name ->
            getStringExtra(name)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                return it
            }
        }
        return ""
    }

    private fun parseSceneIds(raw: String): List<String> {
        return raw
            .split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { DEFAULT_SCENE_IDS }
    }

    private fun ModelProviderProfile.toSafePayload(): Map<String, Any?> {
        return linkedMapOf(
            "id" to id,
            "name" to name,
            "baseUrl" to baseUrl,
            "protocolType" to protocolType,
            "apiKeyConfigured" to apiKey.isNotBlank(),
            "configured" to isConfigured(),
        )
    }

    private fun SceneModelBindingEntry.toPayload(): Map<String, Any?> {
        return linkedMapOf(
            "sceneId" to sceneId,
            "providerProfileId" to providerProfileId,
            "modelId" to modelId,
        )
    }

    companion object {
        private const val TAG = "DebugModelProviderConfigReceiver"
        private const val RESULT_FILE = "debug-model-provider-config-result.json"
        private const val SNAPSHOT_FILE = "debug-model-provider-state-snapshot.json"
        private const val OPERATION_CONFIGURE = "configure"
        private const val OPERATION_QUERY = "query"
        private const val OPERATION_SNAPSHOT = "snapshot"
        private const val OPERATION_RESTORE = "restore"
        private const val FLUTTER_PREFERENCES = "FlutterSharedPreferences"
        private const val FLUTTER_MANUAL_MODEL_IDS_KEY = "flutter.manual_provider_model_ids_v2"
        private const val DEFAULT_PROFILE_ID = "debug-runtime-provider"
        private const val DEFAULT_PROFILE_NAME = "Provider 1"
        private val DEFAULT_SCENE_IDS = listOf(
            "scene.dispatch.model",
            "scene.vlm.operation.primary",
            "scene.compactor.context.chat",
        )
        private val LEGACY_DEFAULT_DEBUG_SCENE_IDS = listOf(
            "scene.dispatch.model",
            "scene.vlm.operation.primary",
            "scene.compactor.context",
            "scene.compactor.context.chat",
        )
        private val gson = GsonBuilder().disableHtmlEscaping().create()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private data class ProviderStateSnapshot(
        val profileId: String,
        val profile: ModelProviderProfile?,
        val editingProfileId: String,
        val sceneIds: List<String>,
        val bindings: List<SceneModelBindingEntry>,
        val manualModelIds: String?,
    )
}
