package cn.com.omnimind.bot.omniflow

import cn.com.omnimind.baselib.runlog.OobReusableFunctionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OobFunctionParameterBindingNormalizerTest {
    @Test
    fun `normalizes input text step parameter into binding table and materializes`() {
        val normalized = OobFunctionParameterBindingNormalizer.normalize(
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

        val schema = normalized["parameters"] as Map<*, *>
        val properties = schema["properties"] as Map<*, *>
        val input = properties["input_text_3"] as Map<*, *>
        val expectedBindings = listOf(
            "$.execution.steps[2].args.text",
        )
        assertEquals(
            expectedBindings,
            input["x_oob_bindings"],
        )

        val metadata = normalized["metadata"] as Map<*, *>
        val table = metadata["oob_parameter_bindings"] as List<*>
        assertEquals(
            expectedBindings,
            (table.single() as Map<*, *>)["bindings"],
        )

        val materialized = OobReusableFunctionStore.materialize(
            normalized,
            mapOf("input_text_3" to "猫猫"),
        )
        val steps = ((materialized["execution"] as Map<*, *>)["steps"] as List<*>)
            .map { it as Map<*, *> }
        val args = steps[2]["args"] as Map<*, *>
        assertEquals("猫猫", args["text"])
        assertFalse(args.containsKey("content"))
        assertFalse(args.containsKey("value"))
        val runtime = materialized["runtime"] as Map<*, *>
        assertEquals(emptyList<Any?>(), runtime["unbound_arguments"])
        assertTrue((runtime["supplied_binding_applied_count"] as Number).toInt() > 0)
    }

    @Test
    fun `supplied argument without binding is rejected by validator`() {
        val materialized = OobReusableFunctionStore.materialize(
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

        val validation = OobFunctionArgumentBindingValidator.validate(materialized)
        assertFalse(validation.success)
        assertTrue(validation.errorMessage.contains("search_query"))
        val unbound = validation.diagnostics["unbound_arguments"] as List<*>
        assertEquals("search_query", (unbound.single() as Map<*, *>)["name"])
    }

    @Test
    fun `undeclared runtime arguments are ignored instead of blocking replay`() {
        val normalized = OobFunctionParameterBindingNormalizer.normalize(
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

        val materialized = OobReusableFunctionStore.materialize(
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
        assertTrue(OobFunctionArgumentBindingValidator.validate(materialized).success)
    }

    @Test
    fun `normalizes semantic parameter into function call argument binding`() {
        val normalized = OobFunctionParameterBindingNormalizer.normalize(
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

        val schema = normalized["parameters"] as Map<*, *>
        val properties = schema["properties"] as Map<*, *>
        val searchQuery = properties["search_query"] as Map<*, *>
        assertEquals(
            listOf("$.execution.steps[0].args.arguments.query"),
            searchQuery["x_oob_bindings"],
        )
        val topLevelTable = normalized["x_oob_parameter_bindings"] as List<*>
        assertEquals(
            listOf("$.execution.steps[0].args.arguments.query"),
            (topLevelTable.single() as Map<*, *>)["bindings"],
        )

        val materialized = OobReusableFunctionStore.materialize(
            normalized,
            mapOf("search_query" to "猫猫"),
        )
        val steps = ((materialized["execution"] as Map<*, *>)["steps"] as List<*>)
            .map { it as Map<*, *> }
        val args = steps[0]["args"] as Map<*, *>
        val functionArguments = args["arguments"] as Map<*, *>
        assertEquals("猫猫", functionArguments["query"])
        assertTrue(OobFunctionArgumentBindingValidator.validate(materialized).success)
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

        val materialized = OobReusableFunctionStore.materialize(
            spec,
            mapOf("search_query" to "猫猫"),
        )
        val steps = ((materialized["execution"] as Map<*, *>)["steps"] as List<*>)
            .map { it as Map<*, *> }
        val args = steps[0]["args"] as Map<*, *>
        val functionArguments = args["arguments"] as Map<*, *>
        assertEquals("猫猫", functionArguments["search_query"])
        assertTrue(OobFunctionArgumentBindingValidator.validate(materialized).success)
    }

    @Test
    fun `internal replay arguments are hidden from function schema and ignored at runtime`() {
        val normalized = OobFunctionParameterBindingNormalizer.normalize(
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

        val schema = OobFunctionSchemaBuilder.inputSchema(normalized)
        val properties = schema["properties"] as Map<*, *>
        assertFalse(properties.containsKey("package_name"))
        assertFalse(properties.containsKey("target_description"))
        assertEquals(emptyList<String>(), OobReusableFunctionStore.missingRequiredArguments(normalized, emptyMap()))

        val materialized = OobReusableFunctionStore.materialize(
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
        assertTrue(OobFunctionArgumentBindingValidator.validate(materialized).success)
    }

    @Test
    fun `semantic click parameter remains public when explicitly bound`() {
        val normalized = OobFunctionParameterBindingNormalizer.normalize(
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

        val schema = OobFunctionSchemaBuilder.inputSchema(normalized)
        val properties = schema["properties"] as Map<*, *>
        assertTrue(properties.containsKey("merchant_name"))
        val materialized = OobReusableFunctionStore.materialize(
            normalized,
            mapOf("merchant_name" to "麦当劳"),
        )
        val steps = ((materialized["execution"] as Map<*, *>)["steps"] as List<*>)
            .map { it as Map<*, *> }
        val args = steps[0]["args"] as Map<*, *>
        assertEquals("麦当劳", args["target_description"])
        assertTrue(OobFunctionArgumentBindingValidator.validate(materialized).success)
    }

    private fun step(tool: String, args: Map<String, Any?>): Map<String, Any?> =
        mapOf(
            "tool" to tool,
            "executor" to "omniflow",
            "model_free" to true,
            "args" to args,
        )
}
