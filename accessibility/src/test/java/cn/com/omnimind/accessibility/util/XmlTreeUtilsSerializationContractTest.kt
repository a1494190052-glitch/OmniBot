package cn.com.omnimind.accessibility.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class XmlTreeUtilsSerializationContractTest {
    @Test
    fun `serialization preserves the captured visible hierarchy and grounding attributes`() {
        val serializer = source()
            .substringAfter("fun serializeXml(tree: XmlTreeNode): String")

        assertFalse(
            "Structural nodes must not be removed from the serialized hierarchy",
            serializer.contains("if (node.node.show)"),
        )
        listOf(
            "resource-id",
            "class",
            "package",
            "enabled",
            "checkable",
            "checked",
            "visible-to-user",
        ).forEach { attribute ->
            assertTrue(
                "Serialized XML must include the $attribute attribute",
                serializer.contains("addAttr(\"$attribute\""),
            )
        }
        assertFalse(
            "False state must remain observable instead of being treated as absent",
            serializer.contains("value != \"false\""),
        )
    }

    private fun source(): String {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (!current.resolve("settings.gradle.kts").isFile) {
            current = requireNotNull(current.parentFile) { "Could not locate project root" }
        }
        return current
            .resolve("accessibility/src/main/java/cn/com/omnimind/accessibility/util/XmlTreeUtils.kt")
            .readText()
    }
}
