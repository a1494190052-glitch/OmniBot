package cn.com.omnimind.bot.omniflow

import android.content.Context
import cn.com.omnimind.baselib.i18n.PromptLocale
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentToolDefinitions
import cn.com.omnimind.bot.agent.AgentToolJson.mapToJsonElement
import cn.com.omnimind.bot.agent.config.AgentToolFeatureStore
import cn.com.omnimind.bot.omniflow.OobFunctionSchemaBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Small native profile that exposes the tools used by the OmniFlow management skill.
 *
 * Workflow rules and prompts belong in `oob-function-management/SKILL.md`.
 */
object OobFunctionSkillProfile {
    const val PROFILE = "function_management"
    const val SKILL_ID = "oob-function-management"
    private const val ENABLE_AGENT_DIRECT_FUNCTION_TOOLS = false
    private const val MAX_DYNAMIC_FUNCTION_TOOLS = 500
    private const val MAX_PROMPT_FUNCTION_CANDIDATES = 50
    private val MODEL_TOOL_NAME_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")

    val toolNames: Set<String> =
        OobFunctionToolNames.profileTools

    fun isProfile(profile: String?): Boolean =
        normalizeProfile(profile) == PROFILE

    fun allowedToolsForProfile(profile: String?): Set<String>? =
        if (isProfile(profile)) toolNames else null

    fun staticToolDefinitions(locale: PromptLocale): List<JsonObject> =
        functionManagementToolDefinitions.map { definition ->
            AgentToolDefinitions.decorateToolDefinition(definition, locale)
        }

    fun runtimeToolDefinitions(locale: PromptLocale): List<JsonObject> =
        emptyList()

    fun dynamicFunctionToolDefinitions(
        context: Context,
        locale: PromptLocale,
        forceInclude: Boolean = false,
    ): List<JsonObject> {
        if (!ENABLE_AGENT_DIRECT_FUNCTION_TOOLS) return emptyList()
        if (!forceInclude && !AgentToolFeatureStore.isOobFunctionAsToolEnabled(context)) {
            return emptyList()
        }
        return runCatching {
            OobFunctionRepository(context)
                .listSpecs(MAX_DYNAMIC_FUNCTION_TOOLS)
                .mapNotNull { spec -> dynamicFunctionToolDefinition(spec, locale) }
        }.onFailure {
            OmniLog.w("OobFunctionSkillProfile", "load Function call candidates failed: ${it.message}")
        }.getOrDefault(emptyList())
    }

    fun shouldKeepDynamicFunctionForProfile(profile: String?, toolType: String): Boolean =
        isProfile(profile) && toolType == "oob_function"

    fun promptCandidateContext(
        context: Context,
        locale: PromptLocale,
        goal: String? = null,
        currentPackageName: String? = null,
        limit: Int = MAX_PROMPT_FUNCTION_CANDIDATES,
    ): String {
        val candidates = runCatching {
            promptCandidates(
                context = context,
                goal = goal,
                currentPackageName = currentPackageName,
                limit = limit.coerceIn(1, MAX_PROMPT_FUNCTION_CANDIDATES),
            )
        }.onFailure {
            OmniLog.w("OobFunctionSkillProfile", "load prompt Function candidates failed: ${it.message}")
        }.getOrDefault(emptyList())
        if (candidates.isEmpty()) return ""

        return buildString {
            when (locale) {
                PromptLocale.ZH_CN -> {
                    appendLine("本轮已根据用户目标完成 OmniFlow Function recall 检查。")
                    appendLine("- Function 是可组合的复用片段；召回、runtime resolve 和重放由本地运行时处理。")
                    appendLine("- Function recall 是运行时内部流程，不是模型工具；不要尝试调用 function_recall、call_tool(function_id) 或隐藏 Function tool。")
                    appendLine("- 如需管理/查看已保存 Function，用 list/get/update/delete 工具；手机 UI 自动化继续走 vlm_task。")
                }
                PromptLocale.EN_US -> {
                    appendLine("OmniFlow Function recall has been checked for this user goal.")
                    appendLine("- A Function is a saved mobile workflow segment; recall, runtime resolve, and replay are handled by the local runtime.")
                    appendLine("- Function recall is an internal runtime flow, not a model tool; do not call function_recall, call_tool(function_id), or hidden Function tools.")
                    appendLine("- Use list/get/update/delete tools to manage saved Functions. Continue phone UI automation through vlm_task.")
                }
            }
        }.trim()
    }

    private fun promptCandidates(
        context: Context,
        goal: String?,
        currentPackageName: String?,
        limit: Int,
    ): List<Map<String, Any?>> {
        val normalizedGoal = goal?.trim().orEmpty()
        if (normalizedGoal.isNotEmpty()) {
            val recall = OobFunctionRecallService(
                context = context,
                functionRepository = OobFunctionRepository(context),
            ).recall(
                mapOf(
                    "goal" to normalizedGoal,
                    "current_package" to currentPackageName.orEmpty(),
                    "k" to limit,
                    "include_debug" to false,
                )
            )
            val recalled = OobFunctionJson.listArg(recall["candidates"])
                .mapNotNull { OobFunctionJson.mapArg(it).takeIf { candidate -> candidate.isNotEmpty() } }
            if (recalled.isNotEmpty()) return recalled
        }
        return OobFunctionRepository(context).listSpecs(limit)
    }

    private fun normalizeProfile(profile: String?): String = profile
        ?.trim()
        ?.lowercase()
        ?.replace('-', '_')
        .orEmpty()

    private fun formatPromptCandidate(
        ordinal: Int,
        spec: Map<String, Any?>,
        locale: PromptLocale,
    ): String {
        val functionId = spec["function_id"]?.toString()?.trim().orEmpty()
        val name = spec["name"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: functionId
        val description = spec["description"]?.toString()?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.takeIf { it.isNotEmpty() }
            ?: name
        val metadata = spec["metadata"] as? Map<*, *>
        val agentReuse = (spec["agent_reuse"] as? Map<*, *>)
            ?: (metadata?.get("agent_reuse") as? Map<*, *>)
        val reuseWhen = agentReuse?.get("reuse_when")?.toString()?.trim().orEmpty()
        val successSignal = agentReuse?.get("success_signal")?.toString()?.trim().orEmpty()
        val inputSchema = OobFunctionSchemaBuilder.inputSchema(spec)
        val params = ((inputSchema["properties"] as? Map<*, *>)?.keys ?: emptySet<Any?>())
            .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            .take(6)
            .joinToString(", ")
            .ifBlank {
                when (locale) {
                    PromptLocale.ZH_CN -> "无显式参数"
                    PromptLocale.EN_US -> "no explicit params"
                }
            }
        val clippedDescription = description.take(220)
        return when (locale) {
            PromptLocale.ZH_CN -> buildString {
                append("- $ordinal. `$functionId` — $name：$clippedDescription；参数: $params")
                spec["score"]?.let { append("；匹配分: $it") }
                spec["recall_scope"]?.toString()?.takeIf { it.isNotBlank() }?.let { append("；来源: $it") }
                if (reuseWhen.isNotEmpty()) append("；适用: ${reuseWhen.take(120)}")
                if (successSignal.isNotEmpty()) append("；成功标志: ${successSignal.take(120)}")
            }
            PromptLocale.EN_US -> buildString {
                append("- $ordinal. `$functionId` — $name: $clippedDescription; params: $params")
                spec["score"]?.let { append("; score: $it") }
                spec["recall_scope"]?.toString()?.takeIf { it.isNotBlank() }?.let { append("; source: $it") }
                if (reuseWhen.isNotEmpty()) append("; use when: ${reuseWhen.take(120)}")
                if (successSignal.isNotEmpty()) append("; success: ${successSignal.take(120)}")
            }
        }
    }

    fun dynamicFunctionToolDefinition(
        spec: Map<String, Any?>,
        locale: PromptLocale
    ): JsonObject? {
        val functionId = spec["function_id"]?.toString()?.trim().orEmpty()
        if (functionId.isEmpty()) return null
        if (!MODEL_TOOL_NAME_REGEX.matches(functionId)) {
            OmniLog.w("OobFunctionSkillProfile", "skip invalid Function tool name: $functionId")
            return null
        }
        val displayName = spec["name"]?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: functionId
        val description = spec["description"]?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: displayName
        val toolDescription = dynamicFunctionDescription(description, locale)
        val parameters = mapToJsonElement(
            OobFunctionSchemaBuilder.inputSchema(spec)
        ) as? JsonObject ?: JsonObject(emptyMap())

        return AgentToolDefinitions.decorateToolDefinition(buildJsonObject {
            put("type", JsonPrimitive("function"))
            put("function", buildJsonObject {
                put("name", JsonPrimitive(functionId))
                put("displayName", JsonPrimitive(displayName))
                put("toolType", JsonPrimitive("oob_function"))
                put("description", JsonPrimitive(toolDescription))
                put("parameters", parameters)
            })
        }, locale)
    }

    private fun dynamicFunctionDescription(
        base: String,
        locale: PromptLocale,
    ): String {
        val suffix = when (locale) {
            PromptLocale.ZH_CN ->
                "这是一个 legacy 兼容描述；已保存 Function 不作为模型可见执行 tool 暴露。正常手机自动化应调用 vlm_task，由本地 runtime 完成 recall、runtime resolve 和 replay。"
            PromptLocale.EN_US ->
                "This is a legacy compatibility description; saved Functions are not exposed as model-visible execution tools. Normal phone automation should call vlm_task so the local runtime handles recall, runtime resolve, and replay."
        }
        return "${base.take(360)} $suffix".trim().take(600)
    }

    private val oobFunctionListTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", OobFunctionToolNames.FUNCTION_LIST)
            put("displayName", "列出复用指令")
            put("toolType", "oob_function")
            put("description", "列出本机已注册的 OmniFlow 复用指令。用于查看可选 Function 候选；不会执行任何手机操作。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("description", "可选。返回数量上限，默认 100，最大 500。")
                    }
                }
            }
        }
    }

    private val oobFunctionGetTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", OobFunctionToolNames.FUNCTION_GET)
            put("displayName", "查看复用指令")
            put("toolType", "oob_function")
            put("description", "读取一个 OmniFlow 复用指令的结构化 Function spec，用于确认步骤、参数和来源。不会执行手机操作。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("function_id") {
                        put("type", "string")
                        put("description", "要读取的 Function id。")
                    }
                }
                putJsonArray("required") { add("function_id") }
            }
        }
    }

    private val oobFunctionRegisterTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", OobFunctionToolNames.FUNCTION_REGISTER)
            put("displayName", "注册复用指令")
            put("toolType", "oob_function")
            put("description", "注册或更新一个 OmniFlow 复用指令。优先使用轻量字段 function_id/name/description/steps；只有已有完整底层结构时才传 function_spec。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("function_id") { put("type", "string") }
                    putJsonObject("name") { put("type", "string") }
                    putJsonObject("description") { put("type", "string") }
                    putJsonObject("package_name") { put("type", "string") }
                    putJsonObject("source_page") { put("type", "object") }
                    putJsonObject("parameters") { put("type", "array") }
                    putJsonObject("steps") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "object") }
                    }
                    putJsonObject("function_spec") { put("type", "object") }
                }
            }
        }
    }

    private val updateFunctionTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", OobFunctionToolNames.FUNCTION_UPDATE)
            put("displayName", "更新复用指令")
            put("toolType", "oob_function")
            put("description", "根据结构化 patch、用户纠错指令或 RunLog 证据分析更新一个已保存的 OmniFlow Function。传 run_id 且不传 analysis/patch 时后台会自动分析 RunLog、生成 patch 并保存增强结果；不会执行手机操作。")
            put("parameters", mapToJsonElement(OobFunctionUpdateToolSchema.inputSchema(includeCamelCaseAliases = true)))
        }
    }

    private val oobFunctionDeleteTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", OobFunctionToolNames.FUNCTION_DELETE)
            put("displayName", "删除复用指令")
            put("toolType", "oob_function")
            put("description", "删除一个 OmniFlow 复用指令。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("function_id") { put("type", "string") }
                }
                putJsonArray("required") { add("function_id") }
            }
        }
    }

    private val oobFunctionClearTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", OobFunctionToolNames.FUNCTION_CLEAR)
            put("displayName", "清空复用指令")
            put("toolType", "oob_function")
            put("description", "清空所有 OmniFlow 复用指令。只有用户明确要求清空全部时使用，必须传 confirm=true。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("confirm") { put("type", "boolean") }
                }
            }
        }
    }

    private val oobRunLogListTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", OobFunctionToolNames.RUN_LOG_LIST)
            put("displayName", "列出 RunLog")
            put("toolType", "oob_function")
            put("description", "列出 OOB 内部最近的 RunLogs，用于选择可固化或检查的历史执行。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {}
            }
        }
    }

    private val oobRunLogGetTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", OobFunctionToolNames.RUN_LOG_GET)
            put("displayName", "查看 RunLog")
            put("toolType", "oob_function")
            put("description", "读取一个 OOB 内部 RunLog 时间线。只在需要检查具体历史执行时使用。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("run_id") { put("type", "string") }
                }
                putJsonArray("required") { add("run_id") }
            }
        }
    }

    private val oobRunLogConvertTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", OobFunctionToolNames.RUN_LOG_CONVERT)
            put("displayName", "转换 RunLog")
            put("toolType", "oob_function")
            put("description", "把成功完成的 RunLog 转换为 oob.reusable_function.v1。register=true 默认保存为 agent 不可见的人工 Function；只有显式 agent_visible=true 才发布为可复用指令候选。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("run_id") { put("type", "string") }
                    putJsonObject("register") { put("type", "boolean") }
                    putJsonObject("agent_visible") { put("type", "boolean") }
                    putJsonObject("function_id") { put("type", "string") }
                    putJsonObject("name") { put("type", "string") }
                    putJsonObject("description") { put("type", "string") }
                }
            }
        }
    }

    private val functionManagementToolDefinitions: List<JsonObject> = listOf(
        oobFunctionListTool,
        oobFunctionGetTool,
        oobFunctionRegisterTool,
        updateFunctionTool,
        oobFunctionDeleteTool,
        oobFunctionClearTool,
        oobRunLogListTool,
        oobRunLogGetTool,
        oobRunLogConvertTool,
    )

}
