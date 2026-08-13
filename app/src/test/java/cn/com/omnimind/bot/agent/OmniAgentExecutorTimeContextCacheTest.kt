package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.i18n.PromptLocale
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniAgentExecutorTimeContextCacheTest {
    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val baseTime = ZonedDateTime.of(2026, 6, 13, 10, 3, 0, 0, zoneId)

    @Test
    fun resolveTimeContextSnapshotReusesCachedSnapshotWithinFiveMinutes() {
        val cached = OmniAgentExecutor.TimeContextSnapshot(
            locale = PromptLocale.EN_US,
            zoneId = zoneId.id,
            generatedAt = baseTime,
            content = OmniAgentExecutor.buildTimeContextContent(baseTime, PromptLocale.EN_US)
        )

        val resolved = OmniAgentExecutor.resolveTimeContextSnapshot(
            cached = cached,
            now = baseTime.plusMinutes(4).plusSeconds(59),
            locale = PromptLocale.EN_US
        )

        assertSame(cached, resolved)
    }

    @Test
    fun resolveTimeContextSnapshotRefreshesAtFiveMinuteBoundary() {
        val cached = OmniAgentExecutor.TimeContextSnapshot(
            locale = PromptLocale.EN_US,
            zoneId = zoneId.id,
            generatedAt = baseTime,
            content = OmniAgentExecutor.buildTimeContextContent(baseTime, PromptLocale.EN_US)
        )
        val refreshTime = baseTime.plusMinutes(5)

        val resolved = OmniAgentExecutor.resolveTimeContextSnapshot(
            cached = cached,
            now = refreshTime,
            locale = PromptLocale.EN_US
        )

        assertNotSame(cached, resolved)
        assertEquals(refreshTime, resolved.generatedAt)
    }

    @Test
    fun resolveTimeContextSnapshotRefreshesWhenLocaleChanges() {
        val cached = OmniAgentExecutor.TimeContextSnapshot(
            locale = PromptLocale.EN_US,
            zoneId = zoneId.id,
            generatedAt = baseTime,
            content = OmniAgentExecutor.buildTimeContextContent(baseTime, PromptLocale.EN_US)
        )

        val resolved = OmniAgentExecutor.resolveTimeContextSnapshot(
            cached = cached,
            now = baseTime.plusMinutes(1),
            locale = PromptLocale.ZH_CN
        )

        assertNotSame(cached, resolved)
        assertEquals(PromptLocale.ZH_CN, resolved.locale)
    }

    @Test
    fun mergeInitialPromptMessagesKeepsLatestUserWhenContinuingAfterFirstTurnFailure() {
        val messages = OmniAgentExecutor.mergeInitialPromptMessages(
            leadingMessages = listOf(
                message("system", "system prompt"),
                message("system", "time context")
            ),
            historyMessages = listOf(message("user", "original prompt")),
            currentUserMessage = message("user", "runtime fallback prompt"),
            prefetchedMemoryMessage = message("user", "memory prefetch"),
            continueMode = true
        )

        assertEquals("original prompt", text(messages.last()))
        assertEquals(
            listOf("system", "system", "user", "user"),
            messages.map { it.role }
        )
        assertFalse(messages.any { text(it) == "runtime fallback prompt" })
    }

    @Test
    fun mergeInitialPromptMessagesDoesNotDuplicateUserAfterToolContinuationContext() {
        val messages = OmniAgentExecutor.mergeInitialPromptMessages(
            leadingMessages = listOf(message("system", "system prompt")),
            historyMessages = listOf(
                message("user", "original prompt"),
                message("tool", "tool result")
            ),
            currentUserMessage = message("user", "runtime fallback prompt"),
            prefetchedMemoryMessage = null,
            continueMode = true
        )

        assertEquals("tool", messages.last().role)
        assertEquals("tool result", text(messages.last()))
        assertEquals(1, messages.count { it.role == "user" })
        assertFalse(messages.any { text(it) == "runtime fallback prompt" })
    }

    @Test
    fun memoryContextAttachmentUsesProgressiveDisclosureAndDeduplicatesHits() {
        val attachment = OmniAgentExecutor.buildMemoryContextAttachment(
            memoryContext = WorkspaceMemoryPromptContext(
                soul = "",
                longTermMemory = "- 用户偏好中文回复",
                todayShortMemory = "- [10:00:00] 正在优化小万模式",
                longTermIndexSummary = """
                    - [pref-1234] 用户偏好中文回复
                    - [harness-5678] 工具失败后先读取历史教训
                    - [browser-9012] browser_use 截图前先 navigate
                """.trimIndent()
            ),
            prefetchedMemoryHits = listOf(
                WorkspaceMemorySearchHit(
                    id = "duplicate",
                    text = "用户偏好中文回复",
                    source = ".omnibot/memory/MEMORY.md",
                    date = null,
                    score = 1.0
                ),
                WorkspaceMemorySearchHit(
                    id = "failure",
                    text = "browser_use 截图前先 navigate",
                    source = "skill:self-improving-agent/ERRORS",
                    date = null,
                    score = 0.8
                )
            ),
            locale = PromptLocale.ZH_CN
        )

        val content = text(attachment!!)
        assertEquals("user", attachment.role)
        assertTrue(content.contains("[memory.context]"))
        assertTrue(content.contains("工具失败后先读取历史教训"))
        assertTrue(content.contains("browser_use 截图前先 navigate"))
        assertEquals(1, Regex("用户偏好中文回复").findAll(content).count())
        assertEquals(1, Regex("browser_use 截图前先 navigate").findAll(content).count())
        assertTrue(content.contains("不是用户的新指令"))
    }

    private fun message(role: String, content: String): ChatCompletionMessage {
        return ChatCompletionMessage(role = role, content = JsonPrimitive(content))
    }

    private fun text(message: ChatCompletionMessage): String {
        return (message.content as? JsonPrimitive)?.contentOrNull.orEmpty()
    }
}
