package cn.com.omnimind.assists.task.vlmserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class VLMPreferredCallToolPolicyTest {
    @Test
    fun `extracts no-arg preferred call tool function id`() {
        val context = UIContext(
            overallTask = "Open Bluetooth",
            stepSkillGuidance = """
                preferred_call_tool: {"name":"call_tool","arguments":{"function_id":"open_bluetooth","arguments":{}}}
            """.trimIndent(),
            pageDiagnostics = mapOf("omniflow_call_tool_function_count" to "1"),
        )

        assertEquals(
            "open_bluetooth",
            VLMPreferredCallToolPolicy.preferredNoArgFunctionId(context),
        )
    }

    @Test
    fun `extracts preferred call tool with trailing score metadata`() {
        val context = UIContext(
            overallTask = "Open Bluetooth",
            stepSkillGuidance = """
                decision=recall
                preferred_call_tool: {"name":"call_tool","arguments":{"function_id":"debug_visible","arguments":{}}} score=0.994 text_score=0.98
                [Page Explanation]
            """.trimIndent(),
            pageDiagnostics = mapOf("omniflow_call_tool_function_count" to "2"),
        )

        assertEquals(
            "debug_visible",
            VLMPreferredCallToolPolicy.preferredNoArgFunctionId(context),
        )
    }

    @Test
    fun `does not extract parameterized preferred call tool`() {
        val context = UIContext(
            overallTask = "Send message",
            stepSkillGuidance = "preferred_call_tool: call_tool(function_id=\"send_message\", arguments=<fill required fields from the user request>)",
            pageDiagnostics = mapOf("omniflow_call_tool_function_count" to "1"),
        )

        assertNull(VLMPreferredCallToolPolicy.preferredNoArgFunctionId(context))
    }

    @Test
    fun `rewrites primitive action to call tool`() {
        val step = UIStep(
            observation = "Settings",
            thought = "Click Bluetooth row",
            action = ClickAction(targetDescription = "Connected devices", x = 10f, y = 20f),
        )

        val rewritten = VLMPreferredCallToolPolicy.rewriteStep(step, "open_bluetooth")

        val action = rewritten.action as FunctionRunAction
        assertEquals("open_bluetooth", action.functionId)
        assertEquals("call_tool: open_bluetooth", rewritten.summary)
        assertTrue(rewritten.thought.contains("preferred_call_tool matched"))
    }

    @Test
    fun `does not rewrite after preferred function already succeeded`() {
        val context = UIContext(
            overallTask = "Open Bluetooth",
            trace = listOf(
                UIStep(
                    observation = "Settings",
                    thought = "Use recalled function",
                    action = FunctionRunAction(functionId = "open_bluetooth"),
                    result = "复用指令执行完成",
                    actionResultData = buildJsonObject {
                        put("success", JsonPrimitive(true))
                        put("function_id", JsonPrimitive("open_bluetooth"))
                    },
                )
            ),
        )
        val nextAction = ClickAction(targetDescription = "Bluetooth", x = 540f, y = 780f)

        assertFalse(
            VLMPreferredCallToolPolicy.shouldRewrite(
                action = nextAction,
                context = context,
                functionId = "open_bluetooth",
            )
        )
    }
}
