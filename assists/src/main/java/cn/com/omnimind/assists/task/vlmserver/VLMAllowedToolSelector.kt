package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.runlog.OobCanonicalActionSchema
import java.util.Locale

/**
 * Keeps online VLM tool injection small while preserving the canonical schema.
 *
 * The selector is intentionally conservative: common GUI actions stay visible,
 * and only uncommon tools are withheld unless the current goal clearly needs
 * them. Execution and parsing still use the canonical action schema.
 */
object VLMAllowedToolSelector {
    private val alwaysVisibleToolNames = linkedSetOf(
        OobCanonicalActionSchema.TOOL_CLICK,
        OobCanonicalActionSchema.TOOL_FINISHED,
        OobCanonicalActionSchema.TOOL_FEEDBACK,
        OobCanonicalActionSchema.TOOL_ABORT,
    )

    fun select(context: UIContext): Set<String> {
        val text = listOf(
            context.activeGoal(),
            context.overallTask,
            context.currentState,
            context.nextStepHint,
        ).joinToString(" ")
        val normalized = text.lowercase(Locale.ROOT)
        val selected = linkedSetOf<String>().apply { addAll(alwaysVisibleToolNames) }

        if (needsInputText(normalized)) {
            selected += OobCanonicalActionSchema.TOOL_INPUT_TEXT
        }
        if (needsSwipe(normalized)) {
            selected += OobCanonicalActionSchema.TOOL_SWIPE
        }
        if (needsPressKey(normalized)) {
            selected += OobCanonicalActionSchema.TOOL_PRESS_KEY
        }
        if (needsWait(normalized)) {
            selected += OobCanonicalActionSchema.TOOL_WAIT
        }
        if (needsLongPress(normalized)) {
            selected += OobCanonicalActionSchema.TOOL_LONG_PRESS
        }
        if (needsOpenApp(context, normalized)) {
            selected += OobCanonicalActionSchema.TOOL_OPEN_APP
        }
        if (needsUserQuestion(normalized) || context.priorityEvent != null) {
            selected += OobCanonicalActionSchema.TOOL_INFO
            selected += OobCanonicalActionSchema.TOOL_REQUIRE_USER_CHOICE
            selected += OobCanonicalActionSchema.TOOL_REQUIRE_USER_CONFIRMATION
        }
        return selected
    }

    private fun needsOpenApp(context: UIContext, normalizedText: String): Boolean {
        if (context.targetPackageName.isBlank()) return false
        if (
            context.currentPackageName.isBlank() ||
            !context.targetPackageName.equals(context.currentPackageName, ignoreCase = true)
        ) {
            return true
        }
        return OPEN_APP_TERMS.any { normalizedText.contains(it) }
    }

    private fun needsLongPress(normalizedText: String): Boolean =
        LONG_PRESS_TERMS.any { normalizedText.contains(it) }

    private fun needsInputText(normalizedText: String): Boolean =
        INPUT_TEXT_TERMS.any { normalizedText.contains(it) } ||
            needsSearchQueryText(normalizedText)

    private fun needsSearchQueryText(normalizedText: String): Boolean {
        if (SEARCH_FIELD_TARGET_TERMS.any { normalizedText.contains(it) }) return false
        return SEARCH_QUERY_PATTERNS.any { pattern -> pattern.containsMatchIn(normalizedText) }
    }

    private fun needsSwipe(normalizedText: String): Boolean =
        SWIPE_TERMS.any { normalizedText.contains(it) }

    private fun needsPressKey(normalizedText: String): Boolean =
        PRESS_KEY_TERMS.any { normalizedText.contains(it) }

    private fun needsWait(normalizedText: String): Boolean =
        WAIT_TERMS.any { normalizedText.contains(it) }

    private fun needsUserQuestion(normalizedText: String): Boolean =
        USER_QUESTION_TERMS.any { normalizedText.contains(it) }

    private val LONG_PRESS_TERMS = listOf(
        "长按",
        "长摁",
        "按住",
        "long press",
        "long-press",
        "hold",
    )

    private val INPUT_TEXT_TERMS = listOf(
        "输入",
        "填写",
        "键入",
        "type",
        "enter text",
        "fill",
    )

    private val SEARCH_FIELD_TARGET_TERMS = listOf(
        "搜索输入框",
        "搜索框",
        "搜索栏",
        "搜索条",
        "search box",
        "search field",
        "search bar",
        "search input",
    )

    private val SEARCH_QUERY_PATTERNS = listOf(
        Regex("""搜索\s*(?!框|栏|条|按钮|图标|入口|页面|页|结果|输入框)[\p{L}\p{N}]"""),
        Regex("""\bsearch\s+(?:for\s+)?(?!box\b|field\b|bar\b|input\b|button\b|icon\b|page\b|result\b)[a-z0-9]"""),
    )

    private val SWIPE_TERMS = listOf(
        "滑动",
        "滚动",
        "上滑",
        "下滑",
        "左滑",
        "右滑",
        "翻页",
        "scroll",
        "swipe",
    )

    private val PRESS_KEY_TERMS = listOf(
        "返回",
        "回到桌面",
        "主页",
        "home",
        "back",
        "press key",
        "按返回",
        "按主页",
        "回车",
        "enter",
    )

    private val WAIT_TERMS = listOf(
        "等待",
        "稍等",
        "加载",
        "刷新",
        "wait",
        "loading",
        "refresh",
    )

    private val OPEN_APP_TERMS = listOf(
        "打开",
        "启动",
        "进入",
        "open app",
        "launch app",
        "start app",
    )

    private val USER_QUESTION_TERMS = listOf(
        "询问用户",
        "让用户选择",
        "需要确认",
        "用户确认",
        "问我",
        "ask user",
        "user choice",
        "confirmation",
        "confirm with user",
    )
}
