package cn.com.omnimind.bot.omniflow

import cn.com.omnimind.assists.task.vlmserver.State
import cn.com.omnimind.assists.task.vlmserver.StateDisplay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniFlowReplayAdapterTest {
    @Test
    fun `direct control act sends canonical action with supplied state`() = runBlocking {
        var request = emptyMap<String, Any?>()
        val adapter = OmniFlowReplayAdapter(
            controlCall = { payload, _ ->
                request = payload
                successResponse("click")
            },
        )

        val suppliedState = State(
            stateId = "live",
            xml = "<hierarchy />",
            packageName = "demo.app",
            activityName = "demo.MainActivity",
            display = StateDisplay(1080, 1920),
        )
        val result = adapter.controlAct(
            action = "click",
            args = mapOf("x" to 250, "y" to 750),
            state = suppliedState,
        )

        assertTrue(result.success)
        assertEquals(
            mapOf(
                "action" to mapOf(
                    "tool" to "click",
                    "args" to mapOf("x" to 250, "y" to 750),
                ),
                "state" to mapOf(
                    "state_id" to "live",
                    "xml" to "<hierarchy />",
                    "package_name" to "demo.app",
                    "activity_name" to "demo.MainActivity",
                    "display" to mapOf("width" to 1080, "height" to 1920),
                ),
            ),
            request,
        )
        assertEquals("before", result.beforeState?.stateId)
        assertEquals("<before />", result.beforeState?.xml)
        assertEquals("after", result.afterState?.stateId)
        assertEquals("<after />", result.afterState?.xml)
    }

    @Test
    fun `function control act sends source state and checker rules`() = runBlocking {
        var request = emptyMap<String, Any?>()
        val rule = mapOf("id" to "wrong_package")
        val adapter = OmniFlowReplayAdapter(
            controlCall = { payload, _ ->
                request = payload
                successResponse("click")
            },
        )

        val result = adapter.controlAct(
            functionId = "search_product",
            sourceStateId = "state-source",
            action = "click",
            args = mapOf("x" to 500, "y" to 400),
            rules = listOf(rule),
        )

        assertTrue(result.success)
        assertEquals("search_product", request["function_id"])
        assertEquals("state-source", request["source_state_id"])
        assertEquals(listOf(rule), request["checker_rules"])
        assertFalse(request.containsKey("state"))
        assertFalse(request.containsKey("source_context"))
    }

    @Test
    fun `control act failure is fail closed`() = runBlocking {
        val adapter = OmniFlowReplayAdapter(
            controlCall = { _, _ -> error("runtime unavailable") },
        )

        val result = adapter.controlAct(
            action = "click",
            args = mapOf("x" to 250, "y" to 750),
        )

        assertFalse(result.success)
        assertEquals("OOB_OMNIFLOW_CONTROL_FAILED", result.diagnostics["local_action_error_code"])
    }

    @Test
    fun `blocked transfer attaches source and target screenshots`() = runBlocking {
        val adapter = OmniFlowReplayAdapter(
            controlCall = { _, _ ->
                mapOf(
                    "success" to false,
                    "error" to "omnitransfer_target_identity_not_unique",
                    "transfer" to mapOf(
                        "source" to mapOf("text" to "Search"),
                        "target" to mapOf("display" to mapOf("width" to 400, "height" to 600)),
                        "candidates" to listOf(mapOf("rank" to 1)),
                    ),
                )
            },
            loadState = {
                mapOf(
                    "state_id" to it,
                    "screenshot_path" to "/source.jpg",
                    "display" to mapOf("width" to 100, "height" to 100),
                )
            },
            captureTarget = {
                mapOf(
                    "screenshot_path" to "/target.jpg",
                    "display" to mapOf("width" to 400, "height" to 600),
                )
            },
        )

        val result = adapter.controlAct(
            functionId = "search_product",
            sourceStateId = "state-source",
            action = "click",
            args = mapOf("x" to 500, "y" to 400),
        )

        assertFalse(result.success)
        val diagnostics = result.diagnostics.getValue("transfer")
        assertTrue(diagnostics.contains("/source.jpg"))
        assertTrue(diagnostics.contains("/target.jpg"))
    }

    private fun successResponse(tool: String): Map<String, Any?> = mapOf(
        "success" to true,
        "action" to mapOf("tool" to tool, "args" to emptyMap<String, Any?>()),
        "result" to mapOf(
            "success" to true,
            "extra" to mapOf("message" to "ok"),
        ),
        "before_state" to mapOf(
            "state_id" to "before",
            "xml" to "<before />",
            "package_name" to "demo.before",
            "display" to mapOf("width" to 100, "height" to 200),
        ),
        "after_state" to mapOf(
            "state_id" to "after",
            "xml" to "<after />",
            "package_name" to "demo.after",
            "display" to mapOf("width" to 100, "height" to 200),
        ),
    )
}
