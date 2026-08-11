package cn.com.omnimind.baselib.account

import android.content.Context

object OmniAccount {
    @Volatile
    private var configuredBaseUrl: String = ""

    @Volatile
    private var configuredRepository: AccountRepository? = null

    @Volatile
    private var configuredPlatformGatewayUrl: String = ""

    fun initialize(context: Context, baseUrl: String, platformGatewayUrl: String = "") {
        val normalized = baseUrl.trim().trimEnd('/')
        val normalizedGateway = platformGatewayUrl.trim().trimEnd('/')
        if (normalized.isEmpty()) {
            configuredBaseUrl = ""
            configuredPlatformGatewayUrl = ""
            configuredRepository = null
            return
        }
        if (
            configuredRepository != null &&
            configuredBaseUrl == normalized &&
            configuredPlatformGatewayUrl == normalizedGateway
        ) return
        synchronized(this) {
            if (
                configuredRepository != null &&
                configuredBaseUrl == normalized &&
                configuredPlatformGatewayUrl == normalizedGateway
            ) return
            configuredRepository = AccountRepository(
                remote = AccountApiClient(normalized),
                tokenStore = EncryptedAccountTokenStore(context),
                aiAccessModeStore = SharedPreferencesAiAccessModeStore(context),
            )
            configuredBaseUrl = normalized
            configuredPlatformGatewayUrl = normalizedGateway
        }
    }

    fun isConfigured(): Boolean = configuredRepository != null

    fun repository(): AccountRepository =
        configuredRepository ?: throw AccountNotConfiguredException()

    fun currentAiRequestAccess(): AiRequestAccess {
        val repository = configuredRepository
        return AiRequestAccessResolver.resolve(
            accountConfigured = repository != null,
            signedIn = repository?.isSignedIn() == true,
            cachedMode = repository?.cachedAiAccessMode(),
            platformGatewayUrl = configuredPlatformGatewayUrl,
            accessToken = runCatching { repository?.accessTokenForPlatformGateway() }.getOrNull(),
        )
    }
}
