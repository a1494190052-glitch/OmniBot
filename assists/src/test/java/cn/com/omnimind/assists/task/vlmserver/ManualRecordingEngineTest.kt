package cn.com.omnimind.assists.task.vlmserver

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualRecordingEngineTest {
    @Test
    fun serializesActionsAndCommitsInReceiveOrder() = runBlocking {
        val events = mutableListOf<String>()
        val journal = ManualRecordingJournal()
        val engine = ManualRecordingEngine(
            journal = journal,
            observe = { stage, command ->
                events += "$stage:${command.action.tool}"
                ManualRecordingObservation(xml = "<$stage/>")
            },
            execute = { command ->
                events += "execute:${command.action.tool}"
                if (command.action.tool == "click") delay(20)
                OperationResult(true, "ok")
            },
            nowMs = { 200L },
        )

        val first = async { engine.perform(action("click", 100L)) }
        delay(5)
        val second = async { engine.perform(action("swipe", 110L)) }

        assertTrue(first.await().recorded)
        assertTrue(second.await().recorded)
        assertEquals(listOf("click", "swipe"), journal.snapshot().map { it.action.tool })
        assertEquals(
            listOf(
                "1_before:click", "execute:click", "1_after:click",
                "2_before:swipe", "execute:swipe", "2_after:swipe",
            ),
            events,
        )
        assertEquals(ManualRecordingEngineStats(2, 2, 0, 0, null), engine.stats())
    }

    @Test
    fun observationFailureDoesNotDropExecutedAction() = runBlocking {
        val journal = ManualRecordingJournal()
        val engine = ManualRecordingEngine(
            journal = journal,
            observe = { _, _ -> error("xml unavailable") },
            execute = { OperationResult(true, "ok") },
            nowMs = { 200L },
        )

        val outcome = engine.perform(action("click", 100L))

        assertTrue(outcome.executed)
        assertTrue(outcome.recorded)
        assertEquals(1, journal.size())
        assertEquals(null, journal.lastOrNull()?.beforeXml)
    }

    @Test
    fun failedDispatchIsCountedButNotPersistedAsReplayStep() = runBlocking {
        val journal = ManualRecordingJournal()
        val engine = ManualRecordingEngine(
            journal = journal,
            observe = { _, _ -> ManualRecordingObservation() },
            execute = { OperationResult(false, "dispatch failed") },
        )

        val outcome = engine.perform(action("click", 100L))

        assertFalse(outcome.executed)
        assertFalse(outcome.recorded)
        assertEquals(0, journal.size())
        assertEquals(ManualRecordingEngineStats(1, 0, 1, 0, null), engine.stats())
    }

    @Test
    fun retriesUnchangedAfterStateUntilFreshXmlArrives() = runBlocking {
        val journal = ManualRecordingJournal()
        var afterAttempts = 0
        val engine = ManualRecordingEngine(
            journal = journal,
            observe = { stage, _ ->
                if (stage.endsWith("_before")) {
                    ManualRecordingObservation(xml = "<page name=\"before\"/>")
                } else {
                    afterAttempts += 1
                    ManualRecordingObservation(
                        xml = if (afterAttempts < 3) {
                            "<page name=\"before\"/>"
                        } else {
                            "<page name=\"after\"/>"
                        },
                    )
                }
            },
            execute = { OperationResult(true, "ok") },
        )

        val outcome = engine.perform(action("click", 100L))

        assertTrue(outcome.recorded)
        assertEquals(3, afterAttempts)
        assertEquals("<page name=\"after\"/>", journal.lastOrNull()?.afterXml)
    }

    @Test
    fun waitDoesNotRequireAfterStateToChange() = runBlocking {
        val journal = ManualRecordingJournal()
        var observationCount = 0
        val engine = ManualRecordingEngine(
            journal = journal,
            observe = { _, _ ->
                observationCount += 1
                ManualRecordingObservation(xml = "<page/>")
            },
            execute = { OperationResult(true, "ok") },
        )

        val outcome = engine.perform(action("wait", 100L))

        assertTrue(outcome.recorded)
        assertEquals(2, observationCount)
        assertEquals("<page/>", journal.lastOrNull()?.afterXml)
    }

    @Test
    fun unchangedClickStateIsStillRecordedAfterRetries() = runBlocking {
        val journal = ManualRecordingJournal()
        var observationCount = 0
        val engine = ManualRecordingEngine(
            journal = journal,
            observe = { _, _ ->
                observationCount += 1
                ManualRecordingObservation(xml = "<page/>")
            },
            execute = { OperationResult(true, "ok") },
        )

        val outcome = engine.perform(action("click", 100L))

        assertTrue(outcome.recorded)
        assertEquals(6, observationCount)
        assertEquals("<page/>", journal.lastOrNull()?.afterXml)
    }

    @Test
    fun cancellationClosesPendingActionWithoutSwallowingIt() = runBlocking {
        val engine = ManualRecordingEngine(
            journal = ManualRecordingJournal(),
            observe = { _, _ -> ManualRecordingObservation() },
            execute = { throw CancellationException("cancelled") },
        )

        var cancelled = false
        try {
            engine.perform(action("click", 100L))
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertEquals(ManualRecordingEngineStats(1, 0, 1, 0, null), engine.stats())
    }

    private fun action(tool: String, startedAtMs: Long) = ManualRecordingCommand(
        action = actionOf(tool),
        title = tool,
        summary = tool,
        source = "overlay_touch",
        startedAtMs = startedAtMs,
    )
}
