package cn.com.omnimind.bot.vlm

import cn.com.omnimind.assists.task.vlmserver.VLMToolDefinitions
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import cn.com.omnimind.baselib.llm.contentText
import cn.com.omnimind.baselib.runlog.OobActionSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

internal object AndroidGuiModelAdapter {
    private const val SUMMARY_FIELD = "summary"
    private const val NATIVE_TOOL_CALLS_MISSING =
        "provider_tool_call_contract_violation: provider returned no native tool_calls"
    private const val TEXT_TOOL_CALL_UNSUPPORTED =
        "model_native_tool_calls_unsupported: " +
            "model returned a tool call in assistant.content instead of native tool_calls"
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val pointTools = setOf(
        OobActionSchema.TOOL_CLICK,
        OobActionSchema.TOOL_LONG_PRESS,
        OobActionSchema.TOOL_INPUT_TEXT,
    )

    fun adapt(toolName: String, arguments: JsonObject): JsonObject {
        return when (toolName) {
            in pointTools -> adaptPoint(arguments, xField = "x", yField = "y", toolName = toolName)
            OobActionSchema.TOOL_SWIPE -> adaptPoint(
                adaptPoint(arguments, xField = "x1", yField = "y1", toolName = toolName),
                xField = "x2",
                yField = "y2",
                toolName = toolName,
            )
            else -> arguments
        }
    }

    fun modelTurnContractViolation(turn: ChatCompletionTurn): String? {
        val toolCalls = turn.message.toolCalls.orEmpty()
        if (toolCalls.size == 1) return null
        if (toolCalls.size > 1) {
            return "provider_tool_call_contract_violation: " +
                "expected one native tool_call, got ${toolCalls.size}"
        }
        return if (containsTextToolCall(turn.message.contentText())) {
            TEXT_TOOL_CALL_UNSUPPORTED
        } else {
            NATIVE_TOOL_CALLS_MISSING
        }
    }

    fun executionArguments(toolName: String, arguments: JsonObject): JsonObject {
        if (VLMToolDefinitions.toolSpec(toolName) == null || SUMMARY_FIELD !in arguments) {
            return arguments
        }
        return JsonObject(arguments.filterKeys { it != SUMMARY_FIELD })
    }

    fun summary(toolName: String, arguments: JsonObject): String {
        if (VLMToolDefinitions.toolSpec(toolName) == null) return ""
        return (arguments[SUMMARY_FIELD] as? JsonPrimitive)
            ?.contentOrNull
            ?.trim()
            .orEmpty()
    }

    private fun adaptPoint(
        arguments: JsonObject,
        xField: String,
        yField: String,
        toolName: String,
    ): JsonObject {
        val rawPoint = arguments[xField] ?: return arguments
        if (rawPoint !is JsonArray) return arguments
        require(yField !in arguments) {
            "model_argument_dialect_invalid: $toolName $xField point array conflicts with $yField"
        }
        require(rawPoint.size == 2 && rawPoint.all { it.isNumericScalar() }) {
            "model_argument_dialect_invalid: $toolName $xField must be a two-number point array"
        }
        return JsonObject(
            arguments.toMutableMap().apply {
                this[xField] = rawPoint[0]
                this[yField] = rawPoint[1]
            }
        )
    }

    private fun containsTextToolCall(content: String): Boolean {
        val normalized = content.trim()
        if (normalized.isEmpty()) return false
        val payload = parseObject(normalized) ?: run {
            val start = normalized.indexOf('{')
            val end = normalized.lastIndexOf('}')
            if (start < 0 || end <= start) return false
            parseObject(normalized.substring(start, end + 1))
        } ?: return false
        if (payload["tool_call"] is JsonObject || payload["tool_calls"] is JsonArray) return true
        val action = payload["action"] as? JsonObject
        if (action != null && listOf("type", "tool", "name").any { key ->
                (action[key] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true
            }
        ) {
            return true
        }
        val name = payload["name"] as? JsonPrimitive
        return name?.contentOrNull?.trim()?.isNotEmpty() == true && payload["arguments"] is JsonObject
    }

    private fun parseObject(value: String): JsonObject? = runCatching {
        json.parseToJsonElement(value) as? JsonObject
    }.getOrNull()

    private fun Any?.isNumericScalar(): Boolean =
        this is JsonPrimitive && !isString && doubleOrNull != null
}
