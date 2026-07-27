package cn.com.omnimind.bot.omniflow.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ExecutionStatusStateTest {
    @Test
    fun `execution starts from whether a model is available`() {
        assertEquals(ExecutionPhase.REASONING, initialExecutionPhase(usesModel = true))
        assertEquals(ExecutionPhase.AUTOMATIC, initialExecutionPhase(usesModel = false))
    }

    @Test
    fun `VLM execution starts in reasoning then switches around automatic actions`() {
        val status = ExecutionStatusState(ExecutionPhase.REASONING)

        assertEquals("智能推理", status.label)

        status.updatePhase(ExecutionPhase.AUTOMATIC)
        assertEquals("自动执行", status.label)

        status.updatePhase(ExecutionPhase.REASONING)
        assertEquals("智能推理", status.label)
    }

    @Test
    fun `resuming preserves the active execution phase`() {
        val status = ExecutionStatusState(ExecutionPhase.AUTOMATIC)

        status.setPaused(true)
        assertEquals("已暂停，可手动操作", status.label)

        status.updatePhase(ExecutionPhase.REASONING)
        assertEquals("已暂停，可手动操作", status.label)

        status.setPaused(false)
        assertEquals("智能推理", status.label)
    }
}
