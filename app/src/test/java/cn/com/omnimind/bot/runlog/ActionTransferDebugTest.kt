package cn.com.omnimind.bot.runlog

import cn.com.omnimind.assists.task.vlmserver.DeviceOperator
import cn.com.omnimind.assists.task.vlmserver.OperationResult
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ActionTransferDebugTest {
    @Test
    fun `swipe remap fails closed when scroll source is missing`() {
        val sourceXml =
            "<hierarchy bounds=\"[0,0][1000,2000]\"><node bounds=\"[10,10][90,90]\" text=\"Header\"/></hierarchy>"
        val targetXml =
            "<hierarchy bounds=\"[0,0][500,1000]\"><node bounds=\"[5,5][45,45]\" text=\"Header\"/></hierarchy>"
        val step = linkedMapOf<String, Any?>(
            "executor" to "omniflow",
            "tool" to "swipe",
            "args" to linkedMapOf(
                "x1" to 500,
                "y1" to 1600,
                "x2" to 500,
                "y2" to 400,
            ),
            "source_context" to mapOf("src_ctx" to mapOf("page" to sourceXml)),
        )

        val remap = ReplayHelper.remapStepArgs(step, DebugDeviceOperator(targetXml))
        val args = remap.args as Map<*, *>

        assertEquals(false, remap.meta["applied"])
        assertEquals("missing_scroll_source_element", remap.meta["reason"])
        assertEquals("anchor_projection", remap.meta["algorithm"])
        assertEquals(500f, (args["x1"] as Number).toFloat())
        assertEquals(1600f, (args["y1"] as Number).toFloat())
    }

    @Test
    fun `swipe remap reads source xml from file paths`() {
        val sourceFile = kotlin.io.path.createTempFile("omniflow-source-", ".xml").toFile()
        sourceFile.deleteOnExit()
        sourceFile.writeText(
            "<hierarchy bounds=\"[0,0][1000,2000]\"><node bounds=\"[10,10][90,90]\" text=\"Header\"/></hierarchy>"
        )
        val targetXml =
            "<hierarchy bounds=\"[0,0][500,1000]\"><node bounds=\"[5,5][45,45]\" text=\"Header\"/></hierarchy>"
        val step = linkedMapOf<String, Any?>(
            "kind" to "command",
            "executor" to "omniflow",
            "tool" to "swipe",
            "args" to linkedMapOf(
                "x1" to 500,
                "y1" to 1600,
                "x2" to 500,
                "y2" to 400,
                "allow_raw_coordinate_replay" to true,
            ),
            "source_context" to mapOf(
                "src_ctx" to mapOf(
                    "page_path" to sourceFile.absolutePath,
                    "xml_path" to sourceFile.absolutePath,
                ),
            ),
        )

        val remap = ReplayHelper.remapStepArgs(step, DebugDeviceOperator(targetXml))
        val args = remap.args as Map<*, *>

        assertEquals(true, remap.meta["applied"])
        assertEquals("root_projection", remap.meta["algorithm"])
        assertEquals(250f, (args["x1"] as Number).toFloat())
        assertEquals(800f, (args["y1"] as Number).toFloat())
        assertEquals(250f, (args["x2"] as Number).toFloat())
        assertEquals(200f, (args["y2"] as Number).toFloat())
    }

    @Test
    fun `swipe transfer uses same matcher branch for scroll container`() {
        val sourceXml =
            """
            <hierarchy bounds="[0,0][1000,2000]">
              <node bounds="[0,100][1000,1900]" class="androidx.recyclerview.widget.RecyclerView" resource-id="com.demo:id/list" scrollable="true">
                <node bounds="[20,120][980,300]" text="Alice"/>
              </node>
            </hierarchy>
            """.trimIndent()
        val targetXml =
            """
            <hierarchy bounds="[0,0][500,1000]">
              <node bounds="[0,50][500,950]" class="androidx.recyclerview.widget.RecyclerView" resource-id="com.demo:id/list" scrollable="true">
                <node bounds="[10,60][490,150]" text="Alice"/>
              </node>
            </hierarchy>
            """.trimIndent()
        val result = ActionTransfer.transfer(
            ActionTransfer.Request(
                action = "swipe",
                args = mapOf("x1" to 500, "y1" to 1600, "x2" to 500, "y2" to 400),
                sourceContext = mapOf("xml" to sourceXml),
                currentContext = mapOf("xml" to targetXml),
            ),
        )
        val args = result.args

        assertEquals(true, result.applied)
        assertEquals(true, result.diagnostics["applied"])
        assertEquals("anchor_projection", result.diagnostics["algorithm"])
        assertEquals(250f, (args["x1"] as Number).toFloat())
        assertEquals(800f, (args["y1"] as Number).toFloat())
        assertEquals(250f, (args["x2"] as Number).toFloat())
        assertEquals(200f, (args["y2"] as Number).toFloat())
    }

    @Test
    fun `hard action transfer failure throws before recorded coordinate replay`() {
        val transfer = ReplayHelper.StepArgsResult(
            args = mapOf("x" to 500, "y" to 1600),
            meta = mapOf(
                "applied" to false,
                "reason" to "no_anchor_match",
                "algorithm" to "anchor_projection",
            ),
        )

        try {
            ReplayHelper.requireActionTransferApplied(
                transfer,
                initialArgs = mapOf("x" to 500, "y" to 1600),
            )
            throw AssertionError("expected action transfer failure")
        } catch (e: ReplayHelper.ExecutionException) {
            assertEquals("OOB_FUNCTION_SOURCE_NOT_REACHED", e.errorCode)
            assertEquals(false, e.diagnostics["recorded_action_args_used"])
        }
    }

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

        val remap = ReplayHelper.remapStepArgs(step, DebugDeviceOperator(targetXml))
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

    private class DebugDeviceOperator(
        private val xml: String,
    ) : DeviceOperator {
        override fun isReady(): Boolean = true
        override suspend fun clickCoordinate(x: Float, y: Float): OperationResult = OperationResult(true, "click")
        override suspend fun longClickCoordinate(x: Float, y: Float, duration: Long): OperationResult =
            OperationResult(true, "long click")
        override suspend fun inputText(text: String): OperationResult = OperationResult(true, "input")
        override suspend fun pressHotKey(key: String): OperationResult = OperationResult(true, "key")
        override suspend fun copyToClipboard(text: String): OperationResult = OperationResult(true, "copy")
        override suspend fun getClipboard(): String? = null
        override suspend fun slideCoordinate(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            duration: Long,
        ): OperationResult = OperationResult(true, "swipe")
        override suspend fun goHome(): OperationResult = OperationResult(true, "home")
        override suspend fun goBack(): OperationResult = OperationResult(true, "back")
        override suspend fun launchApplication(packageName: String): OperationResult = OperationResult(true, "launch")
        override suspend fun captureScreenshot(): String = ""
        override fun getLastScreenshotWidth(): Int = 1080
        override fun getLastScreenshotHeight(): Int = 1920
        override fun getDisplayWidth(): Int = 1080
        override fun getDisplayHeight(): Int = 1920
        override suspend fun showInfo(message: String) = Unit
        override fun currentXml(): String = xml
        override fun currentPackageName(): String = "debug"
        override fun currentActivityName(): String = "debug/.ActionTransferDebug"
        override suspend fun hideKeyboard(): OperationResult = OperationResult(true, "hide")
    }
}
