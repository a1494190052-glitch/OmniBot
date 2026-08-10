package cn.com.omnimind.bot.plugin.runtime

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeBundleCatalogTest {
    @Test
    fun `catalog parses vibe bundle and filters profiles`() {
        val main = RuntimeBundleCatalog.parse(catalogJson(), profile = "main")
        val bundle = main.require("com.omnimind.vibe-project-builder")

        assertEquals("sandbox_bundle", bundle.adapterId)
        assertEquals("vibe-project-builder", bundle.runtimeSkill.id)
        assertEquals(
            "vibe-project/runtime-skill/vibe-project-builder",
            bundle.runtimeSkill.packagedAssetPath,
        )
        assertEquals(
            "Enable Vibe Builder",
            (bundle.descriptor.presentation["description"] as JsonObject)["en"]
                .toString()
                .trim('"'),
        )
        assertTrue(RuntimeBundleCatalog.parse(catalogJson(), profile = "investor").bundles.isEmpty())
    }

    @Test
    fun `catalog rejects packaged paths that escape assets`() {
        val invalid = catalogJson().replace(
            "vibe-project/runtime-skill/vibe-project-builder",
            "../vibe-project-builder",
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            RuntimeBundleCatalog.parse(invalid)
        }

        assertTrue(error.message.orEmpty().contains("cannot escape"))
    }

    private fun catalogJson(): String =
        """
        {
          "schemaVersion": 1,
          "plugins": [{
            "id": "com.omnimind.vibe-project-builder",
            "name": "Vibe Builder",
            "version": "0.2.1",
            "publisher": "OmniMind",
            "adapter": "sandbox_bundle",
            "profiles": ["main"],
            "runtimeSkill": {
              "id": "vibe-project-builder",
              "packagedAssetPath": "vibe-project/runtime-skill/vibe-project-builder"
            },
            "presentation": {
              "description": {"en": "Enable Vibe Builder"}
            }
          }]
        }
        """.trimIndent()
}
