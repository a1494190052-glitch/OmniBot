package cn.com.omnimind.accessibility.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OobGetStateXmlContractTest {
    @Test
    fun `get state always returns the complete captured xml`() {
        val executor = projectFile(
            "app/src/main/java/cn/com/omnimind/bot/mcp/McpToolExecutors.kt",
        ).readText()
        val getState = executor
            .substringAfter("suspend fun executeGetState(")
            .substringBefore("suspend fun executeAct(")

        assertTrue(getState.contains("OmniFlowState.build("))
        assertTrue(getState.contains("xml = rawXml"))
        assertTrue(getState.contains("if (includeXml) captured else captured - \"xml\""))
        assertFalse(getState.contains("xml_truncated"))
        assertFalse(getState.contains("maxXmlChars"))
        assertFalse(executor.contains("fun truncateXml("))
    }

    @Test
    fun `get state tool no longer advertises a truncation option`() {
        val definitions = projectFile(
            "app/src/main/java/cn/com/omnimind/bot/mcp/McpToolDefinitions.kt",
        ).readText()
        val getStateTool = definitions
            .substringAfter("val getStateTool")
            .substringBefore("val actTool")

        assertFalse(getStateTool.contains("max_xml_chars"))
    }

    @Test
    fun `accessibility capture serializes the complete tree in one pass`() {
        val capture = projectFile(
            "accessibility/src/main/java/cn/com/omnimind/accessibility/action/OmniCaptureAction.kt",
        ).readText()
        val captureXml = capture
            .substringAfter("fun captureScreenshotXml(")
            .substringBefore("fun getNodeMap(")
        val treeUtils = projectFile(
            "accessibility/src/main/java/cn/com/omnimind/accessibility/util/XmlTreeUtils.kt",
        ).readText()
        val directSerializer = treeUtils
            .substringAfter("fun buildXmlDirectly(")
            .substringBefore("fun buildXmlTree(")

        assertTrue(captureXml.contains("XmlTreeUtils.buildXmlDirectly(rootNode)"))
        assertFalse(captureXml.contains("XmlTreeUtils.buildXmlTree(rootNode)"))
        assertTrue(directSerializer.contains("FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST"))
        assertFalse(directSerializer.contains("maxChildren"))
        assertFalse(directSerializer.contains("minOf(node.childCount"))
    }

    private fun projectFile(path: String): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (!current.resolve("settings.gradle.kts").isFile) {
            current = requireNotNull(current.parentFile) { "Could not locate project root" }
        }
        return current.resolve(path)
    }
}
