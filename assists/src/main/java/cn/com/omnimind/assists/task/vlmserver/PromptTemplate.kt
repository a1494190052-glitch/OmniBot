package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.assists.util.TimeUtil
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.i18n.PromptLocale
import cn.com.omnimind.baselib.llm.ModelSceneRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/**
 * 主 VLM prompt 构造器：
 * - system: 稳定规则、工具协议、GUI 操作规范
 * - user: 当前轮动态上下文 + 当前截图
 */
object PromptTemplate {
    private fun currentLocale(): PromptLocale = AppLocaleManager.currentPromptLocale()

    private fun t(locale: PromptLocale, zh: String, en: String): String {
        return when (locale) {
            PromptLocale.ZH_CN -> zh
            PromptLocale.EN_US -> en
        }
    }

    fun getPrompt(context: UIContext, sceneId: String? = null): String {
        return buildTurnUserPrompt(context, sceneId)
    }

    fun buildSystemPrompt(sceneId: String? = null): String {
        val locale = currentLocale()
        val resolvedSceneId = if (sceneId.isNullOrBlank()) {
            "scene.vlm.operation.primary"
        } else {
            sceneId
        }
        val runtimeProfile = ModelSceneRegistry.getRuntimeProfile(resolvedSceneId)
        val parser = runtimeProfile?.responseParser ?: ModelSceneRegistry.ResponseParser.TEXT_CONTENT
        val template = ModelSceneRegistry.getPrompt(resolvedSceneId)
            ?: ModelSceneRegistry.getPrompt("scene.vlm.operation.primary")
            ?: throw IllegalStateException("scene.vlm.operation.primary prompt not found")

        val responseContract = if (parser == ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS) {
            VLMToolDefinitions.responseContract(locale)
        } else {
            ""
        }

        return ModelSceneRegistry.renderPrompt(
            template,
            mapOf(
                "priorityEvent" to t(locale, "若后续 user 消息包含紧急事件，请优先处理。", "If later user messages contain urgent events, prioritize them."),
                "overallTask" to t(locale, "见后续 user 消息", "See the following user message"),
                "currentStepGoal" to t(locale, "见后续 user 消息", "See the following user message"),
                "stepSkillGuidance" to t(locale, "见后续 user 消息", "See the following user message"),
                "summaryHistory" to t(locale, "见后续 user 消息", "See the following user message"),
                "currentState" to t(locale, "见后续 user 消息", "See the following user message"),
                "nextStepHint" to t(locale, "见后续 user 消息", "See the following user message"),
                "completedMilestones" to t(locale, "见后续 user 消息", "See the following user message"),
                "keyMemory" to t(locale, "见后续 user 消息", "See the following user message"),
                "installedApps" to t(locale, "见后续 user 消息", "See the following user message"),
                "currentTime" to t(locale, "见后续 user 消息", "See the following user message"),
                "responseContract" to responseContract
            )
        )
    }

    fun buildTurnUserPrompt(context: UIContext, sceneId: String? = null): String {
        val locale = currentLocale()
        val resolvedSceneId = if (sceneId.isNullOrBlank()) {
            "scene.vlm.operation.primary"
        } else {
            sceneId
        }
        val summaryHistory = if (context.runningSummary.isNotEmpty()) {
            context.runningSummary
        } else if (context.trace.isNotEmpty()) {
            context.trace.last().summary
        } else {
            t(locale, "暂无历史操作", "No prior execution history yet")
        }
        val installedApps = renderFocusedInstalledApps(context, locale)
        val completedMilestones = if (context.completedMilestones.isNotEmpty()) {
            context.completedMilestones.joinToString(
                separator = if (locale == PromptLocale.ZH_CN) "、" else ", "
            )
        } else {
            t(locale, "暂无", "None yet")
        }
        val keyMemory = if (context.keyMemory.isNotEmpty()) {
            context.keyMemory.joinToString(
                separator = if (locale == PromptLocale.ZH_CN) "；" else "; "
            )
        } else {
            t(locale, "暂无", "None yet")
        }
        val priorityEventSection = if (context.priorityEvent != null) {
            buildString {
                appendLine(t(locale, "【紧急事件】", "[Urgent Event]"))
                appendLine(context.priorityEvent)
                if (context.suggestCompletion) {
                    appendLine(
                        t(
                            locale,
                            "如果已经确认任务完成，请尽快调用 finished 工具结束任务。",
                            "If the task has already been confirmed complete, call the finished tool as soon as possible."
                        )
                    )
                }
                appendLine()
            }.trim()
        } else {
            ""
        }

        return buildString {
            appendLine(
                t(
                    locale,
                    "以下是当前这一轮的动态上下文，请结合当前截图和 OOB indexed page evidence 选择下一步动作。原始 XML 只供系统内部使用，不会作为模型上下文提供。",
                    "Below is the dynamic context for the current turn. Use it together with the current screenshot and OOB indexed page evidence to choose the next action. Raw XML is internal-only and is not provided as model context."
                )
            )
            appendLine("${t(locale, "场景", "Scene")}: $resolvedSceneId")
            appendLine("${t(locale, "当前时间", "Current time")}: ${TimeUtil.getCurrentTimeString()}")
            appendLine("${t(locale, "用户任务", "User task")}: ${context.overallTask}")
            appendLine("${t(locale, "当前子目标", "Current sub-goal")}: ${context.activeGoal()}")
            appendLine(
                "${t(locale, "技能提示", "Skill guidance")}: ${context.stepSkillGuidance.ifEmpty { t(locale, "无", "None") }}"
            )
            if (priorityEventSection.isNotBlank()) {
                appendLine(priorityEventSection)
            }
            if (context.currentPageSummary.isNotBlank() || context.firstStepGuidance.isNotBlank()) {
                appendLine("${t(locale, "当前页面上下文", "Current page context")}:")
                if (context.currentPageSummary.isNotBlank()) {
                    appendLine(context.currentPageSummary)
                }
                if (context.firstStepGuidance.isNotBlank()) {
                    appendLine(context.firstStepGuidance)
                }
            }
            appendLine("${t(locale, "当前状态", "Current state")}: ${context.currentState.ifEmpty { t(locale, "未知", "Unknown") }}")
            appendLine("${t(locale, "建议下一步", "Suggested next step")}: ${context.nextStepHint.ifEmpty { t(locale, "无", "None") }}")
            appendLine("${t(locale, "已完成里程碑", "Completed milestones")}: $completedMilestones")
            appendLine("${t(locale, "关键记忆", "Key memory")}: $keyMemory")
            appendLine("${t(locale, "历史总结", "History summary")}: $summaryHistory")
            appendLine("${t(locale, "相关已安装应用", "Relevant installed apps")}: $installedApps")
            appendLine()
            appendLine(renderUnifiedActionSchema(context, locale))
            appendLine()
            appendLine("${t(locale, "本轮提醒", "Turn reminder")}:")
            appendLine(
                t(
                    locale,
                    "遵守统一 action schema：每轮恰好一个原生 tool_call。不要输出文本动作、Markdown、旧格式 action/swipe/coordinate/coordinate2，或任何不在 tools[] 里的工具名。",
                    "Follow the unified action schema: exactly one native tool_call per turn. Do not output text actions, Markdown, legacy action/swipe/coordinate/coordinate2 formats, or tool names that are not in tools[]."
                )
            )
            appendLine(
                t(
                    locale,
                    "完成判断：只有当前页面已经显示用户目标的最终状态，或上一轮工具结果明确完成了不可见系统动作，才调用 finished。还需要打开页面、选择项目、输入内容、保存、发送、确认、等待结果，或只是看到了目标入口，都不算完成；不确定时继续执行下一步，系统会在每轮自动刷新页面状态。",
                    "Completion rule: call finished only when the current page already shows the user's final target state, or the previous tool result explicitly completed an invisible system action. If any page opening, item selection, typing, saving, sending, confirmation, result wait, or visible target entry remains, the task is not complete; when uncertain, continue with the next action. The system refreshes page state automatically each turn."
                )
            )
            appendLine(
                t(
                    locale,
                    "如果截图是黑屏/空白，但 indexed evidence 或 visible_texts 包含当前页面和目标控件，请把这些压缩证据视为当前页面证据并继续选择统一 schema 中的工具；不要输出刷新状态、等待或空操作。",
                    "If the screenshot is black/blank but indexed evidence or visible_texts contains the current page and target control, treat that compact evidence as current-page evidence and continue with a tool from the unified schema; do not output refresh-state, wait, or no-op actions."
                )
            )
        }.trim()
    }

    private fun renderUnifiedActionSchema(context: UIContext, locale: PromptLocale): String {
        return buildString {
            appendLine(t(locale, "统一 action schema:", "Unified action schema:"))
            appendLine(VLMToolDefinitions.renderCompactActionSchemaGuide(locale))
            renderFunctionToolUsage(context, locale).takeIf { it.isNotBlank() }?.let {
                appendLine(it)
            }
        }.trim()
    }

    private fun renderFunctionToolUsage(context: UIContext, locale: PromptLocale): String {
        val functionNames = context.dynamicToolDefinitions.mapNotNull { definition ->
            (definition["function"] as? JsonObject)
                ?.get("name")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                ?.takeIf(String::isNotEmpty)
        }
        if (functionNames.isEmpty()) return ""
        return when (locale) {
            PromptLocale.ZH_CN -> buildString {
                appendLine("OmniFlow Function 工具:")
                appendLine("- 本轮召回的 Function 已作为真实 model tool 暴露: ${functionNames.joinToString(", ")}。")
                appendLine("- Function 是和 agent 一样的可组合复用片段：它可能完成整个目标，也可能只推进其中一段；调用后必须继续根据工具结果和 fresh page observe 判断下一步。")
                appendLine("- 当用户目标和某个 Function 的名称、描述、适用条件、参数 schema 高置信匹配时，优先直接调用该 Function tool，并从用户目标填写 arguments。")
                appendLine("- 不要把 Function 当成完成证明；Function 返回后，只有当前页面/工具结果证明目标完成时才调用 finished，否则继续选择 Function 或普通 GUI tool。")
                appendLine("- 如果没有高置信匹配，或者缺少必填参数，就继续使用普通 GUI tools，不要空参数调用。")
                appendLine("动态 Function action schema:")
                appendLine(renderDynamicFunctionSchemaSummary(context.dynamicToolDefinitions, locale))
            }
            PromptLocale.EN_US -> buildString {
                appendLine("OmniFlow Function tools:")
                appendLine("- Recalled Functions are exposed as real model tools this turn: ${functionNames.joinToString(", ")}.")
                appendLine("- A Function has the same meaning as in the agent: a composable reusable segment. It may complete the whole task or only advance one part; after calling it, continue from the tool result and the next fresh page observe.")
                appendLine("- If the user goal strongly matches a Function name, description, applicability, and parameter schema, prefer calling that Function tool directly and fill arguments from the user goal.")
                appendLine("- Do not treat a Function call as completion proof; call finished only when the current page/tool result proves completion. Otherwise continue with another Function or a normal GUI tool.")
                appendLine("- If confidence is low or required arguments are missing, use normal GUI tools instead of calling with empty arguments.")
                appendLine("Dynamic Function action schema:")
                appendLine(renderDynamicFunctionSchemaSummary(context.dynamicToolDefinitions, locale))
            }
        }.trim()
    }

    private fun renderDynamicFunctionSchemaSummary(
        definitions: List<JsonObject>,
        locale: PromptLocale
    ): String {
        return definitions.mapNotNull { definition ->
            val function = definition["function"] as? JsonObject ?: return@mapNotNull null
            val name = function["name"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: return@mapNotNull null
            val description = function["description"]?.jsonPrimitive?.contentOrNull
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.take(100)
                .orEmpty()
            val parameters = function["parameters"] as? JsonObject ?: JsonObject(emptyMap())
            val rawProperties = parameters["properties"] as? JsonObject ?: JsonObject(emptyMap())
            val properties = JsonObject(rawProperties.filterKeys { it != TOOL_TITLE_FIELD })
            val required = (parameters["required"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
                ?.filter { it != TOOL_TITLE_FIELD }
                ?.toSet()
                .orEmpty()
            val args = if (properties.isEmpty()) {
                when (locale) {
                    PromptLocale.ZH_CN -> "无参数，arguments 必须是 {}"
                    PromptLocale.EN_US -> "no parameters; arguments must be {}"
                }
            } else {
                properties.entries.joinToString(", ") { (argName, rawSpec) ->
                    val spec = rawSpec as? JsonObject ?: JsonObject(emptyMap())
                    val type = spec["type"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
                        ?: "any"
                    val requiredLabel = if (argName in required) "required" else "optional"
                    "$argName:$type:$requiredLabel"
                }
            }
            "- tool=$name; arguments={$args}; description=$description"
        }.joinToString("\n").take(MAX_DYNAMIC_FUNCTION_SCHEMA_CHARS)
    }

    private fun renderFocusedInstalledApps(context: UIContext, locale: PromptLocale): String {
        if (context.installedApplications.isEmpty()) {
            return t(locale, "暂无数据", "No data")
        }

        val ranked = focusedInstalledAppEntries(context)
        if (ranked.isEmpty()) {
            return t(
                locale,
                "未找到与任务直接相关的候选；如需打开 App，请只使用已知 targetPackage 或先观察确认。",
                "No directly relevant candidate found. If opening an app is required, use only the known targetPackage or observe first."
            )
        }

        val rendered = ranked.joinToString("\n") { (packageName, appName) ->
            "- $packageName -> $appName"
        }
        val hiddenCount = (context.installedApplications.size - ranked.size).coerceAtLeast(0)
        val note = if (hiddenCount > 0) {
            "\n" + t(
                locale,
                "注：这里只展示聚焦候选；不要猜未展示 package。",
                "Note: only focused candidates are shown; do not guess hidden package names."
            )
        } else {
            ""
        }
        return rendered + note
    }

    internal fun focusedInstalledAppEntries(context: UIContext): List<Map.Entry<String, String>> {
        if (context.installedApplications.isEmpty()) return emptyList()
        val targetPackage = context.targetPackageName.trim()
        val currentPackage = context.currentPackageName.trim()
        val queryTerms = appQueryTerms(context)

        data class ScoredApp(
            val entry: Map.Entry<String, String>,
            val score: Int,
            val originalIndex: Int
        )

        return context.installedApplications.entries
            .mapIndexedNotNull { index, entry ->
                val packageName = entry.key.trim()
                val appName = entry.value.trim()
                if (packageName.isBlank()) return@mapIndexedNotNull null
                val packageLower = packageName.lowercase(Locale.ROOT)
                val appLower = appName.lowercase(Locale.ROOT)
                val packageTail = packageLower.substringAfterLast('.')

                var score = 0
                if (targetPackage.isNotBlank() && packageName.equals(targetPackage, ignoreCase = true)) score += 1000
                if (currentPackage.isNotBlank() && packageName.equals(currentPackage, ignoreCase = true)) score += 800
                queryTerms.forEach { term ->
                    when {
                        term.length <= 1 -> Unit
                        appLower == term -> score += 120
                        packageTail == term -> score += 110
                        packageLower.endsWith(".$term") -> score += 90
                        appLower.contains(term) -> score += 60
                        packageLower.contains(term) -> score += 35
                    }
                }
                if (score <= 0) return@mapIndexedNotNull null
                ScoredApp(entry, score, index)
            }
            .sortedWith(
                compareByDescending<ScoredApp> { it.score }
                    .thenBy { it.originalIndex }
            )
            .map { it.entry }
            .take(MAX_FOCUSED_INSTALLED_APPS)
    }

    private fun appQueryTerms(context: UIContext): Set<String> {
        val raw = listOf(
            context.overallTask,
            context.currentStepGoal,
            context.stepSkillGuidance,
            context.currentState,
            context.nextStepHint,
            context.targetPackageName,
            context.currentPackageName,
        ).joinToString(" ")

        val terms = linkedSetOf<String>()
        Regex("""[\p{L}\p{N}._-]+""").findAll(raw.lowercase(Locale.ROOT)).forEach { match ->
            val token = match.value.trim('.', '_', '-')
            if (token.length < 2) return@forEach
            if (token in APP_QUERY_STOP_WORDS) return@forEach
            terms += token
            token.split('.', '_', '-')
                .map { it.trim() }
                .filter { it.length >= 2 && it !in APP_QUERY_STOP_WORDS }
                .forEach { terms += it }
        }
        return terms.take(MAX_APP_QUERY_TERMS).toSet()
    }

    fun buildToolCallRetryPrompt(context: UIContext, retryState: VLMToolCallRetryState): String {
        val locale = currentLocale()
        val thinking = retryState.thinking
        return buildString {
            val failureReason = retryState.failureReason?.trim().orEmpty()
            if (failureReason.isNotEmpty()) {
                appendLine(
                    t(
                        locale,
                        "系统检查到你上一轮的 tool_call 参数不合规：$failureReason",
                        "The system detected that the tool_call arguments from your previous turn were invalid: $failureReason"
                    )
                )
            } else {
                appendLine(
                    t(
                        locale,
                        "系统检查到你上一轮没有返回标准 tool_calls，但当前任务仍是执行型 GUI 自动化。",
                        "The system detected that your previous turn did not return standard tool_calls, but the current task is still an execution-oriented GUI automation task."
                    )
                )
            }
            appendLine(
                t(
                    locale,
                    "请在本轮严格返回一个原生 tool_call，并从 tools 列表中选择下一步动作。",
                    "In this turn, return exactly one native tool_call and choose the next action from the tools list."
                )
            )
            appendLine(
                t(
                    locale,
                    "必须改用原生 tool_calls，且工具名必须来自本轮 tools[]；不要用文本 JSON 表达动作。",
                    "Switch to native tool_calls, and the tool name must come from this turn's tools[]; do not express actions as text JSON."
                )
            )
            appendLine(
                t(
                    locale,
                    "不要只输出 observation/thought/summary JSON，不要提前宣布任务完成。",
                    "Do not output only observation/thought/summary JSON and do not announce completion prematurely."
                )
            )
            appendLine(
                t(
                    locale,
                    "只有当用户目标已经真正完成时，才能调用 finished。",
                    "Call finished only when the user's goal is truly complete."
                )
            )
            appendLine(
                t(
                    locale,
                    "若你判断下一步是点击、输入、滑动、返回或结束，请直接使用对应工具；不要使用停留、延时或空操作类动作，稳定停留由系统内部处理。",
                    "If the next step should be tap, type, scroll, go back, or finish, call the matching tool directly. Do not use idle, delay, or no-op actions; stable settling is handled internally."
                )
            )
            appendLine(
                t(
                    locale,
                    "若需要坐标，必须分别写入 x/y 或 x1/y1/x2/y2；每个字段都只能是单个数值，不要返回 [x,y]、coordinates 或对象。",
                    "If coordinates are needed, write them separately into x/y or x1/y1/x2/y2. Each field must be a single numeric scalar; do not return [x,y], coordinates, or objects."
                )
            )
            appendLine(
                t(
                    locale,
                    "本次为第 ${retryState.retryIndex} 次协议纠偏。",
                    "This is protocol correction attempt #${retryState.retryIndex}."
                )
            )
            appendLine("${t(locale, "用户原始任务", "Original user task")}: ${context.overallTask}")
            appendLine("${t(locale, "当前子目标", "Current sub-goal")}: ${context.activeGoal()}")
            thinking.finishReason?.takeIf { it.isNotBlank() }?.let {
                appendLine("${t(locale, "上一轮 finish_reason", "Previous finish_reason")}: $it")
            }
            thinking.observation.takeIf { it.isNotBlank() }?.let {
                appendLine("${t(locale, "上一轮 observation", "Previous observation")}: ${truncateForRetry(it)}")
            }
            thinking.thought.takeIf { it.isNotBlank() }?.let {
                appendLine("${t(locale, "上一轮 thought", "Previous thought")}: ${truncateForRetry(it)}")
            }
            thinking.summary.takeIf { it.isNotBlank() }?.let {
                appendLine("${t(locale, "上一轮 summary", "Previous summary")}: ${truncateForRetry(it)}")
            }
            thinking.reasoning.takeIf { it.isNotBlank() }?.let {
                appendLine("${t(locale, "上一轮 reasoning_content", "Previous reasoning_content")}: ${truncateForRetry(it, maxLen = 900)}")
            }
        }.trim()
    }

    private fun truncateForRetry(text: String, maxLen: Int = 280): String {
        val normalized = text.replace("\r\n", "\n").trim()
        return if (normalized.length <= maxLen) normalized else normalized.take(maxLen) + "..."
    }

    private const val MAX_FOCUSED_INSTALLED_APPS = 12
    private const val MAX_APP_QUERY_TERMS = 24
    private const val MAX_DYNAMIC_FUNCTION_SCHEMA_CHARS = 900
    private const val TOOL_TITLE_FIELD = "tool_title"
    private val APP_QUERY_STOP_WORDS = setOf(
        "the",
        "and",
        "for",
        "with",
        "from",
        "into",
        "then",
        "after",
        "open",
        "start",
        "stop",
        "page",
        "screen",
        "task",
        "app",
        "application",
        "android",
        "com",
        "设置",
        "打开",
        "应用",
        "页面",
        "任务",
        "当前",
        "然后",
        "完成",
    )
}
