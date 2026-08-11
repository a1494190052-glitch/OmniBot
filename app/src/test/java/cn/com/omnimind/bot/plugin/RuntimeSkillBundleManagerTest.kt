package cn.com.omnimind.bot.plugin.runtime

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RuntimeSkillBundleManagerTest {
    @Test
    fun `prebuilt runtime archive requires a pinned sha256`() {
        val error = expectFailure {
            RuntimeSkillSpec(
                id = "omniflow-gui-runtime",
                packagedAssetPath = "omni-vlm-lite/runtime-skill/omniflow-gui-runtime",
                prebuiltRuntimeArchive = "scripts/runtime.prebuilt.zip",
            ).validated()
        }

        assertTrue(error.message.orEmpty().contains("SHA-256"))
    }

    @Test
    fun `prebuilt runtime archive and checksum validate together`() {
        val spec = RuntimeSkillSpec(
            id = "omniflow-gui-runtime",
            packagedAssetPath = "omni-vlm-lite/runtime-skill/omniflow-gui-runtime",
            prebuiltRuntimeArchive = "scripts/runtime.prebuilt.zip",
            prebuiltRuntimeSha256 = "a".repeat(64),
        ).validated()

        assertEquals("scripts/runtime.prebuilt.zip", spec.prebuiltRuntimeArchive)
    }

    @Test
    fun `verified prebuilt runtime is extracted and source archive is removed`() {
        val root = createTempDirectory("omniflow-prebuilt-").toFile()
        try {
            val archive = File(root, "runtime.prebuilt.zip")
            writeArchive(
                archive,
                mapOf(
                    "python/omniflow/bridge.py" to "# bridge\n",
                    ".runtime/installed.json" to "{}\n",
                ),
            )
            val expectedSha256 = sha256(archive)
            val target = File(root, "runtime")

            unpackVerifiedPrebuiltRuntime(
                archive = archive,
                target = target,
                expectedSha256 = expectedSha256,
                runtimeId = "omniflow-gui-runtime",
            )

            assertEquals("# bridge\n", File(target, "python/omniflow/bridge.py").readText())
            assertTrue(File(target, ".runtime/installed.json").isFile)
            assertFalse(archive.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `prebuilt runtime checksum mismatch fails before extraction`() {
        val root = createTempDirectory("omniflow-prebuilt-").toFile()
        try {
            val archive = File(root, "runtime.prebuilt.zip")
            writeArchive(
                archive,
                mapOf(
                    "python/omniflow/bridge.py" to "# bridge\n",
                    ".runtime/installed.json" to "{}\n",
                ),
            )
            val target = File(root, "runtime")

            val error = expectFailure {
                unpackVerifiedPrebuiltRuntime(
                    archive = archive,
                    target = target,
                    expectedSha256 = "0".repeat(64),
                    runtimeId = "omniflow-gui-runtime",
                )
            }

            assertTrue(error.message.orEmpty().contains("checksum_mismatch"))
            assertFalse(target.exists())
            assertTrue(archive.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `prebuilt runtime rejects entries escaping the runtime directory`() {
        val root = createTempDirectory("omniflow-prebuilt-").toFile()
        try {
            val archive = File(root, "runtime.prebuilt.zip")
            writeArchive(
                archive,
                mapOf(
                    "../escaped" to "bad",
                    "python/omniflow/bridge.py" to "# bridge\n",
                    ".runtime/installed.json" to "{}\n",
                ),
            )

            val error = expectFailure {
                unpackVerifiedPrebuiltRuntime(
                    archive = archive,
                    target = File(root, "runtime"),
                    expectedSha256 = sha256(archive),
                    runtimeId = "omniflow-gui-runtime",
                )
            }

            assertTrue(error.message.orEmpty().contains("unsafe_entry"))
            assertFalse(File(root, "escaped").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `verified component archive extracts the declared runtime skill`() {
        val root = createTempDirectory("omniflow-component-").toFile()
        try {
            val archive = File(root, "component.zip")
            writeArchive(
                archive,
                mapOf(
                    "component.json" to componentJson(),
                    "runtime-skill/omniflow-gui-runtime/SKILL.md" to
                        "---\nname: omniflow-gui-runtime\n---\n",
                ),
            )
            val target = File(root, "unpacked")

            unpackVerifiedComponentArchive(
                archive = archive,
                target = target,
                expectedSha256 = sha256(archive),
                componentId = "com.omnimind.omni-vlm-lite",
                runtimeSkillId = "omniflow-gui-runtime",
            )

            assertTrue(
                File(target, "runtime-skill/omniflow-gui-runtime/SKILL.md").isFile,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `component archive rejects entries escaping install directory`() {
        val root = createTempDirectory("omniflow-component-").toFile()
        try {
            val archive = File(root, "component.zip")
            writeArchive(
                archive,
                mapOf(
                    "../escaped" to "bad",
                    "component.json" to componentJson(),
                    "runtime-skill/omniflow-gui-runtime/SKILL.md" to
                        "---\nname: omniflow-gui-runtime\n---\n",
                ),
            )

            val error = expectFailure {
                unpackVerifiedComponentArchive(
                    archive = archive,
                    target = File(root, "unpacked"),
                    expectedSha256 = sha256(archive),
                    componentId = "com.omnimind.omni-vlm-lite",
                    runtimeSkillId = "omniflow-gui-runtime",
                )
            }

            assertTrue(error.message.orEmpty().contains("unsafe_entry"))
            assertFalse(File(root, "escaped").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun componentJson(): String = buildJsonObject {
        put("id", "com.omnimind.omni-vlm-lite")
        put("runtimeSkill", buildJsonObject { put("id", "omniflow-gui-runtime") })
    }.toString()

    private fun writeArchive(archive: File, entries: Map<String, String>) {
        ZipOutputStream(archive.outputStream().buffered()).use { output ->
            entries.forEach { (name, contents) ->
                output.putNextEntry(ZipEntry(name))
                output.write(contents.toByteArray())
                output.closeEntry()
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun expectFailure(block: () -> Unit): Throwable = try {
        block()
        fail("Expected failure")
        error("unreachable")
    } catch (error: Throwable) {
        error
    }
}
