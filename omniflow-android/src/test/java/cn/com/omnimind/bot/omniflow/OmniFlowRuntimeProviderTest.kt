package cn.com.omnimind.bot.omniflow

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniFlowRuntimeProviderTest {
    @Test
    fun `runtime upgrade preserves function store and removes stale temp file`() {
        val storeDirectory = Files.createTempDirectory("omniflow-store-test").toFile()
        val store = storeDirectory.resolve("omniflow.json").apply {
            writeText("{\"schema_version\":\"omniflow.store.v2\"}")
        }
        val staleTemp = storeDirectory.resolve("omniflow.json.tmp").apply {
            writeText("partial")
        }

        alignOmniFlowStoreWithRuntime(storeDirectory, "runtime-fingerprint")

        assertTrue(store.isFile)
        assertEquals(
            "{\"schema_version\":\"omniflow.store.v2\"}",
            store.readText(),
        )
        assertFalse(staleTemp.exists())
        assertEquals(
            "runtime-fingerprint",
            storeDirectory.resolve(".runtime_fingerprint").readText(),
        )
        storeDirectory.deleteRecursively()
    }
}
