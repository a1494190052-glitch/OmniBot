package cn.com.omnimind.bot.agent

import android.content.Context
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.shizuku.PrivilegedActionPolicy
import cn.com.omnimind.baselib.shizuku.ShizukuBackend
import cn.com.omnimind.baselib.shizuku.ShizukuCapabilityManager
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.mcp.RemoteMcpDiscoveredServer
import cn.com.omnimind.bot.omniflow.OobFunctionSkillProfile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class AgentToolRegistry(
    private val context: Context,
    discoveredServers: List<RemoteMcpDiscoveredServer>,
    conversationMode: String = AgentConversationModePolicy.NORMAL_MODE,
    dynamicDefinitions: List<JsonObject> = emptyList(),
    toolExposurePolicy: AgentToolExposurePolicy = AgentToolExposurePolicy.DEFAULT,
) : AgentToolCatalog {
    data class RuntimeToolDescriptor(
        val name: String,
        val displayName: String,
        val toolType: String,
        val serverName: String? = null
    )

    private val tag = "AgentToolRegistry"
    private val toolSchemas = linkedMapOf<String, JsonObject>()
    private val runtimeDescriptors = linkedMapOf<String, RuntimeToolDescriptor>()
    override val toolsForModel: List<ChatCompletionTool>

    init {
        val locale = runCatching { AppLocaleManager.resolvePromptLocale(context) }
            .getOrDefault(AppLocaleManager.currentPromptLocale())
        val shizukuStatus = runCatching { ShizukuCapabilityManager.get(context).getStatus() }
            .onFailure { OmniLog.w(tag, "resolve shizuku status failed: ${it.message}") }
            .getOrNull()
        val runtimeDefinitions = mutableListOf<JsonObject>()
        runtimeDefinitions.addAll(AgentToolDefinitions.staticTools(locale))
        if (shizukuStatus?.isGranted() == true) {
            val privilegedVisibleActions = shizukuStatus.availableActions.ifEmpty {
                PrivilegedActionPolicy.visibleAgentActions(
                    if (shizukuStatus.backend == ShizukuBackend.ROOT) {
                        ShizukuBackend.ROOT
                    } else {
                        ShizukuBackend.ADB
                    }
                )
            }
            runtimeDefinitions.add(
                AgentToolDefinitions.androidPrivilegedActionTool(
                    visibleActions = privilegedVisibleActions,
                    backend = shizukuStatus.backend,
                    locale = locale
                )
            )
            runtimeDefinitions.add(
                AgentToolDefinitions.androidPrivilegedSessionStartTool(
                    backend = shizukuStatus.backend,
                    locale = locale
                )
            )
            runtimeDefinitions.add(
                AgentToolDefinitions.androidPrivilegedSessionExecTool(
                    backend = shizukuStatus.backend,
                    locale = locale
                )
            )
            runtimeDefinitions.add(
                AgentToolDefinitions.androidPrivilegedSessionReadTool(
                    backend = shizukuStatus.backend,
                    locale = locale
                )
            )
            runtimeDefinitions.add(
                AgentToolDefinitions.androidPrivilegedSessionStopTool(
                    backend = shizukuStatus.backend,
                    locale = locale
                )
            )
        }
        if (OobFunctionSkillProfile.isProfile(toolExposurePolicy.profile)) {
            runtimeDefinitions.addAll(OobFunctionSkillProfile.staticToolDefinitions(locale))
        } else {
            runtimeDefinitions.addAll(OobFunctionSkillProfile.runtimeToolDefinitions(locale))
        }
        runtimeDefinitions.addAll(AgentToolDefinitions.memoryTools(locale))
        runtimeDefinitions.addAll(AgentToolDefinitions.subagentTools(locale))
        runtimeDefinitions.addAll(
            OobFunctionSkillProfile.dynamicFunctionToolDefinitions(
                context = context,
                locale = locale,
                forceInclude = OobFunctionSkillProfile.isProfile(toolExposurePolicy.profile)
            )
        )

        runtimeDefinitions.addAll(dynamicDefinitions.filterNot(::isModelHiddenDynamicTool))

        val conversationFilteredDefinitions = AgentConversationModePolicy
            .filterToolDefinitionsForConversationMode(runtimeDefinitions, conversationMode)
        val explicitAllowedToolNames = toolExposurePolicy.normalizedAllowedTools()
        val allowedToolNames = toolExposurePolicy.effectiveAllowedTools()
        val includeOobFunctionToolsForProfile = explicitAllowedToolNames.isNullOrEmpty() &&
            toolExposurePolicy.isLightweightProfile()
        val filteredDefinitions = filterToolDefinitionsForExposurePolicy(
            definitions = conversationFilteredDefinitions,
            allowedToolNames = allowedToolNames,
            includeOobFunctionToolsForProfile = includeOobFunctionToolsForProfile,
            toolProfile = toolExposurePolicy.profile,
        )

        val toolsByName = linkedMapOf<String, ChatCompletionTool>()
        filteredDefinitions.forEach { definition ->
            val function = definition["function"] as? JsonObject ?: return@forEach
            val name = function["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (name.isBlank()) return@forEach
            if (!MODEL_TOOL_NAME_REGEX.matches(name)) {
                OmniLog.w(tag, "skip invalid model tool name: $name")
                return@forEach
            }
            val description = function["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val parameters = (function["parameters"] as? JsonObject) ?: JsonObject(emptyMap())
            val displayName = function["displayName"]?.jsonPrimitive?.contentOrNull?.trim()
                .takeUnless { it.isNullOrBlank() } ?: name
            val toolType = function["toolType"]?.jsonPrimitive?.contentOrNull?.trim()
                .takeUnless { it.isNullOrBlank() } ?: "builtin"
            val serverName = function["serverName"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() }

            toolSchemas[name] = parameters
            runtimeDescriptors[name] = RuntimeToolDescriptor(
                name = name,
                displayName = displayName,
                toolType = toolType,
                serverName = serverName
            )
            toolsByName[name] = ChatCompletionTool(
                function = ChatCompletionFunction(
                    name = name,
                    description = description,
                    parameters = parameters
                )
            )
        }
        toolsForModel = toolsByName.values.toList()

        // Debug dump: full registered tool list to verify which ones the LLM actually receives.
        OmniLog.i(
            tag,
                "registered_tools count=${toolsForModel.size} " +
                "conversationMode=$conversationMode " +
                "tool_profile=${toolExposurePolicy.profile.orEmpty()} " +
                "tool_allowlist_size=${allowedToolNames?.size ?: 0} " +
                "subagent_present=${"subagent_dispatch" in runtimeDescriptors.keys} " +
                "memory_load_present=${"memory_load" in runtimeDescriptors.keys} " +
                "names=[${runtimeDescriptors.keys.joinToString(",")}]"
        )
    }

    private fun filterToolDefinitionsForExposurePolicy(
        definitions: List<JsonObject>,
        allowedToolNames: Set<String>?,
        includeOobFunctionToolsForProfile: Boolean,
        toolProfile: String?,
    ): List<JsonObject> {
        if (allowedToolNames.isNullOrEmpty()) {
            return definitions
        }
        return definitions.filter { definition ->
            val function = definition["function"] as? JsonObject
            val toolName = function
                ?.get("name")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                .orEmpty()
            val toolType = function
                ?.get("toolType")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                .orEmpty()
            toolName in allowedToolNames ||
                (
                    includeOobFunctionToolsForProfile &&
                        OobFunctionSkillProfile.shouldKeepDynamicFunctionForProfile(
                            profile = toolProfile,
                            toolType = toolType
                        )
                )
        }
    }

    private fun isModelHiddenDynamicTool(definition: JsonObject): Boolean {
        val function = definition["function"] as? JsonObject ?: return false
        return function["model_visible"]?.jsonPrimitive?.booleanOrNull == false
    }

    override fun runtimeDescriptor(toolName: String): RuntimeToolDescriptor {
        return runtimeDescriptors[toolName] ?: RuntimeToolDescriptor(
            name = toolName,
            displayName = toolName,
            toolType = "builtin"
        )
    }

    override fun validateArguments(toolName: String, arguments: JsonObject) {
        val schema = toolSchemas[toolName] ?: return
        validateWithSchema(toolName, schema, arguments)
    }

    private fun validateWithSchema(
        toolName: String,
        schema: JsonObject,
        arguments: JsonObject
    ) {
        val type = schema["type"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (type.isNotBlank() && type != "object") {
            throw IllegalArgumentException("Tool $toolName schema type must be object")
        }
        val properties = (schema["properties"] as? JsonObject) ?: JsonObject(emptyMap())
        val requiredFields = (schema["required"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        requiredFields.forEach { field ->
            // `tool_title` is a presentation hint injected into model schemas so
            // UI cards have readable labels. It is not part of the execution
            // contract for most tools; handlers that truly require it validate
            // it themselves.
            if (field == "tool_title") return@forEach
            if (arguments[field] == null || arguments[field] is JsonNull) {
                throw IllegalArgumentException("Tool $toolName missing required argument: $field")
            }
        }
        arguments.entries.forEach { (field, value) ->
            val propertySchema = properties[field] as? JsonObject ?: return@forEach
            validateFieldType(toolName, field, value, propertySchema)
        }
    }

    private fun validateFieldType(
        toolName: String,
        field: String,
        value: JsonElement,
        propertySchema: JsonObject
    ) {
        val expectedType = propertySchema["type"]?.jsonPrimitive?.contentOrNull?.trim()
        if (!expectedType.isNullOrBlank() && !matchesType(expectedType, value)) {
            throw IllegalArgumentException(
                "Tool $toolName argument $field expected $expectedType but got ${describeType(value)}"
            )
        }
        val enumValues = (propertySchema["enum"] as? JsonArray).orEmpty()
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

    private fun matchesType(expectedType: String, value: JsonElement): Boolean {
        return when (expectedType) {
            "string" -> value is JsonPrimitive && value.isString
            "integer" -> value is JsonPrimitive && !value.isString && value.intOrNull != null
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
                value.intOrNull != null -> "integer"
                value.doubleOrNull != null -> "number"
                else -> "primitive"
            }
        }
    }

    private companion object {
        val MODEL_TOOL_NAME_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
