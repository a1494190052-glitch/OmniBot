package cn.com.omnimind.bot.omniflow

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniFlowRuntimeProviderTest {
    @Test
    fun `release always uses apk assets`() {
        assertEquals(
            OmniFlowRuntimeSource.APK_ASSETS,
            selectOmniFlowRuntimeSource(
                debuggable = false,
                overrideManifestExists = true,
                overrideBundleExists = true,
            ),
        )
    }

    @Test
    fun `debug uses apk assets without override`() {
        assertEquals(
            OmniFlowRuntimeSource.APK_ASSETS,
            selectOmniFlowRuntimeSource(
                debuggable = true,
                overrideManifestExists = false,
                overrideBundleExists = false,
            ),
        )
    }

    @Test
    fun `debug uses pushed runtime only when complete`() {
        assertEquals(
            OmniFlowRuntimeSource.DEBUG_FILES,
            selectOmniFlowRuntimeSource(
                debuggable = true,
                overrideManifestExists = true,
                overrideBundleExists = true,
            ),
        )
    }

    @Test
    fun `debug rejects manifest without bundle`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            selectOmniFlowRuntimeSource(
                debuggable = true,
                overrideManifestExists = true,
                overrideBundleExists = false,
            )
        }

        assertEquals("omniflow_debug_runtime_override_incomplete", error.message)
    }

    @Test
    fun `debug rejects bundle without manifest`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            selectOmniFlowRuntimeSource(
                debuggable = true,
                overrideManifestExists = false,
                overrideBundleExists = true,
            )
        }

        assertEquals("omniflow_debug_runtime_override_incomplete", error.message)
    }

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
