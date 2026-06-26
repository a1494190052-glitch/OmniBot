package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionStreamOptions
import cn.com.omnimind.baselib.llm.ChatCompletionThinking
import cn.com.omnimind.baselib.llm.ModelSceneRegistry
import cn.com.omnimind.baselib.llm.contentText
import cn.com.omnimind.baselib.runlog.OobActionSchema
import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class VLMClient(
    private val systemPromptBuilder: (sceneId: String) -> String = { sceneId ->
        PromptTemplate.buildSystemPrompt(sceneId = sceneId)
    },
    private val turnPromptBuilder: (context: UIContext, sceneId: String) -> String = { context, sceneId ->
        PromptTemplate.buildTurnUserPrompt(context, sceneId = sceneId)
    }
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    fun buildUIOperationRequest(
        context: UIContext,
        screenshot: String?,
        markedScreenshot: String? = null,
        conversationState: VLMConversationState,
        model: String = VLMRuntimeConfigRegistry.get().primarySceneId,
        retryState: VLMToolCallRetryState? = null,
        includeMarkedScreenshot: Boolean = false
    ): VLMRequestEnvelope {
        val runtimeConfig = runtimeConfig()
        val sceneId = resolveVlmSceneId(model)
        val modelOverride = resolveVlmModelOverride(model)
        val hiddenDynamicFunctionToolNames = VLMToolDefinitions
            .dynamicFunctionToolNamesFromDefinitions(context.dynamicToolDefinitions)
        val dynamicFunctionToolMappings = VLMToolDefinitions
            .dynamicFunctionToolMappingsFromDefinitions(context.dynamicToolDefinitions)
        val dynamicFunctionToolNames = hiddenDynamicFunctionToolNames + dynamicFunctionToolMappings.keys
        val selectedBaseToolNames = VLMAllowedToolSelector.select(context)
        val selectedPromptToolNames = selectedBaseToolNames + dynamicFunctionToolMappings.keys
        val promptContext = context
            .withDynamicFunctionCallToolGuidance(dynamicFunctionToolNames)
            .copy(allowedVlmToolNames = selectedPromptToolNames.toList())
        val systemPrompt = systemPromptBuilder(sceneId)
        val currentUserText = turnPromptBuilder(promptContext, sceneId)
        val historyMessages = conversationState.historyMessages()
        val effectiveMarkedScreenshot = markedScreenshot.takeIf { includeMarkedScreenshot }
        val messages = buildMessages(
            systemPrompt = systemPrompt,
            historyMessages = historyMessages,
            currentUserText = currentUserText,
            screenshot = screenshot,
            markedScreenshot = effectiveMarkedScreenshot,
            context = promptContext,
            retryState = retryState
        )
        val imageCount = listOf(screenshot, effectiveMarkedScreenshot).count { !it.isNullOrBlank() }
        val baseTools = VLMToolDefinitions.tools(allowedToolNames = selectedBaseToolNames)
        val dynamicTools = VLMToolDefinitions
            .dynamicToolsFromDefinitions(promptContext.dynamicToolDefinitions)
            .filterNot { it.function.name in hiddenDynamicFunctionToolNames }
        val tools = (baseTools + dynamicTools).distinctBy { it.function.name }
        val defaultToolCount = VLMToolDefinitions.tools().size

        OmniLog.i(
            TAG,
            "buildUIOperationRequest scene=$model historyRounds=${conversationState.roundCount()} historyMessages=${historyMessages.size} totalMessages=${messages.size} currentImages=$imageCount visualPolicy=screenshot+compact_indexed_evidence marked=${includeMarkedScreenshot && !markedScreenshot.isNullOrBlank()} retry=${retryState?.retryIndex ?: 0} tools=${tools.size}/$defaultToolCount recalledTools=${dynamicFunctionToolNames.size}"
        )

        return VLMRequestEnvelope(
            request = ChatCompletionRequest(
                model = sceneId,
                modelOverride = modelOverride,
                messages = messages,
                maxCompletionTokens = runtimeConfig.maxCompletionTokens,
                temperature = runtimeConfig.temperature,
                stream = true,
                streamOptions = ChatCompletionStreamOptions(includeUsage = true),
                tools = tools,
                toolChoice = JsonPrimitive("required"),
                parallelToolCalls = false,
                enableThinking = false,
                reasoningEffort = "none",
                thinking = ChatCompletionThinking(type = "disabled"),
            ),
            currentUserText = currentUserText,
            dynamicFunctionToolNames = dynamicFunctionToolNames,
            dynamicFunctionToolMappings = dynamicFunctionToolMappings,
            toolNames = tools.map { it.function.name },
            defaultToolCount = defaultToolCount,
            selectedBaseToolNames = selectedBaseToolNames,
            systemPromptChars = systemPrompt.length,
            currentUserTextChars = currentUserText.length,
        )
    }

    private fun UIContext.withDynamicFunctionCallToolGuidance(functionNames: Set<String>): UIContext {
        if (functionNames.isEmpty()) return this
        val hint = "Recalled Functions for this turn are handled by the local runtime. " +
            "You may call a recalled workflow tool if it appears in tools[] and clearly matches the current step. " +
            "Do not emit call_tool, function_id, or raw Function ids."
        val mergedGuidance = listOf(stepSkillGuidance.trim(), hint)
            .filter(String::isNotBlank)
            .joinToString("\n\n")
        return copy(stepSkillGuidance = mergedGuidance)
    }

    fun parseVLMResponse(
        response: SceneChatCompletionTurn,
        modelOrScene: String,
        dynamicFunctionToolNames: Set<String> = emptySet(),
        dynamicFunctionToolMappings: Map<String, String> = emptyMap(),
    ): VLMResult {
        return when (response.parser) {
            ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS ->
                parseToolActionResponse(response, dynamicFunctionToolNames, dynamicFunctionToolMappings)
            ModelSceneRegistry.ResponseParser.JSON_CONTENT ->
                VLMResult(false, null, "主 VLM parser 不支持 JSON_CONTENT: $modelOrScene")
            ModelSceneRegistry.ResponseParser.TEXT_CONTENT ->
                VLMResult(false, null, "主 VLM parser 不支持 TEXT_CONTENT: $modelOrScene")
        }
    }

    fun resolveVlmSceneId(modelOrScene: String?): String {
        val runtimeConfig = runtimeConfig()
        val normalized = modelOrScene?.trim().orEmpty()
        return if (isSceneId(normalized)) {
            normalized
        } else {
            runtimeConfig.primarySceneId
        }
    }

    fun resolveVlmModelOverride(modelOrScene: String?): String? {
        val normalized = modelOrScene?.trim().orEmpty()
        return normalized.takeIf {
            it.isNotEmpty() && !isSceneId(it)
        }
    }

    private fun isSceneId(value: String): Boolean {
        return value.startsWith("scene.")
    }

    fun buildConversationRound(
        currentUserText: String,
        assistantTurn: SceneChatCompletionTurn,
        executedStep: UIStep
    ): VLMConversationRound {
        val assistantMessage = ChatCompletionMessage(
            role = "assistant",
            content = assistantTurn.turn.message.content,
            toolCalls = assistantTurn.turn.message.toolCalls
        )
        val toolCallId = assistantTurn.turn.message.toolCalls?.firstOrNull()?.id.orEmpty()
        val success = !(executedStep.result?.startsWith(ACTION_FAILURE_PREFIX) == true)
        val toolPayload = buildJsonObject {
            put("success", JsonPrimitive(success))
            put("result", JsonPrimitive(compactToolResult(executedStep)))
        }.toString()
        return VLMConversationRound(
            userMessage = ChatCompletionMessage(
                role = "user",
                content = JsonPrimitive(buildCompactHistoryUserMessage(currentUserText, executedStep))
            ),
            assistantMessage = assistantMessage,
            toolMessage = ChatCompletionMessage(
                role = "tool",
                content = JsonPrimitive(toolPayload),
                toolCallId = toolCallId.ifBlank { null }
            )
        )
    }

    private fun compactToolResult(executedStep: UIStep): String {
        val parts = buildList {
            executedStep.result?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
            executedStep.summary.trim().takeIf { it.isNotEmpty() }?.let { add("summary=$it") }
        }
        return parts.joinToString("; ")
            .ifBlank { "no result details" }
            .take(runtimeConfig().maxToolResultChars)
    }

    private fun sanitizeModelVisibleJson(value: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement {
        return when (value) {
            is JsonObject -> buildJsonObject {
                value.forEach { (key, child) ->
                    val normalizedKey = key.trim().lowercase()
                    if (isRawXmlKey(normalizedKey)) {
                        put("${key}_chars", JsonPrimitive(rawXmlCharCount(child)))
                        put("${key}_model_visible", JsonPrimitive(false))
                    } else {
                        put(key, sanitizeModelVisibleJson(child))
                    }
                }
            }
            is JsonArray -> buildJsonArray {
                value.forEach { add(sanitizeModelVisibleJson(it)) }
            }
            is JsonPrimitive -> {
                val text = value.contentOrNull
                if (text != null && looksLikeRawXml(text)) {
                    JsonPrimitive(
                        "raw_xml_omitted(chars=${text.length}, model_visible=false)"
                    )
                } else {
                    value
                }
            }
        }
    }

    private fun isRawXmlKey(normalizedKey: String): Boolean {
        return normalizedKey == "xml" ||
            normalizedKey == "current_xml" ||
            normalizedKey == "observation_xml" ||
            normalizedKey == "before_xml" ||
            normalizedKey == "after_xml" ||
            normalizedKey.endsWith("_xml")
    }

    private fun rawXmlCharCount(value: kotlinx.serialization.json.JsonElement): Int {
        return (value as? JsonPrimitive)?.contentOrNull?.length ?: value.toString().length
    }

    private fun looksLikeRawXml(text: String): Boolean {
        val trimmed = text.trimStart()
        return trimmed.startsWith("<hierarchy") ||
            trimmed.startsWith("<node") ||
            trimmed.contains("<node ")
    }

    internal fun buildCompactHistoryUserMessage(currentUserText: String, executedStep: UIStep): String {
        val runtimeConfig = runtimeConfig()
        val actionSummary = when (val action = executedStep.action) {
            is ClickAction -> "click ${action.targetDescription} @(${action.x},${action.y})"
            is InputTextAction -> "input_text ${action.targetDescription} @(${action.x},${action.y})"
            is SwipeAction -> "swipe ${action.targetDescription} ${action.direction.orEmpty()} @(${action.x1},${action.y1})->(${action.x2},${action.y2})"
            is LongPressAction -> "long_press ${action.targetDescription} @(${action.x},${action.y})"
            is OpenAppAction -> "open_app ${action.packageName}"
            is PressKeyAction -> "press_key ${action.key}"
            is GetStateAction -> "get_state ${action.reason.take(runtimeConfig.maxHistoryActionChars)}"
            is FunctionRunAction -> "${action.functionId} ${action.arguments.toString().take(runtimeConfig.maxHistoryActionChars)}"
            is FinishedAction -> "finished"
            is InfoAction -> "info"
            is AbortAction -> "abort"
            is WaitAction -> "wait"
            is RecordAction -> "record"
        }.take(runtimeConfig.maxHistoryActionChars)
        return buildString {
            append("Previous turn compact context. ")
            append("Do not use this as current page evidence; use the latest user message and screenshot for grounding. ")
            append("Prior action: ")
            append(actionSummary)
            executedStep.result?.trim()?.takeIf { it.isNotEmpty() }?.let {
                append(". Result: ")
                append(it.take(runtimeConfig.maxHistoryResultChars))
            }
            if (currentUserText.contains("用户任务") || currentUserText.contains("User task")) {
                append(". The full previous prompt was intentionally compacted to control tokens.")
            }
        }
    }

    private fun parseToolActionResponse(
        response: SceneChatCompletionTurn,
        dynamicFunctionToolNames: Set<String>,
        dynamicFunctionToolMappings: Map<String, String>,
    ): VLMResult {
        val content = response.turn.message.contentText()
        val metadata = parseStepMetadata(content, response.turn.reasoning)
        val thinking = buildThinkingContext(
            content = content,
            reasoning = response.turn.reasoning,
            finishReason = response.turn.finishReason,
            metadata = metadata
        )
        val toolCalls = response.turn.message.toolCalls.orEmpty()
        if (toolCalls.isEmpty()) {
            return VLMResult(
                success = false,
                step = null,
                error = buildMissingToolCallMessage(response.turn.finishReason, thinking),
                thinking = thinking
            )
        }
        if (toolCalls.size > 1) {
            return VLMResult(
                success = false,
                step = null,
                error = "主 VLM 每轮只能返回一个 tool_call，实际收到 ${toolCalls.size} 个"
            )
        }

        return parseSingleToolCall(
            toolCall = toolCalls.first(),
            metadata = metadata,
            thinking = thinking,
            reasoning = response.turn.reasoning,
            dynamicFunctionToolNames = dynamicFunctionToolNames,
            dynamicFunctionToolMappings = dynamicFunctionToolMappings,
        )
    }

    private fun parseSingleToolCall(
        toolCall: AssistantToolCall,
        metadata: StepMetadataPayload,
        thinking: VLMThinkingContext,
        reasoning: String,
        dynamicFunctionToolNames: Set<String>,
        dynamicFunctionToolMappings: Map<String, String>,
    ): VLMResult {
        return try {
            val action = parseActionFromToolCall(
                toolCall = toolCall,
                dynamicFunctionToolNames = dynamicFunctionToolNames,
                dynamicFunctionToolMappings = dynamicFunctionToolMappings,
            )
            val thought = metadataThoughtFallback(
                metadata = metadata,
                reasoning = reasoning
            )
            VLMResult(
                success = true,
                step = UIStep(
                    observation = metadata.observation,
                    thought = thought,
                    action = action,
                    summary = metadata.summary
                ),
                error = null,
                thinking = thinking
            )
        } catch (e: Exception) {
            VLMResult(
                success = false,
                step = null,
                error = "Failed to parse native tool_call response: ${e.message}",
                thinking = thinking
            )
        }
    }

    private fun metadataThoughtFallback(
        metadata: StepMetadataPayload,
        reasoning: String
    ): String {
        return metadata.thought.ifBlank {
            reasoning.ifBlank {
                ""
            }
        }
    }

    private fun buildMessages(
        systemPrompt: String,
        historyMessages: List<ChatCompletionMessage>,
        currentUserText: String,
        screenshot: String?,
        markedScreenshot: String?,
        context: UIContext,
        retryState: VLMToolCallRetryState?
    ): List<ChatCompletionMessage> {
        val messages = mutableListOf<ChatCompletionMessage>()
        messages += ChatCompletionMessage(
            role = "system",
            content = JsonPrimitive(systemPrompt)
        )
        messages += historyMessages
        messages += buildCurrentUserMessage(currentUserText, screenshot, markedScreenshot)

        if (retryState != null) {
            buildRetryAssistantContent(retryState.thinking)?.let { assistantContent ->
                messages += ChatCompletionMessage(
                    role = "assistant",
                    content = JsonPrimitive(assistantContent)
                )
            }
            messages += ChatCompletionMessage(
                role = "user",
                content = JsonPrimitive(PromptTemplate.buildToolCallRetryPrompt(context, retryState))
            )
        }
        return messages
    }

    private fun buildCurrentUserMessage(
        currentUserText: String,
        screenshot: String?,
        markedScreenshot: String?
    ): ChatCompletionMessage {
        return ChatCompletionMessage(
            role = "user",
            content = buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", JsonPrimitive("text"))
                        put("text", JsonPrimitive(currentUserText))
                    }
                )
                if (!screenshot.isNullOrBlank()) {
                    add(
                        buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive("Current screenshot."))
                        }
                    )
                    add(buildImageContent(screenshot))
                }
                if (!markedScreenshot.isNullOrBlank()) {
                    add(
                        buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive("Marked screenshot with indexes matching OOB indexed page evidence."))
                        }
                    )
                    add(buildImageContent(markedScreenshot))
                }
            }
        )
    }

    private fun buildRetryAssistantContent(thinking: VLMThinkingContext): String? {
        val content = thinking.rawContent.trim()
        if (content.isNotEmpty()) {
            return content
        }

        val fallback = buildList {
            thinking.observation.takeIf { it.isNotBlank() }?.let { add("observation: $it") }
            thinking.thought.takeIf { it.isNotBlank() }?.let { add("thought: $it") }
            thinking.summary.takeIf { it.isNotBlank() }?.let { add("summary: $it") }
        }.joinToString(separator = "\n")

        return fallback.takeIf { it.isNotBlank() }
    }

    private fun buildThinkingContext(
        content: String,
        reasoning: String,
        finishReason: String?,
        metadata: StepMetadataPayload
    ): VLMThinkingContext {
        return VLMThinkingContext(
            observation = metadata.observation.trim(),
            thought = metadata.thought.trim().ifBlank { reasoning.trim() },
            summary = metadata.summary.trim(),
            reasoning = reasoning.trim(),
            rawContent = content.trim(),
            finishReason = finishReason?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    private fun buildMissingToolCallMessage(
        finishReason: String?,
        thinking: VLMThinkingContext
    ): String {
        val suffix = finishReason?.takeIf { it.isNotBlank() }?.let { "（finish_reason=$it）" }.orEmpty()
        val rawPreview = thinking.rawContent
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(240)
            .takeIf { it.isNotEmpty() }
            ?.let { " raw_content=$it" }
            .orEmpty()
        return "provider_tool_call_contract_violation: provider returned no native tool_calls$suffix.$rawPreview"
    }

    private fun parseStepMetadata(content: String, reasoning: String): StepMetadataPayload {
        val normalized = content.trim()
        if (normalized.isEmpty()) {
            return StepMetadataPayload(thought = reasoning)
        }
        return runCatching {
            val jsonStart = normalized.indexOf('{')
            val jsonEnd = normalized.lastIndexOf('}')
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                json.decodeFromString<StepMetadataPayload>(normalized.substring(jsonStart, jsonEnd + 1))
            } else {
                StepMetadataPayload(summary = normalized)
            }
        }.getOrElse {
            StepMetadataPayload(summary = normalized)
        }
    }

    private fun parseActionFromToolCall(
        toolCall: AssistantToolCall,
        dynamicFunctionToolNames: Set<String>,
        dynamicFunctionToolMappings: Map<String, String>,
    ): UIAction {
        val dynamicAction = functionActionFromDynamicToolCall(toolCall, dynamicFunctionToolMappings)
        if (dynamicAction != null) return dynamicAction
        return parseToolCall(toolCall, dynamicFunctionToolNames)
    }

    private fun parseToolCall(
        toolCall: AssistantToolCall,
        dynamicFunctionToolNames: Set<String>
    ): UIAction {
        val rawToolName = toolCall.function.name.trim()
        if (rawToolName.isBlank()) {
            throw IllegalArgumentException("Missing tool_call function name")
        }
        if (isInternalRuntimeToolName(rawToolName)) {
            throw IllegalArgumentException(INTERNAL_CALL_TOOL_ERROR)
        }
        if (rawToolName in dynamicFunctionToolNames) {
            throw IllegalArgumentException("Unknown recalled workflow tool mapping: $rawToolName")
        }
        if (rawToolName !in modelVisibleToolNames()) {
            throw IllegalArgumentException("Unsupported tool call: ${toolCall.function.name}")
        }
        val toolName = rawToolName
        val args = parseArguments(toolName, toolCall.function.arguments)
        return when (toolName) {
            OobActionSchema.TOOL_CLICK -> ClickAction(
                targetDescription = requireString(args, OobActionSchema.ARG_TARGET_DESCRIPTION),
                x = requireFloat(args, OobActionSchema.ARG_X),
                y = requireFloat(args, OobActionSchema.ARG_Y),
                nodeId = optionalString(args, OobActionSchema.ARG_NODE_ID)
            )
            OobActionSchema.TOOL_INPUT_TEXT -> InputTextAction(
                targetDescription = requireString(args, OobActionSchema.ARG_TARGET_DESCRIPTION),
                text = requireString(args, OobActionSchema.ARG_TEXT),
                x = requireFloat(args, OobActionSchema.ARG_X),
                y = requireFloat(args, OobActionSchema.ARG_Y),
                nodeId = optionalString(args, OobActionSchema.ARG_NODE_ID)
            )
            OobActionSchema.TOOL_SWIPE -> SwipeAction(
                targetDescription = requireString(args, OobActionSchema.ARG_TARGET_DESCRIPTION),
                x1 = requireFloat(args, OobActionSchema.ARG_X1),
                y1 = requireFloat(args, OobActionSchema.ARG_Y1),
                x2 = requireFloat(args, OobActionSchema.ARG_X2),
                y2 = requireFloat(args, OobActionSchema.ARG_Y2),
                durationMs = optionalLong(args, OobActionSchema.ARG_DURATION_MS) ?: 1500L,
                scrollableIndex = optionalInt(args, OobActionSchema.ARG_SCROLLABLE_INDEX),
                direction = optionalString(args, OobActionSchema.ARG_DIRECTION)?.lowercase()
            )
            OobActionSchema.TOOL_LONG_PRESS -> LongPressAction(
                targetDescription = requireString(args, OobActionSchema.ARG_TARGET_DESCRIPTION),
                x = requireFloat(args, OobActionSchema.ARG_X),
                y = requireFloat(args, OobActionSchema.ARG_Y),
                nodeId = optionalString(args, OobActionSchema.ARG_NODE_ID)
            )
            OobActionSchema.TOOL_OPEN_APP -> OpenAppAction(
                packageName = requireString(args, OobActionSchema.ARG_PACKAGE_NAME)
            )
            OobActionSchema.TOOL_PRESS_KEY -> PressKeyAction(
                key = requireString(args, OobActionSchema.ARG_KEY).lowercase()
            )
            OobActionSchema.TOOL_WAIT -> WaitAction(
                timeS = optionalDouble(args, OobActionSchema.ARG_TIME_S),
                durationMs = optionalLong(args, OobActionSchema.ARG_DURATION_MS)
            )
            OobActionSchema.TOOL_GET_STATE -> GetStateAction(
                reason = optionalString(args, OobActionSchema.ARG_REASON).orEmpty()
            )
            OobActionSchema.TOOL_CALL_TOOL -> throw IllegalArgumentException(INTERNAL_CALL_TOOL_ERROR)
            OobActionSchema.TOOL_FINISHED -> FinishedAction(
                content = optionalString(args, OobActionSchema.ARG_CONTENT).orEmpty()
            )
            OobActionSchema.TOOL_INFO -> InfoAction(
                value = requireString(args, OobActionSchema.ARG_VALUE)
            )
            OobActionSchema.TOOL_FEEDBACK -> AbortAction(
                value = optionalString(args, OobActionSchema.ARG_VALUE).orEmpty()
            )
            OobActionSchema.TOOL_ABORT -> AbortAction(
                value = optionalString(args, OobActionSchema.ARG_VALUE).orEmpty()
            )
            OobActionSchema.TOOL_REQUIRE_USER_CHOICE -> InfoAction(
                value = buildString {
                    val prompt = optionalString(args, OobActionSchema.ARG_PROMPT).orEmpty()
                    val options = (args[OobActionSchema.ARG_OPTIONS] as? JsonArray)
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
                        .orEmpty()
                    append(prompt)
                    if (options.isNotEmpty()) append("\n可选项：${options.joinToString(" / ")}")
                }
            )
            OobActionSchema.TOOL_REQUIRE_USER_CONFIRMATION -> InfoAction(
                value = optionalString(args, OobActionSchema.ARG_PROMPT).orEmpty()
            )
            else -> throw IllegalArgumentException("Unsupported canonical action: $toolName")
        }
    }

    private fun functionActionFromDynamicToolCall(
        toolCall: AssistantToolCall,
        dynamicFunctionToolMappings: Map<String, String>,
    ): FunctionRunAction? {
        val rawToolName = toolCall.function.name.trim()
        val functionId = dynamicFunctionToolMappings[rawToolName] ?: return null
        val arguments = parseDynamicFunctionArguments(rawToolName, toolCall.function.arguments)
        return FunctionRunAction(
            functionId = functionId,
            toolName = rawToolName,
            arguments = arguments,
        )
    }

    private fun parseDynamicFunctionArguments(toolName: String, rawArguments: String): JsonObject {
        val normalized = rawArguments.trim()
        if (normalized.isEmpty()) return JsonObject(emptyMap())
        val parsed = runCatching { json.parseToJsonElement(normalized) as? JsonObject }
            .getOrElse { error ->
                throw IllegalArgumentException(
                    "Invalid tool arguments JSON for $toolName: ${error.message ?: "unknown parse failure"}",
                    error,
                )
            } ?: throw IllegalArgumentException("Invalid tool arguments JSON for $toolName: function.arguments must be a JSON object")
        return JsonObject(parsed.filterKeys { key ->
            !key.equals("tool_title", ignoreCase = true) &&
                !key.equals("toolTitle", ignoreCase = true) &&
                !key.equals("function_id", ignoreCase = true) &&
                !key.equals("functionId", ignoreCase = true)
        })
    }

    private fun parseArguments(toolName: String, rawArguments: String): JsonObject {
        return VLMToolArgumentParser.parse(toolName, rawArguments)
    }

    private fun isInternalRuntimeToolName(name: String): Boolean {
        val normalized = name.trim()
            .removePrefix("functions.")
            .removePrefix("function.")
            .trim()
            .lowercase()
        return normalized == OobActionSchema.TOOL_CALL_TOOL
    }

    private fun modelVisibleToolNames(): Set<String> =
        OobActionSchema.modelVisibleTools.mapTo(linkedSetOf()) { it.name }.apply {
            remove(OobActionSchema.TOOL_GET_STATE)
            remove(OobActionSchema.TOOL_CALL_TOOL)
        }

    private fun requireString(obj: JsonObject, key: String): String {
        return obj[key]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Missing or empty '$key'")
    }

    private fun optionalString(obj: JsonObject, key: String): String? {
        return obj[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun requireFloat(obj: JsonObject, key: String): Float {
        return obj[key]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()
            ?: throw IllegalArgumentException("Missing or invalid '$key'")
    }

    private fun optionalLong(obj: JsonObject, key: String): Long? {
        val raw = obj[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        return raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong()
    }

    private fun optionalDouble(obj: JsonObject, key: String): Double? {
        return obj[key]?.jsonPrimitive?.contentOrNull?.trim()?.toDoubleOrNull()
    }

    private fun optionalInt(obj: JsonObject, key: String): Int? {
        val raw = obj[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        return raw.toIntOrNull() ?: raw.toDoubleOrNull()?.toInt()
    }

    private fun requireStringList(obj: JsonObject, key: String): List<String> {
        val raw = obj[key] ?: throw IllegalArgumentException("Missing '$key'")
        return when (raw) {
            is JsonArray -> raw.mapNotNull {
                it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
            }
            else -> throw IllegalArgumentException("Field '$key' must be an array of strings")
        }.ifEmpty {
            throw IllegalArgumentException("Field '$key' must contain at least one option")
        }
    }

    private fun buildImageContent(rawImage: String): JsonObject {
        val imageUrl = if (
            rawImage.startsWith("http://", ignoreCase = true) ||
            rawImage.startsWith("https://", ignoreCase = true) ||
            rawImage.startsWith("data:", ignoreCase = true)
        ) {
            rawImage
        } else {
            "data:image/png;base64,$rawImage"
        }
        return buildJsonObject {
            put("type", JsonPrimitive("image_url"))
            put(
                "image_url",
                buildJsonObject {
                    put("url", JsonPrimitive(imageUrl))
                }
            )
        }
    }

    private fun runtimeConfig(): VLMRuntimeConfig = VLMRuntimeConfigRegistry.get()

    private companion object {
        private const val TAG = "VLMClient"
        private const val INTERNAL_CALL_TOOL_ERROR =
            "call_tool is an internal runtime action and cannot be emitted by the VLM"
    }
}
