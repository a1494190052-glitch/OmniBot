package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionStreamOptions
import cn.com.omnimind.baselib.llm.ChatCompletionThinking
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import cn.com.omnimind.baselib.llm.ModelSceneRegistry
import cn.com.omnimind.baselib.llm.contentText
import cn.com.omnimind.baselib.runlog.OobActionSchema
import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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
    private val turnPromptBuilder: (
        context: UIContext,
        runLogSteps: List<Map<String, Any?>>,
        sceneId: String,
    ) -> String = { context, runLogSteps, sceneId ->
        PromptTemplate.buildTurnUserPrompt(
            context = context,
            sceneId = sceneId,
            runLogSteps = runLogSteps,
        )
    },
    private val requestLogger: (String) -> Unit = { message -> OmniLog.i(TAG, message) },
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
        runLogSteps: List<Map<String, Any?>> = emptyList(),
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
        val dynamicFunctionRequiredArguments = VLMToolDefinitions
            .dynamicFunctionRequiredArgumentsFromDefinitions(context.dynamicToolDefinitions)
        val dynamicFunctionToolNames = hiddenDynamicFunctionToolNames + dynamicFunctionToolMappings.keys
        val selectedBaseToolNames = VLMAllowedToolSelector.select(context)
        val selectedPromptToolNames = selectedBaseToolNames + dynamicFunctionToolMappings.keys
        val promptContext = context
            .withDynamicFunctionCallToolGuidance(dynamicFunctionToolNames)
            .copy(allowedVlmToolNames = selectedPromptToolNames.toList())
        val systemPrompt = systemPromptBuilder(sceneId)
        val currentUserText = turnPromptBuilder(promptContext, runLogSteps, sceneId)
        val effectiveMarkedScreenshot = markedScreenshot.takeIf { includeMarkedScreenshot }
        val messages = buildMessages(
            systemPrompt = systemPrompt,
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
        val tools = (dynamicTools + baseTools).distinctBy { it.function.name }
        val defaultToolCount = VLMToolDefinitions.tools().size

        requestLogger(
            "buildUIOperationRequest scene=$model runLogSteps=${runLogSteps.size} totalMessages=${messages.size} currentImages=$imageCount visualPolicy=current_screenshot+runlog_action_summary marked=${includeMarkedScreenshot && !markedScreenshot.isNullOrBlank()} retry=${retryState?.retryIndex ?: 0} tools=${tools.size}/$defaultToolCount recalledTools=${dynamicFunctionToolNames.size}"
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
            dynamicFunctionRequiredArguments = dynamicFunctionRequiredArguments,
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
            "Prefer a recalled workflow tool over manual UI actions when it clearly matches the current step goal. " +
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
        dynamicFunctionRequiredArguments: Map<String, Set<String>> = emptyMap(),
    ): VLMResult {
        return when (response.parser) {
            ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS ->
                parseToolActionResponse(
                    response = response,
                    dynamicFunctionToolNames = dynamicFunctionToolNames,
                    dynamicFunctionToolMappings = dynamicFunctionToolMappings,
                    dynamicFunctionRequiredArguments = dynamicFunctionRequiredArguments,
                )
            ModelSceneRegistry.ResponseParser.JSON_CONTENT ->
                VLMResult(false, null, "主 VLM parser 不支持 JSON_CONTENT: $modelOrScene")
            ModelSceneRegistry.ResponseParser.TEXT_CONTENT ->
                VLMResult(false, null, "主 VLM parser 不支持 TEXT_CONTENT: $modelOrScene")
        }
    }

    fun metadataFromTurn(turn: ChatCompletionTurn): VLMThinkingContext {
        val content = turn.message.contentText()
        val metadata = parseStepMetadata(content, turn.reasoning)
        return buildThinkingContext(
            content = content,
            reasoning = turn.reasoning,
            finishReason = turn.finishReason,
            metadata = metadata,
        )
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

    private fun parseToolActionResponse(
        response: SceneChatCompletionTurn,
        dynamicFunctionToolNames: Set<String>,
        dynamicFunctionToolMappings: Map<String, String>,
        dynamicFunctionRequiredArguments: Map<String, Set<String>>,
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
            val error = buildMissingToolCallMessage(response.turn.finishReason, thinking)
            return VLMResult(
                success = false,
                step = null,
                error = error,
                thinking = thinking,
                shouldRetryForToolCall = true,
                toolCallFailure = VLMToolCallFailure(
                    code = "missing_tool_call",
                    message = error,
                ),
            )
        }
        if (toolCalls.size > 1) {
            val error = "主 VLM 每轮只能返回一个 tool_call，实际收到 ${toolCalls.size} 个"
            return VLMResult(
                success = false,
                step = null,
                error = error,
                thinking = thinking,
                shouldRetryForToolCall = true,
                toolCallFailure = VLMToolCallFailure(
                    code = "multiple_tool_calls",
                    providedFields = toolCalls.map { it.function.name.trim() },
                    safeArgumentsPreview = VLMToolDefinitions.safeToolCallSummary(toolCalls),
                    message = error,
                ),
            )
        }

        return parseSingleToolCall(
            toolCall = toolCalls.first(),
            metadata = metadata,
            thinking = thinking,
            reasoning = response.turn.reasoning,
            dynamicFunctionToolNames = dynamicFunctionToolNames,
            dynamicFunctionToolMappings = dynamicFunctionToolMappings,
            dynamicFunctionRequiredArguments = dynamicFunctionRequiredArguments,
        )
    }

    private fun parseSingleToolCall(
        toolCall: AssistantToolCall,
        metadata: StepMetadataPayload,
        thinking: VLMThinkingContext,
        reasoning: String,
        dynamicFunctionToolNames: Set<String>,
        dynamicFunctionToolMappings: Map<String, String>,
        dynamicFunctionRequiredArguments: Map<String, Set<String>>,
    ): VLMResult {
        return try {
            val action = parseActionFromToolCall(
                toolCall = toolCall,
                dynamicFunctionToolNames = dynamicFunctionToolNames,
                dynamicFunctionToolMappings = dynamicFunctionToolMappings,
                dynamicFunctionRequiredArguments = dynamicFunctionRequiredArguments,
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
            val rawToolName = toolCall.function.name.trim()
            val describedFailure = VLMToolDefinitions.describeInvalidToolCall(
                toolCall = toolCall,
                message = "",
                requiredFieldsOverride = dynamicFunctionRequiredArguments[rawToolName],
            )
            val failureDetail = if (describedFailure.code == "invalid_arguments_json") {
                "Invalid tool arguments JSON for ${rawToolName.ifBlank { "<missing>" }}: malformed JSON object"
            } else {
                VLMToolDefinitions.sanitizeToolCallFailureMessage(e.message.orEmpty())
            }
            val error = "Failed to parse native tool_call response: $failureDetail"
            VLMResult(
                success = false,
                step = null,
                error = error,
                thinking = thinking,
                shouldRetryForToolCall = true,
                previousToolCall = toolCall,
                toolCallFailure = describedFailure.copy(message = error),
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
        messages += buildCurrentUserMessage(currentUserText, screenshot, markedScreenshot)

        retryState?.let { messages += buildRetryMessages(context, it) }
        return messages
    }

    internal fun buildRetryMessages(
        context: UIContext,
        retryState: VLMToolCallRetryState,
    ): List<ChatCompletionMessage> {
        val messages = mutableListOf<ChatCompletionMessage>()
        val previousToolCall = retryState.previousToolCall
        if (previousToolCall != null) {
            messages += ChatCompletionMessage(
                role = "assistant",
                content = buildRetryAssistantContent(retryState.thinking)?.let(::JsonPrimitive),
                toolCalls = listOf(previousToolCall),
            )
            messages += ChatCompletionMessage(
                role = "tool",
                content = JsonPrimitive(buildRetryToolResult(retryState)),
                toolCallId = previousToolCall.id,
            )
        } else {
            buildRetryAssistantContent(retryState.thinking)?.let { assistantContent ->
                messages += ChatCompletionMessage(
                    role = "assistant",
                    content = JsonPrimitive(assistantContent)
                )
            }
        }
        messages += ChatCompletionMessage(
            role = "user",
            content = JsonPrimitive(PromptTemplate.buildToolCallRetryPrompt(context, retryState))
        )
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
                            put("text", JsonPrimitive("Screenshot with visible action targets highlighted."))
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

    private fun buildRetryToolResult(retryState: VLMToolCallRetryState): String {
        val failure = retryState.toolCallFailure
        return buildJsonObject {
            put("success", JsonPrimitive(false))
            put("error", JsonPrimitive("tool_call_schema_validation_failed"))
            retryState.failureReason?.takeIf(String::isNotBlank)?.let {
                put("message", JsonPrimitive(it.take(1000)))
            }
            failure?.let {
                put("tool_name", JsonPrimitive(it.toolName.orEmpty()))
                put("required_fields", JsonArray(it.requiredFields.map(::JsonPrimitive)))
                put("provided_fields", JsonArray(it.providedFields.map(::JsonPrimitive)))
                put("missing_fields", JsonArray(it.missingFields.map(::JsonPrimitive)))
                put(
                    "argument_types",
                    JsonObject(it.argumentTypes.mapValues { (_, type) -> JsonPrimitive(type) })
                )
            }
        }.toString()
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
        dynamicFunctionRequiredArguments: Map<String, Set<String>>,
    ): VLMCommand {
        val dynamicAction = functionActionFromDynamicToolCall(
            toolCall = toolCall,
            dynamicFunctionToolMappings = dynamicFunctionToolMappings,
            dynamicFunctionRequiredArguments = dynamicFunctionRequiredArguments,
        )
        if (dynamicAction != null) return dynamicAction
        return parseToolCall(toolCall, dynamicFunctionToolNames)
    }

    private fun parseToolCall(
        toolCall: AssistantToolCall,
        dynamicFunctionToolNames: Set<String>
    ): VLMCommand {
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
        val args = VLMToolDefinitions.parseArguments(toolName, toolCall.function.arguments)
        return when (toolName) {
            OobActionSchema.TOOL_CLICK,
            OobActionSchema.TOOL_INPUT_TEXT,
            OobActionSchema.TOOL_SWIPE,
            OobActionSchema.TOOL_LONG_PRESS,
            OobActionSchema.TOOL_OPEN_APP,
            OobActionSchema.TOOL_PRESS_KEY,
            OobActionSchema.TOOL_WAIT -> Action(tool = toolName, args = args)
            OobActionSchema.TOOL_GET_STATE -> Observe(
                reason = optionalString(args, OobActionSchema.ARG_REASON).orEmpty()
            )
            OobActionSchema.TOOL_CALL_TOOL -> throw IllegalArgumentException(INTERNAL_CALL_TOOL_ERROR)
            OobActionSchema.TOOL_FINISHED -> FinishedDecision(
                content = optionalString(args, OobActionSchema.ARG_CONTENT).orEmpty()
            )
            OobActionSchema.TOOL_INFO -> InfoDecision(
                value = requireString(args, OobActionSchema.ARG_VALUE)
            )
            OobActionSchema.TOOL_FEEDBACK -> AbortDecision(
                value = optionalString(args, OobActionSchema.ARG_VALUE).orEmpty()
            )
            OobActionSchema.TOOL_ABORT -> AbortDecision(
                value = optionalString(args, OobActionSchema.ARG_VALUE).orEmpty()
            )
            OobActionSchema.TOOL_REQUIRE_USER_CHOICE -> InfoDecision(
                value = buildString {
                    val prompt = optionalString(args, OobActionSchema.ARG_PROMPT).orEmpty()
                    val options = (args[OobActionSchema.ARG_OPTIONS] as? JsonArray)
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
                        .orEmpty()
                    append(prompt)
                    if (options.isNotEmpty()) append("\n可选项：${options.joinToString(" / ")}")
                }
            )
            OobActionSchema.TOOL_REQUIRE_USER_CONFIRMATION -> InfoDecision(
                value = optionalString(args, OobActionSchema.ARG_PROMPT).orEmpty()
            )
            else -> throw IllegalArgumentException("Unsupported canonical action: $toolName")
        }
    }

    private fun functionActionFromDynamicToolCall(
        toolCall: AssistantToolCall,
        dynamicFunctionToolMappings: Map<String, String>,
        dynamicFunctionRequiredArguments: Map<String, Set<String>>,
    ): FunctionInvocation? {
        val rawToolName = toolCall.function.name.trim()
        val functionId = dynamicFunctionToolMappings[rawToolName] ?: return null
        val arguments = parseDynamicFunctionArguments(rawToolName, toolCall.function.arguments)
        dynamicFunctionRequiredArguments[rawToolName].orEmpty().forEach { field ->
            if (arguments[field] == null || arguments[field] is JsonNull) {
                throw IllegalArgumentException("Recalled workflow $rawToolName missing required argument: $field")
            }
        }
        return FunctionInvocation(
            functionId = functionId,
            arguments = arguments,
        )
    }

    private fun parseDynamicFunctionArguments(toolName: String, rawArguments: String): JsonObject {
        val parsed = VLMToolDefinitions.parseRawArgumentsObject(
            toolName = toolName,
            rawArguments = rawArguments,
            allowEmpty = true,
        )
        val reserved = parsed.keys.firstOrNull { key ->
            key.equals("tool_title", ignoreCase = true) ||
                key.equals("toolTitle", ignoreCase = true) ||
                key == "function_id"
        }
        require(reserved == null) {
            "Recalled workflow $toolName has reserved argument: $reserved"
        }
        return parsed
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
