package cn.com.omnimind.assists.task.vlmserver

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

object VLMToolArgumentParser {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = false
    }

    fun parse(toolName: String, rawArguments: String): JsonObject {
        val normalized = rawArguments.trim()
        if (normalized.isEmpty()) {
            throw IllegalArgumentException(
                "Invalid tool arguments JSON for $toolName: function.arguments must be a JSON object; raw=${preview(rawArguments)}"
            )
        }

        val parsed = runCatching { parseObject(normalized) }.getOrElse { error ->
            throw IllegalArgumentException(
                "Invalid tool arguments JSON for $toolName: ${error.message ?: "unknown parse failure"}; raw=${preview(rawArguments)}",
                error
            )
        }
        return finalizeParsedArguments(toolName, parsed)
    }

    private fun finalizeParsedArguments(
        toolName: String,
        parsed: JsonObject
    ): JsonObject {
        val stripped = stripVlmMetadataArguments(parsed)
        val normalizedFromParsed = VLMToolDefinitions.coerceArguments(
            toolName,
            VLMToolDefinitions.normalizeArguments(toolName, stripped)
        )
        VLMToolDefinitions.validateArguments(toolName, normalizedFromParsed)
        return normalizedFromParsed
    }

    private fun stripVlmMetadataArguments(arguments: JsonObject): JsonObject {
        if (arguments.isEmpty()) return arguments
        val stripped = arguments.filterKeys { key ->
            !key.equals("tool_title", ignoreCase = true) &&
                !key.equals("toolTitle", ignoreCase = true)
        }
        return if (stripped.size == arguments.size) arguments else JsonObject(stripped)
    }

    private fun parseObject(candidate: String): JsonObject {
        val element = json.parseToJsonElement(candidate)
        return element as? JsonObject
            ?: throw IllegalArgumentException("tool arguments must be a JSON object")
    }

    private fun preview(raw: String, maxLen: Int = 240): String {
        val normalized = raw.replace(Regex("\\s+"), " ").trim()
        return if (normalized.length <= maxLen) normalized else normalized.take(maxLen) + "..."
    }
}
