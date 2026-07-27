package cn.com.omnimind.bot.mcp

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * MCP 网络工具类
 */
object McpNetworkUtils {

    /**
     * 检查设备当前是否处于可访问局域网的网络环境。
     */
    fun isLanConnected(context: Context): Boolean {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val active = connectivity?.activeNetwork
        val capabilities = active?.let { connectivity.getNetworkCapabilities(it) }
        if (capabilities != null) {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return true
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && currentLanIp() != null) {
                return true
            }
        }
        return currentLanIp() != null
    }

    /**
     * 获取当前局域网 IP 地址
     */
    fun currentLanIp(): String? {
        return currentLanAddresses(includeVirtual = false).firstOrNull()
            ?: currentLanAddresses(includeVirtual = true)
                .firstOrNull(::isTailscaleAddress)
    }

    /**
     * Resolve the best address for a link intended to leave the current Wi-Fi.
     * Tailscale CGNAT addresses win when available; otherwise use the first LAN
     * address using the same address policy as the local MCP server.
     */
    fun currentRemoteAccessIp(): String? {
        return preferredRemoteAccessAddress(
            currentLanAddresses(includeVirtual = true)
        )
    }

    internal fun preferredRemoteAccessAddress(addresses: List<String>): String? {
        val candidates = addresses
            .map(String::trim)
            .filter(::isLanAddress)
            .distinct()
        return candidates.firstOrNull(::isTailscaleAddress)
            ?: candidates.firstOrNull()
    }

    private fun currentLanAddresses(includeVirtual: Boolean): List<String> {
        val interfaces = runCatching {
            NetworkInterface.getNetworkInterfaces()
                ?.let { Collections.list(it) }
                .orEmpty()
        }.getOrDefault(emptyList())

        val result = mutableListOf<String>()
        for (netIf in interfaces) {
            val interfaceUsable = runCatching {
                netIf.isUp &&
                    !netIf.isLoopback &&
                    (includeVirtual || !netIf.isVirtual)
            }.getOrDefault(true)
            if (!interfaceUsable) continue

            val addresses = runCatching { Collections.list(netIf.inetAddresses) }
                .getOrDefault(emptyList())
            addresses.forEach { address ->
                if (!address.isLoopbackAddress &&
                    address is Inet4Address &&
                    isLanAddress(address.hostAddress)
                ) {
                    address.hostAddress?.let(result::add)
                }
            }
        }
        return result.distinct()
    }

    /**
     * 检查是否为局域网地址（包括 RFC1918 和 Tailscale CGNAT）
     */
    fun isLanAddress(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val normalizedHost = host.trim().lowercase()

        if (normalizedHost == "localhost") return true
        if (normalizedHost == "127.0.0.1") return true
        if (normalizedHost == "::1" || normalizedHost == "[::1]") return true

        // RFC1918 私网网段
        if (normalizedHost.startsWith("192.168.")) return true
        if (normalizedHost.startsWith("10.")) return true
        if (normalizedHost.startsWith("172.")) {
            val parts = normalizedHost.split(".")
            if (parts.size >= 2) {
                val second = parts[1].toIntOrNull()
                if (second != null && second in 16..31) return true
            }
        }

        // Tailscale / CGNAT 网段（100.64.0.0/10）
        if (normalizedHost.startsWith("100.")) {
            val parts = normalizedHost.split(".")
            if (parts.size >= 2) {
                val second = parts[1].toIntOrNull()
                if (second != null && second in 64..127) return true
            }
        }

        return false
    }

    private fun isTailscaleAddress(host: String): Boolean {
        if (!host.startsWith("100.")) return false
        val second = host.split(".").getOrNull(1)?.toIntOrNull() ?: return false
        return second in 64..127
    }
}
