package cn.com.omnimind.bot.vlm

import cn.com.omnimind.assists.task.vlmserver.FunctionRunAction
import cn.com.omnimind.assists.task.vlmserver.SceneChatCompletionTurn
import cn.com.omnimind.assists.task.vlmserver.UIContext
import cn.com.omnimind.assists.task.vlmserver.VLMCurrentPageSnapshot
import cn.com.omnimind.assists.task.vlmserver.VLMClient
import cn.com.omnimind.assists.task.vlmserver.VLMPageContextRequest
import cn.com.omnimind.assists.task.vlmserver.VLMRecallContextProvider
import cn.com.omnimind.assists.task.vlmserver.VLMRecallContextProviderRegistry
import cn.com.omnimind.assists.task.vlmserver.VLMStreamClient
import cn.com.omnimind.bot.mcp.PendingOmniFlowFunctionCall
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import cn.com.omnimind.baselib.llm.ModelSceneRegistry
import cn.com.omnimind.bot.mcp.TaskState
import cn.com.omnimind.bot.mcp.TaskStatus
import cn.com.omnimind.bot.mcp.VlmTaskRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VlmToolCoordinatorRecallExecutionTest {
    @Test
    fun `direct recall hit returns finished outcome when function succeeds`() = runBlocking {
        val state = TaskState(
            taskId = "task-recall-hit",
            goal = "open settings",
            status = TaskStatus.RUNNING,
        )
        state.omniflowRecall = mapOf(
            "decision" to "hit",
            "hit" to mapOf("function_id" to "open_settings_function")
        )
        val events = mutableListOf<Map<String, Any?>>()

        val outcome = VlmToolCoordinator.tryExecuteRecallHit(
            taskState = state,
            goal = state.goal,
            recallGuidance = VlmRecallGuidance(
                decision = "hit",
                guidance = "OmniFlow UDEG node skill-like decision context",
                payload = mapOf("success" to true),
                directHitFunctionId = "open_settings_function",
            ),
            progressReporter = { _, extras -> events += extras },
            runFunction = { functionId, _ ->
                mapOf(
                    "success" to true,
                    "fallback" to false,
                    "function_id" to functionId,
                    "run_id" to "omniflow_run_test",
                    "actions_executed" to 1,
                )
            },
        )

        assertNotNull(outcome)
        assertEquals(VlmToolOutcomeStatus.FINISHED, outcome?.status)
        assertEquals(TaskStatus.FINISHED, state.status)
        assertEquals("omniflow_recall_hit:open_settings_function", state.executionRoute)
        assertTrue(state.finishedContent?.contains("open_settings_function") == true)
        assertTrue(state.summaryText?.contains("actions_executed=1") == true)
        assertEquals("hit", outcome?.toPayload()?.get("omniflowRecall")?.let { it as Map<*, *> }?.get("decision"))
        assertEquals(2, events.size)
        assertEquals("FINISHED", events.last()["status"])
    }

    @Test
    fun `direct recall hit falls back to VLM when function needs fallback`() = runBlocking {
        val state = TaskState(
            taskId = "task-recall-fallback",
            goal = "open settings",
            status = TaskStatus.RUNNING,
        )
        val events = mutableListOf<Map<String, Any?>>()

        val outcome = VlmToolCoordinator.tryExecuteRecallHit(
            taskState = state,
            goal = state.goal,
            recallGuidance = VlmRecallGuidance(
                decision = "hit",
                guidance = "OmniFlow UDEG node skill-like decision context",
                payload = mapOf("success" to true),
                directHitFunctionId = "open_settings_function",
            ),
            progressReporter = { _, extras -> events += extras },
            runFunction = { _, _ ->
                mapOf(
                    "success" to false,
                    "fallback" to true,
                    "error" to "execution_failed",
                )
            },
        )

        assertNull(outcome)
        assertEquals(TaskStatus.RUNNING, state.status)
        assertEquals("vlm_with_omniflow_recall_fallback:hit", state.executionRoute)
        assertTrue(state.chatMessages.last().contains("continuing with VLM"))
        assertEquals(2, events.size)
        assertEquals("RUNNING", events.last()["status"])
    }

    @Test
    fun `non direct recall guidance does not attempt local execution`() = runBlocking {
        val state = TaskState(
            taskId = "task-recall-candidate",
            goal = "open settings",
            status = TaskStatus.RUNNING,
        )
        var called = false

        val outcome = VlmToolCoordinator.tryExecuteRecallHit(
            taskState = state,
            goal = state.goal,
            recallGuidance = VlmRecallGuidance(
                decision = "recall",
                guidance = "OmniFlow UDEG node skill-like decision context",
                payload = mapOf("success" to true),
                directHitFunctionId = null,
            ),
            progressReporter = { _, _ -> },
            runFunction = { _, _ ->
                called = true
                emptyMap()
            },
        )

        assertNull(outcome)
        assertEquals(false, called)
        assertEquals(TaskStatus.RUNNING, state.status)
    }

    @Test
    fun `request can disable omniflow recall for fresh VLM validation`() = runBlocking {
        val request = VlmTaskRequest(
            goal = "open settings",
            packageName = "com.android.settings",
            disableOmniFlowRecall = true,
        )

        val result = VlmToolCoordinator.buildRecallGuidanceAfterOptionalPrelaunch(
            context = cn.com.omnimind.bot.runlog.OobOmniFlowLoopAcceptanceTest.TempFilesContext(),
            request = request,
        )

        assertEquals("disabled", result.first.decision)
        assertTrue(result.first.guidance.isBlank())
        assertEquals(true, result.first.payload["recall_disabled"])
        assertEquals(request, result.second)
    }

    @Test
    fun `vlm requests default to recall context without function auto execution`() {
        val request = VlmTaskRequest(
            goal = "open settings",
            packageName = "com.android.settings",
        )

        assertEquals(false, request.allowOmniFlowFunctionAutoExecute)
    }

    @Test
    fun `recall hit is not executed unless request explicitly allows auto execution`() = runBlocking {
        val request = VlmTaskRequest(
            goal = "open settings",
            packageName = "com.android.settings",
        )
        val state = TaskState(
            taskId = "task-default-context-only",
            goal = request.goal,
            status = TaskStatus.RUNNING,
        )
        var called = false

        val outcome = VlmToolCoordinator.tryExecuteRecallHitIfAllowed(
            request = request,
            taskState = state,
            recallGuidance = VlmRecallGuidance(
                decision = "hit",
                guidance = "OmniFlow UDEG node skill-like decision context",
                payload = mapOf("success" to true),
                directHitFunctionId = "open_settings_function",
            ),
            progressReporter = { _, _ -> },
            runFunction = { _, _ ->
                called = true
                mapOf("success" to true)
            },
        )

        assertNull(outcome)
        assertEquals(false, called)
        assertEquals(TaskStatus.RUNNING, state.status)
        val gate = VlmToolCoordinator.evaluateFunctionCacheGate(
            request = request,
            recallGuidance = VlmRecallGuidance(
                decision = "hit",
                guidance = "OmniFlow UDEG node skill-like decision context",
                payload = mapOf("success" to true),
                directHitFunctionId = "open_settings_function",
            ),
        )
        assertFalse(gate.allowed)
        assertEquals(VlmToolCoordinator.CACHE_GATE_AUTO_EXECUTE_DISABLED, gate.reason)
    }

    @Test
    fun `recall hit executes when request explicitly allows auto execution`() = runBlocking {
        val request = VlmTaskRequest(
            goal = "open settings",
            packageName = "com.android.settings",
            allowOmniFlowFunctionAutoExecute = true,
        )
        val state = TaskState(
            taskId = "task-explicit-auto-execute",
            goal = request.goal,
            status = TaskStatus.RUNNING,
        )
        var called = false

        val outcome = VlmToolCoordinator.tryExecuteRecallHitIfAllowed(
            request = request,
            taskState = state,
            recallGuidance = VlmRecallGuidance(
                decision = "hit",
                guidance = "OmniFlow UDEG node skill-like decision context",
                payload = mapOf("success" to true),
                directHitFunctionId = "open_settings_function",
            ),
            progressReporter = { _, _ -> },
            runFunction = { _, _ ->
                called = true
                mapOf(
                    "success" to true,
                    "fallback" to false,
                    "function_id" to "open_settings_function",
                    "run_id" to "omniflow_run_test",
                    "actions_executed" to 1,
                )
            },
        )

        assertNotNull(outcome)
        assertEquals(true, called)
        assertEquals(TaskStatus.FINISHED, state.status)
        val gate = VlmToolCoordinator.evaluateFunctionCacheGate(
            request = request,
            recallGuidance = VlmRecallGuidance(
                decision = "hit",
                guidance = "OmniFlow UDEG node skill-like decision context",
                payload = mapOf("success" to true),
                directHitFunctionId = "open_settings_function",
            ),
        )
        assertTrue(gate.allowed)
        assertEquals(VlmToolCoordinator.CACHE_GATE_STRICT_HIT, gate.reason)
    }

    @Test
    fun `parameterized recall hit waits for simple argument reply when schema is missing`() = runBlocking {
        val request = VlmTaskRequest(
            goal = "小红书查看猫猫",
            packageName = "com.xingin.xhs",
            allowOmniFlowFunctionAutoExecute = true,
        )
        val state = TaskState(
            taskId = "task-parameterized-auto-skip",
            goal = request.goal,
            status = TaskStatus.RUNNING,
        )
        var called = false

        val outcome = VlmToolCoordinator.tryExecuteRecallHitIfAllowed(
            request = request,
            taskState = state,
            recallGuidance = VlmRecallGuidance(
                decision = "hit",
                guidance = "tool=xhs_search_keyword argument_policy: requires_arguments=true arguments={keyword:string required}",
                payload = mapOf(
                    "success" to true,
                    "decision" to "hit",
                    "hit" to mapOf(
                        "function_id" to "xhs_search_keyword",
                        "requires_arguments" to true,
                    ),
                ),
                directHitFunctionId = "xhs_search_keyword",
            ),
            progressReporter = { _, _ -> },
            runFunction = { _, _ ->
                called = true
                mapOf("success" to true)
            },
        )

        assertNotNull(outcome)
        assertEquals(VlmToolOutcomeStatus.WAITING_INPUT, outcome?.status)
        assertFalse(called)
        assertEquals(TaskStatus.WAITING_INPUT, state.status)
        assertEquals("xhs_search_keyword", state.pendingOmniFlowFunctionCall?.functionId)
        assertEquals(listOf("value"), state.pendingOmniFlowFunctionCall?.requiredArgumentNames)
        assertTrue(state.waitingQuestion?.contains("xhs_search_keyword") == true)
    }

    @Test
    fun `recall hit with input schema fills argument from goal and executes`() = runBlocking {
        val request = VlmTaskRequest(
            goal = "小红书查看猫猫",
            packageName = "com.xingin.xhs",
            allowOmniFlowFunctionAutoExecute = true,
        )
        val state = TaskState(
            taskId = "task-schema-parameterized-auto-skip",
            goal = request.goal,
            status = TaskStatus.RUNNING,
        )
        var called = false
        var capturedArguments: Map<String, Any?> = emptyMap()

        val outcome = VlmToolCoordinator.tryExecuteRecallHitIfAllowed(
            request = request,
            taskState = state,
            recallGuidance = VlmRecallGuidance(
                decision = "hit",
                guidance = "tool=xhs_search_keyword arguments={keyword:string required}",
                payload = mapOf(
                    "success" to true,
                    "decision" to "hit",
                    "hit" to mapOf(
                        "function_id" to "xhs_search_keyword",
                        "input_schema" to mapOf(
                            "type" to "object",
                            "required" to listOf("keyword"),
                            "properties" to mapOf(
                                "keyword" to mapOf("type" to "string")
                            ),
                        ),
                    ),
                ),
                directHitFunctionId = "xhs_search_keyword",
            ),
            progressReporter = { _, _ -> },
            runFunction = { _, arguments ->
                called = true
                capturedArguments = arguments
                mapOf("success" to true)
            },
        )

        assertNotNull(outcome)
        assertEquals(VlmToolOutcomeStatus.FINISHED, outcome?.status)
        assertTrue(called)
        assertEquals("猫猫", capturedArguments["keyword"])
        assertEquals(TaskStatus.FINISHED, state.status)
        assertNull(state.pendingOmniFlowFunctionCall)
        val gate = VlmToolCoordinator.evaluateFunctionCacheGate(
            request = request,
            recallGuidance = VlmRecallGuidance(
                decision = "hit",
                guidance = "tool=xhs_search_keyword argument_policy: requires_arguments=true arguments={keyword:string required}",
                payload = mapOf(
                    "success" to true,
                    "decision" to "hit",
                    "hit" to mapOf(
                        "function_id" to "xhs_search_keyword",
                        "requires_arguments" to true,
                    ),
                ),
                directHitFunctionId = "xhs_search_keyword",
            ),
        )
        assertFalse(gate.allowed)
        assertEquals(VlmToolCoordinator.CACHE_GATE_REQUIRES_ARGUMENTS, gate.reason)
    }

    @Test
    fun `pending recall function reply executes without starting another vlm task`() = runBlocking {
        val state = TaskState(
            taskId = "task-pending-omniflow",
            goal = "小红书查看猫猫",
            status = TaskStatus.WAITING_INPUT,
            pendingOmniFlowFunctionCall = PendingOmniFlowFunctionCall(
                functionId = "xhs_search_keyword",
                goal = "小红书查看猫猫",
                requiredArgumentNames = listOf("value"),
                allArgumentNames = emptyList(),
            ),
        )
        var capturedFunctionId = ""
        var capturedArguments: Map<String, Any?> = emptyMap()

        val outcome = VlmToolCoordinator.executePendingOmniFlowFunctionCall(
            taskState = state,
            reply = """{"keyword":"猫猫"}""",
            runFunction = { functionId, arguments ->
                capturedFunctionId = functionId
                capturedArguments = arguments
                mapOf(
                    "success" to true,
                    "fallback" to false,
                    "function_id" to functionId,
                    "actions_executed" to 1,
                )
            },
        )

        assertNotNull(outcome)
        assertEquals(VlmToolOutcomeStatus.FINISHED, outcome?.status)
        assertEquals("xhs_search_keyword", capturedFunctionId)
        assertEquals("猫猫", capturedArguments["keyword"])
        assertNull(state.pendingOmniFlowFunctionCall)
        assertEquals(TaskStatus.FINISHED, state.status)
        assertEquals("omniflow_recall_hit:xhs_search_keyword", state.executionRoute)
    }

    @Test
    fun `mock vlm task recall uses warm memory and goal without real vlm call`() = runBlocking {
        val goal = "小红书查看猫猫"
        val warmMemory = """
            Warm memory:
            - 已保存 Function: xhs_search_keyword
            - 能力: 小红书搜索关键词
            - 参数: keyword
            - 适用目标: 小红书查看/搜索某个关键词
        """.trimIndent()
        VLMRecallContextProviderRegistry.register(
            object : VLMRecallContextProvider {
                override suspend fun enrich(request: VLMPageContextRequest): UIContext {
                    val activeGoal = request.context.activeGoal()
                    val memory = request.context.stepSkillGuidance
                    val hit = activeGoal.contains("小红书") &&
                        activeGoal.contains("猫猫") &&
                        memory.contains("xhs_search_keyword") &&
                        memory.contains("小红书搜索关键词")
                    if (!hit) {
                        return request.context.copy(
                            pageDiagnostics = request.context.pageDiagnostics + mapOf(
                                "omniflow_recall_injected" to "false",
                                "omniflow_recall_miss_reason" to "warm_memory_or_goal_not_matched"
                            )
                        )
                    }
                    return request.context.copy(
                        stepSkillGuidance = request.context.stepSkillGuidance + "\n" +
                            "OmniFlow function recall candidates for this current VLM step:\n" +
                            "1. tool=xhs_search_keyword score=0.98 description=小红书搜索关键词\n" +
                            "   argument_policy: requires_arguments=true arguments={keyword:string required}",
                        dynamicToolDefinitions = listOf(dynamicFunctionToolDefinition("xhs_search_keyword")),
                        pageDiagnostics = request.context.pageDiagnostics + mapOf(
                            "omniflow_recall_injected" to "true",
                            "omniflow_recall_hit_function_id" to "xhs_search_keyword",
                            "omniflow_recall_hit_reason" to "warm_memory_goal_match",
                            "omniflow_recall_goal" to activeGoal,
                            "omniflow_recall_warm_memory_chars" to memory.length.toString()
                        )
                    )
                }
            }
        )
        try {
            var promptText = ""
            var requestToolNames = emptyList<String>()
            val streamClient = object : VLMStreamClient {
                override suspend fun streamTurn(
                    request: ChatCompletionRequest,
                    onReasoningUpdate: (suspend (String) -> Unit)?
                ): SceneChatCompletionTurn {
                    promptText = request.messages.last().content.toString()
                    requestToolNames = request.tools.orEmpty().map { it.function.name }
                    return SceneChatCompletionTurn(
                        parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                        route = "scene.vlm.operation.primary",
                        resolvedModel = "vlm-test-model",
                        turn = ChatCompletionTurn(
                            message = ChatCompletionMessage(
                                role = "assistant",
                                content = JsonPrimitive("""{"observation":"命中复用指令","thought":"调用搜索 Function","summary":""}"""),
                                toolCalls = listOf(
                                    AssistantToolCall(
	                                        id = "call_1",
	                                        function = AssistantToolCallFunction(
	                                            name = "call_tool",
	                                            arguments = """{"function_id":"xhs_search_keyword","arguments":{"keyword":"猫猫"}}"""
	                                        )
	                                    )
                                )
                            )
                        )
                    )
                }
            }

            val result = VlmToolCoordinator.parseOnlyNextAction(
                context = UIContext(
                    overallTask = goal,
                    currentStepGoal = goal,
                    targetPackageName = "com.xingin.xhs",
                    stepSkillGuidance = warmMemory,
                ),
                snapshot = VLMCurrentPageSnapshot(
                    packageName = "com.xingin.xhs",
                    xml = "<hierarchy><node text=\"搜索\" clickable=\"true\" bounds=\"[0,0][100,100]\" /></hierarchy>",
                    screenshotBase64 = "RAW_IMAGE",
                    displayWidth = 1080,
                    displayHeight = 1920,
                    capturedAtMs = 1234L,
                ),
                streamClient = streamClient,
                vlmClient = VLMClient(
                    systemPromptBuilder = { "test vlm system prompt" },
                    turnPromptBuilder = { ctx, _ ->
                        listOf(
                            "goal=${ctx.activeGoal()}",
                            "warm_memory=${ctx.stepSkillGuidance.substringBefore("OmniFlow function recall candidates")}",
                            "current_page_summary=${ctx.currentPageSummary}",
                            "step_skill_guidance=${ctx.stepSkillGuidance}",
                            "first_step_guidance=${ctx.firstStepGuidance}",
                        ).joinToString("\n")
                    },
                ),
            )

            assertTrue(result.success)
            assertEquals("call_tool", result.toolName)
            assertTrue(requestToolNames.contains("call_tool"))
            assertFalse(requestToolNames.contains("xhs_search_keyword"))
            assertTrue(requestToolNames.contains("click"))
            assertTrue(result.screenshotIncluded)
            assertTrue(promptText.contains("goal=$goal"))
            assertTrue(promptText.contains("Warm memory:"))
            assertTrue(promptText.contains("xhs_search_keyword"))
            assertTrue(promptText.contains("call_tool"))
            assertTrue(promptText.contains("arguments={keyword:string required}"))
            assertEquals("1", result.pageDiagnostics["omniflow_call_tool_function_count"])
            assertEquals("xhs_search_keyword", result.pageDiagnostics["omniflow_call_tool_function_ids"])
            assertEquals("true", result.pageDiagnostics["omniflow_recall_injected"])
            assertEquals("xhs_search_keyword", result.pageDiagnostics["omniflow_recall_hit_function_id"])
            assertEquals("warm_memory_goal_match", result.pageDiagnostics["omniflow_recall_hit_reason"])
            assertEquals(goal, result.pageDiagnostics["omniflow_recall_goal"])
            assertEquals(warmMemory.length.toString(), result.pageDiagnostics["omniflow_recall_warm_memory_chars"])
            assertTrue(result.phaseMs.containsKey("function_recall_ms"))
            assertTrue(result.phaseMs.containsKey("vlm_stream_ms"))
            val action = requireNotNull(result.action)
            assertEquals("xhs_search_keyword", action["function_id"])
            val parsedAction = requireNotNull(result.action)
            val args = parsedAction["arguments"] as Map<*, *>
            assertEquals("猫猫", args["keyword"])
        } finally {
            VLMRecallContextProviderRegistry.clear()
        }
    }

    private fun dynamicFunctionToolDefinition(name: String) = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", name)
            put("toolType", "oob_function")
            put("description", "Saved mobile workflow")
            put("parameters", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("keyword", buildJsonObject {
                        put("type", "string")
                    })
                })
                put("required", buildJsonArray {
                    add("keyword")
                })
            })
        })
    }
}
