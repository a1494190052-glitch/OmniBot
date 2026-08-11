package cn.com.omnimind.baselib.account

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class AccountApiClientTest {
    @Test
    fun loginSendsCredentialsAndReadsTokenPair() = runBlocking {
        val calls = RecordingCallFactory(
            StubResponse(200, tokenPairJson("access-one", "refresh-one"))
        )
        val client = AccountApiClient(
            baseUrl = "https://account.example.com/",
            callFactory = calls,
            ioDispatcher = Dispatchers.Unconfined,
        )

        val session = client.login(" learner@example.com ", "a long password")

        assertEquals("access-one", session.tokens.accessToken)
        assertEquals("refresh-one", session.tokens.refreshToken)
        assertEquals("user-1", session.user.id)
        val request = calls.requests.single()
        assertEquals("https://account.example.com/v1/auth/login", request.url.toString())
        assertEquals("POST", request.method)
        val body = JsonParser.parseString(request.bodyUtf8()).asJsonObject
        assertEquals("learner@example.com", body["email"].asString)
        assertEquals("a long password", body["password"].asString)
    }

    @Test
    fun updateAiSettingsSendsOnlyModeAndBearerToken() = runBlocking {
        val calls = RecordingCallFactory(
            StubResponse(200, aiSettingsJson("byok"))
        )
        val client = AccountApiClient(
            baseUrl = "https://account.example.com",
            callFactory = calls,
            ioDispatcher = Dispatchers.Unconfined,
        )

        val settings = client.updateAiSettings("account-access-token", AiAccessMode.BYOK)

        assertEquals(AiAccessMode.BYOK, settings.mode)
        assertTrue(settings.platformAvailable)
        assertEquals("device", settings.keyStorage)
        val request = calls.requests.single()
        assertEquals("Bearer account-access-token", request.header("Authorization"))
        val body = JsonParser.parseString(request.bodyUtf8()).asJsonObject
        assertEquals(setOf("mode"), body.keySet())
        assertEquals("byok", body["mode"].asString)
        assertFalse(request.bodyUtf8().contains("apiKey", ignoreCase = true))
    }

    @Test
    fun serverErrorIsConvertedToSafeTypedException() = runBlocking {
        val calls = RecordingCallFactory(
            StubResponse(
                401,
                """{"error":{"code":"invalid_access_token","message":"请重新登录"}}""",
            )
        )
        val client = AccountApiClient(
            baseUrl = "https://account.example.com",
            callFactory = calls,
            ioDispatcher = Dispatchers.Unconfined,
        )

        val error = runCatching { client.getAiSettings("expired") }.exceptionOrNull()

        assertTrue(error is AccountApiException)
        error as AccountApiException
        assertEquals(401, error.statusCode)
        assertEquals("invalid_access_token", error.errorCode)
        assertEquals("请重新登录", error.message)
    }

    private fun Request.bodyUtf8(): String {
        val buffer = Buffer()
        requireNotNull(body).writeTo(buffer)
        return buffer.readUtf8()
    }

    private fun tokenPairJson(accessToken: String, refreshToken: String): String =
        """
        {
          "tokenType":"Bearer",
          "accessToken":"$accessToken",
          "accessExpiresAt":"2026-08-04T01:00:00Z",
          "refreshToken":"$refreshToken",
          "refreshExpiresAt":"2026-09-03T01:00:00Z",
          "user":{
            "id":"user-1",
            "email":"learner@example.com",
            "role":"user",
            "status":"active",
            "emailVerifiedAt":"2026-08-04T00:00:00Z",
            "createdAt":"2026-08-04T00:00:00Z"
          }
        }
        """.trimIndent()

    private fun aiSettingsJson(mode: String): String =
        """
        {
          "mode":"$mode",
          "keyStorage":"device",
          "platformAvailable":true,
          "platform":{"platformEnabled":true,"balanceQuota":500,"unit":"new_api_quota"},
          "updatedAt":"2026-08-04T00:00:00Z"
        }
        """.trimIndent()
}

private data class StubResponse(val code: Int, val body: String)

private class RecordingCallFactory(vararg responses: StubResponse) : Call.Factory {
    private val queuedResponses = ArrayDeque(responses.toList())
    val requests = mutableListOf<Request>()

    override fun newCall(request: Request): Call {
        requests += request
        val response = checkNotNull(queuedResponses.pollFirst()) { "No response queued" }
        return StubCall(request, response)
    }
}

private class StubCall(
    private val originalRequest: Request,
    private val stub: StubResponse,
) : Call {
    private var executed = false
    private var canceled = false

    override fun request(): Request = originalRequest

    override fun execute(): Response {
        check(!executed) { "Already executed" }
        executed = true
        return response()
    }

    override fun enqueue(responseCallback: Callback) {
        check(!executed) { "Already executed" }
        executed = true
        responseCallback.onResponse(this, response())
    }

    override fun cancel() {
        canceled = true
    }

    override fun isExecuted(): Boolean = executed

    override fun isCanceled(): Boolean = canceled

    override fun clone(): Call = StubCall(originalRequest, stub)

    override fun timeout(): Timeout = Timeout.NONE

    private fun response(): Response = Response.Builder()
        .request(originalRequest)
        .protocol(Protocol.HTTP_1_1)
        .code(stub.code)
        .message(if (stub.code in 200..299) "OK" else "Error")
        .body(stub.body.toResponseBody("application/json".toMediaType()))
        .build()
}
