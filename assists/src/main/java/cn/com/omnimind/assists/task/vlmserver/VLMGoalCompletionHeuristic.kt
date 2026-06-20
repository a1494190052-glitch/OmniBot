package cn.com.omnimind.assists.task.vlmserver

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

object VLMGoalCompletionHeuristic {
    data class Match(
        val target: String,
        val keyword: String,
    )

    fun match(goal: String, step: UIStep): Match? {
        if (step.result?.startsWith("执行失败") == true) return null
        directInputTargetCompletion(goal, step)?.let { return it }
        val targets = completionTargets(goal)
        if (targets.isEmpty()) return null
        val pageText = normalizeForMatch(
            listOfNotNull(
                step.afterObservationXml,
                step.afterPackageName,
                step.result,
                step.summary,
            ).joinToString(" ")
        )
        if (pageText.isBlank()) return null
        targets.forEach { target ->
            keywordsForTarget(target).forEach { keyword ->
                if (keyword.isNotBlank() && pageText.contains(keyword)) {
                    return Match(target = target, keyword = keyword)
                }
            }
        }
        return null
    }

    fun matchCurrentPage(
        goal: String,
        currentXml: String?,
        currentPackageName: String?,
        trace: List<UIStep>,
    ): Match? {
        if (trace.isEmpty()) return null
        if (trace.lastOrNull()?.result?.startsWith("执行失败") == true) return null
        if (currentXml.isNullOrBlank()) return null

        currentInputTargetCompletion(goal, currentXml)?.let { return it }
        explicitCurrentPageCompletion(goal, currentXml, currentPackageName)?.let { return it }
        return openPageTargetCompletion(goal, currentXml)
    }

    fun buildCurrentPageFinishedStep(
        currentXml: String?,
        currentPackageName: String?,
        match: Match,
    ): UIStep {
        val now = System.currentTimeMillis()
        val message = "当前页面已满足完成条件：${match.target}"
        return UIStep(
            observation = "CURRENT_PAGE_GOAL_MATCHED",
            thought = message,
            action = FinishedAction(content = message),
            result = message,
            summary = message,
            observationXml = currentXml,
            packageName = currentPackageName,
            startedAtMs = now,
            finishedAtMs = now,
            pageDiagnostics = linkedMapOf(
                "vlm_goal_completion" to "matched_current_page",
                "vlm_goal_completion_target" to match.target,
                "vlm_goal_completion_keyword" to match.keyword,
            )
        )
    }

    fun buildFinishedStep(source: UIStep, match: Match): UIStep {
        val now = System.currentTimeMillis()
        val message = "当前页面已满足完成条件：${match.target}"
        return UIStep(
            observation = "GOAL_PAGE_MATCHED",
            thought = message,
            action = FinishedAction(content = message),
            result = message,
            summary = message,
            observationXml = source.afterObservationXml ?: source.observationXml,
            afterObservationXml = source.afterObservationXml,
            packageName = source.afterPackageName ?: source.packageName,
            afterPackageName = source.afterPackageName,
            startedAtMs = now,
            finishedAtMs = now,
            pageDiagnostics = source.pageDiagnostics + linkedMapOf(
                "vlm_goal_completion" to "matched",
                "vlm_goal_completion_target" to match.target,
                "vlm_goal_completion_keyword" to match.keyword,
            )
        )
    }

    private fun currentInputTargetCompletion(goal: String, currentXml: String): Match? {
        val goalText = normalizeForMatch(goal)
        if (goalText.isBlank()) return null
        if (!isDirectInputTargetGoal(goalText)) return null
        if (hasFollowUpInstruction(goalText)) return null
        val targetKind = inputTargetKind(goalText) ?: return null
        val nodes = parsePageNodes(currentXml)
        if (nodes.isEmpty()) return null
        val hasCurrentTarget = nodes.any { node ->
            if (!node.enabled || !node.visible) return@any false
            val semantic = normalizeForMatch(
                listOf(node.text, node.contentDesc, node.hintText, node.resourceId, node.className)
                    .joinToString(" ")
            )
            val editableLike = node.editable ||
                semantic.contains("edittext") ||
                semantic.contains("autocompletetextview")
            val searchLike = SEARCH_TARGET_KEYWORDS.any(semantic::contains)
            when (targetKind) {
                "搜索框" -> editableLike && (node.focused || searchLike)
                "输入框" -> editableLike && (node.focused || !searchLike)
                else -> false
            }
        }
        if (!hasCurrentTarget) return null
        return Match(target = targetKind, keyword = "current_page_${targetKind}")
    }

    private fun explicitCurrentPageCompletion(
        goal: String,
        currentXml: String,
        currentPackageName: String?,
    ): Match? {
        val targets = completionTargets(goal)
        if (targets.isEmpty()) return null
        val pageText = normalizeForMatch(
            listOf(currentXml, currentPackageName.orEmpty()).joinToString(" ")
        )
        if (pageText.isBlank()) return null
        targets.forEach { target ->
            keywordsForTarget(target).forEach { keyword ->
                if (keyword.isNotBlank() && pageText.contains(keyword)) {
                    return Match(target = target, keyword = keyword)
                }
            }
        }
        return null
    }

    private fun openPageTargetCompletion(goal: String, currentXml: String): Match? {
        val goalText = normalizeForMatch(goal)
        if (!hasOpenPageIntent(goalText)) return null
        if (hasFollowUpInstruction(goalText)) return null
        val targets = openPageTargets(goal)
        if (targets.isEmpty()) return null
        val titleTexts = pageTitleTexts(currentXml)
        if (titleTexts.isEmpty()) return null
        targets.forEach { target ->
            keywordsForTarget(target).forEach { keyword ->
                if (keyword.isBlank()) return@forEach
                val normalizedKeyword = normalizeForMatch(keyword)
                val matchedTitle = titleTexts.firstOrNull { title ->
                    title.contains(normalizedKeyword) ||
                        normalizedKeyword.length >= 4 && normalizedKeyword.contains(title)
                }
                if (matchedTitle != null) {
                    return Match(target = target, keyword = matchedTitle)
                }
            }
        }
        return null
    }

    private fun directInputTargetCompletion(goal: String, step: UIStep): Match? {
        val action = step.action as? ClickAction ?: return null
        val goalText = normalizeForMatch(goal)
        val targetText = normalizeForMatch(action.targetDescription)
        if (goalText.isBlank() || targetText.isBlank()) return null
        if (!isDirectInputTargetGoal(goalText)) return null
        if (hasFollowUpInstruction(goalText)) return null
        val targetKind = inputTargetKind(goalText) ?: return null
        val actionTargetKind = inputTargetKind(targetText) ?: return null
        if (!targetKindsCompatible(targetKind, actionTargetKind)) return null
        return Match(target = targetKind, keyword = "direct_${action.name}_target")
    }

    private fun hasOpenPageIntent(goalText: String): Boolean =
        OPEN_PAGE_VERBS.any(goalText::contains)

    private fun isDirectInputTargetGoal(goalText: String): Boolean =
        DIRECT_INTERACTION_VERBS.any(goalText::contains) &&
            INPUT_TARGET_KEYWORDS.any(goalText::contains)

    private fun hasFollowUpInstruction(goalText: String): Boolean {
        val withoutInputTargets = INPUT_TARGET_KEYWORDS.fold(goalText) { current, keyword ->
            current.replace(keyword, " ")
        }
        return FOLLOW_UP_MARKERS.any(goalText::contains) ||
            FOLLOW_UP_INPUT_VERBS.any(withoutInputTargets::contains)
    }

    private fun inputTargetKind(text: String): String? {
        if (SEARCH_TARGET_KEYWORDS.any(text::contains)) return "搜索框"
        if (EDIT_TARGET_KEYWORDS.any(text::contains)) return "输入框"
        return null
    }

    private fun targetKindsCompatible(goalKind: String, actionKind: String): Boolean =
        goalKind == actionKind || goalKind == "输入框" && actionKind == "搜索框"

    internal fun completionTargets(goal: String): List<String> {
        val text = goal.trim()
        if (!hasExplicitCompletionCondition(text)) return emptyList()
        val targets = linkedSetOf<String>()
        COMPLETION_PREFIXES.forEach { prefix ->
            COMPLETION_SUFFIXES.forEach { suffix ->
                extractBetween(text, prefix, suffix)?.let { targets += it }
            }
        }
        return targets
            .map(::cleanTarget)
            .filter(::isUsefulTarget)
            .distinct()
            .take(MAX_TARGETS)
    }

    private fun hasExplicitCompletionCondition(text: String): Boolean {
        if (!text.contains("完成")) return false
        return COMPLETION_PREFIXES.any(text::contains) &&
            COMPLETION_SUFFIXES.any(text::contains)
    }

    private fun extractBetween(text: String, prefix: String, suffix: String): String? {
        val start = text.indexOf(prefix)
        if (start < 0) return null
        val bodyStart = start + prefix.length
        val end = text.indexOf(suffix, bodyStart)
        if (end <= bodyStart) return null
        return text.substring(bodyStart, end).take(MAX_RAW_TARGET_CHARS)
    }

    private fun cleanTarget(value: String): String =
        value.trim()
            .trim('，', ',', '。', '.', '；', ';', '：', ':', ' ', '\n', '\t')
            .removePrefix("当前")
            .removePrefix("已经")
            .removePrefix("已")
            .removeSuffix("相关")
            .trim()

    private fun isUsefulTarget(value: String): Boolean {
        if (value.length >= 2) return true
        return value.any { it.code > 127 }
    }

    private fun keywordsForTarget(target: String): List<String> {
        val normalized = normalizeForMatch(target)
        val values = linkedSetOf<String>()
        if (normalized.isNotBlank()) values += normalized
        if (target.contains("蓝牙", ignoreCase = true)) values += "bluetooth"
        if (target.contains("设置", ignoreCase = true)) values += "settings"
        if (
            target.contains("网络", ignoreCase = true) ||
            target.contains("network", ignoreCase = true) ||
            target.contains("无线", ignoreCase = true) ||
            target.contains("wifi", ignoreCase = true) ||
            target.contains("wi-fi", ignoreCase = true)
        ) {
            values += "network"
            values += "wifi"
            values += "wi-fi"
        }
        return values.toList()
    }

    private fun openPageTargets(goal: String): List<String> {
        val values = linkedSetOf<String>()
        val normalizedGoal = goal.trim()
        val stripped = OPEN_PAGE_STRIP_WORDS.fold(normalizedGoal) { current, word ->
            current.replace(word, " ", ignoreCase = true)
        }
        cleanTarget(stripped).takeIf(::isUsefulTarget)?.let { target ->
            values += target
            target.removeSuffix("设置").takeIf { it != target && isUsefulTarget(it) }?.let(values::add)
            target.removeSuffix("页面").takeIf { it != target && isUsefulTarget(it) }?.let(values::add)
            target.removeSuffix("页").takeIf { it != target && isUsefulTarget(it) }?.let(values::add)
            target.removeSuffix("settings").trim().takeIf { it != target && isUsefulTarget(it) }?.let(values::add)
            target.removeSuffix("page").trim().takeIf { it != target && isUsefulTarget(it) }?.let(values::add)
        }
        return values.distinct().take(MAX_TARGETS)
    }

    private fun pageTitleTexts(xml: String): List<String> {
        return parsePageNodes(xml)
            .asSequence()
            .filter { node ->
                val resource = node.resourceId.lowercase()
                resource.contains("action_bar_title") ||
                    resource.contains("toolbar_title") ||
                    resource.contains("page_title")
            }
            .map { node -> firstNonBlank(node.text, node.contentDesc, node.hintText) }
            .map(::normalizeForMatch)
            .filter(String::isNotBlank)
            .distinct()
            .take(MAX_TARGETS)
            .toList()
    }

    private fun parsePageNodes(xml: String): List<PageNode> {
        val document = runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isExpandEntityReferences = false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        }.getOrNull() ?: return emptyList()
        val nodes = document.getElementsByTagName("node")
        return buildList {
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as? Element ?: continue
                add(
                    PageNode(
                        text = element.attr("text"),
                        contentDesc = element.attr("content-desc"),
                        hintText = firstNonBlank(
                            element.attr("hintText"),
                            element.attr("hint-text"),
                            element.attr("hint"),
                        ),
                        resourceId = element.attr("resource-id"),
                        className = firstNonBlank(
                            element.attr("class"),
                            element.attr("class-name"),
                        ),
                        editable = element.boolAttr("editable"),
                        focused = element.boolAttr("focused"),
                        enabled = !element.hasAttribute("enabled") || element.boolAttr("enabled"),
                        visible = element.boolAttr("visible-to-user", defaultValue = true) &&
                            element.boolAttr("displayed", defaultValue = true),
                    )
                )
            }
        }
    }

    private fun Element.attr(name: String): String =
        if (hasAttribute(name)) getAttribute(name).trim() else ""

    private fun Element.boolAttr(name: String, defaultValue: Boolean = false): Boolean =
        if (hasAttribute(name)) attr(name).equals("true", ignoreCase = true) else defaultValue

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() }.orEmpty()

    private fun normalizeForMatch(value: String): String =
        collapseWhitespace(
            value.lowercase()
            .replace('\u2010', '-')
            .replace('\u2011', '-')
            .replace('\u2012', '-')
            .replace('\u2013', '-')
            .replace('\u2014', '-')
        ).trim()

    private fun collapseWhitespace(value: String): String {
        val builder = StringBuilder(value.length)
        var pendingSpace = false
        value.forEach { char ->
            if (char.isWhitespace()) {
                pendingSpace = builder.isNotEmpty()
            } else {
                if (pendingSpace) builder.append(' ')
                builder.append(char)
                pendingSpace = false
            }
        }
        return builder.toString()
    }

    private val COMPLETION_PREFIXES = listOf(
        "如果已经在",
        "如果已在",
        "如果当前在",
        "若已经在",
        "若已在",
        "已经在",
        "已在",
    )
    private val COMPLETION_SUFFIXES = listOf(
        "相关页面",
        "相关页",
        "页面",
    )
    private val DIRECT_INTERACTION_VERBS = listOf(
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
    private val OPEN_PAGE_VERBS = listOf(
        "打开",
        "进入",
        "前往",
        "跳转",
        "open",
        "go to",
        "enter",
        "navigate",
    )
    private val SEARCH_TARGET_KEYWORDS = listOf(
        "搜索输入框",
        "搜索框",
        "搜索栏",
        "搜索条",
        "搜索框",
        "search box",
        "search field",
        "search bar",
        "search input",
    )
    private val EDIT_TARGET_KEYWORDS = listOf(
        "输入框",
        "文本框",
        "编辑框",
        "输入栏",
        "input field",
        "text field",
        "edit text",
        "edittext",
    )
    private val INPUT_TARGET_KEYWORDS = SEARCH_TARGET_KEYWORDS + EDIT_TARGET_KEYWORDS
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
    private val FOLLOW_UP_INPUT_VERBS = listOf(
        "输入",
        "填写",
        "键入",
        "搜索",
        "查找",
        "type",
        "enter",
        "fill",
        "search",
    )
    private val OPEN_PAGE_STRIP_WORDS = OPEN_PAGE_VERBS + listOf(
        "请",
        "帮我",
        "当前",
        "页面",
        "页",
        "the",
        "screen",
    )
    private data class PageNode(
        val text: String,
        val contentDesc: String,
        val hintText: String,
        val resourceId: String,
        val className: String,
        val editable: Boolean,
        val focused: Boolean,
        val enabled: Boolean,
        val visible: Boolean,
    )
    private const val MAX_RAW_TARGET_CHARS = 24
    private const val MAX_TARGETS = 3
}
