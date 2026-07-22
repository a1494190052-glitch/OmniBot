package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.runlog.OobActionSchema
import cn.com.omnimind.baselib.llm.ChatCompletionFunction
import cn.com.omnimind.baselib.llm.ChatCompletionTool
import cn.com.omnimind.baselib.i18n.PromptLocale
import kotlinx.serialization.json.Json
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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

object VLMToolDefinitions {
    private const val TOOL_TITLE_FIELD = "tool_title"
    private val argumentJson = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = false
    }
    private val HIDDEN_BASE_TOOL_NAMES = setOf(
        OobActionSchema.TOOL_GET_STATE,
        OobActionSchema.TOOL_CALL_TOOL,
    )

    private fun currentLocale(): PromptLocale = AppLocaleManager.currentPromptLocale()

    private fun t(locale: PromptLocale, zh: String, en: String): String {
        return when (locale) {
            PromptLocale.ZH_CN -> zh
            PromptLocale.EN_US -> en
        }
    }

    private fun OobActionSchema.LocalizedText.text(locale: PromptLocale): String =
        t(locale, zhCn, enUs)

    private fun visibleSchemas(
        locale: PromptLocale,
        allowedToolNames: Set<String>? = null
    ): List<OobActionSchema.ToolSpec> =
        OobActionSchema.modelVisibleTools
            .filterNot { it.name in HIDDEN_BASE_TOOL_NAMES }
            .filter { schema -> allowedToolNames == null || schema.name in allowedToolNames }

    private fun buildParameters(
        schema: OobActionSchema.ToolSpec,
        locale: PromptLocale
    ): JsonObject {
        val args = vlmOutputArgs(schema)
        return objectSchema(
            properties = args.associate { arg -> arg.name to jsonSchemaForArg(arg, locale) },
            required = args.map { it.name },
        )
    }

    private fun vlmOutputArgs(schema: OobActionSchema.ToolSpec): List<OobActionSchema.ArgSpec> =
        schema.args.filter { it.required }

    fun tools(
        locale: PromptLocale = currentLocale(),
        allowedToolNames: Set<String>? = null
    ): List<ChatCompletionTool> =
        visibleSchemas(locale, allowedToolNames).map { schema ->
            ChatCompletionTool(
                function = ChatCompletionFunction(
                    name = schema.name,
                    description = schema.description.text(locale),
                    parameters = buildParameters(schema, locale),
                    strict = true
                )
            )
        }

    fun dynamicToolsFromDefinitions(definitions: List<JsonObject>): List<ChatCompletionTool> {
        return definitions.mapNotNull { definition ->
            val function = definition["function"] as? JsonObject ?: return@mapNotNull null
            if (isHiddenFunctionTool(function)) return@mapNotNull null
            val name = function["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (name.isBlank()) return@mapNotNull null
            if (isInternalRuntimeToolName(name)) return@mapNotNull null
            ChatCompletionTool(
                function = ChatCompletionFunction(
                    name = name,
                    description = function["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    parameters = sanitizeVlmDynamicFunctionParameters(
                        (function["parameters"] as? JsonObject) ?: JsonObject(emptyMap())
                    ),
                    strict = true
                )
            )
        }
    }

    fun dynamicFunctionToolNamesFromDefinitions(definitions: List<JsonObject>): Set<String> {
        return definitions.mapNotNull { definition ->
            val function = definition["function"] as? JsonObject ?: return@mapNotNull null
            if (!isHiddenFunctionTool(function)) return@mapNotNull null
            val name = function["name"]?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            if (isInternalRuntimeToolName(name)) return@mapNotNull null
            name
        }.toSet()
    }

    fun dynamicFunctionToolMappingsFromDefinitions(definitions: List<JsonObject>): Map<String, String> {
        return definitions.mapNotNull { definition ->
            val function = definition["function"] as? JsonObject ?: return@mapNotNull null
            if (isHiddenFunctionTool(function)) return@mapNotNull null
            val name = function["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (name.isEmpty()) return@mapNotNull null
            if (isInternalRuntimeToolName(name)) return@mapNotNull null
            val functionId = firstNonBlank(definition["function_id"])
            if (functionId.isEmpty()) return@mapNotNull null
            name to functionId
        }.toMap()
    }

    fun dynamicFunctionRequiredArgumentsFromDefinitions(definitions: List<JsonObject>): Map<String, Set<String>> {
        return definitions.mapNotNull { definition ->
            val function = definition["function"] as? JsonObject ?: return@mapNotNull null
            if (isHiddenFunctionTool(function)) return@mapNotNull null
            val name = function["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (name.isEmpty()) return@mapNotNull null
            if (isInternalRuntimeToolName(name)) return@mapNotNull null
            val parameters = sanitizeVlmDynamicFunctionParameters(
                (function["parameters"] as? JsonObject) ?: JsonObject(emptyMap())
            )
            val required = (parameters["required"] as? JsonArray)
                .orEmpty()
                .mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
                .filterNot { it == TOOL_TITLE_FIELD }
                .toSet()
            name to required
        }.toMap()
    }

    private fun isHiddenFunctionTool(function: JsonObject): Boolean {
        val toolType = function["tool_type"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val modelVisible = function["model_visible"]?.jsonPrimitive?.booleanOrNull
        if (modelVisible == false) return true
        return toolType.equals("oob_function", ignoreCase = true)
    }

    private fun isInternalRuntimeToolName(name: String): Boolean {
        val normalized = name.trim()
            .removePrefix("functions.")
            .removePrefix("function.")
            .trim()
            .lowercase()
        return normalized == OobActionSchema.TOOL_CALL_TOOL
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
        val guides = visibleSchemas(locale).joinToString(separator = "\n") { vlmPromptGuide(it, locale) }
        return buildString {
            appendLine(guides)
            appendLine(actionChoiceGuide(locale, null))
            append(
                t(
                    locale,
                    "注意：每个 tool call 的 JSON 参数必须是严格合法的 object，并满足所选工具 schema.required。Function replay 由本地 runtime 自动处理；不要输出 call_tool、function_id 或隐藏 Function 工具。若本轮 tools[] 包含 run_recalled_workflow_*，它是本轮已召回工作流工具，明显匹配当前目标时优先调用，否则继续普通 UI action。schema.required 中的坐标字段必须是 0..1000 相对坐标，分别写入 x / y / x1 / y1 / x2 / y2，每个字段都只能是单个数值；不要返回 [x,y]、coordinates、对象，或 \"x\": 827, 76 这类非法格式。Action、RunLog 和 Function 始终保存相对坐标；只有 Android 执行动作时才转换一次为当前屏幕像素。wait 只在页面明确加载、动画或等待外部状态时使用。",
                    "Important: every tool call JSON argument value must be a strict object and satisfy the selected tool's schema.required. Function replay is handled by the local runtime; do not emit call_tool, function_id, or hidden Function tools. If this turn's tools[] includes run_recalled_workflow_*, it is a recalled workflow tool for this turn; prefer it when it clearly matches the current goal, otherwise choose an ordinary UI action. Coordinate fields in schema.required must be 0..1000 relative coordinates, written into x / y / x1 / y1 / x2 / y2 as separate scalar fields; each field must be a single numeric scalar. Do not emit [x,y], coordinates, objects, or invalid forms such as \"x\": 827, 76. Action, RunLog, and Function always store relative coordinates; conversion to current-screen pixels happens exactly once at Android action dispatch. Use wait only when the page is clearly loading, animating, or waiting for an external state change."
                )
            )
        }
    }

    fun renderCompactActionSchemaGuide(
        locale: PromptLocale = currentLocale(),
        allowedToolNames: Set<String>? = null
    ): String {
        val selectedSchemas = visibleSchemas(locale, allowedToolNames)
        val selectedNames = selectedSchemas.mapTo(linkedSetOf()) { it.name }
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
                    "页面状态只用于选择目标；输出参数只能包含所选工具 schema.properties 中列出的字段。",
                    "Page state is only for choosing the target; output arguments may contain only fields listed in the selected tool's schema.properties."
                )
            )
            appendLine(actionChoiceGuide(locale, selectedNames))
            if (dynamicAllowedNames.isNotEmpty()) {
                appendLine(
                    t(
                        locale,
                        "已召回工作流：${dynamicAllowedNames.joinToString(", ")} 明显匹配当前子目标时优先调用；否则继续选择普通 UI action。",
                        "Recalled workflows: prefer ${dynamicAllowedNames.joinToString(", ")} when one clearly matches the current sub-goal; otherwise choose an ordinary UI action."
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
                    "黑屏/空白但 page state 或 visible_texts 有目标控件时，按这些证据继续选择工具；不要输出刷新状态、等待或空操作。",
                    "If the screenshot is black/blank but page state or visible_texts contains the target control, continue from that evidence; do not output refresh-state, wait, or no-op actions."
                )
            )
        }.trim()
    }

    private fun actionChoiceGuide(locale: PromptLocale, allowedToolNames: Set<String>?): String {
        val visibleNames = allowedToolNames
            ?: visibleSchemas(locale).mapTo(linkedSetOf()) { it.name }
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
            val userTools = listOf("info").filter(::has)
            if (userTools.isNotEmpty()) {
                add("${userTools.joinToString("/")} 用于必须询问用户")
            }
            val fallbackTools = listOf("abort").filter(::has)
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
            val userTools = listOf("info").filter(::has)
            if (userTools.isNotEmpty()) {
                add("use ${userTools.joinToString("/")} only when user input is required")
            }
            val fallbackTools = listOf("abort").filter(::has)
            if (fallbackTools.isNotEmpty()) {
                add("use ${fallbackTools.joinToString("/")} when the context mismatches or cannot continue")
            }
        }.joinToString("; ")

        return t(locale, "操作选择：$zh。", "Action choice: $en.")
    }

    private fun vlmPromptGuide(
        schema: OobActionSchema.ToolSpec,
        locale: PromptLocale
    ): String {
        return when (schema.name) {
            OobActionSchema.TOOL_CLICK -> t(
                locale,
                "- click(target_description, x, y): 点击可见目标；x/y 是 required 的 0..1000 相对坐标。",
                "- click(target_description, x, y): Tap a visible target; x/y are required 0..1000 relative coordinates."
            )
            OobActionSchema.TOOL_LONG_PRESS -> t(
                locale,
                "- long_press(target_description, x, y): 长按目标；x/y 是 required 的 0..1000 相对坐标。",
                "- long_press(target_description, x, y): Long-press a target; x/y are required 0..1000 relative coordinates."
            )
            OobActionSchema.TOOL_INPUT_TEXT -> t(
                locale,
                "- input_text(target_description, text, x, y): 向输入框输入；x/y 是 required 的 0..1000 相对坐标。",
                "- input_text(target_description, text, x, y): Type into an input field; x/y are required 0..1000 relative coordinates."
            )
            OobActionSchema.TOOL_SWIPE -> t(
                locale,
                "- swipe(target_description, direction, x1, y1, x2, y2): 在屏幕或可滚动区域内滑动；direction 和 x1/y1/x2/y2 必须满足 schema.required。",
                "- swipe(target_description, direction, x1, y1, x2, y2): Swipe on the screen or in a scrollable region; direction and x1/y1/x2/y2 must satisfy schema.required."
            )
            OobActionSchema.TOOL_WAIT -> t(
                locale,
                "- wait(): 只在页面明确处于加载、动画或等待外部状态变化时使用。",
                "- wait(): Use only when the page is clearly loading, animating, or waiting for an external state change."
            )
            OobActionSchema.TOOL_FINISHED -> t(
                locale,
                "- finished(): 仅在当前页面或上一轮工具结果直接证明目标完成时调用；不确定就继续执行下一步。",
                "- finished(): Call only when the current page or previous tool result directly proves completion; if uncertain, continue with the next action."
            )
            OobActionSchema.TOOL_ABORT -> t(
                locale,
                "- abort(): 在任务无法继续时终止。",
                "- abort(): Abort when the task cannot continue."
            )
            else -> schema.promptGuide.text(locale)
        }
    }

    fun responseContract(locale: PromptLocale = currentLocale()): String {
        return when (locale) {
            PromptLocale.ZH_CN ->
                """{"summary":"约20字本步摘要"}"""
            PromptLocale.EN_US ->
                """{"summary":"about 20 words for this step"}"""
        }
    }

    fun toolSpec(name: String): OobActionSchema.ToolSpec? =
        OobActionSchema.modelVisibleTools
            .filterNot { it.name in HIDDEN_BASE_TOOL_NAMES }
            .firstOrNull { it.name == name }

    fun propertiesFor(toolName: String, locale: PromptLocale = currentLocale()): Map<String, JsonObject> {
        val schema = toolSpec(toolName) ?: return emptyMap()
        return vlmOutputArgs(schema).associate { arg -> arg.name to jsonSchemaForArg(arg, locale) }
    }

    fun requiredFieldsFor(toolName: String): List<String> {
        val schema = toolSpec(toolName) ?: return emptyList()
        return vlmOutputArgs(schema).map { it.name }
    }

    fun describeInvalidToolCall(
        toolCall: AssistantToolCall,
        message: String,
        requiredFieldsOverride: Set<String>? = null,
    ): VLMToolCallFailure {
        val toolName = toolCall.function.name.trim().takeIf(String::isNotEmpty)
        val parsedArguments = toolName?.let {
            runCatching {
                parseRawArgumentsObject(
                    toolName = it,
                    rawArguments = toolCall.function.arguments,
                    allowEmpty = true,
                )
            }.getOrNull()
        }
        val requiredFields = when {
            requiredFieldsOverride != null -> requiredFieldsOverride.sorted()
            toolName != null -> requiredFieldsFor(toolName)
            else -> emptyList()
        }
        val providedFields = parsedArguments?.keys?.sorted().orEmpty()
        return VLMToolCallFailure(
            code = if (parsedArguments == null) "invalid_arguments_json" else "invalid_arguments",
            toolName = toolName,
            requiredFields = requiredFields,
            providedFields = providedFields,
            argumentTypes = parsedArguments
                ?.entries
                ?.sortedBy { it.key }
                ?.associate { (field, value) -> field to describeType(value) }
                .orEmpty(),
            missingFields = parsedArguments?.let { parsed ->
                requiredFields.filter { field -> parsed[field] == null || parsed[field] is JsonNull }
            }.orEmpty(),
            safeArgumentsPreview = parsedArguments?.let(::safeArgumentsPreview)
                ?: "invalid_json(chars=${toolCall.function.arguments.length})",
            message = sanitizeToolCallFailureMessage(message),
        )
    }

    fun sanitizeToolCallFailureMessage(message: String): String {
        return message
            .replace(Regex(";\\s*raw=.*$", RegexOption.DOT_MATCHES_ALL), "; raw=<redacted>")
            .take(MAX_SAFE_FAILURE_MESSAGE_CHARS)
    }

    fun safeToolCallSummary(toolCalls: List<AssistantToolCall>): String {
        if (toolCalls.isEmpty()) return "[]"
        return toolCalls.joinToString(prefix = "[", postfix = "]") { toolCall ->
            val toolName = toolCall.function.name.trim().ifBlank { "<missing>" }
            val arguments = runCatching {
                parseRawArgumentsObject(toolName, toolCall.function.arguments, allowEmpty = true)
            }.getOrNull()
            val shape = arguments
                ?.entries
                ?.sortedBy { it.key }
                ?.joinToString(",") { (field, value) -> "$field:${describeType(value)}" }
                ?: "invalid_json"
            "$toolName{$shape}"
        }
    }

    fun parseArguments(toolName: String, rawArguments: String): JsonObject {
        val parsed = parseRawArgumentsObject(toolName, rawArguments)
        validateArguments(toolName, parsed)
        return parsed
    }

    internal fun parseRawArgumentsObject(
        toolName: String,
        rawArguments: String,
        allowEmpty: Boolean = false
    ): JsonObject {
        val normalized = rawArguments.trim()
        if (normalized.isEmpty()) {
            if (allowEmpty) return JsonObject(emptyMap())
            throw IllegalArgumentException(
                "Invalid tool arguments JSON for $toolName: function.arguments must be a JSON object; raw=${previewRawArguments(rawArguments)}"
            )
        }
        val element = runCatching { argumentJson.parseToJsonElement(normalized) }.getOrElse { error ->
            throw IllegalArgumentException(
                "Invalid tool arguments JSON for $toolName: ${error.message ?: "unknown parse failure"}; raw=${previewRawArguments(rawArguments)}",
                error
            )
        }
        return element as? JsonObject
            ?: throw IllegalArgumentException("Invalid tool arguments JSON for $toolName: function.arguments must be a JSON object")
    }

    fun validateArguments(toolName: String, arguments: JsonObject) {
        val schema = toolSpec(toolName)
            ?: throw IllegalArgumentException("Unknown VLM tool: $toolName")
        val properties = propertiesFor(toolName)
        val requiredFields = requiredFieldsFor(toolName)
        requiredFields.forEach { field ->
            if (arguments[field] == null || arguments[field] is JsonNull) {
                throw IllegalArgumentException("Tool $toolName missing required argument: $field")
            }
        }

        arguments.entries.forEach { (field, value) ->
            val fieldSchema = properties[field] ?: run {
                throw IllegalArgumentException("Tool $toolName has unknown argument: $field")
            }
            val expectedType = fieldSchema["type"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
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
            val enumValues = (fieldSchema["enum"] as? JsonArray).orEmpty()
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

    private fun previewRawArguments(raw: String, maxLen: Int = 240): String {
        val normalized = raw.replace(Regex("\\s+"), " ").trim()
        return if (normalized.length <= maxLen) normalized else normalized.take(maxLen) + "..."
    }

    private fun isCoordinateField(field: String): Boolean {
        return field == "x" || field == "y" || field == "x1" || field == "y1" || field == "x2" || field == "y2"
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

    private fun safeArgumentsPreview(arguments: JsonObject): String {
        val safeArguments = buildJsonObject {
            arguments.entries.sortedBy { it.key }.forEach { (field, value) ->
                put(field, safePreviewValue(field, value))
            }
        }
        return safeArguments.toString().take(MAX_SAFE_ARGUMENT_PREVIEW_CHARS)
    }

    private fun safePreviewValue(field: String, value: JsonElement): JsonElement {
        if (field.lowercase() in SENSITIVE_ARGUMENT_FIELDS) {
            return JsonPrimitive("<redacted>")
        }
        if (field.lowercase() !in SAFE_ARGUMENT_VALUE_FIELDS) {
            return JsonPrimitive("<${describeType(value)}>")
        }
        return when (value) {
            is JsonPrimitive -> {
                if (value.isString) {
                    JsonPrimitive(value.contentOrNull.orEmpty().take(MAX_SAFE_STRING_VALUE_CHARS))
                } else {
                    value
                }
            }
            is JsonArray -> JsonPrimitive("<array:${value.size}>")
            is JsonObject -> JsonPrimitive("<object:${value.keys.sorted().joinToString(",")}>")
            is JsonNull -> JsonNull
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
        arg: OobActionSchema.ArgSpec,
        locale: PromptLocale,
    ): JsonObject {
        return when (arg.type) {
            OobActionSchema.Type.STRING -> {
                if (arg.enumValues.isNotEmpty()) {
                    enumSchema(arg.description.text(locale), arg.enumValues)
                } else {
                    stringSchema(arg.description.text(locale))
                }
            }
            OobActionSchema.Type.NUMBER -> numberSchema(
                description = arg.description.text(locale),
                minimum = arg.minimum,
                maximum = arg.maximum,
            )
            OobActionSchema.Type.INTEGER -> integerSchema(
                description = arg.description.text(locale),
                minimum = arg.minimum,
            )
            OobActionSchema.Type.BOOLEAN -> booleanSchema(arg.description.text(locale))
            OobActionSchema.Type.OBJECT -> objectSchema(
                additionalProperties = arg.additionalProperties,
            )
            OobActionSchema.Type.STRING_ARRAY -> stringArraySchema(arg.description.text(locale))
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

    private const val MAX_SAFE_ARGUMENT_PREVIEW_CHARS = 800
    private const val MAX_SAFE_FAILURE_MESSAGE_CHARS = 1200
    private const val MAX_SAFE_STRING_VALUE_CHARS = 120
    private val SENSITIVE_ARGUMENT_FIELDS = setOf(
        "text",
        "content",
        "value",
        "prompt",
        "arguments",
    )
    private val SAFE_ARGUMENT_VALUE_FIELDS = setOf(
        "target_description",
        "x",
        "y",
        "x1",
        "y1",
        "x2",
        "y2",
        "node_id",
        "node_resource_id",
        "direction",
        "distance",
        "duration_ms",
        "key",
        "package_name",
    )

}
