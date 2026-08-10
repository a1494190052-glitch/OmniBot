package cn.com.omnimind.baselib.account

interface AccountRemoteDataSource {
    suspend fun requestRegistrationCode(email: String): RegistrationCodeRequest

    suspend fun register(
        email: String,
        password: String,
        verificationRequestId: String,
        verificationCode: String,
    ): AccountUser

    suspend fun login(email: String, password: String): AccountSession

    suspend fun refresh(refreshToken: String): AccountSession

    suspend fun logout(refreshToken: String)

    suspend fun getCurrentUser(accessToken: String): AccountUser

    suspend fun getAiSettings(accessToken: String): AiSettings

    suspend fun updateAiSettings(accessToken: String, mode: AiAccessMode): AiSettings
}
