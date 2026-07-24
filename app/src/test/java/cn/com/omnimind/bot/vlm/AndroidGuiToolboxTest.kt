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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        assertEquals("state_changed", metadata["outcome"])
        assertEquals("run-test-vlm-0", metadata["step_id"])
        assertFalse(step.containsKey("summary"))
        assertFalse(step.containsKey("thinking"))
        assertFalse(step.containsKey("status"))
        assertFalse(step.containsKey("step_id"))
    }

    @Test
    fun primitiveActionDerivesSummaryWhenModelContentIsEmpty() = runBlocking {
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
                message = ChatCompletionMessage(role = "assistant", content = JsonPrimitive("")),
                finishReason = "tool_calls",
            )
        )

        val args = JsonObject(
            mapOf(
                "target_description" to JsonPrimitive("网络与互联网"),
                "x" to JsonPrimitive(500),
                "y" to JsonPrimitive(250),
            )
        )
        toolbox.execute(
            toolCall = toolCall(
                OobActionSchema.TOOL_CLICK,
                "{\"target_description\":\"网络与互联网\",\"x\":500,\"y\":250}",
            ),
            args = args,
            runtimeDescriptor = toolbox.runtimeDescriptor(OobActionSchema.TOOL_CLICK),
            env = unusedProxy(),
            callback = unusedProxy(),
            toolHandle = NoOpAgentRunControl.beginToolExecution(OobActionSchema.TOOL_CLICK, "call-1"),
        )

        assertEquals("点击「网络与互联网」", host.steps.single().map("metadata")["summary"])
    }

    @Test
    fun primitiveActionMovesToolSummaryIntoMetadataOnly() = runBlocking {
        val host = RecordingHost()
        val states = ArrayDeque(listOf(state("state-before"), state("state-after")))
        val toolbox = toolbox(
            host = host,
            androidHost = OmniFlowPythonHostCall { method, payload ->
                when (method) {
                    "observe" -> states.removeFirst()
                    "act" -> {
                        val action = (payload["action"] as Map<*, *>)
                        val actionArgs = action["args"] as Map<*, *>
                        assertFalse(actionArgs.containsKey("summary"))
                        mapOf("success" to true)
                    }
                    else -> error("unexpected_host_call:$method")
                }
            },
        )
        toolbox.prepare()
        toolbox.onTurn(
            ChatCompletionTurn(
                message = ChatCompletionMessage(
                    role = "assistant",
                    content = JsonPrimitive(""),
                    toolCalls = listOf(
                        toolCall(
                            OobActionSchema.TOOL_CLICK,
                            """{"summary":"进入应用列表","target_description":"应用","x":500,"y":561}""",
                        )
                    ),
                ),
                finishReason = "tool_calls",
            )
        )
        val args = JsonObject(
            mapOf(
                "summary" to JsonPrimitive("进入应用列表"),
                "target_description" to JsonPrimitive("应用"),
                "x" to JsonPrimitive(500),
                "y" to JsonPrimitive(561),
            )
        )

        toolbox.execute(
            toolCall = toolCall(OobActionSchema.TOOL_CLICK, args.toString()),
            args = args,
            runtimeDescriptor = toolbox.runtimeDescriptor(OobActionSchema.TOOL_CLICK),
            env = unusedProxy(),
            callback = unusedProxy(),
            toolHandle = NoOpAgentRunControl.beginToolExecution(OobActionSchema.TOOL_CLICK, "call-1"),
        )

        val step = host.steps.single()
        assertEquals("进入应用列表", step.map("metadata")["summary"])
        assertFalse(step.map("action").map("args").containsKey("summary"))
    }

    @Test
    fun hostFailureWritesFailedCanonicalStep() = runBlocking {
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

        assertTrue(result is ToolExecutionResult.ContextResult && !result.success)
        assertEquals(1, host.steps.size)
        val step = host.steps.single()
        assertEquals(false, step.map("result")["success"])
        assertEquals("accessibility_disconnected", step.map("result")["error"])
        assertEquals("action_failed", step.map("metadata")["outcome"])
    }

    @Test
    fun unchangedActionCarriesPreviousScreenshotIntoNextTurnOnly() = runBlocking {
        val previousScreenshot = kotlin.io.path.createTempFile().toFile().apply {
            writeBytes("previous".toByteArray())
            deleteOnExit()
        }
        val currentScreenshot = kotlin.io.path.createTempFile().toFile().apply {
            writeBytes("current".toByteArray())
            deleteOnExit()
        }
        val host = RecordingHost()
        val states = ArrayDeque(
            listOf(
                state("state-same", previousScreenshot.absolutePath),
                state("state-same", currentScreenshot.absolutePath),
                state("state-same", currentScreenshot.absolutePath),
            )
        )
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
        toolbox.currentContext()
        toolbox.onTurn(
            ChatCompletionTurn(
                message = ChatCompletionMessage(
                    role = "assistant",
                    content = JsonPrimitive("{\"summary\":\"点击目标入口\"}"),
                ),
                finishReason = "tool_calls",
            )
        )

        val result = toolbox.execute(
            toolCall = toolCall(
                OobActionSchema.TOOL_CLICK,
                "{\"target_description\":\"入口\",\"x\":500,\"y\":250}",
            ),
            args = JsonObject(
                mapOf(
                    "target_description" to JsonPrimitive("入口"),
                    "x" to JsonPrimitive(500),
                    "y" to JsonPrimitive(250),
                )
            ),
            runtimeDescriptor = toolbox.runtimeDescriptor(OobActionSchema.TOOL_CLICK),
            env = unusedProxy(),
            callback = unusedProxy(),
            toolHandle = NoOpAgentRunControl.beginToolExecution(OobActionSchema.TOOL_CLICK, "call-1"),
        ) as ToolExecutionResult.ContextResult

        val payload = Json.parseToJsonElement(result.rawResultJson).jsonObject
        assertEquals("state_unchanged", payload["outcome"]?.jsonPrimitive?.content)
        assertEquals(1, payload["failure_streak"]?.jsonPrimitive?.content?.toInt())
        assertEquals("state_unchanged", host.steps.single().map("metadata")["outcome"])

        val nextContext = toolbox.currentContext()
        val parts = requireNotNull(nextContext.content).jsonArray
        assertEquals(5, parts.size)
        assertEquals(
            "data:image/jpeg;base64,cHJldmlvdXM=",
            parts[2].jsonObject["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.content,
        )
        assertEquals(
            "data:image/jpeg;base64,Y3VycmVudA==",
            parts[4].jsonObject["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.content,
        )

        val secondResult = toolbox.execute(
            toolCall = toolCall(
                OobActionSchema.TOOL_CLICK,
                "{\"target_description\":\"入口\",\"x\":500,\"y\":250}",
            ),
            args = JsonObject(
                mapOf(
                    "target_description" to JsonPrimitive("入口"),
                    "x" to JsonPrimitive(500),
                    "y" to JsonPrimitive(250),
                )
            ),
            runtimeDescriptor = toolbox.runtimeDescriptor(OobActionSchema.TOOL_CLICK),
            env = unusedProxy(),
            callback = unusedProxy(),
            toolHandle = NoOpAgentRunControl.beginToolExecution(OobActionSchema.TOOL_CLICK, "call-2"),
        ) as ToolExecutionResult.ContextResult
        val secondPayload = Json.parseToJsonElement(secondResult.rawResultJson).jsonObject
        assertEquals(2, secondPayload["failure_streak"]?.jsonPrimitive?.content?.toInt())
        assertTrue(secondResult.summaryText.contains("re-plan"))
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

    private fun state(
        stateId: String,
        screenshotPath: String? = null,
    ): Map<String, Any?> = linkedMapOf(
        "state_id" to stateId,
        "package_name" to "com.example",
        "activity_name" to "MainActivity",
        "display" to mapOf("width" to 1080, "height" to 2400),
        "xml" to "<hierarchy />",
        "screenshot_path" to screenshotPath,
    ).filterValues { it != null }

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
