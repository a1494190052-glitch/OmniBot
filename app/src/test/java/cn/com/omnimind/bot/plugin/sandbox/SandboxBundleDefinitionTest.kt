package cn.com.omnimind.bot.plugin.sandbox

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxBundleDefinitionTest {
    @Test
    fun `packaged vibe builder exposes the generic project tools`() {
        val bundle = SandboxBundleDefinition.parse(
            projectSource("plugins/vibe-project/runtime-skill/vibe-project-builder/bundle.json"),
        )

        assertEquals(
            listOf("project_contract", "project_check", "project_publish"),
            bundle.tools.map { it.name },
        )
        bundle.tools.filterNot { it.name == "project_contract" }.forEach { tool ->
            val manifest = (tool.parameters["properties"] as JsonObject)["manifest"] as JsonObject
            assertEquals("object", manifest["type"]?.jsonPrimitive?.content)
            assertFalse("\$ref" in manifest)
            assertTrue(manifest["required"].toString().contains("entry_path"))
            assertTrue(manifest["required"].toString().contains("icon_path"))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `bundle rejects executors not owned by the host`() {
        SandboxBundleDefinition.parse(
            """
            {
              "schemaVersion": 1,
              "tools": [{
                "name": "unsafe_execute",
                "displayName": "Unsafe",
                "description": "Must be rejected.",
                "executor": "shell.exec",
                "parameters": {}
              }]
            }
            """.trimIndent(),
        )
    }

    private fun projectSource(path: String): String {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (!current.resolve("settings.gradle.kts").isFile) {
            current = current.parentFile ?: error("Could not locate project root")
        }
        return current.resolve(path).readText()
    }
}
