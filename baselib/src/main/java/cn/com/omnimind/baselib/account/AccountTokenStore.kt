package cn.com.omnimind.baselib.account

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

interface AccountTokenStore {
    fun read(): AccountTokens?

    fun write(tokens: AccountTokens)

    fun clear()
}

/**
 * Stores account credentials in an encrypted SharedPreferences file whose key
 * is held by Android Keystore. Model-provider API keys are deliberately not
 * stored here.
 */
class EncryptedAccountTokenStore(context: Context) : AccountTokenStore {
    private val applicationContext = context.applicationContext

    private val preferences: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            applicationContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    @Synchronized
    override fun read(): AccountTokens? {
        val accessToken = preferences.getString(KEY_ACCESS_TOKEN, null)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val accessExpiresAt = preferences.getString(KEY_ACCESS_EXPIRES_AT, null)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val refreshToken = preferences.getString(KEY_REFRESH_TOKEN, null)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val refreshExpiresAt = preferences.getString(KEY_REFRESH_EXPIRES_AT, null)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return AccountTokens(
            accessToken = accessToken,
            accessExpiresAt = accessExpiresAt,
            refreshToken = refreshToken,
            refreshExpiresAt = refreshExpiresAt,
        )
    }

    @Synchronized
    override fun write(tokens: AccountTokens) {
        preferences.edit()
            .putString(KEY_ACCESS_TOKEN, tokens.accessToken)
            .putString(KEY_ACCESS_EXPIRES_AT, tokens.accessExpiresAt)
            .putString(KEY_REFRESH_TOKEN, tokens.refreshToken)
            .putString(KEY_REFRESH_EXPIRES_AT, tokens.refreshExpiresAt)
            .apply()
    }

    @Synchronized
    override fun clear() {
        preferences.edit().clear().apply()
    }

    companion object {
        const val FILE_NAME = "omni_account_tokens"

        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_ACCESS_EXPIRES_AT = "access_expires_at"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_REFRESH_EXPIRES_AT = "refresh_expires_at"
    }
}
