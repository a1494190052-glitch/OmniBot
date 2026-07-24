package cn.com.omnimind.bot.vlm

import cn.com.omnimind.assists.task.vlmserver.PromptTemplate
import cn.com.omnimind.assists.task.vlmserver.UIContext
import cn.com.omnimind.assists.task.vlmserver.VLMAllowedToolSelector
import cn.com.omnimind.assists.task.vlmserver.VLMRuntimeConfigRegistry
import cn.com.omnimind.assists.task.vlmserver.VLMToolDefinitions
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionStreamOptions
import cn.com.omnimind.baselib.llm.ChatCompletionThinking
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import cn.com.omnimind.baselib.llm.contentText
import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal data class AndroidGuiTurnRequest(
    val request: ChatCompletionRequest,
    val currentUserText: String,
    val dynamicFunctionToolNames: Set<String>,
    val dynamicFunctionToolMappings: Map<String, String>,
    val dynamicFunctionRequiredArguments: Map<String, Set<String>>,
    val selectedBaseToolNames: Set<String>,
    val systemPromptChars: Int,
)

internal data class AndroidGuiTurnMetadata(
    val observation: String = "",
    val thinking: String = "",
    val summary: String = "",
    val rawContent: String = "",
    val reasoning: String = "",
    val finishReason: String? = null,
)

internal class AndroidGuiPolicy(
    private val systemPromptBuilder: (String) -> String = { PromptTemplate.buildSystemPrompt(it) },
    private val turnPromptBuilder: (UIContext, String) -> String = { context, sceneId ->
        PromptTemplate.buildTurnUserPrompt(
            context = context,
            sceneId = sceneId,
        )
    },
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    fun buildRequest(
        context: UIContext,
        screenshot: String?,
        model: String,
    ): AndroidGuiTurnRequest {
        val runtimeConfig = VLMRuntimeConfigRegistry.get()
        val sceneId = resolveSceneId(model, runtimeConfig.primarySceneId)
        val modelOverride = model.trim().takeUnless { it.isEmpty() || it.startsWith("scene.") }
        val hiddenFunctionNames = VLMToolDefinitions
            .dynamicFunctionToolNamesFromDefinitions(context.dynamicToolDefinitions)
        val functionMappings = VLMToolDefinitions
            .dynamicFunctionToolMappingsFromDefinitions(context.dynamicToolDefinitions)
        val requiredArguments = VLMToolDefinitions
            .dynamicFunctionRequiredArgumentsFromDefinitions(context.dynamicToolDefinitions)
        val dynamicFunctionNames = hiddenFunctionNames + functionMappings.keys
        val selectedBaseTools = VLMAllowedToolSelector.select(context)
        val promptContext = context
            .withFunctionGuidance(dynamicFunctionNames)
            .copy(allowedVlmToolNames = (selectedBaseTools + functionMappings.keys).toList())
        val systemPrompt = systemPromptBuilder(sceneId)
        val currentUserText = turnPromptBuilder(promptContext, sceneId)
        val baseTools = VLMToolDefinitions.tools(allowedToolNames = selectedBaseTools)
        val dynamicTools = VLMToolDefinitions
            .dynamicToolsFromDefinitions(promptContext.dynamicToolDefinitions)
            .filterNot { it.function.name in hiddenFunctionNames }
        val tools = (dynamicTools + baseTools).distinctBy { it.function.name }
        val messages = listOf(
            ChatCompletionMessage(role = "system", content = JsonPrimitive(systemPrompt)),
            currentUserMessage(currentUserText, screenshot),
        )
        OmniLog.i(
            TAG,
            "build request scene=$model tools=${tools.size} recalled=${dynamicFunctionNames.size} screenshot=${!screenshot.isNullOrBlank()}",
        )
        return AndroidGuiTurnRequest(
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
            dynamicFunctionToolNames = dynamicFunctionNames,
            dynamicFunctionToolMappings = functionMappings,
            dynamicFunctionRequiredArguments = requiredArguments,
            selectedBaseToolNames = selectedBaseTools,
            systemPromptChars = systemPrompt.length,
        )
    }

    fun metadata(turn: ChatCompletionTurn): AndroidGuiTurnMetadata {
        val content = turn.message.contentText().trim()
        val payload = content.extractJsonObject()
        val observation = payload.string("observation")
        val explicitThinking = payload.string("thought").ifBlank { payload.string("thinking") }
        val summary = payload.string("summary").ifBlank {
            content.takeUnless { payload != null }.orEmpty()
        }
        return AndroidGuiTurnMetadata(
            observation = observation,
            thinking = explicitThinking.ifBlank { turn.reasoning.trim() },
            summary = summary,
            rawContent = content,
            reasoning = turn.reasoning.trim(),
            finishReason = turn.finishReason?.trim()?.takeIf(String::isNotEmpty),
        )
    }

    fun parseAndValidateArguments(
        turnRequest: AndroidGuiTurnRequest,
        toolName: String,
        rawArguments: String,
    ): JsonObject {
        val arguments = runCatching { json.parseToJsonElement(rawArguments) as? JsonObject }
            .getOrNull()
            ?: throw IllegalArgumentException("Invalid tool arguments JSON for $toolName")
        validateArguments(turnRequest, toolName, arguments)
        return arguments
    }

    fun validateArguments(
        turnRequest: AndroidGuiTurnRequest,
        toolName: String,
        arguments: JsonObject,
    ) {
        val schema = turnRequest.request.tools
            .firstOrNull { it.function.name == toolName }
            ?.function
            ?.parameters
            ?: throw IllegalArgumentException("Unknown GUI tool: $toolName")
        if (toolName !in turnRequest.dynamicFunctionToolMappings) {
            VLMToolDefinitions.validateArguments(toolName, arguments)
            return
        }
        require(arguments.keys.none { it == "function_id" || it == "tool_title" || it == "toolTitle" }) {
            "Recalled workflow $toolName contains a reserved argument"
        }
        val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        turnRequest.dynamicFunctionRequiredArguments[toolName].orEmpty().forEach { field ->
            require(arguments[field] != null && arguments[field] !is JsonNull) {
                "Recalled workflow $toolName missing required argument: $field"
            }
        }
        arguments.forEach { (field, value) ->
            val fieldSchema = properties[field] as? JsonObject
                ?: throw IllegalArgumentException("Recalled workflow $toolName has unknown argument: $field")
            val expected = fieldSchema["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
            require(expected.isBlank() || value.matchesType(expected)) {
                "Recalled workflow $toolName argument $field expected $expected"
            }
        }
    }

    private fun currentUserMessage(text: String, screenshot: String?): ChatCompletionMessage =
        ChatCompletionMessage(
            role = "user",
            content = buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
                screenshot?.trim()?.takeIf(String::isNotEmpty)?.let { image ->
                    add(buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject { put("url", image.asDataUri()) })
                    })
                }
            },
        )

    private fun UIContext.withFunctionGuidance(functionNames: Set<String>): UIContext {
        if (functionNames.isEmpty()) return this
        val hint = "Recalled Functions are local tools. Prefer one when it clearly matches the current goal. " +
            "Never emit call_tool, function_id, or a raw Function id."
        return copy(
            stepSkillGuidance = listOf(stepSkillGuidance.trim(), hint)
                .filter(String::isNotBlank)
                .joinToString("\n\n"),
        )
    }

    private fun String.extractJsonObject(): JsonObject? {
        val start = indexOf('{')
        val end = lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { json.parseToJsonElement(substring(start, end + 1)) as? JsonObject }
            .getOrNull()
    }

    private fun JsonObject?.string(name: String): String =
        this?.get(name)?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

    private fun JsonElement.matchesType(expected: String): Boolean = when (expected) {
        "string" -> this is JsonPrimitive && isString
        "number" -> this is JsonPrimitive && !isString && doubleOrNull != null
        "integer" -> this is JsonPrimitive && !isString && longOrNull != null
        "boolean" -> this is JsonPrimitive && !isString && booleanOrNull != null
        "array" -> this is JsonArray
        "object" -> this is JsonObject
        "null" -> this is JsonNull
        else -> true
    }

    private fun String.asDataUri(): String = if (
        startsWith("http://", ignoreCase = true) ||
        startsWith("https://", ignoreCase = true) ||
        startsWith("data:", ignoreCase = true)
    ) {
        this
    } else {
        "data:image/jpeg;base64,$this"
    }

    private fun resolveSceneId(model: String, defaultSceneId: String): String =
        model.trim().takeIf { it.startsWith("scene.") } ?: defaultSceneId

    private companion object {
        const val TAG = "AndroidGuiPolicy"
    }
}
