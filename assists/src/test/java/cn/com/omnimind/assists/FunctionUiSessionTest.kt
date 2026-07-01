package cn.com.omnimind.assists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FunctionUiSessionTest {
    @Test
    fun `requestStopSession matches child function task id`() {
        val runId = "function-run-${System.nanoTime()}"
        val childTaskId = "function-child-${System.nanoTime()}"
        val stopped = mutableListOf<String>()

        try {
            FunctionUiSession.registerRun(
                runId = runId,
                onStopRequested = { stopped += runId }
            )
            FunctionUiSession.beginTask(runId, childTaskId)

            assertTrue(FunctionUiSession.requestStopSession(childTaskId))
            assertEquals(listOf(runId), stopped)
            assertFalse(FunctionUiSession.requestStopSession(childTaskId))

            val end = FunctionUiSession.endRun(runId)
            assertTrue(end.wasActive)
            assertTrue(end.stopRequested)
            assertFalse(end.completeRequested)
        } finally {
            FunctionUiSession.endRun(runId)
        }
    }

    @Test
    fun `requestStopSession without id stops every active function session`() {
        val firstRunId = "function-run-a-${System.nanoTime()}"
        val secondRunId = "function-run-b-${System.nanoTime()}"
        val stopped = mutableListOf<String>()

        try {
            FunctionUiSession.registerRun(
                runId = firstRunId,
                onStopRequested = { stopped += firstRunId }
            )
            FunctionUiSession.beginTask(firstRunId, "function-child-a-${System.nanoTime()}")
            FunctionUiSession.registerRun(
                runId = secondRunId,
                onStopRequested = { stopped += secondRunId }
            )
            FunctionUiSession.beginTask(secondRunId, "function-child-b-${System.nanoTime()}")

            assertTrue(FunctionUiSession.requestStopSession(null))
            assertEquals(setOf(firstRunId, secondRunId), stopped.toSet())
        } finally {
            FunctionUiSession.endRun(firstRunId)
            FunctionUiSession.endRun(secondRunId)
        }
    }

    @Test
    fun `ended child task id cannot stop active function run`() {
        val runId = "function-run-active-${System.nanoTime()}"
        val childTaskId = "function-child-active-${System.nanoTime()}"
        val stopped = mutableListOf<String>()

        try {
            FunctionUiSession.registerRun(
                runId = runId,
                onStopRequested = { stopped += runId }
            )
            FunctionUiSession.beginTask(runId, childTaskId)
            FunctionUiSession.endTask(childTaskId)

            assertFalse(FunctionUiSession.requestStopSession(childTaskId))
            assertTrue(stopped.isEmpty())

            assertTrue(FunctionUiSession.requestStopSession(runId))
            assertEquals(listOf(runId), stopped)
        } finally {
            FunctionUiSession.endRun(runId)
        }
    }

    @Test
    fun `requestCompleteActiveSession invokes completion callback once`() {
        val runId = "function-run-complete-${System.nanoTime()}"
        val completed = mutableListOf<String>()
        val stopped = mutableListOf<String>()

        try {
            FunctionUiSession.registerRun(
                runId = runId,
                onStopRequested = { stopped += runId },
                onCompleteRequested = { completed += runId }
            )
            FunctionUiSession.beginTask(runId, "function-child-complete-${System.nanoTime()}")

            assertTrue(FunctionUiSession.requestCompleteActiveSession())
            assertEquals(listOf(runId), completed)
            assertTrue(stopped.isEmpty())
            assertFalse(FunctionUiSession.requestCompleteActiveSession())
            assertFalse(FunctionUiSession.requestStopSession(runId))

            val end = FunctionUiSession.endRun(runId)
            assertTrue(end.wasActive)
            assertTrue(end.completeRequested)
            assertFalse(end.stopRequested)
        } finally {
            FunctionUiSession.endRun(runId)
        }
    }

    @Test
    fun `requestCompleteActiveSession falls back to stop callback`() {
        val runId = "function-run-complete-fallback-${System.nanoTime()}"
        val stopped = mutableListOf<String>()

        try {
            FunctionUiSession.registerRun(
                runId = runId,
                onStopRequested = { stopped += runId }
            )
            FunctionUiSession.beginTask(runId, "function-child-complete-fallback-${System.nanoTime()}")

            assertTrue(FunctionUiSession.requestCompleteActiveSession())
            assertEquals(listOf(runId), stopped)
            assertFalse(FunctionUiSession.requestStopSession(runId))

            val end = FunctionUiSession.endRun(runId)
            assertTrue(end.wasActive)
            assertTrue(end.completeRequested)
            assertFalse(end.stopRequested)
        } finally {
            FunctionUiSession.endRun(runId)
        }
    }
}
