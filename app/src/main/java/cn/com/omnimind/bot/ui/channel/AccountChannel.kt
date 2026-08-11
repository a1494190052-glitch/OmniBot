package cn.com.omnimind.bot.ui.channel

import cn.com.omnimind.baselib.account.AccountApiException
import cn.com.omnimind.baselib.account.AccountException
import cn.com.omnimind.baselib.account.AccountNotAuthenticatedException
import cn.com.omnimind.baselib.account.AccountNotConfiguredException
import cn.com.omnimind.baselib.account.AccountUser
import cn.com.omnimind.baselib.account.AiAccessMode
import cn.com.omnimind.baselib.account.AiSettings
import cn.com.omnimind.baselib.account.OmniAccount
import cn.com.omnimind.baselib.account.RegistrationCodeRequest
import cn.com.omnimind.baselib.util.OmniLog
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AccountChannel {
    companion object {
        private const val TAG = "AccountChannel"
        private const val CHANNEL_NAME = "cn.com.omnimind.bot/account"
    }

    private var channel: MethodChannel? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun setChannel(flutterEngine: FlutterEngine) {
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL_NAME)
        channel?.setMethodCallHandler(::handleMethodCall)
    }

    fun clear() {
        channel?.setMethodCallHandler(null)
        channel = null
    }

    private fun handleMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getSessionState" -> launch(result) {
                mapOf(
                    "configured" to OmniAccount.isConfigured(),
                    "signedIn" to (OmniAccount.isConfigured() && OmniAccount.repository().isSignedIn()),
                )
            }

            "getAiRoutingState" -> launch(result) {
                val access = OmniAccount.currentAiRequestAccess()
                mapOf(
                    "mode" to access.mode?.wireValue,
                    "ready" to access.unavailableReason.isNullOrBlank(),
                    "usesPlatform" to access.usesPlatform,
                    "unavailableReason" to access.unavailableReason,
                )
            }

            "requestRegistrationCode" -> launch(result) {
                OmniAccount.repository()
                    .requestRegistrationCode(call.requiredString("email"))
                    .toPayload()
            }

            "register" -> launch(result) {
                OmniAccount.repository().register(
                    email = call.requiredString("email"),
                    password = call.requiredString("password", trim = false),
                    verificationRequestId = call.requiredString("verificationRequestId"),
                    verificationCode = call.requiredString("verificationCode"),
                ).toPayload()
            }

            "login" -> launch(result) {
                OmniAccount.repository().login(
                    email = call.requiredString("email"),
                    password = call.requiredString("password", trim = false),
                ).user.toPayload()
            }

            "logout" -> launch(result) {
                OmniAccount.repository().logout()
                null
            }

            "getOverview" -> launch(result) {
                val repository = OmniAccount.repository()
                val user = repository.currentUser()
                val settings = repository.getAiSettings()
                mapOf(
                    "user" to user.toPayload(),
                    "settings" to settings.toPayload(),
                )
            }

            "updateAiMode" -> launch(result) {
                val mode = when (call.requiredString("mode").lowercase()) {
                    AiAccessMode.PLATFORM.wireValue -> AiAccessMode.PLATFORM
                    AiAccessMode.BYOK.wireValue -> AiAccessMode.BYOK
                    else -> throw IllegalArgumentException("mode must be platform or byok")
                }
                OmniAccount.repository().updateAiSettings(mode).toPayload()
            }

            else -> result.notImplemented()
        }
    }

    private fun launch(
        result: MethodChannel.Result,
        block: suspend () -> Any?,
    ) {
        scope.launch {
            try {
                val payload = withContext(Dispatchers.IO) { block() }
                result.success(payload)
            } catch (error: Throwable) {
                OmniLog.e(TAG, "Account operation failed: ${error.javaClass.simpleName}")
                when (error) {
                    is IllegalArgumentException -> result.error(
                        "INVALID_ARGUMENT",
                        error.message,
                        null,
                    )

                    is AccountNotConfiguredException -> result.error(
                        "ACCOUNT_NOT_CONFIGURED",
                        "账号服务尚未配置",
                        null,
                    )

                    is AccountNotAuthenticatedException -> result.error(
                        "NOT_AUTHENTICATED",
                        "请先登录",
                        null,
                    )

                    is AccountApiException -> result.error(
                        error.errorCode ?: "ACCOUNT_HTTP_${error.statusCode}",
                        error.message,
                        mapOf("statusCode" to error.statusCode),
                    )

                    is AccountException -> result.error(
                        "ACCOUNT_ERROR",
                        error.message,
                        null,
                    )

                    else -> result.error(
                        "ACCOUNT_UNEXPECTED_ERROR",
                        "账号功能暂时不可用，请稍后重试",
                        null,
                    )
                }
            }
        }
    }

    private fun MethodCall.requiredString(name: String, trim: Boolean = true): String {
        val raw = argument<String>(name).orEmpty()
        val value = if (trim) raw.trim() else raw
        if (value.isEmpty()) throw IllegalArgumentException("$name is required")
        return value
    }

    private fun RegistrationCodeRequest.toPayload(): Map<String, Any> = mapOf(
        "requestId" to requestId,
        "expiresInSeconds" to expiresInSeconds,
    )

    private fun AccountUser.toPayload(): Map<String, Any> = mapOf(
        "id" to id,
        "email" to email,
        "role" to role,
        "status" to status,
        "emailVerifiedAt" to emailVerifiedAt,
        "createdAt" to createdAt,
    )

    private fun AiSettings.toPayload(): Map<String, Any> = mapOf(
        "mode" to effectiveMode.wireValue,
        "keyStorage" to keyStorage,
        "platformAvailable" to platformAvailable,
        "platformUnavailableReason" to platformUnavailableReason.orEmpty(),
        "platform" to mapOf(
            "platformEnabled" to platform.enabled,
            "balanceQuota" to platform.balance,
            "unit" to platform.unit,
        ),
        "updatedAt" to updatedAt,
    )
}
