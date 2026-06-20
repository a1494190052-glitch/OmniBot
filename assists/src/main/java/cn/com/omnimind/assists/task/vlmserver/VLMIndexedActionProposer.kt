package cn.com.omnimind.assists.task.vlmserver

/**
 * Proposes a conservative model-free first action from live indexed XML.
 *
 * This only handles clear visible-target clicks and focused-field text input.
 * Ambiguous screens, scrolls, and app-launch decisions still fall through to
 * the online VLM.
 */
object VLMIndexedActionProposer {
    data class Proposal(
        val step: UIStep,
        val diagnostics: Map<String, String>,
    )

    fun propose(
        context: UIContext,
        currentXml: String?,
        displayWidth: Int,
        displayHeight: Int,
        stepIndex: Int,
    ): Proposal? {
        val goal = context.activeGoal().ifBlank { context.overallTask }.trim()
        val skipReason = baseSkipReason(context, goal, currentXml, displayWidth, displayHeight)
        if (skipReason != null) {
            return null
        }

        proposeFocusedInput(
            goal = goal,
            currentXml = currentXml,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
        )?.let { return it }

        if (!canProposeClick(context, goal, stepIndex)) {
            return null
        }
        val targetQueries = targetQueries(goal)
        if (targetQueries.isEmpty()) return null
        val preferEditable = isInputTargetGoal(goal) && !isSearchTargetGoal(goal)
        val matchedBy = arrayOf("")
        val target = targetQueries.firstNotNullOfOrNull { query ->
            VLMIndexedPageContext.visibleElementTargetByDescription(
                currentXml = currentXml,
                displayWidth = displayWidth,
                displayHeight = displayHeight,
                targetDescription = query,
                preferEditable = preferEditable,
            )?.also {
                matchedBy[0] = "description"
            }
        } ?: uniqueFieldTargetForInputGoal(
            goal = goal,
            currentXml = currentXml,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
        )?.also {
            matchedBy[0] = "unique_form_field"
        } ?: return null

        val relativeX = encodeRelativeCoordinate(target.centerX, displayWidth)
        val relativeY = encodeRelativeCoordinate(target.centerY, displayHeight)
        val targetDescription = target.label.ifBlank { targetQueries.first() }
        val action = ClickAction(
            targetDescription = targetDescription,
            x = relativeX,
            y = relativeY,
            nodeId = target.nodeId,
        )
        val message = "indexed evidence matched visible target: $targetDescription"
        return Proposal(
            step = UIStep(
                observation = "INDEXED_EVIDENCE_MATCHED",
                thought = message,
                action = action,
                summary = "[indexed_action:${target.index}] $targetDescription",
            ),
            diagnostics = linkedMapOf(
                "indexed_action_proposal" to "matched",
                "indexed_action_proposal_tool" to action.name,
                "indexed_action_proposal_target" to targetDescription.take(MAX_DIAGNOSTIC_CHARS),
                "indexed_action_proposal_index" to target.index.toString(),
                "indexed_action_proposal_query_count" to targetQueries.size.toString(),
                "indexed_action_proposal_prefer_editable" to preferEditable.toString(),
                "indexed_action_proposal_matcher" to matchedBy[0],
            )
        )
    }

    private fun uniqueFieldTargetForInputGoal(
        goal: String,
        currentXml: String?,
        displayWidth: Int,
        displayHeight: Int,
    ): VLMIndexedPageContext.IndexedTarget? {
        if (!isInputTargetGoal(goal)) return null
        if (wantsTextEntry(goal) && !hasClickThenTextEntryIntent(goal)) return null
        return VLMIndexedPageContext.uniqueFormFieldTarget(
            currentXml = currentXml,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            preferEditable = true,
        )
    }

    private fun proposeFocusedInput(
        goal: String,
        currentXml: String?,
        displayWidth: Int,
        displayHeight: Int,
    ): Proposal? {
        if (!wantsTextEntry(goal) || wantsScroll(goal)) return null
        val text = extractInputText(goal) ?: return null
        val target = VLMIndexedPageContext.focusedEditableTarget(
            currentXml = currentXml,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
        ) ?: return null

        val relativeX = encodeRelativeCoordinate(target.centerX, displayWidth)
        val relativeY = encodeRelativeCoordinate(target.centerY, displayHeight)
        val targetDescription = target.label.ifBlank { "focused input field" }
        val action = InputTextAction(
            targetDescription = targetDescription,
            text = text,
            x = relativeX,
            y = relativeY,
            nodeId = target.nodeId,
        )
        val message = "indexed evidence matched focused editable target: $targetDescription"
        return Proposal(
            step = UIStep(
                observation = "INDEXED_EVIDENCE_MATCHED",
                thought = message,
                action = action,
                summary = "[indexed_action:${target.index}] input_text $targetDescription",
            ),
            diagnostics = linkedMapOf(
                "indexed_action_proposal" to "matched",
                "indexed_action_proposal_tool" to action.name,
                "indexed_action_proposal_target" to targetDescription.take(MAX_DIAGNOSTIC_CHARS),
                "indexed_action_proposal_index" to target.index.toString(),
                "indexed_action_proposal_text_chars" to text.length.toString(),
                "indexed_action_proposal_focused_editable" to "true",
            )
        )
    }

    private fun baseSkipReason(
        context: UIContext,
        goal: String,
        currentXml: String?,
        displayWidth: Int,
        displayHeight: Int,
    ): String? {
        if (goal.isBlank()) return "blank_goal"
        if (currentXml.isNullOrBlank()) return "missing_xml"
        if (displayWidth <= 0 || displayHeight <= 0) return "missing_display_size"
        if (context.dynamicToolDefinitions.isNotEmpty()) return "function_recall_context"
        if (
            context.targetPackageName.isNotBlank() &&
            context.currentPackageName.isNotBlank() &&
            !context.targetPackageName.equals(context.currentPackageName, ignoreCase = true)
        ) {
            return "target_package_mismatch"
        }
        return null
    }

    private fun canProposeClick(
        context: UIContext,
        goal: String,
        stepIndex: Int,
    ): Boolean {
        if (stepIndex != 0 || context.trace.isNotEmpty()) return false
        if (!hasDirectClickIntent(goal)) return false
        if (wantsScroll(goal)) return false
        if (wantsTextEntry(goal)) {
            return hasClickThenTextEntryIntent(goal) && isInputTargetGoal(goal)
        }
        if (hasFollowUpInstruction(goal)) return false
        return true
    }

    private fun targetQueries(goal: String): List<String> {
        val normalized = normalizeSpace(goal)
        val values = linkedSetOf<String>()
        if (isSearchTargetGoal(normalized)) {
            values += "搜索框"
            values += "搜索"
            values += "search"
            values += "search box"
            values += "search field"
            values += "search bar"
        }
        if (isInputTargetGoal(normalized)) {
            values += "输入框"
            values += "文本框"
            values += "input field"
            values += "text field"
            values += "edit text"
        }
        strippedTarget(normalized).takeIf { it.isNotBlank() }?.let { target ->
            values += target
            stripGenericPageWords(target).takeIf { it.isNotBlank() && it != target }?.let {
                values += it
            }
            if (target.endsWith("框") && target.length > 1) {
                values += target.removeSuffix("框")
            }
        }
        return values
            .map(::cleanQuery)
            .filter { it.length >= 2 || it.any { char -> char.code > 127 } }
            .distinct()
            .take(MAX_TARGET_QUERIES)
    }

    private fun strippedTarget(goal: String): String {
        var value = " $goal "
        STRIP_PHRASES.forEach { phrase ->
            value = value.replace(phrase, " ", ignoreCase = true)
        }
        return cleanQuery(value)
    }

    private fun cleanQuery(value: String): String =
        normalizeSpace(
            value
                .trim(' ', '\t', '\n', '\r', '，', ',', '。', '.', '；', ';', '：', ':')
                .removePrefix("当前")
                .removePrefix("页面")
                .removePrefix("这个")
                .removePrefix("该")
                .removeSuffix("一下")
                .removeSuffix("控件")
                .removeSuffix("按钮")
        )

    private fun stripGenericPageWords(value: String): String {
        var normalized = cleanQuery(value)
        GENERIC_PAGE_WORDS.forEach { word ->
            normalized = normalized
                .removePrefix("$word ")
                .removePrefix(word)
                .removeSuffix(" $word")
                .removeSuffix(word)
                .trim()
        }
        return cleanQuery(normalized)
    }

    private fun encodeRelativeCoordinate(value: Float, displaySize: Int): Float =
        ((value / displaySize.coerceAtLeast(1).toFloat()) * 1000f).coerceIn(0f, 1000f)

    private fun hasDirectClickIntent(goal: String): Boolean =
        DIRECT_CLICK_VERBS.any { goal.contains(it, ignoreCase = true) }

    private fun wantsTextEntry(goal: String): Boolean =
        TEXT_ENTRY_VERBS.any { strippedInputTargetTerms(goal).contains(it, ignoreCase = true) }

    private fun wantsScroll(goal: String): Boolean =
        SCROLL_VERBS.any { goal.contains(it, ignoreCase = true) }

    private fun hasClickThenTextEntryIntent(goal: String): Boolean =
        hasDirectClickIntent(goal) && wantsTextEntry(goal) && FOLLOW_UP_MARKERS.any {
            goal.lowercase().contains(it)
        }

    private fun isSearchTargetGoal(goal: String): Boolean =
        SEARCH_TARGETS.any { goal.contains(it, ignoreCase = true) }

    private fun isInputTargetGoal(goal: String): Boolean =
        INPUT_TARGETS.any { goal.contains(it, ignoreCase = true) }

    private fun hasFollowUpInstruction(goal: String): Boolean {
        val normalized = goal.lowercase()
        return FOLLOW_UP_MARKERS.any { normalized.contains(it) }
    }

    private fun extractInputText(goal: String): String? {
        val normalized = normalizeSpace(goal)
        INPUT_TEXT_PATTERNS.forEach { pattern ->
            val match = pattern.find(normalized) ?: return@forEach
            val raw = match.groupValues.getOrNull(1).orEmpty()
            cleanInputText(raw).takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun strippedInputTargetTerms(goal: String): String {
        var normalized = goal
        INPUT_TARGETS.forEach { target ->
            normalized = normalized.replace(target, " ", ignoreCase = true)
        }
        return normalizeSpace(normalized)
    }

    private fun cleanInputText(value: String): String =
        normalizeSpace(value)
            .trim(' ', '\t', '\n', '\r', '“', '”', '"', '\'', '`', '，', ',', '。', '.', '；', ';', '：', ':')
            .removePrefix("文本")
            .removePrefix("内容")
            .removePrefix("关键字")
            .removePrefix("关键词")
            .removePrefix("为")
            .removePrefix("成")
            .trim(' ', '\t', '\n', '\r', '“', '”', '"', '\'', '`', '，', ',', '。', '.', '；', ';', '：', ':')

    private fun normalizeSpace(value: String): String =
        value.replace(Regex("""\s+"""), " ").trim()

    private val DIRECT_CLICK_VERBS = listOf(
        "点击",
        "点一下",
        "点开",
        "打开",
        "聚焦",
        "选择",
        "进入",
        "click",
        "tap",
        "focus",
        "open",
        "select",
    )
    private val TEXT_ENTRY_VERBS = listOf(
        "输入",
        "填写",
        "键入",
        "type",
        "enter text",
        "fill",
    )
    private val INPUT_TEXT_PATTERNS = listOf(
        Regex("""(?:输入(?!框|栏|条|区域|字段)|填写|键入)\s*[“"']?(.+?)[”"']?\s*(?:$|，|,|。|；|;)"""),
        Regex("""(?:type|enter text|fill)\s+[“"']?(.+?)[”"']?\s*(?:$|,|\.|;)""", RegexOption.IGNORE_CASE),
    )
    private val SCROLL_VERBS = listOf(
        "滑动",
        "滚动",
        "上滑",
        "下滑",
        "scroll",
        "swipe",
    )
    private val SEARCH_TARGETS = listOf(
        "搜索输入框",
        "搜索框",
        "搜索栏",
        "搜索条",
        "search box",
        "search field",
        "search bar",
        "search input",
    )
    private val INPUT_TARGETS = SEARCH_TARGETS + listOf(
        "输入框",
        "文本框",
        "编辑框",
        "输入栏",
        "input field",
        "text field",
        "edit text",
        "edittext",
    )
    private val FOLLOW_UP_MARKERS = listOf(
        "并",
        "然后",
        "之后",
        "以后",
        "再",
        "接着",
        "随后",
        " and ",
        " then ",
        " after ",
    )
    private val STRIP_PHRASES = DIRECT_CLICK_VERBS + listOf(
        "请",
        "帮我",
        "当前",
        "页面",
        "里面",
        "里的",
        "的",
        "按钮",
        "控件",
    )
    private val GENERIC_PAGE_WORDS = listOf(
        "设置",
        "settings",
        "页面",
        "page",
        "app",
        "应用",
    )
    private const val MAX_TARGET_QUERIES = 10
    private const val MAX_DIAGNOSTIC_CHARS = 120
}
