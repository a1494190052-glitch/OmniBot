package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.runlog.OobCanonicalActionSchema
import cn.com.omnimind.baselib.llm.ChatCompletionFunction
import cn.com.omnimind.baselib.llm.ChatCompletionTool
import cn.com.omnimind.baselib.i18n.PromptLocale
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

object VLMToolDefinitions {
    private const val MAX_COMPACT_ACTION_SCHEMA_CHARS = 1_600
    private const val TOOL_TITLE_FIELD = "tool_title"
    private val HIDDEN_BASE_TOOL_NAMES = setOf(
        OobCanonicalActionSchema.TOOL_GET_STATE,
    )

    data class ToolSpec(
        val name: String,
        val description: String,
        val parameters: JsonObject,
        val promptGuide: String
    )

    private fun currentLocale(): PromptLocale = AppLocaleManager.currentPromptLocale()

    private fun t(locale: PromptLocale, zh: String, en: String): String {
        return when (locale) {
            PromptLocale.ZH_CN -> zh
            PromptLocale.EN_US -> en
        }
    }

    private fun OobCanonicalActionSchema.LocalizedText.text(locale: PromptLocale): String =
        t(locale, zhCn, enUs)

    private fun buildToolSpecs(locale: PromptLocale): List<ToolSpec> =
        OobCanonicalActionSchema.modelVisibleTools
            .filterNot { it.name in HIDDEN_BASE_TOOL_NAMES }
            .map { schema ->
            ToolSpec(
                name = schema.name,
                description = schema.description.text(locale),
                parameters = objectSchema(
                    properties = schema.args.associate { arg ->
                        arg.name to jsonSchemaForArg(arg, locale)
                    },
                    required = schema.args.filter { it.required }.map { it.name },
                ),
                promptGuide = schema.promptGuide.text(locale),
            )
        }

    private fun toolSpecs(locale: PromptLocale = currentLocale()): List<ToolSpec> {
        return buildToolSpecs(locale)
    }

    fun tools(locale: PromptLocale = currentLocale()): List<ChatCompletionTool> {
        return toolSpecs(locale).map { spec ->
            ChatCompletionTool(
                function = ChatCompletionFunction(
                    name = spec.name,
                    description = spec.description,
                    parameters = spec.parameters
                )
            )
        }
    }

    fun dynamicToolsFromDefinitions(definitions: List<JsonObject>): List<ChatCompletionTool> {
        return definitions.mapNotNull { definition ->
            val function = definition["function"] as? JsonObject ?: return@mapNotNull null
            val name = function["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (name.isBlank()) return@mapNotNull null
            ChatCompletionTool(
                function = ChatCompletionFunction(
                    name = name,
                    description = function["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    parameters = sanitizeVlmDynamicFunctionParameters(
                        (function["parameters"] as? JsonObject) ?: JsonObject(emptyMap())
                    )
                )
            )
        }
    }

    private fun sanitizeVlmDynamicFunctionParameters(parameters: JsonObject): JsonObject {
        if (parameters.isEmpty()) return parameters
        return buildJsonObject {
            parameters.forEach { (key, value) ->
                when (key) {
                    "properties" -> {
                        val properties = value as? JsonObject ?: JsonObject(emptyMap())
                        put("properties", JsonObject(properties.filterKeys { it != TOOL_TITLE_FIELD }))
                    }
                    "required" -> {
                        val required = value as? JsonArray ?: JsonArray(emptyList())
                        put("required", buildJsonArray {
                            required.forEach { item ->
                                if (item.jsonPrimitive.contentOrNull?.trim() != TOOL_TITLE_FIELD) {
                                    add(item)
                                }
                            }
                        })
                    }
                    else -> put(key, value)
                }
            }
        }
    }

    fun renderPromptGuide(locale: PromptLocale = currentLocale()): String {
        val guides = toolSpecs(locale).joinToString(separator = "\n") { it.promptGuide }
        return buildString {
            appendLine(guides)
            append(
                t(
                    locale,
                    "注意：每个 tool call 的 JSON 参数必须是严格合法的 object；如需调用本轮 recall 给出的复用流程，使用 call_tool(function_id, arguments)，不要把 Function id 当成单独工具名；当 step guidance 给出 preferred_call_tool 且匹配用户目标时，优先 call_tool，不要手动重复已保存路径。把 OOB indexed page evidence 作为主要 grounding：click/input_text/long_press 优先填写 element_index，swipe 优先填写 scrollable_index 和 direction。坐标只作为兜底；坐标字段必须是 0..1000 相对坐标，分别写入 x / y / x1 / y1 / x2 / y2，不要写成 \"x\": 827, 76 这类非法格式。系统会在执行前解码为屏幕绝对像素，本地记录始终保存绝对像素。wait 只在页面明确加载、动画或等待外部状态时使用。",
                    "Important: every tool call JSON argument value must be a strict object. To call a recalled reusable workflow for this turn, use call_tool(function_id, arguments); do not treat the Function id as a separate tool name. When step guidance provides preferred_call_tool and it matches the user goal, prefer call_tool instead of manually repeating the saved path. Use OOB indexed page evidence as the primary grounding: for click/input_text/long_press prefer element_index, and for swipe prefer scrollable_index plus direction. Coordinates are fallback only; coordinate fields must be 0..1000 relative coordinates, written into x / y / x1 / y1 / x2 / y2 as separate scalar fields. Do not emit invalid forms such as \"x\": 827, 76. The system decodes coordinates to screen absolute pixels before execution, and local records always store absolute pixels. Use wait only when the page is clearly loading, animating, or waiting for an external state change."
                )
            )
        }
    }

    fun renderCompactActionSchemaGuide(locale: PromptLocale = currentLocale()): String {
        val toolLines = toolSpecs(locale).joinToString(separator = "\n") { spec ->
            val required = requiredFieldsFor(spec.name, locale).joinToString(",").ifBlank { "-" }
            "- ${spec.name}: required=[$required]"
        }
        return buildString {
            appendLine(toolLines)
            append(
                t(
                    locale,
                    "统一格式：只能使用原生 tool_call。OOB indexed page evidence 是首选可执行菜单：click/input_text/long_press 目标在 #index 中时必须填 element_index；swipe 目标在 Sindex 中时必须填 scrollable_index 和 direction。坐标只在 evidence 缺失时兜底，且必须是 0..1000 相对坐标；系统会在执行前解码为屏幕绝对像素。不要使用旧 action/coordinate/coordinate2。",
                    "Unified format: native tool_call only. OOB indexed page evidence is the preferred executable menu: when a click/input_text/long_press target appears as #index, include element_index; when a swipe target appears as Sindex, include scrollable_index plus direction. Coordinates are fallback only when evidence is missing, and must be 0..1000 relative coordinates; the system decodes them to screen absolute pixels before execution. Do not use legacy action/coordinate/coordinate2."
                )
            )
        }.take(MAX_COMPACT_ACTION_SCHEMA_CHARS)
    }

    fun responseContract(locale: PromptLocale = currentLocale()): String {
        return when (locale) {
            PromptLocale.ZH_CN ->
                """{"observation":"当前界面的关键状态","thought":"为什么要执行这个工具","summary":"执行完本步后新的历史总结"}"""
            PromptLocale.EN_US ->
                """{"observation":"key state of the current screen","thought":"why this tool should be executed","summary":"updated running summary after this step"}"""
        }
    }

    fun toolSpec(name: String, locale: PromptLocale = currentLocale()): ToolSpec? =
        toolSpecs(locale).firstOrNull { it.name == name }

    fun propertiesFor(toolName: String, locale: PromptLocale = currentLocale()): Map<String, JsonObject> {
        val properties = toolSpec(toolName, locale)?.parameters?.get("properties") as? JsonObject ?: return emptyMap()
        return properties.mapValues { (_, value) -> value as? JsonObject ?: JsonObject(emptyMap()) }
    }

    fun requiredFieldsFor(toolName: String, locale: PromptLocale = currentLocale()): List<String> {
        val required = toolSpec(toolName, locale)?.parameters?.get("required") as? JsonArray ?: return emptyList()
        return required.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
    }

    fun normalizeArguments(toolName: String, arguments: JsonObject): JsonObject {
        if (arguments.isEmpty()) return arguments
        val normalized = arguments.toMutableMap()
        when (toolName) {
            OobCanonicalActionSchema.TOOL_CLICK,
            OobCanonicalActionSchema.TOOL_LONG_PRESS,
            OobCanonicalActionSchema.TOOL_INPUT_TEXT -> normalizePointArguments(normalized)
            OobCanonicalActionSchema.TOOL_SWIPE -> normalizeScrollArguments(normalized)
        }
        normalizeEnumArguments(toolName, normalized)
        return JsonObject(normalized)
    }

    fun coerceArguments(toolName: String, arguments: JsonObject): JsonObject {
        val properties = propertiesFor(toolName)
        if (properties.isEmpty() || arguments.isEmpty()) return arguments

        val normalized = linkedMapOf<String, JsonElement>()
        arguments.entries.forEach { (field, value) ->
            val schema = properties[field]
            normalized[field] = if (schema != null) {
                coerceValue(value, schema)
            } else {
                value
            }
        }
        return JsonObject(normalized)
    }

    fun validateArguments(toolName: String, arguments: JsonObject) {
        val toolSpec = toolSpec(toolName)
            ?: throw IllegalArgumentException("Unknown VLM tool: $toolName")
        val properties = propertiesFor(toolName)
        val requiredFields = requiredFieldsFor(toolName)
        val allowsAdditionalProperties =
            toolSpec.parameters["additionalProperties"]?.jsonPrimitive?.booleanOrNull == true
        requiredFields.forEach { field ->
            if (arguments[field] == null || arguments[field] is JsonNull) {
                throw IllegalArgumentException("Tool $toolName missing required argument: $field")
            }
        }

        arguments.entries.forEach { (field, value) ->
            val schema = properties[field] ?: run {
                if (!allowsAdditionalProperties) {
                    throw IllegalArgumentException("Tool $toolName has unknown argument: $field")
                }
                return@forEach
            }
            val expectedType = schema["type"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (expectedType.isNotEmpty() && !matchesType(expectedType, value)) {
                val coordinateHint = if (expectedType == "number" && isCoordinateField(field)) {
                    " Coordinate fields must be a single numeric scalar, not [x,y], objects, or tuples."
                } else {
                    ""
                }
                throw IllegalArgumentException(
                    "Tool $toolName argument $field expected $expectedType but got ${describeType(value)}.$coordinateHint"
                )
            }
            val enumValues = (schema["enum"] as? JsonArray).orEmpty()
            if (enumValues.isNotEmpty()) {
                val raw = (value as? JsonPrimitive)?.contentOrNull
                if (raw == null || enumValues.none { it.jsonPrimitive.contentOrNull == raw }) {
                    throw IllegalArgumentException(
                        "Tool $toolName argument $field must be one of ${
                            enumValues.joinToString(",") { it.toString() }
                        }"
                    )
                }
            }
        }
    }

    private fun normalizePointArguments(arguments: MutableMap<String, JsonElement>) {
        extractPoint(arguments["x"])?.let { (x, y) ->
            arguments["x"] = buildNumericPrimitive(x)
            if (extractScalarNumber(arguments["y"]) == null) {
                arguments["y"] = buildNumericPrimitive(y)
            }
        }
        extractPoint(arguments["y"])?.let { (x, y) ->
            if (extractScalarNumber(arguments["x"]) == null) {
                arguments["x"] = buildNumericPrimitive(x)
            }
            arguments["y"] = buildNumericPrimitive(y)
        }
    }

    private fun normalizeScrollArguments(arguments: MutableMap<String, JsonElement>) {
        extractPoint(arguments["x1"])?.let { (x, y) ->
            arguments["x1"] = buildNumericPrimitive(x)
            if (extractScalarNumber(arguments["y1"]) == null) {
                arguments["y1"] = buildNumericPrimitive(y)
            }
        }
        extractPoint(arguments["x2"])?.let { (x, y) ->
            arguments["x2"] = buildNumericPrimitive(x)
            if (extractScalarNumber(arguments["y2"]) == null) {
                arguments["y2"] = buildNumericPrimitive(y)
            }
        }
        if ((arguments[OobCanonicalActionSchema.ARG_DIRECTION] as? JsonPrimitive)?.contentOrNull.isNullOrBlank()) {
            inferSwipeDirection(arguments)?.let { direction ->
                arguments[OobCanonicalActionSchema.ARG_DIRECTION] = JsonPrimitive(direction)
            }
        }
    }

    private fun inferSwipeDirection(arguments: Map<String, JsonElement>): String? {
        val x1 = extractScalarNumber(arguments["x1"]) ?: return null
        val y1 = extractScalarNumber(arguments["y1"]) ?: return null
        val x2 = extractScalarNumber(arguments["x2"]) ?: return null
        val y2 = extractScalarNumber(arguments["y2"]) ?: return null
        val dx = x2 - x1
        val dy = y2 - y1
        if (kotlin.math.abs(dx) < 1.0 && kotlin.math.abs(dy) < 1.0) return null
        return if (kotlin.math.abs(dy) >= kotlin.math.abs(dx)) {
            if (dy < 0) "up" else "down"
        } else {
            if (dx < 0) "left" else "right"
        }
    }

    private fun normalizeEnumArguments(
        toolName: String,
        arguments: MutableMap<String, JsonElement>
    ) {
        val properties = propertiesFor(toolName)
        if (properties.isEmpty()) return
        arguments.entries.toList().forEach { (field, value) ->
            val raw = (value as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (raw.isEmpty()) return@forEach
            val enumValues = (properties[field]?.get("enum") as? JsonArray).orEmpty()
            if (enumValues.isEmpty()) return@forEach
            val canonical = enumValues
                .mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
                .firstOrNull { it.equals(raw, ignoreCase = true) }
                ?: return@forEach
            if (canonical != raw) {
                arguments[field] = JsonPrimitive(canonical)
            }
        }
    }

    private fun hasCompleteScrollCoordinates(arguments: Map<String, JsonElement>): Boolean {
        return extractScalarNumber(arguments["x1"]) != null &&
            extractScalarNumber(arguments["y1"]) != null &&
            extractScalarNumber(arguments["x2"]) != null &&
            extractScalarNumber(arguments["y2"]) != null
    }

    private fun extractPoint(value: JsonElement?): Pair<Double, Double>? {
        return when (value) {
            is JsonArray -> {
                if (value.size < 2) return null
                val x = extractScalarNumber(value[0]) ?: return null
                val y = extractScalarNumber(value[1]) ?: return null
                x to y
            }

            is JsonObject -> {
                val x = extractScalarNumber(value["x"]) ?: return null
                val y = extractScalarNumber(value["y"]) ?: return null
                x to y
            }

            is JsonPrimitive -> extractPointFromString(value.contentOrNull)
            else -> null
        }
    }

    private fun extractRange(value: JsonElement?): ScrollCoordinates? {
        return when (value) {
            is JsonArray -> {
                if (value.size >= 4) {
                    val x1 = extractScalarNumber(value[0]) ?: return null
                    val y1 = extractScalarNumber(value[1]) ?: return null
                    val x2 = extractScalarNumber(value[2]) ?: return null
                    val y2 = extractScalarNumber(value[3]) ?: return null
                    return ScrollCoordinates(x1, y1, x2, y2)
                }
                if (value.size >= 2) {
                    val start = extractPoint(value[0]) ?: return null
                    val end = extractPoint(value[1]) ?: return null
                    return ScrollCoordinates(start.first, start.second, end.first, end.second)
                }
                null
            }

            is JsonObject -> {
                val direct = buildScrollCoordinates(
                    x1 = extractScalarNumber(value["x1"]),
                    y1 = extractScalarNumber(value["y1"]),
                    x2 = extractScalarNumber(value["x2"]),
                    y2 = extractScalarNumber(value["y2"])
                )
                if (direct != null) return direct

                null
            }

            is JsonPrimitive -> extractRangeFromString(value.contentOrNull)
            else -> null
        }
    }

    private fun extractPointFromString(raw: String?): Pair<Double, Double>? {
        val normalized = raw?.trim().orEmpty()
        if (normalized.isEmpty()) return null
        if ((normalized.startsWith("[") && normalized.endsWith("]")) ||
            (normalized.startsWith("{") && normalized.endsWith("}"))
        ) {
            val parsed = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(normalized)
            }.getOrNull()
            return extractPoint(parsed)
        }
        val numbers = NUMBER_REGEX.findAll(normalized).mapNotNull { it.value.toDoubleOrNull() }.toList()
        if (numbers.size < 2) return null
        return numbers[0] to numbers[1]
    }

    private fun extractRangeFromString(raw: String?): ScrollCoordinates? {
        val normalized = raw?.trim().orEmpty()
        if (normalized.isEmpty()) return null
        if ((normalized.startsWith("[") && normalized.endsWith("]")) ||
            (normalized.startsWith("{") && normalized.endsWith("}"))
        ) {
            val parsed = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(normalized)
            }.getOrNull()
            return extractRange(parsed)
        }
        val numbers = NUMBER_REGEX.findAll(normalized).mapNotNull { it.value.toDoubleOrNull() }.toList()
        if (numbers.size < 4) return null
        return ScrollCoordinates(numbers[0], numbers[1], numbers[2], numbers[3])
    }

    private fun buildScrollCoordinates(
        x1: Double?,
        y1: Double?,
        x2: Double?,
        y2: Double?
    ): ScrollCoordinates? {
        if (x1 == null || y1 == null || x2 == null || y2 == null) return null
        return ScrollCoordinates(x1, y1, x2, y2)
    }

    private fun extractScalarNumber(value: JsonElement?): Double? {
        return when (value) {
            is JsonPrimitive -> value.contentOrNull?.trim()?.toDoubleOrNull()
            else -> null
        }
    }

    private fun isCoordinateField(field: String): Boolean {
        return field == "x" || field == "y" || field == "x1" || field == "y1" || field == "x2" || field == "y2"
    }

    private fun coerceValue(value: JsonElement, schema: JsonObject): JsonElement {
        val type = schema["type"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        return when (type) {
            "string" -> coerceString(value)
            "number" -> coerceNumber(value) ?: value
            "integer" -> coerceInteger(value) ?: value
            "array" -> coerceArray(value, schema) ?: value
            else -> value
        }
    }

    private fun coerceString(value: JsonElement): JsonElement {
        return when (value) {
            is JsonPrimitive -> {
                JsonPrimitive(value.contentOrNull ?: value.toString())
            }

            else -> JsonPrimitive(value.toString())
        }
    }

    private fun coerceNumber(value: JsonElement): JsonPrimitive? {
        val primitive = value as? JsonPrimitive ?: return null
        val raw = primitive.contentOrNull?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val number = raw.toDoubleOrNull() ?: return null
        val asLong = number.toLong()
        return if (number == asLong.toDouble()) JsonPrimitive(asLong) else JsonPrimitive(number)
    }

    private fun coerceInteger(value: JsonElement): JsonPrimitive? {
        val primitive = value as? JsonPrimitive ?: return null
        val raw = primitive.contentOrNull?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val longValue = raw.toLongOrNull()
            ?: raw.toDoubleOrNull()?.toLong()
            ?: return null
        return JsonPrimitive(longValue)
    }

    private fun coerceArray(value: JsonElement, schema: JsonObject): JsonArray? {
        if (value is JsonArray) return value
        val primitive = value as? JsonPrimitive ?: return null
        val raw = primitive.contentOrNull?.trim().orEmpty()
        if (raw.isEmpty()) return JsonArray(emptyList())
        val itemType = ((schema["items"] as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull
        if (itemType == "string") {
            val parts = raw.split(Regex("[,，、\\n]"))
                .map { it.trim().trim('"', '\'') }
                .filter { it.isNotEmpty() }
                .map(::JsonPrimitive)
            return JsonArray(parts)
        }
        return null
    }

    private fun matchesType(expectedType: String, value: JsonElement): Boolean {
        return when (expectedType) {
            "string" -> value is JsonPrimitive && value.isString
            "integer" -> value is JsonPrimitive && !value.isString && (value.longOrNull != null || value.intOrNull != null)
            "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
            "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
            "object" -> value is JsonObject
            "array" -> value is JsonArray
            else -> true
        }
    }

    private fun describeType(value: JsonElement): String {
        return when (value) {
            is JsonObject -> "object"
            is JsonArray -> "array"
            is JsonNull -> "null"
            is JsonPrimitive -> when {
                value.isString -> "string"
                value.booleanOrNull != null -> "boolean"
                value.intOrNull != null || value.longOrNull != null -> "integer"
                value.doubleOrNull != null -> "number"
                else -> "primitive"
            }
        }
    }

    private fun objectSchema(
        properties: Map<String, JsonObject> = emptyMap(),
        required: List<String> = emptyList(),
        additionalProperties: Boolean = false
    ): JsonObject {
        return buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("additionalProperties", JsonPrimitive(additionalProperties))
            put(
                "properties",
                JsonObject(properties)
            )
            if (required.isNotEmpty()) {
                put(
                    "required",
                    buildJsonArray {
                        required.forEach { add(JsonPrimitive(it)) }
                    }
                )
            }
        }
    }

    private fun jsonSchemaForArg(
        arg: OobCanonicalActionSchema.ArgSpec,
        locale: PromptLocale,
    ): JsonObject {
        return when (arg.type) {
            OobCanonicalActionSchema.Type.STRING -> {
                if (arg.enumValues.isNotEmpty()) {
                    enumSchema(arg.description.text(locale), arg.enumValues)
                } else {
                    stringSchema(arg.description.text(locale))
                }
            }
            OobCanonicalActionSchema.Type.NUMBER -> numberSchema(
                description = arg.description.text(locale),
                minimum = arg.minimum,
                maximum = arg.maximum,
            )
            OobCanonicalActionSchema.Type.INTEGER -> integerSchema(
                description = arg.description.text(locale),
                minimum = arg.minimum,
            )
            OobCanonicalActionSchema.Type.BOOLEAN -> booleanSchema(arg.description.text(locale))
            OobCanonicalActionSchema.Type.OBJECT -> objectSchema(
                additionalProperties = arg.additionalProperties,
            )
            OobCanonicalActionSchema.Type.STRING_ARRAY -> stringArraySchema(arg.description.text(locale))
        }
    }

    private fun stringSchema(description: String): JsonObject {
        return buildJsonObject {
            put("type", JsonPrimitive("string"))
            put("description", JsonPrimitive(description))
        }
    }

    private fun numberSchema(
        description: String,
        minimum: Number? = null,
        maximum: Number? = null,
    ): JsonObject {
        return buildJsonObject {
            put("type", JsonPrimitive("number"))
            put("description", JsonPrimitive(description))
            minimum?.let { put("minimum", JsonPrimitive(it.toDouble())) }
            maximum?.let { put("maximum", JsonPrimitive(it.toDouble())) }
        }
    }

    private fun integerSchema(
        description: String,
        minimum: Number? = null,
    ): JsonObject {
        return buildJsonObject {
            put("type", JsonPrimitive("integer"))
            put("description", JsonPrimitive(description))
            minimum?.let { put("minimum", JsonPrimitive(it.toInt())) }
        }
    }

    private fun booleanSchema(description: String): JsonObject {
        return buildJsonObject {
            put("type", JsonPrimitive("boolean"))
            put("description", JsonPrimitive(description))
        }
    }

    private fun stringArraySchema(description: String): JsonObject {
        return buildJsonObject {
            put("type", JsonPrimitive("array"))
            put("description", JsonPrimitive(description))
            put(
                "items",
                buildJsonObject {
                    put("type", JsonPrimitive("string"))
                }
            )
        }
    }

    private fun enumSchema(description: String, values: List<String>): JsonObject {
        return buildJsonObject {
            put("type", JsonPrimitive("string"))
            put("description", JsonPrimitive(description))
            put(
                "enum",
                JsonArray(values.map(::JsonPrimitive))
            )
        }
    }

    private data class ScrollCoordinates(
        val x1: Double,
        val y1: Double,
        val x2: Double,
        val y2: Double
    )

    private fun buildNumericPrimitive(number: Double): JsonPrimitive {
        val asLong = number.toLong()
        return if (number == asLong.toDouble()) JsonPrimitive(asLong) else JsonPrimitive(number)
    }

    private val NUMBER_REGEX = Regex("""[-+]?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?""")
}
