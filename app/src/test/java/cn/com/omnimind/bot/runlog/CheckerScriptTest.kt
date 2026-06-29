package cn.com.omnimind.bot.runlog

import cn.com.omnimind.assists.task.vlmserver.DeviceOperator
import cn.com.omnimind.assists.task.vlmserver.OperationResult
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class CheckerScriptTest {
    @Test
    fun `script can dry run replay checker`() = runBlocking {
        val xml = checkerXml()
        val currentPackage = env("REPLAY_CHECKER_PACKAGE") ?: "com.example"
        val currentActivity = env("REPLAY_CHECKER_ACTIVITY") ?: "ExampleActivity"
        val expect = env("REPLAY_CHECKER_EXPECT") ?: "any"
        val resultFile = env("REPLAY_CHECKER_RESULT_FILE")?.let(::File)
        val backend = ScriptBackend(
            xml = xml,
            currentPackage = currentPackage,
            currentActivity = currentActivity,
        )

        val effects = ReplayHelper.runChecker(
            deviceOperator = backend,
            step = mapOf(
                "executor" to RunLogReplayPolicy.EXECUTOR_OMNIFLOW,
                "tool" to "click",
                "args" to mapOf("x" to 100, "y" to 100),
            ),
            action = "click",
            args = mapOf("x" to 100, "y" to 100),
            checkerRules = listOf(skipOverlayRule()),
            checkerBudget = ReplayHelper.CheckerTriggerBudget(),
        )
        val result = mapOf(
            "matched" to effects.isNotEmpty(),
            "effects" to effects,
            "fake_click_count" to backend.clickPoints.size,
            "fake_click_points" to backend.clickPoints.map { point ->
                mapOf("x" to point.first, "y" to point.second)
            },
        )

        resultFile?.let { file ->
            file.parentFile?.mkdirs()
            file.writeText(toJson(result), Charsets.UTF_8)
        }

        when (expect) {
            "match" -> assertEquals(true, result["matched"])
            "no-match" -> assertEquals(false, result["matched"])
            "any" -> Unit
            else -> fail("Unsupported REPLAY_CHECKER_EXPECT=$expect")
        }
    }

    private class ScriptBackend(
        private val xml: String,
        private val currentPackage: String,
        private val currentActivity: String,
    ) : DeviceOperator {
        val clickPoints = mutableListOf<Pair<Float, Float>>()

        override fun isReady(): Boolean = true

        override suspend fun clickCoordinate(x: Float, y: Float): OperationResult {
            clickPoints += x to y
            return OperationResult(true, "clicked")
        }

        override suspend fun longClickCoordinate(x: Float, y: Float, duration: Long): OperationResult =
            OperationResult(true, "long clicked")
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
        override fun currentPackageName(): String = currentPackage
        override fun currentActivityName(): String = currentActivity
        override suspend fun hideKeyboard(): OperationResult = OperationResult(true, "hide")
    }

    companion object {
        private fun env(name: String): String? =
            System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }

        private fun checkerXml(): String {
            val file = env("REPLAY_CHECKER_XML_FILE")?.let(::File)
            return if (file != null) {
                file.readText(Charsets.UTF_8)
            } else {
                DEFAULT_SKIP_AD_XML
            }
        }

        private fun skipOverlayRule(): OmniflowCheckerRule =
            requireNotNull(
                OmniflowCheckerRule.fromMap(
                    mapOf(
                        "id" to "dismiss_transient_overlay",
                        "phase" to OmniflowCheckerRule.PHASE_PRE_TRANSFER,
                        "when" to mapOf(
                            "xpath_exists" to "//node[@clickable='true' and contains(@text,'跳过')]",
                        ),
                        "then" to mapOf(
                            "action" to "click",
                            "target_xpath" to "//node[@clickable='true' and contains(@text,'跳过')]",
                        ),
                    )
                )
            )

        private fun toJson(value: Any?): String =
            buildString { appendJsonValue(value) }

        private fun StringBuilder.appendJsonValue(value: Any?) {
            when (value) {
                null -> append("null")
                is Boolean, is Number -> append(value.toString())
                is Map<*, *> -> {
                    append('{')
                    value.entries.forEachIndexed { index, entry ->
                        if (index > 0) append(',')
                        append('\n')
                        append("  ")
                        appendJsonString(entry.key.toString())
                        append(": ")
                        appendJsonValue(entry.value)
                    }
                    if (value.isNotEmpty()) append('\n')
                    append('}')
                }
                is Iterable<*> -> {
                    append('[')
                    value.forEachIndexed { index, item ->
                        if (index > 0) append(", ")
                        appendJsonValue(item)
                    }
                    append(']')
                }
                else -> appendJsonString(value.toString())
            }
        }

        private fun StringBuilder.appendJsonString(value: String) {
            append('"')
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
            append('"')
        }

        private const val DEFAULT_SKIP_AD_XML =
            """<hierarchy bounds="[0,0][1080,1920]"><node bounds="[0,0][1080,1920]" enabled="true" visible-to-user="true" class="android.widget.FrameLayout" resource-id="app:id/splash_container"><node bounds="[870,64][1030,128]" clickable="true" enabled="true" visible-to-user="true" text="跳过 3" class="android.widget.TextView" resource-id="app:id/skip_btn"/></node></hierarchy>"""
    }
}
