package cn.com.omnimind.bot.runlog

import cn.com.omnimind.baselib.runlog.InternalRunLogFinishEvent
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.bot.mcp.TaskState
import cn.com.omnimind.bot.mcp.TaskStatus
import cn.com.omnimind.bot.mcp.VlmTaskRequest
import cn.com.omnimind.bot.omniflow.WorkspaceFunctionStore
import cn.com.omnimind.bot.vlm.VlmRecallGuidanceBuilder
import cn.com.omnimind.bot.vlm.VlmToolCoordinator
import cn.com.omnimind.bot.vlm.VlmToolOutcomeStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OobVlmRunLogAutoRegistrarTest {
    @After
    fun clearRunLogFinishListener() {
        InternalRunLogStore.setFinishListener(null)
    }

    @Test
    fun `auto register policy only accepts successful vlm task runs`() {
        val accepted = finishEvent(
            runId = "vlm-run",
            source = "vlm",
            toolName = "vlm_task",
            success = true,
        )

        assertTrue(OobVlmRunLogAutoRegistrar.shouldAutoRegister(accepted))
        assertFalse(OobVlmRunLogAutoRegistrar.shouldAutoRegister(accepted.copy(success = false)))
        assertFalse(OobVlmRunLogAutoRegistrar.shouldAutoRegister(accepted.copy(runId = "")))
        assertFalse(OobVlmRunLogAutoRegistrar.shouldAutoRegister(accepted.copy(source = "agent")))
        assertFalse(OobVlmRunLogAutoRegistrar.shouldAutoRegister(accepted.copy(toolName = "run_function")))
    }

    @Test
    fun `bind auto registers successful vlm task finish event`() {
        val context = OobOmniFlowLoopAcceptanceTest.TempFilesContext()
        try {
            val runId = "vlm-auto-listener-${System.nanoTime()}"
            OobVlmRunLogAutoRegistrar.bind(context)

            InternalRunLogStore.beginRun(
                context = context,
                runId = runId,
                goal = "Open network settings",
                source = "vlm",
                toolName = "vlm_task",
                operationDescription = "Open network settings",
            )
            InternalRunLogStore.appendCard(
                context = context,
                runId = runId,
                card = linkedMapOf(
                    "card_id" to "$runId-click-network",
                    "tool_name" to "click",
                    "title" to "点击 Network",
                    "success" to true,
                    "args" to linkedMapOf(
                        "target_description" to "Network",
                        "x" to 540,
                        "y" to 280,
                    ),
                    "before" to linkedMapOf(
                        "package_name" to "com.example.settings",
                        "observation_xml" to SOURCE_XML,
                    ),
                    "after" to linkedMapOf(
                        "package_name" to "com.example.settings",
                        "observation_xml" to AFTER_XML,
                    ),
                )
            )
            InternalRunLogStore.finishRun(
                context = context,
                runId = runId,
                success = true,
                doneReason = "finished",
            )

            val timeline = waitForRegisteredTimeline(context, runId)
            assertEquals(true, timeline["registered_as_function"])
            assertEquals(true, timeline["is_registered_function"])
            assertEquals(1, timeline["registered_function_count"])
            val functionId = timeline["registered_function_id"]?.toString().orEmpty()
            assertTrue(functionId.isNotBlank())

            val spec = timeline["registered_function_spec"] as? Map<*, *>
            val metadata = spec?.get("metadata") as? Map<*, *>
            assertEquals("agent_reusable", metadata?.get("visibility"))
            assertEquals("offline_only", metadata?.get("enhancement_policy"))

            val recall = OobOmniFlowToolkitService(context).recall(
                mapOf(
                    "goal" to "open network settings",
                    "current_package" to "com.example.settings",
                    "current_xml" to SOURCE_XML,
                    "k" to 3,
                    "auto_execute" to true,
                )
            )
            assertEquals(true, recall["success"])
            assertEquals("hit", recall["decision"])
            val hit = recall["hit"] as? Map<*, *>
            assertEquals(functionId, hit?.get("function_id"))
        } finally {
            InternalRunLogStore.setFinishListener(null)
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `vlm runlog conversion registers agent visible function for UDEG recall`() {
        val context = OobOmniFlowLoopAcceptanceTest.TempFilesContext()
        try {
            val runId = "vlm-auto-register-${System.nanoTime()}"
            val functionId = "vlm_auto_register_network_settings"
            InternalRunLogStore.beginRun(
                context = context,
                runId = runId,
                goal = "Open network settings",
                source = "vlm",
                toolName = "vlm_task",
                operationDescription = "Open network settings",
            )
            InternalRunLogStore.appendCard(
                context = context,
                runId = runId,
                card = linkedMapOf(
                    "card_id" to "$runId-click-network",
                    "tool_name" to "click",
                    "title" to "点击 Network",
                    "success" to true,
                    "args" to linkedMapOf(
                        "target_description" to "Network",
                        "x" to 540,
                        "y" to 280,
                    ),
                    "before" to linkedMapOf(
                        "package_name" to "com.example.settings",
                        "observation_xml" to SOURCE_XML,
                    ),
                    "after" to linkedMapOf(
                        "package_name" to "com.example.settings",
                        "observation_xml" to AFTER_XML,
                    ),
                )
            )
            InternalRunLogStore.finishRun(
                context = context,
                runId = runId,
                success = true,
                doneReason = "finished",
            )

            val workspaceStore = WorkspaceFunctionStore(context.root)
            val convert = OobRunLogReplayService(context, workspaceStore).convertRunLog(
                runId = runId,
                register = true,
                agentVisible = true,
                functionIdOverride = functionId,
                nameOverride = "Open network settings",
                descriptionOverride = "Open network settings from the Settings page",
            )

            assertEquals(true, convert["success"])
            assertEquals(true, convert["registered"])
            assertEquals(true, convert["agent_visible"])
            assertEquals("agent_reusable", convert["visibility"])
            assertEquals(functionId, convert["function_id"])
            val registeredSpec = convert["function_spec"] as? Map<*, *>
            val metadata = registeredSpec?.get("metadata") as? Map<*, *>
            assertEquals("offline_only", metadata?.get("enhancement_policy"))
            val udeg = convert["udeg"] as? Map<*, *>
            assertEquals(true, udeg?.get("indexed"))

            val execution = registeredSpec?.get("execution") as? Map<*, *>
            val steps = execution?.get("steps") as? List<*>
            val step = steps?.single() as? Map<*, *>
            val sourceContext = step?.get("source_context") as? Map<*, *>
            val srcCtx = sourceContext?.get("src_ctx") as? Map<*, *>
            assertEquals(SOURCE_XML.trim(), srcCtx?.get("page")?.toString()?.trim())
            assertEquals("com.example.settings", srcCtx?.get("package_name"))

            val recall = OobOmniFlowToolkitService(context, workspaceStore).recall(
                mapOf(
                    "goal" to "open network settings",
                    "current_package" to "com.example.settings",
                    "current_xml" to SOURCE_XML,
                    "k" to 3,
                    "include_debug" to true,
                )
            )
            assertEquals(true, recall["success"])
            assertEquals("recall", recall["decision"])
            assertEquals("oob_native_udeg_page_match", recall["source"])
            val candidates = recall["candidates"] as? List<*>
            val firstCandidate = candidates?.firstOrNull() as? Map<*, *>
            assertNotNull(firstCandidate)
            assertEquals(functionId, firstCandidate?.get("function_id"))
            assertNotNull(firstCandidate?.get("udeg_node"))
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `vlm recall direct hit ignores same page empty text distractors`() {
        val context = OobOmniFlowLoopAcceptanceTest.TempFilesContext()
        try {
            val workspaceStore = WorkspaceFunctionStore(context.root)
            val service = OobOmniFlowToolkitService(context, workspaceStore)
            val goal = "vlmseed direct hit network settings"
            val targetFunctionId = "vlmseed_direct_hit_network_settings"
            val distractorFunctionId = "zzzzzz"

            val targetRegister = service.registerFunction(
                mapOf(
                    "function_spec" to reusableFunctionSpec(
                        functionId = targetFunctionId,
                        name = goal,
                        description = "Open network settings from the Settings page",
                        source = mapOf(
                            "kind" to "run_log",
                            "goal" to goal,
                            "tool_name" to "vlm_task",
                        ),
                    )
                )
            )
            val distractorRegister = service.registerFunction(
                mapOf(
                    "function_spec" to reusableFunctionSpec(
                        functionId = distractorFunctionId,
                        name = "",
                        description = "",
                        source = emptyMap(),
                        stepTitle = "",
                    )
                )
            )
            assertEquals(true, targetRegister["success"])
            assertEquals(true, (targetRegister["udeg"] as? Map<*, *>)?.get("indexed"))
            assertEquals(true, distractorRegister["success"])
            assertEquals(true, (distractorRegister["udeg"] as? Map<*, *>)?.get("indexed"))

            val recall = service.recall(
                mapOf(
                    "goal" to goal,
                    "current_package" to "com.example.settings",
                    "current_xml" to SOURCE_XML,
                    "k" to 10,
                    "auto_execute" to true,
                    "include_debug" to true,
                )
            )

            assertEquals(true, recall["success"])
            assertEquals("hit", recall["decision"])
            val hit = recall["hit"] as? Map<*, *>
            assertEquals(targetFunctionId, hit?.get("function_id"))
            val capabilities = recall["node_function_capabilities"] as? List<*>
            val distractor = capabilities
                ?.mapNotNull { it as? Map<*, *> }
                ?.firstOrNull { it["function_id"] == distractorFunctionId }
            assertNotNull(distractor)
            val distractorTextScore = distractor?.get("text_score") as? Number
            assertTrue(
                "empty text distractor should not meet direct-hit text threshold: $distractor",
                (distractorTextScore?.toDouble() ?: 1.0) < 0.85,
            )
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `vlm runlog recall hit executes registered function without online enhancement`() = runBlocking {
        val context = OobOmniFlowLoopAcceptanceTest.TempFilesContext()
        try {
            val runId = "vlm-recall-loop-${System.nanoTime()}"
            val functionId = "vlm_recall_loop_network_settings"
            InternalRunLogStore.beginRun(
                context = context,
                runId = runId,
                goal = "Open network settings",
                source = "vlm",
                toolName = "vlm_task",
                operationDescription = "Open network settings",
            )
            InternalRunLogStore.appendCard(
                context = context,
                runId = runId,
                card = linkedMapOf(
                    "card_id" to "$runId-click-network",
                    "tool_name" to "click",
                    "title" to "点击 Network",
                    "success" to true,
                    "args" to linkedMapOf(
                        "target_description" to "Network",
                        "x" to 540,
                        "y" to 280,
                    ),
                    "before" to linkedMapOf(
                        "package_name" to "com.example.settings",
                        "observation_xml" to SOURCE_XML,
                    ),
                    "after" to linkedMapOf(
                        "package_name" to "com.example.settings",
                        "observation_xml" to AFTER_XML,
                    ),
                )
            )
            InternalRunLogStore.finishRun(
                context = context,
                runId = runId,
                success = true,
                doneReason = "finished",
            )

            val workspaceStore = WorkspaceFunctionStore(context.root)
            val convert = OobRunLogReplayService(context, workspaceStore).convertRunLog(
                runId = runId,
                register = true,
                agentVisible = true,
                functionIdOverride = functionId,
                nameOverride = "Open network settings",
                descriptionOverride = "Open network settings from the Settings page",
            )
            assertEquals(true, convert["success"])
            val registeredSpec = convert["function_spec"] as? Map<*, *>
            val metadata = registeredSpec?.get("metadata") as? Map<*, *>
            assertEquals("offline_only", metadata?.get("enhancement_policy"))

            val recall = OobOmniFlowToolkitService(context, workspaceStore).recall(
                mapOf(
                    "goal" to "open network settings",
                    "current_package" to "com.example.settings",
                    "current_xml" to SOURCE_XML,
                    "k" to 3,
                    "auto_execute" to true,
                )
            )
            assertEquals(true, recall["success"])
            assertEquals("hit", recall["decision"])

            val guidance = VlmRecallGuidanceBuilder.fromAgentPayload(
                payload = recall,
                allowDirectExecutionDecision = true,
            )
            val state = TaskState(
                taskId = "vlm-recall-loop-task",
                goal = "open network settings",
                status = TaskStatus.RUNNING,
            )
            var runFunctionCalls = 0
            var executedFunctionId = ""
            val outcome = VlmToolCoordinator.tryExecuteRecallHitIfAllowed(
                request = VlmTaskRequest(
                    goal = state.goal,
                    allowOmniFlowFunctionAutoExecute = true,
                ),
                taskState = state,
                recallGuidance = guidance,
                progressReporter = { _, _ -> },
                runFunction = { calledFunctionId, _ ->
                    runFunctionCalls += 1
                    executedFunctionId = calledFunctionId
                    mapOf(
                        "success" to true,
                        "function_id" to calledFunctionId,
                        "run_id" to "omniflow_recall_loop_run",
                        "actions_executed" to 1,
                        "execution_summary" to mapOf(
                            "success" to true,
                            "function_id" to calledFunctionId,
                            "steps" to 1,
                            "resolve_calls" to 0,
                            "model_calls" to 0,
                            "tokens" to 0,
                            "elapsed_ms" to 1,
                        ),
                    )
                },
                resolveProvider = { _, _, _ ->
                    cn.com.omnimind.bot.vlm.RuntimeResolveResult(reason = "no_public_arguments")
                },
            )

            assertNotNull(outcome)
            assertEquals(VlmToolOutcomeStatus.FINISHED, outcome?.status)
            assertEquals(TaskStatus.FINISHED, state.status)
            assertEquals("omniflow_recall_hit:$functionId", state.executionRoute)
            assertEquals(1, runFunctionCalls)
            assertEquals(functionId, executedFunctionId)
            val executionSummary = state.omniflowExecutionSummary
            assertEquals(1, executionSummary?.get("steps"))
            assertEquals(0, executionSummary?.get("resolve_calls"))
            assertFalse(executionSummary.orEmpty().containsKey("function_id"))
        } finally {
            context.root.deleteRecursively()
        }
    }

    private fun finishEvent(
        runId: String,
        source: String,
        toolName: String,
        success: Boolean,
    ): InternalRunLogFinishEvent =
        InternalRunLogFinishEvent(
            runId = runId,
            goal = "Open network settings",
            source = source,
            toolName = toolName,
            operationDescription = "Open network settings",
            startedAtMs = 1_700_000_000_000L,
            finishedAtMs = 1_700_000_001_000L,
            success = success,
            doneReason = if (success) "finished" else "error",
            errorMessage = "",
            cardCount = 1,
        )

    private fun waitForRegisteredTimeline(
        context: OobOmniFlowLoopAcceptanceTest.TempFilesContext,
        runId: String,
    ): Map<String, Any?> {
        val deadline = System.currentTimeMillis() + 2_000L
        var last = InternalRunLogStore.timelinePayload(context, runId)
        while (System.currentTimeMillis() < deadline) {
            if (last["registered_as_function"] == true) return last
            Thread.sleep(25L)
            last = InternalRunLogStore.timelinePayload(context, runId)
        }
        return last
    }

    private fun reusableFunctionSpec(
        functionId: String,
        name: String,
        description: String,
        source: Map<String, Any?>,
        stepTitle: String = "Tap Network",
    ): Map<String, Any?> = mapOf(
        "schema_version" to "oob.reusable_function.v1",
        "function_id" to functionId,
        "name" to name,
        "description" to description,
        "parameters" to emptyList<Map<String, Any?>>(),
        "source" to source,
        "execution" to mapOf(
            "kind" to "tool_sequence",
            "steps" to listOf(
                mapOf(
                    "id" to "step_1",
                    "title" to stepTitle,
                    "tool" to "wait",
                    "omniflow_action" to "wait",
                    "args" to emptyMap<String, Any?>(),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf(
                            "page" to SOURCE_XML,
                            "package_name" to "com.example.settings",
                        )
                    ),
                )
            )
        )
    )

    private companion object {
        const val SOURCE_XML = """
            <hierarchy bounds="[0,0][1080,1920]">
              <node index="0" package="com.example.settings" class="android.widget.TextView" text="Settings" bounds="[40,80][1040,180]" />
              <node index="1" package="com.example.settings" class="android.widget.TextView" text="Network" resource-id="settings:network" clickable="true" enabled="true" visible-to-user="true" bounds="[40,220][1040,340]" />
              <node index="2" package="com.example.settings" class="android.widget.TextView" text="Display" resource-id="settings:display" clickable="true" enabled="true" visible-to-user="true" bounds="[40,380][1040,500]" />
            </hierarchy>
        """

        const val AFTER_XML = """
            <hierarchy bounds="[0,0][1080,1920]">
              <node index="0" package="com.example.settings" class="android.widget.TextView" text="Network settings" bounds="[40,80][1040,180]" />
              <node index="1" package="com.example.settings" class="android.widget.Switch" text="Wi-Fi" clickable="true" enabled="true" visible-to-user="true" bounds="[40,220][1040,340]" />
            </hierarchy>
        """
    }
}
