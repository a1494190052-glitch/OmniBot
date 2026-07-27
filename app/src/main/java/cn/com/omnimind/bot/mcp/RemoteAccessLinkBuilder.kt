package cn.com.omnimind.bot.mcp

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Builds a WebChat bootstrap URL without putting credentials in the HTTP request. */
object RemoteAccessLinkBuilder {
    fun build(host: String, port: Int, token: String): String {
        val normalizedHost = host.trim()
        require(McpNetworkUtils.isLanAddress(normalizedHost)) {
            "Remote access host must be a private LAN or Tailscale address"
        }
        require(port in 1..65_535) { "Remote access port is invalid" }
        require(token.isNotBlank()) { "Remote access token is missing" }

        val encodedToken = URLEncoder
            .encode(token, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
        return "http://$normalizedHost:$port/webchat/#token=$encodedToken"
    }
}
