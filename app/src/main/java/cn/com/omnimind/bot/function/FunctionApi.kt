package cn.com.omnimind.bot.function

import android.content.Context
import cn.com.omnimind.baselib.i18n.PromptLocale
import cn.com.omnimind.baselib.runlog.OobActionSchema
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentToolDefinitions
import cn.com.omnimind.bot.agent.AgentToolJson.mapToJsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Small native profile that exposes the tools used by the Function management skill.
 *
 * Workflow rules and prompts belong to the built-in Function skill.
 */
object FunctionApi {
    const val PROFILE = "function"
    const val LEGACY_OMNIFLOW_PROFILE = "omniflow"
    const val LEGACY_PROFILE = "function_management"
    const val SKILL_ID = "function"
    private const val MAX_PROMPT_FUNCTION_CANDIDATES = 50

    const val FUNCTION_LIST = "oob_function_list"
    const val FUNCTION_GET = "oob_function_get"
    const val FUNCTION_REGISTER = "oob_function_register"
    const val FUNCTION_UPDATE = "update_function"
    const val FUNCTION_DELETE = "oob_function_delete"
    const val FUNCTION_CLEAR = "oob_function_clear"

    const val RUN_LOG_LIST = "oob_run_log_list"
    const val RUN_LOG_GET = "oob_run_log_get"
    const val RUN_LOG_CONVERT = "oob_run_log_convert"

    const val FUNCTION_RECALL = "function.recall"
    const val FUNCTION_INGEST_RUN_LOG = "function.ingest_run_log"
    const val LEGACY_FUNCTION_RECALL = "omniflow.recall"
    const val LEGACY_FUNCTION_INGEST_RUN_LOG = "omniflow.ingest_run_log"

    val functionLifecycleTools: Set<String> = setOf(
        FUNCTION_LIST,
        FUNCTION_GET,
        FUNCTION_REGISTER,
        FUNCTION_UPDATE,
        FUNCTION_DELETE,
        FUNCTION_CLEAR,
    )

    val runLogTools: Set<String> = setOf(
        RUN_LOG_LIST,
        RUN_LOG_GET,
        RUN_LOG_CONVERT,
    )

    val profileTools: Set<String> = functionLifecycleTools + runLogTools
    val toolNames: Set<String> = profileTools
    val mcpToolNames: Set<String> = profileTools + setOf(FUNCTION_RECALL, FUNCTION_INGEST_RUN_LOG)
    val acceptedMcpToolNames: Set<String> = mcpToolNames + setOf(
        LEGACY_FUNCTION_RECALL,
        LEGACY_FUNCTION_INGEST_RUN_LOG,
    )

    fun isProfile(profile: String?): Boolean =
        canonicalProfile(profile) == PROFILE

    fun canonicalProfile(profile: String?): String {
        val normalized = normalizeProfile(profile)
        return when (normalized) {
            PROFILE, LEGACY_OMNIFLOW_PROFILE, LEGACY_PROFILE -> PROFILE
            else -> normalized
        }
    }

    fun staticToolDefinitions(locale: PromptLocale): List<JsonObject> =
        functionManagementToolDefinitions.map { definition ->
            AgentToolDefinitions.decorateToolDefinition(definition, locale)
        }

    val mcpToolDefinitions: List<Map<String, Any?>>
        get() = listOf(
            functionRecallMcpTool,
            functionIngestRunLogMcpTool,
            functionListMcpTool,
            functionGetMcpTool,
            functionRegisterMcpTool,
            updateFunctionMcpTool,
            functionDeleteMcpTool,
            functionClearMcpTool,
            oobRunLogListMcpTool,
            oobRunLogGetMcpTool,
            oobRunLogConvertMcpTool,
        )

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
            OmniLog.w("FunctionApi", "load prompt Function candidates failed: ${it.message}")
        }.getOrDefault(emptyList())
        return buildPromptCandidateContext(candidates, locale)
    }

    internal fun buildPromptCandidateContext(
        candidates: List<Map<String, Any?>>,
        locale: PromptLocale,
    ): String {
        if (candidates.isEmpty()) return ""
        return buildString {
            when (locale) {
                PromptLocale.ZH_CN -> {
                    appendLine("本轮已根据用户目标完成 Function recall 检查。")
                    appendLine("- Function 是可组合的复用片段；召回和重放由本地运行时处理。")
                    appendLine("- Function recall 是运行时内部流程，不是模型工具；不要尝试调用 function_recall、call_tool(function_id) 或隐藏 Function tool。")
                    appendLine("- 如需管理/查看已保存 Function，用 list/get/update/delete 工具；普通手机 UI 自动化继续走 vlm_task。")
                    appendLine("- 候选复用指令只作上下文；明确要执行时由本地运行时选择，普通手机自动化仍走 vlm_task：")
                }
                PromptLocale.EN_US -> {
                    appendLine("Function recall has been checked for this user goal.")
                    appendLine("- A Function is a saved mobile workflow segment; recall and replay are handled by the local runtime.")
                    appendLine("- Function recall is an internal runtime flow, not a model tool; do not call function_recall, call_tool(function_id), or hidden Function tools.")
                    appendLine("- Use list/get/update/delete tools to manage saved Functions. Continue ordinary phone UI automation through vlm_task.")
                    appendLine("- Candidate reusable Functions are context only; explicit execution is selected by the local runtime, and ordinary phone automation still uses vlm_task:")
                }
            }
            candidates.forEachIndexed { index, spec ->
                appendLine(formatPromptCandidate(index + 1, spec, locale))
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
            val recall = FunctionService(context).recall(
                mapOf(
                    "goal" to normalizedGoal,
                    "current_package" to currentPackageName.orEmpty(),
                    "k" to limit,
                    "include_debug" to false,
                )
            )
            val recalled = FunctionJson.listArg(recall["candidates"])
                .mapNotNull { FunctionJson.mapArg(it).takeIf { candidate -> candidate.isNotEmpty() } }
            if (recalled.isNotEmpty()) return recalled
        }
        return FunctionService(context).listFunctionSpecs(limit)
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
        val callable = FunctionSchema.callableSummary(spec)
        val functionId = FunctionJson.firstNonBlank(callable["function_id"], spec["function_id"])
        val name = FunctionJson.firstNonBlank(callable["name"], spec["name"], functionId)
        val description = FunctionJson.firstNonBlank(callable["description"], spec["description"], name)
            .trim()
            .replace(Regex("\\s+"), " ")
            .takeIf { it.isNotEmpty() }
            ?: name
        val metadata = spec["metadata"] as? Map<*, *>
        val agentReuse = (spec["agent_reuse"] as? Map<*, *>)
            ?: (metadata?.get("agent_reuse") as? Map<*, *>)
        val reuseWhen = agentReuse?.get("reuse_when")?.toString()?.trim().orEmpty()
        val successSignal = agentReuse?.get("success_signal")?.toString()?.trim().orEmpty()
        val inputSchema = FunctionJson.mapArg(callable["parameters"]).ifEmpty {
            FunctionSchema.inputSchema(spec)
        }
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

    private val canonicalReplayTools: String =
        OobActionSchema.replayableToolNames.joinToString(", ")

    private val functionRecallMcpTool = mapOf(
        "name" to FUNCTION_RECALL,
        "description" to """Recall by the UDEG path: page match -> UDEG node -> node skill-like decision context. The result is candidate context for inspection and diagnostics. Online execution should use vlm_task; saved Function execution is selected by the local runtime, not by a direct model tool call.""".trimIndent(),
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "goal" to mapOf("type" to "string", "description" to "Natural-language task goal."),
                "current_package" to mapOf("type" to "string", "description" to "Optional foreground Android package for scope matching."),
                "current_node_id" to mapOf("type" to "string", "description" to "Optional current page/node id for future compatibility."),
                "current_xml" to mapOf("type" to "string", "description" to "Optional live accessibility XML. When omitted, the runtime captures the foreground page and page-matches it to a UDEG node."),
                "k" to mapOf("type" to "integer", "description" to "Maximum candidates to return. Default 8."),
                "include_debug" to mapOf(
                    "type" to "boolean",
                    "default" to false,
                    "description" to "Default false returns an agent-compact payload without timing, full node skill body, page vectors, or artifacts. Set true only for tests/debugging."
                )
            ),
            "required" to listOf("goal")
        )
    )

    private val functionIngestRunLogMcpTool = mapOf(
        "name" to FUNCTION_INGEST_RUN_LOG,
        "description" to """Convert a successful RunLog into a local manual Function asset. By default this returns or saves an agent-hidden manual Function; set register=true only when explicitly publishing it for runtime recall.""".trimIndent(),
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "run_id" to mapOf("type" to "string", "description" to "Existing RunLog id."),
                "run_log" to mapOf("type" to "object", "description" to "Optional inline canonical run log."),
                "register" to mapOf("type" to "boolean", "description" to "Persist the converted manual Function. Default false."),
                "agent_visible" to mapOf("type" to "boolean", "description" to "Compatibility flag for older callers. Function recall/replay is runtime-owned and not exposed as model-callable tools."),
                "auto_enrich" to mapOf("type" to "boolean", "description" to "Accepted for compatibility; Function import does deterministic local import.")
            )
        )
    )

    private val functionListMcpTool = mapOf(
        "name" to FUNCTION_LIST,
        "description" to "List registered Functions available for runtime recall and replay.",
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "limit" to mapOf("type" to "integer", "description" to "Maximum number of Functions to return. Default: 100.")
            )
        )
    )

    private val functionGetMcpTool = mapOf(
        "name" to FUNCTION_GET,
        "description" to "Read one registered Function by id.",
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "function_id" to mapOf("type" to "string", "description" to "Function id to read.")
            ),
            "required" to listOf("function_id")
        )
    )

    private val functionRegisterMcpTool = mapOf(
        "name" to FUNCTION_REGISTER,
        "description" to "Register or update one Function. Prefer the simple shape {function_id,name,description,steps,source_page}; pass function_spec only when you already have a full oob.reusable_function.v1 spec. Registration never auto-executes the Function.",
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "function_id" to mapOf("type" to "string", "description" to "Optional stable Function id. Generated from name when omitted."),
                "name" to mapOf("type" to "string", "description" to "User-readable Function name."),
                "description" to mapOf("type" to "string", "description" to "One-sentence description of when to reuse this Function."),
                "package_name" to mapOf("type" to "string", "description" to "Optional target/source app package for page-scoped recall."),
                "source_page" to mapOf("type" to "object", "description" to "Optional source page context, for example {xml, package_name, activity_name}."),
                "parameters" to mapOf("type" to "array", "description" to "Optional Function parameter descriptors with name/type/required/default/bindings."),
                "steps" to mapOf(
                    "type" to "array",
                    "description" to "Simple canonical step list. Each item must use {tool,args,title?}. Supported tool values are $canonicalReplayTools. input_text uses args.text; finished uses args.content.",
                    "items" to mapOf("type" to "object")
                ),
                "function_spec" to mapOf("type" to "object", "description" to "Optional full oob.reusable_function.v1 spec object.")
            )
        )
    )

    private val updateFunctionMcpTool = mapOf(
        "name" to FUNCTION_UPDATE,
        "description" to "Update one saved Function from a structured patch, user correction, or RunLog evidence. Passing run_id without analysis/patch returns analysis_context and agent_prompt; saving RunLog evidence uses analysis plus an optional patch.",
        "inputSchema" to FunctionUpdateToolSchema.inputSchema(includeCamelCaseAliases = false)
    )

    private val functionDeleteMcpTool = mapOf(
        "name" to FUNCTION_DELETE,
        "description" to "Delete one registered Function from Workspace, local registry, and UDEG node references.",
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "function_id" to mapOf("type" to "string", "description" to "Function id to delete.")
            ),
            "required" to listOf("function_id")
        )
    )

    private val functionClearMcpTool = mapOf(
        "name" to FUNCTION_CLEAR,
        "description" to "Clear all registered Functions and detach all Function references from UDEG node skills. Requires confirm=true.",
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "confirm" to mapOf("type" to "boolean", "description" to "Must be true to clear all Functions.")
            ),
            "required" to listOf("confirm")
        )
    )

    private val oobRunLogListMcpTool = mapOf(
        "name" to RUN_LOG_LIST,
        "description" to "List recent RunLogs that can be inspected or converted to Functions.",
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "limit" to mapOf("type" to "integer", "description" to "Maximum number of RunLogs to return. Default: 50.")
            )
        )
    )

    private val oobRunLogGetMcpTool = mapOf(
        "name" to RUN_LOG_GET,
        "description" to "Read one RunLog timeline payload by id.",
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "run_id" to mapOf("type" to "string", "description" to "RunLog id to read.")
            ),
            "required" to listOf("run_id")
        )
    )

    private val oobRunLogConvertMcpTool = mapOf(
        "name" to RUN_LOG_CONVERT,
        "description" to "Convert one successful RunLog into a reusable Function and optionally register it.",
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "run_id" to mapOf("type" to "string", "description" to "RunLog id to convert."),
                "register" to mapOf("type" to "boolean", "description" to "Register the converted Function. Default follows service policy."),
                "function_id" to mapOf("type" to "string", "description" to "Optional Function id override."),
                "name" to mapOf("type" to "string", "description" to "Optional Function name override."),
                "description" to mapOf("type" to "string", "description" to "Optional Function description override.")
            ),
            "required" to listOf("run_id")
        )
    )

    private val functionListTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", FunctionApi.FUNCTION_LIST)
            put("displayName", "列出复用指令")
            put("toolType", "oob_function")
            put("description", "列出本机已注册的复用指令。用于查看可选 Function 候选；不会执行任何手机操作。")
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

    private val functionGetTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", FunctionApi.FUNCTION_GET)
            put("displayName", "查看复用指令")
            put("toolType", "oob_function")
            put("description", "读取一个复用指令的结构化 Function spec，用于确认步骤、参数和来源。不会执行手机操作。")
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

    private val functionRegisterTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", FunctionApi.FUNCTION_REGISTER)
            put("displayName", "注册复用指令")
            put("toolType", "oob_function")
            put("description", "注册或更新一个复用指令。优先使用轻量字段 function_id/name/description/steps；只有已有完整底层结构时才传 function_spec。")
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
            put("name", FunctionApi.FUNCTION_UPDATE)
            put("displayName", "更新复用指令")
            put("toolType", "oob_function")
            put("description", "离线维护一个已保存的 Function：根据结构化 patch、用户纠错指令或 RunLog 证据更新语义信息。传 run_id 且不传 analysis/patch 时后台会分析 RunLog、生成 patch 并保存结果；不会执行手机操作，也不属于 vlm_task 实时路径。")
            put("parameters", mapToJsonElement(FunctionUpdateToolSchema.inputSchema(includeCamelCaseAliases = true)))
        }
    }

    private val functionDeleteTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", FunctionApi.FUNCTION_DELETE)
            put("displayName", "删除复用指令")
            put("toolType", "oob_function")
            put("description", "删除一个复用指令。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("function_id") { put("type", "string") }
                }
                putJsonArray("required") { add("function_id") }
            }
        }
    }

    private val functionClearTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", FunctionApi.FUNCTION_CLEAR)
            put("displayName", "清空复用指令")
            put("toolType", "oob_function")
            put("description", "清空所有复用指令。只有用户明确要求清空全部时使用，必须传 confirm=true。")
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
            put("name", FunctionApi.RUN_LOG_LIST)
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
            put("name", FunctionApi.RUN_LOG_GET)
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
            put("name", FunctionApi.RUN_LOG_CONVERT)
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
        functionListTool,
        functionGetTool,
        functionRegisterTool,
        updateFunctionTool,
        functionDeleteTool,
        functionClearTool,
        oobRunLogListTool,
        oobRunLogGetTool,
        oobRunLogConvertTool,
    )

}
