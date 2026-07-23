package cn.com.omnimind.assists.controller.http

import org.junit.Assert.assertEquals
import org.junit.Test

class HttpControllerTimeoutTest {
    @Test
    fun `non streaming model requests allow two minutes`() {
        val client = HttpController.buildNonStreamingClient(timeoutSeconds = 120L)

        assertEquals(120_000, client.connectTimeoutMillis)
        assertEquals(120_000, client.readTimeoutMillis)
        assertEquals(120_000, client.writeTimeoutMillis)
        assertEquals(120_000, client.callTimeoutMillis)
    }
}
