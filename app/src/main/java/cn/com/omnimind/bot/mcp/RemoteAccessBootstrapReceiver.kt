package cn.com.omnimind.bot.mcp

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.coroutines.launch

/**
 * Shell/root-only bootstrap entrypoint used by scripts/oob-remote-link.sh.
 *
 * The manifest protects this exported receiver with android.permission.DUMP,
 * which is available to adb shell/root but not ordinary third-party apps.
 */
class RemoteAccessBootstrapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_BOOTSTRAP_REMOTE_ACCESS) {
            resultCode = Activity.RESULT_CANCELED
            resultData = RESULT_ERROR_PREFIX + "unsupported_action"
            return
        }

        val requestedPort = intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT)
        val requestedHost = intent.getStringExtra(EXTRA_HOST)?.trim().orEmpty()
        val refreshToken = intent.getBooleanExtra(EXTRA_REFRESH_TOKEN, false)
        val pendingResult = goAsync()

        McpServerManager.serverScope.launch {
            try {
                require(requestedPort in 1_024..65_535) { "invalid_port" }
                if (requestedHost.isNotEmpty()) {
                    require(McpNetworkUtils.isLanAddress(requestedHost)) { "invalid_host" }
                }

                var state = McpServerManager.setEnabled(
                    context = context.applicationContext,
                    enable = true,
                    port = requestedPort
                )
                if (refreshToken) {
                    state = McpServerManager.refreshToken(context.applicationContext)
                }
                val host = requestedHost.ifEmpty {
                    McpNetworkUtils.currentRemoteAccessIp()
                        ?: state.host
                        ?: error("address_unavailable")
                }
                val launchUrl = RemoteAccessLinkBuilder.build(
                    host = host,
                    port = state.port,
                    token = state.token
                )
                pendingResult.setResultCode(Activity.RESULT_OK)
                pendingResult.setResultData(RESULT_LINK_PREFIX + launchUrl)
            } catch (error: Throwable) {
                OmniLog.e(TAG, "remote access bootstrap failed: ${error.message}")
                pendingResult.setResultCode(Activity.RESULT_CANCELED)
                pendingResult.setResultData(
                    RESULT_ERROR_PREFIX + normalizeErrorCode(error.message)
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun normalizeErrorCode(message: String?): String {
        val normalized = message
            ?.lowercase()
            ?.replace(Regex("[^a-z0-9_]+"), "_")
            ?.trim('_')
            .orEmpty()
        return normalized.ifEmpty { "bootstrap_failed" }.take(80)
    }

    companion object {
        private const val TAG = "[RemoteAccessBootstrap]"
        const val ACTION_BOOTSTRAP_REMOTE_ACCESS =
            "cn.com.omnimind.bot.action.BOOTSTRAP_REMOTE_ACCESS"
        const val EXTRA_PORT = "port"
        const val EXTRA_HOST = "host"
        const val EXTRA_REFRESH_TOKEN = "refresh_token"
        const val RESULT_LINK_PREFIX = "OOB_REMOTE_LINK_V1="
        const val RESULT_ERROR_PREFIX = "OOB_REMOTE_ERROR_V1="
        const val DEFAULT_PORT = 8899
    }
}
