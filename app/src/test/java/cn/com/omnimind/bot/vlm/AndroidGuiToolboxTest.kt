package cn.com.omnimind.bot.vlm

import cn.com.omnimind.assists.task.vlmserver.DeviceOperator
import cn.com.omnimind.assists.task.vlmserver.VLMRecallContextProviderRegistry
import cn.com.omnimind.assists.task.vlmserver.VlmTaskEngineHost
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import cn.com.omnimind.baselib.runlog.OobActionSchema
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.NoOpAgentRunControl
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.omniflow.OmniFlowPythonHostCall
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class AndroidGuiToolboxTest {
    @After
    fun clearRecallProvider() {
        VLMRecallContextProviderRegistry.clear()
    }

    @Test
    fun primitiveActionWritesOneCanonicalRunLogStep() = runBlocking {
        val host = RecordingHost()
        val states = ArrayDeque(listOf(state("state-before"), state("state-after")))
        val toolbox = toolbox(
            host = host,
            androidHost = OmniFlowPythonHostCall { method, _ ->
                when (method) {
                    "observe" -> states.removeFirst()
                    "act" -> mapOf("success" to true)
                    else -> error("unexpected_host_call:$method")
                }
            },
        )
        toolbox.prepare()
        toolbox.onTurn(
            ChatCompletionTurn(
                message = ChatCompletionMessage(
                    role = "assistant",
                    content = JsonPrimitive("{\"summary\":\"等待页面稳定\"}"),
                ),
                reasoning = "页面仍在加载",
                finishReason = "tool_calls",
            )
        )

        val args = JsonObject(mapOf("duration_ms" to JsonPrimitive(1000)))
        val result = toolbox.execute(
            toolCall = toolCall(OobActionSchema.TOOL_WAIT, "{\"duration_ms\":1000}"),
            args = args,
            runtimeDescriptor = toolbox.runtimeDescriptor(OobActionSchema.TOOL_WAIT),
            env = unusedProxy(),
            callback = unusedProxy(),
            toolHandle = NoOpAgentRunControl.beginToolExecution(OobActionSchema.TOOL_WAIT, "call-1"),
        )

        assertTrue(result is ToolExecutionResult.ContextResult && result.success)
        assertEquals(1, host.steps.size)
        val step = host.steps.single()
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
        assertEquals("state-before", step["before_state_id"])
        assertEquals("state-after", step["after_state_id"])
        assertEquals(true, step.map("result")["success"])
        assertEquals(OobActionSchema.TOOL_WAIT, step.map("action")["tool"])
        val metadata = step.map("metadata")
        assertEquals("等待页面稳定", metadata["summary"])
        assertEquals("页面仍在加载", metadata["thinking"])
        assertEquals("succeeded", metadata["status"])
        assertEquals("run-test-vlm-0", metadata["step_id"])
        assertFalse(step.containsKey("summary"))
        assertFalse(step.containsKey("thinking"))
        assertFalse(step.containsKey("status"))
        assertFalse(step.containsKey("step_id"))
    }

    @Test
    fun hostFailureReturnsToolErrorWithoutWritingFalseStep() = runBlocking {
        val host = RecordingHost()
        val toolbox = toolbox(
            host = host,
            androidHost = OmniFlowPythonHostCall { method, _ ->
                when (method) {
                    "observe" -> state("state-before")
                    "act" -> error("accessibility_disconnected")
                    else -> error("unexpected_host_call:$method")
                }
            },
        )
        toolbox.prepare()

        val result = toolbox.execute(
            toolCall = toolCall(OobActionSchema.TOOL_WAIT, "{\"duration_ms\":1000}"),
            args = JsonObject(mapOf("duration_ms" to JsonPrimitive(1000))),
            runtimeDescriptor = toolbox.runtimeDescriptor(OobActionSchema.TOOL_WAIT),
            env = unusedProxy(),
            callback = unusedProxy(),
            toolHandle = NoOpAgentRunControl.beginToolExecution(OobActionSchema.TOOL_WAIT, "call-1"),
        )

        assertTrue(result is ToolExecutionResult.Error)
        assertEquals("accessibility_disconnected", (result as ToolExecutionResult.Error).message)
        assertTrue(host.steps.isEmpty())
    }

    @Test
    fun finishedStopsWithoutCreatingRunLogAction() = runBlocking {
        val host = RecordingHost()
        val toolbox = toolbox(
            host = host,
            androidHost = OmniFlowPythonHostCall { method, _ ->
                when (method) {
                    "observe" -> state("state-current")
                    else -> error("unexpected_host_call:$method")
                }
            },
        )
        toolbox.prepare()

        val result = toolbox.execute(
            toolCall = toolCall(OobActionSchema.TOOL_FINISHED, "{\"content\":\"任务完成\"}"),
            args = JsonObject(mapOf("content" to JsonPrimitive("任务完成"))),
            runtimeDescriptor = toolbox.runtimeDescriptor(OobActionSchema.TOOL_FINISHED),
            env = unusedProxy(),
            callback = unusedProxy(),
            toolHandle = NoOpAgentRunControl.beginToolExecution(OobActionSchema.TOOL_FINISHED, "call-1"),
        )

        assertEquals(ToolExecutionResult.ChatMessage("任务完成"), result)
        assertEquals(true, toolbox.terminal?.success)
        assertEquals("finished", toolbox.terminal?.reason)
        assertTrue(host.steps.isEmpty())
    }

    private fun toolbox(
        host: RecordingHost,
        androidHost: OmniFlowPythonHostCall,
    ): AndroidGuiToolbox = AndroidGuiToolbox(
        context = null,
        config = AndroidGuiTaskConfig(
            runId = "run-test",
            goal = "等待页面加载完成",
            model = "scene.vlm.operation.primary",
            maxSteps = 5,
            packageName = "com.example",
            stepSkillGuidance = "",
            disableFunctionRecall = true,
        ),
        host = host,
        installedApps = { emptyMap() },
        androidHostOverride = androidHost,
    )

    private fun state(stateId: String): Map<String, Any?> = linkedMapOf(
        "state_id" to stateId,
        "package_name" to "com.example",
        "activity_name" to "MainActivity",
        "display" to mapOf("width" to 1080, "height" to 2400),
        "xml" to "<hierarchy />",
    )

    private fun toolCall(tool: String, arguments: String): AssistantToolCall = AssistantToolCall(
        id = "call-1",
        function = AssistantToolCallFunction(name = tool, arguments = arguments),
    )

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> unusedProxy(): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, method, _ ->
        if (method.name == "getRunControl") NoOpAgentRunControl else error("unused:${method.name}")
    } as T

    private fun Map<String, Any?>.map(key: String): Map<String, Any?> =
        (get(key) as Map<*, *>).entries.associate { (name, value) -> name.toString() to value }

    private inner class RecordingHost : VlmTaskEngineHost {
        override val deviceOperator: DeviceOperator = unusedProxy()
        val steps = mutableListOf<Map<String, Any?>>()

        override suspend fun beforeStep() = Unit

        override suspend fun requestUserInput(question: String): String = "continue"

        override suspend fun onModelTurn(metadata: Map<String, Any?>) = Unit

        override suspend fun onActionStarted(
            action: Map<String, Any?>,
            metadata: Map<String, Any?>,
        ) = Unit

        override suspend fun recordStep(step: Map<String, Any?>) {
            steps += step
        }
    }
}
