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
    fun `vlm recall direct hit accepts exact catalog match for same package runlog function`() {
        val context = OobOmniFlowLoopAcceptanceTest.TempFilesContext()
        try {
            val workspaceStore = WorkspaceFunctionStore(context.root)
            val service = OobOmniFlowToolkitService(context, workspaceStore)
            val goal = "在美团打开外卖页面并搜索奶茶，不下单不支付"
            val functionId = "oob_fn_vlm_task_meituan_takeout_search"

            val register = service.registerFunction(
                mapOf(
                    "function_spec" to reusableFunctionSpec(
                        functionId = functionId,
                        name = goal,
                        description = goal,
                        source = mapOf(
                            "kind" to "run_log",
                            "goal" to goal,
                            "tool_name" to "vlm_task",
                            "behavior_identity" to mapOf(
                                "stable" to true,
                                "hash" to "meituansearch",
                                "strategy" to "vlm_goal_package_actions_v1",
                                "payload" to mapOf(
                                    "goal" to goal,
                                    "source_package" to "com.sankuai.meituan",
                                ),
                            ),
                        ),
                    )
                )
            )
            assertEquals(true, register["success"])

            val recall = service.recall(
                mapOf(
                    "goal" to goal,
                    "current_package" to "com.sankuai.meituan",
                    "current_xml" to "",
                    "k" to 10,
                    "auto_execute" to true,
                    "include_debug" to true,
                )
            )

            assertEquals(true, recall["success"])
            assertEquals("hit", recall["decision"])
            assertEquals("function_catalog_exact_match_direct_function_hit", recall["reason"])
            val hit = recall["hit"] as? Map<*, *>
            assertEquals(functionId, hit?.get("function_id"))
            assertEquals("catalog_exact_match_same_package_goal", hit?.get("direct_hit_policy"))
            assertEquals("function_catalog", hit?.get("recall_scope"))
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `vlm recall catalog direct hit uses replay step packages after startup bridge`() {
        val context = OobOmniFlowLoopAcceptanceTest.TempFilesContext()
        try {
            val workspaceStore = WorkspaceFunctionStore(context.root)
            val service = OobOmniFlowToolkitService(context, workspaceStore)
            val goal = "在美团打开外卖页面并搜索奶茶，不下单不支付"
            val functionId = "oob_fn_vlm_task_meituan_startup_bridge"

            val register = service.registerFunction(
                mapOf(
                    "function_spec" to mapOf(
                        "schema_version" to "oob.reusable_function.v1",
                        "function_id" to functionId,
                        "name" to goal,
                        "description" to goal,
                        "parameters" to emptyList<Map<String, Any?>>(),
                        "source" to mapOf(
                            "kind" to "run_log",
                            "goal" to goal,
                            "tool_name" to "vlm_task",
                            "source_package" to "com.hihonor.systemmanager",
                        ),
                        "execution" to mapOf(
                            "kind" to "tool_sequence",
                            "steps" to listOf(
                                mapOf(
                                    "id" to "step_1",
                                    "title" to "点击 允许按钮",
                                    "tool" to "click",
                                    "omniflow_action" to "click",
                                    "args" to mapOf(
                                        "target_description" to "允许按钮",
                                        "x" to 750,
                                        "y" to 2136,
                                    ),
                                    "source_context" to mapOf(
                                        "src_ctx" to mapOf(
                                            "page" to "<hierarchy package=\"com.hihonor.systemmanager\" />",
                                            "package_name" to "com.hihonor.systemmanager",
                                        )
                                    ),
                                ),
                                mapOf(
                                    "id" to "step_2",
                                    "title" to "点击 外卖",
                                    "tool" to "click",
                                    "omniflow_action" to "click",
                                    "args" to mapOf(
                                        "target_description" to "外卖",
                                        "x" to 349,
                                        "y" to 536,
                                    ),
                                    "source_context" to mapOf(
                                        "src_ctx" to mapOf(
                                            "page" to "<hierarchy package=\"com.sankuai.meituan\" />",
                                            "package_name" to "com.sankuai.meituan",
                                        )
                                    ),
                                ),
                                mapOf(
                                    "id" to "step_3",
                                    "title" to "完成任务",
                                    "tool" to "finished",
                                    "omniflow_action" to "finished",
                                    "args" to emptyMap<String, Any?>(),
                                    "source_context" to mapOf(
                                        "src_ctx" to mapOf(
                                            "page" to "<hierarchy package=\"com.sankuai.meituan\" />",
                                            "package_name" to "com.sankuai.meituan",
                                        )
                                    ),
                                ),
                            )
                        )
                    )
                )
            )
            assertEquals(true, register["success"])

            val recall = service.recall(
                mapOf(
                    "goal" to goal,
                    "current_package" to "com.sankuai.meituan",
                    "current_xml" to "",
                    "k" to 10,
                    "auto_execute" to true,
                    "include_debug" to true,
                )
            )

            assertEquals(true, recall["success"])
            assertEquals("hit", recall["decision"])
            val hit = recall["hit"] as? Map<*, *>
            assertEquals(functionId, hit?.get("function_id"))
            assertEquals("catalog_exact_match_same_package_goal", hit?.get("direct_hit_policy"))
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `vlm recall direct hit ignores duplicate behavior identity functions`() {
        val context = OobOmniFlowLoopAcceptanceTest.TempFilesContext()
        try {
            val workspaceStore = WorkspaceFunctionStore(context.root)
            val service = OobOmniFlowToolkitService(context, workspaceStore)
            val goal = "Open network settings"
            val behaviorIdentity = mapOf(
                "stable" to true,
                "hash" to "samebehavior",
                "strategy" to "vlm_goal_package_actions_v1",
            )
            val firstRegister = service.registerFunction(
                mapOf(
                    "function_spec" to reusableFunctionSpec(
                        functionId = "oob_fn_vlm_task_samebehavior",
                        name = goal,
                        description = goal,
                        source = mapOf(
                            "kind" to "run_log",
                            "goal" to goal,
                            "tool_name" to "vlm_task",
                            "behavior_identity" to behaviorIdentity,
                        ),
                        metadata = mapOf("oob_behavior_identity" to behaviorIdentity),
                    )
                )
            )
            val duplicateRegister = service.registerFunction(
                mapOf(
                    "function_spec" to reusableFunctionSpec(
                        functionId = "oob_fn_vlm_task_oldcopy",
                        name = goal,
                        description = goal,
                        source = mapOf(
                            "kind" to "run_log",
                            "goal" to goal,
                            "tool_name" to "vlm_task",
                            "behavior_identity" to behaviorIdentity,
                        ),
                        metadata = mapOf("oob_behavior_identity" to behaviorIdentity),
                    )
                )
            )
            assertEquals(true, firstRegister["success"])
            assertEquals(true, duplicateRegister["success"])

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
            assertEquals("oob_fn_vlm_task_oldcopy", hit?.get("function_id"))
            val capabilities = recall["node_function_capabilities"] as? List<*>
            assertEquals(2, capabilities?.size)
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `vlm recall direct hit ignores legacy equivalent replay functions`() {
        val context = OobOmniFlowLoopAcceptanceTest.TempFilesContext()
        try {
            val workspaceStore = WorkspaceFunctionStore(context.root)
            val service = OobOmniFlowToolkitService(context, workspaceStore)
            val firstRegister = service.registerFunction(
                mapOf(
                    "function_spec" to reusableFunctionSpec(
                        functionId = "oob_fn_vlm_task_open_network",
                        name = "Open network settings",
                        description = "Open network settings",
                        source = mapOf(
                            "kind" to "run_log",
                            "goal" to "Open network settings",
                            "tool_name" to "vlm_task",
                        ),
                    )
                )
            )
            val legacyRegister = service.registerFunction(
                mapOf(
                    "function_spec" to reusableFunctionSpec(
                        functionId = "oob_fn_vlm_task_tap_mobile_network",
                        name = "Tap mobile network",
                        description = "Tap mobile network",
                        source = mapOf(
                            "kind" to "run_log",
                            "goal" to "Tap mobile network",
                            "tool_name" to "vlm_task",
                        ),
                    )
                )
            )
            assertEquals(true, firstRegister["success"])
            assertEquals(true, legacyRegister["success"])

            val recall = service.recall(
                mapOf(
                    "goal" to "Open network settings",
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
            assertNotNull(hit?.get("function_id"))
            val capabilities = recall["node_function_capabilities"] as? List<*>
            assertEquals(2, capabilities?.size)
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `vlm recall direct hit ignores equivalent no-arg vlm functions with different click titles`() {
        val context = OobOmniFlowLoopAcceptanceTest.TempFilesContext()
        try {
            val workspaceStore = WorkspaceFunctionStore(context.root)
            val service = OobOmniFlowToolkitService(context, workspaceStore)
            val firstRegister = service.registerFunction(
                mapOf(
                    "function_spec" to reusableVlmSequenceSpec(
                        functionId = "oob_fn_vlm_task_indexed_mobile_network",
                        goal = "打开网络设置",
                        clickTitle = "indexed evidence matched visible target: 移动网络",
                    )
                )
            )
            val duplicateRegister = service.registerFunction(
                mapOf(
                    "function_spec" to reusableVlmSequenceSpec(
                        functionId = "oob_fn_vlm_task_click_mobile_network",
                        goal = "打开网络设置",
                        clickTitle = "点击 移动网络选项",
                    )
                )
            )
            assertEquals(true, firstRegister["success"])
            assertEquals(true, duplicateRegister["success"])

            val recall = service.recall(
                mapOf(
                    "goal" to "打开网络设置",
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
            assertNotNull(hit?.get("function_id"))
            val capabilities = recall["node_function_capabilities"] as? List<*>
            assertEquals(2, capabilities?.size)
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `vlm recall direct hit folds same-goal startup bridge functions with optional defaults`() {
        val context = OobOmniFlowLoopAcceptanceTest.TempFilesContext()
        try {
            val workspaceStore = WorkspaceFunctionStore(context.root)
            val service = OobOmniFlowToolkitService(context, workspaceStore)
            val goal = "在美团打开外卖页面并搜索奶茶，不下单不支付"
            val firstFunctionId = "oob_fn_vlm_task_meituan_startup_a"
            val secondFunctionId = "oob_fn_vlm_task_meituan_startup_b"

            val firstRegister = service.registerFunction(
                mapOf(
                    "function_spec" to startupBridgeSearchSpec(
                        functionId = firstFunctionId,
                        goal = goal,
                        clickTitle = "点击 外卖奶茶搜索框",
                        extraClosePopup = true,
                    )
                )
            )
            val secondRegister = service.registerFunction(
                mapOf(
                    "function_spec" to startupBridgeSearchSpec(
                        functionId = secondFunctionId,
                        goal = goal,
                        clickTitle = "点击 搜索框，用于输入商家或商品名称",
                        extraClosePopup = false,
                    )
                )
            )
            assertEquals(true, firstRegister["success"])
            assertEquals(true, secondRegister["success"])

            val recall = service.recall(
                mapOf(
                    "goal" to goal,
                    "current_package" to "com.hihonor.systemmanager",
                    "current_xml" to HONOR_LAUNCH_PROMPT_XML,
                    "k" to 10,
                    "auto_execute" to true,
                    "include_debug" to true,
                )
            )

            assertEquals(true, recall["success"])
            assertEquals("hit", recall["decision"])
            assertEquals("udeg_page_match_direct_function_hit", recall["reason"])
            val hit = recall["hit"] as? Map<*, *>
            assertNotNull(hit?.get("function_id"))
            assertEquals("top1_high_confidence_margin", hit?.get("direct_hit_policy"))
            assertEquals("udeg_node", hit?.get("recall_scope"))
            val capabilities = recall["node_function_capabilities"] as? List<*>
            assertEquals(2, capabilities?.size)
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

    private fun startupBridgeSearchSpec(
        functionId: String,
        goal: String,
        clickTitle: String,
        extraClosePopup: Boolean,
    ): Map<String, Any?> {
        val steps = mutableListOf<Map<String, Any?>>(
            mapOf(
                "id" to "step_1",
                "title" to "open_app: com.hihonor.systemmanager",
                "tool" to "open_app",
                "omniflow_action" to "open_app",
                "args" to mapOf("package_name" to "com.hihonor.systemmanager"),
                "source_context" to mapOf(
                    "src_ctx" to mapOf(
                        "page" to HONOR_LAUNCH_PROMPT_XML,
                        "package_name" to "com.hihonor.systemmanager",
                    )
                ),
            ),
            mapOf(
                "id" to "step_2",
                "title" to "点击 允许按钮，用于授权打开美团应用",
                "tool" to "click",
                "omniflow_action" to "click",
                "args" to mapOf(
                    "target_description" to "允许按钮，用于授权打开美团应用",
                    "x" to 750,
                    "y" to 2136,
                ),
                "source_context" to mapOf(
                    "src_ctx" to mapOf(
                        "page" to HONOR_LAUNCH_PROMPT_XML,
                        "package_name" to "com.hihonor.systemmanager",
                    ),
                    "dst_ctx" to mapOf(
                        "package_name" to "com.sankuai.meituan",
                    ),
                ),
            ),
            mapOf(
                "id" to "step_3",
                "title" to clickTitle,
                "tool" to "click",
                "omniflow_action" to "click",
                "args" to mapOf(
                    "target_description" to clickTitle.removePrefix("点击 "),
                    "x" to 476,
                    "y" to 261,
                ),
                "source_context" to mapOf(
                    "src_ctx" to mapOf(
                        "page" to MEITUAN_SEARCH_XML,
                        "package_name" to "com.sankuai.meituan",
                    )
                ),
            ),
        )
        if (extraClosePopup) {
            steps += mapOf(
                "id" to "step_4",
                "title" to "点击 关闭语音搜索弹窗的 X 按钮",
                "tool" to "click",
                "omniflow_action" to "click",
                "args" to mapOf(
                    "target_description" to "关闭语音搜索弹窗的 X 按钮",
                    "x" to 943,
                    "y" to 1628,
                ),
                "source_context" to mapOf(
                    "src_ctx" to mapOf(
                        "page" to MEITUAN_SEARCH_XML,
                        "package_name" to "com.sankuai.meituan",
                    )
                ),
            )
        }
        steps += listOf(
            mapOf(
                "id" to "step_input",
                "title" to "输入文本 搜索框",
                "tool" to "input_text",
                "omniflow_action" to "input_text",
                "args" to mapOf(
                    "target_description" to "搜索框",
                    "text" to "奶茶",
                    "x" to 442,
                    "y" to 181,
                ),
                "source_context" to mapOf(
                    "src_ctx" to mapOf(
                        "page" to MEITUAN_SEARCH_XML,
                        "package_name" to "com.sankuai.meituan",
                    )
                ),
            ),
            mapOf(
                "id" to "step_done",
                "title" to "完成任务",
                "tool" to "finished",
                "omniflow_action" to "finished",
                "args" to mapOf("content" to "已成功搜索奶茶，页面显示结果列表"),
                "source_context" to mapOf(
                    "src_ctx" to mapOf(
                        "page" to MEITUAN_SEARCH_XML,
                        "package_name" to "com.sankuai.meituan",
                    )
                ),
            ),
        )
        return mapOf(
            "schema_version" to "oob.reusable_function.v1",
            "function_id" to functionId,
            "name" to goal,
            "description" to goal,
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "input_text" to mapOf(
                        "type" to "string",
                        "description" to "Text for input_text",
                        "default" to "奶茶",
                        "x_oob_bindings" to listOf("$.execution.steps[3].args.text"),
                    )
                ),
                "required" to emptyList<String>(),
                "additionalProperties" to false,
            ),
            "source" to mapOf(
                "kind" to "run_log",
                "goal" to goal,
                "tool_name" to "vlm_task",
                "source_package" to "com.hihonor.systemmanager",
            ),
            "execution" to mapOf(
                "kind" to "tool_sequence",
                "steps" to steps,
            ),
        )
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
        metadata: Map<String, Any?> = emptyMap(),
        stepTitle: String = "Tap Network",
    ): Map<String, Any?> = mapOf(
        "schema_version" to "oob.reusable_function.v1",
        "function_id" to functionId,
        "name" to name,
        "description" to description,
        "parameters" to emptyList<Map<String, Any?>>(),
        "metadata" to metadata,
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

    private fun reusableVlmSequenceSpec(
        functionId: String,
        goal: String,
        clickTitle: String,
    ): Map<String, Any?> = mapOf(
        "schema_version" to "oob.reusable_function.v1",
        "function_id" to functionId,
        "name" to goal,
        "description" to goal,
        "parameters" to emptyList<Map<String, Any?>>(),
        "source" to mapOf(
            "kind" to "run_log",
            "goal" to goal,
            "tool_name" to "vlm_task",
        ),
        "execution" to mapOf(
            "kind" to "tool_sequence",
            "steps" to listOf(
                mapOf(
                    "id" to "step_1",
                    "title" to "open_app: com.example.settings",
                    "tool" to "open_app",
                    "omniflow_action" to "open_app",
                    "args" to mapOf("package_name" to "com.example.settings"),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf(
                            "page" to SOURCE_XML,
                            "package_name" to "com.example.settings",
                        )
                    ),
                ),
                mapOf(
                    "id" to "step_2",
                    "title" to clickTitle,
                    "tool" to "click",
                    "omniflow_action" to "click",
                    "args" to mapOf(
                        "target_description" to "Network",
                        "x" to 540,
                        "y" to 280,
                    ),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf(
                            "page" to SOURCE_XML,
                            "package_name" to "com.example.settings",
                        )
                    ),
                ),
                mapOf(
                    "id" to "step_3",
                    "title" to "完成任务",
                    "tool" to "finished",
                    "omniflow_action" to "finished",
                    "args" to emptyMap<String, Any?>(),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf(
                            "page" to AFTER_XML,
                            "package_name" to "com.example.settings",
                        )
                    ),
                ),
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

        const val HONOR_LAUNCH_PROMPT_XML = """
            <hierarchy bounds="[0,0][1080,2400]">
              <node index="0" package="com.hihonor.systemmanager" class="android.widget.TextView" text="荣耀安全提示" bounds="[350,1572][710,1653]" />
              <node index="1" package="com.hihonor.systemmanager" class="android.widget.TextView" text="小万 想要打开 美团 是否允许" bounds="[120,1689][940,1754]" />
              <node index="2" package="com.hihonor.systemmanager" class="android.widget.Button" text="拒绝" clickable="true" bounds="[108,2070][512,2202]" />
              <node index="3" package="com.hihonor.systemmanager" class="android.widget.Button" text="允许" clickable="true" bounds="[548,2070][952,2202]" />
            </hierarchy>
        """

        const val MEITUAN_SEARCH_XML = """
            <hierarchy bounds="[0,0][1080,2400]">
              <node index="0" package="com.sankuai.meituan" class="android.widget.TextView" text="外卖" clickable="true" bounds="[40,440][240,620]" />
              <node index="1" package="com.sankuai.meituan" class="android.widget.EditText" text="搜索商家或商品" clickable="true" focusable="true" bounds="[120,120][860,220]" />
              <node index="2" package="com.sankuai.meituan" class="android.widget.TextView" text="搜索" clickable="true" bounds="[880,120][1040,220]" />
            </hierarchy>
        """
    }
}
