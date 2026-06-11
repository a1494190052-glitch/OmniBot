package cn.com.omnimind.bot.runlog

import cn.com.omnimind.omniintelligence.models.ScrollDirection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class UIStepExecutorTest {
    @Test
    fun `detects explicit omniflow steps`() {
        val step = mapOf(
            "executor" to "omniflow",
            "tool" to "click",
            "args" to mapOf("x" to 10, "y" to 20),
        )

        assertTrue(UIStepExecutor.isUIStep(step))
        assertEquals("click", UIStepExecutor.actionNameForStep(step))
    }

    @Test
    fun `detects model free local actions without explicit executor`() {
        val step = mapOf(
            "model_free" to true,
            "tool" to "press_key",
            "args" to mapOf("key" to "back"),
        )

        assertTrue(UIStepExecutor.isUIStep(step))
        assertEquals("press_key", UIStepExecutor.actionNameForStep(step))
    }

    @Test
    fun `does not classify unknown model free tool as omniflow`() {
        val step = mapOf(
            "model_free" to true,
            "tool" to "browser_use",
            "args" to emptyMap<String, Any?>(),
        )

        assertFalse(UIStepExecutor.isUIStep(step))
    }

    @Test
    fun `does not normalize legacy action aliases`() {
        assertEquals(
            "tap",
            UIStepExecutor.actionNameForStep(
                mapOf("model_free" to true, "tool" to "tap")
            )
        )
        assertFalse(
            UIStepExecutor.isUIStep(
                mapOf("model_free" to true, "tool" to "tap")
            )
        )
        assertEquals(
            "type_text",
            UIStepExecutor.actionNameForStep(
                mapOf("model_free" to true, "tool" to "type_text")
            )
        )
        assertFalse(
            UIStepExecutor.isUIStep(
                mapOf("model_free" to true, "tool" to "type_text")
            )
        )
        assertEquals(
            "done",
            UIStepExecutor.actionNameForStep(
                mapOf("model_free" to true, "tool" to "done")
            )
        )
        assertFalse(
            UIStepExecutor.isUIStep(
                mapOf("model_free" to true, "tool" to "done")
            )
        )
    }

    @Test
    fun `detects omniflow canonical action names`() {
        val step = mapOf(
            "model_free" to true,
            "tool" to "input_text",
            "args" to mapOf("text" to "hello"),
        )

        assertTrue(UIStepExecutor.isUIStep(step))
        assertEquals("input_text", UIStepExecutor.actionNameForStep(step))
    }

    @Test
    fun `identifies local replay actions that require accessibility`() {
        assertTrue(
            UIStepExecutor.requiresAccessibility(
                mapOf("executor" to "omniflow", "tool" to "click")
            )
        )
        assertTrue(
            UIStepExecutor.requiresAccessibility(
                mapOf("executor" to "omniflow", "tool" to "swipe")
            )
        )
        assertFalse(
            UIStepExecutor.requiresAccessibility(
                mapOf("executor" to "omniflow", "tool" to "open_app")
            )
        )
        assertFalse(
            UIStepExecutor.requiresAccessibility(
                mapOf("executor" to "omniflow", "tool" to "wait")
            )
        )
        assertFalse(
            UIStepExecutor.requiresAccessibility(
                mapOf("executor" to "omniflow", "tool" to "finished")
            )
        )
    }

    @Test
    fun `does not classify legacy type content as input text`() {
        val step = mapOf(
            "executor" to "omniflow",
            "tool" to "type",
            "args" to mapOf("content" to "hello"),
        )

        assertFalse(UIStepExecutor.isUIStep(step))
        assertEquals("type", UIStepExecutor.actionNameForStep(step))
    }

    @Test
    fun `execute wait action preserves collected time ms`() = runBlocking {
        val backend = FakeBackend(beforeXml = SOURCE_XML, afterXml = AFTER_XML)
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "wait",
                    "args" to mapOf("time_ms" to 1),
                ),
                stepId = "step_wait",
                stepTitle = "wait while recording",
            )

            assertEquals(true, result["success"])
            assertEquals("direct_replay", result["replay_mode"])
            val executed = result["executed_action"] as Map<*, *>
            val args = executed["args"] as Map<*, *>
            assertEquals(1L, (args["time_ms"] as Number).toLong())
            assertTrue("wait should not dispatch clicks", backend.clickPoints.isEmpty())
        }
    }

    @Test
    fun `execute derives coordinate remap from action and source context`() = runBlocking {
        val backend = FakeBackend(beforeXml = SOURCE_XML, afterXml = AFTER_XML)
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "click",
                    "args" to mapOf("x" to 120, "y" to 240),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf("page" to SOURCE_XML),
                        "dst_ctx" to mapOf(
                            "page" to AFTER_XML,
                            "package_name" to "com.example",
                        ),
                    ),
                    "postcondition" to mapOf(
                        "kind" to "recorded_after_page_similarity",
                        "min_score" to 0.1,
                    ),
                ),
                stepId = "step_1",
                stepTitle = "click open",
            )

            assertEquals(true, result["success"])
            assertEquals("action_transfer", result["replay_mode"])
            assertFalse(result.containsKey("postcondition"))
            val checker = result["checker"] as? Map<*, *> ?: error("missing checker")
            assertEquals("before_action", checker["phase"])
            assertEquals(true, checker["verified"])
            assertFalse(result.containsKey("control_effects"))
            val postActionObserve = result["post_action_observe"] as? Map<*, *>
                ?: error("missing post action observe")
            assertEquals("observed", postActionObserve["status"])
            assertEquals("click", postActionObserve["action"])
            assertEquals(true, postActionObserve["xml_ready"])
            assertEquals(AFTER_XML.length, postActionObserve["xml_chars"])
            assertEquals("com.example", postActionObserve["effective_package"])
            val timing = result["timing"] as? Map<*, *> ?: error("missing timing")
            assertEquals("oob_omniflow_step_executor", timing["source"])
            val phaseMs = timing["phase_ms"] as? Map<*, *> ?: error("missing phase timing")
            listOf(
                "observe_ms",
                "checker_ms",
                "action_transfer_ms",
                "act_ms",
                "post_action_observe_ms",
            ).forEach { phase ->
                assertTrue("missing $phase", phaseMs.containsKey(phase))
                assertTrue((phaseMs[phase] as Number).toLong() >= 0L)
            }
            assertTrue((result["duration_ms"] as Number).toLong() >= 0L)
        }
    }

    @Test
    fun `execute reads source context nested under args`() = runBlocking {
        val backend = FakeBackend(beforeXml = SOURCE_XML, afterXml = AFTER_XML)
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "click",
                    "args" to mapOf(
                        "x" to 120,
                        "y" to 240,
                        "source_context" to mapOf("page" to SOURCE_XML),
                    ),
                ),
                stepId = "step_args_source_context",
                stepTitle = "click with nested source context",
            )

            assertEquals(true, result["success"])
            assertEquals("action_transfer", result["replay_mode"])
            val transfer = result["action_transfer"] as? Map<*, *> ?: error("missing action transfer")
            assertEquals(true, transfer["applied"])
            assertFalse("source_context under args must not be dropped", transfer["reason"] == "missing_source_context")
        }
    }

    @Test
    fun `coordinate only source context can replay recorded point when current page matches source page`() = runBlocking {
        val sparseOverlayXml =
            """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy xmlns="http://schemas.android.com/apk/res/android"><node id="0" class="android.widget.FrameLayout" enabled="true" bounds="[199,66][640,157]"><node id="2" class="android.view.ViewGroup" enabled="true" clickable="true" focusable="true" bounds="[199,66][640,157]"><node id="4" text="Transient banner" class="android.widget.TextView" resource-id="com.example:id/body" enabled="true" bounds="[223,90][598,123]" /></node></node></hierarchy>"""
        val backend = FakeBackend(beforeXml = sparseOverlayXml, afterXml = AFTER_XML)
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "click",
                    "args" to mapOf(
                        "x" to 504,
                        "y" to 1129,
                        "source_context" to mapOf("page" to sparseOverlayXml),
                    ),
                ),
                stepId = "step_sparse_coordinate_only",
                stepTitle = "click target outside sparse overlay xml",
            )

            assertEquals(true, result["success"])
            assertEquals("recorded_action_replay", result["replay_mode"])
            val transfer = result["action_transfer"] as? Map<*, *> ?: error("missing action transfer")
            assertEquals(false, transfer["applied"])
            assertEquals("missing_source_element", transfer["source_reason"])
            assertEquals(true, transfer["source_page_matches_current"])
            assertEquals(true, transfer["recorded_action_args_used"])
            assertEquals(1, backend.clickPoints.size)
            assertEquals(504f, backend.clickPoints.single().first, 0.01f)
            assertEquals(1129f, backend.clickPoints.single().second, 0.01f)
        }
    }

    @Test
    fun `missing source point can remap by click prompt semantic target`() = runBlocking {
        val backend = FakeBackend(beforeXml = CLOCK_HOME_XML, afterXml = CLOCK_STOPWATCH_START_XML)
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val remapped = UIStepExecutor.remapStepArgs(
                mapOf(
                    "executor" to "omniflow",
                    "tool" to "click",
                    "args" to mapOf(
                        "clickPrompt" to "Stopwatch",
                        "x" to 504,
                        "y" to 1129,
                        "source_context" to mapOf("page" to PRIVACY_NOTICE_NO_BUTTON_XML),
                    ),
                )
            )
            assertEquals(
                "direct remap should use clickPrompt semantic target: ${remapped.meta}",
                true,
                remapped.meta["applied"],
            )
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "click",
                    "args" to mapOf(
                        "clickPrompt" to "Stopwatch",
                        "x" to 504,
                        "y" to 1129,
                        "source_context" to mapOf("page" to PRIVACY_NOTICE_NO_BUTTON_XML),
                    ),
                ),
                stepId = "step_sparse_source_semantic_target",
                stepTitle = "click stopwatch after sparse source page disappeared",
            )

            assertEquals(true, result["success"])
            assertEquals("action_transfer", result["replay_mode"])
            val transfer = result["action_transfer"] as? Map<*, *> ?: error("missing action transfer")
            assertEquals(true, transfer["applied"])
            assertEquals("semantic_target", transfer["mode"])
            val debug = transfer["debug"] as? Map<*, *> ?: error("missing transfer debug")
            assertEquals("text_exact", debug["matched_by"])
            assertEquals("stopwatch", debug["matched_value"])
            assertEquals(1, backend.clickPoints.size)
            assertEquals(504f, backend.clickPoints.single().first, 0.01f)
            assertEquals(1152f, backend.clickPoints.single().second, 0.01f)
        }
    }

    @Test
    fun `privacy sparse overlay can replay recorded point when source page is hidden`() = runBlocking {
        val backend = FakeBackend(beforeXml = PRIVACY_NOTICE_NO_BUTTON_XML, afterXml = PRIVACY_NOTICE_NO_BUTTON_XML)
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = try {
                UIStepExecutor.execute(
                    step = mapOf(
                        "executor" to "omniflow",
                        "tool" to "click",
                        "args" to mapOf(
                            "x" to 360,
                            "y" to 944,
                            "target_description" to "Start",
                            "node_resource_id" to "com.google.android.deskclock:id/fab",
                            "source_context" to mapOf(
                                "src_ctx" to mapOf("page" to CLOCK_STOPWATCH_START_XML),
                            ),
                        ),
                    ),
                    stepId = "step_sparse_privacy_overlay_target_hidden",
                    stepTitle = "click target hidden behind sparse privacy overlay xml",
                )
            } catch (e: UIStepExecutor.ExecutionException) {
                throw AssertionError("diagnostics=${e.diagnostics}", e)
            }

            assertEquals(true, result["success"])
            assertEquals("recorded_action_replay", result["replay_mode"])
            val transfer = result["action_transfer"] as? Map<*, *> ?: error("missing action transfer")
            assertEquals(false, transfer["applied"])
            assertEquals("no_anchor_match", transfer["reason"])
            assertEquals(false, transfer["source_page_matches_current"])
            assertEquals(true, transfer["current_sparse_overlay_page"])
            assertEquals(true, transfer["current_privacy_notice_overlay"])
            assertEquals(true, transfer["recorded_action_args_used"])
            assertTrue("checker should attempt to dismiss privacy overlay before replay", backend.clickPoints.size >= 2)
            assertEquals(360f, backend.clickPoints.last().first, 0.01f)
            assertEquals(944f, backend.clickPoints.last().second, 0.01f)
        }
    }

    @Test
    fun `action transfer reuses the replay state captured for checker`() = runBlocking {
        val backend = FakeBackend(beforeXml = SOURCE_XML, afterXml = AFTER_XML)
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "click",
                    "coordinate_hook" to "omniflow",
                    "args" to mapOf("x" to 120, "y" to 240),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf("page" to SOURCE_XML),
                    ),
                ),
                stepId = "step_single_state",
                stepTitle = "click open",
            )

            assertEquals(true, result["success"])
            assertEquals("action_transfer", result["replay_mode"])
            assertEquals(2, backend.currentXmlReadCount)
            assertTrue(result.containsKey("post_action_observe"))
            assertFalse(result.containsKey("step_settle"))
        }
    }

    @Test
    fun `swipe replay preserves explicit start and end coordinates`() = runBlocking {
        val backend = FakeBackend(beforeXml = SOURCE_XML, afterXml = AFTER_XML)
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "swipe",
                    "args" to mapOf(
                        "x1" to 100,
                        "y1" to 700,
                        "x2" to 640,
                        "y2" to 700,
                        "duration_ms" to 1200,
                    ),
                ),
                stepId = "step_swipe_endpoint",
                stepTitle = "drag slider",
            )

            assertEquals(true, result["success"])
            assertEquals(emptyList<Map<String, Any?>>(), backend.scrollRequests)
            assertEquals(1, backend.swipeRequests.size)
            assertEquals(
                mapOf(
                    "startX" to 100f,
                    "startY" to 700f,
                    "endX" to 640f,
                    "endY" to 700f,
                    "durationMs" to 1200L,
                ),
                backend.swipeRequests.single(),
            )
            val executed = result["executed_action"] as? Map<*, *> ?: error("missing executed action")
            val executedArgs = executed["args"] as? Map<*, *> ?: error("missing executed args")
            assertEquals(100f, (executedArgs["x1"] as Number).toFloat(), 0.01f)
            assertEquals(640f, (executedArgs["x2"] as Number).toFloat(), 0.01f)
        }
    }

    @Test
    fun `action transfer click does not dispatch through resource id`() = runBlocking {
        val backend = FakeBackend(beforeXml = SOURCE_XML, afterXml = AFTER_XML)
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "click",
                    "coordinate_hook" to "omniflow",
                    "args" to mapOf(
                        "x" to 120,
                        "y" to 240,
                        "target_description" to "Open",
                        "node_resource_id" to "android:id/summary",
                    ),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf("page" to SOURCE_XML),
                    ),
                ),
                stepId = "step_generic_android_resource",
                stepTitle = "click generic resource id",
            )

            assertEquals(true, result["success"])
            assertEquals("action_transfer", result["replay_mode"])
            assertEquals(1, backend.clickNodeResourceIds.size)
            assertEquals("", backend.clickNodeResourceIds.single())
        }
    }

    @Test
    fun `wait for replay action ready polls observe until semantic target appears`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = EMPTY_PAGE_XML,
            afterXml = EMPTY_PAGE_XML,
            preActionXmls = listOf(EMPTY_PAGE_XML, SOURCE_XML),
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val wait = UIStepExecutor.waitForReplayActionReady(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "click",
                    "args" to mapOf("x" to 120, "y" to 240, "target_description" to "Open"),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf("page" to SOURCE_XML),
                    ),
                ),
                targetTimeoutMs = 100,
                pollMs = 1,
            )

            assertEquals("ready", wait["status"])
            assertEquals(2, (wait["attempts"] as Number).toInt())
            assertEquals(true, wait["xml_ready"])
            assertEquals("text_exact", wait["matched_by"])
            assertEquals("open", wait["matched_value"])
            assertTrue(backend.currentXmlReadCount >= 2)
        }
    }

    @Test
    fun `wait for replay action ready times out without failing`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = EMPTY_PAGE_XML,
            afterXml = EMPTY_PAGE_XML,
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val wait = UIStepExecutor.waitForReplayActionReady(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "click",
                    "args" to mapOf("x" to 120, "y" to 240, "target_description" to "Open"),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf("page" to SOURCE_XML),
                    ),
                ),
                targetTimeoutMs = 1,
                pollMs = 1,
            )

            assertEquals("timeout", wait["status"])
            assertEquals("semantic_target_not_visible", wait["reason"])
            assertTrue((wait["attempts"] as Number).toInt() >= 1)
        }
    }

    @Test
    fun `wait for replay action ready repeats previous swipe until semantic target appears`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = EMPTY_PAGE_XML,
            afterXml = SOURCE_XML,
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val wait = UIStepExecutor.waitForReplayActionReady(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "click",
                    "args" to mapOf("x" to 120, "y" to 240, "target_description" to "Open"),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf("page" to SOURCE_XML),
                    ),
                ),
                recoveryStep = mapOf(
                    "executor" to "omniflow",
                    "tool" to "swipe",
                    "args" to mapOf(
                        "x1" to 500,
                        "y1" to 1050,
                        "x2" to 500,
                        "y2" to 350,
                        "duration_ms" to 400,
                    ),
                ),
            )

            assertEquals("ready", wait["status"])
            assertEquals("text_exact", wait["matched_by"])
            assertEquals("open", wait["matched_value"])
            val recovery = wait["recovery"] as? Map<*, *> ?: error("missing recovery")
            assertEquals("swipe", recovery["action"])
            assertEquals("repeated_previous_scroll_until_target_ready", recovery["reason"])
            assertEquals(1, backend.swipeRequests.size)
        }
    }

    @Test
    fun `checker control action refreshes replay state before transfer`() = runBlocking {
        val backend = FakeBackend(beforeXml = AD_OVERLAY_XML, afterXml = SOURCE_XML)
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "click",
                    "coordinate_hook" to "omniflow",
                    "args" to mapOf("x" to 120, "y" to 240),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf("page" to SOURCE_XML),
                    ),
                ),
                stepId = "step_control_refresh",
                stepTitle = "click behind ad",
            )

            assertEquals(true, result["success"])
            assertEquals("action_transfer", result["replay_mode"])
            assertTrue(backend.currentXmlReadCount >= 2)
            assertEquals(2, backend.clickPoints.size)
        }
    }

    @Test
    fun `execute normalizes recorded package from expected xml`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = SETTINGS_XML,
            afterXml = SETTINGS_APPS_XML,
            currentPackage = "com.android.settings",
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "click",
                    "args" to mapOf(
                        "x" to 360,
                        "y" to 977,
                        "coordinate_replay_allowed" to true,
                    ),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf("page" to SETTINGS_XML),
                        "dst_ctx" to mapOf(
                            "page" to SETTINGS_APPS_XML,
                            "package_name" to "cn.com.omnimind.bot.debug",
                        ),
                    ),
                    "postcondition" to mapOf(
                        "kind" to "recorded_after_page_similarity",
                        "min_score" to 0.1,
                    ),
                ),
                stepId = "step_package_infer",
                stepTitle = "click Apps",
            )

            assertEquals(true, result["success"])
            assertFalse(result.containsKey("postcondition"))
        }
    }

    @Test
    fun `execute accepts android system package namespace alias`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = SETTINGS_APPS_XML,
            afterXml = PERMISSION_CONTROLLER_XML,
            currentPackage = "com.google.android.permissioncontroller",
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "click",
                    "args" to mapOf("x" to 360, "y" to 640),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf("page" to SETTINGS_APPS_XML),
                        "dst_ctx" to mapOf(
                            "page" to PERMISSION_CONTROLLER_XML,
                            "package_name" to "com.android.permissioncontroller",
                        ),
                    ),
                    "postcondition" to mapOf(
                        "kind" to "recorded_after_page_similarity",
                        "min_score" to 0.1,
                    ),
                ),
                stepId = "step_permission_controller",
                stepTitle = "click default apps",
            )

            assertEquals(true, result["success"])
            assertFalse(result.containsKey("postcondition"))
        }
    }

    @Test
    fun `execute does not skip based on recorded after page`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = SETTINGS_APPS_XML,
            afterXml = SETTINGS_APPS_XML,
            currentPackage = "com.android.settings",
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "click",
                    "coordinate_hook" to "omniflow",
                    "args" to mapOf(
                        "x" to 360,
                        "y" to 977,
                        "coordinate_replay_allowed" to true,
                    ),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf(
                            "page" to SETTINGS_XML,
                            "package_name" to "com.android.settings",
                        ),
                        "dst_ctx" to mapOf(
                            "page" to SETTINGS_APPS_XML,
                            "package_name" to "com.android.settings",
                        ),
                    ),
                    "postcondition" to mapOf(
                        "kind" to "recorded_after_page_similarity",
                        "min_score" to 0.1,
                    ),
                ),
                stepId = "step_already_done",
                stepTitle = "click Apps",
            )

            assertEquals(true, result["success"])
            assertFalse(result["skipped"] == true)
            assertTrue(backend.clicked)
            assertFalse(result.containsKey("postcondition"))
        }
    }

    @Test
    fun `execute ignores recorded after postcondition for transient settings search page`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = SETTINGS_XML,
            afterXml = SETTINGS_SEARCH_CURRENT_XML,
            currentPackage = "com.google.android.settings.intelligence",
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "click",
                    "title" to "点击 Search settings search_action_bar ViewGroup",
                    "args" to mapOf(
                        "x" to 500,
                        "y" to 120,
                        "target_description" to "Search settings search_action_bar ViewGroup",
                    ),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf("page" to SETTINGS_XML),
                        "dst_ctx" to mapOf(
                            "page" to SETTINGS_SEARCH_RECORDED_XML,
                            "package_name" to "com.google.android.settings.intelligence",
                        ),
                    ),
                    "postcondition" to mapOf(
                        "kind" to "recorded_after_page_similarity",
                        "min_score" to 0.95,
                        "allow_package_only_for_transient_search" to true,
                    ),
                ),
                stepId = "step_search",
                stepTitle = "click search settings",
            )

            assertEquals(true, result["success"])
            assertFalse(result.containsKey("postcondition"))
        }
    }

    @Test
    fun `execute open app does not emit post checker`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = SOURCE_XML,
            afterXml = AFTER_XML,
            currentPackage = "com.example",
            missingXmlReadsAfterAction = 2,
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "open_app",
                    "args" to mapOf("package_name" to "com.example"),
                    "source_context" to mapOf(
                        "dst_ctx" to mapOf(
                            "page" to AFTER_XML,
                            "package_name" to "com.example",
                        ),
                    ),
                    "postcondition" to mapOf(
                        "kind" to "recorded_after_page_similarity",
                        "min_score" to 0.1,
                    ),
                ),
                stepId = "step_open_app",
                stepTitle = "open app",
            )

            assertEquals(true, result["success"])
            assertFalse(result.containsKey("postcondition"))
            assertFalse(result.containsKey("checker"))
        }
    }

    @Test
    fun `execute opens app without activity post checker`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = "",
            afterXml = "",
            currentPackage = "",
            currentActivity = "com.android.settings/.Settings",
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "open_app",
                    "args" to mapOf("package_name" to "com.android.settings"),
                ),
                stepId = "step_open_app_activity_package",
                stepTitle = "open settings",
            )

            assertEquals(true, result["success"])
            assertFalse(result.containsKey("postcondition"))
            assertFalse(result.containsKey("checker"))
        }
    }

    @Test
    fun `open app runs resolver checker after launch`() = runBlocking {
        val backend = FakeBackend(beforeXml = SOURCE_XML, afterXml = RESOLVER_DIALOG_XML)
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "open_app",
                    "args" to mapOf("package_name" to "com.example"),
                ),
                stepId = "step_open_app_resolver",
                stepTitle = "open app with resolver",
            )

            assertEquals(true, result["success"])
            assertEquals(listOf("com.example:false"), backend.launchRequests)
            val controlEffects = result["control_effects"] as? List<*> ?: error("missing control effects")
            val effect = controlEffects.single() as Map<*, *>
            assertEquals("confirm_resolver_always_after_open_app", effect["controller"])
            assertEquals("post_action", effect["phase"])
            assertEquals("resolver_dialog", effect["condition"])
            assertEquals("confirm_resolver_always", effect["action"])
            assertEquals(1, backend.clickPoints.size)
            assertEquals(810f, backend.clickPoints.single().first, 0.01f)
            assertEquals(1690f, backend.clickPoints.single().second, 0.01f)
        }
    }

    @Test
    fun `open app dismisses app upgrade prompt after launch`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = SOURCE_XML,
            afterXml = HI_UPGRADE_DIALOG_XML,
            currentPackage = "com.example.hi",
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "open_app",
                    "args" to mapOf("package_name" to "com.example.hi"),
                ),
                stepId = "step_open_app_hi_upgrade",
                stepTitle = "open hi app",
            )

            assertEquals(true, result["success"])
            assertEquals(listOf("com.example.hi:false"), backend.launchRequests)
            val controlEffects = result["control_effects"] as? List<*> ?: error("missing control effects")
            val effect = controlEffects.single() as Map<*, *>
            assertEquals("dismiss_app_upgrade_prompt_after_open_app", effect["controller"])
            assertEquals("post_action", effect["phase"])
            assertEquals("app_upgrade_prompt", effect["condition"])
            assertEquals("dismiss", effect["action"])
            assertEquals("以后再说 later", effect["button_text"])
            assertEquals(1, backend.clickPoints.size)
            assertEquals(510f, backend.clickPoints.single().first, 0.01f)
            assertEquals(1240f, backend.clickPoints.single().second, 0.01f)
        }
    }

    @Test
    fun `execute opens app with canonical package name only`() = runBlocking {
        val backend = FakeBackend(beforeXml = SOURCE_XML, afterXml = AFTER_XML)
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "open_app",
                    "args" to mapOf("package_name" to "com.example"),
                ),
                stepId = "step_open_app_reset",
                stepTitle = "open app",
            )

            assertEquals(true, result["success"])
            assertEquals(listOf("com.example:false"), backend.launchRequests)
        }
    }

    @Test
    fun `execute open app waits until target package is foreground`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = SOURCE_XML,
            afterXml = AFTER_XML,
            currentPackage = "com.launcher",
            postActionXmls = listOf(SOURCE_XML, AFTER_XML),
            postActionPackages = listOf("com.launcher", "com.example"),
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "open_app",
                    "args" to mapOf("package_name" to "com.example"),
                ),
                stepId = "step_open_app_wait_ready",
                stepTitle = "open app and wait",
            )

            assertEquals(true, result["success"])
            val readyWait = result["open_app_ready_wait"] as? Map<*, *>
                ?: error("missing open_app_ready_wait")
            assertEquals("ready", readyWait["status"])
            assertEquals("com.example", readyWait["expected_package"])
            assertEquals("com.example", readyWait["effective_package"])
            assertEquals(2, readyWait["attempts"])
        }
    }

    @Test
    fun `execute open app ignores sparse target package toast until page content is visible`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = SOURCE_XML,
            afterXml = CLOCK_HOME_XML,
            currentPackage = "com.launcher",
            postActionXmls = listOf(STALE_DIALOG_XML, CLOCK_HOME_XML),
            postActionPackages = listOf(
                "com.google.android.deskclock",
                "com.google.android.deskclock",
            ),
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "open_app",
                    "args" to mapOf("package_name" to "com.google.android.deskclock"),
                ),
                stepId = "step_open_clock_wait_content",
                stepTitle = "open clock and wait for app page",
            )

            assertEquals(true, result["success"])
            val readyWait = result["open_app_ready_wait"] as? Map<*, *>
                ?: error("missing open_app_ready_wait")
            assertEquals("ready", readyWait["status"])
            assertEquals(2, readyWait["attempts"])
            assertEquals(true, readyWait["page_ready"])
            assertEquals("target_page_evidence", readyWait["page_ready_reason"])
            assertEquals(3, readyWait["target_package_node_count"])
        }
    }

    @Test
    fun `execute open app passes through persistent sparse target package overlay`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = SOURCE_XML,
            afterXml = STALE_DIALOG_XML,
            currentPackage = "com.launcher",
            postActionXmls = listOf(STALE_DIALOG_XML),
            postActionPackages = listOf("com.google.android.deskclock"),
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "open_app",
                    "args" to mapOf("package_name" to "com.google.android.deskclock"),
                ),
                stepId = "step_open_clock_sparse_overlay",
                stepTitle = "open clock with sparse overlay",
            )

            assertEquals(true, result["success"])
            val readyWait = result["open_app_ready_wait"] as? Map<*, *>
                ?: error("missing open_app_ready_wait")
            assertEquals("sparse_overlay_passthrough", readyWait["status"])
            assertEquals(false, readyWait["page_ready"])
            assertEquals(true, readyWait["sparse_target_only_overlay"])
        }
    }

    @Test
    fun `execute open app fails when target package never becomes foreground`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = SOURCE_XML,
            afterXml = SOURCE_XML,
            currentPackage = "com.launcher",
            postActionPackages = listOf("com.launcher"),
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val error = try {
                UIStepExecutor.execute(
                    step = mapOf(
                        "executor" to "omniflow",
                        "tool" to "open_app",
                        "args" to mapOf("package_name" to "com.example"),
                    ),
                    stepId = "step_open_app_timeout",
                    stepTitle = "open app timeout",
                )
                null
            } catch (e: UIStepExecutor.ExecutionException) {
                e
            }

            require(error != null) { "expected OPEN_APP_NOT_READY" }
            assertEquals("OPEN_APP_NOT_READY", error.errorCode)
            assertEquals("timeout", error.diagnostics["status"])
            assertEquals("com.example", error.diagnostics["expected_package"])
        }
    }

    @Test
    fun `execute opens app with legacy packageName arg`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = SOURCE_XML,
            afterXml = AFTER_XML,
            currentPackage = "com.example.legacy",
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "open_app",
                    "args" to mapOf("packageName" to "com.example.legacy"),
                ),
                stepId = "step_open_app_legacy_package",
                stepTitle = "open app",
            )

            assertEquals(true, result["success"])
            assertEquals(listOf("com.example.legacy:false"), backend.launchRequests)
        }
    }

    @Test
    fun `execute retries coordinate remap when current xml is temporarily unavailable`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = SCROLL_SOURCE_XML,
            afterXml = SCROLL_SOURCE_XML,
            missingXmlReadsBeforeAction = 2,
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "swipe",
                    "coordinate_hook" to "omniflow",
                    "args" to mapOf(
                        "x1" to 540,
                        "y1" to 1500,
                        "x2" to 540,
                        "y2" to 500,
                    ),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf("page" to SCROLL_SOURCE_XML),
                    ),
                ),
                stepId = "step_scroll_after_launch",
                stepTitle = "swipe after open app",
            )

            assertEquals(true, result["success"])
        }
    }

    @Test
    fun `execute allows root projection only when explicitly requested`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = SOURCE_XML,
            afterXml = SOURCE_XML,
            postActionXmls = listOf(STALE_DIALOG_XML, CLOCK_HOME_XML),
            postActionPackages = listOf("com.google.android.deskclock", "com.google.android.deskclock"),
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "click",
                    "coordinate_hook" to "omniflow",
                    "args" to mapOf(
                        "x" to 504,
                        "y" to 1152,
                        "coordinate_replay_allowed" to true,
                    ),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf("page" to CLOCK_HOME_XML),
                    ),
                ),
                stepId = "step_clock_tab",
                stepTitle = "click stopwatch tab",
            )

            assertEquals(true, result["success"])
        }
    }

    @Test
    fun `execute does not wait for matching postcondition after action`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = SETTINGS_DISPLAY_XML,
            afterXml = SYSTEMUI_SLIDER_XML,
            currentPackage = "com.google.android.gms",
            postActionXmls = listOf(GMS_STALE_XML, SYSTEMUI_SLIDER_XML),
            postActionPackages = listOf("com.google.android.gms", "com.google.android.gms"),
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "click",
                    "args" to mapOf("x" to 360, "y" to 586),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf("page" to SETTINGS_DISPLAY_XML),
                        "dst_ctx" to mapOf(
                            "page" to SYSTEMUI_SLIDER_XML,
                            "package_name" to "com.android.systemui",
                        ),
                    ),
                    "postcondition" to mapOf(
                        "kind" to "recorded_after_page_similarity",
                        "min_score" to 0.1,
                    ),
                ),
                stepId = "step_brightness_dialog",
                stepTitle = "click brightness level",
            )

            assertEquals(true, result["success"])
            assertFalse(result.containsKey("postcondition"))
        }
    }

    @Test
    fun `execute does not reject low recorded after similarity as step checker`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = SETTINGS_XML,
            afterXml = SETTINGS_SECURITY_LIKE_XML,
            currentPackage = "com.android.settings",
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "click",
                    "args" to mapOf(
                        "x" to 360,
                        "y" to 749,
                        "coordinate_replay_allowed" to true,
                    ),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf("page" to SETTINGS_XML),
                        "dst_ctx" to mapOf(
                            "page" to SETTINGS_DISPLAY_XML,
                            "package_name" to "com.android.settings",
                        ),
                    ),
                    "postcondition" to mapOf(
                        "kind" to "recorded_after_page_similarity",
                        "min_score" to 0.12,
                    ),
                ),
                stepId = "step_wrong_settings_page",
                stepTitle = "click Display",
            )

            assertEquals(true, result["success"])
            assertFalse(result.containsKey("postcondition"))
        }
    }

    @Test
    fun `execute ignores low similarity recorded after postcondition without transient marker`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = SETTINGS_XML,
            afterXml = SETTINGS_SEARCH_CURRENT_XML,
            currentPackage = "com.google.android.settings.intelligence",
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "click",
                    "args" to mapOf("x" to 500, "y" to 120),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf("page" to SETTINGS_XML),
                        "dst_ctx" to mapOf(
                            "page" to SETTINGS_SEARCH_RECORDED_XML,
                            "package_name" to "com.google.android.settings.intelligence",
                        ),
                    ),
                    "postcondition" to mapOf(
                        "kind" to "recorded_after_page_similarity",
                        "min_score" to 0.95,
                    ),
                ),
                stepId = "step_search",
                stepTitle = "click search settings",
            )

            assertEquals(true, result["success"])
            assertFalse(result.containsKey("postcondition"))
        }
    }

    @Test
    fun `input text uses target metadata instead of focused node fallback`() = runBlocking {
        val backend = FakeBackend(beforeXml = INPUT_FORM_XML, afterXml = INPUT_FORM_XML)
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "input_text",
                    "args" to mapOf(
                        "text" to "Alice",
                        "target_description" to "First name",
                        "node_resource_id" to "app:id/first_name",
                        "x" to 180,
                        "y" to 232,
                    ),
                ),
                stepId = "step_input",
                stepTitle = "type first name",
            )

            assertEquals(true, result["success"])
            assertEquals(1, backend.inputRequests.size)
            val request = backend.inputRequests.single()
            assertEquals("Alice", request["text"])
            assertEquals("First name", request["targetDescription"])
            assertEquals("app:id/first_name", request["nodeResourceId"])
            assertEquals(180f, request["x"])
            assertEquals(232f, request["y"])
            assertEquals(0, backend.focusedInputCount)
        }
    }

    @Test
    fun `input text retries after observe when target is not ready`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = INPUT_FORM_XML,
            afterXml = INPUT_FORM_XML,
            inputFailuresBeforeSuccess = 1,
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "input_text",
                    "coordinate_hook" to "omniflow",
                    "args" to mapOf(
                        "text" to "Alice",
                        "target_description" to "First name",
                        "node_resource_id" to "app:id/first_name",
                        "x" to 180,
                        "y" to 232,
                    ),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf("page" to INPUT_FORM_XML),
                    ),
                ),
                stepId = "step_input_retry",
                stepTitle = "type first name",
            )

            assertEquals(true, result["success"])
            assertEquals(2, backend.inputRequests.size)
            assertTrue("input retry should refresh observe", backend.currentXmlReadCount >= 2)
            val dispatch = result["action_dispatch"] as? Map<*, *> ?: error("missing action dispatch")
            assertEquals("input_text_observe_retry", dispatch["status"])
            assertEquals(1, dispatch["retry_count"])
        }
    }

    @Test
    fun `action transfer preserves webview relative hotspot`() = runBlocking {
        val backend = FakeBackend(beforeXml = WEBVIEW_CURRENT_XML, afterXml = WEBVIEW_CURRENT_XML)
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "click",
                    "coordinate_hook" to "omniflow",
                    "args" to mapOf("x" to 180, "y" to 220),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf("page" to WEBVIEW_SOURCE_XML),
                    ),
                ),
                stepId = "step_webview",
                stepTitle = "click webview hotspot",
            )

            assertEquals(true, result["success"])
            assertEquals("action_transfer", result["replay_mode"])
            val click = backend.clickPoints.single()
            assertEquals(360f, click.first, 0.01f)
            assertEquals(440f, click.second, 0.01f)
        }
    }

    @Test
    fun `action transfer ignores node resource id shortcut for obfuscated xhs ids`() = runBlocking {
        val backend = FakeBackend(beforeXml = XHS_SEARCH_CURRENT_XML, afterXml = XHS_SEARCH_CURRENT_XML)
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "click",
                    "coordinate_hook" to "omniflow",
                    "args" to mapOf(
                        "target_description" to "搜索",
                        "node_resource_id" to "com.xingin.xhs:id/0_resource_name_obfuscated",
                        "x" to 1165f,
                        "y" to 246f,
                    ),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf("page" to XHS_SEARCH_SOURCE_XML),
                    ),
                ),
                stepId = "step_xhs_search",
                stepTitle = "click xhs search",
            )

            assertEquals(true, result["success"])
            assertEquals("action_transfer", result["replay_mode"])
            val transfer = result["action_transfer"] as? Map<*, *> ?: error("missing action transfer")
            assertFalse(transfer["mode"] == "resource_id")
            assertFalse(transfer["mode"] == "current_node_resource_id")
            val click = backend.clickPoints.single()
            assertTrue("click should stay in the current search affordance", click.first in 480f..1120f)
            assertTrue("click should stay in the current search affordance", click.second in 220f..340f)
        }
    }

    @Test
    fun `target description lookup miss does not replay stale coordinates`() = runBlocking {
        val backend = FakeBackend(beforeXml = TIMER_SETUP_XML, afterXml = TIMER_SETUP_XML)
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val error = try {
                UIStepExecutor.execute(
                    step = mapOf(
                        "executor" to "omniflow",
                        "tool" to "click",
                        "coordinate_hook" to "omniflow",
                        "args" to mapOf(
                            "target_description" to "Timer",
                            "x" to 360f,
                            "y" to 1128.96f,
                        ),
                        "source_context" to mapOf(
                            "src_ctx" to mapOf("page" to STALE_DIALOG_XML),
                        ),
                    ),
                    stepId = "step_timer_tab",
                    stepTitle = "click timer tab",
                )
                null
            } catch (e: UIStepExecutor.ExecutionException) {
                e
            }

            require(error != null) { "expected OOB_FUNCTION_SOURCE_NOT_REACHED" }
            assertEquals("OOB_FUNCTION_SOURCE_NOT_REACHED", error.errorCode)
            assertEquals("no_anchor_match", error.diagnostics["reason"])
            assertTrue("stale coordinates should not be clicked", backend.clickPoints.isEmpty())
        }
    }

    @Test
    fun `coordinate remap miss fails before replaying stale coordinates`() = runBlocking {
        val backend = FakeBackend(beforeXml = EMPTY_PAGE_XML, afterXml = EMPTY_PAGE_XML)
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val error = try {
                UIStepExecutor.execute(
                    step = mapOf(
                        "executor" to "omniflow",
                        "tool" to "click",
                        "coordinate_hook" to "omniflow",
                        "args" to mapOf("x" to 120, "y" to 240),
                        "source_context" to mapOf(
                            "src_ctx" to mapOf("page" to SOURCE_XML),
                        ),
                    ),
                    stepId = "step_no_anchor",
                    stepTitle = "click stale target",
                )
                null
            } catch (e: UIStepExecutor.ExecutionException) {
                e
            }

            require(error != null) { "expected OOB_FUNCTION_SOURCE_NOT_REACHED" }
            assertEquals("OOB_FUNCTION_SOURCE_NOT_REACHED", error.errorCode)
            assertEquals("no_anchor_match", error.diagnostics["reason"])
            assertTrue("stale coordinates should not be clicked", backend.clickPoints.isEmpty())
        }
    }

    @Test
    fun `local anchor transfer rejects global wrong projection`() = runBlocking {
        val backend = FakeBackend(beforeXml = CAMERA_TARGET_WITHOUT_MODE_LIST_XML, afterXml = CAMERA_TARGET_WITHOUT_MODE_LIST_XML)
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val error = try {
                UIStepExecutor.execute(
                    step = mapOf(
                        "executor" to "omniflow",
                        "tool" to "click",
                        "coordinate_hook" to "omniflow",
                        "args" to mapOf(
                            "target_description" to "MODE LIST",
                            "x" to 94.5f,
                            "y" to 48.0f,
                        ),
                        "source_context" to mapOf(
                            "src_ctx" to mapOf("page" to CAMERA_SOURCE_WITH_MODE_LIST_XML),
                        ),
                    ),
                    stepId = "step_camera_mode_list",
                    stepTitle = "click camera mode list",
                )
                null
            } catch (e: UIStepExecutor.ExecutionException) {
                e
            }

            require(error != null) { "expected OOB_FUNCTION_SOURCE_NOT_REACHED" }
            assertEquals("OOB_FUNCTION_SOURCE_NOT_REACHED", error.errorCode)
            assertEquals("no_anchor_match", error.diagnostics["reason"])
            val debug = error.diagnostics["debug"] as? Map<*, *> ?: error("missing debug")
            val gate = debug["gate"] as? Map<*, *> ?: error("missing gate")
            assertEquals("abstain", gate["decision"])
            assertTrue("wrong global projection should not click Options", backend.clickPoints.isEmpty())
        }
    }

    @Test
    fun `semantic target candidates keep phrase but drop short split tokens`() {
        val method = UIStepExecutor::class.java.getDeclaredMethod("semanticTargetTextCandidates", Any::class.java)
        method.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        val targetTexts = method.invoke(UIStepExecutor, "Switch to Video Camera") as List<String>

        assertTrue(targetTexts.contains("switch to video camera"))
        assertTrue(targetTexts.contains("switch"))
        assertTrue(targetTexts.contains("video"))
        assertTrue(targetTexts.contains("camera"))
        assertFalse("short split token should not be used as a semantic target", targetTexts.contains("to"))
    }

    @Test
    fun `omniflow loop dismisses blocking overlay before recorded click`() = runBlocking {
        val backend = FakeBackend(beforeXml = AD_OVERLAY_XML, afterXml = SOURCE_XML)
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "click",
                    "coordinate_hook" to "omniflow",
                    "args" to mapOf("x" to 120, "y" to 240),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf("page" to SOURCE_XML),
                    ),
                ),
                stepId = "step_ad_overlay",
                stepTitle = "click behind ad",
            )

            assertEquals(true, result["success"])
            assertEquals("action_transfer", result["replay_mode"])
            val controlEffects = result["control_effects"] as? List<*> ?: error("missing control effects")
            assertEquals(1, controlEffects.size)
            assertEquals(2, backend.clickPoints.size)
            assertEquals(120f, backend.clickPoints.last().first, 0.01f)
            assertEquals(240f, backend.clickPoints.last().second, 0.01f)
        }
    }

    @Test
    fun `omniflow loop dismisses sparse coachmark overlay covering target point`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = COACHMARK_TARGET_OVERLAY_XML,
            afterXml = CLOCK_STOPWATCH_START_XML,
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "click",
                    "coordinate_hook" to "omniflow",
                    "args" to mapOf(
                        "target_description" to "Start",
                        "node_resource_id" to "com.google.android.deskclock:id/fab",
                        "x" to 360,
                        "y" to 944,
                    ),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf("page" to CLOCK_STOPWATCH_START_XML),
                    ),
                ),
                stepId = "step_start_stopwatch",
                stepTitle = "click start",
            )

            assertEquals(true, result["success"])
            val controlEffects = result["control_effects"] as? List<*> ?: error("missing control effects")
            val effect = controlEffects.single() as Map<*, *>
            assertEquals("dismiss_blocking_overlay", effect["controller"])
            assertEquals("overlay_blocking", effect["condition"])
            assertEquals(2, backend.clickPoints.size)
            assertEquals(360f, backend.clickPoints.first().first, 0.01f)
            assertEquals(970f, backend.clickPoints.first().second, 0.01f)
            assertEquals(360f, backend.clickPoints.last().first, 0.01f)
            assertEquals(944f, backend.clickPoints.last().second, 0.01f)
        }
    }

    @Test
    fun `global checker accepts privacy notice before recorded click`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = PRIVACY_NOTICE_XML,
            afterXml = CLOCK_STOPWATCH_START_XML,
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "click",
                    "coordinate_hook" to "omniflow",
                    "args" to mapOf(
                        "target_description" to "Start",
                        "node_resource_id" to "com.google.android.deskclock:id/fab",
                        "x" to 360,
                        "y" to 944,
                    ),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf("page" to CLOCK_STOPWATCH_START_XML),
                    ),
                ),
                stepId = "step_privacy_notice",
                stepTitle = "click behind privacy notice",
            )

            assertEquals(true, result["success"])
            val controlEffects = result["control_effects"] as? List<*> ?: error("missing control effects")
            val effect = controlEffects.single() as Map<*, *>
            assertEquals("dismiss_blocking_overlay", effect["controller"])
            assertEquals("overlay_blocking", effect["condition"])
            assertEquals(2, backend.clickPoints.size)
            assertEquals(360f, backend.clickPoints.first().first, 0.01f)
            assertEquals(970f, backend.clickPoints.first().second, 0.01f)
            assertEquals(360f, backend.clickPoints.last().first, 0.01f)
            assertEquals(944f, backend.clickPoints.last().second, 0.01f)
        }
    }

    @Test
    fun `global checker dismisses sparse privacy notice without explicit button`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = PRIVACY_NOTICE_NO_BUTTON_XML,
            afterXml = CLOCK_STOPWATCH_START_XML,
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "click",
                    "coordinate_hook" to "omniflow",
                    "args" to mapOf(
                        "target_description" to "Start",
                        "node_resource_id" to "com.google.android.deskclock:id/fab",
                        "x" to 360,
                        "y" to 944,
                    ),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf("page" to CLOCK_STOPWATCH_START_XML),
                    ),
                ),
                stepId = "step_privacy_notice_no_button",
                stepTitle = "click behind privacy notice without button",
            )

            assertEquals(true, result["success"])
            val controlEffects = result["control_effects"] as? List<*> ?: error("missing control effects")
            val effect = controlEffects.single() as Map<*, *>
            assertEquals("dismiss_blocking_overlay", effect["controller"])
            assertEquals("overlay_blocking", effect["condition"])
            assertEquals(2, backend.clickPoints.size)
            val firstClick = backend.clickPoints.first()
            assertTrue("privacy container should be clicked first", firstClick.first in 199f..640f)
            assertTrue("privacy container should be clicked first", firstClick.second in 66f..157f)
        }
    }

    @Test
    fun `global checker skips first run account prompt before recorded click`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = FIRST_RUN_ACCOUNT_PROMPT_XML,
            afterXml = CONTACTS_HOME_XML,
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "click",
                    "args" to mapOf(
                        "target_description" to "Create contact",
                        "node_resource_id" to "com.google.android.contacts:id/floating_action_button",
                        "x" to 616,
                        "y" to 984,
                    ),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf("page" to CONTACTS_HOME_XML),
                    ),
                ),
                stepId = "step_contacts_create_after_first_run_prompt",
                stepTitle = "click create contact behind first-run prompt",
            )

            assertEquals(true, result["success"])
            val controlEffects = result["control_effects"] as? List<*> ?: error("missing control effects")
            val effect = controlEffects.single() as Map<*, *>
            assertEquals("dismiss_blocking_overlay", effect["controller"])
            assertEquals("overlay_blocking", effect["condition"])
            assertEquals(2, backend.clickPoints.size)
            assertEquals(401f, backend.clickPoints.first().first, 0.01f)
            assertEquals(1168f, backend.clickPoints.first().second, 0.01f)
            assertEquals(616f, backend.clickPoints.last().first, 0.01f)
            assertEquals(984f, backend.clickPoints.last().second, 0.01f)
        }
    }

    @Test
    fun `global checker waits for first run prompt to disappear before recorded click`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = FIRST_RUN_ACCOUNT_PROMPT_XML,
            afterXml = CONTACTS_HOME_XML,
            postActionXmls = listOf(
                FIRST_RUN_ACCOUNT_PROMPT_XML,
                FIRST_RUN_ACCOUNT_PROMPT_XML,
                CONTACTS_HOME_XML,
            ),
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "click",
                    "args" to mapOf(
                        "target_description" to "Create contact",
                        "node_resource_id" to "com.google.android.contacts:id/floating_action_button",
                        "x" to 616,
                        "y" to 984,
                    ),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf("page" to CONTACTS_HOME_XML),
                    ),
                ),
                stepId = "step_contacts_create_after_slow_first_run_prompt",
                stepTitle = "click create contact after delayed first-run prompt dismissal",
            )

            assertEquals(true, result["success"])
            val controlEffects = result["control_effects"] as? List<*> ?: error("missing control effects")
            val effect = controlEffects.single() as Map<*, *>
            assertEquals("dismiss_blocking_overlay", effect["controller"])
            assertEquals(0, effect["dismiss_retry_count"])
            assertEquals(false, effect["dismiss_still_blocking_after_retry"])
            assertEquals(2, backend.clickPoints.size)
            assertEquals(401f, backend.clickPoints.first().first, 0.01f)
            assertEquals(1168f, backend.clickPoints.first().second, 0.01f)
            assertEquals(616f, backend.clickPoints.last().first, 0.01f)
            assertEquals(984f, backend.clickPoints.last().second, 0.01f)
        }
    }

    @Test
    fun `global checker advances first run next prompt before recorded click`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = CAMERA_LOCATION_PROMPT_XML,
            afterXml = CAMERA_TARGET_WITHOUT_MODE_LIST_XML,
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "click",
                    "args" to mapOf(
                        "target_description" to "Shutter",
                        "x" to 360,
                        "y" to 1136,
                    ),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf("page" to CAMERA_TARGET_WITHOUT_MODE_LIST_XML),
                    ),
                ),
                stepId = "step_camera_shutter_after_location_prompt",
                stepTitle = "click shutter after camera first-run prompt",
            )

            assertEquals(true, result["success"])
            val controlEffects = result["control_effects"] as? List<*> ?: error("missing control effects")
            val effect = controlEffects.single() as Map<*, *>
            assertEquals("dismiss_blocking_overlay", effect["controller"])
            assertEquals("overlay_blocking", effect["condition"])
            assertEquals(2, backend.clickPoints.size)
            assertEquals(360f, backend.clickPoints.first().first, 0.01f)
            assertEquals(1148f, backend.clickPoints.first().second, 0.01f)
            assertEquals(360f, backend.clickPoints.last().first, 0.01f)
            assertEquals(1136f, backend.clickPoints.last().second, 0.01f)
        }
    }

    @Test
    fun `global checker resolves chained first run and permission prompts before recorded action`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = CAMERA_LOCATION_PROMPT_XML,
            afterXml = CAMERA_TARGET_WITHOUT_MODE_LIST_XML,
            postActionXmls = listOf(
                PERMISSION_ALWAYS_ALLOW_DIALOG_XML,
                PERMISSION_ALWAYS_ALLOW_DIALOG_XML,
                CAMERA_TARGET_WITHOUT_MODE_LIST_XML,
            ),
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "click",
                    "args" to mapOf(
                        "target_description" to "Shutter",
                        "x" to 360,
                        "y" to 1136,
                    ),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf("page" to CAMERA_TARGET_WITHOUT_MODE_LIST_XML),
                    ),
                ),
                stepId = "step_camera_shutter_after_chained_prompts",
                stepTitle = "click shutter after chained first-run prompts",
            )

            assertEquals(true, result["success"])
            val controlEffects = result["control_effects"] as? List<*> ?: error("missing control effects")
            assertEquals(2, controlEffects.size)
            val controllers = controlEffects.map { (it as Map<*, *>)["controller"] }
            assertEquals(listOf("dismiss_blocking_overlay", "auto_grant_permission"), controllers)
            assertEquals(3, backend.clickPoints.size)
            assertEquals(360f, backend.clickPoints.last().first, 0.01f)
            assertEquals(1136f, backend.clickPoints.last().second, 0.01f)
        }
    }

    @Test
    fun `recording stop wait is not released by transient missing recording xml`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = CAMERA_RECORDING_XML,
            afterXml = CAMERA_RECORDING_XML,
            preActionXmls = listOf(
                CAMERA_RECORDING_XML,
                CAMERA_TRANSIENT_NO_RECORDING_TIME_XML,
                CAMERA_TRANSIENT_NO_RECORDING_TIME_XML,
                CAMERA_TRANSIENT_NO_RECORDING_TIME_XML,
            ),
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "click",
                    "args" to mapOf(
                        "target_description" to "Shutter",
                        "x" to 360,
                        "y" to 1136,
                    ),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf("page" to CAMERA_RECORDING_XML),
                    ),
                ),
                stepId = "step_camera_stop_recording",
                stepTitle = "click shutter to stop recording",
            )

            assertEquals(true, result["success"])
            val wait = result["pre_dispatch_wait"] as? Map<*, *> ?: error("missing pre dispatch wait")
            assertEquals("recording_stop_min_duration", wait["reason"])
            assertTrue((wait["waited_ms"] as Number).toLong() >= 2000L)
            assertEquals(1, backend.clickPoints.size)
        }
    }

    @Test
    fun `page guard advances setup apply prompt`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = AUDIO_SETUP_PROMPT_XML,
            afterXml = AUDIO_RECORD_READY_XML,
            currentPackage = "com.dimowner.audiorecorder",
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.runPageGuardOnce()

            assertEquals(true, result["matched"])
            assertEquals(true, result["executed"])
            assertEquals("overlay_blocking", result["condition"])
            assertEquals(1, backend.clickPoints.size)
            assertEquals(526f, backend.clickPoints.single().first, 0.01f)
            assertEquals(1168f, backend.clickPoints.single().second, 0.01f)
        }
    }

    @Test
    fun `global checker can dismiss first run prompt after package recovery`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = EMPTY_PAGE_XML,
            afterXml = CONTACTS_HOME_XML,
            currentPackage = "com.google.android.apps.nexuslauncher",
            postActionXmls = listOf(FIRST_RUN_ACCOUNT_PROMPT_XML, CONTACTS_HOME_XML),
            postActionPackages = listOf("com.google.android.contacts", "com.google.android.contacts"),
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "click",
                    "args" to mapOf(
                        "target_description" to "Create contact",
                        "node_resource_id" to "com.google.android.contacts:id/floating_action_button",
                        "x" to 616,
                        "y" to 984,
                    ),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf(
                            "package_name" to "com.google.android.contacts",
                            "page" to CONTACTS_HOME_XML,
                        ),
                    ),
                ),
                stepId = "step_contacts_create_after_package_recovery",
                stepTitle = "click create contact after package recovery and first-run prompt",
            )

            assertEquals(true, result["success"])
            assertEquals(listOf("com.google.android.contacts:false"), backend.launchRequests)
            val controlEffects = result["control_effects"] as? List<*> ?: error("missing control effects")
            assertEquals(2, controlEffects.size)
            val controllers = controlEffects.map { (it as Map<*, *>)["controller"] }
            assertEquals(listOf("package_mismatch_recovery", "dismiss_blocking_overlay"), controllers)
            assertEquals(2, backend.clickPoints.size)
            assertEquals(401f, backend.clickPoints.first().first, 0.01f)
            assertEquals(1168f, backend.clickPoints.first().second, 0.01f)
            assertEquals(616f, backend.clickPoints.last().first, 0.01f)
            assertEquals(984f, backend.clickPoints.last().second, 0.01f)
        }
    }

    @Test
    fun `global checker skips splash ad before recorded click`() = runBlocking {
        val backend = FakeBackend(beforeXml = SKIP_AD_XML, afterXml = SOURCE_XML)
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "click",
                    "coordinate_hook" to "omniflow",
                    "args" to mapOf("x" to 120, "y" to 240),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf("page" to SOURCE_XML),
                    ),
                ),
                stepId = "step_skip_ad",
                stepTitle = "click behind splash ad",
            )

            assertEquals(true, result["success"])
            val controlEffects = result["control_effects"] as? List<*> ?: error("missing control effects")
            val effect = controlEffects.single() as Map<*, *>
            assertEquals("dismiss_ad_blocking", effect["controller"])
            assertEquals("ad_blocking", effect["condition"])
            assertEquals("dismiss", effect["action"])
            assertEquals(2, backend.clickPoints.size)
            assertEquals(950f, backend.clickPoints.first().first, 0.01f)
            assertEquals(96f, backend.clickPoints.first().second, 0.01f)
            assertEquals(120f, backend.clickPoints.last().first, 0.01f)
            assertEquals(240f, backend.clickPoints.last().second, 0.01f)
        }
    }

    @Test
    fun `page guard skips splash ad without recorded step`() = runBlocking {
        val backend = FakeBackend(beforeXml = SKIP_AD_XML, afterXml = SOURCE_XML)
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.runPageGuardOnce(
                execute = true,
                source = "test",
                checkerBudget = UIStepExecutor.CheckerTriggerBudget(),
            )

            assertEquals("oob.page_guard.v1", result["schema_version"])
            assertEquals(true, result["matched"])
            assertEquals(true, result["executed"])
            assertEquals("ad_blocking", result["condition"])
            assertEquals("dismiss", result["action"])
            assertTrue(result["button_text"].toString().contains("跳过 3"))
            assertEquals(1, backend.clickPoints.size)
            assertEquals(950f, backend.clickPoints.single().first, 0.01f)
            assertEquals(96f, backend.clickPoints.single().second, 0.01f)
        }
    }

    @Test
    fun `shared checker budget suppresses repeated global checker triggers`() = runBlocking {
        val backend = FakeBackend(beforeXml = SKIP_AD_XML, afterXml = SKIP_AD_XML)
        val checkerBudget = UIStepExecutor.CheckerTriggerBudget()
        val step = mapOf(
            "executor" to "omniflow",
            "tool" to "click",
            "args" to mapOf("x" to 120, "y" to 240),
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val first = UIStepExecutor.execute(
                step = step,
                stepId = "step_skip_ad_once",
                stepTitle = "click behind splash ad",
                checkerBudget = checkerBudget,
            )
            val second = UIStepExecutor.execute(
                step = step,
                stepId = "step_skip_ad_budget_exhausted",
                stepTitle = "click behind splash ad again",
                checkerBudget = checkerBudget,
            )

            val firstEffects = first["control_effects"] as? List<*> ?: error("missing control effects")
            val firstEffect = firstEffects.single() as Map<*, *>
            assertEquals("dismiss_ad_blocking", firstEffect["controller"])
            assertEquals(1, firstEffect["trigger_count"])
            assertEquals(1, firstEffect["trigger_limit"])
            assertEquals(0, firstEffect["trigger_remaining"])
            assertFalse(second.containsKey("control_effects"))
            assertEquals(3, backend.clickPoints.size)
            assertEquals(1, backend.clickPoints.count { (x, y) ->
                abs(x - 950f) < 0.01f && abs(y - 96f) < 0.01f
            })
        }
    }

    @Test
    fun `global checker confirms resolver always open before recorded click`() = runBlocking {
        val backend = FakeBackend(beforeXml = RESOLVER_DIALOG_XML, afterXml = SOURCE_XML)
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "click",
                    "coordinate_hook" to "omniflow",
                    "args" to mapOf("x" to 120, "y" to 240),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf("page" to SOURCE_XML),
                    ),
                ),
                stepId = "step_resolver_always",
                stepTitle = "click behind resolver",
            )

            assertEquals(true, result["success"])
            val controlEffects = result["control_effects"] as? List<*> ?: error("missing control effects")
            val effect = controlEffects.single() as Map<*, *>
            assertEquals("confirm_resolver_always", effect["controller"])
            assertEquals("resolver_dialog", effect["condition"])
            assertEquals("confirm_resolver_always", effect["action"])
            assertEquals(2, backend.clickPoints.size)
            assertEquals(810f, backend.clickPoints.first().first, 0.01f)
            assertEquals(1690f, backend.clickPoints.first().second, 0.01f)
            assertEquals(120f, backend.clickPoints.last().first, 0.01f)
            assertEquals(240f, backend.clickPoints.last().second, 0.01f)
        }
    }

    @Test
    fun `global checker confirms vivo resolver always open before recorded click`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = VIVO_RESOLVER_DIALOG_XML,
            afterXml = SOURCE_XML,
            currentPackage = "com.vivo.appfilter",
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "click",
                    "coordinate_hook" to "omniflow",
                    "args" to mapOf("x" to 120, "y" to 240),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf("page" to SOURCE_XML),
                    ),
                ),
                stepId = "step_vivo_resolver_always",
                stepTitle = "click behind vivo resolver",
            )

            assertEquals(true, result["success"])
            val controlEffects = result["control_effects"] as? List<*> ?: error("missing control effects")
            val effect = controlEffects.single() as Map<*, *>
            assertEquals("confirm_resolver_always", effect["controller"])
            assertEquals("resolver_dialog", effect["condition"])
            assertEquals("confirm_resolver_always", effect["action"])
            assertEquals(2, backend.clickPoints.size)
            assertEquals(630f, backend.clickPoints.first().first, 0.01f)
            assertEquals(2124.5f, backend.clickPoints.first().second, 0.01f)
            assertEquals(120f, backend.clickPoints.last().first, 0.01f)
            assertEquals(240f, backend.clickPoints.last().second, 0.01f)
        }
    }

    @Test
    fun `permission dialog recorded click uses action transfer before global checker`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = PERMISSION_ALWAYS_ALLOW_DIALOG_XML,
            afterXml = SOURCE_XML,
            currentPackage = "com.google.android.permissioncontroller",
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "click",
                    "coordinate_hook" to "omniflow",
                    "args" to mapOf("x" to 540, "y" to 1290),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf(
                            "page" to PERMISSION_RECORDED_ALWAYS_ALLOW_DIALOG_XML,
                            "package_name" to "com.example.target",
                        ),
                    ),
                ),
                stepId = "step_permission_always_allow",
                stepTitle = "click always allow",
            )

            assertEquals(true, result["success"])
            assertEquals("action_transfer", result["replay_mode"])
            assertFalse(result.containsKey("control_effects"))
            assertTrue(backend.launchRequests.isEmpty())
            assertEquals(1, backend.clickPoints.size)
            assertEquals(540f, backend.clickPoints.single().first, 0.01f)
            assertEquals(1350f, backend.clickPoints.single().second, 0.01f)
        }
    }

    @Test
    fun `global checker selects resolver app before disabled always open`() = runBlocking {
        val backend = FakeBackend(
            beforeXml = RESOLVER_DIALOG_DISABLED_ALWAYS_XML,
            afterXml = SOURCE_XML,
            postActionXmls = listOf(RESOLVER_DIALOG_XML, SOURCE_XML, SOURCE_XML),
        )
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "click",
                    "coordinate_hook" to "omniflow",
                    "args" to mapOf("x" to 120, "y" to 240),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf("page" to SOURCE_XML),
                    ),
                ),
                stepId = "step_resolver_select_then_always",
                stepTitle = "click behind disabled resolver",
            )

            assertEquals(true, result["success"])
            val controlEffects = result["control_effects"] as? List<*> ?: error("missing control effects")
            val effect = controlEffects.single() as Map<*, *>
            assertEquals("confirm_resolver_always", effect["controller"])
            assertEquals("resolver_dialog", effect["condition"])
            assertEquals("confirm_resolver_always", effect["action"])
            assertEquals("浏览器 text1", effect["preselected_app_text"])
            assertEquals(3, backend.clickPoints.size)
            assertEquals(540f, backend.clickPoints[0].first, 0.01f)
            assertEquals(1020f, backend.clickPoints[0].second, 0.01f)
            assertEquals(810f, backend.clickPoints[1].first, 0.01f)
            assertEquals(1690f, backend.clickPoints[1].second, 0.01f)
            assertEquals(120f, backend.clickPoints[2].first, 0.01f)
            assertEquals(240f, backend.clickPoints[2].second, 0.01f)
        }
    }

    @Test
    fun `global checker does not replace recorded resolver dialog step`() = runBlocking {
        val backend = FakeBackend(beforeXml = RESOLVER_DIALOG_DISABLED_ALWAYS_XML, afterXml = RESOLVER_DIALOG_XML)
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "click",
                    "args" to mapOf("x" to 540, "y" to 1020),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf("page" to RESOLVER_DIALOG_DISABLED_ALWAYS_XML),
                    ),
                ),
                stepId = "step_recorded_resolver_choice",
                stepTitle = "recorded resolver choice",
            )

            assertEquals(true, result["success"])
            assertFalse(result.containsKey("control_effects"))
            assertEquals(1, backend.clickPoints.size)
            assertEquals(540f, backend.clickPoints.single().first, 0.01f)
            assertEquals(1020f, backend.clickPoints.single().second, 0.01f)
        }
    }

    @Test
    fun `global checker does not wrap explicit skip ad action`() = runBlocking {
        val backend = FakeBackend(beforeXml = SKIP_AD_XML, afterXml = SOURCE_XML)
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "click",
                    "args" to mapOf(
                        "x" to 120,
                        "y" to 240,
                        "target_description" to "跳过 3",
                    ),
                ),
                stepId = "step_explicit_skip_ad",
                stepTitle = "skip splash ad",
            )

            assertEquals(true, result["success"])
            assertFalse(result.containsKey("control_effects"))
            assertEquals(1, backend.clickPoints.size)
            assertEquals(120f, backend.clickPoints.single().first, 0.01f)
            assertEquals(240f, backend.clickPoints.single().second, 0.01f)
        }
    }

    @Test
    fun `omniflow loop hides keyboard before covered recorded click`() = runBlocking {
        val backend = FakeBackend(beforeXml = KEYBOARD_XML, afterXml = KEYBOARD_XML)
        OmniflowActionRuntime.useBackendForTesting(backend).use {
            val result = UIStepExecutor.execute(
                step = mapOf(
                    "executor" to "omniflow",
                    "tool" to "click",
                    "args" to mapOf("x" to 540, "y" to 1720),
                ),
                stepId = "step_keyboard",
                stepTitle = "tap covered button",
            )

            assertEquals(true, result["success"])
            assertEquals("direct_replay", result["replay_mode"])
            assertEquals(1, backend.hideKeyboardCount)
            val controlEffects = result["control_effects"] as? List<*> ?: error("missing control effects")
            assertEquals(1, controlEffects.size)
            val click = backend.clickPoints.single()
            assertEquals(540f, click.first, 0.01f)
            assertEquals(1720f, click.second, 0.01f)
        }
    }

    private class FakeBackend(
        private val beforeXml: String,
        private val afterXml: String,
        private val currentPackage: String = "com.example",
        private val currentActivity: String = "ExampleActivity",
        private val missingXmlReadsBeforeAction: Int = 0,
        private val missingXmlReadsAfterAction: Int = 0,
        private val preActionXmls: List<String>? = null,
        private val postActionXmls: List<String>? = null,
        private val postActionPackages: List<String>? = null,
        private val inputFailuresBeforeSuccess: Int = 0,
    ) : OmniflowActionBackend {
        var clicked = false
            private set
        private var currentXmlCallCount = 0
        private var preActionXmlReadCount = 0
        private var actionXmlReadCount = 0
        private var inputFailureCount = 0
        val launchRequests = mutableListOf<String>()
        val clickPoints = mutableListOf<Pair<Float, Float>>()
        val clickNodeResourceIds = mutableListOf<String>()
        val scrollRequests = mutableListOf<Map<String, Any?>>()
        val swipeRequests = mutableListOf<Map<String, Any?>>()
        val inputRequests = mutableListOf<Map<String, Any?>>()
        var focusedInputCount = 0
            private set
        var hideKeyboardCount = 0
            private set
        val currentXmlReadCount: Int
            get() = currentXmlCallCount

        override fun isReady(): Boolean = true

        override suspend fun click(x: Float, y: Float) {
            clicked = true
            clickPoints += x to y
        }

        override suspend fun click(
            x: Float,
            y: Float,
            targetDescription: String,
            nodeResourceId: String,
        ) {
            clickNodeResourceIds += nodeResourceId
            click(x, y)
        }

        override suspend fun longPress(x: Float, y: Float, durationMs: Long) {
            clicked = true
        }

        override suspend fun scroll(
            x: Float,
            y: Float,
            direction: ScrollDirection,
            distance: Float,
            durationMs: Long,
        ) {
            clicked = true
            scrollRequests += mapOf(
                "x" to x,
                "y" to y,
                "direction" to direction,
                "distance" to distance,
                "durationMs" to durationMs,
            )
        }

        override suspend fun swipe(
            startX: Float,
            startY: Float,
            endX: Float,
            endY: Float,
            durationMs: Long,
            targetDescription: String,
        ) {
            clicked = true
            swipeRequests += mapOf(
                "startX" to startX,
                "startY" to startY,
                "endX" to endX,
                "endY" to endY,
                "durationMs" to durationMs,
            )
        }

        override suspend fun inputTextToFocusedNode(text: String) {
            clicked = true
            focusedInputCount += 1
        }

        override suspend fun inputText(
            text: String,
            targetDescription: String,
            x: Float?,
            y: Float?,
            nodeResourceId: String,
        ) {
            clicked = true
            inputRequests += mapOf(
                "text" to text,
                "targetDescription" to targetDescription,
                "x" to x,
                "y" to y,
                "nodeResourceId" to nodeResourceId,
            )
            if (inputFailureCount < inputFailuresBeforeSuccess) {
                inputFailureCount += 1
                throw IllegalStateException("No input text target found: x=$x y=$y")
            }
        }

        override suspend fun launchApplication(packageName: String) {
            launchApplication(packageName, resetTask = false)
        }

        override suspend fun launchApplication(packageName: String, resetTask: Boolean) {
            clicked = true
            launchRequests += "$packageName:$resetTask"
        }

        override suspend fun pressHotKey(key: String) {
            clicked = true
        }

        override suspend fun hideKeyboard() {
            hideKeyboardCount += 1
        }

        override fun currentXml(): String? {
            currentXmlCallCount += 1
            if (!clicked) {
                if (preActionXmlReadCount < missingXmlReadsBeforeAction) {
                    preActionXmlReadCount += 1
                    return null
                }
                preActionXmls?.let { xmls ->
                    if (xmls.isNotEmpty()) {
                        val index = (preActionXmlReadCount - missingXmlReadsBeforeAction)
                            .coerceAtLeast(0)
                            .coerceAtMost(xmls.lastIndex)
                        preActionXmlReadCount += 1
                        return xmls[index]
                    }
                }
                return beforeXml
            }
            if (actionXmlReadCount < missingXmlReadsAfterAction) {
                actionXmlReadCount += 1
                return null
            }
            postActionXmls?.let { xmls ->
                if (xmls.isNotEmpty()) {
                    val index = actionXmlReadCount.coerceAtMost(xmls.lastIndex)
                    actionXmlReadCount += 1
                    return xmls[index]
                }
            }
            return afterXml
        }

        override fun currentPackageName(): String {
            val packages = postActionPackages
            if (clicked && !packages.isNullOrEmpty()) {
                val index = (actionXmlReadCount - 1).coerceAtLeast(0).coerceAtMost(packages.lastIndex)
                return packages[index]
            }
            return currentPackage
        }

        override fun currentActivityName(): String = currentActivity
    }

    companion object {
        private const val SOURCE_XML =
            "<hierarchy bounds=\"[0,0][1080,1920]\"><node bounds=\"[100,200][300,280]\" clickable=\"true\" enabled=\"true\" visible-to-user=\"true\" text=\"Open\" class=\"android.widget.Button\" resource-id=\"app:id/open\"/></hierarchy>"
        private const val INPUT_FORM_XML =
            "<hierarchy bounds=\"[0,0][1080,1920]\"><node bounds=\"[100,200][760,264]\" enabled=\"true\" visible-to-user=\"true\" editable=\"true\" focusable=\"true\" text=\"\" hint-text=\"First name\" class=\"android.widget.EditText\" resource-id=\"app:id/first_name\"/></hierarchy>"
        private const val WEBVIEW_SOURCE_XML =
            "<hierarchy bounds=\"[0,0][1080,1920]\"><node bounds=\"[100,100][500,500]\" clickable=\"true\" enabled=\"true\" visible-to-user=\"true\" class=\"android.webkit.WebView\" resource-id=\"app:id/webview\"/></hierarchy>"
        private const val WEBVIEW_CURRENT_XML =
            "<hierarchy bounds=\"[0,0][1080,1920]\"><node bounds=\"[200,200][1000,1000]\" clickable=\"true\" enabled=\"true\" visible-to-user=\"true\" class=\"android.webkit.WebView\" resource-id=\"app:id/webview\"/></hierarchy>"
        private const val XHS_SEARCH_SOURCE_XML =
            "<hierarchy bounds=\"[0,0][1260,2800]\"><node bounds=\"[1110,210][1230,290]\" clickable=\"true\" enabled=\"true\" visible-to-user=\"true\" text=\"搜索\" class=\"android.widget.TextView\" package=\"com.xingin.xhs\" resource-id=\"com.xingin.xhs:id/0_resource_name_obfuscated\"/></hierarchy>"
        private const val XHS_SEARCH_CURRENT_XML =
            "<hierarchy bounds=\"[0,0][1260,2800]\"><node bounds=\"[521,525][586,589]\" clickable=\"true\" enabled=\"true\" visible-to-user=\"true\" text=\"180\" class=\"android.widget.TextView\" package=\"com.xingin.xhs\" resource-id=\"com.xingin.xhs:id/0_resource_name_obfuscated\"/><node bounds=\"[480,220][1120,340]\" clickable=\"true\" enabled=\"true\" visible-to-user=\"true\" text=\"搜索\" class=\"android.widget.TextView\" package=\"com.xingin.xhs\" resource-id=\"com.xingin.xhs:id/0_resource_name_obfuscated\"/></hierarchy>"
        private const val AD_OVERLAY_XML =
            "<hierarchy bounds=\"[0,0][1080,1920]\"><node bounds=\"[100,200][300,280]\" clickable=\"true\" enabled=\"true\" visible-to-user=\"true\" text=\"Open\" class=\"android.widget.Button\" resource-id=\"app:id/open\"/><node bounds=\"[80,260][1000,1540]\" enabled=\"true\" visible-to-user=\"true\" text=\"Sponsored ad\" class=\"android.app.Dialog\" resource-id=\"ad:id/dialog\"><node bounds=\"[900,300][980,380]\" clickable=\"true\" enabled=\"true\" visible-to-user=\"true\" content-desc=\"Close ad\" class=\"android.widget.ImageButton\" resource-id=\"ad:id/close_ad\"/></node></hierarchy>"
        private const val SKIP_AD_XML =
            "<hierarchy bounds=\"[0,0][1080,1920]\"><node bounds=\"[0,0][1080,1920]\" enabled=\"true\" visible-to-user=\"true\" class=\"android.widget.FrameLayout\" resource-id=\"app:id/splash_container\"><node bounds=\"[870,64][1030,128]\" clickable=\"true\" enabled=\"true\" visible-to-user=\"true\" text=\"跳过 3\" class=\"android.widget.TextView\" resource-id=\"app:id/skip_btn\"/></node></hierarchy>"
        private const val RESOLVER_DIALOG_XML =
            "<hierarchy bounds=\"[0,0][1080,1920]\"><node bounds=\"[40,520][1040,1780]\" enabled=\"true\" visible-to-user=\"true\" class=\"com.android.internal.app.ResolverActivity\" package=\"android\" resource-id=\"android:id/resolver_list\"><node bounds=\"[80,570][1000,660]\" enabled=\"true\" visible-to-user=\"true\" text=\"打开方式\" class=\"android.widget.TextView\" resource-id=\"android:id/title\"/><node bounds=\"[80,720][1000,1320]\" clickable=\"true\" enabled=\"true\" visible-to-user=\"true\" text=\"浏览器\" class=\"android.widget.TextView\" resource-id=\"android:id/text1\"/><node bounds=\"[110,1620][500,1760]\" clickable=\"true\" enabled=\"true\" visible-to-user=\"true\" text=\"仅此一次\" class=\"android.widget.Button\" resource-id=\"android:id/button_once\"/><node bounds=\"[620,1620][1000,1760]\" clickable=\"true\" enabled=\"true\" visible-to-user=\"true\" text=\"始终打开\" class=\"android.widget.Button\" resource-id=\"android:id/button_always\"/></node></hierarchy>"
        private const val VIVO_RESOLVER_DIALOG_XML =
            "<hierarchy bounds=\"[0,0][1260,2800]\"><node bounds=\"[0,0][1260,2800]\" enabled=\"true\" visible-to-user=\"true\" class=\"android.widget.FrameLayout\" package=\"com.vivo.appfilter\"><node bounds=\"[0,0][1260,2800]\" enabled=\"true\" visible-to-user=\"true\" class=\"android.widget.FrameLayout\" package=\"com.vivo.appfilter\"><node bounds=\"[120,1420][1140,2646]\" enabled=\"true\" visible-to-user=\"true\" class=\"android.widget.LinearLayout\" package=\"com.vivo.appfilter\" resource-id=\"com.vivo.appfilter:id/dialog_parent\"><node bounds=\"[196,1512][1064,1606]\" enabled=\"true\" visible-to-user=\"true\" text=\"“小万”想要打开“小红书”\" class=\"android.widget.TextView\" package=\"com.vivo.appfilter\" resource-id=\"com.vivo.appfilter:id/alertTitle\"/><node bounds=\"[315,2044][945,2205]\" clickable=\"true\" enabled=\"true\" visible-to-user=\"true\" content-desc=\"始终打开\" class=\"android.widget.Button\" package=\"com.vivo.appfilter\" resource-id=\"android:id/button1\"/><node bounds=\"[315,2233][945,2394]\" clickable=\"true\" enabled=\"true\" visible-to-user=\"true\" content-desc=\"仅打开一次\" class=\"android.widget.Button\" package=\"com.vivo.appfilter\" resource-id=\"android:id/button3\"/><node bounds=\"[315,2422][945,2583]\" clickable=\"true\" enabled=\"true\" visible-to-user=\"true\" text=\"取消\" class=\"android.widget.Button\" package=\"com.vivo.appfilter\" resource-id=\"android:id/button2\"/></node></node></node></hierarchy>"
        private const val RESOLVER_DIALOG_DISABLED_ALWAYS_XML =
            "<hierarchy bounds=\"[0,0][1080,1920]\"><node bounds=\"[40,520][1040,1780]\" enabled=\"true\" visible-to-user=\"true\" class=\"com.android.internal.app.ResolverActivity\" package=\"android\" resource-id=\"android:id/resolver_list\"><node bounds=\"[80,570][1000,660]\" enabled=\"true\" visible-to-user=\"true\" text=\"打开方式\" class=\"android.widget.TextView\" resource-id=\"android:id/title\"/><node bounds=\"[80,720][1000,1320]\" clickable=\"true\" enabled=\"true\" visible-to-user=\"true\" text=\"浏览器\" class=\"android.widget.TextView\" resource-id=\"android:id/text1\"/><node bounds=\"[110,1620][500,1760]\" clickable=\"true\" enabled=\"false\" visible-to-user=\"true\" text=\"仅此一次\" class=\"android.widget.Button\" resource-id=\"android:id/button_once\"/><node bounds=\"[620,1620][1000,1760]\" clickable=\"true\" enabled=\"false\" visible-to-user=\"true\" text=\"始终打开\" class=\"android.widget.Button\" resource-id=\"android:id/button_always\"/></node></hierarchy>"
        private const val PERMISSION_ALWAYS_ALLOW_DIALOG_XML =
            "<hierarchy bounds=\"[0,0][1080,1920]\"><node bounds=\"[0,0][1080,1920]\" enabled=\"true\" visible-to-user=\"true\" class=\"android.widget.FrameLayout\" package=\"com.google.android.permissioncontroller\" resource-id=\"com.google.android.permissioncontroller:id/grant_dialog\"><node bounds=\"[120,520][960,640]\" enabled=\"true\" visible-to-user=\"true\" text=\"要允许小万获取此设备的位置信息吗？\" class=\"android.widget.TextView\" resource-id=\"com.google.android.permissioncontroller:id/permission_message\"/><node bounds=\"[80,960][1000,1100]\" clickable=\"true\" enabled=\"true\" visible-to-user=\"true\" class=\"android.widget.LinearLayout\" package=\"com.google.android.permissioncontroller\" resource-id=\"com.google.android.permissioncontroller:id/permission_allow_one_time_button\"><node bounds=\"[160,1000][480,1050]\" enabled=\"true\" visible-to-user=\"true\" text=\"仅此一次\" class=\"android.widget.TextView\" resource-id=\"android:id/text1\"/></node><node bounds=\"[80,1120][1000,1260]\" clickable=\"true\" enabled=\"true\" visible-to-user=\"true\" class=\"android.widget.LinearLayout\" package=\"com.google.android.permissioncontroller\" resource-id=\"com.google.android.permissioncontroller:id/permission_allow_foreground_only_button\"><node bounds=\"[160,1160][520,1210]\" enabled=\"true\" visible-to-user=\"true\" text=\"仅在使用中允许\" class=\"android.widget.TextView\" resource-id=\"android:id/text1\"/></node><node bounds=\"[80,1280][1000,1420]\" clickable=\"true\" enabled=\"true\" visible-to-user=\"true\" class=\"android.widget.LinearLayout\" package=\"com.google.android.permissioncontroller\" resource-id=\"android:id/button1\"><node bounds=\"[160,1320][420,1370]\" enabled=\"true\" visible-to-user=\"true\" text=\"始终允许\" class=\"android.widget.TextView\" resource-id=\"android:id/text1\"/></node><node bounds=\"[80,1440][1000,1580]\" clickable=\"true\" enabled=\"true\" visible-to-user=\"true\" class=\"android.widget.LinearLayout\" package=\"com.google.android.permissioncontroller\" resource-id=\"com.google.android.permissioncontroller:id/permission_deny_button\"><node bounds=\"[160,1480][360,1530]\" enabled=\"true\" visible-to-user=\"true\" text=\"不允许\" class=\"android.widget.TextView\" resource-id=\"android:id/text1\"/></node></node></hierarchy>"
        private const val PERMISSION_RECORDED_ALWAYS_ALLOW_DIALOG_XML =
            "<hierarchy bounds=\"[0,0][1080,1920]\"><node bounds=\"[0,0][1080,1920]\" enabled=\"true\" visible-to-user=\"true\" class=\"android.widget.FrameLayout\" package=\"com.google.android.permissioncontroller\" resource-id=\"com.google.android.permissioncontroller:id/grant_dialog\"><node bounds=\"[120,520][960,640]\" enabled=\"true\" visible-to-user=\"true\" text=\"要允许小万获取此设备的位置信息吗？\" class=\"android.widget.TextView\" resource-id=\"com.google.android.permissioncontroller:id/permission_message\"/><node bounds=\"[80,900][1000,1040]\" clickable=\"true\" enabled=\"true\" visible-to-user=\"true\" class=\"android.widget.LinearLayout\" package=\"com.google.android.permissioncontroller\" resource-id=\"com.google.android.permissioncontroller:id/permission_allow_one_time_button\"><node bounds=\"[160,940][480,990]\" enabled=\"true\" visible-to-user=\"true\" text=\"仅此一次\" class=\"android.widget.TextView\" resource-id=\"android:id/text1\"/></node><node bounds=\"[80,1060][1000,1200]\" clickable=\"true\" enabled=\"true\" visible-to-user=\"true\" class=\"android.widget.LinearLayout\" package=\"com.google.android.permissioncontroller\" resource-id=\"com.google.android.permissioncontroller:id/permission_allow_foreground_only_button\"><node bounds=\"[160,1100][520,1150]\" enabled=\"true\" visible-to-user=\"true\" text=\"仅在使用中允许\" class=\"android.widget.TextView\" resource-id=\"android:id/text1\"/></node><node bounds=\"[80,1220][1000,1360]\" clickable=\"true\" enabled=\"true\" visible-to-user=\"true\" class=\"android.widget.LinearLayout\" package=\"com.google.android.permissioncontroller\" resource-id=\"android:id/button1\"><node bounds=\"[160,1260][420,1310]\" enabled=\"true\" visible-to-user=\"true\" text=\"始终允许\" class=\"android.widget.TextView\" resource-id=\"android:id/text1\"/></node><node bounds=\"[80,1380][1000,1520]\" clickable=\"true\" enabled=\"true\" visible-to-user=\"true\" class=\"android.widget.LinearLayout\" package=\"com.google.android.permissioncontroller\" resource-id=\"com.google.android.permissioncontroller:id/permission_deny_button\"><node bounds=\"[160,1420][360,1470]\" enabled=\"true\" visible-to-user=\"true\" text=\"不允许\" class=\"android.widget.TextView\" resource-id=\"android:id/text1\"/></node></node></hierarchy>"
        private const val HI_UPGRADE_DIALOG_XML =
            "<hierarchy bounds=\"[0,0][1080,1920]\"><node bounds=\"[80,420][1000,1360]\" enabled=\"true\" visible-to-user=\"true\" class=\"android.app.Dialog\" package=\"com.example.hi\" resource-id=\"com.example.hi:id/update_dialog\"><node bounds=\"[160,520][920,620]\" enabled=\"true\" visible-to-user=\"true\" text=\"Hi 发现新版本\" class=\"android.widget.TextView\" resource-id=\"com.example.hi:id/title\"/><node bounds=\"[160,680][920,980]\" enabled=\"true\" visible-to-user=\"true\" text=\"升级后体验更好\" class=\"android.widget.TextView\" resource-id=\"com.example.hi:id/message\"/><node bounds=\"[680,1180][940,1300]\" clickable=\"true\" enabled=\"true\" visible-to-user=\"true\" text=\"立即升级\" class=\"android.widget.Button\" resource-id=\"com.example.hi:id/update_now\"/><node bounds=\"[360,1180][660,1300]\" clickable=\"true\" enabled=\"true\" visible-to-user=\"true\" text=\"以后再说\" class=\"android.widget.Button\" resource-id=\"com.example.hi:id/later\"/></node></hierarchy>"
        private const val KEYBOARD_XML =
            "<hierarchy bounds=\"[0,0][1080,1920]\"><node bounds=\"[0,0][1080,1280]\" enabled=\"true\" visible-to-user=\"true\" class=\"android.widget.FrameLayout\" resource-id=\"app:id/content\"/><node bounds=\"[0,1320][1080,1920]\" enabled=\"true\" visible-to-user=\"true\" class=\"android.inputmethodservice.KeyboardView\" package=\"com.google.android.inputmethod.latin\" resource-id=\"com.google.android.inputmethod.latin:id/keyboard_view\"/></hierarchy>"
        private const val SCROLL_SOURCE_XML =
            "<hierarchy bounds=\"[0,0][1080,1920]\"><node bounds=\"[0,0][1080,1920]\" scrollable=\"true\" enabled=\"true\" visible-to-user=\"true\" class=\"androidx.recyclerview.widget.RecyclerView\" resource-id=\"app:id/list\"><node bounds=\"[100,300][900,380]\" enabled=\"true\" visible-to-user=\"true\" text=\"Network\" class=\"android.widget.TextView\" resource-id=\"android:id/title\"/></node></hierarchy>"
        private const val AFTER_XML =
            "<hierarchy bounds=\"[0,0][1080,1920]\"><node bounds=\"[100,200][300,280]\" enabled=\"true\" visible-to-user=\"true\" text=\"Done\" class=\"android.widget.TextView\" resource-id=\"app:id/done\"/></hierarchy>"
        private const val EMPTY_PAGE_XML =
            "<hierarchy bounds=\"[0,0][1080,1920]\"><node bounds=\"[0,0][1080,1920]\" enabled=\"true\" visible-to-user=\"true\" class=\"android.widget.FrameLayout\" resource-id=\"app:id/empty\"/></hierarchy>"
        private const val SETTINGS_XML =
            "<hierarchy bounds=\"[0,0][1080,1920]\"><node bounds=\"[30,40][1050,140]\" clickable=\"true\" enabled=\"true\" visible-to-user=\"true\" text=\"Search settings\" class=\"android.view.ViewGroup\" resource-id=\"com.android.settings:id/search_action_bar\"/></hierarchy>"
        private const val STALE_DIALOG_XML =
            "<hierarchy bounds=\"[0,0][720,1280]\"><node bounds=\"[199,66][640,157]\" clickable=\"true\" enabled=\"true\" visible-to-user=\"true\" class=\"android.view.ViewGroup\"><node bounds=\"[223,90][598,123]\" enabled=\"true\" visible-to-user=\"true\" text=\"Privacy\" class=\"android.widget.TextView\" resource-id=\"com.google.android.deskclock:id/body\"/></node></hierarchy>"
        private const val CLOCK_HOME_XML =
            "<hierarchy bounds=\"[0,0][720,1280]\"><node bounds=\"[0,0][720,1280]\" enabled=\"true\" visible-to-user=\"true\" class=\"android.widget.FrameLayout\"><node bounds=\"[0,176][720,1072]\" enabled=\"true\" focusable=\"true\" class=\"android.view.ViewGroup\" resource-id=\"com.google.android.deskclock:id/desk_clock_pager\"/><node bounds=\"[432,1072][576,1232]\" enabled=\"true\" clickable=\"true\" focusable=\"true\" content-desc=\"Stopwatch\" class=\"android.widget.FrameLayout\" resource-id=\"com.google.android.deskclock:id/tab_menu_stopwatch\"><node bounds=\"[433,1168][575,1209]\" enabled=\"true\" visible-to-user=\"true\" text=\"Stopwatch\" class=\"android.widget.TextView\" resource-id=\"com.google.android.deskclock:id/navigation_bar_item_small_label_view\"/></node></node></hierarchy>"
        private const val CLOCK_STOPWATCH_START_XML =
            "<hierarchy bounds=\"[0,0][720,1280]\"><node bounds=\"[0,0][720,1280]\" enabled=\"true\" visible-to-user=\"true\" class=\"android.widget.FrameLayout\"><node bounds=\"[48,84][259,140]\" enabled=\"true\" visible-to-user=\"true\" text=\"Stopwatch\" class=\"android.widget.TextView\" resource-id=\"com.google.android.deskclock:id/action_bar_title\"/><node bounds=\"[264,848][456,1040]\" clickable=\"true\" enabled=\"true\" visible-to-user=\"true\" content-desc=\"Start\" class=\"android.widget.Button\" resource-id=\"com.google.android.deskclock:id/fab\"/></node></hierarchy>"
        private const val CAMERA_SOURCE_WITH_MODE_LIST_XML =
            "<hierarchy bounds=\"[0,0][720,1280]\"><node id=\"0\" class=\"android.widget.FrameLayout\" enabled=\"true\" bounds=\"[0,0][720,1280]\"><node id=\"14\" content-desc=\"Options\" class=\"android.widget.LinearLayout\" resource-id=\"com.android.camera2:id/mode_options_toggle\" enabled=\"true\" clickable=\"true\" focusable=\"true\" bounds=\"[599,871][696,968]\"/><node id=\"18\" content-desc=\"Shutter\" class=\"android.widget.ImageView\" resource-id=\"com.android.camera2:id/shutter_button\" enabled=\"true\" clickable=\"true\" focusable=\"true\" bounds=\"[0,992][720,1232]\"/><node id=\"20\" text=\"MODE LIST\" content-desc=\"Toggle mode list\" class=\"android.widget.Button\" resource-id=\"com.android.camera2:id/accessibility_mode_toggle_button\" enabled=\"true\" clickable=\"true\" focusable=\"true\" bounds=\"[0,0][189,96]\"/><node id=\"21\" text=\"FILMSTRIP\" content-desc=\"Toggle filmstrip\" class=\"android.widget.Button\" resource-id=\"com.android.camera2:id/accessibility_filmstrip_toggle_button\" enabled=\"true\" clickable=\"true\" focusable=\"true\" bounds=\"[189,0][377,96]\"/><node id=\"22\" text=\"Z-\" content-desc=\"Zoom out\" class=\"android.widget.Button\" resource-id=\"com.android.camera2:id/accessibility_zoom_minus_button\" clickable=\"true\" focusable=\"true\" bounds=\"[377,0][553,96]\"/><node id=\"23\" text=\"Z+\" content-desc=\"Zoom in\" class=\"android.widget.Button\" resource-id=\"com.android.camera2:id/accessibility_zoom_plus_button\" enabled=\"true\" clickable=\"true\" focusable=\"true\" bounds=\"[553,0][720,96]\"/></node></hierarchy>"
        private const val CAMERA_TARGET_WITHOUT_MODE_LIST_XML =
            "<hierarchy bounds=\"[0,0][720,1280]\"><node id=\"0\" class=\"android.widget.FrameLayout\" enabled=\"true\" bounds=\"[0,0][720,1280]\"><node id=\"14\" content-desc=\"Options\" class=\"android.widget.LinearLayout\" resource-id=\"com.android.camera2:id/mode_options_toggle\" enabled=\"true\" clickable=\"true\" focusable=\"true\" bounds=\"[586,919][683,1016]\"/><node id=\"18\" content-desc=\"Shutter\" class=\"android.widget.ImageView\" resource-id=\"com.android.camera2:id/shutter_button\" enabled=\"true\" clickable=\"true\" focusable=\"true\" bounds=\"[14,1040][707,1232]\"/></node></hierarchy>"
        private const val CAMERA_RECORDING_XML =
            "<hierarchy bounds=\"[0,0][720,1280]\"><node id=\"0\" class=\"android.widget.FrameLayout\" enabled=\"true\" bounds=\"[0,0][720,1280]\"><node id=\"12\" text=\"00:00\" class=\"android.widget.TextView\" resource-id=\"com.android.camera2:id/recording_time\" enabled=\"true\" bounds=\"[46,46][221,101]\"/><node id=\"16\" content-desc=\"Shutter\" class=\"android.widget.ImageView\" resource-id=\"com.android.camera2:id/shutter_button\" enabled=\"true\" clickable=\"true\" focusable=\"true\" bounds=\"[14,1040][707,1232]\"/></node></hierarchy>"
        private const val CAMERA_TRANSIENT_NO_RECORDING_TIME_XML =
            "<hierarchy bounds=\"[0,0][720,1280]\"><node id=\"0\" class=\"android.widget.FrameLayout\" enabled=\"true\" bounds=\"[0,0][720,1280]\"><node id=\"16\" content-desc=\"Shutter\" class=\"android.widget.ImageView\" resource-id=\"com.android.camera2:id/shutter_button\" enabled=\"true\" clickable=\"true\" focusable=\"true\" bounds=\"[14,1040][707,1232]\"/></node></hierarchy>"
        private const val CAMERA_LOCATION_PROMPT_XML =
            "<hierarchy bounds=\"[0,0][720,1280]\"><node id=\"0\" class=\"android.widget.FrameLayout\" enabled=\"true\" visible-to-user=\"true\" bounds=\"[0,0][720,1280]\"><node id=\"5\" text=\"Remember photo locations?\" class=\"android.widget.TextView\" enabled=\"true\" visible-to-user=\"true\" bounds=\"[60,56][659,121]\"/><node id=\"8\" text=\"Tag your photos and videos with the locations where they're taken.\" class=\"android.widget.CheckBox\" resource-id=\"com.android.camera2:id/check_box\" enabled=\"true\" clickable=\"true\" focusable=\"true\" checkable=\"true\" checked=\"true\" visible-to-user=\"true\" bounds=\"[56,498][664,583]\"/><node id=\"9\" text=\"NEXT\" class=\"android.widget.Button\" resource-id=\"com.android.camera2:id/confirm_button\" enabled=\"true\" clickable=\"true\" focusable=\"true\" visible-to-user=\"true\" bounds=\"[216,1100][504,1196]\"/></node></hierarchy>"
        private const val AUDIO_SETUP_PROMPT_XML =
            "<hierarchy bounds=\"[0,0][720,1280]\"><node id=\"0\" class=\"android.widget.FrameLayout\" enabled=\"true\" visible-to-user=\"true\" bounds=\"[0,0][720,1280]\"><node id=\"7\" text=\"Setup\" class=\"android.widget.TextView\" enabled=\"true\" visible-to-user=\"true\" bounds=\"[112,71][233,136]\"/><node id=\"14\" text=\"Recording format:\" class=\"android.widget.TextView\" resource-id=\"com.dimowner.audiorecorder:id/setting_title\" enabled=\"true\" visible-to-user=\"true\" bounds=\"[32,401][624,444]\"/><node id=\"20\" text=\"Apply\" class=\"android.widget.Button\" resource-id=\"com.dimowner.audiorecorder:id/btn_apply\" enabled=\"true\" clickable=\"true\" focusable=\"true\" visible-to-user=\"true\" bounds=\"[438,1120][614,1216]\"/></node></hierarchy>"
        private const val AUDIO_RECORD_READY_XML =
            "<hierarchy bounds=\"[0,0][720,1280]\"><node id=\"0\" class=\"android.widget.FrameLayout\" enabled=\"true\" visible-to-user=\"true\" bounds=\"[0,0][720,1280]\"><node id=\"6\" text=\"Audio Recorder\" class=\"android.widget.TextView\" enabled=\"true\" visible-to-user=\"true\" bounds=\"[176,72][548,137]\"/><node id=\"13\" content-desc=\"Recording: %s\" class=\"android.widget.ImageButton\" resource-id=\"com.dimowner.audiorecorder:id/btn_record\" enabled=\"true\" clickable=\"true\" focusable=\"true\" visible-to-user=\"true\" bounds=\"[276,1032][444,1200]\"/></node></hierarchy>"
        private const val COACHMARK_TARGET_OVERLAY_XML =
            "<hierarchy bounds=\"[0,0][720,1280]\"><node bounds=\"[64,900][656,1040]\" clickable=\"true\" enabled=\"true\" visible-to-user=\"true\" text=\"Set a consistent bedtime for better sleep\" class=\"android.widget.TextView\" resource-id=\"com.google.android.deskclock:id/coachmark\"/></hierarchy>"
        private const val PRIVACY_NOTICE_XML =
            "<hierarchy bounds=\"[0,0][720,1280]\"><node bounds=\"[80,420][640,1040]\" enabled=\"true\" visible-to-user=\"true\" class=\"android.app.Dialog\" package=\"com.google.android.deskclock\" resource-id=\"com.google.android.deskclock:id/privacy_dialog\"><node bounds=\"[120,520][600,760]\" enabled=\"true\" visible-to-user=\"true\" text=\"You can find the privacy policy here.\" class=\"android.widget.TextView\" resource-id=\"com.google.android.deskclock:id/message\"/><node bounds=\"[240,900][480,1040]\" clickable=\"true\" enabled=\"true\" visible-to-user=\"true\" text=\"OK\" class=\"android.widget.Button\" resource-id=\"android:id/button1\"/></node></hierarchy>"
        private const val PRIVACY_NOTICE_NO_BUTTON_XML =
            "<hierarchy><node bounds=\"[199,66][640,157]\" enabled=\"true\" visible-to-user=\"true\" class=\"android.widget.FrameLayout\"><node bounds=\"[199,66][640,157]\" clickable=\"true\" focusable=\"true\" enabled=\"true\" visible-to-user=\"true\" class=\"android.view.ViewGroup\"><node bounds=\"[223,90][598,123]\" enabled=\"true\" visible-to-user=\"true\" text=\"You can find the privacy policy here\" class=\"android.widget.TextView\" resource-id=\"com.google.android.deskclock:id/body\"/></node></node></hierarchy>"
        private const val FIRST_RUN_ACCOUNT_PROMPT_XML =
            "<hierarchy bounds=\"[0,0][720,1280]\"><node id=\"0\" class=\"android.widget.FrameLayout\" enabled=\"true\" visible-to-user=\"true\" bounds=\"[0,0][720,1280]\"><node id=\"9\" text=\"Contacts\" class=\"android.widget.TextView\" resource-id=\"com.google.android.contacts:id/product_name\" enabled=\"true\" visible-to-user=\"true\" bounds=\"[281,88][439,136]\"/><node id=\"10\" text=\"Back up &amp; organize your contacts with Google\" class=\"android.widget.TextView\" resource-id=\"android:id/text1\" enabled=\"true\" visible-to-user=\"true\" bounds=\"[48,200][672,311]\"/><node id=\"11\" text=\"Contacts works best with a Google Account. You'll be able to safely back up and sync your contacts to your devices.\" class=\"android.widget.TextView\" resource-id=\"android:id/text2\" enabled=\"true\" visible-to-user=\"true\" bounds=\"[48,343][672,453]\"/><node id=\"13\" text=\"Skip\" class=\"android.widget.Button\" resource-id=\"android:id/button2\" enabled=\"true\" clickable=\"true\" focusable=\"true\" visible-to-user=\"true\" bounds=\"[313,1120][489,1216]\"/><node id=\"14\" text=\"Sign in\" class=\"android.widget.Button\" resource-id=\"android:id/button1\" enabled=\"true\" clickable=\"true\" focusable=\"true\" visible-to-user=\"true\" bounds=\"[489,1120][672,1216]\"/></node></hierarchy>"
        private const val CONTACTS_HOME_XML =
            "<hierarchy bounds=\"[0,0][720,1280]\"><node class=\"android.widget.FrameLayout\" enabled=\"true\" visible-to-user=\"true\" bounds=\"[0,0][720,1280]\"><node text=\"Contacts\" class=\"android.widget.TextView\" resource-id=\"com.google.android.contacts:id/contacts_list_header\" enabled=\"true\" visible-to-user=\"true\" bounds=\"[48,88][240,136]\"/><node text=\"Create contact\" content-desc=\"Create contact\" class=\"android.widget.ImageButton\" resource-id=\"com.google.android.contacts:id/floating_action_button\" enabled=\"true\" clickable=\"true\" focusable=\"true\" visible-to-user=\"true\" bounds=\"[560,928][672,1040]\"/></node></hierarchy>"
        private const val TIMER_SETUP_XML =
            "<hierarchy bounds=\"[0,0][720,1280]\"><node bounds=\"[0,0][720,1280]\" enabled=\"true\" visible-to-user=\"true\" class=\"android.widget.FrameLayout\"><node bounds=\"[48,84][163,140]\" enabled=\"true\" visible-to-user=\"true\" text=\"Timer\" class=\"android.widget.TextView\" resource-id=\"com.google.android.deskclock:id/action_bar_title\"/><node bounds=\"[184,340][296,452]\" clickable=\"true\" enabled=\"true\" visible-to-user=\"true\" text=\"1\" class=\"android.widget.Button\" resource-id=\"com.google.android.deskclock:id/timer_setup_digit_1\"/><node bounds=\"[304,460][416,572]\" clickable=\"true\" enabled=\"true\" visible-to-user=\"true\" text=\"5\" class=\"android.widget.Button\" resource-id=\"com.google.android.deskclock:id/timer_setup_digit_5\"/></node></hierarchy>"
        private const val SETTINGS_APPS_XML =
            "<hierarchy bounds=\"[0,0][720,1280]\"><node bounds=\"[0,0][720,1232]\" enabled=\"true\" visible-to-user=\"true\" text=\"Apps\" class=\"android.widget.TextView\" resource-id=\"com.android.settings:id/content_parent\"/><node bounds=\"[48,594][273,648]\" enabled=\"true\" visible-to-user=\"true\" text=\"Default apps\" class=\"android.widget.TextView\" resource-id=\"android:id/title\"/></hierarchy>"
        private const val PERMISSION_CONTROLLER_XML =
            "<hierarchy bounds=\"[0,0][720,1280]\"><node bounds=\"[0,0][720,1232]\" enabled=\"true\" visible-to-user=\"true\" text=\"Default apps\" class=\"android.widget.TextView\" resource-id=\"com.android.permissioncontroller:id/content_parent\"/><node bounds=\"[144,438][368,492]\" enabled=\"true\" visible-to-user=\"true\" text=\"Browser app\" class=\"android.widget.TextView\" resource-id=\"android:id/title\"/></hierarchy>"
        private const val SETTINGS_SEARCH_RECORDED_XML =
            "<hierarchy bounds=\"[0,0][1080,1920]\"><node bounds=\"[20,40][1060,140]\" enabled=\"true\" visible-to-user=\"true\" text=\"Search settings\" class=\"android.widget.EditText\" resource-id=\"com.google.android.settings.intelligence:id/search_action_bar\"/></hierarchy>"
        private const val SETTINGS_SEARCH_CURRENT_XML =
            "<hierarchy bounds=\"[0,0][1080,1920]\"><node bounds=\"[40,220][1040,320]\" enabled=\"true\" visible-to-user=\"true\" text=\"No recent searches\" class=\"android.widget.TextView\" resource-id=\"com.google.android.settings.intelligence:id/empty\"/></hierarchy>"
        private const val SETTINGS_DISPLAY_XML =
            "<hierarchy bounds=\"[0,0][720,1280]\"><node bounds=\"[0,508][720,664]\" clickable=\"true\" enabled=\"true\" visible-to-user=\"true\" class=\"android.widget.LinearLayout\"><node bounds=\"[48,540][330,594]\" enabled=\"true\" visible-to-user=\"true\" text=\"Brightness level\" class=\"android.widget.TextView\" resource-id=\"android:id/title\"/><node bounds=\"[48,594][85,632]\" enabled=\"true\" visible-to-user=\"true\" text=\"0%\" class=\"android.widget.TextView\" resource-id=\"android:id/summary\"/></node></hierarchy>"
        private const val SYSTEMUI_SLIDER_XML =
            "<hierarchy bounds=\"[0,48][720,176]\"><node bounds=\"[32,64][688,160]\" enabled=\"true\" visible-to-user=\"true\" text=\"Display brightness\" class=\"android.widget.SeekBar\" resource-id=\"com.android.systemui:id/slider\"/></hierarchy>"
        private const val GMS_STALE_XML =
            "<hierarchy bounds=\"[0,0][720,1280]\"><node bounds=\"[48,540][688,594]\" enabled=\"true\" visible-to-user=\"true\" text=\"Google Play services\" class=\"android.widget.TextView\" resource-id=\"com.google.android.gms:id/title\"/></hierarchy>"
        private const val SETTINGS_SECURITY_LIKE_XML =
            "<hierarchy bounds=\"[0,0][720,1280]\"><node bounds=\"[0,406][720,1232]\" enabled=\"true\" visible-to-user=\"true\" class=\"androidx.recyclerview.widget.RecyclerView\" resource-id=\"com.android.settings:id/recycler_view\"><node bounds=\"[48,454][688,492]\" enabled=\"true\" visible-to-user=\"true\" text=\"Security status\" class=\"android.widget.TextView\" resource-id=\"android:id/title\"/><node bounds=\"[144,540][496,594]\" enabled=\"true\" visible-to-user=\"true\" text=\"Google Play Protect\" class=\"android.widget.TextView\" resource-id=\"android:id/title\"/><node bounds=\"[144,712][562,750]\" enabled=\"true\" visible-to-user=\"true\" text=\"No Google account on this device\" class=\"android.widget.TextView\" resource-id=\"android:id/summary\"/></node></hierarchy>"
    }
}
