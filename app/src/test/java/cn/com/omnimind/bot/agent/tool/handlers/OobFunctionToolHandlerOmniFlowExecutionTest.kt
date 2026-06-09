package cn.com.omnimind.bot.agent.tool.handlers

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import cn.com.omnimind.assists.OmniFlowUiSession
import cn.com.omnimind.baselib.runlog.InternalRunLogRecord
import cn.com.omnimind.baselib.runlog.OobReusableFunctionStore
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentResult
import cn.com.omnimind.bot.agent.AgentToolExecutor
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.AgentWorkspaceDescriptor
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.AgentRuntimeContextRepository
import cn.com.omnimind.bot.agent.AgentToolProgressSnapshot
import cn.com.omnimind.bot.agent.ResolvedSkillContext
import cn.com.omnimind.bot.agent.ManualToolStopCancellationException
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.agent.WorkspaceMemoryService
import cn.com.omnimind.bot.agent.NoOpAgentRunControl
import cn.com.omnimind.bot.omniflow.OobFunctionRepository
import cn.com.omnimind.bot.omniflow.OobFunctionParameterBindingNormalizer
import cn.com.omnimind.bot.omniflow.language.OmniflowFunction
import cn.com.omnimind.bot.omniflow.language.OmniflowFunctionStore
import cn.com.omnimind.bot.omniflow.language.ParameterValue
import cn.com.omnimind.bot.omniflow.language.UIStep
import cn.com.omnimind.bot.omniflow.WorkspaceFunctionStore
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.bot.omniflow.OobFunctionSchemaBuilder
import cn.com.omnimind.bot.runlog.OobUdegNodeStore
import cn.com.omnimind.bot.runlog.OmniflowActionBackend
import cn.com.omnimind.bot.runlog.OmniflowActionRuntime
import cn.com.omnimind.bot.runlog.RunLogReusableFunctionCompiler
import cn.com.omnimind.omniintelligence.models.ScrollDirection
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class OobFunctionToolHandlerOmniFlowExecutionTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `local click replay fails before execution when accessibility is unavailable`() = runBlocking {
        val context = TempFilesContext()
        val backend = NotReadyBackend()
        try {
            val spec = functionSpec(
                functionId = "click_requires_accessibility",
                steps = listOf(
                    mapOf(
                        "id" to "click_step",
                        "title" to "Click target",
                        "kind" to "omniflow_action",
                        "executor" to "omniflow",
                        "model_free" to true,
                        "scriptable" to true,
                        "tool" to "click",
                        "callable_tool" to "click",
                        "args" to mapOf("x" to 100, "y" to 240),
                    )
                ),
            )
            OmniflowActionRuntime.useBackendForTesting(backend).use {
                val run = handler(context, WorkspaceFunctionStore(context.root)).runMaterializedFunction(
                    functionId = "click_requires_accessibility",
                    spec = spec,
                    materializedSpec = OobReusableFunctionStore.materialize(spec, emptyMap()),
                    allowAgentFallback = false,
                )

                assertEquals(false, run["success"])
                assertEquals("OOB_ACCESSIBILITY_REQUIRED", run["error_code"])
	                assertEquals("accessibility", run["required_permission"])
	                assertEquals(0, backend.clickCount)
	                assertTrue(run["error_message"].toString().contains("无障碍"))
	                assertEquals(1, run["step_count"])
	                assertEquals(0, run["failed_step_index"])
	                assertEquals(0, run["current_step_index"])
	                assertEquals(1, run["current_step_number"])
	                val step = stepResults(run).single()
	                assertEquals("click", step["tool"])
	                assertEquals("OOB_ACCESSIBILITY_REQUIRED", step["error_code"])
            }
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `function replay observes before each primitive action`() = runBlocking {
        val context = TempFilesContext()
        val backend = RecordingBackend(
            currentXml = CURRENT_PAGE_XML,
            currentPackage = "com.example.current",
            currentActivity = "CurrentActivity",
        )
        try {
            val spec = functionSpec(
                functionId = "two_clicks_observe_per_action",
                steps = listOf(
                    mapOf(
                        "id" to "first_click",
                        "title" to "First click",
                        "kind" to "omniflow_action",
                        "executor" to "omniflow",
                        "model_free" to true,
                        "scriptable" to true,
                        "tool" to "click",
                        "callable_tool" to "click",
                        "args" to mapOf("x" to 100, "y" to 240),
                    ),
                    mapOf(
                        "id" to "second_click",
                        "title" to "Second click",
                        "kind" to "omniflow_action",
                        "executor" to "omniflow",
                        "model_free" to true,
                        "scriptable" to true,
                        "tool" to "click",
                        "callable_tool" to "click",
                        "args" to mapOf("x" to 220, "y" to 360),
                    ),
                ),
            )
            OmniflowActionRuntime.useBackendForTesting(backend).use {
                val run = handler(context, WorkspaceFunctionStore(context.root)).runMaterializedFunction(
                    functionId = "two_clicks_observe_per_action",
                    spec = spec,
                    materializedSpec = OobReusableFunctionStore.materialize(spec, emptyMap()),
                    allowAgentFallback = false,
                )

                assertEquals(true, run["success"])
                assertEquals(2, backend.clickCount)
                assertTrue(
                    "Function execution must fresh observe per primitive action",
                    backend.currentXmlReadCount >= 2,
                )
                val results = stepResults(run)
                assertEquals(2, results.size)
                results.forEach { step ->
                    val timing = step["timing"] as? Map<*, *> ?: error("missing step timing")
                    val phaseMs = timing["phase_ms"] as? Map<*, *> ?: error("missing phase timing")
                    assertTrue("missing observe timing", phaseMs.containsKey("observe_ms"))
                    assertTrue("missing checker timing", phaseMs.containsKey("checker_ms"))
                    assertTrue("missing action transfer timing", phaseMs.containsKey("action_transfer_ms"))
                    assertTrue("missing act timing", phaseMs.containsKey("act_ms"))
                }
            }
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `function replay waits for semantic target before next primitive action`() = runBlocking {
        val context = TempFilesContext()
        val readyPage = pageXml("second_click", "com.example.current")
        val backend = RecordingBackend(
            currentXml = readyPage,
            currentPackage = "com.example.current",
            currentActivity = "CurrentActivity",
            currentXmlSequence = listOf(
                CURRENT_PAGE_XML,
                CURRENT_PAGE_XML,
                readyPage,
                readyPage,
            ),
        )
        try {
            val spec = functionSpec(
                functionId = "wait_until_second_click_ready",
                steps = listOf(
                    mapOf(
                        "id" to "first_click",
                        "title" to "First click",
                        "kind" to "omniflow_action",
                        "executor" to "omniflow",
                        "model_free" to true,
                        "scriptable" to true,
                        "tool" to "click",
                        "callable_tool" to "click",
                        "args" to mapOf("x" to 100, "y" to 240),
                    ),
                    clickStepWithSource(
                        stepId = "second_click",
                        sourceXml = readyPage,
                        destinationXml = readyPage,
                        packageName = "com.example.current",
                    ),
                ),
            )
            OmniflowActionRuntime.useBackendForTesting(backend).use {
                val run = handler(context, WorkspaceFunctionStore(context.root)).runMaterializedFunction(
                    functionId = "wait_until_second_click_ready",
                    spec = spec,
                    materializedSpec = OobReusableFunctionStore.materialize(spec, emptyMap()),
                    allowAgentFallback = false,
                )

                assertEquals(true, run["success"])
                assertEquals(2, backend.clickCount)
                val results = stepResults(run)
                assertEquals(2, results.size)
                val wait = results[1]["pre_action_ready_wait"] as? Map<*, *>
                    ?: error("missing pre-action ready wait")
                assertEquals("ready", wait["status"])
                assertEquals(2, (wait["attempts"] as Number).toInt())
                assertTrue(wait["matched_by"] == "text_exact" || wait["matched_by"] == "text_contains")
                assertEquals("second_click", wait["matched_value"])
            }
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `manual stop returns stopped run payload instead of throwing`() = runBlocking {
        val context = TempFilesContext()
        try {
            val spec = functionSpec(
                functionId = "stoppable_function",
                steps = listOf(finishedStep("done")),
            )
            val run = handler(context, WorkspaceFunctionStore(context.root)).runMaterializedFunction(
                functionId = "stoppable_function",
                spec = spec,
                materializedSpec = OobReusableFunctionStore.materialize(spec, emptyMap()),
                toolHandle = StoppingToolHandle(),
                allowAgentFallback = false,
            )

            assertEquals(false, run["success"])
            assertEquals("OOB_FUNCTION_STOPPED", run["error_code"])
            assertTrue(run["error_message"].toString().contains("停止"))
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `bound stop action cancels active replay action immediately`() = runBlocking {
        val context = TempFilesContext()
        val backend = BlockingClickBackend(
            currentXml = CURRENT_PAGE_XML,
            currentPackage = "com.xingin.xhs",
            currentActivity = "FeedActivity",
        )
        try {
            val spec = functionSpec(
                functionId = "stop_during_click",
                steps = listOf(
                    mapOf(
                        "id" to "click_step",
                        "title" to "Click target",
                        "kind" to "omniflow_action",
                        "executor" to "omniflow",
                        "model_free" to true,
                        "scriptable" to true,
                        "tool" to "click",
                        "args" to mapOf("x" to 100, "y" to 240),
                    )
                ),
            )
            val stopHandle = StopActionHandle()
            OmniflowActionRuntime.useBackendForTesting(backend).use {
                val runDeferred = async {
                    handler(context, WorkspaceFunctionStore(context.root)).runMaterializedFunction(
                        functionId = "stop_during_click",
                        spec = spec,
                        materializedSpec = OobReusableFunctionStore.materialize(spec, emptyMap()),
                        toolHandle = stopHandle,
                        allowAgentFallback = false,
                    )
                }

                backend.clickStarted.await()
                stopHandle.awaitStopAction().invoke()

                val run = withTimeout(1000L) { runDeferred.await() }
                assertEquals(false, run["success"])
                assertEquals("OOB_FUNCTION_STOPPED", run["error_code"])
                assertEquals(1, backend.clickCount)
                assertTrue(run["error_message"].toString().contains("停止"))
            }
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `overlay complete request finishes active replay as user completed`() = runBlocking {
        val context = TempFilesContext()
        val backend = BlockingClickBackend(
            currentXml = CURRENT_PAGE_XML,
            currentPackage = "com.xingin.xhs",
            currentActivity = "FeedActivity",
        )
        val frontendRunId = "overlay-complete-${System.nanoTime()}"
        val frontendTaskId = "$frontendRunId-task"
        try {
            val spec = functionSpec(
                functionId = "complete_during_click",
                steps = listOf(
                    mapOf(
                        "id" to "click_step",
                        "title" to "Click target",
                        "kind" to "omniflow_action",
                        "executor" to "omniflow",
                        "model_free" to true,
                        "scriptable" to true,
                        "tool" to "click",
                        "args" to mapOf("x" to 100, "y" to 240),
                    )
                ),
            )
            OmniflowActionRuntime.useBackendForTesting(backend).use {
                val runDeferred = async {
                    handler(context, WorkspaceFunctionStore(context.root)).runMaterializedFunction(
                        functionId = "complete_during_click",
                        spec = spec,
                        materializedSpec = OobReusableFunctionStore.materialize(spec, emptyMap()),
                        allowAgentFallback = false,
                        frontendRunId = frontendRunId,
                        frontendTaskId = frontendTaskId,
                    )
                }

                backend.clickStarted.await()
                assertTrue(OmniFlowUiSession.requestCompleteSession(frontendTaskId))

                val run = withTimeout(1000L) { runDeferred.await() }
                assertEquals(true, run["success"])
                assertEquals("user_completed", run["done_reason"])
                assertEquals(true, run["completed_by_user"])
                assertEquals(null, run["error_code"])
                assertEquals(1, backend.clickCount)
            }
        } finally {
            OmniFlowUiSession.endRun(frontendRunId)
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `call_tool executes nested registered function locally by function id`() = runBlocking {
        val context = TempFilesContext()
        try {
            val store = WorkspaceFunctionStore(context.root)
            val child = functionSpec(
                functionId = "child_finished",
                steps = listOf(finishedStep("child_step")),
            )
            val parent = functionSpec(
                functionId = "parent_calls_child",
                steps = listOf(
                    mapOf(
                        "id" to "parent_step",
                        "title" to "Call child",
                        "kind" to "omniflow_function",
                        "executor" to "omniflow",
                        "model_free" to true,
                        "scriptable" to true,
                        "tool" to "call_tool",
                        "callable_tool" to "call_tool",
                        "args" to mapOf("function_id" to "child_finished"),
                    )
                ),
            )
            assertTrue(store.register(child)["success"] == true)

            val handler = handler(context, store)
            val run = handler.runMaterializedFunction(
                functionId = "parent_calls_child",
                spec = parent,
                materializedSpec = OobReusableFunctionStore.materialize(parent, emptyMap()),
                allowAgentFallback = false,
            )

            assertEquals(true, run["success"])
            assertEquals(false, run["model_required"])
            assertEquals("oob_omniflow_loop", run["runner"])
            assertTrue(run["run_id"]?.toString()?.startsWith("omniflow_run_") == true)
            assertEquals(run["run_id"], run["audit_run_id"])
            val step = stepResults(run).single()
            assertEquals("omniflow_function", step["executor"])
            assertEquals("call_tool", step["tool"])
            assertEquals("child_finished", step["nested_function_id"])
            assertTrue(step["nested_run_id"]?.toString()?.startsWith("omniflow_run_") == true)
            assertEquals(true, step["success"])
            assertEquals(false, step["nested_model_required"])
            val nestedSteps = step["step_results"] as? List<*>
            assertEquals(1, nestedSteps?.size)
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `nested call_tool emits explicit tool card events`() = runBlocking {
        val context = TempFilesContext()
        try {
            val store = WorkspaceFunctionStore(context.root)
            val child = functionSpec(
                functionId = "child_finished",
                steps = listOf(finishedStep("child_step")),
            )
            val parent = functionSpec(
                functionId = "parent_calls_child",
                steps = listOf(
                    mapOf(
                        "id" to "parent_step",
                        "title" to "Call child",
                        "kind" to "omniflow_function",
                        "executor" to "omniflow",
                        "model_free" to true,
                        "scriptable" to true,
                        "tool" to "call_tool",
                        "callable_tool" to "call_tool",
                        "args" to mapOf("function_id" to "child_finished"),
                    )
                ),
            )
            assertTrue(store.register(child)["success"] == true)

            val callback = RecordingToolCardCallback()
            val run = handler(context, store).runMaterializedFunction(
                functionId = "parent_calls_child",
                spec = parent,
                materializedSpec = OobReusableFunctionStore.materialize(parent, emptyMap()),
                callback = callback,
                parentToolCallId = "parent-call",
                allowAgentFallback = false,
            )

            assertEquals(true, run["success"])
            assertEquals(2, callback.toolCardEvents.size)
            assertEquals("tool_started", callback.toolCardEvents[0].first)
            assertEquals("tool_completed", callback.toolCardEvents[1].first)

            val start = callback.toolCardEvents[0].second
            assertEquals("parent-call_parent_step_call_tool", start["cardId"])
            assertEquals("call_tool", start["toolName"])
            assertEquals("oob_function", start["toolType"])
            assertEquals("running", start["status"])
            assertTrue(start["argsJson"].toString().contains("child_finished"))

            val complete = callback.toolCardEvents[1].second
            assertEquals("parent-call_parent_step_call_tool", complete["cardId"])
            assertEquals("call_tool", complete["toolName"])
            assertEquals("success", complete["status"])
            assertEquals(true, complete["success"])
            assertTrue(complete["resultPreviewJson"].toString().contains("child_finished"))
            assertTrue(complete["rawResultJson"].toString().contains("step_results"))
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `nested call_tool propagates model continuation requirement to parent run`() = runBlocking {
        val context = TempFilesContext()
        try {
            val store = WorkspaceFunctionStore(context.root)
            val child = functionSpec(
                functionId = "child_needs_agent",
                steps = listOf(
                    mapOf(
                        "id" to "child_agent_step",
                        "title" to "Child agent step",
                        "kind" to "agent_call",
                        "executor" to "agent",
                        "model_free" to false,
                        "scriptable" to false,
                        "tool" to "vlm_task",
                        "callable_tool" to "vlm_task",
                        "args" to mapOf("goal" to "tap the live-only target"),
                    )
                ),
            )
            val parent = functionSpec(
                functionId = "parent_calls_agent_child",
                steps = listOf(
                    mapOf(
                        "id" to "parent_nested_step",
                        "title" to "Call agent child",
                        "kind" to "omniflow_function",
                        "executor" to "omniflow",
                        "model_free" to true,
                        "scriptable" to true,
                        "tool" to "call_tool",
                        "callable_tool" to "call_tool",
                        "args" to mapOf("function_id" to "child_needs_agent"),
                    )
                ),
            )
            assertTrue(store.register(child)["success"] == true)

            val run = handler(context, store).runMaterializedFunction(
                functionId = "parent_calls_agent_child",
                spec = parent,
                materializedSpec = OobReusableFunctionStore.materialize(parent, emptyMap()),
                allowAgentFallback = true,
            )

            assertEquals(false, run["success"])
            assertEquals(true, run["model_required"])
            val step = stepResults(run).single()
            assertEquals(false, step["success"])
            assertEquals(true, step["model_required"])
            assertEquals(true, step["nested_model_required"])
            assertEquals("child_needs_agent", step["nested_function_id"])
            val nestedSteps = step["step_results"] as? List<*>
            val childStep = nestedSteps?.single() as? Map<*, *>
            assertEquals(true, childStep?.get("model_required"))
            assertEquals("child_agent_step", childStep?.get("step_id"))
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `call_tool executes source-context steps in fixed order`() = runBlocking {
        val context = TempFilesContext()
        val pageA = pageXml("A", "com.example.a")
        val pageB = pageXml("B", "com.example.b")
        val pageC = pageXml("C", "com.example.c")
        val pageD = pageXml("D", "com.example.d")
        try {
            val spec = functionSpec(
                functionId = "fixed_order_with_source_context",
                steps = listOf(
                    finishedStepWithSource("step_a", pageA, "com.example.a"),
                    finishedStepWithSource("step_b", pageB, "com.example.b"),
                    finishedStepWithSource("step_c", pageC, "com.example.c"),
                    finishedStepWithSource("step_d", pageD, "com.example.d"),
                    finishedStepWithSource("step_key", pageD, "com.example.d"),
                ),
                extras = mapOf(
                    "agent_reuse" to mapOf(
                        "key_actions" to listOf(mapOf("step_index" to 4)),
                    ),
                ),
            )
            val backend = RecordingBackend(pageD, "com.example.d", "TestActivity")
            OmniflowActionRuntime.useBackendForTesting(backend).use {
                val run = handler(context, WorkspaceFunctionStore(context.root)).runMaterializedFunction(
                    functionId = "fixed_order_with_source_context",
                    spec = spec,
                    materializedSpec = OobReusableFunctionStore.materialize(spec, emptyMap()),
                    allowAgentFallback = false,
                )

                assertEquals(true, run["success"])
                val results = stepResults(run)
                assertEquals(5, results.size)
                assertEquals(
                    listOf("step_a", "step_b", "step_c", "step_d", "step_key"),
                    results.map { it["step_id"] }
                )
                assertNoSourceAlignmentPayload(run)
            }
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `call_tool ignores key action metadata during fixed replay`() = runBlocking {
        val context = TempFilesContext()
        val pageA = pageXml("A", "com.example.a")
        val pageB = pageXml("B", "com.example.b")
        val pageC = pageXml("C", "com.example.c")
        try {
            val spec = functionSpec(
                functionId = "fixed_order_key_metadata",
                steps = listOf(
                    finishedStepWithSource("step_a", pageA, "com.example.a"),
                    finishedStepWithSource("step_key", pageB, "com.example.b"),
                    finishedStepWithSource("step_after_key", pageC, "com.example.c"),
                ),
                extras = mapOf(
                    "agent_reuse" to mapOf(
                        "key_actions" to listOf(mapOf("step_index" to 1)),
                    ),
                ),
            )
            val backend = RecordingBackend(pageC, "com.example.c", "TestActivity")
            OmniflowActionRuntime.useBackendForTesting(backend).use {
                val run = handler(context, WorkspaceFunctionStore(context.root)).runMaterializedFunction(
                    functionId = "fixed_order_key_metadata",
                    spec = spec,
                    materializedSpec = OobReusableFunctionStore.materialize(spec, emptyMap()),
                    allowAgentFallback = false,
                )

                assertEquals(true, run["success"])
                val results = stepResults(run)
                assertEquals(listOf("step_a", "step_key", "step_after_key"), results.map { it["step_id"] })
                assertFalse(results.any { it["error_code"] == "OOB_SOURCE_ALIGNMENT_MISS" })
                assertNoSourceAlignmentPayload(run)
            }
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `call_tool keeps fixed order when no key action metadata exists`() = runBlocking {
        val context = TempFilesContext()
        val pageA = pageXml("A", "com.example.a")
        val pageB = pageXml("B", "com.example.b")
        try {
            val spec = functionSpec(
                functionId = "fixed_order_no_key_metadata",
                steps = listOf(
                    finishedStepWithSource("step_a", pageA, "com.example.a"),
                    finishedStepWithSource("step_b", pageB, "com.example.b"),
                ),
            )
            val backend = RecordingBackend(pageB, "com.example.b", "TestActivity")
            OmniflowActionRuntime.useBackendForTesting(backend).use {
                val run = handler(context, WorkspaceFunctionStore(context.root)).runMaterializedFunction(
                    functionId = "fixed_order_no_key_metadata",
                    spec = spec,
                    materializedSpec = OobReusableFunctionStore.materialize(spec, emptyMap()),
                    allowAgentFallback = false,
                )

                assertEquals(true, run["success"])
                val results = stepResults(run)
                assertEquals(listOf("step_a", "step_b"), results.map { it["step_id"] })
                assertNoSourceAlignmentPayload(run)
            }
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `registered function runs precise go to function before target start when current page differs`() = runBlocking {
        val context = TempFilesContext()
        val pageA = pageXml("A", "com.example.route")
        val pageB = pageXml("B", "com.example.route")
        val pageC = pageXml("C", "com.example.route")
        val store = WorkspaceFunctionStore(context.root)
        val udegStore = OobUdegNodeStore(context)
        val goToSpec = functionSpec(
            functionId = "route_business_start",
            steps = listOf(routeStepWithSource("route_open", pageA, pageB, "com.example.route")),
            extras = mapOf(
                "name" to "Route business start",
                "description" to "Move from A to B.",
            ),
        )
        val businessSpec = functionSpec(
            functionId = "business_from_b",
            steps = listOf(clickStepWithSource("business_click", pageB, pageC, "com.example.route")),
        )
        val backend = SwitchingBackend(
            currentXml = pageA,
            currentPackage = "com.example.route",
            currentActivity = "RouteActivity",
            transitions = listOf(pageB, pageC),
        )
        try {
            assertTrue(store.register(goToSpec)["success"] == true)
            assertTrue(store.register(businessSpec)["success"] == true)
            assertEquals(true, udegStore.upsertFunction("route_business_start", goToSpec)["success"])
            assertEquals(true, udegStore.upsertFunction("business_from_b", businessSpec)["success"])

            OmniflowActionRuntime.useBackendForTesting(backend).use {
                val run = handler(context, store).runMaterializedFunction(
                    functionId = "business_from_b",
                    spec = businessSpec,
                    materializedSpec = OobReusableFunctionStore.materialize(businessSpec, emptyMap()),
                    allowAgentFallback = false,
                )

                assertEquals(true, run["success"])
                assertEquals(1, backend.launchCount)
                assertEquals(1, backend.clickCount)
                val preGoTo = run["pre_function_go_to"] as? Map<*, *>
                assertEquals(true, preGoTo?.get("success"))
                assertEquals(listOf("route_business_start"), preGoTo?.get("function_ids"))
                assertEquals(listOf("business_click"), stepResults(run).map { it["step_id"] })
            }
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `go_to_node executes UTG path locally`() = runBlocking {
        val context = TempFilesContext()
        try {
            val spec = functionSpec(
                functionId = "graph_path",
                steps = listOf(
                    mapOf(
                        "id" to "graph_step",
                        "title" to "Navigate graph",
                        "kind" to "omniflow_graph",
                        "executor" to "omniflow",
                        "model_free" to true,
                        "scriptable" to true,
                        "tool" to "go_to_node",
                        "callable_tool" to "go_to_node",
                        "args" to mapOf(
                            "node_id" to "node_done",
                            "path" to listOf(
                                mapOf(
                                    "edge_id" to "edge_done",
                                    "to_node_id" to "node_done",
                                    "action" to "finished",
                                    "args" to emptyMap<String, Any?>(),
                                )
                            ),
                        ),
                    )
                ),
            )

            val handler = handler(context, WorkspaceFunctionStore(context.root))
            val run = handler.runMaterializedFunction(
                functionId = "graph_path",
                spec = spec,
                materializedSpec = OobReusableFunctionStore.materialize(spec, emptyMap()),
                allowAgentFallback = false,
            )

            assertEquals(true, run["success"])
            assertEquals(false, run["model_required"])
            val step = stepResults(run).single()
            assertEquals("omniflow_graph", step["executor"])
            assertEquals(true, step["success"])
            assertEquals(1, (step["path_length"] as Number).toInt())
            val pathSteps = step["step_results"] as? List<*>
            assertEquals(1, pathSteps?.size)
            val primitive = pathSteps?.single() as? Map<*, *>
            assertEquals("finished", primitive?.get("tool"))
            assertEquals("omniflow", primitive?.get("executor"))
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `nested call_tool missing child fails locally without model continuation`() = runBlocking {
        val context = TempFilesContext()
        try {
            val spec = functionSpec(
                functionId = "parent_missing_child",
                steps = listOf(
                    mapOf(
                        "id" to "missing_child",
                        "title" to "Missing child",
                        "kind" to "omniflow_function",
                        "executor" to "omniflow",
                        "model_free" to true,
                        "scriptable" to true,
                        "tool" to "call_tool",
                        "callable_tool" to "call_tool",
                        "args" to mapOf("function_id" to "does_not_exist"),
                    )
                ),
            )

            val handler = handler(context, WorkspaceFunctionStore(context.root))
            val run = handler.runMaterializedFunction(
                functionId = "parent_missing_child",
                spec = spec,
                materializedSpec = OobReusableFunctionStore.materialize(spec, emptyMap()),
                allowAgentFallback = false,
            )

            assertEquals(false, run["success"])
            assertEquals(false, run["model_required"])
            assertEquals(false, run["delegated_tool_used"])
            assertEquals("OOB_FUNCTION_NOT_FOUND", run["error_code"])
            val step = stepResults(run).single()
            assertEquals("omniflow_function", step["executor"])
            assertEquals("OOB_FUNCTION_NOT_FOUND", step["error_code"])
            assertEquals(false, step["success"])
            assertFalse(step["model_required"] == true)
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `invalid omniflow step refetches current page before failing`() = runBlocking {
        val context = TempFilesContext()
        val backend = RecordingBackend(
            currentXml = CURRENT_PAGE_XML,
            currentPackage = "com.example.current",
            currentActivity = "CurrentActivity",
        )
        try {
            val spec = functionSpec(
                functionId = "invalid_click_refreshes_page",
                steps = listOf(
                    mapOf(
                        "id" to "bad_click",
                        "title" to "Bad click",
                        "kind" to "omniflow_action",
                        "executor" to "omniflow",
                        "model_free" to true,
                        "scriptable" to true,
                        "tool" to "click",
                        "callable_tool" to "click",
                        "args" to mapOf("y" to 240),
                    )
                ),
            )
            OmniflowActionRuntime.useBackendForTesting(backend).use {
                val run = handler(context, WorkspaceFunctionStore(context.root)).runMaterializedFunction(
                    functionId = "invalid_click_refreshes_page",
                    spec = spec,
                    materializedSpec = OobReusableFunctionStore.materialize(spec, emptyMap()),
                    allowAgentFallback = false,
                )

                assertEquals(false, run["success"])
                assertEquals(0, backend.clickCount)
                assertTrue(backend.currentXmlReadCount >= 1)
                val step = stepResults(run).single()
                assertEquals(false, step["success"])
                val recovery = step["recovery"] as? Map<*, *>
                assertNotNull(recovery)
                assertEquals(true, recovery?.get("refetched_current_page"))
                assertEquals("com.example.current", recovery?.get("effective_package"))
                assertEquals("CurrentActivity", recovery?.get("activity_name"))
                assertEquals(CURRENT_PAGE_XML, recovery?.get("observation_xml"))
            }
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `local omniflow failure does not invoke vlm fallback when disabled`() = runBlocking {
        val context = TempFilesContext()
        val backend = RecordingBackend(
            currentXml = CURRENT_PAGE_XML,
            currentPackage = "com.example.current",
            currentActivity = "CurrentActivity",
        )
        try {
            val spec = functionSpec(
                functionId = "bad_click_no_fallback",
                steps = listOf(
                    mapOf(
                        "id" to "bad_click",
                        "title" to "Bad click",
                        "kind" to "omniflow_action",
                        "executor" to "omniflow",
                        "model_free" to true,
                        "scriptable" to true,
                        "tool" to "click",
                        "callable_tool" to "click",
                        "args" to mapOf(
                            "y" to 240,
                            "target_description" to "visible target",
                        ),
                    )
                ),
            )
            val handler = handler(context, WorkspaceFunctionStore(context.root))
            val router = CapturingRouter()
            handler.router = router
            OmniflowActionRuntime.useBackendForTesting(backend).use {
                val run = handler.runMaterializedFunction(
                    functionId = "bad_click_no_fallback",
                    spec = spec,
                    materializedSpec = OobReusableFunctionStore.materialize(spec, emptyMap()),
                    callback = RecordingToolCardCallback(),
                    toolHandle = NoOpAgentRunControl.beginToolExecution("click", "bad-click"),
                    env = FakeEnv(context),
                    allowAgentFallback = false,
                )

                assertEquals(false, run["success"])
                assertEquals(false, run["model_required"])
                assertEquals("", router.toolName)
                val step = stepResults(run).single()
                assertFalse(step.containsKey("model_required"))
                assertEquals("OOB_OMNIFLOW_STEP_FAILED", step["error_code"])
            }
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `provider-owned agent call_tool spec is executed locally`() = runBlocking {
        val context = TempFilesContext()
        try {
            val store = WorkspaceFunctionStore(context.root)
            assertTrue(
                store.register(
                    functionSpec(
                        functionId = "legacy_child",
                        steps = listOf(finishedStep("legacy_child_done")),
                    )
                )["success"] == true
            )
            val legacyParent = functionSpec(
                functionId = "legacy_parent",
                steps = listOf(
                    mapOf(
                        "id" to "legacy_call",
                        "title" to "Legacy call",
                        "kind" to "agent_call",
                        "executor" to "agent",
                        "scriptable" to false,
                        "tool" to "call_tool",
                        "callable_tool" to "oob.agent.run",
                        "args" to mapOf("function_id" to "legacy_child"),
                        "agent_call" to mapOf(
                            "tool" to "oob.agent.run",
                            "args" to mapOf(
                                "original_tool" to "call_tool",
                                "original_args" to mapOf("function_id" to "legacy_child"),
                            ),
                            "reason" to "provider_owned_replay_requires_omniflow",
                        ),
                    )
                ),
            )

            val handler = handler(context, store)
            val run = handler.runMaterializedFunction(
                functionId = "legacy_parent",
                spec = legacyParent,
                materializedSpec = OobReusableFunctionStore.materialize(legacyParent, emptyMap()),
                allowAgentFallback = false,
            )

            assertEquals(true, run["success"])
            assertEquals(false, run["model_required"])
            val step = stepResults(run).single()
            assertEquals("omniflow_function", step["executor"])
            assertEquals("legacy_child", step["nested_function_id"])
            assertEquals(true, step["success"])
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `recursive call_tool is rejected by local runner`() = runBlocking {
        val context = TempFilesContext()
        try {
            val store = WorkspaceFunctionStore(context.root)
            val spec = functionSpec(
                functionId = "self_recursive",
                steps = listOf(
                    mapOf(
                        "id" to "self_call",
                        "title" to "Self call",
                        "kind" to "omniflow_function",
                        "executor" to "omniflow",
                        "model_free" to true,
                        "scriptable" to true,
                        "tool" to "call_tool",
                        "callable_tool" to "call_tool",
                        "args" to mapOf("function_id" to "self_recursive"),
                    )
                ),
            )
            assertTrue(store.register(spec)["success"] == true)

            val handler = handler(context, store)
            val run = handler.runMaterializedFunction(
                functionId = "self_recursive",
                spec = spec,
                materializedSpec = OobReusableFunctionStore.materialize(spec, emptyMap()),
                allowAgentFallback = false,
            )

            assertEquals(false, run["success"])
            assertEquals(false, run["model_required"])
            assertTrue(run["run_id"]?.toString()?.startsWith("omniflow_run_") == true)
            assertEquals(run["run_id"], run["audit_run_id"])
            val step = stepResults(run).single()
            assertEquals("omniflow_function", step["executor"])
            assertEquals("OOB_FUNCTION_RECURSION", step["error_code"])
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `canRunFullyWithOmniflow includes graph and function execution tools`() {
        val context = TempFilesContext()
        try {
            val handler = handler(context, WorkspaceFunctionStore(context.root))
            val spec = functionSpec(
                functionId = "all_local",
                steps = listOf(
                    finishedStep("primitive"),
                    mapOf(
                        "id" to "graph",
                        "executor" to "omniflow",
                        "tool" to "go_to_node",
                        "callable_tool" to "go_to_node",
                        "args" to mapOf(
                            "path" to listOf(mapOf("action" to "finished")),
                        ),
                    ),
                    mapOf(
                        "id" to "function",
                        "executor" to "omniflow",
                        "tool" to "call_tool",
                        "callable_tool" to "call_tool",
                        "args" to mapOf("function_id" to "anything"),
                    ),
                ),
            )
            val materialized = OobReusableFunctionStore.materialize(spec, emptyMap())

            assertTrue(handler.canRunFullyWithOmniflow(materialized))
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `canonical actions only function replays without legacy execution steps`() = runBlocking {
        val context = TempFilesContext()
        try {
            val spec = linkedMapOf<String, Any?>(
                "schema_version" to "omniflow.function.v1",
                "function_id" to "canonical_finished",
                "name" to "omniflow_exported_finished",
                "description" to "Canonical OmniFlow function",
                "parameters" to linkedMapOf(
                    "type" to "object",
                    "properties" to emptyMap<String, Any?>(),
                    "required" to emptyList<String>(),
                    "additionalProperties" to false,
                ),
                "actions" to listOf(
                    linkedMapOf(
                        "type" to "finished",
                        "content" to "done",
                    )
                ),
            )

            val run = handler(context, WorkspaceFunctionStore(context.root)).runMaterializedFunction(
                functionId = "canonical_finished",
                spec = spec,
                materializedSpec = OobReusableFunctionStore.materialize(spec, emptyMap()),
                allowAgentFallback = false,
            )

            assertEquals(true, run["success"])
            assertEquals(false, run["model_required"])
            val step = stepResults(run).single()
            assertEquals("omniflow", step["executor"])
            assertEquals("finished", step["tool"])
            assertEquals(true, step["success"])
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `vlm task runlog converts to function and replays with changed drink`() = runBlocking {
        val context = TempFilesContext()
        val backend = RecordingBackend(
            currentXml = "<hierarchy><node text=\"美团\" package=\"com.sankuai.meituan\"/></hierarchy>",
            currentPackage = "com.sankuai.meituan",
            currentActivity = "MainActivity",
        )
        try {
            val spec = requireNotNull(
                RunLogReusableFunctionCompiler.compile(
                    InternalRunLogRecord(
                        runId = "run-vlm-meituan-coffee",
                        goal = "打开美团点一杯咖啡",
                        source = "agent_tool",
                        toolName = "vlm_task",
                        operationDescription = "打开美团点一杯咖啡",
                        success = true,
                        cards = listOf(
                            runLogCard(
                                toolName = "vlm_task",
                                args = mapOf("goal" to "打开美团点一杯咖啡"),
                                title = "视觉执行：打开美团点一杯咖啡",
                            ),
                            runLogCard(
                                toolName = "open_app",
                                args = mapOf("package_name" to "com.sankuai.meituan"),
                                title = "Open Meituan",
                                afterPackage = "com.sankuai.meituan",
                            ),
                            runLogCard(
                                toolName = "input_text",
                                args = mapOf(
                                    "target_description" to "美团搜索框",
                                    "text" to "咖啡",
                                    "x" to 360,
                                    "y" to 180,
                                ),
                                title = "order item",
                                beforePackage = "com.sankuai.meituan",
                                afterPackage = "com.sankuai.meituan",
                            ),
                            runLogCard(
                                toolName = "click",
                                args = mapOf(
                                    "target_description" to "搜索",
                                    "x" to 920,
                                    "y" to 180,
                                ),
                                title = "Tap search",
                                beforePackage = "com.sankuai.meituan",
                                afterPackage = "com.sankuai.meituan",
                            ),
                            runLogCard(
                                toolName = "finished",
                                args = mapOf("content" to "已准备下单咖啡"),
                                title = "Finished coffee flow",
                                beforePackage = "com.sankuai.meituan",
                                afterPackage = "com.sankuai.meituan",
                            ),
                        ),
                    )
                )
            )
            val parameterName = OobFunctionSchemaBuilder.parameterNames(spec).single()
            assertEquals("order_item", parameterName)
            assertEquals("run-vlm-meituan-coffee", (spec["source"] as Map<*, *>)["run_id"])

            val materialized = OobReusableFunctionStore.materialize(
                spec,
                mapOf(parameterName to "奶茶"),
            )
            val steps = OobFunctionSchemaBuilder.materializedSteps(materialized)
            assertEquals(listOf("open_app", "input_text", "click", "finished"), steps.map { it["tool"] })
            val inputArgs = steps[1]["args"] as Map<*, *>
            assertEquals("奶茶", inputArgs["text"])
            assertFalse(inputArgs.containsKey("content"))
            assertFalse(inputArgs.containsKey("value"))

            OmniflowActionRuntime.useBackendForTesting(backend).use {
                val run = handler(context, WorkspaceFunctionStore(context.root)).runMaterializedFunction(
                    functionId = OobFunctionSchemaBuilder.functionId(spec),
                    spec = spec,
                    materializedSpec = materialized,
                    allowAgentFallback = false,
                )

                assertEquals(true, run["success"])
                assertEquals(false, run["model_required"])
                assertEquals(listOf("com.sankuai.meituan"), backend.launchedPackages)
                assertEquals(listOf("奶茶"), backend.inputTexts)
                assertEquals(1, backend.clickCount)
                assertEquals(4, stepResults(run).size)
            }
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `input text runtime argument overrides recorded content during omniflow replay`() = runBlocking {
        val context = TempFilesContext()
        val backend = RecordingBackend(
            currentXml = "<hierarchy><node text=\"搜索框\" package=\"com.xingin.xhs\"/></hierarchy>",
            currentPackage = "com.xingin.xhs",
            currentActivity = "MainActivity",
        )
        try {
            val spec = OobFunctionParameterBindingNormalizer.normalize(
                functionSpec(
                    functionId = "xiaohongshu_search_keyword",
                    steps = listOf(
                        mapOf(
                            "id" to "step_input_keyword",
                            "title" to "Input keyword",
                            "kind" to "omniflow_action",
                            "executor" to "omniflow",
                            "model_free" to true,
                            "scriptable" to true,
                            "tool" to "input_text",
                            "callable_tool" to "input_text",
                            "args" to mapOf(
                                "text" to "彩票",
                                "target_description" to "搜索框",
                            ),
                        ),
                    ),
                    extras = mapOf(
                        "parameters" to mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "input_text_1" to mapOf(
                                    "type" to "string",
                                    "description" to "搜索关键词",
                                    "default" to "彩票",
                                ),
                            ),
                        ),
                    ),
                )
            )
            val materialized = OobReusableFunctionStore.materialize(
                spec,
                mapOf("input_text_1" to "猫猫"),
            )
            val materializedStepArgs = OobFunctionSchemaBuilder.materializedSteps(materialized)
                .single()["args"] as Map<*, *>
            assertEquals("猫猫", materializedStepArgs["text"])
            assertFalse(materializedStepArgs.containsKey("content"))
            assertFalse(materializedStepArgs.containsKey("value"))

            OmniflowActionRuntime.useBackendForTesting(backend).use {
                val run = handler(context, WorkspaceFunctionStore(context.root)).runMaterializedFunction(
                    functionId = OobFunctionSchemaBuilder.functionId(spec),
                    spec = spec,
                    materializedSpec = materialized,
                    allowAgentFallback = false,
                )

                assertEquals(true, run["success"])
                assertEquals(listOf("猫猫"), backend.inputTexts)
            }
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `call_tool without function id requires vlm continuation when router unavailable`() = runBlocking {
        val context = TempFilesContext()
        try {
            val spec = functionSpec(
                functionId = "needs_router",
                steps = listOf(
                    mapOf(
                        "id" to "call_vlm",
                        "title" to "Call VLM",
                        "kind" to "tool_call",
                        "executor" to "tool",
                        "scriptable" to true,
                        "tool" to "call_tool",
                        "callable_tool" to "call_tool",
                        "args" to mapOf(
                            "tool_name" to "vlm_task",
                            "arguments" to mapOf("goal" to "tap settings"),
                        ),
                    )
                ),
            )

            val handler = handler(context, WorkspaceFunctionStore(context.root))
            val run = handler.runMaterializedFunction(
                functionId = "needs_router",
                spec = spec,
                materializedSpec = OobReusableFunctionStore.materialize(spec, emptyMap()),
                allowAgentFallback = true,
            )

            assertEquals(false, run["success"])
            assertEquals(true, run["model_required"])
            val step = stepResults(run).single()
            assertEquals("vlm_step", step["executor"])
            assertEquals("vlm_task", step["tool"])
            assertEquals(true, step["model_required"])
            assertEquals(true, step["vlm_step_required"])
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `call_tool with vlm task delegates through router when available`() = runBlocking {
        val context = TempFilesContext()
        try {
            val handler = handler(context, WorkspaceFunctionStore(context.root))
            val router = CapturingRouter()
            handler.router = router
            val spec = functionSpec(
                functionId = "delegates_vlm",
                steps = listOf(
                    mapOf(
                        "id" to "call_vlm",
                        "title" to "Call VLM",
                        "kind" to "tool_call",
                        "executor" to "tool",
                        "scriptable" to true,
                        "tool" to "call_tool",
                        "callable_tool" to "call_tool",
                        "args" to mapOf(
                            "tool_name" to "vlm_task",
                            "arguments" to mapOf(
                                "goal" to "open settings",
                                "model" to "scene.vlm.operation.primary",
                                "maxSteps" to 3,
                                "startFromCurrent" to true,
                            ),
                        ),
                    )
                ),
            )

            val run = handler.runMaterializedFunction(
                functionId = "delegates_vlm",
                spec = spec,
                materializedSpec = OobReusableFunctionStore.materialize(spec, emptyMap()),
                toolHandle = cn.com.omnimind.bot.agent.NoOpAgentRunControl
                    .beginToolExecution("call_tool", "test"),
                env = FakeEnv(context),
                allowAgentFallback = true,
            )

            assertEquals(true, run["success"])
            assertEquals(true, run["delegated_tool_used"])
            assertEquals(false, run["model_required"])
            assertEquals("vlm_task", router.toolName)
            assertEquals("open settings", router.args?.get("goal")?.jsonPrimitive?.contentOrNull)
            assertEquals("scene.vlm.operation.primary", router.args?.get("model")?.jsonPrimitive?.contentOrNull)
            assertEquals(3, router.args?.get("maxSteps")?.jsonPrimitive?.contentOrNull?.toInt())
            assertEquals(true, router.args?.get("startFromCurrent")?.jsonPrimitive?.contentOrNull?.toBoolean())
            val step = stepResults(run).single()
            assertEquals("tool", step["executor"])
            assertEquals("call_tool", step["delegated_from"])
            assertEquals("vlm_task", step["tool"])
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `call_tool with vlm task delegates through router without callback`() = runBlocking {
        val context = TempFilesContext()
        try {
            val handler = handler(context, WorkspaceFunctionStore(context.root))
            val router = CapturingRouter()
            handler.router = router
            val spec = functionSpec(
                functionId = "direct_guiagent_vlm",
                steps = listOf(
                    mapOf(
                        "id" to "guiagent_step",
                        "title" to "GUIagent current page smoke",
                        "kind" to "tool_call",
                        "executor" to "tool",
                        "scriptable" to true,
                        "tool" to "call_tool",
                        "callable_tool" to "call_tool",
                        "args" to mapOf(
                            "tool_name" to "vlm_task",
                            "arguments" to mapOf(
                                "goal" to "tap the visible OK button if present",
                                "maxSteps" to 2,
                                "startFromCurrent" to true,
                                "needSummary" to false,
                            ),
                        ),
                    )
                ),
            )

            val run = handler.runMaterializedFunction(
                functionId = "direct_guiagent_vlm",
                spec = spec,
                materializedSpec = OobReusableFunctionStore.materialize(spec, emptyMap()),
                env = FakeEnv(context),
                allowAgentFallback = true,
            )

            assertEquals(true, run["success"])
            assertEquals(true, run["delegated_tool_used"])
            assertEquals(false, run["model_required"])
            assertEquals("vlm_task", router.toolName)
            assertEquals(
                "tap the visible OK button if present",
                router.args?.get("goal")?.jsonPrimitive?.contentOrNull
            )
            assertEquals(2, router.args?.get("maxSteps")?.jsonPrimitive?.contentOrNull?.toInt())
            assertEquals(true, router.args?.get("startFromCurrent")?.jsonPrimitive?.contentOrNull?.toBoolean())
            val step = stepResults(run).single()
            assertEquals("tool", step["executor"])
            assertEquals("call_tool", step["delegated_from"])
            assertEquals("vlm_task", step["tool"])
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `dynamic function tool ignores presentation metadata when binding arguments`() = runBlocking {
        val context = TempFilesContext()
        try {
            val store = WorkspaceFunctionStore(context.root)
            val spec = functionSpec(
                functionId = "search_cat",
                steps = listOf(
                    finishedStep("done"),
                ),
                extras = mapOf(
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "search_query" to mapOf(
                                "type" to "string",
                                "x_oob_bindings" to listOf(
                                    "$.execution.steps[0].args.content",
                                ),
                            ),
                        ),
                    ),
                ),
            )
            OobReusableFunctionStore.register(context, spec)
            assertTrue(store.register(spec)["success"] == true)
            val handler = handler(context, store)
            val args = Json.parseToJsonElement(
                """
                {
                  "tool_title": "小红书搜索猫猫",
                  "function_id": "search_cat",
                  "arguments": {
                    "search_query": "猫猫"
                  }
                }
                """.trimIndent()
            ).jsonObject

            val result = handler.execute(
                toolCall = AssistantToolCall(
                    id = "call_search_cat",
                    type = "function",
                    function = cn.com.omnimind.baselib.llm.AssistantToolCallFunction(
                        name = "search_cat",
                        arguments = args.toString(),
                    ),
                ),
                args = args,
                runtimeDescriptor = AgentToolRegistry.RuntimeToolDescriptor(
                    name = "search_cat",
                    displayName = "小红书搜索",
                    toolType = "oob_function",
                ),
                env = FakeEnv(context),
                callback = RecordingToolCardCallback(),
                toolHandle = NoOpAgentRunControl.beginToolExecution("search_cat", "call_search_cat"),
            )

            assertTrue(result is ToolExecutionResult.ContextResult)
            result as ToolExecutionResult.ContextResult
            assertEquals(true, result.success)
            assertFalse(result.rawResultJson.contains("tool_title"))
            assertTrue(result.rawResultJson.contains("search_query"))
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `oob function run tool executes registered function with arguments`() = runBlocking {
        val context = TempFilesContext()
        val backend = RecordingBackend(
            currentXml = CURRENT_PAGE_XML,
            currentPackage = "com.example.current",
            currentActivity = "MainActivity",
        )
        try {
            val store = WorkspaceFunctionStore(context.root)
            val spec = functionSpec(
                functionId = "search_keyword",
                steps = listOf(
                    mapOf(
                        "id" to "input_keyword",
                        "title" to "Input keyword",
                        "kind" to "omniflow_action",
                        "executor" to "omniflow",
                        "model_free" to true,
                        "scriptable" to true,
                        "tool" to "input_text",
                        "callable_tool" to "input_text",
                        "args" to mapOf("text" to "原始关键词"),
                    ),
                ),
                extras = mapOf(
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "keyword" to mapOf(
                                "type" to "string",
                                "x_oob_bindings" to listOf(
                                    "$.execution.steps[0].args.text",
                                ),
                            ),
                        ),
                        "required" to listOf("keyword"),
                    ),
                ),
            )
            OobReusableFunctionStore.register(context, spec)
            assertTrue(store.register(spec)["success"] == true)
            val handler = handler(context, store)
            val args = Json.parseToJsonElement(
                """
                {
                  "tool_title": "小红书搜索猫猫",
                  "function_id": "search_keyword",
                  "arguments": {
                    "keyword": "猫猫"
                  }
                }
                """.trimIndent()
            ).jsonObject

            OmniflowActionRuntime.useBackendForTesting(backend).use {
                val result = handler.execute(
                    toolCall = AssistantToolCall(
                        id = "call_call_tool",
                        type = "function",
                        function = cn.com.omnimind.baselib.llm.AssistantToolCallFunction(
                            name = "call_tool",
                            arguments = args.toString(),
                        ),
                    ),
                    args = args,
                    runtimeDescriptor = AgentToolRegistry.RuntimeToolDescriptor(
                        name = "call_tool",
                        displayName = "执行复用指令",
                        toolType = "oob_function",
                    ),
                    env = FakeEnv(context),
                    callback = RecordingToolCardCallback(),
                    toolHandle = NoOpAgentRunControl.beginToolExecution(
                        "call_tool",
                        "call_call_tool"
                    ),
                )

                assertTrue(result is ToolExecutionResult.ContextResult)
                result as ToolExecutionResult.ContextResult
                assertEquals(true, result.success)
                assertEquals(listOf("猫猫"), backend.inputTexts)
                assertTrue(result.rawResultJson.contains("search_keyword"))
                assertTrue(result.rawResultJson.contains("keyword"))
            }
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `registering edited function invalidates stale compiled function cache`() = runBlocking {
        val context = TempFilesContext()
        try {
            val functionId = "edited_search_keyword"
            val store = WorkspaceFunctionStore(context.root)
            OmniflowFunctionStore.save(
                context,
                OmniflowFunction(
                    id = functionId,
                    name = "Old search keyword",
                    steps = listOf(
                        UIStep(
                            id = "old_input_keyword",
                            title = "Input old keyword",
                            toolName = "input_text",
                            arguments = mapOf("text" to ParameterValue.Literal("旧关键词")),
                        )
                    ),
                )
            )
            assertNotNull(OmniflowFunctionStore.get(context, functionId))

            val editedSpec = functionSpec(
                functionId = functionId,
                steps = listOf(
                    mapOf(
                        "id" to "new_input_keyword",
                        "title" to "Input new keyword",
                        "kind" to "omniflow_action",
                        "executor" to "omniflow",
                        "model_free" to true,
                        "scriptable" to true,
                        "tool" to "input_text",
                        "callable_tool" to "input_text",
                        "args" to mapOf("text" to "新关键词"),
                    ),
                ),
            )

            val register = OobFunctionRepository(context, store).register(editedSpec)

            assertEquals(true, register["success"])
            assertEquals(true, register["compiled_function_invalidated"])
            assertEquals(null, OmniflowFunctionStore.get(context, functionId))
            val storedSpec = OobFunctionRepository(context, store).get(functionId)!!
            val steps = OobFunctionSchemaBuilder.materializedSteps(storedSpec)
            assertEquals("new_input_keyword", steps.single()["id"])
            assertEquals("新关键词", (steps.single()["args"] as Map<*, *>)["text"])
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `function arguments can propagate through nested function binding tables`() = runBlocking {
        val context = TempFilesContext()
        val backend = RecordingBackend(
            currentXml = CURRENT_PAGE_XML,
            currentPackage = "com.example.current",
            currentActivity = "MainActivity",
        )
        try {
            val store = WorkspaceFunctionStore(context.root)
            val leaf = functionSpec(
                functionId = "leaf_input_search",
                steps = listOf(
                    mapOf(
                        "id" to "leaf_input",
                        "title" to "Input search query",
                        "kind" to "omniflow_action",
                        "executor" to "omniflow",
                        "model_free" to true,
                        "scriptable" to true,
                        "tool" to "input_text",
                        "callable_tool" to "input_text",
                        "args" to mapOf("text" to "彩票"),
                    ),
                ),
                extras = mapOf(
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "search_query" to mapOf(
                                "type" to "string",
                                "x_oob_bindings" to listOf(
                                    "$.execution.steps[0].args.text",
                                ),
                            ),
                        ),
                    ),
                ),
            )
            val middle = functionSpec(
                functionId = "middle_call_leaf",
                steps = listOf(
                    mapOf(
                        "id" to "middle_call",
                        "title" to "Call leaf input",
                        "kind" to "omniflow_function",
                        "executor" to "omniflow",
                        "model_free" to true,
                        "scriptable" to true,
                        "tool" to "call_tool",
                        "callable_tool" to "call_tool",
                        "args" to mapOf(
                            "function_id" to "leaf_input_search",
                            "arguments" to mapOf("search_query" to "彩票"),
                        ),
                    ),
                ),
                extras = mapOf(
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "search_query" to mapOf(
                                "type" to "string",
                                "x_oob_bindings" to listOf(
                                    "$.execution.steps[0].args.arguments.search_query",
                                ),
                            ),
                        ),
                    ),
                ),
            )
            val parent = functionSpec(
                functionId = "parent_call_middle",
                steps = listOf(
                    mapOf(
                        "id" to "parent_call",
                        "title" to "Call middle",
                        "kind" to "omniflow_function",
                        "executor" to "omniflow",
                        "model_free" to true,
                        "scriptable" to true,
                        "tool" to "call_tool",
                        "callable_tool" to "call_tool",
                        "args" to mapOf(
                            "function_id" to "middle_call_leaf",
                            "arguments" to mapOf("search_query" to "彩票"),
                        ),
                    ),
                ),
                extras = mapOf(
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "search_query" to mapOf(
                                "type" to "string",
                                "x_oob_bindings" to listOf(
                                    "$.execution.steps[0].args.arguments.search_query",
                                ),
                            ),
                        ),
                    ),
                ),
            )
            assertTrue(store.register(leaf)["success"] == true)
            assertTrue(store.register(middle)["success"] == true)
            OmniflowActionRuntime.useBackendForTesting(backend).use {
                val run = handler(context, store).runMaterializedFunction(
                    functionId = "parent_call_middle",
                    spec = parent,
                    materializedSpec = OobReusableFunctionStore.materialize(
                        parent,
                        mapOf("search_query" to "猫猫"),
                    ),
                    allowAgentFallback = false,
                )

                assertEquals(true, run["success"])
                assertEquals(listOf("猫猫"), backend.inputTexts)
                val parentStep = stepResults(run).single()
                assertEquals("middle_call_leaf", parentStep["nested_function_id"])
                val middleStepResults = parentStep["step_results"] as List<*>
                val middleStep = middleStepResults.single() as Map<*, *>
                assertEquals("leaf_input_search", middleStep["nested_function_id"])
            }
        } finally {
            context.root.deleteRecursively()
        }
    }

    private fun handler(
        context: Context,
        store: WorkspaceFunctionStore,
    ): OobFunctionToolHandler = OobFunctionToolHandler(
        context = context,
        helper = SharedHelper(
            context = context,
            json = Json {
                ignoreUnknownKeys = true
                isLenient = true
                encodeDefaults = false
            },
        ),
    ).apply {
        workspaceFunctionStore = store
    }

    private fun functionSpec(
        functionId: String,
        steps: List<Map<String, Any?>>,
        extras: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "schema_version" to "oob.reusable_function.v1",
        "function_id" to functionId,
        "name" to functionId,
        "description" to functionId,
        "parameters" to emptyList<Any?>(),
        "execution" to linkedMapOf(
            "kind" to "tool_sequence",
            "runner" to "oob_tool_sequence",
            "entrypoint" to "execute",
            "steps" to steps,
            "step_count" to steps.size,
        ),
    ).apply {
        putAll(extras)
    }

    private fun finishedStep(stepId: String): Map<String, Any?> = mapOf(
        "id" to stepId,
        "title" to "Finished",
        "kind" to "omniflow_action",
        "executor" to "omniflow",
        "model_free" to true,
        "scriptable" to true,
        "omniflow_action" to "finished",
        "tool" to "finished",
        "callable_tool" to "finished",
        "args" to emptyMap<String, Any?>(),
    )

    private fun finishedStepWithSource(
        stepId: String,
        sourceXml: String,
        packageName: String,
    ): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
        putAll(finishedStep(stepId))
        put(
            "source_context",
            mapOf(
                "src_ctx" to mapOf(
                    "page" to sourceXml,
                    "package_name" to packageName,
                ),
            ),
        )
    }

    private fun clickStepWithSource(
        stepId: String,
        sourceXml: String,
        destinationXml: String,
        packageName: String,
    ): Map<String, Any?> = mapOf(
        "id" to stepId,
        "title" to stepId,
        "kind" to "omniflow_action",
        "executor" to "omniflow",
        "model_free" to true,
        "scriptable" to true,
        "omniflow_action" to "click",
        "tool" to "click",
        "callable_tool" to "click",
        "args" to mapOf(
            "x" to 540,
            "y" to 320,
            "target_description" to stepId,
        ),
        "source_context" to mapOf(
            "src_ctx" to mapOf(
                "page" to sourceXml,
                "package_name" to packageName,
            ),
            "dst_ctx" to mapOf(
                "page" to destinationXml,
                "package_name" to packageName,
            ),
        ),
    )

    private fun routeStepWithSource(
        stepId: String,
        sourceXml: String,
        destinationXml: String,
        packageName: String,
    ): Map<String, Any?> = mapOf(
        "id" to stepId,
        "title" to stepId,
        "kind" to "omniflow_action",
        "executor" to "omniflow",
        "model_free" to true,
        "scriptable" to true,
        "omniflow_action" to "open_app",
        "tool" to "open_app",
        "callable_tool" to "open_app",
        "args" to mapOf("package_name" to packageName),
        "source_context" to mapOf(
            "src_ctx" to mapOf(
                "page" to sourceXml,
                "package_name" to packageName,
            ),
            "dst_ctx" to mapOf(
                "page" to destinationXml,
                "package_name" to packageName,
            ),
        ),
    )

    private fun pageXml(label: String, packageName: String): String = """
        <hierarchy rotation="0">
          <node index="0" text="$label" resource-id="$packageName:id/title" class="android.widget.TextView" package="$packageName" content-desc="$label" bounds="[0,0][1080,220]" clickable="false" focusable="false" />
          <node index="1" text="Action $label" resource-id="$packageName:id/action" class="android.widget.Button" package="$packageName" content-desc="Action $label" bounds="[0,260][1080,420]" clickable="true" focusable="true" />
        </hierarchy>
    """.trimIndent()

    private fun assertNoSourceAlignmentPayload(run: Map<String, Any?>) {
        assertFalse(run.containsKey("pending_action_stack"))
        assertFalse(run.containsKey("source_alignment"))
        assertFalse(run.containsKey("source_alignment_enabled"))
        assertFalse(run.containsKey("skipped_by_source_alignment_count"))
    }

    private fun runLogCard(
        toolName: String,
        args: Map<String, Any?>,
        title: String,
        beforePackage: String = "com.android.launcher",
        afterPackage: String = beforePackage,
    ): Map<String, Any?> {
        return linkedMapOf(
            "tool_name" to toolName,
            "title" to title,
            "args" to args,
            "success" to true,
            "before" to linkedMapOf(
                "package_name" to beforePackage,
            ),
            "after" to linkedMapOf(
                "package_name" to afterPackage,
            ),
        )
    }

    private fun stepResults(run: Map<String, Any?>): List<Map<String, Any?>> {
        val raw = run["step_results"] as? List<*>
        assertNotNull(raw)
        return raw!!.map { step ->
            @Suppress("UNCHECKED_CAST")
            step as Map<String, Any?>
        }
    }

    private class TempFilesContext : ContextWrapper(null) {
        val root: File = Files.createTempDirectory("oob-function-runner-test").toFile()
        private val prefs = InMemoryPrefs()

        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = root

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

    private class StoppingToolHandle : cn.com.omnimind.bot.agent.AgentToolExecutionHandle {
        override val generation: Long = 1L
        override val toolName: String = "call_tool"
        override val toolCallId: String = "stop-test"

        override fun bindCardId(cardId: String) = Unit

        override fun currentCardId(): String? = null

        override fun bindExecutionJob(job: Job) = Unit

        override fun bindStopAction(action: (suspend () -> Unit)?) = Unit

        override fun recordProgress(summary: String, extras: Map<String, Any?>) = Unit

        override fun latestProgressSnapshot(): AgentToolProgressSnapshot = AgentToolProgressSnapshot()

        override fun isManualStopRequested(): Boolean = true

        override fun throwIfStopRequested() {
            throw ManualToolStopCancellationException("test stop")
        }

        override fun complete() = Unit
    }

    private class StopActionHandle : cn.com.omnimind.bot.agent.AgentToolExecutionHandle {
        override val generation: Long = 1L
        override val toolName: String = "call_tool"
        override val toolCallId: String = "stop-action-test"
        private val stopAction = CompletableDeferred<suspend () -> Unit>()

        suspend fun awaitStopAction(): suspend () -> Unit = stopAction.await()

        override fun bindCardId(cardId: String) = Unit

        override fun currentCardId(): String? = null

        override fun bindExecutionJob(job: Job) = Unit

        override fun bindStopAction(action: (suspend () -> Unit)?) {
            if (action != null && !stopAction.isCompleted) {
                stopAction.complete(action)
            }
        }

        override fun recordProgress(summary: String, extras: Map<String, Any?>) = Unit

        override fun latestProgressSnapshot(): AgentToolProgressSnapshot = AgentToolProgressSnapshot()

        override fun isManualStopRequested(): Boolean = false

        override fun throwIfStopRequested() = Unit

        override fun complete() = Unit
    }

    private class NotReadyBackend : OmniflowActionBackend {
        var clickCount = 0

        override fun isReady(): Boolean = false

        override suspend fun click(x: Float, y: Float) {
            clickCount += 1
        }

        override suspend fun longPress(x: Float, y: Float, durationMs: Long) = Unit

        override suspend fun scroll(
            x: Float,
            y: Float,
            direction: ScrollDirection,
            distance: Float,
            durationMs: Long,
        ) = Unit

        override suspend fun inputTextToFocusedNode(text: String) = Unit

        override suspend fun launchApplication(packageName: String) = Unit

        override suspend fun pressHotKey(key: String) = Unit

        override fun currentXml(): String? = null

        override fun currentPackageName(): String? = null

        override fun currentActivityName(): String? = null
    }

    private class BlockingClickBackend(
        private val currentXml: String,
        private val currentPackage: String,
        private val currentActivity: String,
    ) : OmniflowActionBackend {
        val clickStarted = CompletableDeferred<Unit>()
        private val clickRelease = CompletableDeferred<Unit>()
        var clickCount = 0
            private set

        override fun isReady(): Boolean = true

        override suspend fun click(x: Float, y: Float) {
            clickCount += 1
            clickStarted.complete(Unit)
            clickRelease.await()
        }

        override suspend fun longPress(x: Float, y: Float, durationMs: Long) = Unit

        override suspend fun scroll(
            x: Float,
            y: Float,
            direction: ScrollDirection,
            distance: Float,
            durationMs: Long,
        ) = Unit

        override suspend fun inputTextToFocusedNode(text: String) = Unit

        override suspend fun launchApplication(packageName: String) = Unit

        override suspend fun pressHotKey(key: String) = Unit

        override fun currentXml(): String? = currentXml

        override fun currentPackageName(): String? = currentPackage

        override fun currentActivityName(): String? = currentActivity
    }

    private class RecordingBackend(
        private val currentXml: String,
        private val currentPackage: String,
        private val currentActivity: String,
        private val currentXmlSequence: List<String>? = null,
    ) : OmniflowActionBackend {
        var clickCount = 0
            private set
        var launchCount = 0
            private set
        var currentXmlReadCount = 0
            private set
        val launchedPackages = mutableListOf<String>()
        val inputTexts = mutableListOf<String>()

        override fun isReady(): Boolean = true

        override suspend fun click(x: Float, y: Float) {
            clickCount += 1
        }

        override suspend fun longPress(x: Float, y: Float, durationMs: Long) = Unit

        override suspend fun scroll(
            x: Float,
            y: Float,
            direction: ScrollDirection,
            distance: Float,
            durationMs: Long,
        ) = Unit

        override suspend fun inputTextToFocusedNode(text: String) {
            inputTexts += text
        }

        override suspend fun launchApplication(packageName: String) {
            launchedPackages += packageName
        }

        override suspend fun launchApplication(packageName: String, resetTask: Boolean) {
            launchedPackages += packageName
        }

        override suspend fun pressHotKey(key: String) = Unit

        override fun currentXml(): String? {
            currentXmlReadCount += 1
            currentXmlSequence?.let { xmls ->
                if (xmls.isNotEmpty()) {
                    return xmls[(currentXmlReadCount - 1).coerceAtMost(xmls.lastIndex)]
                }
            }
            return currentXml
        }

        override fun currentPackageName(): String? = currentPackage

        override fun currentActivityName(): String? = currentActivity
    }

    private class SwitchingBackend(
        currentXml: String,
        private val currentPackage: String,
        private val currentActivity: String,
        transitions: List<String>,
    ) : OmniflowActionBackend {
        private var currentXml: String = currentXml
        private val pendingTransitions = ArrayDeque(transitions)
        var clickCount = 0
            private set
        var launchCount = 0
            private set

        override fun isReady(): Boolean = true

        override suspend fun click(x: Float, y: Float) {
            clickCount += 1
            if (pendingTransitions.isNotEmpty()) {
                currentXml = pendingTransitions.removeFirst()
            }
        }

        override suspend fun longPress(x: Float, y: Float, durationMs: Long) = Unit

        override suspend fun scroll(
            x: Float,
            y: Float,
            direction: ScrollDirection,
            distance: Float,
            durationMs: Long,
        ) = Unit

        override suspend fun inputTextToFocusedNode(text: String) = Unit

        override suspend fun launchApplication(packageName: String) {
            launchCount += 1
            if (pendingTransitions.isNotEmpty()) {
                currentXml = pendingTransitions.removeFirst()
            }
        }

        override suspend fun launchApplication(packageName: String, resetTask: Boolean) {
            launchApplication(packageName)
        }

        override suspend fun pressHotKey(key: String) = Unit

        override fun currentXml(): String? = currentXml

        override fun currentPackageName(): String? = currentPackage

        override fun currentActivityName(): String? = currentActivity
    }

    private class InMemoryPrefs : SharedPreferences {
        private val values = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

        override fun getString(key: String?, defValue: String?): String? =
            values[key] as? String ?: defValue

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST")
            (values[key] as? Set<String>)?.toMutableSet() ?: defValues

        override fun getInt(key: String?, defValue: Int): Int =
            values[key] as? Int ?: defValue

        override fun getLong(key: String?, defValue: Long): Long =
            values[key] as? Long ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float =
            values[key] as? Float ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pending = linkedMapOf<String, Any?>()
            private var clear = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor =
                apply { if (key != null) pending[key] = value }

            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?
            ): SharedPreferences.Editor =
                apply { if (key != null) pending[key] = values?.toSet() }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor =
                apply { if (key != null) pending[key] = value }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor =
                apply { if (key != null) pending[key] = value }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor =
                apply { if (key != null) pending[key] = value }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
                apply { if (key != null) pending[key] = value }

            override fun remove(key: String?): SharedPreferences.Editor =
                apply { if (key != null) pending[key] = null }

            override fun clear(): SharedPreferences.Editor =
                apply { clear = true }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clear) values.clear()
                pending.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }
            }
        }
    }

    private class CapturingRouter : AgentToolExecutor {
        var toolName: String = ""
            private set
        var args: JsonObject? = null
            private set

        override suspend fun execute(
            toolCall: AssistantToolCall,
            args: JsonObject,
            runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
            env: AgentExecutionEnvironment,
            callback: AgentCallback,
            toolHandle: cn.com.omnimind.bot.agent.AgentToolExecutionHandle
        ): ToolExecutionResult {
            toolName = toolCall.function.name
            this.args = args
            return ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = "delegated",
                previewJson = "{}",
                rawResultJson = "{}",
                success = true,
            )
        }
    }

    private class RecordingToolCardCallback : AgentCallback {
        val toolCardEvents = mutableListOf<Pair<String, Map<String, Any?>>>()

        override suspend fun onThinkingStart() = Unit

        override suspend fun onThinkingUpdate(thinking: String) = Unit

        override suspend fun onToolCallStart(
            toolName: String,
            toolCallId: String,
            arguments: JsonObject
        ) = Unit

        override suspend fun onToolCallProgress(
            toolName: String,
            progress: String,
            extras: Map<String, Any?>
        ) = Unit

        override suspend fun onToolCallComplete(
            toolName: String,
            result: ToolExecutionResult
        ) = Unit

        override suspend fun onToolCardEvent(kind: String, payload: Map<String, Any?>) {
            toolCardEvents += kind to payload
        }

        override suspend fun onChatMessage(message: String) = Unit

        override suspend fun onChatMessage(message: String, isFinal: Boolean) = Unit

        override suspend fun onChatMessage(
            message: String,
            isFinal: Boolean,
            prefillTokensPerSecond: Double?,
            decodeTokensPerSecond: Double?
        ) = Unit

        override suspend fun onPromptTokenUsageChanged(
            latestPromptTokens: Int,
            promptTokenThreshold: Int?
        ) = Unit

        override suspend fun onClarifyRequired(
            question: String,
            missingFields: List<String>?
        ) = Unit

        override suspend fun onComplete(result: AgentResult) = Unit

        override suspend fun onError(error: String) = Unit

        override suspend fun onPermissionRequired(missing: List<String>) = Unit
    }

    private class FakeEnv(
        private val context: Context
    ) : AgentExecutionEnvironment {
        override val agentRunId: String = "test-run"
        override val userMessage: String = "test"
        override val attachments: List<Map<String, Any?>> = emptyList()
        override val currentPackageName: String? = null
        override val runtimeContextRepository: AgentRuntimeContextRepository =
            AgentRuntimeContextRepository(context)
        override val workspaceDescriptor: AgentWorkspaceDescriptor =
            AgentWorkspaceDescriptor(
                id = "test",
                rootPath = context.filesDir.absolutePath,
                androidRootPath = context.filesDir.absolutePath,
                uriRoot = "content://test",
                currentCwd = context.filesDir.absolutePath,
                androidCurrentCwd = context.filesDir.absolutePath,
                shellRootPath = context.filesDir.absolutePath,
                retentionPolicy = "test",
            )
        override val resolvedSkills: List<ResolvedSkillContext> = emptyList()
        override val failureLearningSkill: ResolvedSkillContext? = null
        override val workspaceManager: AgentWorkspaceManager
            get() = throw UnsupportedOperationException("unused in test")
        override val workspaceMemoryService: WorkspaceMemoryService
            get() = throw UnsupportedOperationException("unused in test")
        override val conversationMode: String = "normal"
        override val reasoningEffort: String? = null
        override val terminalEnvironment: Map<String, String> = emptyMap()
        override val runControl = NoOpAgentRunControl
    }

    private companion object {
        private const val CURRENT_PAGE_XML =
            "<hierarchy bounds=\"[0,0][1080,1920]\"><node bounds=\"[10,20][200,80]\" text=\"Current\" package=\"com.example.current\" class=\"android.widget.TextView\"/></hierarchy>"
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : TestWatcher() {
    private val dispatcher = UnconfinedTestDispatcher()

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
