package cn.com.omnimind.bot.runlog

import cn.com.omnimind.baselib.runlog.OobActionSchema
import kotlinx.coroutines.delay

// ── Checker-machinery constants ─────────────────────────────────────────────

internal const val CHECKERS_DISABLED = true

internal const val DEFAULT_PAGE_GUARD_TRIGGER_LIMIT = 3
internal const val MAX_CHECKER_PHASE_CONTROL_COUNT = 3
internal const val MIN_FIRST_RUN_PROMPT_DISMISS_SCORE = 520f
private const val DEFAULT_CHECKER_TRIGGER_LIMIT = 1
private const val DISMISS_CONTROL_RETRY_LIMIT = 1
private const val PRE_ACTION_CONTROL_DELAY_MS = 1_000L
private const val DISMISS_CONTROL_SETTLE_TIMEOUT_MS = 2_500L
private const val DISMISS_CONTROL_POLL_INTERVAL_MS = 250L

internal const val MIN_AD_DISMISS_SCORE = 760f
internal const val MIN_DISMISS_OVERLAY_SCORE = 760f
internal const val MIN_PRIVACY_NOTICE_OVERLAY_SCORE = 620f
private const val MIN_APP_UPGRADE_DISMISS_SCORE = 700f
private const val KEYBOARD_OBSCURE_MARGIN_PX = 16f
private const val ACTION_TARGET_HIT_MARGIN_PX = 24f
private const val MAX_TARGET_OVERLAY_AREA_RATIO = 0.45f
private const val SPARSE_OVERLAY_MAX_VISIBLE_NODES = 6
private const val SPARSE_OVERLAY_MAX_INTERACTIVE_NODES = 2
private const val FULLSCREEN_INTERACTIVE_AREA_RATIO = 0.65f

private val AD_OR_MODAL_TERMS = setOf(
    "advert",
    "sponsor",
    "promo",
    "promotion",
    "dialog",
    "popup",
    "modal",
    "privacy",
    "privacy policy",
    "terms",
    "terms of service",
    "consent",
    "notice",
    "广告",
    "推广",
    "赞助",
    "弹窗",
    "隐私",
    "隐私政策",
    "用户协议",
    "服务条款",
    "同意",
    "须知",
)

internal val PRIVACY_NOTICE_TERMS = setOf(
    "privacy",
    "privacy policy",
    "terms",
    "terms of service",
    "consent",
    "notice",
    "隐私",
    "隐私政策",
    "用户协议",
    "服务条款",
    "须知",
)

private val AD_LABEL_TERMS = setOf(
    "advert",
    "sponsored",
    "sponsor",
    "promotion",
    "interstitial",
    "splash ad",
    "广告",
    "推广",
    "赞助",
    "开屏",
    "插屏",
)

private val AD_RESOURCE_CUE_TERMS = setOf(
    "advert",
    "splash",
    "interstitial",
    "reward",
    "rewarded",
    "ksad",
    "gdt",
    "tt_splash",
    "admob",
    "bytedance",
    "pangle",
)

private val AD_DISMISS_RESOURCE_TERMS = setOf(
    "skip_ad",
    "ad_skip",
    "close_ad",
    "ad_close",
    "btn_skip",
    "skip_btn",
    "splash_skip",
    "tt_splash_skip",
    "ksad_skip",
    "gdt_skip",
)

private val AD_SKIP_EXACT_LABELS = setOf(
    "skip",
    "跳过",
)

private val AD_CLOSE_EXACT_LABELS = setOf(
    "close",
    "dismiss",
    "x",
    "×",
    "关闭",
)

private val AD_DISMISS_CONTAINS_LABELS = setOf(
    "close ad",
    "close ads",
    "skip ad",
    "skip ads",
    "dismiss ad",
    "关闭广告",
    "跳过广告",
)

private val DISMISS_EXACT_LABELS = setOf(
    "close",
    "dismiss",
    "skip",
    "x",
    "×",
    "ok",
    "got it",
    "continue",
    "agree",
    "i agree",
    "accept",
    "关闭",
    "跳过",
    "确定",
    "知道了",
    "我知道了",
    "继续",
    "同意",
    "接受",
)

private val DISMISS_CONTAINS_LABELS = setOf(
    "close ad",
    "close ads",
    "skip ad",
    "skip ads",
    "dismiss ad",
    "not now",
    "got it",
    "i agree",
    "关闭广告",
    "跳过广告",
    "关闭弹窗",
    "稍后再说",
    "以后再说",
    "我知道了",
)

private val DISMISS_RESOURCE_TAILS = setOf(
    "close",
    "close_button",
    "btn_close",
    "iv_close",
    "dismiss",
    "skip",
    "skip_ad",
    "ad_close",
    "close_ad",
)

internal val FIRST_RUN_PROMPT_CUE_TERMS = setOf(
    "welcome",
    "get started",
    "sign in",
    "account",
    "google account",
    "back up",
    "backup",
    "sync",
    "organize your",
    "personalize",
    "set up",
    "setup",
    "first run",
    "remember photo locations",
    "tag your photos",
    "登录",
    "账号",
    "帐号",
    "账户",
    "备份",
    "同步",
    "开始使用",
    "个性化",
    "设置",
)

internal val FIRST_RUN_PROMPT_DISMISS_EXACT_LABELS = setOf(
    "skip",
    "not now",
    "no thanks",
    "no, thanks",
    "maybe later",
    "later",
    "cancel",
    "跳过",
    "稍后再说",
    "以后再说",
    "下次再说",
    "暂不",
    "不用了",
    "取消",
)

internal val FIRST_RUN_PROMPT_DISMISS_CONTAINS_LABELS = setOf(
    "continue without",
    "use without",
    "skip sign",
    "skip setup",
    "not now",
    "no thanks",
    "maybe later",
    "稍后再说",
    "以后再说",
    "跳过登录",
    "跳过设置",
)

internal val FIRST_RUN_PROMPT_SAFE_ADVANCE_EXACT_LABELS = setOf(
    "next",
    "continue",
    "get started",
    "start",
    "done",
    "finish",
    "save",
    "apply",
    "下一步",
    "继续",
    "开始",
    "完成",
    "保存",
    "应用",
)

internal val FIRST_RUN_PROMPT_AUTH_ADVANCE_LABELS = setOf(
    "sign in",
    "login",
    "log in",
    "continue with google",
    "continue with account",
    "google account",
    "account",
    "登录",
    "账号",
    "帐号",
    "账户",
)

internal val FIRST_RUN_PROMPT_AFFIRMATIVE_LABELS = setOf(
    "sign in",
    "login",
    "log in",
    "continue",
    "get started",
    "start",
    "next",
    "allow",
    "enable",
    "turn on",
    "登录",
    "继续",
    "开始",
    "下一步",
    "允许",
    "启用",
    "开启",
)

private val APP_UPGRADE_CUE_TERMS = setOf(
    "app update",
    "app upgrade",
    "new version",
    "new_version",
    "update available",
    "upgrade available",
    "version update",
    "version upgrade",
    "upgrade",
    "更新提示",
    "版本更新",
    "版本升级",
    "检测到新版本",
    "发现新版本",
    "新版本",
    "新版",
    "升级",
    "更新",
)

private val APP_UPGRADE_DISMISS_EXACT_LABELS = setOf(
    "not now",
    "later",
    "maybe later",
    "skip",
    "cancel",
    "close",
    "dismiss",
    "x",
    "×",
    "稍后再说",
    "以后再说",
    "下次再说",
    "暂不升级",
    "暂不更新",
    "暂不",
    "稍后",
    "以后",
    "取消",
    "忽略",
    "跳过",
    "关闭",
)

private val APP_UPGRADE_DISMISS_CONTAINS_LABELS = setOf(
    "not now",
    "maybe later",
    "remind me later",
    "skip update",
    "skip upgrade",
    "cancel update",
    "cancel upgrade",
    "稍后再说",
    "以后再说",
    "下次再说",
    "暂不升级",
    "暂不更新",
    "取消升级",
    "取消更新",
    "跳过升级",
    "跳过更新",
)

private val APP_UPGRADE_DISMISS_RESOURCE_TAILS = setOf(
    "cancel",
    "btn_cancel",
    "button_cancel",
    "later",
    "btn_later",
    "not_now",
    "btn_not_now",
    "skip",
    "btn_skip",
    "close",
    "close_button",
    "btn_close",
    "iv_close",
)

private val APP_UPGRADE_AFFIRMATIVE_LABELS = setOf(
    "update",
    "upgrade",
    "update now",
    "upgrade now",
    "install",
    "download",
    "立即更新",
    "立即升级",
    "马上更新",
    "马上升级",
    "去更新",
    "去升级",
    "下载安装",
    "安装",
    "下载",
    "更新",
    "升级",
)

private val FULLSCREEN_AD_SURFACE_TERMS = setOf(
    "webview",
    "image",
    "frame",
    "layout",
    "splash",
    "interstitial",
    "ad",
)

private val AD_RESOURCE_TOKEN_REGEX = Regex("""(^|[/:_.-])ads?($|[/:_.-])""")
private val SKIP_COUNTDOWN_REGEX = Regex("""(跳过|skip)\s*\d+\s*(s|sec|秒)?""", RegexOption.IGNORE_CASE)

private val KEYBOARD_TERMS = setOf(
    "keyboard",
    "inputmethod",
    "input_method",
    "latin",
    "gboard",
    "softinput",
    "软键盘",
    "键盘",
)

private val RESOLVER_PACKAGES = setOf(
    "android",
    "com.android.intentresolver",
    "com.google.android.intentresolver",
    "com.vivo.appfilter",
)

private val RESOLVER_PACKAGE_TERMS = setOf(
    "resolver",
    "chooser",
    "intentresolver",
    "appfilter",
)

private val RESOLVER_TITLE_CONTAINS_LABELS = setOf(
    "打开方式",
    "选择应用",
    "选择要使用的应用",
    "使用以下方式打开",
    "默认打开",
    "想要打开",
    "open with",
    "complete action using",
    "choose an app",
    "choose app",
)

private val RESOLVER_ALWAYS_EXACT_LABELS = setOf(
    "始终打开",
    "始终",
    "always",
    "always open",
    "open always",
)

private val RESOLVER_ALWAYS_CONTAINS_LABELS = setOf(
    "始终打开",
    "always open",
    "open always",
)

private val RESOLVER_ONCE_LABELS = setOf(
    "仅此一次",
    "仅限一次",
    "只此一次",
    "仅打开一次",
    "just once",
    "only once",
    "once",
)

private val RESOLVER_ONCE_RESOURCE_TAILS = setOf(
    "once",
    "button_once",
    "once_button",
    "resolver_once",
    "button_once_open",
)

private val RESOLVER_ALWAYS_RESOURCE_TAILS = setOf(
    "always",
    "button_always",
    "always_button",
    "resolver_always",
    "button_always_open",
    "always_open",
)

private val RESOLVER_APP_CHOICE_RESOURCE_TAILS = setOf(
    "text1",
    "text2",
    "title",
    "app_name",
    "resolver_list",
    "profile_button",
)

private val RESOLVER_NON_CHOICE_RESOURCE_TAILS = setOf(
    "button_bar",
    "button_once",
    "button_always",
    "always_button",
    "once_button",
    "resolver_button_bar",
)

private val RESOLVER_APP_CHOICE_CLASS_SUFFIXES = setOf(
    "textview",
    "linearlayout",
    "relativelayout",
    "framelayout",
    "recyclerview",
)

private val PERMISSION_PACKAGES = setOf(
    "com.android.permissioncontroller",
    "com.google.android.permissioncontroller",
    "com.android.packageinstaller",
)

private val PERMISSION_RESOURCE_PACKAGE_TERMS = setOf(
    ".permissioncontroller:id/",
    ".packageinstaller:id/",
)

// ── Small top-level extensions (no UIStepExecutor receiver needed) ──────────

internal fun OmniflowCheckerRule.budgetKey(): String =
    listOf(phase, id, condition, action).joinToString("|")

internal fun Map<String, Any?>.withCheckerTrigger(
    trigger: UIStepExecutor.CheckerTriggerRecord,
): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
    putAll(this@withCheckerTrigger)
    put("trigger_count", trigger.count)
    put("trigger_limit", trigger.limit)
    put("trigger_remaining", trigger.remaining)
}

internal fun Int?.orZero(): Int = this ?: 0

internal fun checkerTriggerLimit(rule: OmniflowCheckerRule): Int =
    intArg(
        rule.params["max_triggers"],
        rule.params["maxTriggers"],
        rule.params["trigger_limit"],
        rule.params["triggerLimit"],
        rule.params["max_count"],
        rule.params["maxCount"],
        defaultValue = DEFAULT_CHECKER_TRIGGER_LIMIT,
    ).coerceAtLeast(0)

// ── UIStepExecutor extension functions (checker machinery) ─────────────────

internal suspend fun UIStepExecutor.evaluateAndExecuteRule(
    rule: OmniflowCheckerRule,
    state: UIStepExecutor.ReplayState,
    replayAction: UIStepExecutor.ReplayAction,
): Map<String, Any?>? = when (rule.condition) {
    OmniflowCheckerRule.COND_RESOLVER_DIALOG ->
        checkerResolverDialog(rule, state, replayAction)
    OmniflowCheckerRule.COND_PERMISSION_DIALOG ->
        checkerPermissionDialog(rule, state, replayAction)
    OmniflowCheckerRule.COND_PACKAGE_MISMATCH ->
        checkerPackageMismatch(rule, state, replayAction)
    OmniflowCheckerRule.COND_AD_BLOCKING ->
        checkerAdBlocking(rule, state, replayAction)
    OmniflowCheckerRule.COND_APP_UPGRADE_PROMPT ->
        checkerAppUpgradePrompt(rule, state, replayAction)
    OmniflowCheckerRule.COND_OVERLAY_BLOCKING ->
        checkerOverlayBlocking(rule, state, replayAction)
    OmniflowCheckerRule.COND_KEYBOARD_OBSCURING ->
        checkerKeyboardObscuring(rule, state, replayAction)
    else -> null
}

internal suspend fun UIStepExecutor.checkerResolverDialog(
    rule: OmniflowCheckerRule,
    state: UIStepExecutor.ReplayState,
    replayAction: UIStepExecutor.ReplayAction,
): Map<String, Any?>? {
    if (targetLooksLikeResolverConfirm(replayAction.args)) return null
    val page = state.page ?: return null
    if (!looksLikeResolverDialog(page)) return null
    if (recordedStepLooksLikeResolverDialog(replayAction.step)) return null

    val immediateAlways = resolverAlwaysCandidate(page, requireEnabled = true)
    if (immediateAlways != null) {
        return clickResolverAlways(rule, immediateAlways, selectedApp = null)
    }

    val appChoice = resolverAppChoiceCandidate(page) ?: return null
    OmniflowActionRuntime.backend.click(appChoice.centerX, appChoice.centerY)
    delay(PRE_ACTION_CONTROL_DELAY_MS)

    val refreshedPage = parsePageModel(readBackendSnapshot().xml)
        ?.takeIf(::looksLikeResolverDialog)
    val refreshedAlways = refreshedPage?.let {
        resolverAlwaysCandidate(it, requireEnabled = true)
    }
    if (refreshedAlways != null) {
        return clickResolverAlways(rule, refreshedAlways, selectedApp = appChoice)
    }

    return linkedMapOf(
        "phase" to rule.phase,
        "effect" to "run_actions",
        "controller" to rule.id,
        "condition" to OmniflowCheckerRule.COND_RESOLVER_DIALOG,
        "action" to OmniflowCheckerRule.ACTION_SELECT_RESOLVER_APP,
        "pending_action" to OmniflowCheckerRule.ACTION_CONFIRM_RESOLVER_ALWAYS,
        "selected_app_text" to nodeLabelText(appChoice),
        "x" to appChoice.centerX,
        "y" to appChoice.centerY,
        "target_element" to summarizeNode(appChoice),
    )
}

internal suspend fun UIStepExecutor.clickResolverAlways(
    rule: OmniflowCheckerRule,
    candidate: UIStepExecutor.UiNode,
    selectedApp: UIStepExecutor.UiNode?,
): Map<String, Any?> {
    OmniflowActionRuntime.backend.click(candidate.centerX, candidate.centerY)
    delay(PRE_ACTION_CONTROL_DELAY_MS)
    return linkedMapOf(
        "phase" to rule.phase,
        "effect" to "run_actions",
        "controller" to rule.id,
        "condition" to OmniflowCheckerRule.COND_RESOLVER_DIALOG,
        "action" to OmniflowCheckerRule.ACTION_CONFIRM_RESOLVER_ALWAYS,
        "button_text" to nodeLabelText(candidate),
        "x" to candidate.centerX,
        "y" to candidate.centerY,
        "target_element" to summarizeNode(candidate),
    ).apply {
        selectedApp?.let {
            put("preselected_app_text", nodeLabelText(it))
            put("preselected_app_element", summarizeNode(it))
        }
    }
}

internal suspend fun UIStepExecutor.checkerPermissionDialog(
    rule: OmniflowCheckerRule,
    state: UIStepExecutor.ReplayState,
    replayAction: UIStepExecutor.ReplayAction,
): Map<String, Any?>? {
    val page = state.page ?: return null
    if (!looksLikePermissionDialog(page)) return null
    if (recordedActionTargetsPermissionDialog(replayAction)) return null
    val candidate = permissionAllowCandidate(page) ?: return null
    OmniflowActionRuntime.backend.click(candidate.centerX, candidate.centerY)
    delay(PRE_ACTION_CONTROL_DELAY_MS)
    return linkedMapOf(
        "phase" to "before_action",
        "effect" to "run_actions",
        "controller" to rule.id,
        "action" to OmniflowCheckerRule.ACTION_ALLOW,
        "button_text" to permissionNodeLabelText(candidate),
        "x" to candidate.centerX,
        "y" to candidate.centerY,
    )
}

internal fun UIStepExecutor.looksLikePermissionDialog(page: UIStepExecutor.PageModel): Boolean =
    page.nodes.any(::isPermissionControllerNode)

internal fun UIStepExecutor.permissionAllowCandidate(page: UIStepExecutor.PageModel): UIStepExecutor.UiNode? {
    if (!looksLikePermissionDialog(page)) return null
    return page.nodes
        .asSequence()
        .filter { it.visible && it.enabled && it.clickable }
        .mapNotNull { node ->
            val score = allowButtonScore(node)
            if (score > 0f) node to score else null
        }
        .maxByOrNull { it.second }
        ?.first
}

internal fun UIStepExecutor.isPermissionControllerNode(node: UIStepExecutor.UiNode): Boolean {
    val packageName = node.packageName.lowercase()
    val resourceId = node.resourceId.lowercase()
    return PERMISSION_PACKAGES.any { prefix ->
        packageName.startsWith(prefix) || resourceId.startsWith("$prefix:")
    } || PERMISSION_RESOURCE_PACKAGE_TERMS.any { term ->
        resourceId.contains(term)
    }
}

internal fun UIStepExecutor.resolverAlwaysCandidate(
    page: UIStepExecutor.PageModel,
    requireEnabled: Boolean,
): UIStepExecutor.UiNode? {
    return page.nodes
        .asSequence()
        .filter { it.visible && (!requireEnabled || it.enabled) && it.area > 1f }
        .mapNotNull { node ->
            val score = resolverAlwaysButtonScore(node)
            if (score > 0f) node to score else null
        }
        .maxByOrNull { it.second }
        ?.first
}

internal fun UIStepExecutor.resolverAppChoiceCandidate(page: UIStepExecutor.PageModel): UIStepExecutor.UiNode? {
    return page.nodes
        .asSequence()
        .mapNotNull { node ->
            val score = resolverAppChoiceScore(node, page)
            if (score > 0f) node to score else null
        }
        .maxWithOrNull(
            compareBy<Pair<UIStepExecutor.UiNode, Float>> { it.second }
                .thenByDescending { -it.first.bounds.top }
        )
        ?.first
}

internal fun UIStepExecutor.looksLikeResolverDialog(page: UIStepExecutor.PageModel): Boolean {
    val hasResolverPackage = page.nodes.any { node ->
        RESOLVER_PACKAGES.any { prefix -> node.packageName.startsWith(prefix) } ||
            RESOLVER_PACKAGE_TERMS.any { term ->
                node.packageName.contains(term) || node.resourceId.contains(term)
            }
    }
    val hasResolverTitle = page.nodes.any { node ->
        val label = nodeLabelText(node).lowercase()
        RESOLVER_TITLE_CONTAINS_LABELS.any { label.contains(it) }
    }
    val hasOnceButton = page.nodes.any { node ->
        val label = nodeLabelText(node).lowercase()
        RESOLVER_ONCE_LABELS.any { label == it || label.contains(it) }
    }
    val hasAlwaysButton = page.nodes.any { resolverAlwaysButtonScore(it) > 0f }
    return hasAlwaysButton && (hasResolverPackage || hasResolverTitle || hasOnceButton)
}

internal fun UIStepExecutor.resolverAlwaysButtonScore(node: UIStepExecutor.UiNode): Float {
    val label = nodeLabelText(node).lowercase()
    val resource = node.resourceTail.lowercase()
    val exactLabel = RESOLVER_ALWAYS_EXACT_LABELS.any { label == it }
    val containsLabel = RESOLVER_ALWAYS_CONTAINS_LABELS.any { label.contains(it) }
    val resourceMatch = RESOLVER_ALWAYS_RESOURCE_TAILS.any { resource == it || resource.contains(it) }
    if (!exactLabel && !containsLabel && !resourceMatch) return 0f

    var score = 0f
    if (exactLabel) score += 520f
    if (containsLabel) score += 360f
    if (resourceMatch) score += 280f
    if (node.clickable) score += 90f
    if (node.classSuffix == "button") score += 70f
    return score
}

internal fun UIStepExecutor.resolverOnceButtonScore(node: UIStepExecutor.UiNode): Float {
    val label = nodeLabelText(node).lowercase()
    val resource = node.resourceTail.lowercase()
    val labelMatch = RESOLVER_ONCE_LABELS.any { label == it || label.contains(it) }
    val resourceMatch = RESOLVER_ONCE_RESOURCE_TAILS.any {
        resource == it || resource.contains(it)
    }
    if (!labelMatch && !resourceMatch) return 0f
    var score = 0f
    if (labelMatch) score += 260f
    if (resourceMatch) score += 180f
    if (node.clickable) score += 70f
    if (node.classSuffix == "button") score += 50f
    return score
}

internal fun UIStepExecutor.resolverAppChoiceScore(node: UIStepExecutor.UiNode, page: UIStepExecutor.PageModel): Float {
    if (!node.visible || !node.enabled || node.area <= 1f || !node.interactive) return 0f
    if (resolverAlwaysButtonScore(node) > 0f || resolverOnceButtonScore(node) > 0f) return 0f

    val label = nodeLabelText(node).lowercase()
    val resource = node.resourceTail.lowercase()
    if (label.isNotBlank() && RESOLVER_TITLE_CONTAINS_LABELS.any { label.contains(it) }) return 0f
    if (RESOLVER_NON_CHOICE_RESOURCE_TAILS.any { resource == it || resource.contains(it) }) return 0f

    val rootArea = page.rootBounds.area.coerceAtLeast(1f)
    val relativeArea = node.area / rootArea
    if (relativeArea > 0.45f) return 0f

    var score = 0f
    if (node.clickable) score += 260f
    if (node.focusable) score += 120f
    if (label.isNotBlank()) score += 90f
    if (RESOLVER_APP_CHOICE_RESOURCE_TAILS.any { resource == it || resource.contains(it) }) {
        score += 140f
    }
    if (node.classSuffix in RESOLVER_APP_CHOICE_CLASS_SUFFIXES) score += 80f
    if (node.bounds.centerY < page.rootBounds.bottom - page.rootBounds.height * 0.18f) {
        score += 60f
    }
    return if (score >= 240f) score else 0f
}

internal fun UIStepExecutor.allowButtonScore(node: UIStepExecutor.UiNode): Float {
    val label = permissionNodeLabelText(node)
    val resource = node.resourceTail.lowercase()
    val resourceScore = when {
        ALLOW_RESOURCE_TAILS.any { resource == it } -> 400f
        else -> 0f
    }
    val labelScore = when {
        ALLOW_EXACT_LABELS.any { label == it } -> 300f
        ALLOW_CONTAINS_LABELS.any { label.contains(it) } -> 150f
        else -> 0f
    }
    val oncePenalty = if (ALLOW_ONCE_LABELS.any { label.contains(it) }) -100f else 0f
    return resourceScore + labelScore + oncePenalty
}

internal suspend fun UIStepExecutor.checkerPackageMismatch(
    rule: OmniflowCheckerRule,
    state: UIStepExecutor.ReplayState,
    replayAction: UIStepExecutor.ReplayAction,
): Map<String, Any?>? {
    if (targetLooksLikeDismiss(replayAction.args)) return null
    if (recordedActionTargetsPermissionDialog(replayAction)) return null
    val expectedPkg = rule.params["package_name"]?.toString()?.trim()
        ?: stepSourcePackage(replayAction.step)
    if (expectedPkg.isBlank()) return null
    val currentPkg = state.snapshot.effectivePackage()
    if (packageMatchMode(expectedPkg, currentPkg) != null) return null
    runCatching {
        OmniflowActionRuntime.backend.launchApplication(expectedPkg, resetTask = false)
    }
    delay(PRE_ACTION_CONTROL_DELAY_MS)
    return linkedMapOf(
        "phase" to "before_action",
        "effect" to "run_actions",
        "controller" to rule.id,
        "action" to OmniflowCheckerRule.ACTION_OPEN_APP,
        "expected_package" to expectedPkg,
        "current_package" to currentPkg,
    )
}

internal suspend fun UIStepExecutor.checkerAdBlocking(
    rule: OmniflowCheckerRule,
    state: UIStepExecutor.ReplayState,
    replayAction: UIStepExecutor.ReplayAction,
): Map<String, Any?>? {
    if (targetLooksLikeDismiss(replayAction.args)) return null
    val page = state.page ?: return null
    val candidate = adBlockingDismissCandidate(page) ?: return null
    if (actionTargetHitsNode(replayAction.action, replayAction.args, candidate)) return null
    OmniflowActionRuntime.backend.click(candidate.centerX, candidate.centerY)
    delay(PRE_ACTION_CONTROL_DELAY_MS)
    return linkedMapOf(
        "phase" to "before_action",
        "effect" to "run_actions",
        "controller" to rule.id,
        "condition" to OmniflowCheckerRule.COND_AD_BLOCKING,
        "action" to OmniflowCheckerRule.ACTION_DISMISS,
        "x" to candidate.centerX,
        "y" to candidate.centerY,
        "target_element" to summarizeNode(candidate),
    )
}

internal suspend fun UIStepExecutor.checkerAppUpgradePrompt(
    rule: OmniflowCheckerRule,
    state: UIStepExecutor.ReplayState,
    replayAction: UIStepExecutor.ReplayAction,
): Map<String, Any?>? {
    if (targetLooksLikeDismiss(replayAction.args)) return null
    val page = state.page ?: return null
    val candidate = appUpgradeDismissCandidate(page) ?: return null
    if (actionTargetHitsNode(replayAction.action, replayAction.args, candidate)) return null
    OmniflowActionRuntime.backend.click(candidate.centerX, candidate.centerY)
    delay(PRE_ACTION_CONTROL_DELAY_MS)
    return linkedMapOf(
        "phase" to rule.phase,
        "effect" to "run_actions",
        "controller" to rule.id,
        "condition" to OmniflowCheckerRule.COND_APP_UPGRADE_PROMPT,
        "action" to OmniflowCheckerRule.ACTION_DISMISS,
        "button_text" to nodeLabelText(candidate),
        "x" to candidate.centerX,
        "y" to candidate.centerY,
        "target_element" to summarizeNode(candidate),
    )
}

internal suspend fun UIStepExecutor.checkerOverlayBlocking(
    rule: OmniflowCheckerRule,
    state: UIStepExecutor.ReplayState,
    replayAction: UIStepExecutor.ReplayAction,
): Map<String, Any?>? {
    if (targetLooksLikeDismiss(replayAction.args)) return null
    val page = state.page ?: return null
    val candidate = blockingOverlayDismissCandidate(page)
        ?: blockingOverlayAtActionTarget(page, replayAction)
        ?: return null
    val clickMeta = clickDismissCandidateWithRetry(candidate, ::blockingOverlayDismissCandidate)
    return linkedMapOf(
        "phase" to "before_action",
        "effect" to "run_actions",
        "controller" to rule.id,
        "condition" to OmniflowCheckerRule.COND_OVERLAY_BLOCKING,
        "action" to OobActionSchema.TOOL_CLICK,
        "x" to candidate.centerX,
        "y" to candidate.centerY,
        "target_element" to summarizeNode(candidate),
    ) + clickMeta
}

internal suspend fun UIStepExecutor.clickDismissCandidateWithRetry(
    candidate: UIStepExecutor.UiNode,
    nextCandidate: (UIStepExecutor.PageModel) -> UIStepExecutor.UiNode?,
): Map<String, Any?> {
    var latestCandidate = candidate
    var retryCount = 0
    clickCheckerDismissCandidate(latestCandidate)
    var remaining = waitForDismissCandidate(nextCandidate)
    while (retryCount < DISMISS_CONTROL_RETRY_LIMIT) {
        val stillBlocking = remaining ?: return mapOf(
            "dismiss_retry_count" to retryCount,
            "dismiss_still_blocking_after_retry" to false,
        )
        retryCount += 1
        latestCandidate = stillBlocking
        clickCheckerDismissCandidate(latestCandidate)
        remaining = waitForDismissCandidate(nextCandidate)
    }
    return linkedMapOf<String, Any?>(
        "dismiss_retry_count" to retryCount,
        "dismiss_still_blocking_after_retry" to (remaining != null),
        "dismiss_remaining_candidate_text" to remaining?.let(::nodeDisplayLabel)?.takeIf { it.isNotBlank() },
        "dismiss_remaining_candidate" to remaining?.let(::summarizeNode),
    ).filterValues { it != null }
}

internal suspend fun UIStepExecutor.clickCheckerDismissCandidate(candidate: UIStepExecutor.UiNode) {
    OmniflowActionRuntime.backend.click(
        x = candidate.centerX,
        y = candidate.centerY,
        targetDescription = nodeDisplayLabel(candidate),
        nodeResourceId = checkerSafeClickNodeResourceId(candidate.resourceId),
    )
}

internal suspend fun UIStepExecutor.waitForDismissCandidate(
    nextCandidate: (UIStepExecutor.PageModel) -> UIStepExecutor.UiNode?,
): UIStepExecutor.UiNode? {
    val deadline = System.currentTimeMillis() + DISMISS_CONTROL_SETTLE_TIMEOUT_MS
    var latest: UIStepExecutor.UiNode? = null
    do {
        val page = parsePageModel(readBackendSnapshot().xml)
        val remaining = page?.let(nextCandidate)
        latest = remaining
        if (remaining == null) return null
        delay(DISMISS_CONTROL_POLL_INTERVAL_MS)
    } while (System.currentTimeMillis() < deadline)
    return latest
}

internal suspend fun UIStepExecutor.checkerKeyboardObscuring(
    rule: OmniflowCheckerRule,
    state: UIStepExecutor.ReplayState,
    replayAction: UIStepExecutor.ReplayAction,
): Map<String, Any?>? {
    val action = replayAction.action
    if (action !in OobActionSchema.pointTargetToolNames + OobActionSchema.TOOL_SWIPE) return null
    val page = state.page ?: return null
    val kbTop = keyboardTop(page) ?: return null
    if (!actionTargetIntersectsKeyboard(action, replayAction.args, kbTop)) return null
    OmniflowActionRuntime.backend.hideKeyboard()
    delay(PRE_ACTION_CONTROL_DELAY_MS)
    return linkedMapOf(
        "phase" to "before_action",
        "effect" to "run_actions",
        "controller" to rule.id,
        "action" to OmniflowCheckerRule.ACTION_HIDE_KEYBOARD,
        "keyboard_top" to kbTop,
    )
}

internal fun UIStepExecutor.blockingOverlayAtActionTarget(
    page: UIStepExecutor.PageModel,
    replayAction: UIStepExecutor.ReplayAction,
): UIStepExecutor.UiNode? {
    if (replayAction.action !in OobActionSchema.pointTargetToolNames) return null
    if (pageHasReplayActionTarget(page, replayAction)) return null
    if (!looksLikeSparseOverlayPage(page)) return null
    val rootArea = page.rootBounds.area.coerceAtLeast(1f)
    return page.nodes
        .asSequence()
        .filter { node ->
            node.visible &&
                node.enabled &&
                node.interactive &&
                node.area > 1f &&
                node.area / rootArea <= MAX_TARGET_OVERLAY_AREA_RATIO &&
                nodeLabelWithSubtreeText(node).isNotBlank() &&
                !recordedSourceTargetLooksLikeNode(replayAction, node) &&
                actionTargetHitsNode(replayAction.action, replayAction.args, node)
        }
        .minByOrNull { it.area }
}

internal fun UIStepExecutor.recordedSourceTargetLooksLikeNode(
    replayAction: UIStepExecutor.ReplayAction,
    candidate: UIStepExecutor.UiNode,
): Boolean {
    val x = numberArg(replayAction.args, "x")?.toFloat() ?: return false
    val y = numberArg(replayAction.args, "y")?.toFloat() ?: return false
    val sourceXml = sourceXmlForStep(replayAction.step)
    if (sourceXml.isBlank()) return false
    val sourcePage = parsePageModel(sourceXml) ?: return false
    val sourceNode = selectPointSourceNode(sourcePage, x, y) ?: return false
    if (sourceNode.resourceId.isNotBlank() && sourceNode.resourceId == candidate.resourceId) {
        return true
    }
    if (sourceNode.text.isNotBlank() && sourceNode.text == candidate.text) {
        return true
    }
    if (sourceNode.contentDesc.isNotBlank() && sourceNode.contentDesc == candidate.contentDesc) {
        return true
    }
    return sourceNode.classSuffix.isNotBlank() &&
        sourceNode.classSuffix == candidate.classSuffix &&
        nodeLabelWithSubtreeText(sourceNode).isNotBlank() &&
        nodeLabelWithSubtreeText(sourceNode) == nodeLabelWithSubtreeText(candidate)
}

internal fun UIStepExecutor.looksLikeSparseOverlayPage(page: UIStepExecutor.PageModel): Boolean {
    val visibleNodes = page.nodes.count { it.visible }
    val interactiveNodes = page.nodes.count { it.visible && it.enabled && it.interactive }
    if (visibleNodes <= SPARSE_OVERLAY_MAX_VISIBLE_NODES &&
        interactiveNodes <= SPARSE_OVERLAY_MAX_INTERACTIVE_NODES
    ) {
        return true
    }
    val rootArea = page.rootBounds.area.coerceAtLeast(1f)
    val fullScreenInteractiveNodes = page.nodes.count { node ->
        node.visible && node.enabled && node.interactive &&
            node.area / rootArea >= FULLSCREEN_INTERACTIVE_AREA_RATIO
    }
    return interactiveNodes <= 1 && fullScreenInteractiveNodes == 0
}

internal fun UIStepExecutor.pageHasReplayActionTarget(
    page: UIStepExecutor.PageModel,
    replayAction: UIStepExecutor.ReplayAction,
): Boolean {
    val targetResourceId = firstNonBlank(
        stringArg(replayAction.args, "node_resource_id", "resource_id", "resource-id"),
        stringArg(replayAction.args, "selector"),
    ).trim()
    if (targetResourceId.isNotBlank() && page.nodes.any { node ->
            node.visible && node.resourceId == targetResourceId
        }
    ) {
        return true
    }

    val targetDescription = stringArg(replayAction.args, "target_description", "label")
        ?.lowercase()
        .orEmpty()
    if (targetDescription.isBlank()) return false
    return page.nodes.any { node ->
        node.visible &&
            nodeLabelWithSubtreeText(node).let { label ->
                label == targetDescription || label.contains(targetDescription)
            }
    }
}

internal fun UIStepExecutor.appUpgradeDismissCandidate(page: UIStepExecutor.PageModel): UIStepExecutor.UiNode? {
    val hasUpgradeCue = page.nodes.any(::hasAppUpgradeCue)
    if (!hasUpgradeCue) return null
    return page.nodes
        .asSequence()
        .filter { it.visible && it.enabled && it.area > 1f && it.interactive }
        .mapNotNull { node ->
            val score = appUpgradeDismissCandidateScore(node, page.rootBounds)
            if (score >= MIN_APP_UPGRADE_DISMISS_SCORE) node to score else null
        }
        .maxByOrNull { it.second }
        ?.first
}

internal fun UIStepExecutor.adDismissCandidateScore(
    node: UIStepExecutor.UiNode,
    rootBounds: UIStepExecutor.Rect,
    hasExplicitAdCue: Boolean,
    hasFullScreenAdSurface: Boolean,
): Float {
    val label = nodeLabelText(node)
    val resource = node.resourceId.lowercase()
    val topRight = isTopRightSmallControl(node, rootBounds)
    val small = node.area / rootBounds.area.coerceAtLeast(1f) <= 0.08f
    val explicitAdDismiss = AD_DISMISS_CONTAINS_LABELS.any { label.contains(it) }
    val skipDismiss = AD_SKIP_EXACT_LABELS.any { label == it || label.startsWith("$it ") } ||
        SKIP_COUNTDOWN_REGEX.containsMatchIn(label)
    val closeDismiss = AD_CLOSE_EXACT_LABELS.any { label == it }
    val adResourceDismiss = hasAdDismissResourceCue(resource)
    val genericDismissResource = DISMISS_RESOURCE_TAILS.any {
        node.resourceTail == it || node.resourceTail.contains(it)
    }
    if (!explicitAdDismiss && !skipDismiss && !closeDismiss && !adResourceDismiss && !genericDismissResource) {
        return 0f
    }
    if (!explicitAdDismiss &&
        !hasExplicitAdCue &&
        !adResourceDismiss &&
        !(skipDismiss && (topRight || hasFullScreenAdSurface))
    ) {
        return 0f
    }

    var score = 0f
    if (explicitAdDismiss) score += 560f
    if (SKIP_COUNTDOWN_REGEX.containsMatchIn(label)) score += 520f
    if (skipDismiss) score += 380f
    if (closeDismiss) score += 260f
    if (adResourceDismiss) score += 460f
    if (genericDismissResource) score += 180f
    if (hasExplicitAdCue) score += 240f
    if (hasFullScreenAdSurface) score += 130f
    if (small) score += 130f
    if (topRight) score += 150f
    return score
}

internal fun UIStepExecutor.dismissCandidateScore(
    node: UIStepExecutor.UiNode,
    rootBounds: UIStepExecutor.Rect,
    hasOverlayCue: Boolean,
): Float {
    val label = nodeLabelText(node)
    val resource = node.resourceTail
    val hasAdCue = hasAdOrModalCue(node)
    val dismissByLabel = DISMISS_EXACT_LABELS.any { label == it } ||
        DISMISS_CONTAINS_LABELS.any { label.contains(it) }
    val dismissByResource = DISMISS_RESOURCE_TAILS.any { resource == it || resource.contains(it) }
    if (!dismissByLabel && !dismissByResource) return 0f
    if (!hasOverlayCue && !hasAdCue) return 0f

    val rootArea = rootBounds.area.coerceAtLeast(1f)
    val relativeArea = node.area / rootArea
    val smallButtonScore = if (relativeArea <= 0.08f) 160f else -220f
    val topRightScore = if (
        node.centerX >= rootBounds.left + rootBounds.width * 0.60f &&
        node.centerY <= rootBounds.top + rootBounds.height * 0.35f
    ) {
        130f
    } else {
        0f
    }
    val labelScore = when {
        DISMISS_CONTAINS_LABELS.any { label.contains(it) } -> 520f
        DISMISS_EXACT_LABELS.any { label == it } -> 520f
        else -> 0f
    }
    val resourceScore = if (dismissByResource) 360f else 0f
    val overlayScore = if (hasAdCue) 220f else 120f
    return labelScore + resourceScore + overlayScore + smallButtonScore + topRightScore
}

internal fun UIStepExecutor.appUpgradeDismissCandidateScore(node: UIStepExecutor.UiNode, rootBounds: UIStepExecutor.Rect): Float {
    val label = nodeLabelText(node)
    val resource = node.resourceTail.lowercase()
    val topRight = isTopRightSmallControl(node, rootBounds)
    val small = node.area / rootBounds.area.coerceAtLeast(1f) <= 0.08f
    val dismissByExact = APP_UPGRADE_DISMISS_EXACT_LABELS.any { label == it }
    val dismissByContains = APP_UPGRADE_DISMISS_CONTAINS_LABELS.any { label.contains(it) }
    val dismissByResource = APP_UPGRADE_DISMISS_RESOURCE_TAILS.any {
        resource == it || resource.contains(it)
    }
    val closeControl = DISMISS_EXACT_LABELS.any { label == it } && (topRight || small)
    val explicitDismiss = dismissByExact || dismissByContains || dismissByResource || closeControl
    if (!explicitDismiss) return 0f
    if (APP_UPGRADE_AFFIRMATIVE_LABELS.any { label == it || label.contains(it) } &&
        !dismissByExact &&
        !dismissByContains
    ) {
        return 0f
    }

    var score = 180f
    if (dismissByExact) score += 520f
    if (dismissByContains) score += 560f
    if (dismissByResource) score += 360f
    if (closeControl) score += 260f
    if (small) score += 80f
    if (topRight) score += 120f
    return score
}

internal fun UIStepExecutor.hasExplicitAdCue(node: UIStepExecutor.UiNode): Boolean {
    val text = nodeLabelText(node)
    val classText = node.className.lowercase()
    val resource = node.resourceId.lowercase()
    return AD_LABEL_TERMS.any { term ->
        text.contains(term) || classText.contains(term) || resource.contains(term)
    } || AD_RESOURCE_TOKEN_REGEX.containsMatchIn(resource) ||
        AD_RESOURCE_CUE_TERMS.any { resource.contains(it) }
}

internal fun UIStepExecutor.hasAppUpgradeCue(node: UIStepExecutor.UiNode): Boolean {
    val text = nodeLabelText(node)
    val classText = node.className.lowercase()
    val resource = node.resourceId.lowercase()
    return APP_UPGRADE_CUE_TERMS.any { term ->
        text.contains(term) || classText.contains(term) || resource.contains(term)
    }
}

internal fun UIStepExecutor.hasAdOrModalCue(node: UIStepExecutor.UiNode): Boolean {
    val text = nodeLabelText(node)
    val classText = node.className.lowercase()
    val resource = node.resourceId.lowercase()
    return AD_OR_MODAL_TERMS.any { term ->
        text.contains(term) || classText.contains(term) || resource.contains(term)
    }
}

internal fun UIStepExecutor.hasPrivacyNoticeCue(node: UIStepExecutor.UiNode): Boolean {
    val text = nodeLabelWithSubtreeText(node)
    val classText = node.className.lowercase()
    val resource = node.resourceId.lowercase()
    return PRIVACY_NOTICE_TERMS.any { term ->
        text.contains(term) || classText.contains(term) || resource.contains(term)
    }
}

internal fun UIStepExecutor.hasLikelyFullScreenAdSurface(page: UIStepExecutor.PageModel): Boolean {
    val rootArea = page.rootBounds.area.coerceAtLeast(1f)
    return page.nodes.any { node ->
        node.visible &&
            node.area / rootArea >= 0.72f &&
            FULLSCREEN_AD_SURFACE_TERMS.any { term ->
                node.className.contains(term, ignoreCase = true) ||
                    node.resourceId.contains(term, ignoreCase = true)
            }
    }
}

internal fun UIStepExecutor.hasAdDismissResourceCue(resource: String): Boolean =
    AD_DISMISS_RESOURCE_TERMS.any { resource.contains(it) } ||
        AD_RESOURCE_TOKEN_REGEX.containsMatchIn(resource)

internal fun UIStepExecutor.isTopRightSmallControl(node: UIStepExecutor.UiNode, rootBounds: UIStepExecutor.Rect): Boolean =
    node.centerX >= rootBounds.left + rootBounds.width * 0.58f &&
        node.centerY <= rootBounds.top + rootBounds.height * 0.26f &&
        node.area / rootBounds.area.coerceAtLeast(1f) <= 0.10f

internal fun UIStepExecutor.targetLooksLikeDismiss(args: Map<String, Any?>): Boolean {
    val target = listOf(
        stringArg(args, "target_description"),
        stringArg(args, "label"),
        stringArg(args, "selector"),
    ).filterNotNull().joinToString(" ").lowercase()
    return DISMISS_EXACT_LABELS.any { target == it } ||
        DISMISS_CONTAINS_LABELS.any { target.contains(it) } ||
        APP_UPGRADE_DISMISS_EXACT_LABELS.any { target == it } ||
        APP_UPGRADE_DISMISS_CONTAINS_LABELS.any { target.contains(it) } ||
        AD_DISMISS_CONTAINS_LABELS.any { target.contains(it) } ||
        AD_SKIP_EXACT_LABELS.any { target == it || target.startsWith("$it ") } ||
        SKIP_COUNTDOWN_REGEX.containsMatchIn(target)
}

internal fun UIStepExecutor.targetLooksLikeResolverConfirm(args: Map<String, Any?>): Boolean {
    val target = listOf(
        stringArg(args, "target_description"),
        stringArg(args, "label"),
        stringArg(args, "selector"),
    ).filterNotNull().joinToString(" ").lowercase()
    return RESOLVER_ALWAYS_EXACT_LABELS.any { target == it } ||
        RESOLVER_ALWAYS_CONTAINS_LABELS.any { target.contains(it) }
}

internal fun UIStepExecutor.recordedStepLooksLikeResolverDialog(step: Map<String, Any?>): Boolean {
    val srcCtx = (step["source_context"] as? Map<*, *>)?.get("src_ctx") as? Map<*, *>
    val sourceXml = srcCtx?.get("page")?.toString()?.trim().orEmpty()
    if (sourceXml.isBlank()) return false
    return parsePageModel(sourceXml)?.let(::looksLikeResolverDialog) == true
}

internal fun UIStepExecutor.recordedActionTargetsPermissionDialog(replayAction: UIStepExecutor.ReplayAction): Boolean {
    if (replayAction.action !in OobActionSchema.pointTargetToolNames) return false
    if (!shouldUseCoordinateHook(replayAction.step)) return false
    val sourceXml = sourceXmlForStep(replayAction.step)
    if (sourceXml.isBlank()) return false
    val sourcePage = parsePageModel(sourceXml) ?: return false
    if (!looksLikePermissionDialog(sourcePage)) return false
    val x = numberArg(replayAction.args, "x")?.toFloat() ?: return false
    val y = numberArg(replayAction.args, "y")?.toFloat() ?: return false
    val sourceNode = selectPointSourceNode(sourcePage, x, y) ?: return false
    if (!sourceNode.visible || !sourceNode.enabled || !sourceNode.interactive) return false
    return sourcePage.nodes.any { node ->
        isPermissionControllerNode(node) && node.bounds.contains(x, y)
    }
}

internal fun UIStepExecutor.sourceXmlForStep(step: Map<String, Any?>): String {
    val sourceContext = sourceContextForStep(step)
    val srcCtx = mapArg(sourceContext["src_ctx"])
    return RunLogXmlArtifacts.pageXmlFromContext(srcCtx)
        .ifBlank { RunLogXmlArtifacts.pageXmlFromContext(sourceContext) }
}

internal fun UIStepExecutor.actionTargetHitsNode(
    action: String,
    args: Map<String, Any?>,
    node: UIStepExecutor.UiNode,
): Boolean {
    if (action !in OobActionSchema.coordinateToolNames) return false
    val x = numberArg(args, "x")?.toFloat()
    val y = numberArg(args, "y")?.toFloat()
    if (x != null && y != null) {
        return node.bounds.expanded(ACTION_TARGET_HIT_MARGIN_PX).contains(x, y)
    }
    val x1 = numberArg(args, "x1")?.toFloat()
    val y1 = numberArg(args, "y1")?.toFloat()
    val x2 = numberArg(args, "x2")?.toFloat()
    val y2 = numberArg(args, "y2")?.toFloat()
    val expanded = node.bounds.expanded(ACTION_TARGET_HIT_MARGIN_PX)
    return listOfNotNull(
        x1?.let { px -> y1?.let { py -> px to py } },
        x2?.let { px -> y2?.let { py -> px to py } },
    ).any { (px, py) -> expanded.contains(px, py) }
}

internal fun UIStepExecutor.keyboardTop(page: UIStepExecutor.PageModel): Float? {
    val rootHeight = page.rootBounds.height.coerceAtLeast(1f)
    return page.nodes
        .asSequence()
        .filter { node ->
            node.visible &&
                node.bounds.bottom >= page.rootBounds.bottom - rootHeight * 0.04f &&
                node.bounds.height >= rootHeight * 0.18f &&
                nodeLabelForKeyboard(node).let { label ->
                    KEYBOARD_TERMS.any { label.contains(it) }
                }
        }
        .minOfOrNull { it.bounds.top }
}

internal fun UIStepExecutor.actionTargetIntersectsKeyboard(
    action: String,
    args: Map<String, Any?>,
    keyboardTop: Float,
): Boolean {
    val threshold = keyboardTop - KEYBOARD_OBSCURE_MARGIN_PX
    if (action == OobActionSchema.TOOL_SWIPE) {
        val y1 = numberArg(args, "y1")?.toFloat()
        val y2 = numberArg(args, "y2")?.toFloat()
        return listOfNotNull(y1, y2).any { it >= threshold }
    }
    val y = numberArg(args, "y")?.toFloat() ?: return false
    return y >= threshold
}
