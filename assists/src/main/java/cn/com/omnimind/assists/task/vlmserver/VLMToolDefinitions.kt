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

    private fun buildToolSpecs(
        locale: PromptLocale,
        allowedToolNames: Set<String>? = null
    ): List<ToolSpec> =
        OobCanonicalActionSchema.modelVisibleTools
            .filterNot { it.name in HIDDEN_BASE_TOOL_NAMES }
            .filter { schema -> allowedToolNames == null || schema.name in allowedToolNames }
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

    private fun toolSpecs(
        locale: PromptLocale = currentLocale(),
        allowedToolNames: Set<String>? = null
    ): List<ToolSpec> {
        return buildToolSpecs(locale, allowedToolNames)
    }

    fun tools(
        locale: PromptLocale = currentLocale(),
        allowedToolNames: Set<String>? = null
    ): List<ChatCompletionTool> {
        return toolSpecs(locale, allowedToolNames).map { spec ->
            ChatCompletionTool(
                function = ChatCompletionFunction(
                    name = spec.name,
                    description = spec.description,
                    parameters = spec.parameters,
                    strict = true
                )
            )
        }
    }

    fun dynamicToolsFromDefinitions(definitions: List<JsonObject>): List<ChatCompletionTool> {
        return definitions.mapNotNull { definition ->
            val function = definition["function"] as? JsonObject ?: return@mapNotNull null
            if (isHiddenFunctionTool(function)) return@mapNotNull null
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

    fun dynamicFunctionToolNamesFromDefinitions(definitions: List<JsonObject>): Set<String> {
        return definitions.mapNotNull { definition ->
            val function = definition["function"] as? JsonObject ?: return@mapNotNull null
            if (!isHiddenFunctionTool(function)) return@mapNotNull null
                function["name"]?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }.toSet()
    }

    fun dynamicFunctionToolMappingsFromDefinitions(definitions: List<JsonObject>): Map<String, String> {
        return definitions.mapNotNull { definition ->
            val function = definition["function"] as? JsonObject ?: return@mapNotNull null
            if (isHiddenFunctionTool(function)) return@mapNotNull null
            val name = function["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (name.isEmpty()) return@mapNotNull null
            val functionId = firstNonBlank(
                definition["function_id"],
                definition["functionId"],
                function["function_id"],
                function["functionId"],
            )
            if (functionId.isEmpty()) return@mapNotNull null
            name to functionId
        }.toMap()
    }

    private fun isHiddenFunctionTool(function: JsonObject): Boolean {
        val toolType = function["toolType"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val modelVisible = function["model_visible"]?.jsonPrimitive?.booleanOrNull
        if (modelVisible == false) return true
        return toolType.equals("oob_function", ignoreCase = true)
    }

    private fun firstNonBlank(vararg values: JsonElement?): String {
        values.forEach { value ->
            val text = (value as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (text.isNotEmpty()) return text
        }
        return ""
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
            appendLine(actionChoiceGuide(locale, null))
            append(
                t(
                    locale,
                    "注意：每个 tool call 的 JSON 参数必须是严格合法的 object，并满足所选工具 schema.required。Function replay 由本地 runtime 自动处理；不要输出 call_tool、function_id 或隐藏 Function 工具。若本轮 tools[] 包含 run_recalled_workflow_*，它是当前页面可选的已召回工作流工具，可与普通 UI action 同级选择。schema.required 中的坐标字段必须是 0..1000 相对坐标，分别写入 x / y / x1 / y1 / x2 / y2，不要写成 \"x\": 827, 76 这类非法格式。系统会在执行前解码为屏幕绝对像素，本地记录始终保存绝对像素。wait 只在页面明确加载、动画或等待外部状态时使用。",
                    "Important: every tool call JSON argument value must be a strict object and satisfy the selected tool's schema.required. Function replay is handled by the local runtime; do not emit call_tool, function_id, or hidden Function tools. If this turn's tools[] includes run_recalled_workflow_*, it is an optional recalled workflow tool for the current page and can be chosen at the same level as ordinary UI actions. Coordinate fields in schema.required must be 0..1000 relative coordinates, written into x / y / x1 / y1 / x2 / y2 as separate scalar fields. Do not emit invalid forms such as \"x\": 827, 76. The system decodes coordinates to screen absolute pixels before execution, and local records always store absolute pixels. Use wait only when the page is clearly loading, animating, or waiting for an external state change."
                )
            )
        }
    }

    fun renderCompactActionSchemaGuide(
        locale: PromptLocale = currentLocale(),
        allowedToolNames: Set<String>? = null
    ): String {
        val selectedSpecs = toolSpecs(locale, allowedToolNames)
        val selectedNames = selectedSpecs.mapTo(linkedSetOf()) { it.name }
        val dynamicAllowedNames = allowedToolNames
            .orEmpty()
            .map(String::trim)
            .filter { it.startsWith("run_recalled_workflow_") && it !in selectedNames }
        val toolNames = (selectedNames + dynamicAllowedNames).joinToString(separator = ", ")
        return buildString {
            appendLine("${t(locale, "本轮允许工具", "Allowed tools this turn")}: $toolNames")
            appendLine(
                t(
                    locale,
                    "输出约束：只返回 tools[] 中恰好一个原生 tool_call；function.arguments 必须满足所选工具的 JSON schema，schema.required 字段必须全部填写；assistant.content 可为空，若返回只能是约20字 summary；不要输出其他 JSON 字段、Markdown、旧格式 action/swipe/coordinate/coordinate2，或 tools[] 外的工具名。",
                    "Output constraint: return exactly one native tool_call from tools[]. function.arguments must satisfy the selected tool JSON schema, and every schema.required field must be present. assistant.content may be empty; if present it must be an about-20-word summary only. Do not output other JSON fields, Markdown, legacy action/swipe/coordinate/coordinate2 formats, or tool names outside tools[]."
                )
            )
            appendLine(
                t(
                    locale,
                    "可选的 element_index/scrollable_index 只能作为补充 grounding 线索，不能替代所选工具 schema.required 中的坐标或其他必填字段。",
                    "Optional element_index/scrollable_index values are supplemental grounding hints only; they cannot replace coordinates or other fields listed in the selected tool's schema.required."
                )
            )
            appendLine(actionChoiceGuide(locale, selectedNames))
            if (dynamicAllowedNames.isNotEmpty()) {
                appendLine(
                    t(
                        locale,
                        "已召回工作流：${dynamicAllowedNames.joinToString(", ")} 可在它明显匹配当前子目标时直接调用；否则继续选择普通 UI action。",
                        "Recalled workflows: ${dynamicAllowedNames.joinToString(", ")} may be called when one clearly matches the current sub-goal; otherwise choose an ordinary UI action."
                    )
                )
            }
            appendLine(
                t(
                    locale,
                    "完成判断：每轮先判断用户目标是否已经达成；若已达成，必须调用 finished，不要重复点击同一目标。对“点击/聚焦/打开搜索框或输入框”类目标，只要当前页面已进入搜索页、目标输入框已聚焦，或目标控件已处于可输入状态，就视为完成。",
                    "Completion rule: first decide whether the user's goal is already satisfied this turn. If it is, call finished and do not click the same target again. For goals like click/focus/open a search box or input field, treat the goal as complete once the current page is a search page, the target input is focused, or the target control is ready for typing."
                )
            )
            append(
                t(
                    locale,
                    "黑屏/空白但 indexed evidence 或 visible_texts 有目标控件时，按这些证据继续选择工具；不要输出刷新状态、等待或空操作。",
                    "If the screenshot is black/blank but indexed evidence or visible_texts contains the target control, continue from that evidence; do not output refresh-state, wait, or no-op actions."
                )
            )
        }.trim()
    }

    private fun actionChoiceGuide(locale: PromptLocale, allowedToolNames: Set<String>?): String {
        val visibleNames = allowedToolNames
            ?: toolSpecs(locale).mapTo(linkedSetOf()) { it.name }
        fun has(name: String): Boolean = name in visibleNames

        val zh = buildList {
            if (has("click")) add("click 用于点击可见按钮、列表项、标签、搜索框或输入框聚焦")
            if (has("input_text")) add("input_text 用于向可见输入目标输入已知文本")
            if (has("long_press")) add("long_press 只用于上下文菜单、拖拽起点或页面明确需要长按")
            if (has("swipe")) add("swipe 用于目标不在当前可见区域、列表翻页或横向切换")
            if (has("open_app")) add("open_app 用于当前不在目标应用且目标包名明确")
            if (has("press_key")) add("press_key 用于系统 back/home/enter")
            if (has("wait")) add("wait 只用于页面明确加载、动画或等待外部状态")
            if (has("finished")) add("finished 只在目标已完成")
            val userTools = listOf("info", "require_user_choice", "require_user_confirmation")
                .filter(::has)
            if (userTools.isNotEmpty()) {
                add("${userTools.joinToString("/")} 用于必须询问用户")
            }
            val fallbackTools = listOf("feedback", "abort").filter(::has)
            if (fallbackTools.isNotEmpty()) {
                add("${fallbackTools.joinToString("/")} 用于当前上下文不匹配或无法继续")
            }
        }.joinToString("；")

        val en = buildList {
            if (has("click")) add("use click for visible buttons, list items, tabs, search boxes, or focusing an input field")
            if (has("input_text")) add("use input_text when known text must be typed into a visible input target")
            if (has("long_press")) add("use long_press only for context menus, drag starts, or screens that clearly require a long press")
            if (has("swipe")) add("use swipe when the target is not currently visible, a list must move, or horizontal switching is needed")
            if (has("open_app")) add("use open_app when the target app is not current and its package is known")
            if (has("press_key")) add("use press_key for system back/home/enter")
            if (has("wait")) add("use wait only for clear loading, animation, or external state")
            if (has("finished")) add("use finished only after the goal is complete")
            val userTools = listOf("info", "require_user_choice", "require_user_confirmation")
                .filter(::has)
            if (userTools.isNotEmpty()) {
                add("use ${userTools.joinToString("/")} only when user input is required")
            }
            val fallbackTools = listOf("feedback", "abort").filter(::has)
            if (fallbackTools.isNotEmpty()) {
                add("use ${fallbackTools.joinToString("/")} when the context mismatches or cannot continue")
            }
        }.joinToString("; ")

        return t(locale, "操作选择：$zh。", "Action choice: $en.")
    }

    fun responseContract(locale: PromptLocale = currentLocale()): String {
        return when (locale) {
            PromptLocale.ZH_CN ->
                """{"summary":"约20字本步摘要"}"""
            PromptLocale.EN_US ->
                """{"summary":"about 20 words for this step"}"""
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
        if (toolName == OobCanonicalActionSchema.TOOL_SWIPE) {
            inferSwipeDirection(normalized)?.let { direction ->
                normalized[OobCanonicalActionSchema.ARG_DIRECTION] = JsonPrimitive(direction)
            }
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

    private fun inferSwipeDirection(arguments: Map<String, JsonElement>): String? {
        if (!(arguments[OobCanonicalActionSchema.ARG_DIRECTION] as? JsonPrimitive)?.contentOrNull.isNullOrBlank()) {
            return null
        }
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

}
