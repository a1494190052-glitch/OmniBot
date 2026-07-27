package cn.com.omnimind.baselib.runlog

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RunLogWriterTest {
    @Test
    fun `writer accepts canonical execution fact maps`() = runBlocking {
        val records = mutableListOf<RunLogStepRecord>()
        val writer = RunLogWriter { records += it }

        writer.write(
            mapOf(
                "before_state_id" to "state-before",
                "action" to mapOf("tool" to "wait", "args" to mapOf("duration_ms" to 1000)),
                "result" to mapOf("success" to true),
                "after_state_id" to "state-after",
                "metadata" to mapOf("summary" to "等待页面稳定"),
            ),
        )

        assertEquals(0, records.single().step["step_index"])
        assertEquals("state-before", records.single().step["before_state_id"])
        assertEquals(mapOf("summary" to "等待页面稳定"), records.single().step["metadata"])
    }

    @Test
    fun `writer alone builds canonical steps from execution fact maps`() = runBlocking {
        val records = mutableListOf<RunLogStepRecord>()
        val writer = RunLogWriter { records += it }

        writer.write(
            mapOf(
                "before_state_id" to "state_before",
                "action" to actionOf("click", mapOf("x" to 100, "y" to 200)).asMap(),
                "result" to mapOf("success" to true),
                "after_state_id" to "state_after",
                "metadata" to mapOf("summary" to "点击目标"),
            ),
        )

        val step = records.single().step
        assertEquals(
            setOf(
                "step_index",
                "before_state_id",
                "action",
                "result",
                "after_state_id",
                "metadata",
            ),
            step.keys,
        )
        assertEquals(0, step["step_index"])
        assertEquals(mapOf("success" to true), step["result"])
        assertEquals(mapOf("summary" to "点击目标"), step["metadata"])
        assertFalse(step.containsKey("status"))
        assertEquals(1, writer.stepCount)
    }

    @Test
    fun `failed persistence does not consume a step index`() = runBlocking {
        val fact = mapOf(
            "before_state_id" to "state_before",
            "action" to actionOf("wait", mapOf("duration_ms" to 1000)).asMap(),
            "result" to mapOf("success" to true),
            "after_state_id" to "state_after",
        )
        var fail = true
        val records = mutableListOf<RunLogStepRecord>()
        val writer = RunLogWriter { record ->
            if (fail) error("disk_unavailable")
            records += record
        }

        assertEquals("disk_unavailable", runCatching { writer.write(fact) }.exceptionOrNull()?.message)
        assertEquals(0, writer.stepCount)

        fail = false
        writer.write(fact)

        assertEquals(0, records.single().step["step_index"])
        assertEquals(1, writer.stepCount)
    }
}
