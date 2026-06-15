package cn.com.omnimind.bot.manager

import cn.com.omnimind.bot.runlog.RunLogReplayPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistsCoreManagerOobReusableFunctionPayloadTest {
    @Test
    fun `toolkit run payload normalization keeps runtime resolve channel fields on errors`() {
        val payload = normalizeOobToolkitFunctionRunPayloadForChannel(
            mapOf(
                "success" to false,
                "function_id" to "missing_function",
                "error_code" to "OOB_FUNCTION_NOT_FOUND",
                "error_message" to "OmniFlow function not found: missing_function",
                "runtime_resolve_session_id" to "runtime-resolve-session",
                "runtime_resolve_attempt" to 1,
                "runtime_resolve_unavailable_reason" to "runtime-resolve-disabled",
                "runtime_resolve_context" to mapOf("step" to 2),
            )
        )

        assertEquals(false, payload["success"])
        assertEquals("missing_function", payload["function_id"])
        assertEquals(emptyList<Map<String, Any?>>(), payload["step_results"])
        assertEquals(0, payload["step_count"])
        assertEquals(0, payload["success_step_count"])
        assertEquals("oob_mixed_runner", payload["runner"])
        assertEquals(false, payload["model_used"])
        assertEquals("OOB_FUNCTION_NOT_FOUND", payload["error_code"])
        assertEquals("runtime-resolve-session", payload["runtime_resolve_session_id"])
        assertEquals(1, payload["runtime_resolve_attempt"])
        assertEquals("runtime-resolve-disabled", payload["runtime_resolve_unavailable_reason"])
        assertEquals(mapOf("step" to 2), payload["runtime_resolve_context"])
    }

    @Test
    fun `local reusable function payload reports completed local status and timing internally`() {
        val timing = mapOf(
            "duration_ms" to 21L,
            "phase_ms" to mapOf("rank_functions_ms" to 3L)
        )
        val stepResults = listOf<Map<*, *>>(
            mapOf("tool" to "click", "success" to true),
            mapOf("tool" to "finished", "success" to true),
        )

        val payload = buildOobReusableFunctionLocalPayload(
            functionId = "open_settings",
            localSuccess = true,
            runPayload = mapOf(
                "runner" to "oob_omniflow_replay",
                "step_count" to 2,
                "success_step_count" to 2,
                "model_used" to false,
                "timing" to timing
            ),
            stepResults = stepResults,
            argumentCount = 1
        )

        assertEquals(true, payload["success"])
        assertEquals(OOB_REUSABLE_EXECUTION_STATUS_COMPLETED_LOCAL, payload["execution_status"])
        val terminalState = payload["terminal_state"] as Map<*, *>
        assertEquals(OOB_REUSABLE_EXECUTION_STATUS_COMPLETED_LOCAL, terminalState["status"])
        assertEquals(2, terminalState["step_count"])
        assertEquals(2, terminalState["success_step_count"])
        assertEquals(false, terminalState["model_used"])
        assertEquals(timing, terminalState["timing"])
        val context = payload["context"] as Map<*, *>
        assertEquals(1, context["argument_count"])
        assertEquals(2, context["step_count"])
        assertEquals(timing, context["timing"])
    }

    @Test
    fun `runtime resolve payload keeps local prefix and resolve step counts in context`() {
        val timing = mapOf(
            "duration_ms" to 34L,
            "phase_ms" to mapOf("rank_functions_ms" to 5L)
        )
        val stepResults = listOf<Map<*, *>>(
            mapOf("tool" to "click", "success" to true),
            mapOf(
                "executor" to "agent",
                "tool" to "call_tool",
                "success" to false
            ),
        )

        val payload = buildOobReusableFunctionRuntimeResolvePayload(
            functionId = "open_settings_then_repair",
            resolveId = "resolve-step-1",
            runPayload = mapOf(
                "runner" to "oob_mixed_runner",
                "model_required" to true,
                "timing" to timing
            ),
            stepResults = stepResults,
            completedStepCount = 1,
            runtimeResolveStepCount = 1,
            argumentCount = 0
        )

        assertEquals(false, payload["success"])
        assertEquals(
            OOB_REUSABLE_EXECUTION_STATUS_RUNTIME_RESOLVE_REQUIRED,
            payload["execution_status"]
        )
        assertEquals("OOB_RUNTIME_RESOLVE_UNAVAILABLE", payload["error_code"])
        val terminalState = payload["terminal_state"] as Map<*, *>
        assertEquals("resolve-step-1", terminalState["resolve_id"])
        assertEquals(true, terminalState["runtime_resolve_required"])
        assertEquals(false, terminalState["runtime_resolve_available"])
        assertEquals(1, terminalState["local_steps_completed"])
        assertEquals(1, terminalState["resolve_calls"])
        assertFalse(terminalState.containsKey("runtime_resolve_steps"))
        assertEquals(2, terminalState["step_count"])
        assertEquals(1, terminalState["success_step_count"])
        assertEquals(true, terminalState["model_required"])
        assertEquals(timing, terminalState["timing"])
        assertFalse(terminalState.containsKey("repair_id"))
        assertFalse(terminalState.containsKey("fallback_session_id"))
        assertFalse(terminalState.containsKey("fallback_attempt"))
        assertFalse(terminalState.containsKey("online_repair_required"))
        assertFalse(terminalState.containsKey("online_repair_available"))
        assertFalse(terminalState.containsKey("online_repair_steps"))
        val context = payload["context"] as Map<*, *>
        assertEquals("resolve-step-1", context["resolve_id"])
        assertEquals(true, context["runtime_resolve_required"])
        assertEquals(false, context["runtime_resolve_available"])
        assertEquals(1, context["local_steps_completed"])
        assertEquals(1, context["resolve_calls"])
        assertFalse(context.containsKey("runtime_resolve_steps"))
        assertEquals(2, context["step_count"])
        assertEquals(1, context["success_step_count"])
        assertEquals(timing, context["timing"])
        assertFalse(context.containsKey("repair_id"))
        assertFalse(context.containsKey("fallback_session_id"))
        assertFalse(context.containsKey("fallback_attempt"))
        assertFalse(context.containsKey("online_repair_required"))
        assertFalse(context.containsKey("online_repair_available"))
        assertFalse(context.containsKey("online_repair_steps"))
    }

    @Test
    fun `local reusable function payload preserves accessibility preflight error`() {
        val payload = buildOobReusableFunctionLocalPayload(
            functionId = "click_requires_accessibility",
            localSuccess = false,
            runPayload = mapOf(
                "runner" to "oob_omniflow_replay",
                "step_count" to 1,
                "success_step_count" to 0,
                "error_code" to "OOB_ACCESSIBILITY_REQUIRED",
                "error_message" to "请先开启无障碍权限，复用指令才能执行点击、滑动和输入。",
            ),
            stepResults = listOf(
                mapOf(
                    "tool" to "click",
                    "success" to false,
                    "error_code" to "OOB_ACCESSIBILITY_REQUIRED",
                )
            ),
            argumentCount = 0
        )

        assertEquals(false, payload["success"])
        assertEquals(OOB_REUSABLE_EXECUTION_STATUS_FAILED, payload["execution_status"])
        assertEquals("OOB_ACCESSIBILITY_REQUIRED", payload["error_code"])
        assertTrue(payload["error_message"].toString().contains("无障碍"))
    }

    @Test
    fun `runtime resolve step detection supports current replay markers`() {
        assertTrue(
            isOobReusableFunctionRuntimeResolveStep(
                mapOf("runtime_resolve_required" to true)
            )
        )
        assertTrue(
            isOobReusableFunctionRuntimeResolveStep(
                mapOf("executor" to "agent", "success" to false)
            )
        )
        assertTrue(
            isOobReusableFunctionRuntimeResolveStep(
                mapOf("vlm_step_required" to true)
            )
        )
        assertTrue(
            isOobReusableFunctionRuntimeResolveStep(
                mapOf("error_code" to "OOB_VLM_CONTINUATION_REQUIRED")
            )
        )
        assertFalse(
            isOobReusableFunctionRuntimeResolveStep(
                mapOf("model_required" to true)
            )
        )
        assertFalse(
            isOobReusableFunctionRuntimeResolveStep(
                mapOf("executor" to RunLogReplayPolicy.EXECUTOR_OMNIFLOW)
            )
        )
    }
}
