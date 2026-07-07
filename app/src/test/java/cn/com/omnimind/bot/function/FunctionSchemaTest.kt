package cn.com.omnimind.bot.function

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FunctionSchemaTest {
    @Test
    fun `materialize renders dollar and mustache parameter templates`() {
        val spec = linkedMapOf<String, Any?>(
            "function_id" to "template_test",
            "parameters" to linkedMapOf(
                "type" to "object",
                "properties" to linkedMapOf(
                    "query" to linkedMapOf("type" to "string")
                ),
                "required" to listOf("query")
            ),
            "execution" to linkedMapOf(
                "steps" to listOf(
                    linkedMapOf(
                        "id" to "step_1",
                        "tool" to "input_text",
                        "args" to linkedMapOf(
                            "text" to "${'$'}{query}",
                            "target_description" to "搜索框",
                        ),
                    ),
                    linkedMapOf(
                        "id" to "step_2",
                        "tool" to "input_text",
                        "args" to linkedMapOf(
                            "text" to "搜索 {{ query }}",
                            "target_description" to "搜索框",
                        ),
                    ),
                ),
            ),
        )

        val materialized = FunctionSchema.materialize(spec, mapOf("query" to "清华大学"))

        val steps = FunctionSchema.materializedSteps(materialized)
        assertEquals("清华大学", FunctionJson.mapArg(steps[0]["args"])["text"])
        assertEquals("搜索 清华大学", FunctionJson.mapArg(steps[1]["args"])["text"])
        assertEquals(emptyList<Any?>(), FunctionJson.listArg(FunctionJson.mapArg(materialized["runtime"])["unbound_arguments"]))
        assertEquals(
            2,
            FunctionJson.intArg(
                FunctionJson.mapArg(materialized["runtime"])["supplied_binding_applied_count"],
                defaultValue = 0
            )
        )
        assertTrue(FunctionArgumentBindingValidator.validate(materialized).success)
    }

    @Test
    fun `materialize records partial template bindings without overwriting the full string`() {
        val spec = linkedMapOf<String, Any?>(
            "function_id" to "partial_template_test",
            "parameters" to linkedMapOf(
                "type" to "object",
                "properties" to linkedMapOf(
                    "query" to linkedMapOf("type" to "string")
                ),
                "required" to listOf("query")
            ),
            "execution" to linkedMapOf(
                "steps" to listOf(
                    linkedMapOf(
                        "id" to "step_1",
                        "tool" to "finished",
                        "args" to linkedMapOf(
                            "content" to "done ${'$'}{query} / {{ query }}",
                        ),
                    ),
                ),
            ),
        )

        val materialized = FunctionSchema.materialize(spec, mapOf("query" to "清华大学"))

        val steps = FunctionSchema.materializedSteps(materialized)
        assertEquals("done 清华大学 / 清华大学", FunctionJson.mapArg(steps[0]["args"])["content"])
        val runtime = FunctionJson.mapArg(materialized["runtime"])
        assertEquals(emptyList<Any?>(), FunctionJson.listArg(runtime["unbound_arguments"]))
        assertEquals(1, FunctionJson.intArg(runtime["supplied_binding_applied_count"], defaultValue = 0))
        assertTrue(FunctionArgumentBindingValidator.validate(materialized).success)
    }
}
