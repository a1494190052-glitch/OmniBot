package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.i18n.PromptLocale
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
        return buildTurnUserPrompt(context, sceneId = sceneId)
    }

    @Suppress("UNUSED_PARAMETER")
    fun buildSystemPrompt(sceneId: String? = null): String {
        return buildToolActionSystemPrompt(currentLocale())
    }

    private fun buildToolActionSystemPrompt(locale: PromptLocale): String {
        val base = buildHardcodedSystemPrompt(locale)
        val extra = VLMSystemPromptRegistry.get()
        return if (extra.isNullOrBlank()) base else "$base\n\n$extra"
    }

    private fun buildHardcodedSystemPrompt(locale: PromptLocale): String {
        return t(
            locale,
            """
            你是 Android 手机 GUI-Agent，只负责为当前手机界面选择下一步动作。

            动作合同以本轮 OpenAI tools[] JSON schema 为唯一来源：
            1. 每轮必须且只能返回 tools[] 中一个原生 tool_call。
            2. function.arguments 必须是严格 JSON object，并满足所选工具的 schema。
            3. schema.required 里的字段必须全部填写；可选字段不能替代 required 字段。
            4. 不要输出 tools[] 外的工具名、旧文本动作格式、call_tool、function_id 或隐藏 Function 工具；若 tools[] 中出现 run_recalled_workflow_*，它是本轮已召回工作流工具，明显匹配当前目标时优先调用，否则继续普通 UI action。
            5. assistant.content 可为空；若返回，只能是 {"summary":"约20字本步摘要"}，不要包含动作参数。

            每轮先判断目标是否已经达成；若已达成，直接调用 finished，不要重复点击已经聚焦或已经打开的目标控件。
            根据后续 user 消息里的用户任务、当前截图、OOB compact page state 和 RunLog 动作摘要选择动作；raw XML 只供本地 runtime 内部使用。
            """.trimIndent(),
            """
            You are an Android phone GUI agent and only choose the next action for the current phone UI.

            The action contract is defined only by this turn's OpenAI tools[] JSON schema:
            1. Each turn must return exactly one native tool_call from tools[].
            2. function.arguments must be a strict JSON object that satisfies the selected tool schema.
            3. Every field listed in schema.required must be present; optional fields cannot replace required fields.
            4. Do not output tool names outside tools[], legacy text action formats, call_tool, function_id, or hidden Function tools. If tools[] includes run_recalled_workflow_*, it is a recalled workflow tool for this turn; prefer it when it clearly matches the current goal, otherwise continue with ordinary UI actions.
            5. assistant.content may be empty. If present, it must only be {"summary":"about 20 words for this step"} and must not contain action arguments.

            First check whether the goal is already satisfied this turn. If it is, call finished and do not click a target control that is already focused or already open.
            Choose the action from the later user message's user task, current screenshot, OOB compact page state, and RunLog action summary. Raw XML is internal-only for the local runtime.
            """.trimIndent()
        )
    }

    @Suppress("UNUSED_PARAMETER")
    fun buildTurnUserPrompt(
        context: UIContext,
        sceneId: String? = null,
        runLogSteps: List<Map<String, Any?>> = emptyList(),
    ): String {
        val locale = currentLocale()
        val installedApps = renderFocusedInstalledApps(context, locale)
        val keyMemory = if (context.keyMemory.isNotEmpty()) {
            context.keyMemory.joinToString(
                separator = if (locale == PromptLocale.ZH_CN) "；" else "; "
            )
        } else {
            t(locale, "暂无", "None yet")
        }
        val transientEventSection = if (context.transientEvents.isNotEmpty()) {
            buildString {
                appendLine(t(locale, "【本轮临时事件】", "[Transient events for this turn]"))
                context.transientEvents.forEach { event ->
                    appendLine("- [${event.type}] ${event.text}")
                    if (event.suggestCompletion) {
                        appendLine(
                            t(
                                locale,
                                "如果已经确认任务完成，请尽快调用 finished 工具结束任务。",
                                "If the task has already been confirmed complete, call the finished tool as soon as possible."
                            )
                        )
                    }
                }
            }.trim()
        } else if (context.priorityEvent != null) {
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
        val runLogHistory = VLMRunLogPlannerHistory.render(runLogSteps)

        return buildString {
            appendLine(
                t(
                    locale,
                    "请根据用户任务、当前截图和 RunLog 中更早动作的文本摘要，只选择一个下一步动作。原始 XML 只供系统内部使用。",
                    "Choose exactly one next action from the user task, current screenshot, and textual summaries of earlier RunLog actions. Raw XML is internal-only."
                )
            )
            appendLine("${t(locale, "用户任务", "User task")}: ${context.overallTask}")
            if (context.activeGoal() != context.overallTask) {
                appendLine("${t(locale, "当前子目标", "Current sub-goal")}: ${context.activeGoal()}")
            }
            renderSkillGuidance(context, locale)
                .takeUnless { it == t(locale, "无", "None") }
                ?.let { appendLine("${t(locale, "技能提示", "Skill guidance")}: $it") }
            if (transientEventSection.isNotBlank()) {
                appendLine(transientEventSection)
            }
            renderPageExplanationBlock(context, locale).takeIf { it.isNotBlank() }?.let {
                appendLine(it)
            }
            appendLine(t(locale, "【更早动作（来自当前 RunLog）】", "[Earlier actions from the current RunLog]"))
            appendLine(runLogHistory)
            if (context.keyMemory.isNotEmpty()) {
                appendLine("${t(locale, "关键记忆", "Key memory")}: $keyMemory")
            }
            appendLine("${t(locale, "相关已安装应用", "Relevant installed apps")}: $installedApps")
            renderCoordinateSystemBlock(context, locale).takeIf { it.isNotBlank() }?.let {
                appendLine(it)
            }
        }.trim()
    }

    private fun renderSkillGuidance(context: UIContext, locale: PromptLocale): String {
        val raw = context.stepSkillGuidance.trim()
        if (raw.isEmpty()) return t(locale, "无", "None")
        val compacted = raw
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filterNot { line ->
                line.contains("schema.required", ignoreCase = true) ||
                    line.contains("tool_call", ignoreCase = true) ||
                    line.contains("tools[]", ignoreCase = true) ||
                    line.contains("function_id", ignoreCase = true) ||
                    line.contains("call_tool", ignoreCase = true)
            }
            .distinct()
            .joinToString(" ")
            .ifBlank { raw.replace(Regex("""\s+"""), " ") }
        return compactLine(compacted, MAX_SKILL_GUIDANCE_CHARS)
    }

    private fun renderCoordinateSystemBlock(context: UIContext, locale: PromptLocale): String {
        val width = context.displayWidth
        val height = context.displayHeight
        if (width <= 0 || height <= 0) return ""
        return t(
            locale,
            "坐标系统：凡所选工具 schema.required 要求的 x/y/x1/y1/x2/y2，都必须输出 0..1000 相对坐标，其中 x=0 是屏幕左侧、x=1000 是右侧、y=0 是顶部、y=1000 是底部。Action、RunLog 和 Function 始终保存相对坐标；只有 Android 执行动作时才转换一次为当前屏幕像素。",
            "Coordinate system: whenever the selected tool's schema.required includes x/y/x1/y1/x2/y2, output them as 0..1000 relative coordinates: x=0 is the left edge, x=1000 is the right edge, y=0 is the top edge, and y=1000 is the bottom edge. Action, RunLog, and Function always store relative coordinates; conversion to current-screen pixels happens exactly once at Android action dispatch."
        )
    }

    private fun renderPageExplanationBlock(context: UIContext, locale: PromptLocale): String {
        val pageContext = listOf(context.currentPageSummary, context.firstStepGuidance)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString("\n")
            .take(MAX_PAGE_EXPLANATION_CHARS)
            .trim()
        if (pageContext.isBlank()) return ""
        return buildString {
            appendLine(t(locale, "【页面解释】", "[Page Explanation]"))
            append(pageContext)
        }.trim()
    }

    private fun compactLine(value: String, maxLen: Int = 240): String {
        val normalized = value
            .replace("\r\n", "\n")
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString(" ")
        return if (normalized.length <= maxLen) normalized else normalized.take(maxLen) + "..."
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
            retryState.toolCallFailure?.let { failure ->
                failure.toolName?.takeIf(String::isNotBlank)?.let {
                    appendLine("${t(locale, "上一轮实际工具", "Previous actual tool")}: $it")
                }
                if (failure.requiredFields.isNotEmpty()) {
                    appendLine(
                        "${t(locale, "该工具必填字段", "Required fields for this tool")}: " +
                            failure.requiredFields.joinToString(", ")
                    )
                }
                if (failure.providedFields.isNotEmpty()) {
                    val providedShape = failure.providedFields.joinToString(", ") { field ->
                        "$field:${failure.argumentTypes[field] ?: "unknown"}"
                    }
                    appendLine("${t(locale, "上一轮实际字段与类型", "Previous argument fields and types")}: $providedShape")
                }
                if (failure.missingFields.isNotEmpty()) {
                    appendLine(
                        "${t(locale, "上一轮缺失字段", "Missing fields in the previous turn")}: " +
                            failure.missingFields.joinToString(", ")
                    )
                }
                if (failure.toolName != null && failure.requiredFields.isNotEmpty()) {
                    val minimalShape = failure.requiredFields.joinToString(
                        prefix = "{",
                        postfix = "}",
                    ) { field -> "\"$field\":${retryArgumentExample(field)}" }
                    appendLine(
                        t(
                            locale,
                            "强制纠错：若本轮仍调用 ${failure.toolName}，function.arguments 至少必须具有这个完整 JSON 形状：$minimalShape。任何 required 字段缺失都会被拒绝，且动作不会执行。",
                            "Mandatory correction: if you call ${failure.toolName} again, function.arguments must at least have this complete JSON shape: $minimalShape. Any missing required field will be rejected and the action will not execute."
                        )
                    )
                }
                failure.safeArgumentsPreview?.takeIf(String::isNotBlank)?.let {
                    appendLine("${t(locale, "上一轮安全参数预览", "Safe previous arguments preview")}: $it")
                }
                appendLine(
                    t(
                        locale,
                        "保留上一轮仍然正确的字段，只修正缺失或类型错误的字段；不要再次提交同一个非法结构。",
                        "Keep fields that were already valid and correct only missing or mistyped fields; do not submit the same invalid structure again."
                    )
                )
            }
            appendLine(
                t(
                    locale,
                    "请在本轮严格返回一个原生 tool_call，并让 function.arguments 满足所选工具的 tools[] JSON schema。",
                    "In this turn, return exactly one native tool_call and make function.arguments satisfy the selected tool's tools[] JSON schema."
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
                    "If the next step should be tap, type, swipe, press_key, or finish, call the matching tool directly. Do not use idle, delay, or no-op actions; stable settling is handled internally."
                )
            )
            appendLine(
                t(
                    locale,
                    "schema.required 里的字段必须全部填写；若 required 包含坐标，必须分别写入 x/y 或 x1/y1/x2/y2，每个字段都只能是单个数值，不要返回 [x,y]、coordinates 或对象。",
                    "Every schema.required field must be present. If required fields include coordinates, write them separately into x/y or x1/y1/x2/y2. Each field must be a single numeric scalar; do not return [x,y], coordinates, or objects."
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

    private fun retryArgumentExample(field: String): String {
        return when (field) {
            "x", "y", "x1", "y1", "x2", "y2", "distance" -> "500"
            "duration_ms" -> "1000"
            "direction" -> "\"up\""
            "key" -> "\"back\""
            "package_name" -> "\"com.example.app\""
            "options" -> "[\"option\"]"
            "text" -> "\"required text\""
            else -> "\"value\""
        }
    }

    private const val MAX_FOCUSED_INSTALLED_APPS = 12
    private const val MAX_APP_QUERY_TERMS = 24
    private const val MAX_PAGE_EXPLANATION_CHARS = 1_200
    private const val MAX_SKILL_GUIDANCE_CHARS = 480
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

    fun summaryPrompt(goal: String): String = """
# Role: 智能视觉信息整合与决策专家

# Task
你将收到用户的**原始目标**以及一组**按时间顺序排列的屏幕截图**（Agent 的执行过程）。
你的任务是：**忽略操作过程中的无关细节（如点击位置、加载状态），像人类浏览网页一样，从截图中"阅读"并提取关键信息，最终为用户生成一份直接响应其目标的交付物。**

# Input Data
## 1. 用户原始目标 (User Goal)
$goal

## 2. 视觉证据 (Visual Evidence)
*（附带了一组连续的屏幕截图，记录了搜索和浏览的全过程）*
请仔细阅读附带的图片序列。图片内容可能包含：搜索引擎结果、具体网页详情、地图路线、表格数据等。

# Thinking Process (CoT)
1. **目标拆解**：明确用户到底想要什么？（是攻略、表格、代码、还是摘要？）
2. **视觉信息提取**：
   - 按顺序浏览图片。
   - **过滤噪点**：忽略浏览器的地址栏、侧边栏广告、弹窗关闭按钮等 UI 元素。
   - **抓取干货**：重点识别图片中的正文文本、价格数字、时间表、景点介绍、优缺点评价等。
   - **关联上下文**：如果图1是搜索列表，图2是详情页，则以图2的详情为准。
3. **逻辑重组**：将从多张图片中提取的碎片信息整合成一个连贯的整体。
4. **交付生成**：根据目标类型，输出最终结果。

# Constraints
- **直接回答**：不要包含"根据搜索结果"、"我整理了以下内容"等开场白，直接给出融合相关的浏览结果。
- **禁止流水账**：不要描述图片（例如不要说"第1张图显示了百度首页..."），直接使用图里的信息回答问题。
- **事实准确**：严禁编造图片中不存在的数值（如价格、时间），如果图片中未展示关键信息，请注明"未知"。
- **格式规范**：确保易读性。

# Final Answer
(请直接输出针对用户目标的最终整理结果...)
""".trimIndent()
}
