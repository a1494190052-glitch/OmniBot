package cn.com.omnimind.assists.task.vlmserver

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Base64
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.util.IdentityHashMap
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Builds compact page state from live Accessibility XML.
 *
 * The VLM receives one current screenshot plus this compact Accessibility-tree
 * view by default. The page state gives stable labels, flags, and 0..1000
 * relative centers without exposing Accessibility node identities.
 */
object VLMIndexedPageContext {
    data class IndexedTarget(
        val label: String,
        val centerX: Float,
        val centerY: Float,
    )

    fun enrich(
        context: UIContext,
        currentXml: String?,
        displayWidth: Int,
        displayHeight: Int
    ): UIContext {
        val section = render(
            currentXml = currentXml,
            displayWidth = displayWidth,
            displayHeight = displayHeight
        )
        if (section.isBlank()) return context

        val pageSummary = listOf(
            context.currentPageSummary.trim(),
            section
        )
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .take(MAX_CONTEXT_CHARS)

        return context.copy(currentPageSummary = pageSummary)
    }

    fun render(
        currentXml: String?,
        displayWidth: Int,
        displayHeight: Int
    ): String {
        val snapshot = buildIndexedSnapshot(currentXml, displayWidth, displayHeight) ?: return ""
        val screen = snapshot.screen
        val candidates = snapshot.candidates
        val focusedEditable = snapshot.focusedEditable
        val formFields = snapshot.formFields
        val scrollables = snapshot.scrollables

        if (candidates.isEmpty() && scrollables.isEmpty() && focusedEditable == null && formFields.isEmpty()) {
            return ""
        }

        return buildString {
            appendLine("OOB page state (0..1000 coords):")
            candidates.forEach { node ->
                append(node.actionHint())
                    .append(" c=(").append(norm(node.bounds.centerX, screen.left, screen.width))
                    .append(",").append(norm(node.bounds.centerY, screen.top, screen.height)).append(")")
                    .append(" flags=").append(node.flags())
                    .append(" role=").append(node.semanticRole())
                    .append(" label=\"").append(node.displayLabel.take(MAX_LABEL_CHARS)).append("\"")
                    .appendLine()
            }
            if (focusedEditable != null) {
                append("Focused edit: c=(")
                    .append(norm(focusedEditable.bounds.centerX, screen.left, screen.width))
                    .append(",")
                    .append(norm(focusedEditable.bounds.centerY, screen.top, screen.height))
                    .append(") label=\"")
                    .append(focusedEditable.displayLabel.take(MAX_LABEL_CHARS))
                    .appendLine("\"")
            }
            if (formFields.isNotEmpty()) {
                appendLine("Forms:")
                formFields.forEach { node ->
                    append("c=(").append(norm(node.bounds.centerX, screen.left, screen.width))
                        .append(",").append(norm(node.bounds.centerY, screen.top, screen.height)).append(")")
                        .append(" role=").append(node.formRole())
                        .append(" ").append(node.actionHint())
                        .append(" label=\"").append(node.formLabel.take(MAX_LABEL_CHARS)).append("\"")
                    node.formValueHint.takeIf { it.isNotBlank() }?.let { valueHint ->
                        append(" value_hint=\"").append(valueHint.take(MAX_LABEL_CHARS)).append("\"")
                    }
                    appendLine()
                }
            }
            if (scrollables.isNotEmpty()) {
                appendLine("Scroll:")
                scrollables.forEach { node ->
                    val x = norm(node.bounds.centerX, screen.left, screen.width)
                    val y1 = norm(node.bounds.bottom - node.bounds.height * 0.14f, screen.top, screen.height)
                    val y2 = norm(node.bounds.top + node.bounds.height * 0.22f, screen.top, screen.height)
                    append("swipe")
                        .append(" down=(").append(x).append(",").append(y1)
                        .append(")->(").append(x).append(",").append(y2).append(")")
                        .append(" label=\"").append(node.displayLabel.take(MAX_LABEL_CHARS)).append("\"")
                        .appendLine()
                }
            }
        }.trim().take(MAX_SECTION_CHARS)
    }

    fun renderMarkedScreenshot(
        screenshotBase64: String?,
        currentXml: String?,
        displayWidth: Int,
        displayHeight: Int
    ): String? {
        if (screenshotBase64.isNullOrBlank()) return null
        val snapshot = buildIndexedSnapshot(currentXml, displayWidth, displayHeight) ?: return null
        if (snapshot.candidates.isEmpty()) return null
        val bitmap = decodeBitmap(screenshotBase64)?.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        val canvas = Canvas(bitmap)
        val widthScale = bitmap.width.toFloat() / snapshot.screen.width.coerceAtLeast(1f)
        val heightScale = bitmap.height.toFloat() / snapshot.screen.height.coerceAtLeast(1f)
        val strokeWidth = max(2f, min(bitmap.width, bitmap.height) / 260f)
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 180, 0)
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
        }

        snapshot.candidates.forEach { node ->
            val rect = node.bounds.toBitmapRect(snapshot.screen, widthScale, heightScale)
            if (rect.width() <= 1f || rect.height() <= 1f) return@forEach
            canvas.drawRect(rect, boxPaint)
        }
        return encodeJpegDataUri(bitmap)
    }

    fun uniqueElementTargetByDescription(
        currentXml: String?,
        displayWidth: Int,
        displayHeight: Int,
        targetDescription: String,
        preferEditable: Boolean = false
    ): IndexedTarget? {
        val query = normalizeMatchText(targetDescription)
        if (query.isBlank()) return null
        val snapshot = buildIndexedSnapshot(currentXml, displayWidth, displayHeight) ?: return null
        val scored = snapshot.candidates
            .mapNotNull { node ->
                val score = descriptionMatchScore(query, node, preferEditable)
                if (score >= MIN_UNIQUE_MATCH_SCORE) {
                    IndexedNodeScore(node = node, score = score)
                } else {
                    null
                }
            }
            .sortedWith(
                compareByDescending<IndexedNodeScore> { it.score }
                    .thenBy { it.node.bounds.top }
                    .thenBy { it.node.bounds.left }
            )
        val best = scored.firstOrNull() ?: return null
        val competing = scored.drop(1).firstOrNull { candidate ->
            !best.node.isSameSemanticTarget(candidate.node)
        }
        if (competing != null && best.score - competing.score < MIN_UNIQUE_MATCH_MARGIN) {
            return null
        }
        return IndexedTarget(
            label = best.node.displayLabel.take(MAX_LABEL_CHARS),
            centerX = best.node.bounds.centerX,
            centerY = best.node.bounds.centerY,
        )
    }

    private fun buildIndexedSnapshot(
        currentXml: String?,
        displayWidth: Int,
        displayHeight: Int
    ): IndexedSnapshot? {
        val page = parsePage(currentXml) ?: return null
        val screen = page.screenBounds(displayWidth, displayHeight)
        val screenArea = screen.area.coerceAtLeast(1f)

        val candidates = page.nodes
            .asSequence()
            .filter { it.visible && it.enabled }
            .filter { it.bounds.area >= MIN_ELEMENT_AREA }
            .filter { !isOverlayLabel(it.label) }
            .filter { it.actionable || it.displayLabel.isNotBlank() }
            .filter { it.bounds.area <= screenArea * MAX_ELEMENT_AREA_RATIO }
            .distinctBy { it.dedupKey() }
            .sortedWith(
                compareByDescending<PageNode> { it.vlmCandidateScore(screen) }
                    .thenBy { it.bounds.top }
                    .thenBy { it.bounds.left }
                    .thenBy { it.bounds.area }
            )
            .take(MAX_ELEMENTS)
            .toList()

        val scrollables = page.nodes
            .asSequence()
            .filter { it.visible && it.enabled && it.scrollable }
            .filter { it.bounds.area >= MIN_SCROLLABLE_AREA }
            .filter { !isOverlayLabel(it.label) }
            .distinctBy { it.dedupKey() }
            .sortedByDescending { it.bounds.area }
            .take(MAX_SCROLLABLES)
            .toList()

        val formFields = page.nodes
            .asSequence()
            .filter { it.visible && it.enabled }
            .filter { it.bounds.area >= MIN_FORM_FIELD_AREA }
            .filter { it.bounds.area <= screenArea * MAX_FORM_FIELD_AREA_RATIO }
            .filter { !isOverlayLabel(it.displayLabel) }
            .filter { it.isFormFieldLike }
            .distinctBy { it.dedupKey() }
            .sortedWith(
                compareByDescending<PageNode> { if (it.focused && it.editable) 1 else 0 }
                    .thenByDescending { if (it.editable) 1 else 0 }
                    .thenByDescending { if (it.isSelectionRowLike) 1 else 0 }
                    .thenBy { it.bounds.top }
                    .thenBy { it.bounds.left }
                    .thenBy { it.bounds.area }
            )
            .take(MAX_FORM_FIELDS)
            .toList()

        val focusedEditable = page.nodes.firstOrNull {
            it.visible && it.enabled && it.editable && it.focused && !isOverlayLabel(it.label)
        }
        return IndexedSnapshot(
            screen = screen,
            candidates = candidates,
            scrollables = scrollables,
            focusedEditable = focusedEditable,
            formFields = formFields
        )
    }

    private fun parsePage(xml: String?): PageModel? {
        if (xml.isNullOrBlank()) return null
        val document = runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isExpandEntityReferences = false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        }.getOrNull() ?: return null

        val nodeList = document.getElementsByTagName("node")
        val elements = ArrayList<Element>(nodeList.length)
        for (index in 0 until nodeList.length) {
            (nodeList.item(index) as? Element)?.let(elements::add)
        }
        val descendantSemanticText = aggregateDescendantSemanticText(elements)
        val nodes = ArrayList<PageNode>(elements.size)
        for (element in elements) {
            val bounds = parseBounds(element.attr("bounds")) ?: continue
            if (bounds.area <= 0f) continue
            nodes += PageNode(
                bounds = bounds,
                text = element.attr("text"),
                contentDesc = element.attr("content-desc"),
                hintText = firstNonBlank(element.attr("hintText"), element.attr("hint-text"), element.attr("hint")),
                resourceId = element.attr("resource-id"),
                className = firstNonBlank(element.attr("class"), element.attr("class-name")),
                packageName = element.attr("package"),
                descendantText = descendantSemanticText[element]?.render().orEmpty(),
                clickable = element.boolAttr("clickable"),
                longClickable = element.boolAttr("long-clickable"),
                focusable = element.boolAttr("focusable"),
                editable = element.boolAttr("editable"),
                focused = element.boolAttr("focused"),
                scrollable = element.boolAttr("scrollable"),
                checkable = element.boolAttr("checkable"),
                checked = element.boolAttr("checked"),
                selected = element.boolAttr("selected"),
                enabled = !element.hasAttribute("enabled") || element.boolAttr("enabled"),
                visible = element.boolAttr("visible-to-user", defaultValue = true) &&
                    element.boolAttr("displayed", defaultValue = true)
            )
        }
        return PageModel(nodes)
    }

    private fun PageModel.screenBounds(displayWidth: Int, displayHeight: Int): Rect {
        val maxRight = nodes.maxOfOrNull { it.bounds.right } ?: 1f
        val maxBottom = nodes.maxOfOrNull { it.bounds.bottom } ?: 1f
        val width = displayWidth.takeIf { it > 0 }?.toFloat() ?: maxRight
        val height = displayHeight.takeIf { it > 0 }?.toFloat() ?: maxBottom
        return Rect(0f, 0f, max(width, maxRight), max(height, maxBottom))
    }

    private fun aggregateDescendantSemanticText(
        elements: List<Element>
    ): IdentityHashMap<Element, DescendantSemanticAccumulator> {
        val accumulators = IdentityHashMap<Element, DescendantSemanticAccumulator>()
        elements.forEach { element ->
            val semanticParts = listOf(
                element.attr("text"),
                element.attr("content-desc"),
                element.attr("hintText"),
                element.attr("hint-text"),
                element.attr("hint")
            ).filter { it.isNotBlank() }
            if (semanticParts.isEmpty()) return@forEach

            var ancestor = element.parentNode
            while (ancestor != null) {
                if (ancestor is Element && ancestor.tagName == "node") {
                    accumulators
                        .getOrPut(ancestor, ::DescendantSemanticAccumulator)
                        .appendNode(semanticParts)
                }
                ancestor = ancestor.parentNode
            }
        }
        return accumulators
    }

    private fun parseBounds(raw: String): Rect? {
        val values = BOUNDS_REGEX.find(raw)?.groupValues ?: return null
        val left = values.getOrNull(1)?.toFloatOrNull() ?: return null
        val top = values.getOrNull(2)?.toFloatOrNull() ?: return null
        val right = values.getOrNull(3)?.toFloatOrNull() ?: return null
        val bottom = values.getOrNull(4)?.toFloatOrNull() ?: return null
        return Rect(
            left = min(left, right),
            top = min(top, bottom),
            right = max(left, right),
            bottom = max(top, bottom)
        )
    }

    private fun Element.attr(name: String): String =
        if (hasAttribute(name)) getAttribute(name).trim() else ""

    private fun Element.boolAttr(name: String, defaultValue: Boolean = false): Boolean =
        if (hasAttribute(name)) attr(name).equals("true", ignoreCase = true) else defaultValue

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() }.orEmpty()

    private fun descriptionMatchScore(
        normalizedQuery: String,
        node: PageNode,
        preferEditable: Boolean
    ): Int {
        val labels = listOf(
            node.displayLabel,
            node.formLabel,
            node.resourceId.substringAfterLast('/').substringAfterLast(':'),
            node.label
        )
            .map(::normalizeMatchText)
            .filter { it.isNotBlank() }
            .distinct()
        if (labels.isEmpty()) return 0

        val queryTokens = normalizedQuery.split(' ').filter { it.length >= 2 }
        if (queryTokens.isEmpty()) return 0
        var best = 0
        labels.forEach { label ->
            val labelTokens = label.split(' ').filter { it.length >= 2 }
            val tokenCoverage = queryTokens.count { token ->
                labelTokens.any { labelToken ->
                    labelToken == token || labelToken.contains(token) || token.contains(labelToken)
                }
            }
            val base = when {
                label == normalizedQuery -> 100
                label.contains(normalizedQuery) -> 92
                normalizedQuery.contains(label) && label.length >= 4 -> 88
                tokenCoverage == queryTokens.size -> 82
                else -> 0
            }
            if (base > best) best = base
        }
        if (best == 0) return 0
        var adjusted = best
        if (preferEditable) {
            adjusted += when {
                node.editable -> 10
                node.isFormFieldLike -> 6
                else -> -18
            }
        }
        if (!node.actionable) adjusted -= 6
        return adjusted.coerceIn(0, 120)
    }

    private fun normalizeMatchText(value: String): String =
        value.lowercase()
            .replace(Regex("""[^a-z0-9\u4e00-\u9fa5]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun norm(value: Float, origin: Float, size: Float): Int =
        (((value - origin) / size.coerceAtLeast(1f)) * 1000f)
            .roundToInt()
            .coerceIn(0, 1000)

    private fun isOverlayLabel(value: String): Boolean {
        val normalized = value.lowercase().replace(" ", "")
        return OVERLAY_LABELS.any { normalized.contains(it) }
    }

    private data class PageModel(val nodes: List<PageNode>)

    private data class IndexedSnapshot(
        val screen: Rect,
        val candidates: List<PageNode>,
        val scrollables: List<PageNode>,
        val focusedEditable: PageNode?,
        val formFields: List<PageNode>
    )

    private data class IndexedNodeScore(
        val node: PageNode,
        val score: Int
    )

    private class DescendantSemanticAccumulator {
        private val parts = linkedSetOf<String>()
        private var saturated = false

        fun appendNode(semanticParts: List<String>) {
            if (saturated) return
            semanticParts.forEach(parts::add)
            saturated = parts.size >= MAX_DESCENDANT_PARTS ||
                parts.joinToString(" ").length >= MAX_DESCENDANT_CHARS
        }

        fun render(): String = parts.joinToString(" ").take(MAX_DESCENDANT_CHARS)
    }

    private data class PageNode(
        val bounds: Rect,
        val text: String,
        val contentDesc: String,
        val hintText: String,
        val resourceId: String,
        val className: String,
        val packageName: String,
        val descendantText: String,
        val clickable: Boolean,
        val longClickable: Boolean,
        val focusable: Boolean,
        val editable: Boolean,
        val focused: Boolean,
        val scrollable: Boolean,
        val checkable: Boolean,
        val checked: Boolean,
        val selected: Boolean,
        val enabled: Boolean,
        val visible: Boolean
    ) {
        val actionable: Boolean
            get() = clickable || longClickable || focusable || editable || scrollable || checkable

        val role: String
            get() = className.substringAfterLast('.').ifBlank { "node" }

        val displayLabel: String
            get() = firstNonBlank(text, contentDesc, hintText, descendantText, resourceTail(), role)
                .replace(Regex("""\s+"""), " ")
                .trim()

        val formLabel: String
            get() = if (editable) {
                firstNonBlank(hintText, contentDesc, resourceTail(), text, role)
            } else {
                firstNonBlank(text, contentDesc, hintText, resourceTail(), role)
            }
                .replace(Regex("""\s+"""), " ")
                .trim()

        val formValueHint: String
            get() = if (editable) {
                firstNonBlank(text, contentDesc)
            } else {
                firstNonBlank(descendantText, contentDesc, text)
            }
                .replace(Regex("""\s+"""), " ")
                .trim()

        val isSelectionRowLike: Boolean
            get() {
                val normalized = formSemanticText()
                val spinnerLike = normalized.contains("spinner") ||
                    normalized.contains("dropdown") ||
                    normalized.contains("select")
                if (spinnerLike) return actionable
                if (editable) return false
                return actionable &&
                    descendantText.isNotBlank() &&
                    (
                        normalized.contains("label") ||
                            normalized.contains("type") ||
                            normalized.contains("category") ||
                            normalized.contains("account") ||
                            normalized.contains("country") ||
                            normalized.contains("language")
                        )
            }

        val isFormFieldLike: Boolean
            get() {
                val normalized = formSemanticText()
                return editable ||
                    normalized.contains("edittext") ||
                    normalized.contains("textfield") ||
                    normalized.contains("autocompletetextview") ||
                    normalized.contains("spinner") ||
                    isSelectionRowLike ||
                    (actionable && FORM_FIELD_TERMS.any { term -> normalized.contains(term) })
            }

        fun formRole(): String =
            when {
                isSelectionRowLike -> "selection_row"
                editable && focused -> "focused_editable"
                editable -> "editable"
                checkable -> "toggle"
                else -> "field"
            }

        val label: String
            get() = listOf(text, contentDesc, hintText, descendantText, resourceTail(), role)
                .filter { it.isNotBlank() }
                .joinToString(" ")

        fun flags(): String {
            val flags = buildList {
                if (clickable) add("click")
                if (longClickable) add("long")
                if (editable) add(if (focused) "edit_focused" else "edit")
                if (scrollable) add("swipe")
                if (checkable) add(if (checked) "checked" else "checkable")
                if (selected) add("selected")
                if (!enabled) add("disabled")
            }
            return if (flags.isEmpty()) "text" else flags.joinToString("|")
        }

        fun actionHint(): String =
            when {
                editable -> "input_text"
                checkable -> "click"
                clickable || focusable -> "click"
                longClickable -> "long_press"
                scrollable -> "swipe"
                else -> "read"
            }

        fun semanticRole(): String {
            val lower = role.lowercase()
            val semantic = listOf(text, contentDesc, hintText, resourceTail())
                .joinToString(" ")
                .lowercase()
            return when {
                editable -> "editable"
                checkable && (lower.contains("switch") || semantic.contains("switch")) -> "switch"
                checkable -> "toggle"
                lower.contains("button") || semantic.contains("button") -> "button"
                lower.contains("imagebutton") -> "button"
                lower.contains("checkbox") -> "checkbox"
                lower.contains("radiobutton") -> "radio"
                lower.contains("edittext") -> "editable"
                lower.contains("textview") && clickable -> "text_button"
                clickable || focusable -> "clickable"
                scrollable -> "scrollable"
                else -> role
            }
        }

        fun vlmCandidateScore(screen: Rect): Int {
            val screenArea = screen.area.coerceAtLeast(1f)
            val labelPresent = displayLabel.isNotBlank() && displayLabel != role
            val roleText = semanticRole()
            var score = 0
            if (editable) score += 90
            if (checkable) score += 80
            if (clickable) score += 70
            if (focusable) score += 32
            if (longClickable) score += 24
            if (scrollable) score += 12
            if (labelPresent) score += 36
            if (contentDesc.isNotBlank()) score += 18
            if (text.isNotBlank()) score += 16
            if (hintText.isNotBlank()) score += 12
            if (resourceId.isNotBlank()) score += 8
            if (roleText == "button" || roleText == "text_button") score += 18
            if (roleText == "switch" || roleText == "toggle" || roleText == "checkbox" || roleText == "radio") score += 16
            val areaRatio = bounds.area / screenArea
            if (areaRatio > 0.28f) score -= 36
            if (areaRatio > 0.50f) score -= 60
            if (bounds.top < screen.height * 0.04f) score -= 6
            return score
        }

        fun dedupKey(): String =
            listOf(
                bounds.left.roundToInt(),
                bounds.top.roundToInt(),
                bounds.right.roundToInt(),
                bounds.bottom.roundToInt(),
                displayLabel,
                role,
                packageName
            ).joinToString("|")

        fun isSameSemanticTarget(other: PageNode): Boolean {
            val ownLabel = normalizeMatchText(displayLabel)
            val otherLabel = normalizeMatchText(other.displayLabel)
            if (ownLabel.isBlank() || ownLabel != otherLabel) return false
            return bounds.contains(other.bounds) || other.bounds.contains(bounds)
        }

        private fun resourceTail(): String =
            resourceId.substringAfterLast('/').substringAfterLast(':')

        private fun formSemanticText(): String =
            listOf(text, contentDesc, hintText, descendantText, resourceTail(), role)
                .joinToString(" ")
                .lowercase()

    }

    private data class Rect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        val width: Float get() = max(0f, right - left)
        val height: Float get() = max(0f, bottom - top)
        val area: Float get() = width * height
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f

        fun contains(other: Rect): Boolean =
            left <= other.left &&
                top <= other.top &&
                right >= other.right &&
                bottom >= other.bottom

        fun toBitmapRect(screen: Rect, widthScale: Float, heightScale: Float): RectF =
            RectF(
                (left - screen.left) * widthScale,
                (top - screen.top) * heightScale,
                (right - screen.left) * widthScale,
                (bottom - screen.top) * heightScale
            )
    }

    private fun decodeBitmap(rawImage: String): Bitmap? {
        val cleanBase64 = rawImage.substringAfter(",")
        val bytes = runCatching { Base64.decode(cleanBase64, Base64.DEFAULT) }.getOrNull() ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun encodeJpegDataUri(bitmap: Bitmap): String? {
        return runCatching {
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, MARKED_SCREENSHOT_JPEG_QUALITY, output)
            "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        }.getOrNull()
    }

    private val BOUNDS_REGEX = Regex("""\[(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)\]\[(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)\]""")
    private val OVERLAY_LABELS = setOf(
        "接管",
        "继续执行",
        "已接管控制",
        "小万",
        "omnibot",
        "oob"
    )
    private const val MIN_ELEMENT_AREA = 18f
    private const val MIN_SCROLLABLE_AREA = 2_500f
    private const val MIN_FORM_FIELD_AREA = 200f
    private const val MAX_ELEMENT_AREA_RATIO = 0.72f
    private const val MAX_FORM_FIELD_AREA_RATIO = 0.60f
    private const val MAX_ELEMENTS = 8
    private const val MAX_SCROLLABLES = 1
    private const val MAX_FORM_FIELDS = 3
    private const val MAX_LABEL_CHARS = 40
    private const val MAX_DESCENDANT_PARTS = 5
    private const val MAX_DESCENDANT_CHARS = 80
    private const val MAX_SECTION_CHARS = 1_100
    private const val MAX_CONTEXT_CHARS = 1_400
    private const val MARKED_SCREENSHOT_JPEG_QUALITY = 92
    private const val MIN_UNIQUE_MATCH_SCORE = 82
    private const val MIN_UNIQUE_MATCH_MARGIN = 8
    private val FORM_FIELD_TERMS = setOf(
        "name",
        "phone",
        "mobile",
        "email",
        "mail",
        "label",
        "company",
        "title",
        "address",
        "city",
        "state",
        "zip",
        "postal",
        "note",
        "contact",
        "type",
        "category",
        "account",
        "country",
        "language",
        "birthday",
        "date",
        "time",
        "number",
        "value"
    )
}
