package cn.com.omnimind.bot.function

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FunctionParameterBindingNormalizerTest {
    @Test
    fun `does not infer input text binding from parameter name`() {
        val normalized = FunctionParameterBindingNormalizer.normalize(
            mapOf(
                "function_id" to "xiaohongshu_search",
                "name" to "小红书搜索关键词",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "input_text_3" to mapOf(
                            "type" to "string",
                            "description" to "搜索关键词",
                            "default" to "彩票",
                        ),
                    ),
                    "required" to emptyList<String>(),
                ),
                "execution" to mapOf(
                    "steps" to listOf(
                        step("open_app", mapOf("package_name" to "com.xingin.xhs")),
                        step("click", mapOf("x" to 100, "y" to 200)),
                        step(
                            "input_text",
                            mapOf(
                                "text" to "彩票",
                                "target_description" to "搜索框",
                            ),
                        ),
                    ),
                ),
            )
        )

        val publicSchema = FunctionSchema.inputSchema(normalized)
        val publicProperties = publicSchema["properties"] as Map<*, *>
        assertFalse(publicProperties.containsKey("input_text_3"))
        assertFalse(normalized.containsKey("x_oob_parameter_bindings"))

        val materialized = FunctionSchema.materialize(
            normalized,
            mapOf("input_text_3" to "猫猫"),
        )
        val steps = ((materialized["execution"] as Map<*, *>)["steps"] as List<*>)
            .map { it as Map<*, *> }
        val args = steps[2]["args"] as Map<*, *>
        assertEquals("彩票", args["text"])
        assertFalse(args.containsKey("content"))
        assertFalse(args.containsKey("value"))
        val validation = FunctionArgumentBindingValidator.validate(materialized)
        assertFalse(validation.success)
        assertTrue(validation.errorMessage.contains("input_text_3"))
    }

    @Test
    fun `supplied argument without binding is rejected by validator`() {
        val materialized = FunctionSchema.materialize(
            mapOf(
                "function_id" to "bad_search",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "search_query" to mapOf(
                            "type" to "string",
                            "default" to "彩票",
                        ),
                    ),
                ),
                "execution" to mapOf(
                    "steps" to listOf(
                        step("input_text", mapOf("text" to "彩票")),
                    ),
                ),
            ),
            mapOf("search_query" to "猫猫"),
        )

        val validation = FunctionArgumentBindingValidator.validate(materialized)
        assertFalse(validation.success)
        assertTrue(validation.errorMessage.contains("search_query"))
        val unbound = validation.diagnostics["unbound_arguments"] as List<*>
        assertEquals("search_query", (unbound.single() as Map<*, *>)["name"])
    }

    @Test
    fun `undeclared runtime arguments are ignored instead of blocking replay`() {
        val normalized = FunctionParameterBindingNormalizer.normalize(
            mapOf(
                "function_id" to "androidworld_file_delete",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "input_text" to mapOf(
                            "type" to "string",
                            "default" to "old.pdf",
                            "x_oob_bindings" to listOf("$.execution.steps[0].args.text"),
                        ),
                    ),
                    "required" to emptyList<String>(),
                ),
                "execution" to mapOf(
                    "steps" to listOf(
                        step("input_text", mapOf("text" to "old.pdf")),
                    ),
                ),
            )
        )

        val materialized = FunctionSchema.materialize(
            normalized,
            mapOf(
                "file_name" to "new.pdf",
                "subfolder" to "Documents",
                "noise_candidates" to listOf("noise.pdf"),
                "seed" to 1234,
            ),
        )

        val runtime = materialized["runtime"] as Map<*, *>
        assertEquals(emptyList<Any?>(), runtime["unbound_arguments"])
        val ignored = runtime["ignored_arguments"] as List<*>
        assertEquals(
            listOf("file_name", "subfolder", "noise_candidates", "seed"),
            ignored.map { (it as Map<*, *>)["name"] },
        )
        assertTrue(FunctionArgumentBindingValidator.validate(materialized).success)
    }

    @Test
    fun `does not infer function call argument binding from semantic parameter name`() {
        val normalized = FunctionParameterBindingNormalizer.normalize(
            mapOf(
                "function_id" to "parent_search",
                "name" to "小红书搜索",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "search_query" to mapOf(
                            "type" to "string",
                            "description" to "搜索关键词",
                            "default" to "彩票",
                        ),
                    ),
                ),
                "execution" to mapOf(
                    "steps" to listOf(
                        step(
                            "call_tool",
                            mapOf(
                                "function_id" to "child_search",
                                "arguments" to mapOf("query" to "彩票"),
                            ),
                        ),
                    ),
                ),
            )
        )

        val publicSchema = FunctionSchema.inputSchema(normalized)
        val publicProperties = publicSchema["properties"] as Map<*, *>
        assertFalse(publicProperties.containsKey("search_query"))
        assertFalse(normalized.containsKey("x_oob_parameter_bindings"))

        val materialized = FunctionSchema.materialize(
            normalized,
            mapOf("search_query" to "猫猫"),
        )
        val steps = ((materialized["execution"] as Map<*, *>)["steps"] as List<*>)
            .map { it as Map<*, *> }
        val args = steps[0]["args"] as Map<*, *>
        val functionArguments = args["arguments"] as Map<*, *>
        assertEquals("彩票", functionArguments["query"])
        val validation = FunctionArgumentBindingValidator.validate(materialized)
        assertFalse(validation.success)
        assertTrue(validation.errorMessage.contains("search_query"))
    }

    @Test
    fun `materialize can create explicit function call argument object from binding table`() {
        val spec = mapOf(
            "function_id" to "parent_search_explicit",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "search_query" to mapOf(
                        "type" to "string",
                        "description" to "搜索关键词",
                        "x_oob_bindings" to listOf(
                            "$.execution.steps[0].args.arguments.search_query",
                        ),
                    ),
                ),
            ),
            "execution" to mapOf(
                "steps" to listOf(
                    step("call_tool", mapOf("function_id" to "child_search")),
                ),
            ),
        )

        val materialized = FunctionSchema.materialize(
            spec,
            mapOf("search_query" to "猫猫"),
        )
        val steps = ((materialized["execution"] as Map<*, *>)["steps"] as List<*>)
            .map { it as Map<*, *> }
        val args = steps[0]["args"] as Map<*, *>
        val functionArguments = args["arguments"] as Map<*, *>
        assertEquals("猫猫", functionArguments["search_query"])
        assertTrue(FunctionArgumentBindingValidator.validate(materialized).success)
    }

    @Test
    fun `agent parameter descriptor binds query into input text step`() {
        val spec = mapOf(
            "function_id" to "zhihu_search",
            "name" to "知乎搜索 \${query}",
            "description" to "打开知乎搜索 \${query}",
            "parameters" to listOf(
                mapOf(
                    "name" to "query",
                    "type" to "string",
                    "description" to "搜索关键词",
                    "required" to true,
                    "bindings" to listOf("$.execution.steps[0].args.text"),
                ),
            ),
            "execution" to mapOf(
                "steps" to listOf(
                    step(
                        "input_text",
                        mapOf(
                            "text" to "猫猫",
                            "target_description" to "搜索框",
                        ),
                    ),
                ),
            ),
        )

        val schema = FunctionSchema.inputSchema(spec)
        val properties = schema["properties"] as Map<*, *>
        val query = properties["query"] as Map<*, *>
        assertEquals(listOf("$.execution.steps[0].args.text"), query["x_oob_bindings"])
        assertEquals(listOf("query"), schema["required"])

        val materialized = FunctionSchema.materialize(
            spec,
            mapOf("query" to "清华大学"),
        )
        val steps = ((materialized["execution"] as Map<*, *>)["steps"] as List<*>)
            .map { it as Map<*, *> }
        val args = steps[0]["args"] as Map<*, *>
        assertEquals("清华大学", args["text"])
        assertEquals("知乎搜索 清华大学", materialized["name"])
        assertTrue(FunctionArgumentBindingValidator.validate(materialized).success)
    }

    @Test
    fun `internal replay arguments are hidden from function schema and ignored at runtime`() {
        val normalized = FunctionParameterBindingNormalizer.normalize(
            mapOf(
                "function_id" to "open_game_and_click",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "package_name" to mapOf("type" to "string"),
                        "target_description" to mapOf("type" to "string"),
                    ),
                    "required" to listOf("package_name", "target_description"),
                ),
                "execution" to mapOf(
                    "steps" to listOf(
                        step("open_app", mapOf("package_name" to "com.hupu.games")),
                        step("click", mapOf("target_description" to "麦当劳", "x" to 100, "y" to 200)),
                    ),
                ),
            )
        )

        val schema = FunctionSchema.inputSchema(normalized)
        val properties = schema["properties"] as Map<*, *>
        assertFalse(properties.containsKey("package_name"))
        assertFalse(properties.containsKey("target_description"))
        assertEquals(emptyList<String>(), FunctionSchema.missingRequiredArguments(normalized, emptyMap()))

        val materialized = FunctionSchema.materialize(
            normalized,
            mapOf(
                "package_name" to "com.hupu.games",
                "target_description" to "麦当劳",
            ),
        )
        val runtime = materialized["runtime"] as Map<*, *>
        assertEquals(emptyList<Any?>(), runtime["unbound_arguments"])
        val ignored = runtime["ignored_arguments"] as List<*>
        assertEquals(
            listOf("package_name", "target_description"),
            ignored.map { (it as Map<*, *>)["name"] },
        )
        assertTrue(FunctionArgumentBindingValidator.validate(materialized).success)
    }

    @Test
    fun `semantic click parameter remains public when explicitly bound`() {
        val normalized = FunctionParameterBindingNormalizer.normalize(
            mapOf(
                "function_id" to "click_brand",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "merchant_name" to mapOf(
                            "type" to "string",
                            "description" to "要点击的商家名称",
                            "x_oob_bindings" to listOf("$.execution.steps[0].args.target_description"),
                        ),
                    ),
                ),
                "execution" to mapOf(
                    "steps" to listOf(
                        step("click", mapOf("target_description" to "肯德基", "x" to 100, "y" to 200)),
                    ),
                ),
            )
        )

        val schema = FunctionSchema.inputSchema(normalized)
        val properties = schema["properties"] as Map<*, *>
        assertTrue(properties.containsKey("merchant_name"))
        val materialized = FunctionSchema.materialize(
            normalized,
            mapOf("merchant_name" to "麦当劳"),
        )
        val steps = ((materialized["execution"] as Map<*, *>)["steps"] as List<*>)
            .map { it as Map<*, *> }
        val args = steps[0]["args"] as Map<*, *>
        assertEquals("麦当劳", args["target_description"])
        assertTrue(FunctionArgumentBindingValidator.validate(materialized).success)
    }

    private fun step(tool: String, args: Map<String, Any?>): Map<String, Any?> =
        mapOf(
            "tool" to tool,
            "executor" to "omniflow",
            "model_free" to true,
            "args" to args,
        )
}
