package cn.com.omnimind.baselib.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AccountRepository(
    private val remote: AccountRemoteDataSource,
    private val tokenStore: AccountTokenStore,
    private val aiAccessModeStore: AiAccessModeStore = VolatileAiAccessModeStore(),
) {
    private val refreshMutex = Mutex()

    fun isSignedIn(): Boolean = tokenStore.read() != null

    suspend fun requestRegistrationCode(email: String): RegistrationCodeRequest =
        remote.requestRegistrationCode(email)

    suspend fun register(
        email: String,
        password: String,
        verificationRequestId: String,
        verificationCode: String,
    ): AccountUser = remote.register(
        email = email,
        password = password,
        verificationRequestId = verificationRequestId,
        verificationCode = verificationCode,
    )

    suspend fun login(email: String, password: String): AccountSession {
        val session = remote.login(email, password)
        tokenStore.write(session.tokens)
        aiAccessModeStore.clear()
        try {
            getAiSettings()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // Login itself succeeded. The UI overview and app-start sync can
            // retry settings without discarding the valid session.
        }
        return session
    }

    suspend fun currentUser(): AccountUser = authorized(remote::getCurrentUser)

    suspend fun getAiSettings(): AiSettings = authorized(remote::getAiSettings).also {
        aiAccessModeStore.write(it.effectiveMode)
    }

    suspend fun updateAiSettings(mode: AiAccessMode): AiSettings =
        authorized { accessToken -> remote.updateAiSettings(accessToken, mode) }.also {
            aiAccessModeStore.write(it.effectiveMode)
        }

    fun accessTokenForPlatformGateway(): String =
        tokenStore.read()?.accessToken ?: throw AccountNotAuthenticatedException()

    fun cachedAiAccessMode(): AiAccessMode? = aiAccessModeStore.read()

    suspend fun refreshSession(): AccountSession {
        val current = tokenStore.read() ?: throw AccountNotAuthenticatedException()
        return refreshMutex.withLock {
            val latest = tokenStore.read() ?: throw AccountNotAuthenticatedException()
            if (latest.accessToken != current.accessToken) {
                return@withLock AccountSession(
                    user = remote.getCurrentUser(latest.accessToken),
                    tokens = latest,
                )
            }
            refreshAndStore(latest)
        }
    }

    suspend fun logout() {
        val tokens = tokenStore.read()
        try {
            if (tokens != null) {
                remote.logout(tokens.refreshToken)
            }
        } finally {
            tokenStore.clear()
            aiAccessModeStore.clear()
        }
    }

    private suspend fun <T> authorized(operation: suspend (String) -> T): T {
        val initial = tokenStore.read() ?: throw AccountNotAuthenticatedException()
        return try {
            operation(initial.accessToken)
        } catch (error: AccountApiException) {
            if (error.statusCode != 401) throw error
            val refreshed = refreshAfterUnauthorized(initial)
            operation(refreshed.accessToken)
        }
    }

    private suspend fun refreshAfterUnauthorized(stale: AccountTokens): AccountTokens =
        refreshMutex.withLock {
            val current = tokenStore.read() ?: throw AccountNotAuthenticatedException()
            if (current.accessToken != stale.accessToken) {
                return@withLock current
            }
            refreshAndStore(current).tokens
        }

    private suspend fun refreshAndStore(current: AccountTokens): AccountSession {
        return try {
            remote.refresh(current.refreshToken).also { tokenStore.write(it.tokens) }
        } catch (error: AccountApiException) {
            if (error.statusCode == 401) {
                tokenStore.clear()
                aiAccessModeStore.clear()
            }
            throw error
        }
    }
}

private class VolatileAiAccessModeStore : AiAccessModeStore {
    @Volatile
    private var mode: AiAccessMode? = null

    override fun read(): AiAccessMode? = mode

    override fun write(mode: AiAccessMode) {
        this.mode = mode
    }

    override fun clear() {
        mode = null
    }
}
