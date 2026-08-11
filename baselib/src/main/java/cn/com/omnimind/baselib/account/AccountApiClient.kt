package cn.com.omnimind.baselib.account

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class AccountApiClient(
    baseUrl: String,
    private val callFactory: Call.Factory = OkHttpClient(),
    private val gson: Gson = Gson(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AccountRemoteDataSource {
    private val normalizedBaseUrl = baseUrl.trim().trimEnd('/').also { value ->
        require(value.isNotEmpty()) { "baseUrl is empty" }
        require(value.toHttpUrlOrNull() != null) { "baseUrl is not a valid HTTP URL" }
    }

    override suspend fun requestRegistrationCode(email: String): RegistrationCodeRequest {
        val response = executeJson(
            request = jsonRequest(
                path = "/v1/auth/email-codes",
                method = "POST",
                body = EmailCodeRequest(email = email.trim(), purpose = "register"),
            ),
            responseClass = EmailCodeResponse::class.java,
        )
        return RegistrationCodeRequest(
            requestId = response.requestId.required("requestId"),
            expiresInSeconds = response.expiresInSeconds,
        )
    }

    override suspend fun register(
        email: String,
        password: String,
        verificationRequestId: String,
        verificationCode: String,
    ): AccountUser = executeJson(
        request = jsonRequest(
            path = "/v1/auth/register",
            method = "POST",
            body = RegisterRequest(
                email = email.trim(),
                password = password,
                verificationRequestId = verificationRequestId.trim(),
                verificationCode = verificationCode.trim(),
            ),
        ),
        responseClass = UserResponse::class.java,
    ).toDomain()

    override suspend fun login(email: String, password: String): AccountSession =
        executeJson(
            request = jsonRequest(
                path = "/v1/auth/login",
                method = "POST",
                body = LoginRequest(email = email.trim(), password = password),
            ),
            responseClass = TokenPairResponse::class.java,
        ).toDomain()

    override suspend fun refresh(refreshToken: String): AccountSession = executeJson(
        request = jsonRequest(
            path = "/v1/auth/refresh",
            method = "POST",
            body = RefreshTokenRequest(refreshToken = refreshToken),
        ),
        responseClass = TokenPairResponse::class.java,
    ).toDomain()

    override suspend fun logout(refreshToken: String) {
        executeWithoutBody(
            jsonRequest(
                path = "/v1/auth/logout",
                method = "POST",
                body = RefreshTokenRequest(refreshToken = refreshToken),
            )
        )
    }

    override suspend fun getCurrentUser(accessToken: String): AccountUser = executeJson(
        request = authenticatedRequest("/v1/me", "GET", accessToken),
        responseClass = UserResponse::class.java,
    ).toDomain()

    override suspend fun getAiSettings(accessToken: String): AiSettings = executeJson(
        request = authenticatedRequest("/v1/me/ai-settings", "GET", accessToken),
        responseClass = AiSettingsResponse::class.java,
    ).toDomain()

    override suspend fun updateAiSettings(
        accessToken: String,
        mode: AiAccessMode,
    ): AiSettings = executeJson(
        request = jsonRequest(
            path = "/v1/me/ai-settings",
            method = "PUT",
            body = UpdateAiSettingsRequest(mode = mode.wireValue),
            accessToken = accessToken,
        ),
        responseClass = AiSettingsResponse::class.java,
    ).toDomain()

    private fun authenticatedRequest(path: String, method: String, accessToken: String): Request =
        Request.Builder()
            .url(endpoint(path))
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .method(method, null)
            .build()

    private fun jsonRequest(
        path: String,
        method: String,
        body: Any,
        accessToken: String? = null,
    ): Request {
        val builder = Request.Builder()
            .url(endpoint(path))
            .header("Accept", "application/json")
            .method(
                method,
                gson.toJson(body).toRequestBody(JSON_MEDIA_TYPE),
            )
        if (!accessToken.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $accessToken")
        }
        return builder.build()
    }

    private fun endpoint(path: String): String = "$normalizedBaseUrl/${path.trimStart('/')}"

    private suspend fun <T> executeJson(request: Request, responseClass: Class<T>): T {
        val body = execute(request)
        return try {
            gson.fromJson(body, responseClass)
                ?: throw AccountProtocolException("Account server returned an empty JSON value")
        } catch (error: JsonParseException) {
            throw AccountProtocolException("Account server returned invalid JSON", error)
        }
    }

    private suspend fun executeWithoutBody(request: Request) {
        execute(request)
    }

    private suspend fun execute(request: Request): String = withContext(ioDispatcher) {
        callFactory.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val envelope = runCatching {
                    gson.fromJson(body, ErrorEnvelope::class.java)
                }.getOrNull()
                throw AccountApiException(
                    statusCode = response.code,
                    errorCode = envelope?.error?.code,
                    message = envelope?.error?.message?.takeIf { it.isNotBlank() }
                        ?: "Account request failed with HTTP ${response.code}",
                )
            }
            body
        }
    }

    private fun String?.required(fieldName: String): String =
        this?.takeIf { it.isNotBlank() }
            ?: throw AccountProtocolException("Account response is missing $fieldName")

    private fun UserResponse.toDomain(): AccountUser = AccountUser(
        id = id.required("user.id"),
        email = email.required("user.email"),
        role = role.required("user.role"),
        status = status.required("user.status"),
        emailVerifiedAt = emailVerifiedAt.required("user.emailVerifiedAt"),
        createdAt = createdAt.required("user.createdAt"),
    )

    private fun TokenPairResponse.toDomain(): AccountSession = AccountSession(
        user = user?.toDomain()
            ?: throw AccountProtocolException("Account response is missing user"),
        tokens = AccountTokens(
            accessToken = accessToken.required("accessToken"),
            accessExpiresAt = accessExpiresAt.required("accessExpiresAt"),
            refreshToken = refreshToken.required("refreshToken"),
            refreshExpiresAt = refreshExpiresAt.required("refreshExpiresAt"),
        ),
    )

    private fun AiSettingsResponse.toDomain(): AiSettings = AiSettings(
        mode = AiAccessMode.fromWireValue(mode.required("mode")),
        keyStorage = keyStorage.required("keyStorage"),
        platform = platform?.let {
            PlatformQuota(
                enabled = it.platformEnabled,
                balance = it.balanceQuota,
                unit = it.unit.required("platform.unit"),
            )
        } ?: throw AccountProtocolException("Account response is missing platform quota"),
        platformAvailable = platformAvailable,
        platformUnavailableReason = platformUnavailableReason?.trim()?.ifEmpty { null },
        updatedAt = updatedAt.required("updatedAt"),
    )

    private data class EmailCodeRequest(
        @SerializedName("email") val email: String,
        @SerializedName("purpose") val purpose: String,
    )

    private data class EmailCodeResponse(
        @SerializedName("requestId") val requestId: String? = null,
        @SerializedName("expiresInSeconds") val expiresInSeconds: Long = 0,
    )

    private data class RegisterRequest(
        @SerializedName("email") val email: String,
        @SerializedName("password") val password: String,
        @SerializedName("verificationRequestId") val verificationRequestId: String,
        @SerializedName("verificationCode") val verificationCode: String,
    )

    private data class LoginRequest(
        @SerializedName("email") val email: String,
        @SerializedName("password") val password: String,
    )

    private data class RefreshTokenRequest(
        @SerializedName("refreshToken") val refreshToken: String,
    )

    private data class UpdateAiSettingsRequest(
        @SerializedName("mode") val mode: String,
    )

    private data class TokenPairResponse(
        @SerializedName("accessToken") val accessToken: String? = null,
        @SerializedName("accessExpiresAt") val accessExpiresAt: String? = null,
        @SerializedName("refreshToken") val refreshToken: String? = null,
        @SerializedName("refreshExpiresAt") val refreshExpiresAt: String? = null,
        @SerializedName("user") val user: UserResponse? = null,
    )

    private data class UserResponse(
        @SerializedName("id") val id: String? = null,
        @SerializedName("email") val email: String? = null,
        @SerializedName("role") val role: String? = null,
        @SerializedName("status") val status: String? = null,
        @SerializedName("emailVerifiedAt") val emailVerifiedAt: String? = null,
        @SerializedName("createdAt") val createdAt: String? = null,
    )

    private data class AiSettingsResponse(
        @SerializedName("mode") val mode: String? = null,
        @SerializedName("keyStorage") val keyStorage: String? = null,
        @SerializedName("platformAvailable") val platformAvailable: Boolean = false,
        @SerializedName("platformUnavailableReason") val platformUnavailableReason: String? = null,
        @SerializedName("platform") val platform: PlatformQuotaResponse? = null,
        @SerializedName("updatedAt") val updatedAt: String? = null,
    )

    private data class PlatformQuotaResponse(
        @SerializedName("platformEnabled") val platformEnabled: Boolean = false,
        @SerializedName("balanceQuota") val balanceQuota: Long = 0,
        @SerializedName("unit") val unit: String? = null,
    )

    private data class ErrorEnvelope(
        @SerializedName("error") val error: ErrorResponse? = null,
    )

    private data class ErrorResponse(
        @SerializedName("code") val code: String? = null,
        @SerializedName("message") val message: String? = null,
    )

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
