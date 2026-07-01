package cn.com.omnimind.bot.runlog

import cn.com.omnimind.assists.task.vlmserver.DeviceOperator
import cn.com.omnimind.baselib.runlog.OobActionSchema
import kotlinx.coroutines.delay
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

internal suspend fun ReplayHelper.evaluateWorkspaceRule(
    deviceOperator: DeviceOperator,
    rule: ReplayCheckerRule,
    state: ReplayHelper.ReplayState,
    replayAction: ReplayHelper.ReplayAction,
): Map<String, Any?>? = when (rule.condition) {
    ReplayCheckerRule.COND_XPATH_EXISTS,
    ReplayCheckerRule.COND_TARGET_COVERED_BY_XPATH -> checkerXPathRule(deviceOperator, rule, state, replayAction)
    ReplayCheckerRule.COND_PACKAGE_MISMATCH -> checkerPackageMismatch(deviceOperator, rule, state, replayAction)
    ReplayCheckerRule.COND_KEYBOARD_OBSCURING -> checkerKeyboardObscuring(deviceOperator, rule, state, replayAction)
    else -> null
}

private suspend fun ReplayHelper.checkerXPathRule(
    deviceOperator: DeviceOperator,
    rule: ReplayCheckerRule,
    state: ReplayHelper.ReplayState,
    replayAction: ReplayHelper.ReplayAction,
): Map<String, Any?>? {
    if (state.snapshot.xml.isBlank()) return null
    val document = parseCheckerXml(state.snapshot.xml) ?: return null
    val conditionXPath = when (rule.condition) {
        ReplayCheckerRule.COND_TARGET_COVERED_BY_XPATH ->
            rule.params["target_covered_by_xpath"]?.toString()?.trim().orEmpty()
        else -> firstNonBlank(rule.params["xpath_exists"], rule.params["xpath"])
    }
    if (conditionXPath.isBlank()) return null
    val matchedNodes = checkerXPathElements(document.documentElement, conditionXPath)
    if (matchedNodes.isEmpty()) return null
    if (rule.condition == ReplayCheckerRule.COND_TARGET_COVERED_BY_XPATH &&
        matchedNodes.none { node ->
            ActionTransfer.parseBounds(node.getAttribute("bounds"))
                ?.let { actionTargetHitsBounds(replayAction.action, replayAction.args, it) } == true
        }
    ) {
        return null
    }
    return when (rule.action) {
        ReplayCheckerRule.ACTION_CLICK -> {
            val targetXPath = firstNonBlank(rule.params["target_xpath"], conditionXPath)
            val target = checkerXPathElements(document.documentElement, targetXPath)
                .firstNotNullOfOrNull { element ->
                    ActionTransfer.parseBounds(element.getAttribute("bounds"))?.let { element to it }
                }
                ?: return null
            val (targetElement, bounds) = target
            deviceOperator.clickCoordinate(bounds.centerX, bounds.centerY)
            delay(PRE_ACTION_CONTROL_DELAY_MS)
            linkedMapOf(
                "phase" to rule.phase,
                "effect" to "run_actions",
                "controller" to rule.id,
                "condition" to rule.condition,
                "action" to OobActionSchema.TOOL_CLICK,
                "x" to bounds.centerX,
                "y" to bounds.centerY,
                "target_xpath" to targetXPath,
                "target_element" to summarizeElement(targetElement, bounds),
            )
        }
        ReplayCheckerRule.ACTION_HIDE_KEYBOARD -> {
            deviceOperator.hideKeyboard()
            delay(PRE_ACTION_CONTROL_DELAY_MS)
            linkedMapOf(
                "phase" to rule.phase,
                "effect" to "run_actions",
                "controller" to rule.id,
                "condition" to rule.condition,
                "action" to ReplayCheckerRule.ACTION_HIDE_KEYBOARD,
            )
        }
        ReplayCheckerRule.ACTION_WAIT -> {
            val delayMs = longParam(rule.params["delay_ms"], 1_000L).coerceIn(0L, 10_000L)
            delay(delayMs)
            linkedMapOf(
                "phase" to rule.phase,
                "effect" to "run_actions",
                "controller" to rule.id,
                "condition" to rule.condition,
                "action" to ReplayCheckerRule.ACTION_WAIT,
                "delay_ms" to delayMs,
            )
        }
        else -> null
    }
}

private fun parseCheckerXml(xml: String): org.w3c.dom.Document? =
    runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        factory.newDocumentBuilder()
            .parse(InputSource(StringReader(xml)))
    }.getOrNull()

private fun checkerXPathElements(root: Element, expression: String): List<Element> =
    runCatching {
        val result = XPathFactory.newInstance()
            .newXPath()
            .evaluate(expression, root, XPathConstants.NODESET) as? NodeList
            ?: return emptyList()
        (0 until result.length).mapNotNull { index -> result.item(index) as? Element }
    }.getOrDefault(emptyList())

private fun ReplayHelper.actionTargetHitsBounds(
    action: String,
    args: Map<String, Any?>,
    bounds: ActionTransfer.Rect,
): Boolean {
    if (action !in OobActionSchema.coordinateToolNames) return false
    val x = numberArg(args, "x")?.toFloat()
    val y = numberArg(args, "y")?.toFloat()
    if (x != null && y != null) {
        return bounds.expanded(ACTION_TARGET_HIT_MARGIN_PX).contains(x, y)
    }
    val x1 = numberArg(args, "x1")?.toFloat()
    val y1 = numberArg(args, "y1")?.toFloat()
    val x2 = numberArg(args, "x2")?.toFloat()
    val y2 = numberArg(args, "y2")?.toFloat()
    val expanded = bounds.expanded(ACTION_TARGET_HIT_MARGIN_PX)
    return listOfNotNull(
        x1?.let { px -> y1?.let { py -> px to py } },
        x2?.let { px -> y2?.let { py -> px to py } },
    ).any { (px, py) -> expanded.contains(px, py) }
}

private fun summarizeElement(element: Element, bounds: ActionTransfer.Rect): Map<String, Any?> =
    mapOf(
        "bounds" to listOf(bounds.left, bounds.top, bounds.right, bounds.bottom),
        "class" to element.getAttribute("class").ifBlank { element.getAttribute("class-name") },
        "resource_id" to element.getAttribute("resource-id"),
        "text" to element.getAttribute("text"),
        "content_desc" to element.getAttribute("content-desc"),
        "clickable" to element.getAttribute("clickable"),
    ).filterValues { value -> value.toString().isNotBlank() }

private fun longParam(value: Any?, defaultValue: Long): Long =
    when (value) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull() ?: defaultValue
        else -> defaultValue
    }
