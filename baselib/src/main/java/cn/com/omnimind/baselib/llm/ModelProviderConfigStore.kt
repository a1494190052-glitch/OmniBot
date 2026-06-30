package cn.com.omnimind.baselib.llm

import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.baselib.util.OssIdentity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tencent.mmkv.MMKV

object ModelProviderConfigStore {
    private const val TAG = "ModelProviderConfigStore"
    private const val DIRECT_REQUEST_URL_MARKER = "#"

    internal const val KEY_PROVIDER_BASE_URL = "model_provider_openai_base_url"
    internal const val KEY_PROVIDER_API_KEY = "model_provider_openai_api_key"
    private const val KEY_PROVIDER_PROFILES = "model_provider_profiles_v1"
    private const val KEY_EDITING_PROFILE_ID = "model_provider_editing_profile_id"
    private const val KEY_BUILTIN_OFFICIAL_PROFILES_SEEDED =
        "model_provider_builtin_official_profiles_seeded_v2"

    internal const val LEGACY_MODEL_OVERRIDE_KEY = "vlm_operation_model_override"
    internal const val LEGACY_API_BASE_OVERRIDE_KEY = "vlm_operation_api_base_override"
    internal const val LEGACY_API_KEY_OVERRIDE_KEY = "vlm_operation_api_key_override"
    internal const val MIGRATION_DONE_KEY = "model_provider_scene_config_flattened_v3"
    internal const val LEGACY_DEFAULT_PROFILE_ID = "legacy-default"

    private const val DEFAULT_PROFILE_ID = "profile-1"
    private const val DEFAULT_PROFILE_NAME = "Provider 1"
    private val canonicalEndpointSuffixes = listOf(
        "/v1/chat/completions",
        "/chat/completions",
        "/v1/responses",
        "/responses",
        "/v1/images/generations",
        "/images/generations",
        "/v1/models",
        "/models",
        "/v1/messages",
        "/messages"
    )
    private val canonicalVersionBaseSuffixes = listOf(
        "/v1",
        "/compatible-mode/v1"
    )

    private val gson = Gson()

    private data class StoredModelProviderProfile(
        val id: String? = null,
        val name: String? = null,
        val baseUrl: String? = null,
        val apiKey: String? = null,
        val customHeaders: Map<String, String>? = null,
        val sourceType: String? = null,
        val readOnly: Boolean? = null,
        val ready: Boolean? = null,
        val statusText: String? = null,
        val protocolType: String? = null,
        val wireApi: String? = null
    )

    fun listProfiles(): List<ModelProviderProfile> {
        ModelProviderMigration.ensureMigrated()
        val mmkv = MMKV.defaultMMKV() ?: return withBuiltin(defaultProfiles())
        val current = ensureBuiltinOfficialProfilesSeeded(mmkv, readProfiles(mmkv))
        if (current.isNotEmpty()) {
            ensureEditingProfile(mmkv, withBuiltin(current))
            return withBuiltin(current)
        }
        val created = defaultProfiles()
        writeProfiles(mmkv, created)
        mmkv.encode(KEY_EDITING_PROFILE_ID, created.first().id)
        return withBuiltin(created)
    }

    fun getEditingProfileId(): String {
        val profiles = listProfiles()
        val mmkv = MMKV.defaultMMKV()
        if (mmkv == null) return profiles.first().id
        return ensureEditingProfile(mmkv, profiles)
    }

    fun getEditingProfile(): ModelProviderProfile {
        val profiles = listProfiles()
        val editingId = getEditingProfileId()
        return profiles.firstOrNull { it.id == editingId } ?: profiles.first()
    }

    fun getProfile(profileId: String?): ModelProviderProfile? {
        if (profileId.isNullOrBlank()) return null
        return listProfiles().firstOrNull { it.id == profileId.trim() }
    }

    fun setEditingProfile(profileId: String): ModelProviderProfile {
        val normalizedId = profileId.trim()
        require(normalizedId.isNotEmpty()) { "profileId is empty" }
        val profiles = listProfiles()
        val target = profiles.firstOrNull { it.id == normalizedId }
            ?: throw IllegalArgumentException("profile not found: $normalizedId")
        val mmkv = MMKV.defaultMMKV()
        mmkv?.encode(KEY_EDITING_PROFILE_ID, target.id)
        return target
    }

    fun replaceProfiles(
        profiles: List<ModelProviderProfile>,
        editingProfileId: String? = null
    ): List<ModelProviderProfile> {
        ModelProviderMigration.ensureMigrated()

        val sanitized = buildList<ModelProviderProfile> {
            profiles
                .filterNot { MnnLocalProviderStateStore.isBuiltinProfileId(it.id) }
                .forEach { profile ->
                val existing = toList()
                val requestedId = profile.id.trim()
                val normalizedId = when {
                    requestedId.isEmpty() -> generateProfileId(existing)
                    existing.any { it.id == requestedId } -> generateProfileId(existing)
                    else -> requestedId
                }
                    add(
                        ModelProviderProfile(
                            id = normalizedId,
                        name = sanitizeProfileName(
                            raw = profile.name,
                            profiles = existing,
                            existingId = null
                        ),
                        baseUrl = normalizeBaseUrl(profile.baseUrl).orEmpty(),
                            apiKey = profile.apiKey.trim(),
                            customHeaders = ProviderCustomHeaderUtils.sanitizeCustomHeaders(
                                profile.customHeaders
                            ),
                            sourceType = normalizeSourceType(
                                sourceType = profile.sourceType,
                                profileId = normalizedId,
                                baseUrl = profile.baseUrl
                            ),
                            protocolType = normalizeProtocolType(profile.protocolType),
                            wireApi = normalizeWireApi(profile.wireApi)
                        )
                )
            }
        }.ifEmpty { defaultProfiles() }

        val resolvedEditingId = editingProfileId
            ?.trim()
            ?.takeIf { candidate ->
                sanitized.any { it.id == candidate } ||
                    MnnLocalProviderStateStore.isBuiltinProfileId(candidate)
            }
            ?: sanitized.first().id

        val mmkv = MMKV.defaultMMKV()
        if (mmkv != null) {
            writeProfiles(mmkv, sanitized)
            mmkv.encode(KEY_EDITING_PROFILE_ID, resolvedEditingId)
            getProfile(resolvedEditingId)?.let { syncLegacyFlatConfig(mmkv, it) }
        }

        return withBuiltin(sanitized)
    }

    fun saveProfile(
        id: String? = null,
        name: String,
        baseUrl: String,
        apiKey: String,
        customHeaders: Map<String, String> = emptyMap(),
        sourceType: String? = null,
        protocolType: String = "openai_compatible",
        wireApi: String = OpenAiWireApi.CHAT_COMPLETIONS
    ): ModelProviderProfile {
        ModelProviderMigration.ensureMigrated()
        require(!MnnLocalProviderStateStore.isBuiltinProfileId(id)) { "builtin provider is read only" }
        val normalizedProtocolType = normalizeProtocolType(protocolType)
        val normalizedWireApi = resolveWireApiForSave(
            baseUrl = baseUrl,
            protocolType = normalizedProtocolType,
            wireApi = wireApi
        )
        val normalizedCustomHeaders = ProviderCustomHeaderUtils.sanitizeCustomHeaders(customHeaders)
        val mmkv = MMKV.defaultMMKV() ?: return ModelProviderProfile(
            id = id?.trim().orEmpty().ifEmpty { DEFAULT_PROFILE_ID },
            name = name.trim().ifEmpty { DEFAULT_PROFILE_NAME },
            baseUrl = normalizeBaseUrl(baseUrl).orEmpty(),
            apiKey = apiKey.trim(),
            customHeaders = normalizedCustomHeaders,
            sourceType = resolveSourceTypeForSave(
                requestedSourceType = sourceType,
                profileId = id,
                baseUrl = baseUrl,
                existingSourceType = null
            ),
            protocolType = normalizedProtocolType,
            wireApi = normalizedWireApi
        )

        val current = readProfiles(mmkv).toMutableList().ifEmpty {
            defaultProfiles().toMutableList()
        }
        val normalizedId = id?.trim()?.takeIf { it.isNotEmpty() } ?: generateProfileId(current)
        val currentIndex = current.indexOfFirst { it.id == normalizedId }
        val sanitizedName = sanitizeProfileName(
            raw = name,
            profiles = current,
            existingId = if (currentIndex >= 0) normalizedId else null
        )
        val nextProfile = ModelProviderProfile(
            id = normalizedId,
            name = sanitizedName,
            baseUrl = normalizeBaseUrl(baseUrl).orEmpty(),
            apiKey = apiKey.trim(),
            customHeaders = normalizedCustomHeaders,
            sourceType = resolveSourceTypeForSave(
                requestedSourceType = sourceType,
                profileId = normalizedId,
                baseUrl = baseUrl,
                existingSourceType = current.getOrNull(currentIndex)?.sourceType
            ),
            protocolType = normalizedProtocolType,
            wireApi = normalizedWireApi
        )

        if (currentIndex >= 0) {
            current[currentIndex] = nextProfile
        } else {
            current.add(nextProfile)
        }

        writeProfiles(mmkv, current)
        mmkv.encode(KEY_EDITING_PROFILE_ID, nextProfile.id)
        syncLegacyFlatConfig(mmkv, nextProfile)
        return nextProfile
    }

    fun deleteProfile(profileId: String): List<ModelProviderProfile> {
        ModelProviderMigration.ensureMigrated()
        require(!MnnLocalProviderStateStore.isBuiltinProfileId(profileId)) { "builtin provider is read only" }
        val mmkv = MMKV.defaultMMKV() ?: return withBuiltin(defaultProfiles())
        val normalizedId = profileId.trim()
        val current = readProfiles(mmkv).toMutableList().ifEmpty {
            defaultProfiles().toMutableList()
        }
        require(current.size > 1) { "at least one provider profile must remain" }
        val removed = current.removeAll { it.id == normalizedId }
        require(removed) { "profile not found: $normalizedId" }

        writeProfiles(mmkv, current)
        val editingId = mmkv.decodeString(KEY_EDITING_PROFILE_ID)?.trim().orEmpty()
        if (editingId == normalizedId || editingId.isEmpty()) {
            mmkv.encode(KEY_EDITING_PROFILE_ID, current.first().id)
            syncLegacyFlatConfig(mmkv, current.first())
        }
        return withBuiltin(current)
    }

    fun getConfig(): ModelProviderConfig {
        val profile = getEditingProfile()
        if (profile.readOnly && MnnLocalProviderStateStore.isBuiltinProfileId(profile.id)) {
            return MnnLocalProviderStateStore.getConfig()
        }
        return ModelProviderConfig(
            id = profile.id,
            name = profile.name,
            baseUrl = profile.baseUrl,
            apiKey = profile.apiKey,
            customHeaders = ProviderCustomHeaderUtils.sanitizeCustomHeaders(profile.customHeaders),
            source = "profile",
            providerType = profile.sourceType,
            readOnly = profile.readOnly,
            ready = profile.ready,
            statusText = profile.statusText,
            wireApi = profile.wireApi
        )
    }

    fun saveConfig(
        baseUrl: String,
        apiKey: String,
        customHeaders: Map<String, String> = emptyMap()
    ) {
        val current = getEditingProfile()
        require(!current.readOnly) { "builtin provider is read only" }
        saveProfile(
            id = current.id,
            name = current.name,
            baseUrl = baseUrl,
            apiKey = apiKey,
            customHeaders = customHeaders,
            sourceType = current.sourceType,
            protocolType = current.protocolType,
            wireApi = current.wireApi
        )
    }

    fun clearConfig() {
        val current = getEditingProfile()
        require(!current.readOnly) { "builtin provider is read only" }
        saveProfile(
            id = current.id,
            name = current.name,
            baseUrl = "",
            apiKey = "",
            customHeaders = emptyMap(),
            sourceType = current.sourceType,
            protocolType = current.protocolType,
            wireApi = current.wireApi
        )
    }

    fun isValidBaseUrl(value: String): Boolean = normalizeBaseUrl(value) != null

    fun hasDirectRequestUrlMarker(value: String): Boolean {
        return value.trim().endsWith(DIRECT_REQUEST_URL_MARKER)
    }

    fun stripDirectRequestUrlMarker(value: String): String {
        var result = value.trim()
        if (result.endsWith(DIRECT_REQUEST_URL_MARKER)) {
            result = result.dropLast(DIRECT_REQUEST_URL_MARKER.length)
        }
        return result.replace(Regex("/+$"), "")
    }

    fun hasVersionedBasePath(value: String): Boolean {
        val normalized = stripDirectRequestUrlMarker(value).lowercase()
        return canonicalVersionBaseSuffixes.any { normalized.endsWith(it) }
    }

    fun normalizeBaseUrl(value: String): String? {
        val normalized = value.trim()
        if (normalized.isEmpty()) {
            return null
        }
        val hasDirectRequestUrl = hasDirectRequestUrlMarker(normalized)
        val candidate = if (hasDirectRequestUrl) {
            normalized.dropLast(DIRECT_REQUEST_URL_MARKER.length).trim()
        } else {
            normalized
        }
        if (candidate.isEmpty()) {
            return null
        }
        val uri = runCatching { java.net.URI(candidate) }.getOrNull() ?: return null
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            return null
        }

        var result = candidate.replace(Regex("/+$"), "")
        if (!hasDirectRequestUrl) {
            for (suffix in canonicalEndpointSuffixes) {
                if (result.endsWith(suffix, ignoreCase = true)) {
                    result = result.dropLast(suffix.length)
                    break
                }
            }
        }
        result = result.replace(Regex("/+$"), "")
        if (result.isEmpty()) {
            return null
        }
        return if (hasDirectRequestUrl) {
            result + DIRECT_REQUEST_URL_MARKER
        } else {
            result
        }
    }

    internal fun readConfig(mmkv: MMKV): ModelProviderConfig {
        val baseUrl = mmkv.decodeString(KEY_PROVIDER_BASE_URL)
            ?.trim()
            ?.let(::normalizeBaseUrl)
            .orEmpty()
        val apiKey = mmkv.decodeString(KEY_PROVIDER_API_KEY)?.trim().orEmpty()
        return ModelProviderConfig(
            baseUrl = baseUrl,
            apiKey = apiKey,
            customHeaders = emptyMap(),
            source = "legacy"
        )
    }

    internal fun readConfigForScope(mmkv: MMKV, userId: String?): ModelProviderConfig {
        val baseUrl = readScopedString(mmkv, KEY_PROVIDER_BASE_URL, userId)
            ?.let(::normalizeBaseUrl)
            .orEmpty()
        val apiKey = readScopedString(mmkv, KEY_PROVIDER_API_KEY, userId).orEmpty()
        return ModelProviderConfig(
            baseUrl = baseUrl,
            apiKey = apiKey,
            customHeaders = emptyMap(),
            source = "legacy_scope"
        )
    }

    internal fun readLegacyConfigForScope(mmkv: MMKV, userId: String?): ModelProviderConfig {
        val baseUrl = readScopedString(mmkv, LEGACY_API_BASE_OVERRIDE_KEY, userId)
            ?.let(::normalizeBaseUrl)
            .orEmpty()
        val apiKey = readScopedString(mmkv, LEGACY_API_KEY_OVERRIDE_KEY, userId).orEmpty()
        return ModelProviderConfig(
            baseUrl = baseUrl,
            apiKey = apiKey,
            customHeaders = emptyMap(),
            source = "legacy_vlm"
        )
    }

    internal fun scopedKey(key: String, userId: String?): String {
        return if (userId.isNullOrBlank()) key else "user_${userId}_$key"
    }

    internal fun readScopedString(mmkv: MMKV, key: String, userId: String?): String? {
        return mmkv.decodeString(scopedKey(key, userId))
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun ensureEditingProfile(
        mmkv: MMKV,
        profiles: List<ModelProviderProfile>
    ): String {
        val currentId = mmkv.decodeString(KEY_EDITING_PROFILE_ID)?.trim().orEmpty()
        if (profiles.any { it.id == currentId }) {
            return currentId
        }
        val fallback = profiles.first().id
        mmkv.encode(KEY_EDITING_PROFILE_ID, fallback)
        return fallback
    }

    private fun sanitizeProfileName(
        raw: String,
        profiles: List<ModelProviderProfile>,
        existingId: String?
    ): String {
        val normalized = raw.trim()
        if (normalized.isNotEmpty()) {
            return normalized
        }
        val existingIndex = if (existingId == null) -1 else profiles.indexOfFirst { it.id == existingId }
        if (existingIndex >= 0) {
            return profiles[existingIndex].name
        }
        var nextIndex = 1
        val existingNames = profiles.map { it.name }.toSet()
        while (true) {
            val candidate = "Provider $nextIndex"
            if (!existingNames.contains(candidate)) {
                return candidate
            }
            nextIndex += 1
        }
    }

    private fun defaultProfiles(): List<ModelProviderProfile> {
        return buildList {
            add(
                ModelProviderProfile(
                    id = DEFAULT_PROFILE_ID,
                    name = DEFAULT_PROFILE_NAME
                )
            )
            addAll(OfficialProviderRegistry.officialProfiles())
        }
    }

    private fun normalizeSourceType(
        sourceType: String?,
        profileId: String?,
        baseUrl: String?
    ): String {
        return OfficialProviderRegistry.normalizeSourceType(
            sourceType = sourceType,
            profileId = profileId,
            baseUrl = baseUrl
        )
    }

    private fun normalizeProtocolType(value: String?): String {
        return DeepSeekProvider.normalizeProtocolType(value)
    }

    private fun normalizeWireApi(value: String?): String {
        return OpenAiWireApi.normalize(value)
    }

    private fun resolveWireApiForSave(
        baseUrl: String,
        protocolType: String,
        wireApi: String?
    ): String {
        val normalizedWireApi = wireApi?.trim()?.lowercase().orEmpty()
        if (normalizedWireApi == OpenAiWireApi.RESPONSES ||
            normalizedWireApi == OpenAiWireApi.CHAT_COMPLETIONS
        ) {
            return normalizedWireApi
        }
        if (protocolType != "openai_compatible") {
            return OpenAiWireApi.CHAT_COMPLETIONS
        }
        val rawBaseUrl = stripDirectRequestUrlMarker(baseUrl).lowercase()
        return if (
            rawBaseUrl.endsWith("/v1/responses") ||
            rawBaseUrl.endsWith("/responses")
        ) {
            OpenAiWireApi.RESPONSES
        } else {
            OpenAiWireApi.CHAT_COMPLETIONS
        }
    }

    private fun resolveSourceTypeForSave(
        requestedSourceType: String?,
        profileId: String?,
        baseUrl: String,
        existingSourceType: String?
    ): String {
        val normalizedRequested = requestedSourceType?.trim()?.lowercase().orEmpty()
        if (normalizedRequested == "custom") {
            return "custom"
        }
        if (normalizedRequested == "omniinfer") {
            return normalizedRequested
        }
        OfficialProviderRegistry.findByKey(normalizedRequested)?.let { return it.key }
        OfficialProviderRegistry.findByKey(existingSourceType)?.let { return it.key }
        OfficialProviderRegistry.findByProfileId(profileId)?.let { return it.key }
        OfficialProviderRegistry.findByBaseUrl(baseUrl)?.let { return it.key }
        return "custom"
    }

    private fun ensureBuiltinOfficialProfilesSeeded(
        mmkv: MMKV,
        profiles: List<ModelProviderProfile>
    ): List<ModelProviderProfile> {
        if (profiles.isEmpty()) {
            return profiles
        }
        val officialProfiles = OfficialProviderRegistry.officialProfiles()
        val missingProfiles = officialProfiles.filter { official ->
            profiles.none { it.id == official.id }
        }
        if (missingProfiles.isEmpty()) {
            mmkv.encode(KEY_BUILTIN_OFFICIAL_PROFILES_SEEDED, true)
            return profiles
        }
        if (mmkv.decodeBool(KEY_BUILTIN_OFFICIAL_PROFILES_SEEDED, false)) {
            val currentIds = profiles.map { it.id }.toSet()
            if (officialProfiles.all { it.id in currentIds }) {
                return profiles
            }
        }
        val next = buildList {
            profiles.forEach(::add)
            missingProfiles.forEach(::add)
        }
        writeProfiles(mmkv, next)
        mmkv.encode(KEY_BUILTIN_OFFICIAL_PROFILES_SEEDED, true)
        return next
    }

    private fun withBuiltin(profiles: List<ModelProviderProfile>): List<ModelProviderProfile> {
        if (!MnnLocalProviderStateStore.isEnabled()) {
            return profiles.filterNot { MnnLocalProviderStateStore.isBuiltinProfileId(it.id) }
                .ifEmpty { defaultProfiles() }
        }
        val builtIn = MnnLocalProviderStateStore.getProfile()
        return buildList {
            add(builtIn)
            profiles
                .filterNot { it.id == builtIn.id }
                .forEach(::add)
        }
    }

    private fun generateProfileId(profiles: List<ModelProviderProfile>): String {
        var nextIndex = profiles.size + 1
        while (true) {
            val candidate = "profile-$nextIndex"
            if (profiles.none { it.id == candidate }) {
                return candidate
            }
            nextIndex += 1
        }
    }

    internal fun decodeProfilesJson(raw: String?): List<ModelProviderProfile> {
        val normalizedRaw = raw
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return emptyList()
        return try {
            val type = object : TypeToken<List<StoredModelProviderProfile>>() {}.type
            val parsed: List<StoredModelProviderProfile> = gson.fromJson(normalizedRaw, type)
                ?: emptyList()
            val seen = LinkedHashSet<String>()
            parsed.mapNotNull { profile ->
                val normalizedId = profile.id?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                if (!seen.add(normalizedId)) {
                    return@mapNotNull null
                }
                ModelProviderProfile(
                    id = normalizedId,
                    name = profile.name?.trim().orEmpty().ifEmpty { DEFAULT_PROFILE_NAME },
                    baseUrl = normalizeBaseUrl(profile.baseUrl.orEmpty()).orEmpty(),
                    apiKey = profile.apiKey?.trim().orEmpty(),
                    customHeaders = ProviderCustomHeaderUtils.sanitizeCustomHeaders(
                        profile.customHeaders
                    ),
                    sourceType = normalizeSourceType(
                        sourceType = profile.sourceType,
                        profileId = normalizedId,
                        baseUrl = profile.baseUrl
                    ),
                    readOnly = profile.readOnly ?: false,
                    ready = profile.ready ?: true,
                    statusText = profile.statusText,
                    protocolType = normalizeProtocolType(profile.protocolType),
                    wireApi = normalizeWireApi(profile.wireApi)
                )
            }
        } catch (t: Throwable) {
            OmniLog.w(TAG, "read provider profiles failed: ${t.message}")
            emptyList()
        }
    }

    internal fun encodeProfilesJson(profiles: List<ModelProviderProfile>): String {
        val normalized = profiles.mapIndexedNotNull { index, profile ->
            val id = profile.id.trim().takeIf { it.isNotEmpty() }
                ?: return@mapIndexedNotNull null
            if (MnnLocalProviderStateStore.isBuiltinProfileId(id)) {
                return@mapIndexedNotNull null
            }
            StoredModelProviderProfile(
                id = id,
                name = profile.name.trim().ifEmpty { "Provider ${index + 1}" },
                baseUrl = normalizeBaseUrl(profile.baseUrl).orEmpty(),
                apiKey = profile.apiKey.trim(),
                customHeaders = ProviderCustomHeaderUtils.sanitizeCustomHeaders(
                    profile.customHeaders
                ),
                sourceType = normalizeSourceType(
                    sourceType = profile.sourceType,
                    profileId = id,
                    baseUrl = profile.baseUrl
                ),
                readOnly = profile.readOnly,
                ready = profile.ready,
                statusText = profile.statusText,
                protocolType = normalizeProtocolType(profile.protocolType),
                wireApi = normalizeWireApi(profile.wireApi)
            )
        }
        return gson.toJson(normalized)
    }

    private fun readProfiles(mmkv: MMKV): List<ModelProviderProfile> {
        return decodeProfilesJson(mmkv.decodeString(KEY_PROVIDER_PROFILES))
    }

    private fun writeProfiles(mmkv: MMKV, profiles: List<ModelProviderProfile>) {
        mmkv.encode(KEY_PROVIDER_PROFILES, encodeProfilesJson(profiles))
    }

    private fun syncLegacyFlatConfig(mmkv: MMKV, profile: ModelProviderProfile) {
        mmkv.encode(KEY_PROVIDER_BASE_URL, profile.baseUrl)
        mmkv.encode(KEY_PROVIDER_API_KEY, profile.apiKey)
    }

    internal object ModelProviderMigration {
        private const val PRIMARY_SCENE = "scene.dispatch.model"

        fun ensureMigrated() {
            val mmkv = MMKV.defaultMMKV() ?: return
            if (mmkv.decodeBool(MIGRATION_DONE_KEY, false)) {
                return
            }

            try {
                val existingProfiles = readProfiles(mmkv)
                if (existingProfiles.isNotEmpty()) {
                    ensureEditingProfile(mmkv, existingProfiles)
                    syncLegacyFlatConfig(mmkv, existingProfiles.first())
                    return
                }

                val legacyUserId = OssIdentity.currentUserIdOrNull()
                val providerConfig = resolveEffectiveLegacyConfig(mmkv, legacyUserId)
                val initialProfiles = if (
                    providerConfig.baseUrl.isNotBlank() || providerConfig.apiKey.isNotBlank()
                ) {
                    listOf(
                        ModelProviderProfile(
                            id = LEGACY_DEFAULT_PROFILE_ID,
                            name = DEFAULT_PROFILE_NAME,
                            baseUrl = providerConfig.baseUrl,
                            apiKey = providerConfig.apiKey,
                            sourceType = normalizeSourceType(
                                sourceType = null,
                                profileId = LEGACY_DEFAULT_PROFILE_ID,
                                baseUrl = providerConfig.baseUrl
                            )
                        )
                    )
                } else {
                    defaultProfiles()
                }
                val initialProfile = initialProfiles.first()
                writeProfiles(mmkv, initialProfiles)
                mmkv.encode(KEY_EDITING_PROFILE_ID, initialProfile.id)
                syncLegacyFlatConfig(mmkv, initialProfile)

                val mergedOverrides = SceneModelOverrideStore.readLegacyOverrideMapForScope(mmkv, null)
                    .toMutableMap()
                if (!legacyUserId.isNullOrBlank()) {
                    mergedOverrides.putAll(
                        SceneModelOverrideStore.readLegacyOverrideMapForScope(mmkv, legacyUserId)
                    )
                }

                val legacyModel = readScopedString(mmkv, LEGACY_MODEL_OVERRIDE_KEY, legacyUserId)
                    ?.takeIf { SceneModelOverrideStore.isValidModelName(it) }
                    ?: readScopedString(mmkv, LEGACY_MODEL_OVERRIDE_KEY, null)
                        ?.takeIf { SceneModelOverrideStore.isValidModelName(it) }
                if (legacyModel != null) {
                    mergedOverrides.putIfAbsent(PRIMARY_SCENE, legacyModel)
                } else if (
                    (providerConfig.baseUrl.isNotBlank() || providerConfig.apiKey.isNotBlank()) &&
                    !mergedOverrides.containsKey(PRIMARY_SCENE)
                ) {
                    ModelSceneRegistry.getRuntimeProfile(PRIMARY_SCENE)?.model
                        ?.takeIf { SceneModelOverrideStore.isValidModelName(it) }
                        ?.let { mergedOverrides.putIfAbsent(PRIMARY_SCENE, it) }
                }

                if (mergedOverrides.isNotEmpty()) {
                    SceneModelOverrideStore.writeOverrideMap(mmkv, mergedOverrides)
                }
            } catch (t: Throwable) {
                OmniLog.w(TAG, "migrate legacy provider config failed: ${t.message}")
            } finally {
                mmkv.encode(MIGRATION_DONE_KEY, true)
            }
        }

        private fun resolveEffectiveLegacyConfig(mmkv: MMKV, userId: String?): ModelProviderConfig {
            val candidates = buildList {
                if (!userId.isNullOrBlank()) {
                    add(readConfigForScope(mmkv, userId))
                }
                add(readConfigForScope(mmkv, null))
                if (!userId.isNullOrBlank()) {
                    add(readLegacyConfigForScope(mmkv, userId))
                }
                add(readLegacyConfigForScope(mmkv, null))
                add(readConfig(mmkv))
            }
            return candidates.firstOrNull { it.baseUrl.isNotBlank() || it.apiKey.isNotBlank() }
                ?: ModelProviderConfig()
        }
    }
}
