package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import cn.com.omnimind.baselib.llm.ModelSceneRegistry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VLMClientRequestTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `raw VLM model id keeps primary tool-action scene and uses model override`() {
        val client = VLMClient()

        assertEquals("scene.vlm.operation.primary", client.resolveVlmSceneId("guiagent-vlm-model"))
        assertEquals("guiagent-vlm-model", client.resolveVlmModelOverride("guiagent-vlm-model"))
    }

    @Test
    fun `scene model id is not duplicated as override`() {
        val client = VLMClient()

        assertEquals("scene.vlm.operation.primary", client.resolveVlmSceneId("scene.vlm.operation.primary"))
        assertEquals(null, client.resolveVlmModelOverride("scene.vlm.operation.primary"))
    }

    @Test
    fun `model override is transport metadata and not serialized to provider payload`() {
        val request = cn.com.omnimind.baselib.llm.ChatCompletionRequest(
            model = "scene.vlm.operation.primary",
            modelOverride = "guiagent-vlm-model",
            messages = emptyList()
        )
        val encoded = json.encodeToString(request)

        assertFalse(encoded.contains("modelOverride"))
        assertFalse(encoded.contains("model_override"))
        assertTrue(encoded.contains("scene.vlm.operation.primary"))
    }

    @Test
    fun `operation request defaults to one screenshot plus compact indexed evidence text`() {
        val client = VLMClient(
            systemPromptBuilder = { "system prompt" },
            turnPromptBuilder = { context, _ -> "${context.overallTask}\n${context.currentPageSummary}" }
        )

        val envelope = client.buildUIOperationRequest(
            context = UIContext(
                overallTask = "Open Settings",
                currentPageSummary = "OOB indexed page evidence:\n#0 label=\"Settings\""
            ),
            screenshot = "RAW_IMAGE",
            markedScreenshot = "MARKED_IMAGE",
            conversationState = VLMConversationState()
        )

        val currentUser = envelope.request.messages.last()
        val blocks = currentUser.content!!.jsonArray
        assertEquals(3, blocks.size)
        assertTrue(blocks[0].jsonObject["text"]!!.jsonPrimitive.contentOrNull!!.contains("indexed page evidence"))
        assertEquals("Current screenshot.", blocks[1].jsonObject["text"]!!.jsonPrimitive.contentOrNull)
        assertEquals(
            "data:image/png;base64,RAW_IMAGE",
            blocks[2].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.contentOrNull
        )
        assertFalse(currentUser.content.toString().contains("MARKED_IMAGE"))
        assertEquals(384, envelope.request.maxCompletionTokens)
        assertEquals(false, envelope.request.enableThinking)
        assertEquals("none", envelope.request.reasoningEffort)
        assertEquals("disabled", envelope.request.thinking?.type)
    }

    @Test
    fun `operation request can opt into marked screenshot fallback`() {
        val client = VLMClient(
            systemPromptBuilder = { "system prompt" },
            turnPromptBuilder = { context, _ -> context.overallTask }
        )

        val envelope = client.buildUIOperationRequest(
            context = UIContext(overallTask = "Open Settings"),
            screenshot = "RAW_IMAGE",
            markedScreenshot = "MARKED_IMAGE",
            conversationState = VLMConversationState(),
            includeMarkedScreenshot = true
        )

        val currentUser = envelope.request.messages.last()
        val blocks = currentUser.content!!.jsonArray
        assertEquals(5, blocks.size)
        assertEquals(
            "Marked screenshot with indexes matching OOB indexed page evidence.",
            blocks[3].jsonObject["text"]!!.jsonPrimitive.contentOrNull
        )
        assertEquals(
            "data:image/png;base64,MARKED_IMAGE",
            blocks[4].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.contentOrNull
        )
    }

    @Test
    fun `operation request hides recalled function tools from normal vlm`() {
        val client = VLMClient(
            systemPromptBuilder = { "system prompt" },
            turnPromptBuilder = { context, _ -> context.overallTask }
        )

        val envelope = client.buildUIOperationRequest(
            context = UIContext(
                overallTask = "Open Settings",
                dynamicToolDefinitions = listOf(dynamicFunctionToolDefinition("debug_agent_function_open_settings"))
            ),
            screenshot = null,
            conversationState = VLMConversationState()
        )

        val toolNames = envelope.request.tools.orEmpty().map { it.function.name }
        assertTrue(toolNames.contains("click"))
        assertFalse(toolNames.contains("call_tool"))
        assertFalse(toolNames.contains("debug_agent_function_open_settings"))
        assertEquals(setOf("debug_agent_function_open_settings"), envelope.dynamicFunctionToolNames)
        assertEquals(toolNames, envelope.toolNames)
        assertEquals("required", envelope.request.toolChoice!!.jsonPrimitive.contentOrNull)
        assertTrue(envelope.systemPromptChars > 0)
        assertTrue(envelope.currentUserTextChars > 0)
    }

    @Test
    fun `operation request injects a narrowed canonical tool set for ordinary click tasks`() {
        val client = VLMClient(
            systemPromptBuilder = { "system prompt" },
            turnPromptBuilder = { context, _ ->
                PromptTemplate.buildTurnUserPrompt(context, "scene.vlm.operation.primary")
            }
        )

        val envelope = client.buildUIOperationRequest(
            context = UIContext(overallTask = "点击设置搜索框"),
            screenshot = null,
            conversationState = VLMConversationState()
        )

        val toolNames = envelope.request.tools.map { it.function.name }
        assertTrue(toolNames.contains("click"))
        assertTrue(toolNames.contains("finished"))
        assertTrue(toolNames.contains("feedback"))
        assertTrue(toolNames.contains("abort"))
        assertFalse(toolNames.contains("input_text"))
        assertFalse(toolNames.contains("swipe"))
        assertFalse(toolNames.contains("press_key"))
        assertFalse(toolNames.contains("wait"))
        assertFalse(toolNames.contains("long_press"))
        assertFalse(toolNames.contains("open_app"))
        assertFalse(toolNames.contains("info"))
        assertTrue(envelope.defaultToolCount > toolNames.size)
        assertEquals(toolNames.toSet(), envelope.selectedBaseToolNames)
        assertTrue(
            envelope.currentUserText.contains("Allowed tools this turn") ||
                envelope.currentUserText.contains("本轮允许工具")
        )
        assertFalse(envelope.currentUserText.contains("long_press only for context menus"))
    }

    @Test
    fun `operation request keeps input tool when task asks to type text`() {
        val client = VLMClient(
            systemPromptBuilder = { "system prompt" },
            turnPromptBuilder = { context, _ ->
                PromptTemplate.buildTurnUserPrompt(context, "scene.vlm.operation.primary")
            }
        )

        val envelope = client.buildUIOperationRequest(
            context = UIContext(overallTask = "点击搜索框并输入奶茶"),
            screenshot = null,
            conversationState = VLMConversationState()
        )

        val toolNames = envelope.request.tools.map { it.function.name }
        assertTrue(toolNames.contains("click"))
        assertTrue(toolNames.contains("input_text"))
        assertTrue(toolNames.contains("finished"))
        assertFalse(toolNames.contains("swipe"))
        assertFalse(toolNames.contains("wait"))
    }

    @Test
    fun `operation request keeps open app tool when target package is not current`() {
        val client = VLMClient(
            systemPromptBuilder = { "system prompt" },
            turnPromptBuilder = { context, _ -> PromptTemplate.buildTurnUserPrompt(context, "scene.vlm.operation.primary") }
        )

        val envelope = client.buildUIOperationRequest(
            context = UIContext(
                overallTask = "打开设置",
                targetPackageName = "com.android.settings",
                currentPackageName = "com.android.launcher"
            ),
            screenshot = null,
            conversationState = VLMConversationState()
        )

        val toolNames = envelope.request.tools.map { it.function.name }
        assertTrue(toolNames.contains("open_app"))
        assertTrue(envelope.currentUserText.contains("open_app"))
    }

    @Test
    fun `protocol retry request can omit unchanged screenshot`() {
        val client = VLMClient(
            systemPromptBuilder = { "system prompt" },
            turnPromptBuilder = { context, _ -> "${context.overallTask}\n${context.currentPageSummary}" }
        )

        val envelope = client.buildUIOperationRequest(
            context = UIContext(
                overallTask = "Open Settings",
                currentPageSummary = "OOB indexed page evidence:\n#0 label=\"Settings\""
            ),
            screenshot = null,
            markedScreenshot = null,
            conversationState = VLMConversationState(),
            retryState = VLMToolCallRetryState(
                retryIndex = 1,
                thinking = VLMThinkingContext(rawContent = """{"thought":"missing tool"}"""),
                failureReason = "missing native tool_call"
            ),
            includeMarkedScreenshot = true
        )

        val currentUser = envelope.request.messages[1]
        val blocks = currentUser.content!!.jsonArray
        assertEquals(1, blocks.size)
        assertTrue(blocks[0].jsonObject["text"]!!.jsonPrimitive.contentOrNull!!.contains("indexed page evidence"))
        assertFalse(currentUser.content.toString().contains("image_url"))
        assertFalse(currentUser.content.toString().contains("MARKED_IMAGE"))
        assertEquals("assistant", envelope.request.messages[2].role)
        assertEquals("user", envelope.request.messages[3].role)
        assertTrue(
            envelope.request.messages[3].content!!.jsonPrimitive.contentOrNull!!.contains("tool_call")
        )
    }

    @Test
    fun `conversation tool result omits post action page observation`() {
        val client = VLMClient()
        val round = client.buildConversationRound(
            currentUserText = "current turn",
            assistantTurn = SceneChatCompletionTurn(
                parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                route = "scene.vlm.operation.primary",
                resolvedModel = "vlm-test-model",
                turn = ChatCompletionTurn(
                    message = ChatCompletionMessage(
                        role = "assistant",
                        content = JsonPrimitive("""{"thought":"tap settings"}"""),
                        toolCalls = listOf(
                            AssistantToolCall(
                                id = "call_1",
                                function = AssistantToolCallFunction(
                                    name = "click",
                                    arguments = """{"target_description":"Settings","x":100,"y":100}"""
                                )
                            )
                        )
                    )
                )
            ),
            executedStep = UIStep(
                observation = "before",
                thought = "tap",
                action = ClickAction(targetDescription = "Settings", x = 100f, y = 100f),
                result = "OK",
                observationXml = BEFORE_XML,
                afterObservationXml = AFTER_XML,
                packageName = "com.android.launcher",
                afterPackageName = "com.android.settings"
            )
        )

        val payload = json.parseToJsonElement(round.toolMessage.content!!.jsonPrimitive.contentOrNull!!).jsonObject
        assertEquals(setOf("success", "result"), payload.keys)
        assertTrue(payload["success"]!!.jsonPrimitive.boolean)
        assertEquals("OK", payload["result"]!!.jsonPrimitive.contentOrNull)
        assertFalse(payload["result"].toString().contains("Network & internet"))
        assertFalse(payload["result"].toString().contains("after_visible_texts"))
        assertFalse(payload.containsKey("state_delta"))
        assertFalse(payload.containsKey("continuation"))
    }

    @Test
    fun `plain assistant content with native tool call is treated as summary`() {
        val client = VLMClient()
        val result = client.parseVLMResponse(
            SceneChatCompletionTurn(
                parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                route = "scene.vlm.operation.primary",
                resolvedModel = "vlm-test-model",
                turn = ChatCompletionTurn(
                    message = ChatCompletionMessage(
                        role = "assistant",
                        content = JsonPrimitive("点击设置入口"),
                        toolCalls = listOf(
                            AssistantToolCall(
                                id = "call_1",
                                function = AssistantToolCallFunction(
                                    name = "click",
                                    arguments = """{"target_description":"Settings","x":100,"y":100}"""
                                )
                            )
                        )
                    )
                )
            ),
            modelOrScene = "scene.vlm.operation.primary"
        )

        assertTrue(result.error.orEmpty(), result.success)
        val step = requireNotNull(result.step)
        assertTrue(step.action is ClickAction)
        assertEquals("", step.thought)
        assertEquals("点击设置入口", step.summary)
    }

    @Test
    fun `conversation history compacts previous user prompt to avoid repeating page evidence`() {
        val client = VLMClient()
        val verbosePrompt = """
            用户任务: Open Settings
            OOB indexed page evidence:
            #0 label="Settings" bounds=[0,0][720,1280]
        """.trimIndent()
        val round = client.buildConversationRound(
            currentUserText = verbosePrompt,
            assistantTurn = SceneChatCompletionTurn(
                parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                route = "scene.vlm.operation.primary",
                resolvedModel = "vlm-test-model",
                turn = ChatCompletionTurn(
                    message = ChatCompletionMessage(
                        role = "assistant",
                        toolCalls = listOf(
                            AssistantToolCall(
                                id = "call_1",
                                function = AssistantToolCallFunction(
                                    name = "click",
                                    arguments = """{"target_description":"Settings","x":100,"y":100}"""
                                )
                            )
                        )
                    )
                )
            ),
            executedStep = UIStep(
                observation = "before",
                thought = "tap",
                action = ClickAction(targetDescription = "Settings", x = 100f, y = 100f),
                result = "OK",
                observationXml = BEFORE_XML,
                afterObservationXml = AFTER_XML,
                packageName = "com.android.launcher",
                afterPackageName = "com.android.settings"
            )
        )

        val compactUser = round.userMessage.content!!.jsonPrimitive.contentOrNull.orEmpty()
        assertTrue(compactUser.contains("Previous turn compact context"))
        assertTrue(compactUser.contains("Prior action: click Settings"))
        assertFalse(compactUser.contains("Post-action observation"))
        assertFalse(compactUser.contains("Network & internet"))
        assertFalse(compactUser.contains("OOB indexed page evidence"))
        assertFalse(compactUser.contains("bounds=[0,0][720,1280]"))
    }

    @Test
    fun `conversation tool result omits raw xml from function result data`() {
        val client = VLMClient()
        val round = client.buildConversationRound(
            currentUserText = "current turn",
            assistantTurn = SceneChatCompletionTurn(
                parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                route = "scene.vlm.operation.primary",
                resolvedModel = "vlm-test-model",
                turn = ChatCompletionTurn(
                    message = ChatCompletionMessage(
                        role = "assistant",
                        toolCalls = listOf(
                            AssistantToolCall(
                                id = "call_1",
                                function = AssistantToolCallFunction(
                                    name = "debug_agent_function_open_settings",
                                    arguments = "{}"
                                )
                            )
                        )
                    )
                )
            ),
            executedStep = UIStep(
                observation = "before",
                thought = "call function",
                action = FunctionRunAction(functionId = "debug_agent_function_open_settings"),
                result = "OK",
                actionResultData = buildJsonObject {
                    put("function_id", "debug_agent_function_open_settings")
                    put("observation_xml", "<hierarchy><node text=\"Settings\" /></hierarchy>")
                    put("nested", buildJsonObject {
                        put("current_xml", "<node text=\"Network\" />")
                    })
                }
            )
        )

        val payloadText = round.toolMessage.content!!.jsonPrimitive.contentOrNull.orEmpty()
        val payload = json.parseToJsonElement(payloadText).jsonObject
        assertEquals(setOf("success", "result"), payload.keys)
        assertTrue(payload["success"]!!.jsonPrimitive.boolean)
        assertFalse(payloadText.contains("<hierarchy"))
        assertFalse(payloadText.contains("<node"))
    }

    @Test
    fun `openai tool action parser supports input_text with target grounding`() {
        val client = VLMClient()
        val result = client.parseVLMResponse(
            SceneChatCompletionTurn(
                parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                route = "scene.vlm.operation.primary",
                resolvedModel = "vlm-test-model",
                turn = ChatCompletionTurn(
                    message = ChatCompletionMessage(
                        role = "assistant",
                        toolCalls = listOf(
                            AssistantToolCall(
                                id = "call_1",
                                function = AssistantToolCallFunction(
                                    name = "input_text",
                                    arguments = """{"target_description":"Last name","text":"Smith","x":356,"y":799.5}"""
                                )
                            )
                        )
                    )
                )
            ),
            modelOrScene = "scene.vlm.operation.primary"
        )

        assertTrue(result.success)
        val step = requireNotNull(result.step)
        val action = step.action as InputTextAction
        assertEquals("Last name", action.targetDescription)
        assertEquals("Smith", action.text)
        assertEquals(356f, action.x, 0.01f)
        assertEquals(799.5f, action.y, 0.01f)
    }

    @Test
    fun `openai tool action parser rejects multiple tool calls in one turn`() {
        val client = VLMClient()
        val result = client.parseVLMResponse(
            SceneChatCompletionTurn(
                parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                route = "scene.vlm.operation.primary",
                resolvedModel = "vlm-test-model",
                turn = ChatCompletionTurn(
                    message = ChatCompletionMessage(
                        role = "assistant",
                        toolCalls = listOf(
                            AssistantToolCall(
                                id = "call_1",
                                function = AssistantToolCallFunction(
                                    name = "click",
                                    arguments = """{"target_description":"Settings","x":100,"y":100}"""
                                )
                            ),
                            AssistantToolCall(
                                id = "call_2",
                                function = AssistantToolCallFunction(
                                    name = "click",
                                    arguments = """{"target_description":"Network","x":200,"y":200}"""
                                )
                            )
                        )
                    )
                )
            ),
            modelOrScene = "scene.vlm.operation.primary"
        )

        assertFalse(result.success)
        assertTrue(result.error.orEmpty().contains("每轮只能返回一个 tool_call"))
    }

    @Test
    fun `openai tool action parser rejects scroll alias tool call`() {
        val client = VLMClient()
        val result = client.parseVLMResponse(
            SceneChatCompletionTurn(
                parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                route = "scene.vlm.operation.primary",
                resolvedModel = "qwen-vl-max",
                turn = ChatCompletionTurn(
                    message = ChatCompletionMessage(
                        role = "assistant",
                        toolCalls = listOf(
                            AssistantToolCall(
                                id = "call_1",
                                function = AssistantToolCallFunction(
                                    name = "scroll",
                                    arguments = """{"target_description":"Settings list","direction":"down","x1":500,"y1":860,"x2":500,"y2":220}"""
                                )
                            )
                        )
                    )
                )
            ),
            modelOrScene = "scene.vlm.operation.primary"
        )

        assertFalse(result.success)
        assertTrue(result.step == null)
        assertTrue(result.error.orEmpty().contains("Unsupported tool call: scroll"))
    }

    @Test
    fun `openai tool action parser strips qwen tool title metadata arguments`() {
        val client = VLMClient()
        val result = client.parseVLMResponse(
            SceneChatCompletionTurn(
                parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                route = "scene.vlm.operation.primary",
                resolvedModel = "qwen-vl-max",
                turn = ChatCompletionTurn(
                    message = ChatCompletionMessage(
                        role = "assistant",
                        toolCalls = listOf(
                            AssistantToolCall(
                                id = "call_1",
                                function = AssistantToolCallFunction(
                                    name = "click",
                                    arguments = """{"tool_title":"继续 VLM 任务执行","toolTitle":"legacy title","target_description":"Settings","x":480,"y":702}"""
                                )
                            )
                        )
                    )
                )
            ),
            modelOrScene = "scene.vlm.operation.primary"
        )

        assertTrue(result.error.orEmpty(), result.success)
        val action = requireNotNull(result.step).action as ClickAction
        assertEquals("Settings", action.targetDescription)
        assertEquals(480f, action.x, 0.01f)
        assertEquals(702f, action.y, 0.01f)
    }

    @Test
    fun `openai tool action parser rejects point tuple coordinate arguments`() {
        val client = VLMClient()
        val result = client.parseVLMResponse(
            SceneChatCompletionTurn(
                parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                route = "scene.vlm.operation.primary",
                resolvedModel = "vlm-test-model",
                turn = ChatCompletionTurn(
                    message = ChatCompletionMessage(
                        role = "assistant",
                        toolCalls = listOf(
                            AssistantToolCall(
                                id = "call_1",
                                function = AssistantToolCallFunction(
                                    name = "click",
                                    arguments = """{"target_description":"Settings","x":[480,702],"y":702}"""
                                )
                            )
                        )
                    )
                )
            ),
            modelOrScene = "scene.vlm.operation.primary"
        )

        assertFalse(result.success)
        assertTrue(result.step == null)
        assertTrue(result.error.orEmpty().contains("Coordinate fields must be a single numeric scalar"))
    }

    @Test
    fun `openai tool action parser rejects swipe tuple coordinate arguments`() {
        val client = VLMClient()
        val result = client.parseVLMResponse(
            SceneChatCompletionTurn(
                parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                route = "scene.vlm.operation.primary",
                resolvedModel = "vlm-test-model",
                turn = ChatCompletionTurn(
                    message = ChatCompletionMessage(
                        role = "assistant",
                        toolCalls = listOf(
                            AssistantToolCall(
                                id = "call_1",
                                function = AssistantToolCallFunction(
                                    name = "swipe",
                                    arguments = """{"target_description":"Settings list","direction":"up","x1":[500,860],"y1":860,"x2":500,"y2":220}"""
                                )
                            )
                        )
                    )
                )
            ),
            modelOrScene = "scene.vlm.operation.primary"
        )

        assertFalse(result.success)
        assertTrue(result.step == null)
        assertTrue(result.error.orEmpty().contains("Coordinate fields must be a single numeric scalar"))
    }

    @Test
    fun `openai tool action parser rejects call tool function invocation`() {
        val client = VLMClient()
        val result = client.parseVLMResponse(
            SceneChatCompletionTurn(
                parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                route = "scene.vlm.operation.primary",
                resolvedModel = "vlm-test-model",
                turn = ChatCompletionTurn(
                    message = ChatCompletionMessage(
                        role = "assistant",
                        toolCalls = listOf(
                            AssistantToolCall(
                                id = "call_1",
                                function = AssistantToolCallFunction(
                                    name = "call_tool",
                                    arguments = """{"function_id":"xiaohongshu_search","arguments":{"keyword":"美食"}}"""
                                )
                            )
                        )
                    )
                )
            ),
            modelOrScene = "scene.vlm.operation.primary"
        )

        assertFalse(result.success)
        assertTrue(result.error.orEmpty().contains("call_tool is an internal runtime action"))
    }

    @Test
    fun `openai tool action parser rejects recalled function id inside call tool`() {
        val client = VLMClient()
        val result = client.parseVLMResponse(
            SceneChatCompletionTurn(
                parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                route = "scene.vlm.operation.primary",
                resolvedModel = "vlm-test-model",
                turn = ChatCompletionTurn(
                    message = ChatCompletionMessage(
                        role = "assistant",
                        toolCalls = listOf(
                            AssistantToolCall(
                                id = "call_1",
                                function = AssistantToolCallFunction(
                                    name = "call_tool",
                                    arguments = """{"function_id":"xhs_search_keyword","arguments":{"keyword":"猫猫"}}"""
                                )
                            )
                        )
                    )
                )
            ),
            modelOrScene = "scene.vlm.operation.primary"
        )

        assertFalse(result.success)
        assertTrue(result.error.orEmpty().contains("call_tool is an internal runtime action"))
    }

    @Test
    fun `openai tool action parser rejects native saved function id tool call`() {
        val client = VLMClient()
        val result = client.parseVLMResponse(
            SceneChatCompletionTurn(
                parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                route = "scene.vlm.operation.primary",
                resolvedModel = "vlm-test-model",
                turn = ChatCompletionTurn(
                    message = ChatCompletionMessage(
                        role = "assistant",
                        toolCalls = listOf(
                            AssistantToolCall(
                                id = "call_1",
                                function = AssistantToolCallFunction(
                                    name = "oob_fn_vlm_task_41329798",
                                    arguments = """{"keyword":"猫猫"}"""
                                )
                            )
                        )
                    )
                )
            ),
            modelOrScene = "scene.vlm.operation.primary",
            dynamicFunctionToolNames = setOf("oob_fn_vlm_task_41329798")
        )

        assertFalse(result.success)
        assertTrue(result.step == null)
        assertTrue(result.error.orEmpty().contains("Function tool calls are handled by runtime recall"))
    }

    @Test
    fun `assistant completion text without native tool call is provider contract violation`() {
        val client = VLMClient()
        val result = client.parseVLMResponse(
            textOnlyTurn(
                content = """
                {
                  "observation": "The Contacts app is open and no pop-ups are visible.",
                  "thought": "The task has been completed successfully.",
                  "summary": "The Contacts app is open; no further actions are needed."
                }
                """.trimIndent()
            ),
            modelOrScene = "scene.vlm.operation.primary"
        )

        assertProviderToolCallViolation(result)
        assertTrue(result.error.orEmpty().contains("raw_content="))
    }

    @Test
    fun `text tool wrapper without native tool call is provider contract violation`() {
        val client = VLMClient()
        val result = client.parseVLMResponse(
            textOnlyTurn(
                content = """
                ```json
                {"tool_call":{"name":"open_app","arguments":{"package_name":"com.android.settings"}}}
                ```
                """.trimIndent()
            ),
            modelOrScene = "scene.vlm.operation.primary"
        )

        assertProviderToolCallViolation(result)
    }

    @Test
    fun `qwen function id wrapper for primitive action is provider contract violation`() {
        val client = VLMClient()
        val result = client.parseVLMResponse(
            textOnlyTurn(
                content = """{"tool_call":{"function_id":"click","args":{"x":500,"y":452}}}"""
            ),
            modelOrScene = "scene.vlm.operation.primary"
        )

        assertProviderToolCallViolation(result)
    }

    @Test
    fun `saved function id in vlm text output is provider contract violation`() {
        val client = VLMClient()
        val result = client.parseVLMResponse(
            textOnlyTurn(
                content = """{"tool_call":{"function_id":"oob_fn_vlm_task_41329798","arguments":{"x":500,"y":452}}}"""
            ),
            modelOrScene = "scene.vlm.operation.primary"
        )

        assertProviderToolCallViolation(result)
    }

    @Test
    fun `line style text tool call is provider contract violation`() {
        val client = VLMClient()
        val result = client.parseVLMResponse(
            textOnlyTurn(
                content = """
                {"observation":"The Contacts app is not visible.","thought":"Open the app drawer."}
                tool_call: swipe
                target_description: "swipe down to reveal more apps"
                direction: "down"
                x1: 500
                y1: 860
                x2: 500
                y2: 220
                """.trimIndent()
            ),
            modelOrScene = "scene.vlm.operation.primary"
        )

        assertProviderToolCallViolation(result)
    }

    @Test
    fun `call tool text output is provider contract violation before execution`() {
        val client = VLMClient()
        val result = client.parseVLMResponse(
            textOnlyTurn(
                content = """call_tool({"function_id":"xhs_search_keyword","arguments":{"keyword":"美食"}})"""
            ),
            modelOrScene = "scene.vlm.operation.primary"
        )

        assertProviderToolCallViolation(result)
    }

    @Test
    fun `openai tool action parser preserves indexed grounding fields`() {
        val client = VLMClient()
        val clickResult = client.parseVLMResponse(
            SceneChatCompletionTurn(
                parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                route = "scene.vlm.operation.primary",
                resolvedModel = "vlm-test-model",
                turn = ChatCompletionTurn(
                    message = ChatCompletionMessage(
                        role = "assistant",
                        toolCalls = listOf(
                            AssistantToolCall(
                                id = "call_1",
                                function = AssistantToolCallFunction(
                                    name = "click",
                                    arguments = """{"target_description":"Display","element_index":3,"x":500,"y":740}"""
                                )
                            )
                        )
                    )
                )
            ),
            modelOrScene = "scene.vlm.operation.primary"
        )

        assertTrue(clickResult.success)
        val click = requireNotNull(clickResult.step).action as ClickAction
        assertEquals("Display", click.targetDescription)


        val scrollResult = client.parseVLMResponse(
            SceneChatCompletionTurn(
                parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                route = "scene.vlm.operation.primary",
                resolvedModel = "vlm-test-model",
                turn = ChatCompletionTurn(
                    message = ChatCompletionMessage(
                        role = "assistant",
                        toolCalls = listOf(
                            AssistantToolCall(
                                id = "call_2",
                                function = AssistantToolCallFunction(
                                    name = "swipe",
                                    arguments = """{"target_description":"Settings list","scrollable_index":0,"direction":"down","x1":500,"y1":880,"x2":500,"y2":250}"""
                                )
                            )
                        )
                    )
                )
            ),
            modelOrScene = "scene.vlm.operation.primary"
        )

        assertTrue(scrollResult.success)
        val scroll = requireNotNull(scrollResult.step).action as SwipeAction
        assertEquals(0, scroll.scrollableIndex)
        assertEquals("down", scroll.direction)
    }

    companion object {
        private fun textOnlyTurn(content: String): SceneChatCompletionTurn =
            SceneChatCompletionTurn(
                parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                route = "scene.vlm.operation.primary",
                resolvedModel = "vlm-test-model",
                turn = ChatCompletionTurn(
                    finishReason = "stop",
                    message = ChatCompletionMessage(
                        role = "assistant",
                        content = JsonPrimitive(content)
                    )
                )
            )

        private fun assertProviderToolCallViolation(result: VLMResult) {
            assertFalse(result.success)
            assertTrue(result.step == null)
            assertTrue(result.error.orEmpty().contains("provider_tool_call_contract_violation"))
            assertFalse(result.shouldRetryForToolCall)
        }

        private fun dynamicFunctionToolDefinition(name: String) = buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", name)
                put("toolType", "oob_function")
                put("description", "Reusable Function")
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("tool_title", buildJsonObject {
                            put("type", "string")
                        })
                        put("keyword", buildJsonObject {
                            put("type", "string")
                        })
                    })
                    put("required", buildJsonArray {
                        add("tool_title")
                        add("keyword")
                    })
                })
            })
        }

        private const val BEFORE_XML =
            """
            <hierarchy>
              <node bounds="[0,0][720,1280]">
                <node text="Settings" bounds="[20,20][120,80]" clickable="true" />
              </node>
            </hierarchy>
            """

        private const val AFTER_XML =
            """
            <hierarchy>
              <node bounds="[0,0][720,1280]">
                <node text="Settings" bounds="[48,256][312,353]" />
                <node text="Network &amp; internet" bounds="[144,579][475,633]" clickable="true" />
              </node>
            </hierarchy>
            """
    }
}
