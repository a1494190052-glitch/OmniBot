package cn.com.omnimind.bot.function

import cn.com.omnimind.baselib.i18n.PromptLocale
import cn.com.omnimind.baselib.runlog.OobActionSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FunctionApiTest {
    @Test
    fun `function profile exposes function management tools only`() {
        val definitions = FunctionApi.staticToolDefinitions(PromptLocale.EN_US)
        val toolNames = definitions
            .mapNotNull { definition ->
                (definition["function"] as? JsonObject)
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.contentOrNull
            }
            .toSet()

        assertEquals(FunctionApi.profileTools, toolNames)
        assertEquals(FunctionApi.profileTools, FunctionApi.toolNames)
        assertFalse(toolNames.contains(OobActionSchema.TOOL_CALL_TOOL))
        assertFalse(toolNames.any { it.startsWith("omniflow.call") })
    }

    @Test
    fun `function profile accepts legacy spellings`() {
        assertEquals("function", FunctionApi.PROFILE)
        assertEquals("omniflow", FunctionApi.LEGACY_OMNIFLOW_PROFILE)
        assertEquals("function_management", FunctionApi.LEGACY_PROFILE)
        assertEquals("function", FunctionApi.SKILL_ID)
        assertEquals("function", FunctionApi.canonicalProfile(" function-management "))
        assertEquals("function", FunctionApi.canonicalProfile("function_management"))
        assertEquals("function", FunctionApi.canonicalProfile("omniflow"))
        assertTrue(FunctionApi.isProfile("function_management"))
    }

    @Test
    fun `prompt candidate context includes reusable function summaries without direct calls`() {
        val prompt = FunctionApi.buildPromptCandidateContext(
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
        assertTrue(prompt.contains("普通手机自动化仍走 vlm_task"))
        assertFalse(prompt.contains("oob_" + "function_run"))
    }

    @Test
    fun `function callable summary exposes stable callable contract`() {
        val summary = FunctionSchema.callableSummary(
            mapOf(
                "function_id" to "xhs_search",
                "name" to "小红书搜索",
                "description" to "在小红书搜索指定关键词",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "query" to mapOf(
                            "type" to "string",
                            "description" to "搜索关键词",
                            "x_oob_bindings" to listOf("$.execution.steps[1].args.text")
                        ),
                        "x" to mapOf(
                            "type" to "number",
                            "x_oob_bindings" to listOf("$.execution.steps[0].args.x")
                        )
                    ),
                    "required" to listOf("query", "x")
                ),
                "execution" to mapOf(
                    "steps" to listOf(
                        mapOf("tool" to "click", "args" to mapOf("x" to 1, "y" to 2)),
                        mapOf("tool" to "input_text", "args" to mapOf("text" to "\${query}"))
                    )
                )
            )
        )

        assertEquals("xhs_search", summary["function_id"])
        assertEquals("小红书搜索", summary["name"])
        assertEquals("在小红书搜索指定关键词", summary["description"])
        assertEquals(listOf("query"), summary["argument_names"])
        assertEquals(2, summary["step_count"])

        val parameters = summary["parameters"] as Map<*, *>
        val properties = parameters["properties"] as Map<*, *>
        assertTrue(properties.containsKey("query"))
        assertFalse(properties.containsKey("x"))
        assertEquals(listOf("query"), parameters["required"])
    }

    @Test
    fun `template referenced parameter is exposed as callable argument`() {
        val spec = mapOf(
            "function_id" to "xhs_search_template",
            "name" to "小红书搜索关键词",
            "description" to "在小红书应用中执行关键词搜索",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "search_query" to mapOf(
                        "type" to "string",
                        "description" to "要搜索的关键词",
                    ),
                ),
                "required" to listOf("search_query"),
                "additionalProperties" to false,
            ),
            "execution" to mapOf(
                "steps" to listOf(
                    mapOf(
                        "tool" to "input_text",
                        "args" to mapOf(
                            "target_description" to "搜索输入框",
                            "text" to "{{search_query}}",
                        ),
                    ),
                ),
            ),
        )

        val summary = FunctionSchema.callableSummary(spec)
        val parameters = summary["parameters"] as Map<*, *>
        val properties = parameters["properties"] as Map<*, *>
        val searchQuery = properties["search_query"] as Map<*, *>

        assertEquals(listOf("search_query"), summary["argument_names"])
        assertEquals(listOf("search_query"), parameters["required"])
        assertEquals(
            listOf("$.execution.steps[0].args.text"),
            searchQuery["x_oob_bindings"],
        )

        val materialized = FunctionSchema.materialize(
            spec,
            mapOf("search_query" to "狗狗"),
        )
        val steps = ((materialized["execution"] as Map<*, *>)["steps"] as List<*>)
            .map { it as Map<*, *> }
        val args = steps.single()["args"] as Map<*, *>
        assertEquals("狗狗", args["text"])
        assertTrue(FunctionArgumentBindingValidator.validate(materialized).success)
    }
}
