package cn.com.omnimind.baselib.account

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountRepositoryTest {
    @Test
    fun loginStoresBothTokens() = runBlocking {
        val store = FakeTokenStore()
        val remote = FakeAccountRemote().apply {
            loginHandler = { _, _ -> session("access-one", "refresh-one") }
        }
        val repository = AccountRepository(remote, store)

        val loggedIn = repository.login("learner@example.com", "password")

        assertEquals("access-one", loggedIn.tokens.accessToken)
        assertEquals(loggedIn.tokens, store.tokens)
        assertTrue(repository.isSignedIn())
    }

    @Test
    fun unauthorizedSettingsRequestRefreshesRotatedTokensAndRetries() = runBlocking {
        val store = FakeTokenStore(session("expired-access", "old-refresh").tokens)
        val modeStore = FakeAiAccessModeStore()
        val remote = FakeAccountRemote().apply {
            getSettingsHandler = { accessToken ->
                settingsAccessTokens += accessToken
                if (accessToken == "expired-access") {
                    throw AccountApiException(401, "invalid_access_token", "expired")
                }
                aiSettings(AiAccessMode.PLATFORM)
            }
            refreshHandler = { refreshToken ->
                assertEquals("old-refresh", refreshToken)
                session("fresh-access", "rotated-refresh")
            }
        }
        val repository = AccountRepository(remote, store, modeStore)

        val settings = repository.getAiSettings()

        assertEquals(AiAccessMode.PLATFORM, settings.mode)
        assertEquals(listOf("expired-access", "fresh-access"), remote.settingsAccessTokens)
        assertEquals("fresh-access", store.tokens?.accessToken)
        assertEquals("rotated-refresh", store.tokens?.refreshToken)
        assertEquals(AiAccessMode.PLATFORM, modeStore.mode)
    }

    @Test
    fun rejectedRefreshClearsInvalidLocalSession() = runBlocking {
        val store = FakeTokenStore(session("expired-access", "expired-refresh").tokens)
        val modeStore = FakeAiAccessModeStore(AiAccessMode.PLATFORM)
        val remote = FakeAccountRemote().apply {
            getSettingsHandler = {
                throw AccountApiException(401, "invalid_access_token", "expired")
            }
            refreshHandler = {
                throw AccountApiException(401, "invalid_refresh_token", "expired")
            }
        }
        val repository = AccountRepository(remote, store, modeStore)

        val error = runCatching { repository.getAiSettings() }.exceptionOrNull()

        assertTrue(error is AccountApiException)
        assertNull(store.tokens)
        assertNull(modeStore.mode)
        assertFalse(repository.isSignedIn())
    }

    @Test
    fun unavailablePlatformIsCachedAsByokForRequestRouting() = runBlocking {
        val store = FakeTokenStore(session("access", "refresh").tokens)
        val modeStore = FakeAiAccessModeStore(AiAccessMode.PLATFORM)
        val remote = FakeAccountRemote().apply {
            getSettingsHandler = {
                aiSettings(AiAccessMode.PLATFORM, platformAvailable = false)
            }
        }
        val repository = AccountRepository(remote, store, modeStore)

        val settings = repository.getAiSettings()

        assertFalse(settings.platformAvailable)
        assertEquals(AiAccessMode.BYOK, settings.effectiveMode)
        assertEquals(AiAccessMode.BYOK, modeStore.mode)
    }

    @Test
    fun logoutClearsTokensEvenWhenServerCannotBeReached() = runBlocking {
        val store = FakeTokenStore(session("access", "refresh").tokens)
        val modeStore = FakeAiAccessModeStore(AiAccessMode.BYOK)
        val remote = FakeAccountRemote().apply {
            logoutHandler = { throw AccountException("offline") }
        }
        val repository = AccountRepository(remote, store, modeStore)

        val error = runCatching { repository.logout() }.exceptionOrNull()

        assertTrue(error is AccountException)
        assertNull(store.tokens)
        assertNull(modeStore.mode)
    }

    private fun session(accessToken: String, refreshToken: String) = AccountSession(
        user = AccountUser(
            id = "user-1",
            email = "learner@example.com",
            role = "user",
            status = "active",
            emailVerifiedAt = "2026-08-04T00:00:00Z",
            createdAt = "2026-08-04T00:00:00Z",
        ),
        tokens = AccountTokens(
            accessToken = accessToken,
            accessExpiresAt = "2026-08-04T01:00:00Z",
            refreshToken = refreshToken,
            refreshExpiresAt = "2026-09-03T01:00:00Z",
        ),
    )

    private fun aiSettings(
        mode: AiAccessMode,
        platformAvailable: Boolean = true,
    ) = AiSettings(
        mode = mode,
        keyStorage = "device",
        platform = PlatformQuota(true, 500, "new_api_quota"),
        platformAvailable = platformAvailable,
        platformUnavailableReason = if (platformAvailable) null else "平台 AI 服务暂未开放",
        updatedAt = "2026-08-04T00:00:00Z",
    )
}

private class FakeAiAccessModeStore(initial: AiAccessMode? = null) : AiAccessModeStore {
    var mode: AiAccessMode? = initial

    override fun read(): AiAccessMode? = mode

    override fun write(mode: AiAccessMode) {
        this.mode = mode
    }

    override fun clear() {
        mode = null
    }
}

private class FakeTokenStore(initial: AccountTokens? = null) : AccountTokenStore {
    var tokens: AccountTokens? = initial

    override fun read(): AccountTokens? = tokens

    override fun write(tokens: AccountTokens) {
        this.tokens = tokens
    }

    override fun clear() {
        tokens = null
    }
}

private class FakeAccountRemote : AccountRemoteDataSource {
    var loginHandler: suspend (String, String) -> AccountSession = { _, _ -> unused() }
    var refreshHandler: suspend (String) -> AccountSession = { unused() }
    var logoutHandler: suspend (String) -> Unit = { unused() }
    var getSettingsHandler: suspend (String) -> AiSettings = { unused() }
    val settingsAccessTokens = mutableListOf<String>()

    override suspend fun requestRegistrationCode(email: String): RegistrationCodeRequest = unused()

    override suspend fun register(
        email: String,
        password: String,
        verificationRequestId: String,
        verificationCode: String,
    ): AccountUser = unused()

    override suspend fun login(email: String, password: String): AccountSession =
        loginHandler(email, password)

    override suspend fun refresh(refreshToken: String): AccountSession =
        refreshHandler(refreshToken)

    override suspend fun logout(refreshToken: String) = logoutHandler(refreshToken)

    override suspend fun getCurrentUser(accessToken: String): AccountUser = unused()

    override suspend fun getAiSettings(accessToken: String): AiSettings =
        getSettingsHandler(accessToken)

    override suspend fun updateAiSettings(
        accessToken: String,
        mode: AiAccessMode,
    ): AiSettings = unused()

    private fun <T> unused(): T = error("Unexpected fake remote call")
}
