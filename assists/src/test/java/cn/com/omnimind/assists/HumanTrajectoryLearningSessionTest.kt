package cn.com.omnimind.assists

import cn.com.omnimind.assists.task.vlmserver.ManualVlmRecordedAction
import cn.com.omnimind.assists.task.vlmserver.actionOf
import cn.com.omnimind.assists.runlog.OmniFlowRecordStepExecutor
import cn.com.omnimind.baselib.runlog.RunLogStepRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

class HumanTrajectoryLearningSessionTest {
    @Test
    fun newManualRunLogDelegatesStepConstructionToOmniFlow() = runBlocking {
        val action = ManualVlmRecordedAction(
            action = actionOf(
                "click",
                mapOf(
                    "x" to 500,
                    "y" to 516.098,
                ),
            ),
            title = "点击搜索",
            beforePackageName = "demo.before",
            afterPackageName = "demo.after",
            beforeXml = "<hierarchy><node text=\"before\" bounds=\"[0,0][1440,3168]\" /></hierarchy>",
            afterXml = "<hierarchy><node text=\"after\" bounds=\"[0,0][1440,3168]\" /></hierarchy>",
            startedAtMs = 100L,
            finishedAtMs = 200L,
            summary = "点击搜索",
            displayWidth = 1440,
            displayHeight = 3168,
        )

        var inputRecord: RunLogStepRecord? = null
        val expectedStep = mapOf(
            "step_index" to 0,
            "before_state_id" to "manual-run-human-0-before",
            "action" to mapOf("tool" to "click", "args" to mapOf("x" to 500, "y" to 516)),
            "result" to mapOf("success" to true),
            "after_state_id" to "manual-run-human-0-after",
        )
        val step = HumanTrajectoryLearningSession.buildRunLogStep(
            runId = "manual-run",
            index = 0,
            action = action,
            recordStepExecutor = OmniFlowRecordStepExecutor {
                inputRecord = it
                it.copy(step = expectedStep)
            },
        )

        assertEquals(expectedStep, step.step)
        assertEquals(2, step.states.size)
        val input = requireNotNull(inputRecord)
        assertFalse(input.step.containsKey("coordinate_space"))
        assertStateInput(
            state = input.states[0],
            expectedXml = action.beforeXml,
            expectedPackageName = "demo.before",
        )
        assertStateInput(
            state = input.states[1],
            expectedXml = action.afterXml,
            expectedPackageName = "demo.after",
        )
        val recordedAction = input.step.getValue("action") as Map<*, *>
        assertEquals("click", recordedAction["tool"])
        assertEquals(mapOf("x" to 500L, "y" to 516.098), recordedAction["args"])
        assertFalse(input.step.containsKey("before_state"))
        assertFalse(input.step.containsKey("after_state"))
        assertFalse(input.step.containsKey("state"))
        assertFalse(input.step.containsKey("tool_call"))
        assertFalse(input.step.containsKey("params"))
        assertFalse(input.step.containsKey("source_context"))
    }

    private fun assertStateInput(
        state: Map<*, *>,
        expectedXml: String?,
        expectedPackageName: String,
    ) {
        assertEquals(expectedXml, state["xml"])
        assertEquals(expectedPackageName, state["package_name"])
        assertEquals(mapOf("width" to 1440, "height" to 3168), state["display"])
        assertFalse(state.containsKey("xml_path"))
        assertFalse(state.containsKey("xml_sha256"))
        assertFalse(state.containsKey("xml_chars"))
        assertFalse(state.containsKey("xml_bytes"))
        assertFalse(state.containsKey("display_width"))
        assertFalse(state.containsKey("display_height"))
    }
}
