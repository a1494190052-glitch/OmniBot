package cn.com.omnimind.bot.vlm

import cn.com.omnimind.assists.task.vlmserver.SceneChatCompletionTurn
import cn.com.omnimind.assists.task.vlmserver.VLMStreamClient
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import cn.com.omnimind.baselib.llm.ModelSceneRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class VlmRecallFunctionSelectorTest {
    @Test
    fun `high scoring canonical candidate enters eager replay`() = runBlocking {
        val selector = VlmRecallFunctionSelector(
            recallEnabled = { true },
            recall = {
                mapOf(
                    "candidates" to listOf(
                        mapOf(
                            "function" to functionSpec(),
                            "retrieval" to mapOf(
                                "score" to 0.92,
                                "source" to "goal_token_jaccard",
                                "rank" to 1,
                            ),
                        ),
                    ),
                )
            },
        )

        val invocation = selector.selectAction(
            goal = "open settings",
            packageName = "com.example",
            disableFunctionRecall = false,
            streamClient = matchingStreamClient(),
        )

        assertNotNull(invocation)
        assertEquals("open_settings", invocation?.functionId)
        assertEquals(emptyMap<String, Any?>(), invocation?.arguments?.toMap())
    }

    private fun matchingStreamClient(): VLMStreamClient = object : VLMStreamClient {
        override suspend fun streamTurn(
            request: ChatCompletionRequest,
            onReasoningUpdate: (suspend (String) -> Unit)?,
        ): SceneChatCompletionTurn = SceneChatCompletionTurn(
            parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
            resolvedModel = "test-model",
            turn = ChatCompletionTurn(
                message = ChatCompletionMessage(
                    role = "assistant",
                    content = JsonPrimitive("{\"matches\":true,\"arguments\":{}}"),
                ),
            ),
        )
    }

    private fun functionSpec(): Map<String, Any?> = linkedMapOf(
        "schema_version" to "omniflow.function.v2",
        "function_id" to "open_settings",
        "name" to "Open device settings",
        "description" to "Open the Android settings app",
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
                "source_state_id" to "state-0",
                "action" to linkedMapOf(
                    "tool" to "open_app",
                    "args" to mapOf("package_name" to "com.android.settings"),
                ),
            ),
        ),
        "checker_rules" to emptyList<Map<String, Any?>>(),
        "agent_visible" to true,
    )
}
