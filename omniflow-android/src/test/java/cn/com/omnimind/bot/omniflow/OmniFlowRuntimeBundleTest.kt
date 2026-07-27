package cn.com.omnimind.bot.omniflow

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniFlowRuntimeBundleTest {
    @Test
    fun `manifest parses pinned runtime identity`() {
        val manifest = parseOmniFlowRuntimeManifest(
            ByteArrayInputStream(
                """
                    runtime.version=2026.07.19.1
                    runtime.protocol=omniflow.bridge.v2
                    runtime.capabilities=catalog,health,recall
                    runtime.python=3.12
                    runtime.platform=alpine-3.21-aarch64
                    bridge.contract.sha256=${"d".repeat(64)}
                    omniflow.commit=flow
                    omniflow.source.sha256=${"b".repeat(64)}
                    omnitransfer.commit=transfer
                    omnitransfer.source.sha256=${"c".repeat(64)}
                    omnitransfer.checkpoint=checkpoints/matcher.npz
                    numpy.version=2.2.6
                    bundle.sha256=${"a".repeat(64)}
                """.trimIndent().toByteArray()
            )
        )

        assertEquals("2026.07.19.1", manifest.version)
        assertEquals("omniflow.bridge.v2", manifest.protocol)
        assertEquals(setOf("catalog", "health", "recall"), manifest.capabilities)
        assertEquals("d".repeat(64), manifest.bridgeContractSha256)
        assertEquals("flow", manifest.omniFlowCommit)
        assertEquals("b".repeat(64), manifest.omniFlowSourceSha256)
        assertEquals("transfer", manifest.omniTransferCommit)
        assertEquals("c".repeat(64), manifest.omniTransferSourceSha256)
        assertEquals("checkpoints/matcher.npz", manifest.omniTransferCheckpoint)
    }

    @Test
    fun `bundle extraction rejects path traversal`() {
        val archive = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("../escape.py"))
                zip.write("bad".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        val target = Files.createTempDirectory("omniflow-runtime-test").toFile()

        val error = runCatching {
            extractOmniFlowRuntimeBundle(ByteArrayInputStream(archive), target)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(!requireNotNull(target.parentFile).resolve("escape.py").exists())
        target.deleteRecursively()
    }
}
