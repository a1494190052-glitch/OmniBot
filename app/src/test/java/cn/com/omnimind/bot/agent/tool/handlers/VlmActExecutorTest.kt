package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.baselib.runlog.OobLocalActionLedger
import cn.com.omnimind.bot.runlog.OmniflowActionBackend
import cn.com.omnimind.omniintelligence.models.ScrollDirection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VlmActExecutorTest {
    @Test
    fun `dispatch records successful mcp local action`() = runBlocking {
        OobLocalActionLedger.resetForTesting()
        val backend = FakeBackend(xml = SAFE_XML)
        try {
            VlmActExecutor(
                backendProvider = { backend },
                actionSource = "mcp_act",
            ).dispatch(
                action = "click",
                args = mapOf(
                    "x" to 120,
                    "y" to 240,
                    "target_description" to "Open menu",
                ),
            )

            assertEquals(listOf(120f to 240f), backend.clicks)
            val record = OobLocalActionLedger.recentRecordsForTesting().single()
            assertEquals("mcp_act", record.source)
            assertEquals("click", record.tool)
            assertEquals(true, record.success)
            assertEquals(false, record.blocked)
            assertEquals("com.example", record.packageName)
            assertTrue(record.beforeXmlSha256.isNotBlank())
        } finally {
            OobLocalActionLedger.resetForTesting()
        }
    }

    @Test
    fun `dispatch allows slider swipe when checkers are disabled`() = runBlocking {
        OobLocalActionLedger.resetForTesting()
        val backend = FakeBackend(xml = CAPTCHA_XML)
        try {
            VlmActExecutor(
                backendProvider = { backend },
                actionSource = "mcp_act",
            ).dispatch(
                action = "swipe",
                args = mapOf(
                    "target_description" to "拖动下方滑块完成验证",
                    "x1" to 100,
                    "y1" to 900,
                    "x2" to 900,
                    "y2" to 900,
                ),
            )

            assertEquals(1, backend.swipes.size)
            val record = OobLocalActionLedger.recentRecordsForTesting().single()
            assertEquals("mcp_act", record.source)
            assertEquals("swipe", record.tool)
            assertEquals(true, record.success)
            assertEquals(false, record.blocked)
        } finally {
            OobLocalActionLedger.resetForTesting()
        }
    }

    @Test
    fun `dispatch records input text without leaking typed value`() = runBlocking {
        OobLocalActionLedger.resetForTesting()
        val backend = FakeBackend(xml = SAFE_XML)
        try {
            VlmActExecutor(
                backendProvider = { backend },
                actionSource = "mcp_act",
            ).dispatch(
                action = "input_text",
                args = mapOf(
                    "target_description" to "Search box",
                    "text" to "coffee near me",
                    "x" to 120,
                    "y" to 240,
                ),
            )

            assertEquals(listOf("coffee near me"), backend.inputTexts)
            val record = OobLocalActionLedger.recentRecordsForTesting().single()
            assertEquals("input_text", record.tool)
            assertEquals("<redacted>", record.args["text"])
            assertEquals(true, record.args["text_present"])
            assertEquals(14, record.args["text_length"])
            assertEquals(true, record.args["text_redacted"])
        } finally {
            OobLocalActionLedger.resetForTesting()
        }
    }

    private class FakeBackend(
        private val xml: String,
    ) : OmniflowActionBackend {
        val clicks = mutableListOf<Pair<Float, Float>>()
        val swipes = mutableListOf<Map<String, Any?>>()
        val inputTexts = mutableListOf<String>()

        override fun isReady(): Boolean = true

        override suspend fun click(x: Float, y: Float) {
            clicks += x to y
        }

        override suspend fun longPress(x: Float, y: Float, durationMs: Long) = Unit

        override suspend fun scroll(
            x: Float,
            y: Float,
            direction: ScrollDirection,
            distance: Float,
            durationMs: Long,
        ) {
            swipes += mapOf(
                "x" to x,
                "y" to y,
                "direction" to direction.name,
                "distance" to distance,
                "duration_ms" to durationMs,
            )
        }

        override suspend fun swipe(
            startX: Float,
            startY: Float,
            endX: Float,
            endY: Float,
            durationMs: Long,
            targetDescription: String,
        ) {
            swipes += mapOf(
                "x1" to startX,
                "y1" to startY,
                "x2" to endX,
                "y2" to endY,
                "duration_ms" to durationMs,
                "target_description" to targetDescription,
            )
        }

        override suspend fun inputTextToFocusedNode(text: String) {
            inputTexts += text
        }

        override suspend fun launchApplication(packageName: String) = Unit

        override suspend fun pressHotKey(key: String) = Unit

        override fun currentXml(): String = xml

        override fun currentPackageName(): String = "com.example"

        override fun currentActivityName(): String = "ExampleActivity"
    }

    private companion object {
        private const val SAFE_XML =
            """<hierarchy><node package="com.example" text="Open menu" bounds="[0,0][1080,1920]" /></hierarchy>"""
        private const val CAPTCHA_XML =
            """<hierarchy><node package="com.example" text="拖动下方滑块完成验证" bounds="[0,0][1080,1920]" /></hierarchy>"""
    }
}
