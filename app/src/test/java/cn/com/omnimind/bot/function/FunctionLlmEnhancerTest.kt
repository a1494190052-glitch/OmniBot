package cn.com.omnimind.bot.function

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FunctionLlmEnhancerTest {
    private val enhancer = FunctionLlmEnhancer { error("not used") }

    @Test
    fun `enhancement only changes labels and preserves executable actions`() {
        val original = functionSpec()

        val result = enhancer.applyProposal(
            original,
            mapOf(
                "function_id" to "attempted_override",
                "name" to "搜索外卖",
                "description" to "在美团中搜索指定商品；结果列表出现后结束。",
                "actions" to emptyList<Any?>(),
                "step_annotations" to listOf(
                    mapOf(
                        "index" to 0,
                        "title" to "打开搜索入口",
                        "description" to "进入外卖搜索页",
                        "action_purpose" to "准备输入搜索词",
                        "args" to mapOf("x" to 1, "y" to 2),
                    ),
                    mapOf("index" to 99, "title" to "越界动作"),
                ),
            ),
        )

        assertEquals("fn_meituan_search", result.updated["function_id"])
        assertEquals("搜索外卖", result.updated["name"])
        assertEquals("enhanced", result.status)

        val beforeActions = FunctionJson.listArg(original["steps"])
        val afterActions = FunctionJson.listArg(result.updated["steps"])
        assertEquals(
            FunctionJson.mapArg(FunctionJson.mapArg(beforeActions[0])["action"])["args"],
            FunctionJson.mapArg(FunctionJson.mapArg(afterActions[0])["action"])["args"],
        )
        assertEquals(
            FunctionJson.mapArg(FunctionJson.mapArg(beforeActions[0])["action"])["tool"],
            FunctionJson.mapArg(FunctionJson.mapArg(afterActions[0])["action"])["tool"],
        )
        assertEquals(2, afterActions.size)
        assertFalse(result.changes.any { it["field"] == "args" })
    }

    @Test
    fun `empty safe proposal records unchanged without altering actions`() {
        val original = functionSpec()
        val result = enhancer.applyProposal(
            original,
            mapOf("step_annotations" to listOf(mapOf("index" to 3, "title" to "missing"))),
        )

        assertEquals("unchanged", result.status)
        assertEquals(original["steps"], result.updated["steps"])
        assertEquals(emptyList<Map<String, Any?>>(), result.changes)
    }

    private fun functionSpec(): Map<String, Any?> = linkedMapOf(
        "schema_version" to FunctionContract.SCHEMA_VERSION,
        "function_id" to "fn_meituan_search",
        "name" to "美团任务",
        "description" to "打开美团并搜索",
        "input_schema" to linkedMapOf(
            "type" to "object",
            "properties" to emptyMap<String, Any?>(),
            "required" to emptyList<String>(),
            "additionalProperties" to false,
        ),
        "bindings" to emptyList<Map<String, Any?>>(),
        "steps" to listOf(
            linkedMapOf(
                "step_index" to 0,
                "action" to linkedMapOf(
                    "tool" to "click",
                    "args" to linkedMapOf(
                        "x" to 500,
                        "y" to 600,
                        "target_description" to "搜索",
                    ),
                ),
            ),
            linkedMapOf(
                "step_index" to 1,
                "action" to linkedMapOf(
                    "tool" to "input_text",
                    "args" to linkedMapOf("text" to "咖啡"),
                ),
            ),
        ),
        "checker_rules" to emptyList<Map<String, Any?>>(),
        "agent_visible" to true,
    )
}
