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
        assertTrue(envelope.dynamicFunctionToolNames.isEmpty())
        assertEquals(toolNames, envelope.toolNames)
        assertEquals("required", envelope.request.toolChoice!!.jsonPrimitive.contentOrNull)
        assertTrue(envelope.systemPromptChars > 0)
        assertTrue(envelope.currentUserTextChars > 0)
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
    fun `conversation tool result includes post action page observation`() {
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
        assertTrue(payload["result"].toString().contains("Network & internet"))
        assertFalse(payload.containsKey("state_delta"))
        assertFalse(payload.containsKey("continuation"))
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
        assertTrue(compactUser.contains("Post-action observation"))
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
    fun `openai tool action parser maps scroll tool call alias to swipe`() {
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

        assertTrue(result.error.orEmpty(), result.success)
        val action = requireNotNull(result.step).action as SwipeAction
        assertEquals("Settings list", action.targetDescription)
        assertEquals("down", action.direction)
        assertEquals(500f, action.x1, 0.01f)
        assertEquals(860f, action.y1, 0.01f)
        assertEquals(500f, action.x2, 0.01f)
        assertEquals(220f, action.y2, 0.01f)
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
    fun `text fallback parser maps explicit completion JSON to finished action`() {
        val client = VLMClient()
        val result = client.parseVLMResponse(
            SceneChatCompletionTurn(
                parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                route = "scene.vlm.operation.primary",
                resolvedModel = "qwen-vl-max",
                turn = ChatCompletionTurn(
                    message = ChatCompletionMessage(
                        role = "assistant",
                        content = JsonPrimitive(
                            """
                            {
                              "observation": "The Contacts app is open and no pop-ups are visible.",
                              "thought": "The task has been completed successfully.",
                              "summary": "The Contacts app is open; no further actions are needed."
                            }
                            """.trimIndent()
                        )
                    ),
                    finishReason = "stop"
                )
            ),
            modelOrScene = "scene.vlm.operation.primary"
        )

        assertTrue(result.success)
        val step = requireNotNull(result.step)
        assertTrue(step.action is FinishedAction)
        assertEquals("The Contacts app is open; no further actions are needed.", step.summary)
        assertFalse(result.shouldRetryForToolCall)
    }

    @Test
    fun `text fallback parser maps Chinese completion JSON to finished action`() {
        val client = VLMClient()
        val result = client.parseVLMResponse(
            SceneChatCompletionTurn(
                parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                route = "scene.vlm.operation.primary",
                resolvedModel = "qwen-vl-max",
                turn = ChatCompletionTurn(
                    message = ChatCompletionMessage(
                        role = "assistant",
                        content = JsonPrimitive(
                            """
                            {
                              "observation": "当前页面为设置应用主界面，已成功打开",
                              "thought": "用户任务是重新打开设置应用，当前页面已显示设置应用内容，任务完成",
                              "summary": "设置应用已成功打开，任务完成"
                            }
                            """.trimIndent()
                        )
                    ),
                    finishReason = "stop"
                )
            ),
            modelOrScene = "scene.vlm.operation.primary"
        )

        assertTrue(result.success)
        val step = requireNotNull(result.step)
        assertTrue(step.action is FinishedAction)
        assertEquals("设置应用已成功打开，任务完成", step.summary)
        assertFalse(result.shouldRetryForToolCall)
    }

    @Test
    fun `text fallback tool parser supports input_text`() {
        val client = VLMClient()
        val result = client.parseVLMResponse(
            SceneChatCompletionTurn(
                parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                route = "scene.vlm.operation.primary",
                resolvedModel = "vlm-test-model",
                turn = ChatCompletionTurn(
                    message = ChatCompletionMessage(
                        role = "assistant",
                        content = JsonPrimitive(
                            """{"name":"input_text","arguments":{"target_description":"Phone","text":"415-555-0130","x":480,"y":702}}"""
                        )
                    )
                )
            ),
            modelOrScene = "scene.vlm.operation.primary"
        )

        assertTrue(result.success)
        val action = requireNotNull(result.step).action as InputTextAction
        assertEquals("Phone", action.targetDescription)
        assertEquals("415-555-0130", action.text)
    }

    @Test
    fun `text fallback tool parser normalizes uppercase press key enum value`() {
        val client = VLMClient()
        val result = client.parseVLMResponse(
            SceneChatCompletionTurn(
                parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                route = "scene.vlm.operation.primary",
                resolvedModel = "qwen-vl-plus",
                turn = ChatCompletionTurn(
                    finishReason = "stop",
                    message = ChatCompletionMessage(
                        role = "assistant",
                        content = JsonPrimitive(
                            """{"name":"press_key","arguments":{"key":"Home"}}"""
                        )
                    )
                )
            ),
            modelOrScene = "scene.vlm.operation.primary"
        )

        assertTrue(result.error.orEmpty(), result.success)
        val action = requireNotNull(result.step).action as PressKeyAction
        assertEquals("home", action.key)
    }

    @Test
    fun `text fallback tool parser normalizes uppercase swipe direction enum value`() {
        val client = VLMClient()
        val result = client.parseVLMResponse(
            SceneChatCompletionTurn(
                parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                route = "scene.vlm.operation.primary",
                resolvedModel = "qwen-vl-plus",
                turn = ChatCompletionTurn(
                    finishReason = "stop",
                    message = ChatCompletionMessage(
                        role = "assistant",
                        content = JsonPrimitive(
                            """{"name":"swipe","arguments":{"target_description":"app drawer","direction":"Down","x1":500,"y1":860,"x2":500,"y2":220}}"""
                        )
                    )
                )
            ),
            modelOrScene = "scene.vlm.operation.primary"
        )

        assertTrue(result.error.orEmpty(), result.success)
        val action = requireNotNull(result.step).action as SwipeAction
        assertEquals("down", action.direction)
    }

    @Test
    fun `text fallback tool parser supports generic tool call wrapper`() {
        val client = VLMClient()
        val result = client.parseVLMResponse(
            SceneChatCompletionTurn(
                parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                route = "scene.vlm.operation.primary",
                resolvedModel = "qwen-vl-plus",
                turn = ChatCompletionTurn(
                    finishReason = "stop",
                    message = ChatCompletionMessage(
                        role = "assistant",
                        content = JsonPrimitive(
                            """
                            ```json
                            {"tool_call":{"name":"open_app","arguments":{"package_name":"com.android.settings"}}}
                            ```
                            """.trimIndent()
                        )
                    )
                )
            ),
            modelOrScene = "scene.vlm.operation.primary"
        )

        assertTrue(result.error.orEmpty(), result.success)
        val action = requireNotNull(result.step).action as OpenAppAction
        assertEquals("com.android.settings", action.packageName)
    }

    @Test
    fun `text fallback tool parser supports line style tool call from qwen vl`() {
        val client = VLMClient()
        val result = client.parseVLMResponse(
            SceneChatCompletionTurn(
                parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                route = "scene.vlm.operation.primary",
                resolvedModel = "qwen-vl-max",
                turn = ChatCompletionTurn(
                    finishReason = "stop",
                    message = ChatCompletionMessage(
                        role = "assistant",
                        content = JsonPrimitive(
                            """
                            {"observation":"The Contacts app is not visible.","thought":"Open the app drawer."}
                            tool_call: swipe
                            target_description: "swipe down to reveal more apps"
                            direction: "down"
                            x1: 500
                            y1: 860
                            x2: 500
                            y2: 220
                            """.trimIndent()
                        )
                    )
                )
            ),
            modelOrScene = "scene.vlm.operation.primary"
        )

        assertTrue(result.success)
        val action = requireNotNull(result.step).action as SwipeAction
        assertEquals("swipe down to reveal more apps", action.targetDescription)
        assertEquals("down", action.direction)
        assertEquals(500f, action.x1)
        assertEquals(860f, action.y1)
        assertEquals(500f, action.x2)
        assertEquals(220f, action.y2)
    }

    @Test
    fun `text fallback parser skips qwen tool title marker before function invocation`() {
        val client = VLMClient()
        val result = client.parseVLMResponse(
            SceneChatCompletionTurn(
                parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                route = "scene.vlm.operation.primary",
                resolvedModel = "qwen-vl-max",
                turn = ChatCompletionTurn(
                    finishReason = "stop",
                    message = ChatCompletionMessage(
                        role = "assistant",
                        content = JsonPrimitive(
                            """{"text":"tool_title: 继续 VLM 任务执行\n\nopen_app(package_name=\"com.android.settings\")","status":"success"}"""
                        )
                    )
                )
            ),
            modelOrScene = "scene.vlm.operation.primary"
        )

        assertTrue(result.error.orEmpty(), result.success)
        val action = requireNotNull(result.step).action as OpenAppAction
        assertEquals("com.android.settings", action.packageName)
    }

    @Test
    fun `text fallback tool parser normalizes generic action field`() {
        val client = VLMClient()
        val result = client.parseVLMResponse(
            SceneChatCompletionTurn(
                parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                route = "scene.vlm.operation.primary",
                resolvedModel = "vlm-test-model",
                turn = ChatCompletionTurn(
                    finishReason = "stop",
                    message = ChatCompletionMessage(
                        role = "assistant",
                        content = JsonPrimitive(
                            """{"action":"click","arguments":{"target_description":"Settings","x":480,"y":702}}"""
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
    fun `text fallback tool parser rejects inline call tool json`() {
        val client = VLMClient()
        val result = client.parseVLMResponse(
            SceneChatCompletionTurn(
                parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                route = "scene.vlm.operation.primary",
                resolvedModel = "vlm-test-model",
                turn = ChatCompletionTurn(
                    finishReason = "stop",
                    message = ChatCompletionMessage(
                        role = "assistant",
                        content = JsonPrimitive(
                            """{"tool":"call_tool","function_id":"xhs_search_keyword","arguments":{"keyword":"猫猫"}}"""
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
    fun `text fallback tool parser rejects call tool invocation syntax`() {
        val client = VLMClient()
        val result = client.parseVLMResponse(
            SceneChatCompletionTurn(
                parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                route = "scene.vlm.operation.primary",
                resolvedModel = "vlm-test-model",
                turn = ChatCompletionTurn(
                    finishReason = "stop",
                    message = ChatCompletionMessage(
                        role = "assistant",
                        content = JsonPrimitive(
                            """call_tool({"function_id":"xhs_search_keyword","arguments":{"keyword":"美食"}})"""
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
    fun `text fallback tool parser supports equals arguments in function invocation syntax`() {
        val client = VLMClient()
        val result = client.parseVLMResponse(
            SceneChatCompletionTurn(
                parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                route = "scene.vlm.operation.primary",
                resolvedModel = "qwen-vl-max",
                turn = ChatCompletionTurn(
                    finishReason = "stop",
                    message = ChatCompletionMessage(
                        role = "assistant",
                        content = JsonPrimitive(
                            """{"observation":"Home screen.","thought":"Open contacts directly."}
                            open_app(package_name="com.google.android.contacts")""".trimIndent()
                        )
                    )
                )
            ),
            modelOrScene = "scene.vlm.operation.primary"
        )

        assertTrue(result.success)
        val action = requireNotNull(result.step).action as OpenAppAction
        assertEquals("com.google.android.contacts", action.packageName)
    }

    @Test
    fun `text fallback tool parser supports bare qwen command line`() {
        val client = VLMClient()
        val result = client.parseVLMResponse(
            SceneChatCompletionTurn(
                parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                route = "scene.vlm.operation.primary",
                resolvedModel = "qwen-vl-max",
                turn = ChatCompletionTurn(
                    finishReason = "stop",
                    message = ChatCompletionMessage(
                        role = "assistant",
                        content = JsonPrimitive("open_app package_name=com.android.deskclock")
                    )
                )
            ),
            modelOrScene = "scene.vlm.operation.primary"
        )

        assertTrue(result.error.orEmpty(), result.success)
        val action = requireNotNull(result.step).action as OpenAppAction
        assertEquals("com.android.deskclock", action.packageName)
    }

    @Test
    fun `text fallback tool parser supports inline tool call arguments from qwen`() {
        val client = VLMClient()
        val result = client.parseVLMResponse(
            SceneChatCompletionTurn(
                parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                route = "scene.vlm.operation.primary",
                resolvedModel = "qwen-vl-max",
                turn = ChatCompletionTurn(
                    finishReason = "stop",
                    message = ChatCompletionMessage(
                        role = "assistant",
                        content = JsonPrimitive(
                            """
                            {"observation":"The Settings app is open.","thought":"Clicking on Network & internet will lead to Wi-Fi settings.","summary":"Navigating to the Network & internet section."}
                            tool_call: click x=208 y=451
                            """.trimIndent()
                        )
                    )
                )
            ),
            modelOrScene = "scene.vlm.operation.primary"
        )

        assertTrue(result.error.orEmpty(), result.success)
        val action = requireNotNull(result.step).action as ClickAction
        assertEquals(208f, action.x, 0.01f)
        assertEquals(451f, action.y, 0.01f)
        assertTrue(action.targetDescription.contains("Network"))
    }

    @Test
    fun `text fallback tool parser supports compact qwen adjacent tool call arguments`() {
        val client = VLMClient()
        val result = client.parseVLMResponse(
            SceneChatCompletionTurn(
                parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                route = "scene.vlm.operation.primary",
                resolvedModel = "qwen-vl-max",
                turn = ChatCompletionTurn(
                    finishReason = "stop",
                    message = ChatCompletionMessage(
                        role = "assistant",
                        content = JsonPrimitive(
                            """{"observation":"Home screen.","thought":"Need to find Clock.","summary":"Need to open Clock."}tool_call:scrolltarget_description:"scrolldowntorevealmoreapps"x1:500y1:860x2:500y2:220"""
                        )
                    )
                )
            ),
            modelOrScene = "scene.vlm.operation.primary"
        )

        assertTrue(result.error.orEmpty(), result.success)
        val action = requireNotNull(result.step).action as SwipeAction
        assertEquals("scrolldowntorevealmoreapps", action.targetDescription)
        assertEquals(500f, action.x1, 0.01f)
        assertEquals(860f, action.y1, 0.01f)
        assertEquals(500f, action.x2, 0.01f)
        assertEquals(220f, action.y2, 0.01f)
    }

    @Test
    fun `text fallback tool parser supports qwen named tool with json arguments`() {
        val client = VLMClient()
        val result = client.parseVLMResponse(
            SceneChatCompletionTurn(
                parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                route = "scene.vlm.operation.primary",
                resolvedModel = "qwen-vl-max",
                turn = ChatCompletionTurn(
                    finishReason = "stop",
                    message = ChatCompletionMessage(
                        role = "assistant",
                        content = JsonPrimitive(
                            """
                            {"observation":"Need home.","thought":"Go home.","summary":"Open from launcher."}
                            tool_call: press_key {"key": "home"}
                            """.trimIndent()
                        )
                    )
                )
            ),
            modelOrScene = "scene.vlm.operation.primary"
        )

        assertTrue(result.error.orEmpty(), result.success)
        val action = requireNotNull(result.step).action as PressKeyAction
        assertEquals("home", action.key)
    }

    @Test
    fun `text fallback tool parser rejects qwen call tool marker`() {
        val client = VLMClient()
        val result = client.parseVLMResponse(
            SceneChatCompletionTurn(
                parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                route = "scene.vlm.operation.primary",
                resolvedModel = "qwen-vl-max",
                turn = ChatCompletionTurn(
                    finishReason = "stop",
                    message = ChatCompletionMessage(
                        role = "assistant",
                        content = JsonPrimitive(
                            """
                            {"observation":"Need Settings.","thought":"Use recalled function.","summary":"Call reusable function."}
                            tool_call: {"name": "call_tool", "arguments": {"function_id": "oob_cmd_vlm_task_582f9485", "arguments": {}}}
                            """.trimIndent()
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
    fun `text fallback tool parser supports bare colon command with json arguments`() {
        val client = VLMClient()
        val result = client.parseVLMResponse(
            SceneChatCompletionTurn(
                parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
                route = "scene.vlm.operation.primary",
                resolvedModel = "qwen-vl-max",
                turn = ChatCompletionTurn(
                    finishReason = "stop",
                    message = ChatCompletionMessage(
                        role = "assistant",
                        content = JsonPrimitive(
                            """
                            {"observation":"Calendar is not visible.","thought":"Open Simple Calendar.","summary":"Starting calendar task."}
                            open_app: {"package_name": "com.simplemobiletools.calendar"}
                            """.trimIndent()
                        )
                    )
                )
            ),
            modelOrScene = "scene.vlm.operation.primary"
        )

        assertTrue(result.error.orEmpty(), result.success)
        val action = requireNotNull(result.step).action as OpenAppAction
        assertEquals("com.simplemobiletools.calendar", action.packageName)
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
