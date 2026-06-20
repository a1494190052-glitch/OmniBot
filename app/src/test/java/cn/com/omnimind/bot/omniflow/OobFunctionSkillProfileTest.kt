package cn.com.omnimind.bot.omniflow

import android.content.Context
import android.content.ContextWrapper
import cn.com.omnimind.baselib.i18n.PromptLocale
import cn.com.omnimind.bot.runlog.RunLogReplayPolicy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OobFunctionSkillProfileTest {
    @Test
    fun `saved Functions are not exposed as dynamic model tools`() {
        val context = MinimalContext()

        assertTrue(
            OobFunctionSkillProfile.dynamicFunctionToolDefinitions(
                context = context,
                locale = PromptLocale.EN_US,
            ).isEmpty()
        )
        assertTrue(
            OobFunctionSkillProfile.dynamicFunctionToolDefinitions(
                context = context,
                locale = PromptLocale.EN_US,
                forceInclude = true,
            ).isEmpty()
        )
        assertTrue(OobFunctionSkillProfile.runtimeToolDefinitions(PromptLocale.EN_US).isEmpty())
    }

    @Test
    fun `function management profile exposes agent managed function tools`() {
        val definitions = OobFunctionSkillProfile.staticToolDefinitions(PromptLocale.EN_US)
        val toolNames = definitions
            .mapNotNull { definition ->
                (definition["function"] as? JsonObject)
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.contentOrNull
            }
            .toSet()

        assertEquals(OobFunctionToolNames.profileTools, toolNames)
        assertTrue(toolNames.contains(OobFunctionToolNames.FUNCTION_RUN))
        assertFalse(toolNames.contains(RunLogReplayPolicy.TOOL_CALL_TOOL))
        assertFalse(toolNames.any { it.startsWith("omniflow.call") })

        val runTool = definitions
            .mapNotNull { it["function"] as? JsonObject }
            .first { it["name"]?.jsonPrimitive?.contentOrNull == OobFunctionToolNames.FUNCTION_RUN }
        val runToolDescription = runTool["description"]
            ?.jsonPrimitive
            ?.contentOrNull
            .orEmpty()
        assertTrue(runToolDescription.contains("Previous local replay result"))
        assertTrue(runToolDescription.contains("runtime_resolve_goal"))
    }

    @Test
    fun `function management profile is owned by omniflow skill`() {
        assertEquals("function_management", OobFunctionSkillProfile.PROFILE)
        assertEquals("omniflow", OobFunctionSkillProfile.SKILL_ID)
    }

    @Test
    fun `prompt candidate context includes reusable function summaries without direct calls`() {
        val prompt = OobFunctionSkillProfile.buildPromptCandidateContext(
            candidates = listOf(
                mapOf(
                    "function_id" to "oob_fn_order_lunch",
                    "name" to "点外卖",
                    "description" to "打开外卖应用并复用常用午餐下单流程",
                    "score" to 0.91,
                    "metadata" to mapOf(
                        "agent_reuse" to mapOf(
                            "reuse_when" to "用户要点午餐或外卖",
                            "success_signal" to "进入订单确认页"
                        )
                    ),
                    "input_schema" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "restaurant" to mapOf(
                                "type" to "string",
                                "x_oob_bindings" to listOf("steps[0].args.text")
                            ),
                            "dish" to mapOf(
                                "type" to "string",
                                "x_oob_bindings" to listOf("steps[1].args.text")
                            )
                        )
                    )
                )
            ),
            locale = PromptLocale.ZH_CN
        )

        assertTrue(prompt.contains("候选复用指令"))
        assertTrue(prompt.contains("`oob_fn_order_lunch`"))
        assertTrue(prompt.contains("点外卖"))
        assertTrue(prompt.contains("restaurant"))
        assertTrue(prompt.contains("dish"))
        assertTrue(prompt.contains("不要尝试调用 function_recall"))
        assertTrue(prompt.contains("继续走 vlm_task"))
        assertTrue(prompt.contains("oob_function_run"))
    }

    private class MinimalContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }
}
