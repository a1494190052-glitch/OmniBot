package cn.com.omnimind.bot.function

import android.content.Context
import cn.com.omnimind.baselib.i18n.PromptLocale
import cn.com.omnimind.baselib.runlog.OobActionSchema
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentToolDefinitions
import cn.com.omnimind.bot.agent.AgentToolJson.mapToJsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    const val SCHEMA_RESOURCE_URI = "omniflow://schemas/function-management"
    private const val SCHEMA_EXPORT_VERSION = "oob.function_schema_export.v1"

    fun schemaBundle(mcpTools: List<Map<String, Any?>> = emptyList()): Map<String, Any?> =
        linkedMapOf(
            "schema_version" to SCHEMA_EXPORT_VERSION,
            "kind" to "oob_function_schema_export",
            "resource_uri" to SCHEMA_RESOURCE_URI,
            "canonical_actions_schema_version" to OobActionSchema.SCHEMA_VERSION,
            "schemas" to linkedMapOf(
                "oob.reusable_function.v1" to reusableFunctionSchema,
                "update_function.input" to updateFunctionInputSchema(),
                "update_function.patch" to updateFunctionPatchSchema,
            ),
            "tool_schemas" to mcpTools.mapNotNull(::toolSchemaSummary),
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
        limit: Int,
    ): List<Map<String, Any?>> = FunctionService(context).listFunctionSpecs(limit)

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
                "run_id" to mapOf("type" to "string", "description" to "Existing RunLog id. Raw RunLog state stays in the RunLog store and is resolved by id."),
                "register" to mapOf("type" to "boolean", "description" to "Persist the converted manual Function. Default false."),
                "agent_visible" to mapOf("type" to "boolean", "description" to "Compatibility flag for older callers. Function recall/replay is runtime-owned and not exposed as model-callable tools."),
                "auto_enrich" to mapOf("type" to "boolean", "description" to "Accepted for compatibility; Function import does deterministic local import.")
            ),
            "required" to listOf("run_id")
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
        "description" to "Register or update one canonical Function spec. Compilation belongs to OmniFlow; this tool only persists function_spec and never executes it.",
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "function_spec" to reusableFunctionSchema,
            ),
            "required" to listOf("function_spec"),
        )
    )

    private val updateFunctionMcpTool = mapOf(
        "name" to FUNCTION_UPDATE,
        "description" to "Apply explicit delete or replace_args edits to one saved Function. Raw RunLog evidence stays in the RunLog store.",
        "inputSchema" to updateFunctionInputSchema()
    )

    private val functionDeleteMcpTool = mapOf(
        "name" to FUNCTION_DELETE,
        "description" to "Delete one registered Function from the workspace Function store.",
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
        "description" to "Clear all registered Functions from the workspace Function store. Requires confirm=true.",
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
                "register" to mapOf("type" to "boolean", "description" to "Register the converted Function. Default false."),
                "agent_visible" to mapOf("type" to "boolean", "description" to "Publish the registered Function for runtime recall. Default false."),
                "function_id" to mapOf("type" to "string", "description" to "Optional Function id override."),
                "name" to mapOf("type" to "string", "description" to "Optional Function name override."),
                "description" to mapOf("type" to "string", "description" to "Optional Function description override.")
            ),
            "required" to listOf("run_id")
        )
    )

    private data class ToolPresentation(
        val displayName: String,
        val description: String,
    )

    private val staticToolPresentations = mapOf(
        FUNCTION_LIST to ToolPresentation("列出复用指令", "列出本机已注册的复用指令；不会执行手机操作。"),
        FUNCTION_GET to ToolPresentation("查看复用指令", "读取一个复用指令的 Function spec；不会执行手机操作。"),
        FUNCTION_REGISTER to ToolPresentation("注册复用指令", "保存一个由 OmniFlow 生成的完整 function_spec；不会执行手机操作。"),
        FUNCTION_UPDATE to ToolPresentation("更新复用指令", "对已保存 Function 应用 delete 或 replace_args 动作编辑。"),
        FUNCTION_DELETE to ToolPresentation("删除复用指令", "删除一个复用指令。"),
        FUNCTION_CLEAR to ToolPresentation("清空复用指令", "清空所有复用指令，必须传 confirm=true。"),
        RUN_LOG_LIST to ToolPresentation("列出 RunLog", "列出最近的 RunLogs，用于选择可固化或检查的历史执行。"),
        RUN_LOG_GET to ToolPresentation("查看 RunLog", "读取一个 RunLog 时间线。"),
        RUN_LOG_CONVERT to ToolPresentation("转换 RunLog", "把成功 RunLog 交给 OmniFlow 编译为可复用 Function。"),
    )

    private val functionManagementToolDefinitions: List<JsonObject> by lazy {
        mcpToolDefinitions.mapNotNull { definition ->
            val name = definition["name"]?.toString()?.takeIf(profileTools::contains) ?: return@mapNotNull null
            val presentation = staticToolPresentations.getValue(name)
            buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", name)
                    put("displayName", presentation.displayName)
                    put("toolType", "oob_function")
                    put("description", presentation.description)
                    put(
                        "parameters",
                        mapToJsonElement(FunctionJson.mapArg(definition["inputSchema"])),
                    )
                }
            }
        }
    }

    private fun toolSchemaSummary(tool: Map<String, Any?>): Map<String, Any?>? {
        val name = tool["name"]?.toString()?.trim().orEmpty()
        if (name.isEmpty()) return null
        return linkedMapOf(
            "name" to name,
            "inputSchema" to tool["inputSchema"],
        )
    }

    private fun updateFunctionInputSchema(): Map<String, Any?> =
        obj(
            properties = linkedMapOf(
                "function_id" to string("Existing Function id to update in place."),
                "patch" to updateFunctionPatchSchema,
                "dry_run" to boolean("Preview changes without saving."),
            ),
            required = listOf("function_id"),
        )

    private val updateFunctionPatchSchema: Map<String, Any?>
        get() = obj(
            description = "Explicit in-place action edits. Unspecified actions are preserved.",
            properties = linkedMapOf(
                "action_edits" to array(
                    description = "Evidence-backed action cleanup. Indexes refer to the current stored action list; unspecified actions are preserved.",
                    items = actionEditSchema,
                ),
            ),
            required = listOf("action_edits"),
        )

    private val actionEditSchema: Map<String, Any?>
        get() = obj(
            properties = linkedMapOf(
                "op" to enumString("Action edit operation.", listOf("delete", "replace_args")),
                "index" to integer("Zero-based action index in the current Function."),
                "expected_tool" to string("Optional stale-index guard; the edit is ignored if the tool differs."),
                "args" to obj("Arguments merged into the existing action for replace_args. Source state fields are removed."),
                "reason" to string("Evidence-backed reason for the edit."),
            ),
            required = listOf("op", "index"),
        )

    private val cleanupAnnotationSchema: Map<String, Any?>
        get() = obj(
            description = "Optional annotation for cleanup/noise/checker candidate steps. Does not directly insert/delete executable steps.",
            properties = linkedMapOf(
                "schema_version" to string("Annotation schema version."),
                "cleanup_action" to string("For optional runtime checker use optional_checker."),
                "optional_condition" to string("When this checker candidate should run."),
                "reason" to string("Why the annotation is safe/useful."),
                "action_purpose" to string("What the original action was trying to do."),
                "role" to string("Optional role such as checker_candidate."),
            ),
        )

    private val agentReuseSchema: Map<String, Any?>
        get() = obj(
            properties = linkedMapOf(
                "reuse_when" to array("When the Function should be reused.", string()),
                "avoid_when" to array("When the Function should not be reused.", string()),
                "success_signal" to string("How to know the Function succeeded."),
                "key_actions" to array("Important user-visible actions.", obj()),
                "checker_assets" to array("Links from optional checker annotations to runtime checker rules.", obj()),
            ),
        )

    private val reusableFunctionSchema: Map<String, Any?>
        get() = obj(
            description = "Saved Function. update_function only applies explicit action edits.",
            properties = linkedMapOf(
                "schema_version" to constString("oob.reusable_function.v1"),
                "function_id" to string("Stable Function id."),
                "name" to string("Short user-visible Function name."),
                "description" to string("Reusable description with app/page conditions, runtime inputs, and success signal."),
                "package_name" to string("Optional Android package scope."),
                "parameters" to array(
                    "Runtime parameter descriptors.",
                    obj(
                        properties = linkedMapOf(
                            "name" to string("Parameter name."),
                            "type" to string("Parameter type."),
                            "description" to string("User-visible parameter description."),
                            "required" to boolean("Whether the caller must provide this value."),
                            "default" to obj(description = "Optional default value."),
                            "bindings" to array("JSONPath bindings under execution.steps[*].args.", string()),
                        )
                    ),
                ),
                "execution" to obj(
                    properties = linkedMapOf(
                        "steps" to array(
                            "Ordered executable or agent-routed steps.",
                            obj(
                                properties = linkedMapOf(
                                    "id" to string("Step id."),
                                    "tool" to string("Canonical action/tool name."),
                                    "model_free" to boolean("Whether local replay can execute without model planning."),
                                    "title" to string("Short step title."),
                                    "description" to string("Visible action intent."),
                                    "args" to obj(description = "Concrete replay arguments."),
                                    "cleanup_annotation" to cleanupAnnotationSchema,
                                    "source_context" to obj(description = "Minimal coordinate semantics and source action index. Raw page evidence remains in the RunLog."),
                                )
                            ),
                        ),
                    )
                ),
                "agent_reuse" to agentReuseSchema,
                "metadata" to obj(description = "Runtime metadata, checker rules, and diagnostics."),
            ),
        )

    private fun obj(
        description: String? = null,
        properties: Map<String, Any?> = emptyMap(),
        required: List<String> = emptyList(),
    ): Map<String, Any?> = linkedMapOf<String, Any?>("type" to "object").apply {
        if (description != null) put("description", description)
        if (properties.isNotEmpty()) put("properties", properties)
        if (required.isNotEmpty()) put("required", required)
    }

    private fun array(description: String, items: Map<String, Any?>): Map<String, Any?> =
        linkedMapOf("type" to "array", "description" to description, "items" to items)

    private fun string(description: String = ""): Map<String, Any?> =
        primitive("string", description)

    private fun integer(description: String): Map<String, Any?> =
        primitive("integer", description)

    private fun boolean(description: String): Map<String, Any?> =
        primitive("boolean", description)

    private fun constString(value: String): Map<String, Any?> =
        linkedMapOf("type" to "string", "const" to value)

    private fun enumString(description: String, values: List<String>): Map<String, Any?> =
        linkedMapOf<String, Any?>("type" to "string", "description" to description, "enum" to values)

    private fun primitive(type: String, description: String): Map<String, Any?> =
        linkedMapOf<String, Any?>("type" to type).apply {
            if (description.isNotBlank()) put("description", description)
        }

}
