package cn.com.omnimind.bot.omniflow

import cn.com.omnimind.assists.task.vlmserver.OperationResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniFlowReplayAdapterTest {
    @Test
    fun `prepare act returns a pure recovery decision`() = runBlocking {
        var request = emptyMap<String, Any?>()
        val adapter = OmniFlowReplayAdapter(
            observe = { mapOf("xml" to TARGET_XML, "package_name" to "com.example") },
            enabled = { true },
            bridgeCall = { operation, payload ->
                assertEquals("prepare_action", operation)
                request = payload
                mapOf(
                    "success" to true,
                    "decision" to "recover",
                    "coordinate_space" to "absolute_pixels",
                    "function_id" to "fn_restore_package",
                    "action" to mapOf(
                        "tool" to "open_app",
                        "args" to mapOf("package_name" to "com.example"),
                    ),
                )
            },
        )

        val result = adapter.prepareAct(
            functionId = "example_function",
            step = minimalStep(),
            sourceRunId = "run-1",
            sourceActionIndex = 2,
            action = "click",
            args = mapOf("x" to 20, "y" to 30),
            rules = listOf(packageRule()),
        )

        assertEquals(ActionDecisionKind.RECOVER, result.kind)
        assertEquals("open_app", result.action)
        assertEquals(mapOf("package_name" to "com.example"), result.args)
        assertEquals("fn_restore_package", result.functionId)
        assertEquals("relative_0_1000", request["coordinate_space"])
        assertEquals("run-1", request["source_run_id"])
        assertEquals(2, request["source_action_index"])
    }

    @Test
    fun `control act blocks when Python is unavailable`() = runBlocking {
        var called = false
        val adapter = OmniFlowReplayAdapter(
            observe = { emptyMap() },
            enabled = { false },
            bridgeCall = { _, _ ->
                called = true
                emptyMap()
            },
        )

        val result = adapter.prepareAct(
            functionId = "example_function",
            step = step(),
            action = "click",
            args = mapOf("x" to 20, "y" to 30),
            rules = emptyList(),
        )

        assertEquals(ActionDecisionKind.BLOCK, result.kind)
        assertEquals("python_not_ready", result.reason)
        assertFalse(called)
    }

    @Test
    fun `control act preserves stored recovery function id`() = runBlocking {
        var checkerRule = emptyMap<String, Any?>()
        val rule = mapOf(
            "id" to "dismiss_permission",
            "condition" to mapOf(
                "type" to "permission_dialog",
                "text_any" to listOf("Don’t allow"),
            ),
            "recovery_function_id" to "fn_dismiss_permission",
        )
        val adapter = OmniFlowReplayAdapter(
            observe = { mapOf("xml" to TARGET_XML, "package_name" to "com.example") },
            enabled = { true },
            bridgeCall = { _, payload ->
                checkerRule = (payload["checker_rules"] as List<*>)
                    .first() as Map<String, Any?>
                mapOf(
                    "success" to true,
                    "decision" to "ready",
                    "action" to mapOf("tool" to "click", "args" to mapOf("x" to 20, "y" to 30)),
                )
            },
        )

        val result = adapter.prepareAct(
            functionId = "example_function",
            step = step(),
            action = "click",
            args = mapOf("x" to 20, "y" to 30),
            rules = listOf(rule),
        )

        assertEquals(ActionDecisionKind.READY, result.kind)
        assertEquals("fn_dismiss_permission", checkerRule["recovery_function_id"])
        assertFalse(checkerRule.containsKey("action"))
        assertEquals(
            mapOf(
                "type" to "permission_dialog",
                "text_any" to listOf("Don’t allow"),
            ),
            checkerRule["condition"],
        )
    }

    @Test
    fun `action pipeline dispatches recovery and main action through one seam`() = runBlocking {
        val dispatched = mutableListOf<Pair<String, String>>()
        var prepareCount = 0
        val pipeline = ActionPipeline(
            dispatch = { action, _, source, _, _ ->
                dispatched += action to source
                OperationResult(success = true, message = "ok")
            },
            settle = {},
        )

        val result = pipeline.execute(
            action = "click",
            args = mapOf("x" to 20, "y" to 30),
            prepare = { action, args ->
                prepareCount += 1
                if (prepareCount == 1) {
                    ActionDecision(
                        kind = ActionDecisionKind.RECOVER,
                        action = "open_app",
                        args = mapOf("package_name" to "com.example"),
                        functionId = "fn_restore_package",
                    )
                } else {
                    ActionDecision(
                        kind = ActionDecisionKind.READY,
                        action = action,
                        args = args,
                    )
                }
            },
        )

        assertTrue(result.success)
        assertEquals(2, prepareCount)
        assertEquals(
            listOf(
                "open_app" to "omniflow_checker_recovery",
                "click" to "function_replay",
            ),
            dispatched,
        )
    }

    private fun packageRule(): Map<String, Any?> = mapOf(
        "id" to "restore_package",
        "condition" to "package_mismatch",
        "action" to "open_app",
        "params" to mapOf("package_name" to "com.example"),
    )

    private fun step(): Map<String, Any?> = mapOf(
        "tool" to "click",
        "args" to mapOf("x" to 20, "y" to 30),
        "source_context" to mapOf(
            "src_ctx" to mapOf("page" to SOURCE_XML, "package_name" to "com.example")
        ),
    )

    private fun minimalStep(): Map<String, Any?> = mapOf(
        "tool" to "click",
        "args" to mapOf(
            "x" to 500,
            "y" to 500,
            "source_context" to mapOf(
                "coordinate_space" to "relative_0_1000",
                "action_index" to 2,
            ),
        ),
    )

    companion object {
        private const val SOURCE_XML =
            "<hierarchy><node package=\"com.example\" bounds=\"[0,0][100,100]\" /></hierarchy>"
        private const val TARGET_XML =
            "<hierarchy><node package=\"com.example\" bounds=\"[0,0][400,600]\" /></hierarchy>"
    }
}
