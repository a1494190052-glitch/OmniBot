package cn.com.omnimind.bot.omniflow.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniflowFunctionMaterializeTest {
    @Test
    fun `materialize uses default value for legacy bound input text parameter`() {
        val fn = functionWithLegacyInputTextParameter()

        val materialized = fn.materialize(emptyMap())
        val value = materialized.steps.single().arguments.getValue("text")

        assertEquals("你好", (value as ParameterValue.Literal).value)
    }

    @Test
    fun `materialize accepts input text alias for legacy bound parameter id`() {
        val fn = functionWithLegacyInputTextParameter()

        val materialized = fn.materialize(mapOf("input_text" to "猫猫"))
        val value = materialized.steps.single().arguments.getValue("text")

        assertEquals("猫猫", (value as ParameterValue.Literal).value)
    }

    @Test
    fun `compiler falls back to public input text parameter id for non latin titles`() {
        val fn = OmniflowCompiler.compile(
            cards = listOf(
                card(
                    toolName = "input_text",
                    args = mapOf("target_description" to "搜索框", "text" to "你好"),
                    title = "人工输入文本",
                ),
            ),
            functionId = "xiaohongshu_search",
        )

        assertEquals("input_text", fn.parameters.single().id)
        assertTrue(fn.steps.single().arguments.getValue("text") is ParameterValue.Bound)
        val materialized = fn.materialize(mapOf("input_text" to "猫猫"))

        assertEquals(
            "猫猫",
            (materialized.steps.single().arguments.getValue("text") as ParameterValue.Literal).value,
        )
    }

    private fun functionWithLegacyInputTextParameter(): OmniflowFunction =
        OmniflowFunction(
            id = "oob_fn_human_trajectory_106fd985",
            name = "人工学习轨迹",
            parameters = listOf(
                FunctionParameter(
                    id = "text_______",
                    default = "你好",
                    bindings = listOf(StepArgBinding(stepIndex = 3, argPath = "text")),
                ),
            ),
            steps = listOf(
                UIStep(
                    id = "step_4",
                    title = "人工输入文本",
                    toolName = "input_text",
                    arguments = mapOf(
                        "target_description" to ParameterValue.Literal("搜索框"),
                        "text" to ParameterValue.Bound("text_______"),
                    ),
                ),
            ),
        )

    private fun card(
        toolName: String,
        args: Map<String, Any?>,
        title: String,
    ): Map<String, Any?> =
        linkedMapOf(
            "tool_name" to toolName,
            "title" to title,
            "args" to args,
            "before" to linkedMapOf(
                "package_name" to "com.xingin.xhs",
                "observation_xml" to "<hierarchy />",
            ),
        )
}
