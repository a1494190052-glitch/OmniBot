package cn.com.omnimind.bot.mcp

/**
 * MCP 服务器状态
 */
data class McpServerState(
    val enabled: Boolean,
    val running: Boolean,
    val host: String?,
    val port: Int,
    val token: String,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "enabled" to enabled,
        "running" to running,
        "host" to host,
        "port" to port,
        "token" to token,
    )
}
