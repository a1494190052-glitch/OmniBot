package cn.com.omnimind.bot.vlm

import cn.com.omnimind.assists.task.vlmserver.UIContext
import cn.com.omnimind.assists.task.vlmserver.VLMCurrentPageSnapshot
import cn.com.omnimind.assists.task.vlmserver.VLMPageContextRequest
import cn.com.omnimind.bot.runlog.OobOmniFlowLoopAcceptanceTest
import cn.com.omnimind.bot.runlog.OobUdegNodeStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VlmRecallGuidanceBuilderTest {
    @Test
    fun `render guidance exposes recall facts without declaring completion`() {
        val guidance = VlmRecallGuidanceBuilder.renderGuidance(
            mapOf(
                "success" to true,
                "decision" to "hit",
                "decision_policy" to mapOf(
                    "mode" to "direct_execution_allowed",
                    "direct_hit_requested" to true,
                ),
                "hit" to mapOf(
                    "function_id" to "open_network_settings",
                    "score" to 0.99,
                    "description" to "open network settings",
                    "step_summaries" to listOf(
                        mapOf("tool" to "open_app", "title" to "open_app: com.android.settings"),
                        mapOf("tool" to "click", "title" to "click: Network"),
                    ),
                ),
            )
        )

        assertTrue(guidance.contains("OmniFlow recall checked for this VLM step."))
        assertTrue(guidance.contains("function_reuse_policy=runtime_recall_replay"))
        assertTrue(guidance.contains("model_must_not_emit_call_tool=true"))
        assertTrue(guidance.contains("Function hits are resolved and replayed locally"))
        assertTrue(guidance.contains("online_action_policy="))
        assertFalse(guidance.contains("fallback_policy="))
        assertFalse(guidance.contains("function=open_network_settings"))
        assertFalse(guidance.contains("open_network_settings"))
        assertFalse(guidance.contains("resolve_policy"))
        assertFalse(guidance.contains("step: 1. open_app"))
        assertFalse(guidance.contains("任务已完成"))
        assertFalse(guidance.contains("current task is complete"))

        val debug = VlmRecallGuidanceBuilder.renderDebugGuidance(
            mapOf(
                "success" to true,
                "decision" to "hit",
                "decision_policy" to mapOf(
                    "mode" to "direct_execution_allowed",
                    "direct_hit_requested" to true,
                ),
                "hit" to mapOf(
                    "function_id" to "open_network_settings",
                    "score" to 0.99,
                    "description" to "open network settings",
                ),
            )
        )
        assertTrue(debug.contains("function=open_network_settings"))
    }

    @Test
    fun `legacy direct hit payload is rendered as optional candidate unless direct execution was requested`() {
        val guidance = VlmRecallGuidanceBuilder.renderGuidance(
            mapOf(
                "success" to true,
                "decision" to "hit",
                "hit" to mapOf(
                    "function_id" to "open_network_settings",
                    "score" to 1.0,
                    "page_similarity" to 1.0,
                    "text_score" to 1.0,
                    "description" to "open network settings",
                    "step_summaries" to listOf(
                        mapOf("tool" to "click", "title" to "click: Network"),
                    ),
                ),
            )
        )

        assertTrue(guidance.contains("function_reuse_policy=runtime_context_only"))
        assertTrue(guidance.contains("model_must_choose_normal_ui_action=true"))
        assertFalse(guidance.contains("preferred_call_tool"))
        assertFalse(guidance.contains("\"name\":\"call_tool\""))
        assertFalse(guidance.contains("function=open_network_settings"))
        assertFalse(guidance.contains("open_network_settings"))
        assertFalse(guidance.contains("function_reuse_policy=runtime_recall_replay"))
    }

    @Test
    fun `render guidance exposes node skill even without function candidates`() {
        val guidance = VlmRecallGuidanceBuilder.renderGuidance(
            mapOf(
                "success" to true,
                "decision" to "recall",
                "node_candidates" to listOf(
                    mapOf(
                        "node_id" to "udeg_node_settings",
                        "page_similarity" to 0.91,
                        "decision_context" to mapOf(
                            "role" to "decision",
                            "entry_policy" to "page_match_to_udeg_node",
                            "skill_id" to "udeg_node_skill_udeg_node_settings",
                            "decision_path" to "page match -> UDEG node -> node skill-like decision context -> VLM/tool decision",
                        ),
                        "node_skill_context" to mapOf(
                            "role" to "decision",
                            "context_kind" to "udeg_node_skill_like_decision_context",
                            "entry_policy" to "page_match_to_udeg_node",
                            "decision_path" to "page match -> UDEG node -> node skill-like decision context -> VLM/tool decision",
                            "page_match" to mapOf(
                                "node_id" to "udeg_node_settings",
                                "page_similarity" to 0.91,
                                "reason" to "page_vector_strong_match",
                            ),
                        ),
                        "skill" to mapOf(
                            "decision_guidance" to "Use Settings node context before choosing actions.",
                            "body" to """
                                # UDEG Node Skill

                                ## Decision Context
                                Use Settings node context before choosing actions.
                            """.trimIndent(),
                        ),
                    )
                ),
                "candidates" to emptyList<Map<String, Any?>>(),
            )
        )

        assertTrue(guidance.contains("decision=recall"))
        assertTrue(guidance.contains("page_context=matched_current_page_context_available"))
        assertFalse(guidance.contains("node 1: node_id=udeg_node_settings"))
        assertFalse(guidance.contains("Use Settings node context before choosing actions."))
    }

    @Test
    fun `render guidance exposes node attached capabilities for VLM decision`() {
        val guidance = VlmRecallGuidanceBuilder.renderGuidance(
            mapOf(
                "success" to true,
                "decision" to "recall",
                "decision_policy" to mapOf(
                    "mode" to "node_skill_context_only",
                    "requires_vlm_or_tool_decision" to true,
                ),
                "node_candidates" to listOf(
                    mapOf(
                        "node_id" to "udeg_node_settings",
                        "page_similarity" to 0.94,
                    )
                ),
                "capability_candidates" to listOf(
                    mapOf(
                        "capability_type" to "function",
                        "recall_scope" to "udeg_node",
                        "function_id" to "open_network_settings",
                        "score" to 0.98,
                        "description" to "Open Network settings from the Settings page.",
                        "step_summaries" to listOf(
                            mapOf("tool" to "click", "title" to "click: Network & internet"),
                        ),
                    )
                ),
            )
        )

        assertTrue(guidance.contains("decision=recall"))
        assertTrue(guidance.contains("function_reuse=runtime_only"))
        assertFalse(guidance.contains("capability 1: type=function scope=udeg_node function=open_network_settings"))
        assertFalse(guidance.contains("open_network_settings"))
        assertFalse(guidance.contains("capability_step: 1. click"))
        assertNull(
            VlmRecallGuidanceBuilder.directHitFunctionId(
                mapOf(
                    "success" to true,
                    "decision" to "recall",
                    "capability_candidates" to listOf(mapOf("function_id" to "open_network_settings")),
                )
            )
        )
    }

    @Test
    fun `context only recall renders function candidates without making them direct calls`() {
        val guidance = VlmRecallGuidanceBuilder.renderGuidance(
            mapOf(
                "success" to true,
                "decision" to "recall",
                "decision_policy" to mapOf(
                    "mode" to "node_skill_context_only",
                    "requires_vlm_or_tool_decision" to true,
                ),
                "node_candidates" to listOf(
                    mapOf(
                        "node_id" to "udeg_node_settings",
                        "page_similarity" to 0.93,
                    )
                ),
                "candidates" to listOf(
                    mapOf(
                        "function_id" to "open_network_settings",
                        "score" to 0.91,
                        "description" to "Open Network settings from Settings.",
                        "step_summaries" to listOf(
                            mapOf("tool" to "click", "title" to "click: Network & internet"),
                        ),
                    )
                ),
            )
        )

        assertTrue(guidance.contains("function_reuse_policy=runtime_context_only"))
        assertTrue(guidance.contains("model_must_choose_normal_ui_action=true"))
        assertFalse(guidance.contains("preferred_call_tool"))
        assertFalse(guidance.contains("1. function=open_network_settings"))
        assertFalse(guidance.contains("open_network_settings"))
        assertFalse(guidance.contains("step:"))
        assertFalse(guidance.contains("segment"))
        assertFalse(guidance.contains("start_step_index"))
        assertNull(
            VlmRecallGuidanceBuilder.directHitFunctionId(
                mapOf(
                    "success" to true,
                    "decision" to "recall",
                    "candidates" to listOf(mapOf("function_id" to "open_network_settings")),
                )
            )
        )
    }

    @Test
    fun `strict direct hit payload is candidate only unless caller enables auto execution`() {
        val payload = mapOf(
            "success" to true,
            "decision" to "hit",
            "hit" to mapOf(
                "function_id" to "open_network_settings",
                "score" to 1.0,
                "page_similarity" to 1.0,
                "text_score" to 1.0,
                "strict_direct_hit" to true,
                "requires_arguments" to false,
            ),
        )

        val contextOnly = VlmRecallGuidanceBuilder.fromAgentPayload(
            payload = payload,
            allowDirectExecutionDecision = false,
        )
        val directAllowed = VlmRecallGuidanceBuilder.fromAgentPayload(
            payload = payload,
            allowDirectExecutionDecision = true,
        )

        assertNull(contextOnly.directHitFunctionId)
        assertEquals("open_network_settings", directAllowed.directHitFunctionId)
    }

    @Test
    fun `missing page match does not render catalog function candidates for online VLM step guidance`() {
        val guidance = VlmRecallGuidanceBuilder.renderGuidance(
            mapOf(
                "success" to true,
                "decision" to "miss",
                "decision_policy" to mapOf(
                    "mode" to "current_page_required",
                    "requires_vlm_or_tool_decision" to true,
                ),
                "candidates" to emptyList<Map<String, Any?>>(),
            )
        )

        assertEquals("", guidance)
        assertFalse(guidance.contains("function=open_settings_from_history"))
        assertFalse(guidance.contains("function_catalog"))
        assertFalse(guidance.contains("step:"))
    }

    @Test
    fun `catalog function candidate renders runtime recall guidance only`() {
        val guidance = VlmRecallGuidanceBuilder.renderGuidance(
            mapOf(
                "success" to true,
                "decision" to "recall",
                "decision_policy" to mapOf(
                    "mode" to "function_catalog_context",
                    "requires_vlm_or_tool_decision" to true,
                ),
                "candidates" to listOf(
                    mapOf(
                        "function_id" to "open_settings_from_history",
                        "name" to "Open Settings",
                        "description" to "Open Android Settings from any current screen.",
                        "score" to 0.99,
                        "text_score" to 0.99,
                        "recall_scope" to "function_catalog",
                        "requires_arguments" to false,
                        "function_profile" to mapOf(
                            "purpose" to "Open Android Settings",
                            "package_name" to "com.android.settings",
                        ),
                    )
                ),
            )
        )

        assertFalse(guidance.contains("preferred_call_tool"))
        assertFalse(guidance.contains("\"name\":\"call_tool\""))
        assertFalse(guidance.contains("function=open_settings_from_history"))
        assertFalse(guidance.contains("open_settings_from_history"))
        assertFalse(guidance.contains("mode=function_catalog_context"))
        assertTrue(guidance.contains("function_reuse=runtime_only"))
    }

    @Test
    fun `agent safe payload removes recall timing fields`() {
        val safe = VlmRecallGuidanceBuilder.agentSafePayload(
            mapOf(
                "success" to true,
                "decision" to "recall",
                "timing" to mapOf(
                    "duration_ms" to 12,
                    "phase_ms" to mapOf("page_match_ms" to 3),
                ),
                "candidates" to listOf(
                    mapOf(
                        "function_id" to "open_settings",
                        "started_at_ms" to 100,
                        "finished_at_ms" to 120,
                        "step_summaries" to listOf(
                            mapOf("tool" to "click", "duration_ms" to 5),
                        ),
                    )
                ),
            )
        )

        assertFalse(safe.containsKey("timing"))
        val candidate = (safe["candidates"] as List<*>).single() as Map<*, *>
        assertEquals("open_settings", candidate["function_id"])
        assertFalse(candidate.containsKey("started_at_ms"))
        assertFalse(candidate.containsKey("finished_at_ms"))
        val step = (candidate["step_summaries"] as List<*>).single() as Map<*, *>
        assertFalse(step.containsKey("duration_ms"))
    }

    @Test
    fun `direct hit function id requires strict page and text evidence`() {
        assertEquals(
            "open_settings",
            VlmRecallGuidanceBuilder.directHitFunctionId(
                mapOf(
                    "success" to true,
                    "decision" to "hit",
                    "hit" to mapOf(
                        "function_id" to "open_settings",
                        "score" to 1.0,
                        "page_similarity" to 1.0,
                        "text_score" to 1.0,
                    ),
                )
            )
        )
        assertNull(
            VlmRecallGuidanceBuilder.directHitFunctionId(
                mapOf(
                    "success" to true,
                    "decision" to "hit",
                    "hit" to mapOf(
                        "function_id" to "open_settings",
                        "score" to 0.94,
                        "page_similarity" to 0.89,
                        "text_score" to 1.0,
                    ),
                )
            )
        )
        assertNull(
            VlmRecallGuidanceBuilder.directHitFunctionId(
                mapOf(
                    "success" to true,
                    "decision" to "hit",
                    "hit" to mapOf("function_id" to "open_settings"),
                )
            )
        )
        assertNull(
            VlmRecallGuidanceBuilder.directHitFunctionId(
                mapOf(
                    "success" to true,
                    "decision" to "recall",
                    "candidates" to listOf(mapOf("function_id" to "open_settings")),
                )
            )
        )
        assertNull(
            VlmRecallGuidanceBuilder.directHitFunctionId(
                mapOf(
                    "success" to false,
                    "decision" to "hit",
                    "hit" to mapOf("function_id" to "open_settings"),
                )
            )
        )
    }

    @Test
    fun `catalog exact match direct hit does not require page similarity`() {
        assertEquals(
            "meituan_takeout_search",
            VlmRecallGuidanceBuilder.directHitFunctionId(
                mapOf(
                    "success" to true,
                    "decision" to "hit",
                    "hit" to mapOf(
                        "function_id" to "meituan_takeout_search",
                        "score" to 1.0,
                        "page_similarity" to 0.0,
                        "text_score" to 1.0,
                        "strict_direct_hit" to true,
                        "recall_scope" to "function_catalog",
                        "direct_hit_policy" to "catalog_exact_match_same_package_goal",
                    ),
                )
            )
        )
        assertNull(
            VlmRecallGuidanceBuilder.directHitFunctionId(
                mapOf(
                    "success" to true,
                    "decision" to "hit",
                    "hit" to mapOf(
                        "function_id" to "legacy_without_catalog_policy",
                        "score" to 1.0,
                        "page_similarity" to 0.0,
                        "text_score" to 1.0,
                        "strict_direct_hit" to true,
                    ),
                )
            )
        )
    }

    @Test
    fun `parameterized hit is runtime executable after runtime resolve`() {
        val payload = mapOf(
            "success" to true,
            "decision" to "hit",
            "decision_policy" to mapOf(
                "mode" to "direct_execution_allowed",
                "direct_hit_requested" to true,
                "direct_hit_allows_runtime_resolved_arguments" to true,
            ),
            "hit" to mapOf(
                "function_id" to "send_message",
                "score" to 0.96,
                "page_similarity" to 0.94,
                "text_score" to 0.91,
                "strict_direct_hit" to true,
                "requires_arguments" to true,
                "resolve_policy" to "required_arguments_from_user_goal",
                "inputSchema" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "contact" to mapOf("type" to "string"),
                        "message" to mapOf("type" to "string"),
                    ),
                    "required" to listOf("contact", "message"),
                ),
                "function_profile" to mapOf(
                    "purpose" to "Send a chat message to a contact.",
                    "use_when" to "User asks to message someone.",
                    "success_signal" to "Message appears in the conversation.",
                ),
            ),
        )

        val guidance = VlmRecallGuidanceBuilder.renderGuidance(payload)

        assertEquals(
            "send_message",
            VlmRecallGuidanceBuilder.fromAgentPayload(
                payload,
                allowDirectExecutionDecision = true
            ).directHitFunctionId
        )
        assertFalse(guidance.contains("function=send_message"))
        assertFalse(guidance.contains("send_message"))
        assertFalse(guidance.contains("preferred_call_tool"))
        assertFalse(guidance.contains("call_tool(function_id=\"send_message\""))
        assertFalse(guidance.contains("resolve_policy: requires_arguments=true"))
        assertFalse(guidance.contains("arguments={contact:string required, message:string required}"))
        assertFalse(guidance.contains("function_profile: purpose=Send a chat message"))

        val debug = VlmRecallGuidanceBuilder.renderDebugGuidance(payload)
        assertTrue(debug.contains("function=send_message"))
        assertTrue(debug.contains("resolve_policy: requires_arguments=true"))
        assertTrue(debug.contains("arguments={contact:string required, message:string required}"))
        assertTrue(debug.contains("function_profile: purpose=Send a chat message"))
    }

    @Test
    fun `online page context provider records first seen page without injecting node skill`() = runBlocking {
        val context = OobOmniFlowLoopAcceptanceTest.TempFilesContext()
        try {
            val provider = OobVlmPageContextProvider(context)
            val enriched = provider.enrich(
                VLMPageContextRequest(
                    context = UIContext(
                        overallTask = "Open Network settings",
                        targetPackageName = "com.example.settings",
                    ),
                    currentXml = SETTINGS_XML,
                    currentPackageName = "com.example.settings",
                    screenshotBase64 = "fake-screenshot",
                    stepIndex = 0,
                    snapshot = VLMCurrentPageSnapshot(
                        packageName = "com.example.settings",
                        xml = SETTINGS_XML,
                        screenshotBase64 = "fake-screenshot",
                        displayWidth = 1080,
                        displayHeight = 1920,
                        capturedAtMs = 1234L,
                    )
                )
            )

            assertFalse(enriched.currentPageSummary.contains("OOB Page Skill"))
            assertEquals("true", enriched.pageDiagnostics["udeg_first_seen"])
            assertEquals("false", enriched.pageDiagnostics["udeg_context_injected"])
            assertEquals("1234", enriched.pageDiagnostics["snapshot_timestamp"])
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `online page context provider injects structured node skill after page match`() = runBlocking {
        val context = OobOmniFlowLoopAcceptanceTest.TempFilesContext()
        try {
            OobUdegNodeStore(context).upsertFunction(
                "open_network_settings",
                mapOf(
                    "name" to "Open Network settings",
                    "description" to "Open the Network row from Settings",
                    "execution" to mapOf(
                        "steps" to listOf(
                            mapOf(
                                "tool" to "click",
                                "title" to "Click Network",
                                "source_context" to mapOf(
                                    "src_ctx" to mapOf(
                                        "observation_xml" to SETTINGS_XML,
                                        "package_name" to "com.example.settings",
                                    )
                                )
                            )
                        )
                    )
                )
            )
            val provider = OobVlmPageContextProvider(context)
            val enriched = provider.enrich(
                VLMPageContextRequest(
                    context = UIContext(
                        overallTask = "Open Network settings",
                        targetPackageName = "com.example.settings",
                    ),
                    currentXml = SETTINGS_XML,
                    currentPackageName = "com.example.settings",
                    screenshotBase64 = "fake-screenshot",
                    stepIndex = 1,
                )
            )

            assertTrue(enriched.currentPageSummary.contains("OOB Page Skill"))
            assertTrue(enriched.currentPageSummary.contains("当前页面:"))
            assertFalse(enriched.currentPageSummary.contains(OobUdegNodeStore.UDEG_DECISION_PATH))
            assertFalse(enriched.currentPageSummary.contains("UDEG attached Functions"))
            assertFalse(enriched.currentPageSummary.contains("function_id=open_network_settings"))
            assertFalse(enriched.currentPageSummary.contains("VLM调用格式: call_tool"))
            assertFalse(enriched.currentPageSummary.contains("UDEG可见文本"))
            assertFalse(enriched.currentPageSummary.contains("UDEG可交互元素"))
            assertTrue(enriched.currentPageSummary.contains("只描述当前页面是什么"))
            assertEquals("false", enriched.pageDiagnostics["udeg_first_seen"])
            assertEquals("true", enriched.pageDiagnostics["udeg_context_injected"])
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `online page context provider uses snapshot xml instead of stale request xml`() = runBlocking {
        val context = OobOmniFlowLoopAcceptanceTest.TempFilesContext()
        try {
            OobUdegNodeStore(context).upsertFunction(
                "open_network_settings",
                mapOf(
                    "name" to "Open Network settings",
                    "description" to "Open the Network row from Settings",
                    "execution" to mapOf(
                        "steps" to listOf(
                            mapOf(
                                "tool" to "click",
                                "title" to "Click Network",
                                "source_context" to mapOf(
                                    "src_ctx" to mapOf(
                                        "observation_xml" to SETTINGS_XML,
                                        "package_name" to "com.example.settings",
                                    )
                                )
                            )
                        )
                    )
                )
            )
            val provider = OobVlmPageContextProvider(context)
            val enriched = provider.enrich(
                VLMPageContextRequest(
                    context = UIContext(
                        overallTask = "Open Network settings",
                        targetPackageName = "com.example.settings",
                    ),
                    currentXml = SETTINGS_XML,
                    currentPackageName = "com.example.settings",
                    screenshotBase64 = "stale-screenshot",
                    stepIndex = 2,
                    snapshot = VLMCurrentPageSnapshot(
                        packageName = "com.example.settings",
                        xml = DISPLAY_XML,
                        screenshotBase64 = "fresh-screenshot",
                        displayWidth = 1080,
                        displayHeight = 1920,
                        capturedAtMs = 5678L,
                    )
                )
            )

            assertFalse(enriched.currentPageSummary.contains("function_id=open_network_settings"))
            assertFalse(enriched.currentPageSummary.contains("OOB Page Skill"))
            assertEquals("true", enriched.pageDiagnostics["udeg_first_seen"])
            assertEquals("false", enriched.pageDiagnostics["udeg_context_injected"])
            assertEquals("5678", enriched.pageDiagnostics["snapshot_timestamp"])
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `recall step guidance keeps recall block before existing guidance budget`() {
        val base = "existing guidance ".repeat(120)
        val recall = """
            [[OOB_OMNIFLOW_STEP_RECALL_START]]
            function_reuse_policy=runtime_context_only
            online_action_policy=if this turn reaches the VLM, output exactly one ordinary UI action for the current screen.
            [[OOB_OMNIFLOW_STEP_RECALL_END]]
        """.trimIndent()

        val merged = mergeOobVlmRecallStepGuidance(
            baseGuidance = base,
            recallBlock = recall,
            maxChars = 220,
        )

        assertTrue(merged.startsWith("[[OOB_OMNIFLOW_STEP_RECALL_START]]"))
        assertTrue(merged.contains("online_action_policy="))
        assertFalse(merged.contains("function=open_bluetooth"))
        assertFalse(merged.contains("preferred_call_tool"))
        assertFalse(merged.contains("\"name\":\"call_tool\""))
        assertTrue(merged.length <= 220)
        assertFalse(merged.startsWith("existing guidance"))
    }

    private companion object {
        const val SETTINGS_XML = """
            <hierarchy>
              <node class="android.widget.FrameLayout" package="com.example.settings" bounds="[0,0][1080,1920]">
                <node class="android.widget.TextView" package="com.example.settings" text="Settings" bounds="[32,64][400,160]" />
                <node class="android.widget.TextView" package="com.example.settings" text="Network" clickable="true" enabled="true" bounds="[32,240][1048,360]" />
                <node class="android.widget.TextView" package="com.example.settings" text="Display" clickable="true" enabled="true" bounds="[32,380][1048,500]" />
              </node>
            </hierarchy>
        """

        const val DISPLAY_XML = """
            <hierarchy>
              <node class="android.widget.FrameLayout" package="com.example.settings" bounds="[0,0][1080,1920]">
                <node class="android.widget.TextView" package="com.example.settings" text="Display" bounds="[32,64][400,160]" />
                <node class="android.widget.TextView" package="com.example.settings" text="Brightness level" clickable="true" enabled="true" bounds="[32,240][1048,360]" />
                <node class="android.widget.TextView" package="com.example.settings" text="Dark theme" clickable="true" enabled="true" bounds="[32,380][1048,500]" />
              </node>
            </hierarchy>
        """
    }
}
