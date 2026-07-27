package cn.com.omnimind.bot.agent

import android.content.Context
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.i18n.LocalizedText
import cn.com.omnimind.baselib.shizuku.PrivilegedActionPolicy
import cn.com.omnimind.baselib.shizuku.ShizukuBackend
import cn.com.omnimind.baselib.shizuku.ShizukuCapabilityManager
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.function.FunctionApi
import cn.com.omnimind.bot.mcp.RemoteMcpDiscoveredServer
import cn.com.omnimind.bot.mcp.RemoteMcpToolDescriptor
import com.rk.terminal.runtime.TerminalDistribution
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class AgentToolRegistry(
    private val context: Context,
    discoveredServers: List<RemoteMcpDiscoveredServer>,
    conversationMode: String = AgentConversationModePolicy.NORMAL_MODE,
    terminalDistribution: TerminalDistribution.Spec = TerminalDistribution.alpine,
    enabledToolProfiles: Set<String> = emptySet(),
) : AgentToolCatalog {
    data class RuntimeToolDescriptor(
        val name: String,
        val displayName: String,
        val toolType: String,
        val serverName: String? = null,
        val remoteTool: RemoteMcpToolDescriptor? = null
    )

    private val tag = "AgentToolRegistry"
    private val toolSchemas = linkedMapOf<String, JsonObject>()
    private val runtimeDescriptors = linkedMapOf<String, RuntimeToolDescriptor>()
    override val toolsForModel: List<ChatCompletionTool>

    init {
        val locale = AppLocaleManager.resolvePromptLocale(context)
        val shizukuStatus = ShizukuCapabilityManager.get(context).getStatus()
        val runtimeDefinitions = mutableListOf<JsonObject>()
        runtimeDefinitions.addAll(AgentToolDefinitions.staticTools(locale, terminalDistribution))
        if (FunctionApi.PROFILE in enabledToolProfiles) {
            runtimeDefinitions.addAll(FunctionApi.staticToolDefinitions(locale))
        }
        if (shizukuStatus.isGranted()) {
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
        runtimeDefinitions.addAll(AgentToolDefinitions.memoryTools(locale))
        runtimeDefinitions.addAll(AgentToolDefinitions.subagentTools(locale))
        discoveredServers.flatMap { it.tools }.forEach { tool ->
            runtimeDefinitions.add(toDynamicMcpToolDefinition(tool, locale))
        }

        val filteredDefinitions = AgentConversationModePolicy
            .filterToolDefinitionsForConversationMode(runtimeDefinitions, conversationMode)

        toolsForModel = filteredDefinitions.mapNotNull { definition ->
            val function = definition["function"] as? JsonObject ?: return@mapNotNull null
            val name = function["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (name.isBlank()) return@mapNotNull null
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
                serverName = serverName,
                remoteTool = findRemoteTool(name, discoveredServers)
            )
            ChatCompletionTool(
                function = ChatCompletionFunction(
                    name = name,
                    description = description,
                    parameters = parameters
                )
            )
        }

        // Debug dump: full registered tool list to verify which ones the LLM actually receives.
        OmniLog.i(
            tag,
            "registered_tools count=${toolsForModel.size} " +
                "conversationMode=$conversationMode " +
                "subagent_present=${"subagent_dispatch" in runtimeDescriptors.keys} " +
                "memory_load_present=${"memory_load" in runtimeDescriptors.keys} " +
                "names=[${runtimeDescriptors.keys.joinToString(",")}]"
        )
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
        AgentToolSchema.validate(toolName, schema, arguments)
    }

    private fun findRemoteTool(
        toolName: String,
        discoveredServers: List<RemoteMcpDiscoveredServer>
    ): RemoteMcpToolDescriptor? {
        return discoveredServers.asSequence()
            .flatMap { it.tools.asSequence() }
            .firstOrNull { it.encodedToolName == toolName }
    }

    private fun toDynamicMcpToolDefinition(
        tool: RemoteMcpToolDescriptor,
        locale: cn.com.omnimind.baselib.i18n.PromptLocale
    ): JsonObject {
        return AgentToolDefinitions.decorateToolDefinition(buildJsonObject {
            put("type", JsonPrimitive("function"))
            put("function", buildJsonObject {
                put("name", JsonPrimitive(tool.encodedToolName))
                put("displayName", JsonPrimitive(tool.toolName))
                put("toolType", JsonPrimitive("mcp"))
                put("serverName", JsonPrimitive(tool.serverName))
                put(
                    "description",
                    JsonPrimitive(
                        tool.description.ifBlank {
                            LocalizedText(
                                zhCN = "调用远端 MCP 工具。",
                                enUS = "Call a remote MCP tool."
                            ).resolve(locale)
                        }
                    )
                )
                put("parameters", mapToJsonElement(tool.inputSchema))
            })
        }, locale)
    }

    private fun mapToJsonElement(value: Any?): JsonElement {
        return when (value) {
            null -> JsonNull
            is JsonElement -> value
            is Map<*, *> -> JsonObject(
                value.entries.associate { (key, item) ->
                    key.toString() to mapToJsonElement(item)
                }
            )
            is List<*> -> JsonArray(value.map { mapToJsonElement(it) })
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }
    }
}
