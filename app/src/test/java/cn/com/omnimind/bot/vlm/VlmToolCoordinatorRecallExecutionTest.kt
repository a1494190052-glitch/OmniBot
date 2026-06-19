package cn.com.omnimind.bot.vlm

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
                    "function_id" to functionId,
                    "run_id" to "omniflow_run_test",
                    "actions_executed" to 1,
                    "execution_summary" to mapOf(
                        "success" to true,
                        "steps" to 1,
                        "resolve_calls" to 0,
                        "model_calls" to 0,
                        "tokens" to 0,
                        "elapsed_ms" to 42,
                    ),
                )
            },
            resolveProvider = resolveRecall(),
        )

        assertNotNull(outcome)
        assertEquals(VlmToolOutcomeStatus.FINISHED, outcome?.status)
        assertEquals(TaskStatus.FINISHED, state.status)
        assertEquals("omniflow_recall_hit:open_settings_function", state.executionRoute)
        assertTrue(state.finishedContent?.contains("open_settings_function") == true)
        assertTrue(state.summaryText?.contains("actions_executed=1") == true)
        assertEquals("hit", outcome?.toPayload()?.get("omniflowRecall")?.let { it as Map<*, *> }?.get("decision"))
        val executionSummary = outcome?.toPayload()?.get("omniflowExecutionSummary") as Map<*, *>
        assertFalse(executionSummary.containsKey("function_id"))
        assertEquals(1, executionSummary["steps"])
        assertEquals(0, executionSummary["resolve_calls"])
        assertFalse(executionSummary.containsKey("repair_steps"))
        assertEquals(0, executionSummary["model_calls"])
        assertEquals(0, executionSummary["tokens"])
        assertEquals(2, events.size)
        assertEquals("FINISHED", events.last()["status"])
        assertFalse(events.last().containsKey("omniflowRecallResult"))
        assertTrue(events.last().containsKey("omniflowExecutionSummary"))
    }

    @Test
    fun `direct recall hit reports error when local replay fails`() = runBlocking {
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
                    "error" to "execution_failed",
                    "execution_summary" to mapOf(
                        "success" to false,
                        "function_id" to "open_settings_function",
                        "steps" to 1,
                        "resolve_calls" to 0,
                        "model_calls" to 0,
                        "tokens" to 0,
                        "elapsed_ms" to 11,
                        "failure_reason" to "execution_failed",
                    ),
                )
            },
            resolveProvider = resolveRecall(),
        )

        assertNotNull(outcome)
        assertEquals(VlmToolOutcomeStatus.ERROR, outcome?.status)
        assertEquals(TaskStatus.ERROR, state.status)
        assertEquals("omniflow_recall_failed:open_settings_function", state.executionRoute)
        assertTrue(state.chatMessages.last().contains("normal VLM will not reselect"))
        val executionSummary = outcome?.toPayload()?.get("omniflowExecutionSummary") as Map<*, *>
        assertEquals(false, executionSummary["success"])
        assertEquals("execution_failed", executionSummary["failure_reason"])
        assertEquals(2, events.size)
        assertEquals("ERROR", events.last()["status"])
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
            resolveProvider = resolveRecall(),
        )

        assertNull(outcome)
        assertEquals(false, called)
        assertEquals(TaskStatus.RUNNING, state.status)
    }

    @Test
    fun `recall verifier rejection falls back to ordinary vlm execution`() = runBlocking {
        val state = TaskState(
            taskId = "task-recall-verifier-no",
            goal = "open display settings",
            status = TaskStatus.RUNNING,
        )
        var called = false
        val events = mutableListOf<Map<String, Any?>>()

        val outcome = VlmToolCoordinator.tryExecuteRecallHit(
            taskState = state,
            goal = state.goal,
            recallGuidance = VlmRecallGuidance(
                decision = "hit",
                guidance = "OmniFlow recall checked for this VLM step.",
                payload = mapOf("success" to true),
                directHitFunctionId = "open_network_settings_function",
            ),
            progressReporter = { _, extras -> events += extras },
            runFunction = { _, _ ->
                called = true
                mapOf("success" to true)
            },
            resolveProvider = rejectRecall("goal_mismatch"),
        )

        assertNull(outcome)
        assertFalse(called)
        assertEquals(TaskStatus.RUNNING, state.status)
        assertEquals("omniflow_recall_verifier_no:open_network_settings_function", state.executionRoute)
        assertTrue(state.chatMessages.last().contains("verifier rejected"))
        assertEquals(1, events.size)
        assertEquals("RUNNING", events.first()["status"])
        assertEquals("goal_mismatch", events.first()["runtimeResolveReason"])
    }

    @Test
    fun `recall verifier parses fenced json with execute decision`() {
        val parsed = VlmToolCoordinator.parseRecallFunctionVerifierResolveForTest(
            """
            ```json
            {"decision":"execute","arguments":{"keyword":"猫猫"},"missing_required_arguments":[],"reason":"goal match"}
            ```
            """.trimIndent()
        )

        assertNotNull(parsed)
        assertEquals(true, parsed?.accepted)
        assertEquals("猫猫", parsed?.arguments?.get("keyword"))
        assertEquals(emptyList<String>(), parsed?.missingRequiredArguments)
        assertEquals("goal match", parsed?.reason)
    }

    @Test
    fun `recall verifier parses no decision from wrapped text`() {
        val parsed = VlmToolCoordinator.parseRecallFunctionVerifierResolveForTest(
            """result: {"decision":"no","arguments":{},"reason":"wrong page"}"""
        )

        assertNotNull(parsed)
        assertEquals(false, parsed?.accepted)
        assertEquals("wrong page", parsed?.reason)
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
    fun `vlm requests default to runtime recall auto execution`() {
        val request = VlmTaskRequest(
            goal = "open settings",
            packageName = "com.android.settings",
        )

        assertEquals(true, request.allowOmniFlowFunctionAutoExecute)
    }

    @Test
    fun `recall hit is not executed when auto execution is explicitly disabled`() = runBlocking {
        val request = VlmTaskRequest(
            goal = "open settings",
            packageName = "com.android.settings",
            allowOmniFlowFunctionAutoExecute = false,
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
            resolveProvider = resolveRecall(),
        )

        assertNull(outcome)
        assertEquals(false, called)
        assertEquals(TaskStatus.RUNNING, state.status)
        val selection = VlmToolCoordinator.evaluateFunctionRuntimeSelection(
            request = request,
            recallGuidance = VlmRecallGuidance(
                decision = "hit",
                guidance = "OmniFlow UDEG node skill-like decision context",
                payload = mapOf("success" to true),
                directHitFunctionId = "open_settings_function",
            ),
        )
        assertFalse(selection.allowed)
        assertEquals(VlmToolCoordinator.RUNTIME_SELECTION_AUTO_EXECUTE_DISABLED, selection.reason)
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
                    "function_id" to "open_settings_function",
                    "run_id" to "omniflow_run_test",
                    "actions_executed" to 1,
                )
            },
            resolveProvider = resolveRecall(),
        )

        assertNotNull(outcome)
        assertEquals(true, called)
        assertEquals(TaskStatus.FINISHED, state.status)
        val selection = VlmToolCoordinator.evaluateFunctionRuntimeSelection(
            request = request,
            recallGuidance = VlmRecallGuidance(
                decision = "hit",
                guidance = "OmniFlow UDEG node skill-like decision context",
                payload = mapOf("success" to true),
                directHitFunctionId = "open_settings_function",
            ),
        )
        assertTrue(selection.allowed)
        assertEquals(VlmToolCoordinator.RUNTIME_SELECTION_STRICT_HIT, selection.reason)
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
                guidance = "OmniFlow recall checked for this VLM step.",
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
            resolveProvider = resolveMissingArguments(
                reason = "missing_required_arguments",
                missing = listOf("value"),
            ),
        )

        assertNotNull(outcome)
        assertEquals(VlmToolOutcomeStatus.WAITING_INPUT, outcome?.status)
        assertFalse(called)
        assertEquals(TaskStatus.WAITING_INPUT, state.status)
        assertEquals("xhs_search_keyword", state.pendingOmniFlowFunctionCall?.functionId)
        assertEquals(listOf("value"), state.pendingOmniFlowFunctionCall?.requiredArgumentNames)
        assertTrue(state.waitingQuestion?.contains("复用指令") == true)
        assertFalse(state.waitingQuestion?.contains("xhs_search_keyword") == true)
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
                guidance = "OmniFlow recall checked for this VLM step.",
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
            resolveProvider = resolveRecall(mapOf("keyword" to "猫猫"), resolveCalls = 1),
        )

        assertNotNull(outcome)
        assertEquals(VlmToolOutcomeStatus.FINISHED, outcome?.status)
        assertTrue(called)
        assertEquals("猫猫", capturedArguments["keyword"])
        assertEquals(TaskStatus.FINISHED, state.status)
        assertNull(state.pendingOmniFlowFunctionCall)
        val executionSummary = outcome?.toPayload()?.get("omniflowExecutionSummary") as Map<*, *>
        assertEquals(1, executionSummary["resolve_calls"])
        val selection = VlmToolCoordinator.evaluateFunctionRuntimeSelection(
            request = request,
            recallGuidance = VlmRecallGuidance(
                decision = "hit",
                guidance = "OmniFlow recall checked for this VLM step.",
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
        assertTrue(selection.allowed)
        assertEquals(VlmToolCoordinator.RUNTIME_SELECTION_STRICT_HIT, selection.reason)
    }

    @Test
    fun `argument resolve and replay step resolve share compact resolve call metric`() = runBlocking {
        val request = VlmTaskRequest(
            goal = "小红书查看猫猫",
            packageName = "com.xingin.xhs",
            allowOmniFlowFunctionAutoExecute = true,
        )
        val state = TaskState(
            taskId = "task-runtime-resolve-unified-count",
            goal = request.goal,
            status = TaskStatus.RUNNING,
        )

        val outcome = VlmToolCoordinator.tryExecuteRecallHitIfAllowed(
            request = request,
            taskState = state,
            recallGuidance = VlmRecallGuidance(
                decision = "hit",
                guidance = "OmniFlow recall checked for this VLM step.",
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
            runFunction = { functionId, arguments ->
                assertEquals("xhs_search_keyword", functionId)
                assertEquals("猫猫", arguments["keyword"])
                mapOf(
                    "success" to true,
                    "function_id" to functionId,
                    "actions_executed" to 2,
                    "execution_summary" to mapOf(
                        "success" to true,
                        "steps" to 2,
                        "resolve_calls" to 1,
                        "model_calls" to 1,
                        "tokens" to 88,
                        "elapsed_ms" to 123,
                    ),
                )
            },
            resolveProvider = resolveRecall(mapOf("keyword" to "猫猫"), resolveCalls = 1),
        )

        assertNotNull(outcome)
        assertEquals(VlmToolOutcomeStatus.FINISHED, outcome?.status)
        val executionSummary = outcome?.toPayload()?.get("omniflowExecutionSummary") as Map<*, *>
        assertEquals(true, executionSummary["success"])
        assertFalse(executionSummary.containsKey("function_id"))
        assertEquals(2, executionSummary["steps"])
        assertEquals(2, executionSummary["resolve_calls"])
        assertEquals(1, executionSummary["model_calls"])
        assertEquals(88, executionSummary["tokens"])
        assertFalse(executionSummary.containsKey("repair_steps"))
        assertFalse(executionSummary.containsKey("online_repair_steps"))
        assertFalse(executionSummary.containsKey("fallback_agent_fill"))
    }

    @Test
    fun `runtime resolve with no public arguments executes selected recall hit`() = runBlocking {
        val request = VlmTaskRequest(
            goal = "open settings",
            packageName = "com.android.settings",
            allowOmniFlowFunctionAutoExecute = true,
        )
        val state = TaskState(
            taskId = "task-runtime-selection-no-args",
            goal = request.goal,
            status = TaskStatus.RUNNING,
        )
        var called = false

        val outcome = VlmToolCoordinator.tryExecuteRecallHitIfAllowed(
            request = request,
            taskState = state,
            recallGuidance = VlmRecallGuidance(
                decision = "hit",
                guidance = "OmniFlow recall checked for this VLM step.",
                payload = mapOf(
                    "success" to true,
                    "decision" to "hit",
                    "hit" to mapOf(
                        "function_id" to "open_settings_function",
                        "requires_arguments" to false,
                    ),
                ),
                directHitFunctionId = "open_settings_function",
            ),
            progressReporter = { _, _ -> },
            runFunction = { _, _ ->
                called = true
                mapOf(
                    "success" to true,
                    "function_id" to "open_settings_function",
                    "actions_executed" to 1,
                )
            },
            resolveProvider = resolveRecall(reason = "no_public_arguments"),
        )

        assertNotNull(outcome)
        assertEquals(VlmToolOutcomeStatus.FINISHED, outcome?.status)
        assertTrue(called)
        assertEquals(TaskStatus.FINISHED, state.status)
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
                    "function_id" to functionId,
                    "actions_executed" to 1,
                    "execution_summary" to mapOf(
                        "success" to true,
                        "steps" to 1,
                        "resolve_calls" to 0,
                        "model_calls" to 0,
                        "tokens" to 0,
                        "elapsed_ms" to 7,
                    ),
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
        val executionSummary = outcome?.toPayload()?.get("omniflowExecutionSummary") as Map<*, *>
        assertFalse(executionSummary.containsKey("function_id"))
        assertEquals(1, executionSummary["steps"])
    }

    @Test
    fun `mock vlm task recall uses warm memory and goal without real vlm call`() = runBlocking {
        val goal = "小红书查看猫猫"
        val warmMemory = """
            Warm memory:
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
                            "[[OOB_OMNIFLOW_STEP_RECALL_START]]\n" +
                            "OmniFlow recall checked for this VLM step.\n" +
                            "function_reuse_policy=runtime_context_only\n" +
                            "runtime_behavior=high-confidence Function hits are resolved and replayed locally before ordinary VLM actions.\n" +
                            "online_action_policy=if this turn reaches the VLM, output exactly one ordinary UI action for the current screen.\n" +
                            "allowed_actions=click,input_text,swipe,press_back,press_home,open_app,wait\n" +
                            "hidden_runtime_actions=do not select saved Functions, hidden replay tools, or task-level replanning.\n" +
                            "[[OOB_OMNIFLOW_STEP_RECALL_END]]",
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
                                content = JsonPrimitive("""{"observation":"命中复用指令","thought":"改为普通 UI 点击","summary":""}"""),
                                toolCalls = listOf(
                                    AssistantToolCall(
		                                        id = "call_1",
		                                        function = AssistantToolCallFunction(
		                                            name = "click",
		                                            arguments = """{"target_description":"搜索","x":500,"y":500}"""
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
                            "warm_memory=${ctx.stepSkillGuidance.substringBefore("[[OOB_OMNIFLOW_STEP_RECALL_START]]")}",
                            "current_page_summary=${ctx.currentPageSummary}",
                            "step_skill_guidance=${ctx.stepSkillGuidance}",
                            "first_step_guidance=${ctx.firstStepGuidance}",
                        ).joinToString("\n")
                    },
                ),
            )

            assertTrue(result.success)
            assertEquals("click", result.toolName)
            assertFalse(requestToolNames.contains("call_tool"))
            assertFalse(requestToolNames.contains("xhs_search_keyword"))
            assertTrue(requestToolNames.contains("click"))
            assertTrue(result.screenshotIncluded)
            assertTrue(promptText.contains("goal=$goal"))
            assertTrue(promptText.contains("Warm memory:"))
            assertFalse(promptText.contains("call_tool("))
            assertFalse(promptText.contains("xhs_search_keyword"))
            assertFalse(promptText.contains("arguments={keyword:string required}"))
            assertTrue(promptText.contains("Function hits are resolved and replayed locally"))
            assertEquals("1", result.pageDiagnostics["omniflow_recalled_function_count"])
            assertEquals("xhs_search_keyword", result.pageDiagnostics["omniflow_recalled_function_ids"])
            assertEquals("true", result.pageDiagnostics["omniflow_recall_injected"])
            assertEquals("xhs_search_keyword", result.pageDiagnostics["omniflow_recall_hit_function_id"])
            assertEquals("warm_memory_goal_match", result.pageDiagnostics["omniflow_recall_hit_reason"])
            assertEquals(goal, result.pageDiagnostics["omniflow_recall_goal"])
            assertEquals(warmMemory.length.toString(), result.pageDiagnostics["omniflow_recall_warm_memory_chars"])
            assertTrue(result.phaseMs.containsKey("function_recall_ms"))
            assertTrue(result.phaseMs.containsKey("vlm_stream_ms"))
            val action = requireNotNull(result.action)
            assertEquals("click", action["tool"])
            assertEquals("搜索", action["target_description"])
            assertEquals(500.0f, action["x"])
            assertEquals(500.0f, action["y"])
            assertFalse(action.containsKey("function_id"))
        } finally {
            VLMRecallContextProviderRegistry.clear()
        }
    }

    private fun resolveRecall(
        arguments: Map<String, Any?> = emptyMap(),
        resolveCalls: Int = 0,
        reason: String = "test_accept",
    ): RuntimeResolveProvider =
        { _, _, _ ->
            RuntimeResolveResult(
                arguments = arguments,
                reason = reason,
                resolveCalls = resolveCalls,
            )
        }

    private fun resolveMissingArguments(
        reason: String,
        missing: List<String> = emptyList(),
        arguments: Map<String, Any?> = emptyMap(),
    ): RuntimeResolveProvider =
        { _, _, _ ->
            RuntimeResolveResult(
                arguments = arguments,
                missingRequiredArguments = missing,
                reason = reason,
            )
        }

    private fun rejectRecall(reason: String): RuntimeResolveProvider =
        { _, _, _ ->
            RuntimeResolveResult(
                accepted = false,
                reason = reason,
                resolveCalls = 1,
            )
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
