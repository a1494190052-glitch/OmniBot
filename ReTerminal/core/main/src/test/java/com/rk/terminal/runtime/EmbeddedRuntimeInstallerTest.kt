package com.rk.terminal.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class EmbeddedRuntimeInstallerTest {
    @Test
    fun usesPinnedOfficialUbuntuRuntimeByDefault() {
        val entry = EmbeddedRuntimeInstaller.officialUbuntuRuntime()

        assertEquals("ubuntu", entry.id)
        assertEquals("24.04.4", entry.version)
        assertEquals(29_870_567L, entry.compressedSize)
        assertEquals(106_649_600L, entry.expandedSize)
        assertEquals(
            "04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2",
            entry.sha256
        )
        assertEquals("cdimage.ubuntu.com", entry.downloadUrl.host)
        assertEquals("https", entry.downloadUrl.scheme)
    }

    private fun manifest(
        id: String = "ubuntu",
        abi: String = "arm64-v8a",
        url: String = "https://updates.example/terminal-runtimes/downloads/ubuntu/24.04.4/ubuntu.tar.gz",
        compressedSize: Long = 2_000_000,
        expandedSize: Long = 8_000_000,
        sha256: String = "a".repeat(64)
    ): String = """
        {
          "schemaVersion": 1,
          "runtimes": [{
            "id": "$id",
            "version": "24.04.4",
            "abi": "$abi",
            "fileName": "ubuntu-base-24.04.4-base-arm64.tar.gz",
            "compressedSize": $compressedSize,
            "expandedSize": $expandedSize,
            "sha256": "$sha256",
            "downloadUrl": "$url"
          }]
        }
    """.trimIndent()

    @Test
    fun parsesPinnedArm64HttpsRuntime() {
        val entry = EmbeddedRuntimeInstaller.parseManifest(manifest(), "ubuntu")

        assertEquals("ubuntu", entry.id)
        assertEquals("24.04.4", entry.version)
        assertEquals("arm64-v8a", entry.abi)
        assertEquals(2_000_000, entry.compressedSize)
        assertEquals("https", entry.downloadUrl.scheme)
    }

    @Test
    fun rejectsNonHttpsDownload() {
        assertThrows(IOException::class.java) {
            EmbeddedRuntimeInstaller.parseManifest(
                manifest(url = "http://updates.example/ubuntu.tar.gz"),
                "ubuntu"
            )
        }
    }

    @Test
    fun rejectsUnknownAbiAndDistribution() {
        assertThrows(IOException::class.java) {
            EmbeddedRuntimeInstaller.parseManifest(manifest(abi = "armeabi-v7a"), "ubuntu")
        }
        assertThrows(IOException::class.java) {
            EmbeddedRuntimeInstaller.parseManifest(manifest(id = "debian"), "debian")
        }
    }

    @Test
    fun rejectsInvalidSizesAndDigest() {
        assertThrows(IOException::class.java) {
            EmbeddedRuntimeInstaller.parseManifest(
                manifest(compressedSize = 8_000_000, expandedSize = 2_000_000),
                "ubuntu"
            )
        }
        assertThrows(IOException::class.java) {
            EmbeddedRuntimeInstaller.parseManifest(manifest(sha256 = "not-a-sha"), "ubuntu")
        }
    }
}
