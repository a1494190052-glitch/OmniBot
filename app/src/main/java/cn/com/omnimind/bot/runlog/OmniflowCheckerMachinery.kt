package cn.com.omnimind.bot.runlog

import cn.com.omnimind.assists.task.vlmserver.DeviceOperator
import cn.com.omnimind.baselib.runlog.OobActionSchema
import kotlinx.coroutines.delay

internal const val MAX_CHECKER_PHASE_CONTROL_COUNT = 3
internal const val PRE_ACTION_CONTROL_DELAY_MS = 1_000L
internal const val ACTION_TARGET_HIT_MARGIN_PX = 24f
private const val DEFAULT_CHECKER_TRIGGER_LIMIT = 1
private const val KEYBOARD_OBSCURE_MARGIN_PX = 16f

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

internal fun OmniflowCheckerRule.budgetKey(): String =
    listOf(phase, id, condition, action).joinToString("|")

internal fun Map<String, Any?>.withCheckerTrigger(
    trigger: ReplayHelper.CheckerTriggerRecord,
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

internal suspend fun ReplayHelper.evaluateAndExecuteRule(
    deviceOperator: DeviceOperator,
    rule: OmniflowCheckerRule,
    state: ReplayHelper.ReplayState,
    replayAction: ReplayHelper.ReplayAction,
): Map<String, Any?>? =
    evaluateWorkspaceRule(deviceOperator, rule, state, replayAction)

internal suspend fun ReplayHelper.checkerPackageMismatch(
    deviceOperator: DeviceOperator,
    rule: OmniflowCheckerRule,
    state: ReplayHelper.ReplayState,
    replayAction: ReplayHelper.ReplayAction,
): Map<String, Any?>? {
    val expectedPkg = rule.params["package_name"]?.toString()?.trim()
        ?: stepSourcePackage(replayAction.step)
    if (expectedPkg.isBlank()) return null
    val currentPkg = state.snapshot.effectivePackage()
    if (packageMatchMode(expectedPkg, currentPkg) != null) return null
    runCatching {
        deviceOperator.launchApplication(expectedPkg)
    }
    delay(PRE_ACTION_CONTROL_DELAY_MS)
    return linkedMapOf(
        "phase" to rule.phase,
        "effect" to "run_actions",
        "controller" to rule.id,
        "condition" to rule.condition,
        "action" to OmniflowCheckerRule.ACTION_OPEN_APP,
        "expected_package" to expectedPkg,
        "current_package" to currentPkg,
    )
}

internal suspend fun ReplayHelper.checkerKeyboardObscuring(
    deviceOperator: DeviceOperator,
    rule: OmniflowCheckerRule,
    state: ReplayHelper.ReplayState,
    replayAction: ReplayHelper.ReplayAction,
): Map<String, Any?>? {
    val action = replayAction.action
    if (action !in OobActionSchema.pointTargetToolNames + OobActionSchema.TOOL_SWIPE) return null
    val page = state.page ?: return null
    val kbTop = keyboardTop(page) ?: return null
    if (!actionTargetIntersectsKeyboard(action, replayAction.args, kbTop)) return null
    deviceOperator.hideKeyboard()
    delay(PRE_ACTION_CONTROL_DELAY_MS)
    return linkedMapOf(
        "phase" to rule.phase,
        "effect" to "run_actions",
        "controller" to rule.id,
        "condition" to rule.condition,
        "action" to OmniflowCheckerRule.ACTION_HIDE_KEYBOARD,
        "keyboard_top" to kbTop,
    )
}

internal fun ReplayHelper.sourceXmlForStep(step: Map<String, Any?>): String {
    val sourceContext = sourceContextForStep(step)
    val srcCtx = mapArg(sourceContext["src_ctx"])
    return RunLogXmlArtifacts.pageXmlFromContext(srcCtx)
        .ifBlank { RunLogXmlArtifacts.pageXmlFromContext(sourceContext) }
}

internal fun ReplayHelper.actionTargetHitsNode(
    action: String,
    args: Map<String, Any?>,
    node: ActionTransfer.UiNode,
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

internal fun ReplayHelper.keyboardTop(page: ActionTransfer.PageModel): Float? {
    val rootHeight = page.rootBounds.height.coerceAtLeast(1f)
    return page.nodes
        .asSequence()
        .filter { node ->
            node.visible &&
                node.bounds.bottom >= page.rootBounds.bottom - rootHeight * 0.04f &&
                node.bounds.height >= rootHeight * 0.18f &&
                ActionTransfer.nodeLabelForKeyboard(node).let { label ->
                    KEYBOARD_TERMS.any { label.contains(it) }
                }
        }
        .minOfOrNull { it.bounds.top }
}

internal fun ReplayHelper.actionTargetIntersectsKeyboard(
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
