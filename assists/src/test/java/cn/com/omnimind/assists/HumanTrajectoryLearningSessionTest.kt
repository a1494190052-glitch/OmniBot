package cn.com.omnimind.assists

import cn.com.omnimind.assists.task.recording.ManualRecordedAction
import cn.com.omnimind.baselib.runlog.State
import cn.com.omnimind.baselib.runlog.RunLogStepRecord
import cn.com.omnimind.baselib.runlog.RunLogWriter
import cn.com.omnimind.baselib.runlog.actionOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

class HumanTrajectoryLearningSessionTest {
    @Test
    fun failedManualInputIsRetainedAsCanonicalFailedStep() = runBlocking {
        val action = ManualRecordedAction(
            action = actionOf("input_text", mapOf("text" to "拿铁咖啡", "x" to 500, "y" to 300)),
            title = "输入文字",
            beforeState = state("input-before", "demo.app", "<hierarchy/>"),
            afterState = state("input-after", "demo.app", "<hierarchy/>"),
            startedAtMs = 100L,
            finishedAtMs = 200L,
            summary = "输入文字：拿铁咖啡",
            operationSuccess = false,
            operationError = "input_target_not_found",
        )

        val fact = HumanTrajectoryLearningSession.buildRunLogFact("manual-run", 0, action)
        val result = fact.getValue("result") as Map<*, *>
        val metadata = fact.getValue("metadata") as Map<*, *>

        assertEquals(false, result["success"])
        assertEquals("input_target_not_found", result["error"])
        assertEquals("failed", metadata["status"])
        assertEquals("拿铁咖啡", (fact.getValue("action") as Map<*, *>).let {
            (it["args"] as Map<*, *>)["text"]
        })
        assertFalse(manualOperationFailuresResolved(listOf(action)))
        assertEquals(
            true,
            manualOperationFailuresResolved(
                listOf(action, action.copy(operationSuccess = true, operationError = null)),
            ),
        )
    }

    @Test
    fun newManualRunLogUsesCanonicalExecutionFacts() = runBlocking {
        val action = ManualRecordedAction(
            action = actionOf(
                "click",
                mapOf(
                    "x" to 500,
                    "y" to 516.098,
                ),
            ),
            title = "点击搜索",
            beforeState = state(
                stateId = "manual-run-human-0-before",
                packageName = "demo.before",
                xml = "<hierarchy><node text=\"before\" bounds=\"[0,0][1440,3168]\" /></hierarchy>",
            ),
            afterState = state(
                stateId = "manual-run-human-0-after",
                packageName = "demo.after",
                xml = "<hierarchy><node text=\"after\" bounds=\"[0,0][1440,3168]\" /></hierarchy>",
            ),
            startedAtMs = 100L,
            finishedAtMs = 200L,
            summary = "点击搜索",
            displayWidth = 1440,
            displayHeight = 3168,
        )

        var record: RunLogStepRecord? = null
        val writer = RunLogWriter { record = it }
        writer.write(
            fact = HumanTrajectoryLearningSession.buildRunLogFact(
                runId = "manual-run",
                index = 0,
                action = action,
            ),
            states = manualRunLogStates(action),
        )
        val saved = requireNotNull(record)

        assertEquals(2, saved.states.size)
        assertEquals(
            setOf(
                "step_index",
                "before_state_id",
                "action",
                "result",
                "after_state_id",
                "metadata",
            ),
            saved.step.keys,
        )
        assertEquals(0, saved.step["step_index"])
        assertEquals("manual-run-human-0-before", saved.step["before_state_id"])
        assertEquals("manual-run-human-0-after", saved.step["after_state_id"])
        assertFalse(saved.step.containsKey("coordinate_space"))
        assertStateInput(
            state = saved.states[0],
            expectedXml = action.beforeXml,
            expectedPackageName = "demo.before",
        )
        assertStateInput(
            state = saved.states[1],
            expectedXml = action.afterXml,
            expectedPackageName = "demo.after",
        )
        val recordedAction = saved.step.getValue("action") as Map<*, *>
        assertEquals("click", recordedAction["tool"])
        assertEquals(mapOf("x" to 500, "y" to 516.098), recordedAction["args"])
        assertFalse(saved.step.containsKey("before_state"))
        assertFalse(saved.step.containsKey("after_state"))
        assertFalse(saved.step.containsKey("state"))
        assertFalse(saved.step.containsKey("tool_call"))
        assertFalse(saved.step.containsKey("params"))
        assertFalse(saved.step.containsKey("source_context"))
    }

    private fun state(stateId: String, packageName: String, xml: String): State = State(
        stateId = stateId,
        packageName = packageName,
        activityName = "",
        displayWidth = 1440,
        displayHeight = 3168,
        xml = xml,
    )

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
