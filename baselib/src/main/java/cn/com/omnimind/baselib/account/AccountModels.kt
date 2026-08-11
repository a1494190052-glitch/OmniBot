package cn.com.omnimind.baselib.account

enum class AiAccessMode(val wireValue: String) {
    PLATFORM("platform"),
    BYOK("byok");

    companion object {
        fun fromWireValue(value: String): AiAccessMode =
            entries.firstOrNull { it.wireValue == value.trim().lowercase() }
                ?: throw AccountProtocolException("Unknown AI access mode: $value")
    }
}

data class AccountUser(
    val id: String,
    val email: String,
    val role: String,
    val status: String,
    val emailVerifiedAt: String,
    val createdAt: String,
)

data class AccountTokens(
    val accessToken: String,
    val accessExpiresAt: String,
    val refreshToken: String,
    val refreshExpiresAt: String,
)

data class AccountSession(
    val user: AccountUser,
    val tokens: AccountTokens,
)

data class RegistrationCodeRequest(
    val requestId: String,
    val expiresInSeconds: Long,
)

data class PlatformQuota(
    val enabled: Boolean,
    val balance: Long,
    val unit: String,
)

data class AiSettings(
    val mode: AiAccessMode,
    val keyStorage: String,
    val platform: PlatformQuota,
    val platformAvailable: Boolean = false,
    val platformUnavailableReason: String? = null,
    val updatedAt: String,
) {
    val effectiveMode: AiAccessMode
        get() = if (platformAvailable) mode else AiAccessMode.BYOK
}

open class AccountException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

class AccountNotConfiguredException :
    AccountException("Account server URL is not configured")

class AccountNotAuthenticatedException :
    AccountException("The user is not signed in")

class AccountApiException(
    val statusCode: Int,
    val errorCode: String?,
    message: String,
) : AccountException(message)

class AccountProtocolException(message: String, cause: Throwable? = null) :
    AccountException(message, cause)
