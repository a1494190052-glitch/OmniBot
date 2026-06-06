package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

object VLMToolArgumentParser {
    private const val TAG = "VLMToolArgumentParser"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    fun parse(toolName: String, rawArguments: String): JsonObject {
        val normalized = normalizeRawArguments(rawArguments)
        if (normalized.isEmpty()) {
            val empty = JsonObject(emptyMap())
            VLMToolDefinitions.validateArguments(toolName, empty)
            return empty
        }

        val candidateTexts = LinkedHashSet<String>().apply {
            add(normalized)
            extractTopLevelObject(normalized)?.let(::add)
            unwrapQuotedJson(normalized)?.let(::add)
        }

        var strictParseError: String? = null
        candidateTexts.forEach { candidate ->
            val parsed = runCatching { parseObject(candidate) }
                .onFailure { strictParseError = it.message ?: strictParseError }
                .getOrNull()
            if (parsed != null) {
                return finalizeParsedArguments(
                    toolName = toolName,
                    parsed = parsed,
                    rawArguments = rawArguments,
                    repaired = candidate != normalized
                )
            }
        }

        val repaired = repairUsingSchema(toolName, normalized)
        if (repaired != null) {
            return finalizeParsedArguments(
                toolName = toolName,
                parsed = repaired,
                rawArguments = rawArguments,
                repaired = true
            )
        }

        val reason = strictParseError?.takeIf { it.isNotBlank() } ?: "unknown parse failure"
        throw IllegalArgumentException(
            "Invalid tool arguments JSON for $toolName: $reason; raw=${preview(rawArguments)}"
        )
    }

    private fun finalizeParsedArguments(
        toolName: String,
        parsed: JsonObject,
        rawArguments: String,
        repaired: Boolean
    ): JsonObject {
        val stripped = stripVlmMetadataArguments(parsed)
        val normalizedFromParsed = VLMToolDefinitions.coerceArguments(
            toolName,
            VLMToolDefinitions.normalizeArguments(toolName, stripped)
        )
        val validatedFromParsed = runCatching {
            VLMToolDefinitions.validateArguments(toolName, normalizedFromParsed)
            normalizedFromParsed
        }.getOrNull()
        if (validatedFromParsed != null) {
            if (repaired || validatedFromParsed != parsed) {
                OmniLog.w(
                    TAG,
                    "Repaired malformed tool arguments for $toolName: raw=${preview(rawArguments)} repaired=${preview(validatedFromParsed.toString())}"
                )
            }
            return validatedFromParsed
        }

        val repairedFromRaw = repairUsingSchema(toolName, normalizeRawArguments(rawArguments))
        if (repairedFromRaw != null) {
            val strippedRawRepair = stripVlmMetadataArguments(repairedFromRaw)
            val normalizedFromRawRepair = VLMToolDefinitions.coerceArguments(
                toolName,
                VLMToolDefinitions.normalizeArguments(toolName, strippedRawRepair)
            )
            VLMToolDefinitions.validateArguments(toolName, normalizedFromRawRepair)
            OmniLog.w(
                TAG,
                "Repaired malformed tool arguments for $toolName: raw=${preview(rawArguments)} repaired=${preview(normalizedFromRawRepair.toString())}"
            )
            return normalizedFromRawRepair
        }

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
        return when (element) {
            is JsonObject -> element
            is JsonPrimitive -> {
                if (!element.isString) {
                    throw IllegalArgumentException("tool arguments must be a JSON object")
                }
                val inner = element.contentOrNull?.trim().orEmpty()
                if (inner.startsWith("{") && inner.endsWith("}")) {
                    json.parseToJsonElement(inner) as? JsonObject
                        ?: throw IllegalArgumentException("tool arguments must be a JSON object")
                } else {
                    throw IllegalArgumentException("tool arguments must be a JSON object")
                }
            }

            else -> throw IllegalArgumentException("tool arguments must be a JSON object")
        }
    }

    private fun normalizeRawArguments(raw: String): String {
        var normalized = raw.trim()
        if (normalized.startsWith("```")) {
            normalized = normalized
                .removePrefix("```json")
                .removePrefix("```JSON")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
        }
        return normalized
    }

    private fun unwrapQuotedJson(raw: String): String? {
        val parsed = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonPrimitive ?: return null
        val inner = parsed.contentOrNull?.trim().orEmpty()
        return inner.takeIf { it.startsWith("{") && it.endsWith("}") }
    }

    private fun extractTopLevelObject(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var stringQuote = '\u0000'
        var escaped = false

        for (index in start until raw.length) {
            val ch = raw[index]
            if (inString) {
                if (escaped) {
                    escaped = false
                    continue
                }
                when (ch) {
                    '\\' -> escaped = true
                    stringQuote -> inString = false
                }
                continue
            }

            when (ch) {
                '"', '\'' -> {
                    inString = true
                    stringQuote = ch
                }

                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return raw.substring(start, index + 1)
                    }
                }
            }
        }
        return null
    }

    private fun repairUsingSchema(toolName: String, raw: String): JsonObject? {
        val properties = VLMToolDefinitions.propertiesFor(toolName)
        if (properties.isEmpty()) {
            return JsonObject(emptyMap())
        }

        val repaired = linkedMapOf<String, JsonElement>()
        properties.forEach { (field, schema) ->
            val type = schema["type"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            when (type) {
                "string" -> extractStringField(raw, field)?.let { repaired[field] = JsonPrimitive(it) }
                "number" -> extractNumberField(raw, field)?.let { repaired[field] = buildNumericPrimitive(it) }
                "integer" -> extractNumberField(raw, field)?.let { repaired[field] = buildIntegerPrimitive(it) }
                "array" -> extractArrayField(raw, field)?.let { repaired[field] = it }
            }
        }

        if (repaired.isEmpty()) return null
        return JsonObject(repaired)
    }

    private fun extractStringField(raw: String, field: String): String? {
        val source = locateFieldValue(raw, field) ?: return null
        val trimmed = source.trimStart()
        if (trimmed.isEmpty()) return null
        val first = trimmed.first()
        if (first == '"' || first == '\'') {
            val quoted = extractQuotedLiteral(trimmed) ?: return null
            return runCatching {
                json.parseToJsonElement(quoted).jsonPrimitive.contentOrNull
            }.getOrNull() ?: quoted.trim('"', '\'')
        }

        return trimmed.takeWhile { it != ',' && it != '}' && it != '\n' && it != '\r' }
            .trim()
            .trim('"', '\'')
            .takeIf { it.isNotEmpty() }
    }

    private fun extractNumberField(raw: String, field: String): String? {
        val source = locateFieldValue(raw, field) ?: return null
        val trimmed = source.trimStart()
        if (trimmed.isEmpty()) return null
        val rawValue = if (trimmed.first() == '"' || trimmed.first() == '\'') {
            extractQuotedLiteral(trimmed)?.trim('"', '\'').orEmpty()
        } else {
            trimmed
        }
        return NUMBER_REGEX.find(rawValue)?.value
    }

    private fun extractArrayField(raw: String, field: String): JsonArray? {
        val source = locateFieldValue(raw, field) ?: return null
        val trimmed = source.trimStart()
        if (trimmed.isEmpty()) return null

        if (trimmed.startsWith("[")) {
            val literal = extractBalancedLiteral(trimmed, '[', ']') ?: return null
            return runCatching { json.parseToJsonElement(literal) as? JsonArray }.getOrNull()
                ?: parseLooseStringArray(literal)
        }

        val single = extractStringField(raw, field) ?: return null
        val values = single.split(Regex("[,，、\\n]"))
            .map { it.trim().trim('"', '\'') }
            .filter { it.isNotEmpty() }
        return buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }
    }

    private fun locateFieldValue(raw: String, field: String): String? {
        val pattern = Regex("""(?:(["'])${Regex.escape(field)}\1|${Regex.escape(field)})\s*[:=]\s*""")
        val match = pattern.find(raw) ?: return null
        return raw.substring(match.range.last + 1)
    }

    private fun extractQuotedLiteral(source: String): String? {
        val quote = source.firstOrNull() ?: return null
        if (quote != '"' && quote != '\'') return null
        var escaped = false
        for (index in 1 until source.length) {
            val ch = source[index]
            if (escaped) {
                escaped = false
                continue
            }
            when (ch) {
                '\\' -> escaped = true
                quote -> return source.substring(0, index + 1)
            }
        }
        return null
    }

    private fun extractBalancedLiteral(source: String, open: Char, close: Char): String? {
        if (!source.startsWith(open)) return null
        var depth = 0
        var inString = false
        var stringQuote = '\u0000'
        var escaped = false

        source.forEachIndexed { index, ch ->
            if (inString) {
                if (escaped) {
                    escaped = false
                    return@forEachIndexed
                }
                when (ch) {
                    '\\' -> escaped = true
                    stringQuote -> inString = false
                }
                return@forEachIndexed
            }

            when (ch) {
                '"', '\'' -> {
                    inString = true
                    stringQuote = ch
                }

                open -> depth += 1
                close -> {
                    depth -= 1
                    if (depth == 0) {
                        return source.substring(0, index + 1)
                    }
                }
            }
        }
        return null
    }

    private fun parseLooseStringArray(raw: String): JsonArray? {
        val inner = raw.removePrefix("[").removeSuffix("]")
        val items = inner.split(Regex("[,，、\\n]"))
            .map { it.trim().trim('"', '\'') }
            .filter { it.isNotEmpty() }
        if (items.isEmpty()) return null
        return buildJsonArray { items.forEach { add(JsonPrimitive(it)) } }
    }

    private fun buildNumericPrimitive(raw: String): JsonPrimitive {
        val number = raw.toDoubleOrNull()
            ?: throw IllegalArgumentException("Invalid numeric literal: $raw")
        val asLong = number.toLong()
        return if (number == asLong.toDouble()) JsonPrimitive(asLong) else JsonPrimitive(number)
    }

    private fun buildIntegerPrimitive(raw: String): JsonPrimitive {
        val value = raw.toLongOrNull()
            ?: raw.toDoubleOrNull()?.toLong()
            ?: throw IllegalArgumentException("Invalid integer literal: $raw")
        return JsonPrimitive(value)
    }

    private fun preview(raw: String, maxLen: Int = 240): String {
        val normalized = raw.replace(Regex("\\s+"), " ").trim()
        return if (normalized.length <= maxLen) normalized else normalized.take(maxLen) + "..."
    }

    private val NUMBER_REGEX = Regex("""[-+]?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?""")
}
