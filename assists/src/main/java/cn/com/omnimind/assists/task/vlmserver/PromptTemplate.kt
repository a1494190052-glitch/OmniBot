package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.assists.util.TimeUtil
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.i18n.PromptLocale
import cn.com.omnimind.baselib.llm.ModelSceneRegistry
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
            renderPageExplanationBlock(context, locale).takeIf { it.isNotBlank() }?.let {
                appendLine(it)
            }
            appendLine(renderRecentResultsBlock(context, locale))
            appendLine("${t(locale, "已完成里程碑", "Completed milestones")}: $completedMilestones")
            appendLine("${t(locale, "关键记忆", "Key memory")}: $keyMemory")
            appendLine("${t(locale, "相关已安装应用", "Relevant installed apps")}: $installedApps")
            renderCoordinateSystemBlock(context, locale).takeIf { it.isNotBlank() }?.let {
                appendLine(it)
            }
            appendLine()
            appendLine(renderUnifiedActionSchema(context, locale))
            appendLine()
            appendLine("${t(locale, "本轮提醒", "Turn reminder")}:")
            appendLine(
                t(
                    locale,
                    "遵守统一 action schema：每轮恰好一个原生 tool_call。优先把 OOB indexed page evidence 当成可执行菜单：目标出现在 #index 中时，click/input_text/long_press 必须填写 element_index；swipe 目标出现在 Sindex 中时必须填写 scrollable_index。不要凭截图估坐标，除非 evidence 没有目标。",
                    "Follow the unified action schema: exactly one native tool_call per turn. Treat OOB indexed page evidence as the executable menu first: when the target appears as #index, click/input_text/long_press must include element_index; when a swipe target appears as Sindex, include scrollable_index. Do not estimate coordinates from the screenshot unless the target is missing from evidence."
                )
            )
            appendLine(
                t(
                    locale,
                    "不要输出文本动作、Markdown、旧格式 action/swipe/coordinate/coordinate2，或任何不在 tools[] 里的工具名。",
                    "Do not output text actions, Markdown, legacy action/swipe/coordinate/coordinate2 formats, or tool names that are not in tools[]."
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

    private fun renderCoordinateSystemBlock(context: UIContext, locale: PromptLocale): String {
        val width = context.displayWidth
        val height = context.displayHeight
        if (width <= 0 || height <= 0) return ""
        return t(
            locale,
            "坐标系统：如果必须使用坐标兜底，所有 x/y/x1/y1/x2/y2 都输出 0..1000 相对坐标，其中 x=0 是屏幕左侧、x=1000 是右侧、y=0 是顶部、y=1000 是底部。系统会在执行前把相对坐标解码为当前屏幕绝对像素。本地记录和执行结果始终保存绝对像素。",
            "Coordinate system: when coordinate fallback is necessary, output all x/y/x1/y1/x2/y2 as 0..1000 relative coordinates: x=0 is the left edge, x=1000 is the right edge, y=0 is the top edge, and y=1000 is the bottom edge. The system decodes relative coordinates to current-screen absolute pixels before execution. Local records and execution results always store absolute pixels."
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

    private fun renderUnifiedActionSchema(context: UIContext, locale: PromptLocale): String {
        return buildString {
            appendLine(t(locale, "统一 action schema:", "Unified action schema:"))
            appendLine(VLMToolDefinitions.renderCompactActionSchemaGuide(locale))
        }.trim()
    }

    private fun renderRecentResultsBlock(context: UIContext, locale: PromptLocale): String {
        val recent = context.trace.takeLast(MAX_RECENT_RESULT_STEPS)
        return buildString {
            appendLine()
            appendLine(t(locale, "【最近结果】", "[Recent Results]"))
            if (recent.isEmpty() && context.runningSummary.isBlank()) {
                append(t(locale, "暂无；这是本任务的起始阶段。", "None yet; this is the beginning of the task."))
                return@buildString
            }
            if (context.runningSummary.isNotBlank()) {
                appendLine("${t(locale, "历史总结", "History summary")}: ${compactLine(context.runningSummary, 420)}")
            }
            recent.forEachIndexed { offset, step ->
                val number = context.trace.size - recent.size + offset + 1
                appendLine("- #$number ${actionSummary(step.action)} | success=${stepSucceeded(step)} | result=${stepResultForModel(step, locale)}")
            }
        }.trim()
    }

    private fun actionSummary(action: UIAction): String {
        return when (action) {
            is ClickAction -> "click ${compactLine(action.targetDescription, 80)}"
            is InputTextAction -> "input_text ${compactLine(action.targetDescription, 60)} text=${compactLine(action.text, 80)}"
            is SwipeAction -> "swipe ${compactLine(action.targetDescription, 80)} ${action.direction.orEmpty()}"
            is LongPressAction -> "long_press ${compactLine(action.targetDescription, 80)}"
            is OpenAppAction -> "open_app ${action.packageName}"
            is PressKeyAction -> "press_key ${action.key}"
            is FunctionRunAction -> "function ${action.functionId}"
            is FinishedAction -> "finished"
            is RequireUserChoiceAction -> "require_user_choice"
            is RequireUserConfirmationAction -> "require_user_confirmation"
            is RecordAction -> "record"
            is InfoAction -> "info"
            is FeedbackAction -> "feedback"
            is AbortAction -> "abort"
            is WaitAction -> "wait"
            is GetStateAction -> "get_state"
        }.trim()
    }

    private fun stepSucceeded(step: UIStep): Boolean {
        val result = step.result?.trim().orEmpty()
        return result.isEmpty() ||
            (!result.startsWith("执行失败") && !result.startsWith("failed", ignoreCase = true))
    }

    private fun stepResultForModel(step: UIStep, locale: PromptLocale): String {
        val result = step.result?.trim().orEmpty()
        if (result.isNotEmpty()) return compactLine(result, MAX_RECENT_RESULT_CHARS)
        val summary = step.summary.trim()
        if (summary.isNotEmpty()) return compactLine(summary, MAX_RECENT_RESULT_CHARS)
        return t(locale, "已发送动作；等待最新页面观察。", "Action was dispatched; use the latest page observe.")
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
                    "If the next step should be tap, type, swipe, press_key, or finish, call the matching tool directly. Do not use idle, delay, or no-op actions; stable settling is handled internally."
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
    private const val MAX_PAGE_EXPLANATION_CHARS = 2_200
    private const val MAX_RECENT_RESULT_STEPS = 4
    private const val MAX_RECENT_RESULT_CHARS = 260
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
