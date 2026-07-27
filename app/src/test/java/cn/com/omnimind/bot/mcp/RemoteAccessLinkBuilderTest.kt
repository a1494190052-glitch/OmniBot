package cn.com.omnimind.bot.mcp

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteAccessLinkBuilderTest {
    @Test
    fun `launch token is encoded in fragment instead of query`() {
        assertEquals(
            "http://100.80.12.34:8899/webchat/#token=a%2Bb%2Fc%3D%3D%20token",
            RemoteAccessLinkBuilder.build(
                host = "100.80.12.34",
                port = 8899,
                token = "a+b/c== token"
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `public hosts are rejected`() {
        RemoteAccessLinkBuilder.build(
            host = "203.0.113.10",
            port = 8899,
            token = "token"
        )
    }

    @Test
    fun `tailscale address wins over ordinary lan address`() {
        assertEquals(
            "100.99.1.2",
            McpNetworkUtils.preferredRemoteAccessAddress(
                listOf("192.168.1.20", "100.99.1.2", "10.0.0.5")
            )
        )
    }

    @Test
    fun `first lan address is used when tailscale is unavailable`() {
        assertEquals(
            "192.168.1.20",
            McpNetworkUtils.preferredRemoteAccessAddress(
                listOf("192.168.1.20", "10.0.0.5")
            )
        )
    }
}
