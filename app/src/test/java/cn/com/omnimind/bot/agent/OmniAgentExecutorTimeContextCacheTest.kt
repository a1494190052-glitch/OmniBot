package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.i18n.PromptLocale
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
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
}
