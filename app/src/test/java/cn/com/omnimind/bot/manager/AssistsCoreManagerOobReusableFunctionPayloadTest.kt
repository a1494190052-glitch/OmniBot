package cn.com.omnimind.bot.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistsCoreManagerOobReusableFunctionPayloadTest {
    @Test
    fun `function run payload normalization keeps compact failure fields on errors`() {
        val payload = normalizeOobFunctionRunPayloadForChannel(
            mapOf(
                "success" to false,
                "function_id" to "missing_function",
                "error_code" to "OOB_FUNCTION_NOT_FOUND",
                "error_message" to "OmniFlow function not found: missing_function",
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
                "runner" to "oob_function_direct_runner",
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
    fun `local reusable function payload preserves accessibility action error`() {
        val payload = buildOobReusableFunctionLocalPayload(
            functionId = "click_requires_accessibility",
            localSuccess = false,
            runPayload = mapOf(
                "runner" to "oob_function_direct_runner",
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
}
