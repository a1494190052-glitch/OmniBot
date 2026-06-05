package cn.com.omnimind.bot.runlog

import cn.com.omnimind.omniintelligence.models.ScrollDirection
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class PageGuardCheckerScriptTest {
    @Test
    fun `script can dry run page guard checker`() = runBlocking {
        val xml = checkerXml()
        val currentPackage = env("PAGE_GUARD_CHECKER_PACKAGE") ?: "com.example"
        val currentActivity = env("PAGE_GUARD_CHECKER_ACTIVITY") ?: "ExampleActivity"
        val execute = env("PAGE_GUARD_CHECKER_EXECUTE") == "true"
        val expect = env("PAGE_GUARD_CHECKER_EXPECT") ?: "match"
        val resultFile = env("PAGE_GUARD_CHECKER_RESULT_FILE")?.let(::File)
        val backend = ScriptBackend(
            xml = xml,
            currentPackage = currentPackage,
            currentActivity = currentActivity,
        )

        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = OmniflowStepExecutor.runPageGuardOnce(
                execute = execute,
                source = "page_guard_checker_script",
                checkerBudget = OmniflowStepExecutor.CheckerTriggerBudget(),
            ) + mapOf(
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
                else -> fail("Unsupported PAGE_GUARD_CHECKER_EXPECT=$expect")
            }
        }
    }

    private class ScriptBackend(
        private val xml: String,
        private val currentPackage: String,
        private val currentActivity: String,
    ) : OmniflowActionBackend {
        val clickPoints = mutableListOf<Pair<Float, Float>>()

        override fun isReady(): Boolean = true

        override suspend fun click(x: Float, y: Float) {
            clickPoints += x to y
        }

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

        override fun currentPackageName(): String = currentPackage

        override fun currentActivityName(): String = currentActivity
    }

    companion object {
        private fun env(name: String): String? =
            System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }

        private fun checkerXml(): String {
            val file = env("PAGE_GUARD_CHECKER_XML_FILE")?.let(::File)
            return if (file != null) {
                file.readText(Charsets.UTF_8)
            } else {
                DEFAULT_SKIP_AD_XML
            }
        }

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
