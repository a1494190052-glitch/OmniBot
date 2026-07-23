package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import cn.com.omnimind.baselib.llm.ModelSceneRegistry
import cn.com.omnimind.baselib.i18n.PromptLocale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VLMToolCallRecoveryTest {
    @Test
    fun modelVisibleClickSchemaRequiresBothCoordinates() {
        val click = VLMToolDefinitions.tools(
            locale = PromptLocale.ZH_CN,
            allowedToolNames = setOf("click"),
        ).single().function.parameters
        val required = (click["required"] as JsonArray)
            .map { it.jsonPrimitive.content }
        val properties = click["properties"] as JsonObject

        assertEquals(listOf("target_description", "x", "y"), required)
        assertTrue("x" in properties)
        assertTrue("y" in properties)
    }

    @Test
    fun validNativeToolCallDoesNotRequireAssistantContent() {
        val result = VLMClient().parseVLMResponse(
            response = sceneTurn(
                toolCall(
                    name = "click",
                    arguments = """{"target_description":"商品卡片","x":812,"y":500}""",
                )
            ),
            modelOrScene = "scene.vlm_task",
        )

        assertTrue(result.success)
        assertTrue(result.step?.action is Action)
        assertEquals("click", result.step?.action?.name)
        assertEquals("", result.step?.summary)
        assertFalse(result.shouldRetryForToolCall)
    }

    @Test
    fun missingClickYIsRejectedAndPreservedForCorrection() {
        val toolCall = toolCall(
            name = "click",
            arguments = """{"target_description":"商品卡片","x":812}""",
        )

        val result = VLMClient().parseVLMResponse(
            response = sceneTurn(toolCall),
            modelOrScene = "scene.vlm_task",
        )

        assertFalse(result.success)
        assertNull(result.step)
        assertTrue(result.shouldRetryForToolCall)
        assertEquals(toolCall, result.previousToolCall)
        assertEquals("click", result.toolCallFailure?.toolName)
        assertEquals(listOf("y"), result.toolCallFailure?.missingFields)
        assertEquals("integer", result.toolCallFailure?.argumentTypes?.get("x"))
    }

    @Test
    fun retryConversationIncludesFailedNativeCallAndStructuredToolError() {
        val toolCall = toolCall(
            name = "click",
            arguments = """{"target_description":"商品卡片","x":812}""",
        )
        val failure = VLMToolDefinitions.describeInvalidToolCall(
            toolCall = toolCall,
            message = "Tool click missing required argument: y",
        )
        val messages = VLMClient().buildRetryMessages(
            context = UIContext(overallTask = "添加商品到购物车"),
            retryState = VLMToolCallRetryState(
                retryIndex = 1,
                thinking = VLMThinkingContext(rawContent = """{"summary":"点击商品"}"""),
                failureReason = failure.message,
                previousToolCall = toolCall,
                toolCallFailure = failure,
            ),
        )

        assertEquals(listOf("assistant", "tool", "user"), messages.map { it.role })
        assertEquals(toolCall, messages[0].toolCalls?.single())
        assertEquals(toolCall.id, messages[1].toolCallId)
        val toolResult = messages[1].content?.jsonPrimitive?.contentOrNull.orEmpty()
        assertTrue(toolResult.contains("tool_call_schema_validation_failed"))
        assertTrue(toolResult.contains("missing_fields"))
        assertTrue(toolResult.contains("y"))
        val correctionPrompt = messages[2].content?.jsonPrimitive?.contentOrNull.orEmpty()
        assertTrue(correctionPrompt.contains("y"))
        assertTrue(correctionPrompt.contains("target_description:string"))
        assertTrue(correctionPrompt.contains("x:integer"))
        assertTrue(correctionPrompt.contains("\"target_description\":\"value\""))
        assertTrue(correctionPrompt.contains("\"x\":500"))
        assertTrue(correctionPrompt.contains("\"y\":500"))
    }

    @Test
    fun safeFailurePreviewRedactsInputTextValue() {
        val secret = "private search query"
        val failure = VLMToolDefinitions.describeInvalidToolCall(
            toolCall = toolCall(
                name = "input_text",
                arguments = """{"target_description":"搜索框","text":"$secret","x":300}""",
            ),
            message = "Tool input_text missing required argument: y",
        )

        assertFalse(failure.safeArgumentsPreview.orEmpty().contains(secret))
        assertTrue(failure.safeArgumentsPreview.orEmpty().contains("<redacted>"))
        assertEquals(listOf("y"), failure.missingFields)
    }

    @Test
    fun malformedArgumentsDoNotLeakRawInputIntoFailure() {
        val secret = "private search query"
        val result = VLMClient().parseVLMResponse(
            response = sceneTurn(
                toolCall(
                    name = "input_text",
                    arguments = """{"target_description":"搜索框","text":"$secret"""",
                )
            ),
            modelOrScene = "scene.vlm_task",
        )

        assertFalse(result.error.orEmpty().contains(secret))
        assertFalse(result.toolCallFailure?.message.orEmpty().contains(secret))
        assertFalse(result.toolCallFailure?.safeArgumentsPreview.orEmpty().contains(secret))
        assertEquals("invalid_arguments_json", result.toolCallFailure?.code)
    }

    @Test
    fun quotedCoordinateIsRejectedInsteadOfCoerced() {
        val result = VLMClient().parseVLMResponse(
            response = sceneTurn(
                toolCall(
                    name = "click",
                    arguments = """{"target_description":"商品卡片","x":"812","y":500}""",
                )
            ),
            modelOrScene = "scene.vlm_task",
        )

        assertFalse(result.success)
        assertNull(result.step)
        assertTrue(result.error.orEmpty().contains("expected number"))
    }

    @Test
    fun presentationMetadataIsRejectedInsteadOfSilentlyDropped() {
        val result = VLMClient().parseVLMResponse(
            response = sceneTurn(
                toolCall(
                    name = "click",
                    arguments = """{"target_description":"商品卡片","x":812,"y":500,"tool_title":"点击商品"}""",
                )
            ),
            modelOrScene = "scene.vlm_task",
        )

        assertFalse(result.success)
        assertNull(result.step)
        assertTrue(result.error.orEmpty().contains("unknown argument: tool_title"))
    }

    @Test
    fun recalledFunctionWrapperFieldsAreRejected() {
        val toolName = "run_recalled_workflow_1"
        val result = VLMClient().parseVLMResponse(
            response = sceneTurn(
                toolCall(
                    name = toolName,
                    arguments = """{"function_id":"legacy_wrapper"}""",
                )
            ),
            modelOrScene = "scene.vlm_task",
            dynamicFunctionToolNames = setOf(toolName),
            dynamicFunctionToolMappings = mapOf(toolName to "search_product"),
        )

        assertFalse(result.success)
        assertNull(result.step)
        assertTrue(result.error.orEmpty().contains("reserved argument: function_id"))
    }

    @Test
    fun diagnosticFailureIsNotRecordedAsSuccessfulToolCall() {
        val step = UIStep(
            observation = "VLM响应解析失败",
            thought = "参数不合规",
            action = RecordMemory(content = "解析失败"),
            result = "解析失败",
            failure = VLMFailureDiagnostics(
                kind = "tool_call_failure",
                message = "Tool click missing required argument: y",
            ),
        )

        val semantics = resolveVlmRunLogStepSemantics(step, successOverride = true)

        assertFalse(semantics.success)
        assertEquals("tool_call_failure", semantics.toolName)
        assertEquals("vlm_diagnostic", semantics.toolType)
        assertNull(semantics.actionType)
        assertFalse(semantics.hasNativeToolCall)
    }

    @Test
    fun userCancellationBuildsFailedAbortTerminalStep() {
        val state = State(
            stateId = "run-vlm-user-cancelled-100",
            xml = "<hierarchy />",
            packageName = "demo.app",
            display = StateDisplay(width = 1080, height = 2400),
        )

        val step = buildUserCancelledTerminalStep(
            state = state,
            message = "任务已取消",
            timestampMs = 100L,
        )
        val semantics = resolveVlmRunLogStepSemantics(step)

        assertTrue(step.action is AbortDecision)
        assertEquals("任务已取消", (step.action as AbortDecision).value)
        assertEquals(state, step.beforeState)
        assertEquals(state, step.afterState)
        assertEquals("user_cancelled", step.failure?.kind)
        assertFalse(semantics.success)
    }

    private fun toolCall(name: String, arguments: String): AssistantToolCall =
        AssistantToolCall(
            id = "call_1",
            function = AssistantToolCallFunction(name = name, arguments = arguments),
        )

    private fun sceneTurn(toolCall: AssistantToolCall): SceneChatCompletionTurn =
        SceneChatCompletionTurn(
            parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
            resolvedModel = "Qwen3-VL",
            turn = ChatCompletionTurn(
                message = ChatCompletionMessage(
                    role = "assistant",
                    content = JsonPrimitive(""),
                    toolCalls = listOf(toolCall),
                ),
                finishReason = "tool_calls",
            ),
        )
}
