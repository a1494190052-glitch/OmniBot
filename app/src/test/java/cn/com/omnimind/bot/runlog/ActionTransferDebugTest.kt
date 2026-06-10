package cn.com.omnimind.bot.runlog

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import cn.com.omnimind.omniintelligence.models.ScrollDirection
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ActionTransferDebugTest {
    @Test
    fun `debug remap from xml or json fixtures`() {
        val sourcePath = setting("omniflow.debug.sourceJson")
            ?: setting("omniflow.debug.sourceXml")
        val targetPath = setting("omniflow.debug.targetJson")
            ?: setting("omniflow.debug.targetXml")
        val x = setting("omniflow.debug.x")?.toFloatOrNull()
        val y = setting("omniflow.debug.y")?.toFloatOrNull()
        assumeTrue(
            "Set OMNIFLOW_DEBUG_SOURCE_JSON/OMNIFLOW_DEBUG_TARGET_JSON/OMNIFLOW_DEBUG_X/OMNIFLOW_DEBUG_Y",
            sourcePath != null && targetPath != null && x != null && y != null,
        )

        val tool = setting("omniflow.debug.tool") ?: "click"
        val sourceXml = loadXml(sourcePath!!, source = true)
        val targetXml = loadXml(targetPath!!, source = false)
        val args = linkedMapOf<String, Any?>(
            "x" to x!!,
            "y" to y!!,
        )
        setting("omniflow.debug.targetDescription")?.let { args["target_description"] = it }
        setting("omniflow.debug.resourceId")?.let { args["node_resource_id"] = it }
        val step = linkedMapOf<String, Any?>(
            "executor" to "omniflow",
            "tool" to tool,
            "coordinate_hook" to "omniflow",
            "args" to args,
            "source_context" to mapOf("src_ctx" to mapOf("page" to sourceXml)),
        )

        val remap = DebugBackend(targetXml).useAsBackend {
            UIStepExecutor.remapStepArgs(step)
        }
        val remappedArgs = remap.args as? Map<*, *>
        val payload = linkedMapOf<String, Any?>(
            "input" to linkedMapOf<String, Any?>(
                "source_path" to sourcePath,
                "target_path" to targetPath,
                "tool" to tool,
                "x" to x,
                "y" to y,
                "source_xml_chars" to sourceXml.length,
                "target_xml_chars" to targetXml.length,
            ),
            "source_xml" to sourceXml,
            "target_xml" to targetXml,
            "mapped_point" to linkedMapOf<String, Any?>(
                "x" to remappedArgs?.get("x"),
                "y" to remappedArgs?.get("y"),
            ),
            "result" to linkedMapOf<String, Any?>(
                "args" to remap.args,
                "meta" to remap.meta,
            ),
        )
        val json = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(payload)
        val outputPath = setting("omniflow.debug.outputJson")
        if (outputPath != null) {
            File(outputPath).apply {
                parentFile?.mkdirs()
                writeText(json)
            }
        }
        println(json)
    }

    private fun <T> DebugBackend.useAsBackend(block: () -> T): T =
        OmniflowActionRuntime.useBackendForTesting(this).use { block() }

    private fun loadXml(path: String, source: Boolean): String {
        val text = File(path).readText()
        if (text.trimStart().startsWith("<")) return text
        val root = JsonParser.parseString(text)
        val keys = if (source) {
            listOf(
                listOf("source_xml"),
                listOf("source_context", "src_ctx", "page"),
                listOf("source_context", "page"),
                listOf("src_ctx", "page"),
                listOf("executed_action_record", "action", "params", "source_context", "src_ctx", "page"),
                listOf("executed_action_record", "source_context", "src_ctx", "page"),
                listOf("page"),
            )
        } else {
            listOf(
                listOf("target_xml"),
                listOf("current_xml"),
                listOf("target_page"),
                listOf("target_context", "page"),
                listOf("current_context", "page"),
                listOf("page"),
            )
        }
        for (keyPath in keys) {
            val value = root.stringAt(keyPath)
            if (!value.isNullOrBlank()) return value
        }
        error("No XML string found in $path")
    }

    private fun JsonElement.stringAt(path: List<String>): String? {
        var current: JsonElement = this
        for (key in path) {
            val obj = current.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            current = obj.get(key) ?: return null
        }
        return current.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    }

    private fun setting(name: String): String? {
        System.getProperty(name)?.takeIf { it.isNotBlank() }?.let { return it }
        val compactEnvName = name.replace(".", "_").replace("-", "_").uppercase()
        val snakeEnvName = name
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .replace(".", "_")
            .replace("-", "_")
            .uppercase()
        return listOf(compactEnvName, snakeEnvName)
            .asSequence()
            .mapNotNull { System.getenv(it)?.takeIf { value -> value.isNotBlank() } }
            .firstOrNull()
    }

    private class DebugBackend(
        private val xml: String,
    ) : OmniflowActionBackend {
        override fun isReady(): Boolean = true
        override suspend fun click(x: Float, y: Float) = Unit
        override suspend fun longPress(x: Float, y: Float, durationMs: Long) = Unit
        override suspend fun scroll(
            x: Float,
            y: Float,
            direction: ScrollDirection,
            distance: Float,
            durationMs: Long,
        ) = Unit
        override suspend fun inputTextToFocusedNode(text: String) = Unit
        override suspend fun launchApplication(packageName: String) = Unit
        override suspend fun pressHotKey(key: String) = Unit
        override fun currentXml(): String = xml
        override fun currentPackageName(): String = "debug"
        override fun currentActivityName(): String = "debug/.ActionTransferDebug"
    }
}
