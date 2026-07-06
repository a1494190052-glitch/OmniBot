package cn.com.omnimind.bot.runlog

import cn.com.omnimind.baselib.runlog.OobActionSchema
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

object ActionTransfer {
    private const val MIN_ANCHOR_PROJECTION_CONFIDENCE = 0.55f
    private val LOCAL_ANCHOR_RADIUS_RATIOS = floatArrayOf(0.12f, 0.20f, 0.35f, 0.50f, 0.75f, 1.00f)
    private const val MIN_LOCAL_ANCHOR_SOURCE_COUNT = 3
    private const val MAX_LOCAL_ANCHOR_SOURCE_COUNT = 12
    private const val LOCAL_ANCHOR_DISTANCE_SIGMA = 0.25f

    data class Request(
        val action: String,
        val args: Map<String, Any?>,
        val sourceContext: Map<String, Any?>,
        val currentContext: Map<String, Any?>,
        val options: Options = Options(),
    )

    data class Options(
        val allowRootProjectionFallback: Boolean = false,
        val minConfidence: Float = MIN_ANCHOR_PROJECTION_CONFIDENCE,
    )

    data class Result(
        val args: Map<String, Any?>,
        val applied: Boolean,
        val reason: String = "",
        val diagnostics: Map<String, Any?> = emptyMap(),
    )

    data class Rect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        val width: Float get() = max(0f, right - left)
        val height: Float get() = max(0f, bottom - top)
        val area: Float get() = width * height
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f

        fun contains(x: Float, y: Float): Boolean =
            x >= left && x <= right && y >= top && y <= bottom

        fun clampX(x: Float): Float = min(max(x, left), right)

        fun clampY(y: Float): Float = min(max(y, top), bottom)

        fun expanded(margin: Float): Rect = Rect(
            left = left - margin,
            top = top - margin,
            right = right + margin,
            bottom = bottom + margin,
        )
    }

    data class UiNode(
        val index: Int,
        val bounds: Rect,
        val className: String,
        val classSuffix: String,
        val resourceId: String,
        val resourceTail: String,
        val text: String,
        val contentDesc: String,
        val hintText: String,
        val subtreeText: String,
        val packageName: String,
        val clickable: Boolean,
        val longClickable: Boolean,
        val focusable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean,
        val enabled: Boolean,
        val visible: Boolean,
        val selected: Boolean,
        val checkable: Boolean,
        val focused: Boolean,
        val isLeaf: Boolean,
        val hasSiblings: Boolean,
        val structSignature: String,
        val depth: Int = 0,
    ) {
        val centerX: Float get() = bounds.centerX
        val centerY: Float get() = bounds.centerY
        val area: Float get() = bounds.area
        val interactive: Boolean get() = clickable || focusable || editable || scrollable
    }

    data class PageModel(
        val rootBounds: Rect,
        val nodes: List<UiNode>,
    )

    private data class Coordinate(
        val xKey: String,
        val yKey: String,
        val x: Float,
        val y: Float,
    )

    private data class SourceGrounding(
        val node: UiNode,
        val coordinates: List<Coordinate>,
        val debug: Map<String, Any?> = emptyMap(),
    )

    private data class TargetMatch(
        val node: UiNode,
        val confidence: Float,
        val anchorCount: Int,
        val mode: String,
        val debug: Map<String, Any?> = emptyMap(),
    )

    private data class TargetMatchAttempt(
        val match: TargetMatch?,
        val debug: Map<String, Any?> = emptyMap(),
    )

    private data class LocalAnchorSourceSelection(
        val nodes: List<UiNode>,
        val radiusRatio: Float,
        val allCandidateCount: Int,
        val nonSelfCount: Int,
    ) {
        fun toDebugMap(): Map<String, Any?> = mapOf(
            "mode" to "local_radius",
            "radius_ratio" to radiusRatio,
            "selected_source_anchor_count" to nodes.size,
            "selected_non_self_source_anchor_count" to nonSelfCount,
            "all_source_anchor_candidate_count" to allCandidateCount,
            "min_local_source_anchor_count" to MIN_LOCAL_ANCHOR_SOURCE_COUNT,
            "local_distance_sigma" to LOCAL_ANCHOR_DISTANCE_SIGMA,
        )
    }

    private data class SemanticTargetMatch(
        val node: UiNode,
        val matchedBy: String,
        val matchedValue: String,
    )

    fun transfer(request: Request): Result {
        val action = request.action
        val args = request.args
        if (action !in OobActionSchema.coordinateToolNames) {
            return Result(args = args, applied = true)
        }
        val sourceXml = pageXmlFromContext(request.sourceContext)
        val currentXml = pageXmlFromContext(request.currentContext)
        val sourcePage = parsePageModel(sourceXml)
            ?: return rawCoordinateFallback(
                action = action,
                args = args,
                sourceResolution = "source_page_unavailable",
                diagnostics = mapOf(
                    "source_xml_present" to sourceXml.isNotBlank(),
                    "current_xml_present" to currentXml.isNotBlank(),
                ),
            )
        val targetPage = parsePageModel(currentXml)
            ?: return rawCoordinateFallback(
                action = action,
                args = args,
                sourceResolution = "current_page_unavailable",
                diagnostics = mapOf(
                    "source_xml_present" to sourceXml.isNotBlank(),
                    "current_xml_present" to currentXml.isNotBlank(),
                ),
            )
        val pageMatchMeta = sourceCurrentPageMatchMeta(sourceXml, currentXml, targetPage)
        val rawGrounding = sourceGrounding(action, args, sourcePage)
        val missingSourceReason = missingSourceReason(action)
        val grounding = rawGrounding ?: fallbackSourceGrounding(action, args, sourcePage, missingSourceReason)
        if (grounding == null) {
            val sourceFailureMeta = sourceFailureMeta(action, args, sourcePage)
            if (action != OobActionSchema.TOOL_SWIPE) {
                val semanticFallback = semanticTargetFallback(action, args, targetPage)
                if (semanticFallback != null) {
                    return semanticFallback.copy(
                        diagnostics = semanticFallback.diagnostics +
                            mapOf("source_fallback_reason" to missingSourceReason) +
                            pageMatchMeta +
                            sourceFailureMeta,
                    )
                }
            }
            val rootFallback = rootProjectionFallback(action, args, sourcePage, targetPage, missingSourceReason, request.options)
            if (rootFallback != null) {
                return rootFallback.copy(
                    diagnostics = rootFallback.diagnostics + pageMatchMeta + sourceFailureMeta,
                )
            }
            return rawCoordinateFallback(
                action = action,
                args = args,
                sourceResolution = missingSourceReason,
                diagnostics = pageMatchMeta + sourceFailureMeta,
            )
        }
        val sourceGroundingMeta = sourceGroundingMeta(grounding)
        val targetMatch = matchTargetNodeForAction(action, args, sourcePage, targetPage, grounding.node)
        val mapped = if (targetMatch != null) {
            projectGrounding(action, args, grounding, targetMatch, request.options)
        } else if (action != OobActionSchema.TOOL_SWIPE) {
            semanticTargetFallback(action, args, targetPage)
        } else {
            null
        }
        if (mapped != null) {
            return mapped.copy(
                diagnostics = mapped.diagnostics + sourceGroundingMeta + pageMatchMeta,
            )
        }
        val matchDebug = matchTargetNodeAttempt(sourcePage, targetPage, grounding.node).debug
        val elementFallback = elementSimilarityFallback(action, args, sourcePage, targetPage, grounding, matchDebug)
        if (elementFallback != null) {
            return elementFallback.copy(
                diagnostics = elementFallback.diagnostics + sourceGroundingMeta + pageMatchMeta,
            )
        }
        return rootProjectionFallback(action, args, sourcePage, targetPage, "target_match_unresolved", request.options)
            ?: rawCoordinateFallback(
                action = action,
                args = args,
                sourceResolution = "target_match_unresolved",
                diagnostics = pageMatchMeta + sourceGroundingMeta + mapOf(
                    "reason" to "target_match_unresolved",
                    "source_element" to summarizeNode(grounding.node),
                    "debug" to matchDebug,
                ),
            )
    }

    private fun sourceCurrentPageMatchMeta(
        sourceXml: String,
        currentXml: String,
        currentPage: PageModel,
    ): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "source_page_matches_current" to (sourceXml.trim() == currentXml.trim()),
            "source_xml_hash" to Integer.toHexString(sourceXml.hashCode()),
            "current_xml_hash" to Integer.toHexString(currentXml.hashCode()),
            "current_sparse_overlay_page" to looksLikeSparseOverlayPage(currentPage),
        )

    private fun pageXmlFromContext(context: Map<String, Any?>): String {
        val direct = firstNonBlank(
            context["xml"],
            context["page"],
            context["observation_xml"],
            context["observationXml"],
            context["source_xml"],
            context["sourceXml"],
            context["current_xml"],
            context["currentXml"],
        )
        if (direct.isNotBlank()) return direct
        val srcCtx = mapArg(context["src_ctx"])
        if (srcCtx.isNotEmpty()) {
            val nested = pageXmlFromContext(srcCtx)
            if (nested.isNotBlank()) return nested
        }
        val currentCtx = mapArg(context["current_ctx"])
        if (currentCtx.isNotEmpty()) {
            val nested = pageXmlFromContext(currentCtx)
            if (nested.isNotBlank()) return nested
        }
        return ""
    }

    private fun sourceGrounding(
        action: String,
        args: Map<String, Any?>,
        sourcePage: PageModel,
    ): SourceGrounding? =
        when (action) {
            OobActionSchema.TOOL_CLICK,
            OobActionSchema.TOOL_LONG_PRESS,
            OobActionSchema.TOOL_INPUT_TEXT -> pointSourceGrounding(action, args, sourcePage)
            OobActionSchema.TOOL_SWIPE -> swipeSourceGrounding(args, sourcePage)
            else -> null
        }

    private fun pointSourceGrounding(
        action: String,
        args: Map<String, Any?>,
        sourcePage: PageModel,
    ): SourceGrounding? {
        val x = optionalFloatArg(args["x"]) ?: return null
        val y = optionalFloatArg(args["y"]) ?: return null
        val resourceNode = if (action == OobActionSchema.TOOL_INPUT_TEXT) {
            bestResourceNodeForPoint(
                page = sourcePage,
                resourceId = stringArg(args, "node_resource_id", "resource_id", "resource-id").orEmpty(),
                tool = action,
                x = x,
                y = y,
                reference = null,
            )
        } else {
            null
        }
        val sourceNode = resourceNode ?: selectPointSourceNode(sourcePage, x, y) ?: return null
        if (requiresConcreteSourcePoint(action) && isUnusableSourcePointNode(sourceNode, sourcePage)) {
            return null
        }
        return SourceGrounding(
            node = sourceNode,
            coordinates = listOf(Coordinate("x", "y", x, y)),
        )
    }

    private fun swipeSourceGrounding(
        args: Map<String, Any?>,
        sourcePage: PageModel,
    ): SourceGrounding? {
        val x1 = optionalFloatArg(args["x1"]) ?: return null
        val y1 = optionalFloatArg(args["y1"]) ?: return null
        val x2 = optionalFloatArg(args["x2"]) ?: return null
        val y2 = optionalFloatArg(args["y2"]) ?: return null
        val sourceNode = selectScrollSourceNode(sourcePage, x1, y1, x2, y2) ?: return null
        return SourceGrounding(
            node = sourceNode,
            coordinates = listOf(
                Coordinate("x1", "y1", x1, y1),
                Coordinate("x2", "y2", x2, y2),
            ),
        )
    }

    private fun missingSourceReason(action: String): String =
        if (action == OobActionSchema.TOOL_SWIPE) {
            "source_scroll_unresolved"
        } else {
            "source_point_unresolved"
        }

    private fun rawCoordinateFallback(
        action: String,
        args: Map<String, Any?>,
        sourceResolution: String,
        diagnostics: Map<String, Any?> = emptyMap(),
    ): Result {
        val coordinates = coordinatesForRawFallback(action, args)
        return Result(
            args = args,
            applied = true,
            diagnostics = mapOf(
                "applied" to true,
                "tool" to action,
                "mode" to "raw_coordinate_fallback",
                "algorithm" to "raw_coordinate_replay",
                "source_resolution" to sourceResolution,
                "coordinate_replay_used" to true,
                "old" to coordinates,
                "new" to coordinates,
                "confidence" to 0f,
                "anchor_count" to 0,
            ) + diagnostics,
        )
    }

    private fun coordinatesForRawFallback(
        action: String,
        args: Map<String, Any?>,
    ): Map<String, Float> =
        when (action) {
            OobActionSchema.TOOL_SWIPE -> linkedMapOf<String, Float>().apply {
                optionalFloatArg(args["x1"])?.let { put("x1", it) }
                optionalFloatArg(args["y1"])?.let { put("y1", it) }
                optionalFloatArg(args["x2"])?.let { put("x2", it) }
                optionalFloatArg(args["y2"])?.let { put("y2", it) }
            }
            else -> linkedMapOf<String, Float>().apply {
                optionalFloatArg(args["x"])?.let { put("x", it) }
                optionalFloatArg(args["y"])?.let { put("y", it) }
            }
        }

    private fun fallbackSourceGrounding(
        action: String,
        args: Map<String, Any?>,
        sourcePage: PageModel,
        reason: String,
    ): SourceGrounding? {
        if (action == OobActionSchema.TOOL_SWIPE) return null
        val x = optionalFloatArg(args["x"]) ?: return null
        val y = optionalFloatArg(args["y"]) ?: return null
        val candidates = sourcePage.nodes
            .filter { isSourceFallbackCandidate(action, it, sourcePage.rootBounds) }
        if (candidates.isEmpty()) return null

        val targetTexts = semanticHintTexts(args)
        val rootDiagonal = hypot(sourcePage.rootBounds.width, sourcePage.rootBounds.height).coerceAtLeast(1f)
        val scored = candidates.map { node ->
            val distanceRatio = distanceToRectRatio(x, y, node.bounds, rootDiagonal)
            val semanticScore = semanticHintScore(node, targetTexts)
            val actionScore = sourceFallbackActionScore(action, node)
            val pointScore = if (node.bounds.contains(x, y)) 0.25f else 0f
            val areaPenalty = (node.area / sourcePage.rootBounds.area.coerceAtLeast(1f)).coerceIn(0f, 1f) * 0.08f
            val score = semanticScore + actionScore + pointScore +
                0.65f * (1f - distanceRatio).coerceIn(0f, 1f) -
                areaPenalty
            SourceFallbackCandidate(
                node = node,
                score = score,
                distanceRatio = distanceRatio,
                semanticScore = semanticScore,
                actionScore = actionScore,
            )
        }.sortedWith(
            compareByDescending<SourceFallbackCandidate> { it.score }
                .thenBy { it.distanceRatio }
                .thenByDescending { it.node.interactive }
                .thenBy { it.node.area }
                .thenBy { it.node.index }
        )

        val best = scored.firstOrNull() ?: return null
        val effectiveX = if (best.node.bounds.contains(x, y)) x else best.node.centerX
        val effectiveY = if (best.node.bounds.contains(x, y)) y else best.node.centerY
        return SourceGrounding(
            node = best.node,
            coordinates = listOf(Coordinate("x", "y", effectiveX, effectiveY)),
            debug = mapOf(
                "source_fallback_reason" to reason,
                "source_fallback_mode" to "nearest_source_element",
                "recorded" to mapOf("x" to x, "y" to y),
                "effective" to mapOf("x" to effectiveX, "y" to effectiveY),
                "selected_source_element" to summarizeNode(best.node),
                "candidate_count" to candidates.size,
                "target_texts" to targetTexts.take(6),
                "score" to best.score,
                "distance_ratio" to best.distanceRatio,
                "semantic_score" to best.semanticScore,
                "action_score" to best.actionScore,
                "top" to scored.take(5).map {
                    mapOf(
                        "score" to it.score,
                        "distance_ratio" to it.distanceRatio,
                        "semantic_score" to it.semanticScore,
                        "action_score" to it.actionScore,
                        "node" to summarizeNode(it.node),
                    )
                },
            ),
        )
    }

    private fun sourceGroundingMeta(grounding: SourceGrounding): Map<String, Any?> {
        if (grounding.debug.isEmpty()) return emptyMap()
        return mapOf(
            "source_fallback_reason" to grounding.debug["source_fallback_reason"],
            "source_fallback_mode" to grounding.debug["source_fallback_mode"],
            "source_fallback" to grounding.debug,
        ).filterValues { it != null }
    }

    private fun isSourceFallbackCandidate(
        action: String,
        node: UiNode,
        rootBounds: Rect,
    ): Boolean {
        if (!isAnchorCandidate(node, rootBounds)) return false
        val rootArea = rootBounds.area.coerceAtLeast(1f)
        if (node.area / rootArea >= 0.90f) return false
        return when (action) {
            OobActionSchema.TOOL_INPUT_TEXT ->
                node.editable || node.focusable || node.classSuffix.contains("edittext")
            OobActionSchema.TOOL_LONG_PRESS ->
                node.longClickable || node.clickable || node.focusable || nodeVisibleTexts(node).isNotEmpty()
            OobActionSchema.TOOL_CLICK ->
                node.clickable || node.focusable || node.editable || nodeVisibleTexts(node).isNotEmpty()
            else -> false
        }
    }

    private fun semanticHintTexts(args: Map<String, Any?>): List<String> =
        listOf(
            args["target_description"],
            args["targetDescription"],
            args["clickPrompt"],
            args["label"],
            args["selector"],
            args["node_text"],
            args["node_content_description"],
            args["node_hint_text"],
            args["node_resource_id"],
            args["resource_id"],
        ).mapNotNull { value ->
            normalizeText(value?.toString())
                .takeIf(::isMeaningfulSemanticTargetText)
                ?.takeUnless(::isCoordinateOnlyTargetText)
        }.distinct()

    private fun semanticHintScore(node: UiNode, targetTexts: List<String>): Float {
        if (targetTexts.isEmpty()) return 0f
        val labels = nodeVisibleTexts(node) + listOf(node.resourceTail).filter(::isMeaningfulSemanticTargetText)
        if (labels.isEmpty()) return 0f
        return targetTexts.maxOf { target ->
            labels.maxOf { label ->
                when {
                    label == target -> 1.4f
                    label.contains(target) || target.contains(label) && label.length >= 2 -> 0.95f
                    else -> 0f
                }
            }
        }
    }

    private fun sourceFallbackActionScore(
        action: String,
        node: UiNode,
    ): Float =
        when (action) {
            OobActionSchema.TOOL_INPUT_TEXT ->
                when {
                    node.editable -> 0.65f
                    node.focusable || node.classSuffix.contains("edittext") -> 0.45f
                    else -> -0.35f
                }
            OobActionSchema.TOOL_LONG_PRESS ->
                when {
                    node.longClickable -> 0.45f
                    node.clickable || node.focusable -> 0.30f
                    else -> 0.05f
                }
            OobActionSchema.TOOL_CLICK ->
                when {
                    node.clickable -> 0.45f
                    node.focusable || node.editable -> 0.35f
                    else -> 0.08f
                }
            else -> 0f
        }

    private fun distanceToRectRatio(
        x: Float,
        y: Float,
        rect: Rect,
        diagonal: Float,
    ): Float {
        val dx = when {
            x < rect.left -> rect.left - x
            x > rect.right -> x - rect.right
            else -> 0f
        }
        val dy = when {
            y < rect.top -> rect.top - y
            y > rect.bottom -> y - rect.bottom
            else -> 0f
        }
        return (hypot(dx, dy) / diagonal.coerceAtLeast(1f)).coerceIn(0f, 1f)
    }

    private data class SourceFallbackCandidate(
        val node: UiNode,
        val score: Float,
        val distanceRatio: Float,
        val semanticScore: Float,
        val actionScore: Float,
    )

    private fun matchTargetNodeForAction(
        action: String,
        args: Map<String, Any?>,
        sourcePage: PageModel,
        targetPage: PageModel,
        sourceNode: UiNode,
    ): TargetMatch? {
        if (action == OobActionSchema.TOOL_INPUT_TEXT) {
            val resourceId = stringArg(args, "node_resource_id", "resource_id", "resource-id").orEmpty()
            val targetNode = bestResourceNodeForPoint(
                page = targetPage,
                resourceId = resourceId,
                tool = action,
                x = null,
                y = null,
                reference = sourceNode,
            )
            if (targetNode != null) {
                return TargetMatch(
                    node = targetNode,
                    confidence = 1.0f,
                    anchorCount = 0,
                    mode = "resource_id",
                    debug = mapOf(
                        "matched_by" to listOf("resource_id"),
                        "resource_id" to resourceId,
                    ),
                )
            }
            inputTextTargetFallback(args, targetPage, sourceNode)?.let { return it }
        }
        return matchTargetNode(sourcePage, targetPage, sourceNode)
    }

    private fun inputTextTargetFallback(
        args: Map<String, Any?>,
        targetPage: PageModel,
        sourceNode: UiNode,
    ): TargetMatch? {
        val candidates = targetPage.nodes.filter { node ->
            node.visible && node.enabled && (node.editable || node.focusable || node.classSuffix.contains("edittext"))
        }
        if (candidates.isEmpty()) return null
        val targetTexts = listOf(
            args["target_description"],
            args["targetDescription"],
            sourceNode.text,
            sourceNode.contentDesc,
            sourceNode.hintText,
            sourceNode.subtreeText,
        ).mapNotNull { value ->
            normalizeText(value?.toString()).takeIf(::isMeaningfulSemanticTargetText)
        }.distinct()

        val scored = candidates.mapNotNull { node ->
            val labels = nodeVisibleTexts(node)
            val textMatch = targetTexts.any { target ->
                labels.any { label ->
                    label == target ||
                        label.contains(target) ||
                        (target.contains(label) && label.length >= 2)
                }
            }
            val hasEvidence = node.focused || textMatch || candidates.size == 1
            if (!hasEvidence) return@mapNotNull null
            val sizePenalty = (
                kotlin.math.abs(node.bounds.width - sourceNode.bounds.width) +
                    kotlin.math.abs(node.bounds.height - sourceNode.bounds.height)
                ) * 0.001f
            val score =
                (if (node.focused) 1000f else 0f) +
                    (if (node.editable) 500f else 0f) +
                    (if (textMatch) 300f else 0f) +
                    (if (node.classSuffix == sourceNode.classSuffix) 80f else 0f) -
                    sizePenalty
            node to score
        }.maxByOrNull { it.second } ?: return null

        val mode = when {
            scored.first.focused -> "focused_input"
            targetTexts.isNotEmpty() -> "semantic_input"
            else -> "single_input"
        }
        return TargetMatch(
            node = scored.first,
            confidence = if (scored.first.focused) 1.0f else 0.86f,
            anchorCount = 0,
            mode = mode,
            debug = mapOf(
                "matched_by" to mode,
                "candidate_count" to candidates.size,
                "target_texts" to targetTexts.take(6),
                "source_element" to summarizeNode(sourceNode),
                "target_element" to summarizeNode(scored.first),
            ),
        )
    }

    private fun projectGrounding(
        action: String,
        args: Map<String, Any?>,
        grounding: SourceGrounding,
        targetMatch: TargetMatch,
        options: Options,
    ): Result {
        if (targetMatch.mode == "omniflow_bayesian" && targetMatch.confidence < options.minConfidence) {
            return rawCoordinateFallback(
                action = action,
                args = args,
                sourceResolution = "low_confidence_anchor_projection",
                diagnostics = mapOf(
                    "reason" to "low_confidence_anchor_projection",
                    "confidence" to targetMatch.confidence,
                    "min_confidence" to options.minConfidence,
                    "rejected_new" to projectedCoordinateMap(grounding.coordinates, grounding.node.bounds, targetMatch.node.bounds),
                    "source_element" to summarizeNode(grounding.node),
                    "target_element" to summarizeNode(targetMatch.node),
                    "debug" to targetMatch.debug,
                ),
            )
        }
        val newCoordinates = projectedCoordinateMap(grounding.coordinates, grounding.node.bounds, targetMatch.node.bounds)
        return Result(
            args = args + newCoordinates,
            applied = true,
            diagnostics = mapOf(
                "applied" to true,
                "tool" to action,
                "mode" to targetMatch.mode,
                "algorithm" to "anchor_projection",
                "confidence" to targetMatch.confidence,
                "anchor_count" to targetMatch.anchorCount,
                "old" to oldCoordinateMap(grounding.coordinates),
                "new" to newCoordinates,
                "source_element" to summarizeNode(grounding.node),
                "target_element" to summarizeNode(targetMatch.node),
                "debug" to targetMatch.debug,
            ),
        )
    }

    private fun semanticTargetFallback(
        action: String,
        args: Map<String, Any?>,
        targetPage: PageModel,
    ): Result? {
        if (action != OobActionSchema.TOOL_CLICK && action != OobActionSchema.TOOL_LONG_PRESS) {
            return null
        }
        val targetTexts = listOf(
            args["target_description"],
            args["targetDescription"],
            args["clickPrompt"],
            args["label"],
            args["selector"],
        ).mapNotNull { value ->
            normalizeText(value?.toString()).takeIf(::isMeaningfulSemanticTargetText)
        }.distinct()
        if (targetTexts.isEmpty()) return null
        val match = findInteractivePointSemanticTarget(targetPage, targetTexts) ?: return null
        val newCoordinates = mapOf("x" to match.node.centerX, "y" to match.node.centerY)
        return Result(
            args = args + newCoordinates,
            applied = true,
            diagnostics = mapOf(
                "applied" to true,
                "tool" to action,
                "mode" to "semantic_target",
                "algorithm" to "anchor_projection",
                "confidence" to 1.0f,
                "anchor_count" to 0,
                "new" to newCoordinates,
                "source_element" to summarizeNode(match.node),
                "target_element" to summarizeNode(match.node),
                "debug" to mapOf(
                    "matched_by" to match.matchedBy,
                    "matched_value" to match.matchedValue,
                    "target_texts" to targetTexts.take(6),
                    "reason" to "source_point_missing_semantic_target",
                ),
            ),
        )
    }

    private fun rootProjectionFallback(
        action: String,
        args: Map<String, Any?>,
        sourcePage: PageModel,
        targetPage: PageModel,
        reason: String,
        options: Options,
    ): Result? {
        if (!options.allowRootProjectionFallback) return null
        val coordinates = when (action) {
            OobActionSchema.TOOL_SWIPE -> {
                val x1 = optionalFloatArg(args["x1"]) ?: return null
                val y1 = optionalFloatArg(args["y1"]) ?: return null
                val x2 = optionalFloatArg(args["x2"]) ?: return null
                val y2 = optionalFloatArg(args["y2"]) ?: return null
                listOf(Coordinate("x1", "y1", x1, y1), Coordinate("x2", "y2", x2, y2))
            }
            OobActionSchema.TOOL_CLICK,
            OobActionSchema.TOOL_LONG_PRESS,
            OobActionSchema.TOOL_INPUT_TEXT -> {
                val x = optionalFloatArg(args["x"]) ?: return null
                val y = optionalFloatArg(args["y"]) ?: return null
                listOf(Coordinate("x", "y", x, y))
            }
            else -> return null
        }
        if (sourcePage.rootBounds.area <= 0f || targetPage.rootBounds.area <= 0f) return null
        val newCoordinates = projectedCoordinateMap(coordinates, sourcePage.rootBounds, targetPage.rootBounds)
        return Result(
            args = args + newCoordinates,
            applied = true,
            reason = reason,
            diagnostics = mapOf(
                "applied" to true,
                "tool" to action,
                "mode" to "root_projection_fallback",
                "algorithm" to "root_projection",
                "reason" to reason,
                "confidence" to 0f,
                "anchor_count" to 0,
                "old" to oldCoordinateMap(coordinates),
                "new" to newCoordinates,
                "source_element" to summarizeNode(sourcePage.nodes.first()),
                "target_element" to mapOf(
                    "bounds" to summarizeBounds(targetPage.rootBounds),
                    "fallback" to true,
                ),
                "debug" to mapOf(
                    "source_root" to summarizeBounds(sourcePage.rootBounds),
                    "target_root" to summarizeBounds(targetPage.rootBounds),
                ),
            ),
        )
    }

    private fun elementSimilarityFallback(
        action: String,
        args: Map<String, Any?>,
        sourcePage: PageModel,
        targetPage: PageModel,
        grounding: SourceGrounding,
        matchDebug: Map<String, Any?>,
    ): Result? {
        val targetMatch = closestElementSimilarityTarget(
            action = action,
            sourcePage = sourcePage,
            targetPage = targetPage,
            sourceNode = grounding.node,
            matchDebug = matchDebug,
        ) ?: return null
        return projectGrounding(
            action = action,
            args = args,
            grounding = grounding,
            targetMatch = targetMatch,
            options = Options(),
        )
    }

    private fun closestElementSimilarityTarget(
        action: String,
        sourcePage: PageModel,
        targetPage: PageModel,
        sourceNode: UiNode,
        matchDebug: Map<String, Any?>,
    ): TargetMatch? {
        if (looksLikeSparseOverlayPage(targetPage) && isLikelyDifferentAppOverlay(sourcePage, targetPage)) return null
        val candidates = targetPage.nodes
            .filter { isElementSimilarityFallbackCandidate(action, it, targetPage.rootBounds) }
        if (candidates.isEmpty()) return null
        val sourceInfo = sourceNode.toNodeInfo(sourcePage.rootBounds.area.coerceAtLeast(1f))
        val sourceVector = ActionTransferNodeMatcher.vector(sourceInfo)
        val targetArea = targetPage.rootBounds.area.coerceAtLeast(1f)
        val scored = candidates.map { node ->
            val targetInfo = node.toNodeInfo(targetArea)
            val components = ActionTransferNodeMatcher.simComponents(sourceInfo, targetInfo)
            val vectorSimilarity = ActionTransferNodeMatcher
                .cosine(sourceVector, ActionTransferNodeMatcher.vector(targetInfo))
                .coerceIn(0f, 1f)
            val score = elementSimilarityFallbackScore(action, sourceNode, node, components.score, vectorSimilarity)
            ElementSimilarityCandidate(
                node = node,
                score = score,
                componentScore = components.score,
                vectorSimilarity = vectorSimilarity,
                components = components.toDebugMap(),
            )
        }.sortedWith(
            compareByDescending<ElementSimilarityCandidate> { it.score }
                .thenByDescending { it.componentScore }
                .thenBy { it.node.area }
                .thenBy { it.node.index }
        )
        val best = scored.firstOrNull() ?: return null
        return TargetMatch(
            node = best.node,
            confidence = best.score,
            anchorCount = 0,
            mode = "element_similarity_fallback",
            debug = mapOf(
                "fallback_reason" to "target_match_unresolved",
                "source_element" to summarizeNode(sourceNode),
                "target_element" to summarizeNode(best.node),
                "similarity_score" to best.score,
                "component_score" to best.componentScore,
                "vector_similarity" to best.vectorSimilarity,
                "components" to best.components,
                "candidate_count" to candidates.size,
                "previous_match_debug" to matchDebug,
                "top" to scored.take(5).map {
                    mapOf(
                        "similarity_score" to it.score,
                        "component_score" to it.componentScore,
                        "vector_similarity" to it.vectorSimilarity,
                        "node" to summarizeNode(it.node),
                    )
                },
            ),
        )
    }

    private fun elementSimilarityFallbackScore(
        action: String,
        sourceNode: UiNode,
        targetNode: UiNode,
        componentScore: Float,
        vectorSimilarity: Float,
    ): Float {
        val actionBonus = when (action) {
            OobActionSchema.TOOL_INPUT_TEXT ->
                if (targetNode.editable || targetNode.focusable || targetNode.classSuffix.contains("edittext")) 0.12f else -0.35f
            OobActionSchema.TOOL_LONG_PRESS ->
                if (targetNode.longClickable || targetNode.clickable) 0.08f else -0.20f
            OobActionSchema.TOOL_CLICK ->
                if (targetNode.clickable || targetNode.focusable || targetNode.editable) 0.08f else -0.20f
            OobActionSchema.TOOL_SWIPE ->
                if (targetNode.scrollable || sourceNode.scrollable == targetNode.scrollable) 0.05f else -0.20f
            else -> 0f
        }
        val labelBonus = when {
            nodeVisibleTexts(sourceNode).isEmpty() -> 0f
            nodeVisibleTexts(sourceNode).intersect(nodeVisibleTexts(targetNode).toSet()).isNotEmpty() -> 0.10f
            else -> 0f
        }
        return (0.82f * componentScore + 0.18f * vectorSimilarity + actionBonus + labelBonus)
            .coerceIn(0f, 1f)
    }

    private fun isElementSimilarityFallbackCandidate(
        action: String,
        node: UiNode,
        rootBounds: Rect,
    ): Boolean {
        if (!isAnchorCandidate(node, rootBounds)) return false
        val rootArea = rootBounds.area.coerceAtLeast(1f)
        if (node.area / rootArea >= 0.90f) return false
        return when (action) {
            OobActionSchema.TOOL_INPUT_TEXT ->
                node.editable || node.focusable || node.classSuffix.contains("edittext")
            OobActionSchema.TOOL_LONG_PRESS ->
                node.longClickable || node.clickable || node.focusable
            OobActionSchema.TOOL_CLICK ->
                node.clickable || node.focusable || node.editable
            OobActionSchema.TOOL_SWIPE ->
                node.scrollable
            else -> false
        }
    }

    private fun isLikelyDifferentAppOverlay(
        sourcePage: PageModel,
        targetPage: PageModel,
    ): Boolean {
        val targetPackage = dominantPackageName(targetPage)
        if (targetPackage.startsWith("cn.com.omnimind.")) return true
        val sourcePackage = dominantPackageName(sourcePage)
        return sourcePackage.isNotBlank() &&
            targetPackage.isNotBlank() &&
            sourcePackage != targetPackage
    }

    private fun dominantPackageName(page: PageModel): String {
        return page.nodes
            .asSequence()
            .map { it.packageName.trim().lowercase() }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            ?.key
            .orEmpty()
    }

    private data class ElementSimilarityCandidate(
        val node: UiNode,
        val score: Float,
        val componentScore: Float,
        val vectorSimilarity: Float,
        val components: Map<String, Any?>,
    )

    private fun sourceFailureMeta(
        action: String,
        args: Map<String, Any?>,
        sourcePage: PageModel,
    ): Map<String, Any?> {
        if (action == OobActionSchema.TOOL_SWIPE) {
            val x1 = optionalFloatArg(args["x1"])
            val y1 = optionalFloatArg(args["y1"])
            val x2 = optionalFloatArg(args["x2"])
            val y2 = optionalFloatArg(args["y2"])
            return mapOf(
                "old" to mapOf("x1" to x1, "y1" to y1, "x2" to x2, "y2" to y2),
                "source_resolution" to "source_scroll_unresolved",
            )
        }
        val x = optionalFloatArg(args["x"])
        val y = optionalFloatArg(args["y"])
        val sourceNode = if (x != null && y != null) selectPointSourceNode(sourcePage, x, y) else null
        return if (sourceNode == null) {
            mapOf(
                "old" to mapOf("x" to x, "y" to y),
                "source_resolution" to "source_point_unresolved",
            )
        } else {
            mapOf(
                "old" to mapOf("x" to x, "y" to y),
                "source_element" to summarizeNode(sourceNode),
            )
        }
    }

    private fun oldCoordinateMap(coordinates: List<Coordinate>): Map<String, Float> =
        coordinates.flatMap { listOf(it.xKey to it.x, it.yKey to it.y) }.toMap()

    private fun projectedCoordinateMap(
        coordinates: List<Coordinate>,
        sourceBounds: Rect,
        targetBounds: Rect,
    ): Map<String, Float> =
        coordinates.flatMap { coordinate ->
            val mapped = projectPoint(sourceBounds, targetBounds, coordinate.x, coordinate.y)
            listOf(coordinate.xKey to mapped.first, coordinate.yKey to mapped.second)
        }.toMap()

    private fun findInteractivePointSemanticTarget(
        page: PageModel,
        targetTexts: List<String>,
    ): SemanticTargetMatch? {
        val nodes = page.nodes.filter { it.visible && it.enabled && it.interactive }
        val texts = targetTexts.sortedByDescending { it.length }
        for (text in texts) {
            val exact = nodes.firstOrNull { node ->
                nodeVisibleTexts(node).any { it == text }
            }
            if (exact != null) {
                return SemanticTargetMatch(exact, "text_exact", text)
            }
        }
        for (text in texts) {
            val contains = nodes.firstOrNull { node ->
                val labels = nodeVisibleTexts(node)
                labels.any { label ->
                    label.contains(text) || (text.contains(label) && label.length >= 3)
                }
            }
            if (contains != null) {
                return SemanticTargetMatch(contains, "text_contains", text)
            }
        }
        return null
    }

    private fun isMeaningfulSemanticTargetText(text: String): Boolean {
        if (text.isBlank()) return false
        if (text in GENERIC_TARGET_TEXT_TOKENS) return false
        return text.length >= 2
    }

    private fun isCoordinateOnlyTargetText(text: String): Boolean {
        val normalized = normalizeText(text)
        if (normalized == "屏幕坐标" || normalized == "screen coordinate") return true
        return COORDINATE_ONLY_TARGET_REGEX.matches(normalized)
    }

    private fun nodeVisibleTexts(node: UiNode): List<String> =
        listOf(node.text, node.contentDesc, node.hintText, node.subtreeText)
            .map(::normalizeText)
            .filter(::isMeaningfulSemanticTargetText)
            .distinct()

    private fun bestResourceNodeForPoint(
        page: PageModel,
        resourceId: String,
        tool: String,
        x: Float?,
        y: Float?,
        reference: UiNode?,
    ): UiNode? {
        if (resourceId.isBlank()) return null
        val tail = resourceTail(resourceId)
        return page.nodes
            .asSequence()
            .filter { node ->
                node.visible &&
                    node.enabled &&
                    (node.resourceId == resourceId || (tail.isNotBlank() && node.resourceTail == tail))
            }
            .map { node ->
                val containsPoint = x != null && y != null && node.bounds.contains(x, y)
                val typeScore = when (tool) {
                    OobActionSchema.TOOL_INPUT_TEXT -> if (node.editable) 1000f else -1000f
                    OobActionSchema.TOOL_CLICK -> if (node.clickable || node.focusable) 400f else 0f
                    OobActionSchema.TOOL_LONG_PRESS -> if (node.longClickable || node.clickable) 400f else 0f
                    else -> 0f
                }
                val pointScore = if (containsPoint) 700f else 0f
                val referenceScore = reference?.let {
                    var score = 0f
                    if (node.classSuffix == it.classSuffix) score += 160f
                    score -= kotlin.math.abs(node.bounds.width - it.bounds.width) * 0.03f
                    score -= kotlin.math.abs(node.bounds.height - it.bounds.height) * 0.03f
                    score -= kotlin.math.abs(node.depth - it.depth) * 8f
                    score
                } ?: 0f
                val areaPenalty = node.area / 100000f
                node to (typeScore + pointScore + referenceScore - areaPenalty)
            }
            .filter { (_, score) -> score > -500f }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun matchTargetNode(
        sourcePage: PageModel,
        targetPage: PageModel,
        sourceNode: UiNode,
    ): TargetMatch? = matchTargetNodeAttempt(sourcePage, targetPage, sourceNode).match

    private fun matchTargetNodeAttempt(
        sourcePage: PageModel,
        targetPage: PageModel,
        sourceNode: UiNode,
    ): TargetMatchAttempt {
        val srcArea = sourcePage.rootBounds.area.coerceAtLeast(1f)
        val tgtArea = targetPage.rootBounds.area.coerceAtLeast(1f)
        val allTgtInfos = targetPage.nodes.map { it.toNodeInfo(tgtArea) }
        val allTgtVecs = allTgtInfos.map { ActionTransferNodeMatcher.vector(it) }

        val anchorSourceSelection = selectLocalAnchorSourceNodes(sourcePage, sourceNode)
        val anchorSrcNodes = anchorSourceSelection.nodes.map { it.toNodeInfo(srcArea) }
        val anchorSrcVecs = anchorSrcNodes.map { ActionTransferNodeMatcher.vector(it) }
        val anchorTgtIdx = targetPage.nodes.indices.filter { isAnchorCandidate(targetPage.nodes[it], targetPage.rootBounds) }
        val anchorTgtInfos = anchorTgtIdx.map { allTgtInfos[it] }
        val anchorTgtVecs = anchorTgtIdx.map { allTgtVecs[it] }
        val anchors = weightAnchorsByLocality(
            anchors = ActionTransferNodeMatcher.findAnchors(anchorSrcNodes, anchorSrcVecs, anchorTgtInfos, anchorTgtVecs),
            sourceNode = sourceNode,
            rootBounds = sourcePage.rootBounds,
        )

        val candIdx = targetPage.nodes.indices.filter { targetPage.nodes[it].let { n -> n.visible && n.enabled && n.area > 1f } }
        if (candIdx.isEmpty()) {
            return TargetMatchAttempt(
                match = null,
                debug = mapOf(
                    "reason" to "no_candidates",
                    "source_element" to summarizeNode(sourceNode),
                    "anchor_count" to anchors.size,
                    "anchor_scope" to anchorSourceSelection.toDebugMap(),
                ),
            )
        }
        val candidates = candIdx.map { targetPage.nodes[it] }
        val candInfos = candIdx.map { allTgtInfos[it] }
        val candVecs = candIdx.map { allTgtVecs[it] }

        val srcInfo = sourceNode.toNodeInfo(srcArea)
        val srcVec = ActionTransferNodeMatcher.vector(srcInfo)
        val srcDiagonal = hypot(sourcePage.rootBounds.width, sourcePage.rootBounds.height).coerceAtLeast(1f)
        val diagonal = hypot(targetPage.rootBounds.width, targetPage.rootBounds.height).coerceAtLeast(1f)
        val scaleX = targetPage.rootBounds.width / sourcePage.rootBounds.width.coerceAtLeast(1e-6f)
        val scaleY = targetPage.rootBounds.height / sourcePage.rootBounds.height.coerceAtLeast(1e-6f)

        val result = ActionTransferNodeMatcher.match(srcInfo, srcVec, candInfos, candVecs, anchors, srcDiagonal, diagonal, scaleX, scaleY)
        if (result.abstain || result.index < 0) {
            return TargetMatchAttempt(
                match = null,
                debug = mapOf(
                    "reason" to "matcher_abstain",
                    "source_element" to summarizeNode(sourceNode),
                    "anchor_count" to anchors.size,
                    "anchor_scope" to anchorSourceSelection.toDebugMap(),
                ) + result.debug,
            )
        }

        val bestNode = candidates[result.index]
        val debug = mapOf(
            "source_element" to summarizeNode(sourceNode),
            "target_element" to summarizeNode(bestNode),
            "anchor_count" to anchors.size,
            "anchor_scope" to anchorSourceSelection.toDebugMap(),
        ) + result.debug
        return TargetMatchAttempt(
            match = TargetMatch(
                node = bestNode,
                confidence = result.confidence,
                anchorCount = anchors.size,
                mode = result.mode,
                debug = debug,
            ),
            debug = debug,
        )
    }

    private fun selectLocalAnchorSourceNodes(
        page: PageModel,
        sourceNode: UiNode,
    ): LocalAnchorSourceSelection {
        val allCandidates = page.nodes
            .filter { isAnchorCandidate(it, page.rootBounds) }
            .sortedWith(
                compareBy<UiNode> { anchorDistanceRatio(sourceNode, it, page.rootBounds) }
                    .thenBy { it.area }
                    .thenBy { it.index }
            )
        if (allCandidates.isEmpty()) {
            return LocalAnchorSourceSelection(emptyList(), 0f, 0, 0)
        }
        for (radius in LOCAL_ANCHOR_RADIUS_RATIOS) {
            val selected = allCandidates
                .filter { anchorDistanceRatio(sourceNode, it, page.rootBounds) <= radius }
                .take(MAX_LOCAL_ANCHOR_SOURCE_COUNT)
            val nonSelfCount = selected.count { !sameUiNode(it, sourceNode) }
            if (nonSelfCount >= MIN_LOCAL_ANCHOR_SOURCE_COUNT || radius == LOCAL_ANCHOR_RADIUS_RATIOS.last()) {
                return LocalAnchorSourceSelection(selected, radius, allCandidates.size, nonSelfCount)
            }
        }
        val selected = allCandidates.take(MAX_LOCAL_ANCHOR_SOURCE_COUNT)
        return LocalAnchorSourceSelection(
            selected,
            LOCAL_ANCHOR_RADIUS_RATIOS.last(),
            allCandidates.size,
            selected.count { !sameUiNode(it, sourceNode) },
        )
    }

    private fun weightAnchorsByLocality(
        anchors: List<ActionTransferNodeMatcher.Anchor>,
        sourceNode: UiNode,
        rootBounds: Rect,
    ): List<ActionTransferNodeMatcher.Anchor> {
        if (anchors.isEmpty()) return emptyList()
        return anchors.map { anchor ->
            val distanceRatio = anchorDistanceRatio(
                sourceX = sourceNode.bounds.centerX,
                sourceY = sourceNode.bounds.centerY,
                anchorX = anchor.src.centerX,
                anchorY = anchor.src.centerY,
                rootBounds = rootBounds,
            )
            val localityPrior = exp(
                -((distanceRatio * distanceRatio) /
                    (2.0f * LOCAL_ANCHOR_DISTANCE_SIGMA * LOCAL_ANCHOR_DISTANCE_SIGMA)).toDouble()
            ).toFloat().coerceIn(0.05f, 1f)
            anchor.copy(sim = (anchor.sim * localityPrior).coerceIn(0f, 1f))
        }
    }

    private fun anchorDistanceRatio(
        sourceNode: UiNode,
        anchorNode: UiNode,
        rootBounds: Rect,
    ): Float =
        anchorDistanceRatio(
            sourceX = sourceNode.bounds.centerX,
            sourceY = sourceNode.bounds.centerY,
            anchorX = anchorNode.bounds.centerX,
            anchorY = anchorNode.bounds.centerY,
            rootBounds = rootBounds,
        )

    private fun anchorDistanceRatio(
        sourceX: Float,
        sourceY: Float,
        anchorX: Float,
        anchorY: Float,
        rootBounds: Rect,
    ): Float {
        val diagonal = hypot(rootBounds.width, rootBounds.height).coerceAtLeast(1f)
        return (hypot(sourceX - anchorX, sourceY - anchorY) / diagonal).coerceAtLeast(0f)
    }

    private fun sameUiNode(left: UiNode, right: UiNode): Boolean =
        left.index == right.index ||
            (
                left.bounds == right.bounds &&
                    left.resourceId == right.resourceId &&
                    left.text == right.text &&
                    left.contentDesc == right.contentDesc &&
                    left.className == right.className
                )

    fun selectPointSourceNode(
        page: PageModel,
        x: Float,
        y: Float,
    ): UiNode? {
        val containing = page.nodes
            .filter { it.bounds.contains(x, y) }
            .sortedBy { it.area }
        if (containing.isEmpty()) return null
        return containing.firstOrNull { it.interactive } ?: containing.first()
    }

    private fun selectScrollSourceNode(
        page: PageModel,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
    ): UiNode? {
        val containingBoth = page.nodes
            .filter { it.bounds.contains(x1, y1) && it.bounds.contains(x2, y2) }
            .sortedBy { it.area }
        containingBoth.firstOrNull { it.scrollable }?.let { return it }
        containingBoth.firstOrNull { it.interactive }?.let { return it }
        containingBoth.firstOrNull()?.let { return it }
        return selectPointSourceNode(page, (x1 + x2) / 2f, (y1 + y2) / 2f)
    }

    private fun projectPoint(
        sourceBounds: Rect,
        targetBounds: Rect,
        x: Float,
        y: Float,
    ): Pair<Float, Float> {
        val relativeX = if (sourceBounds.width <= 1e-3f) {
            0.5f
        } else {
            ((x - sourceBounds.left) / sourceBounds.width).coerceIn(0f, 1f)
        }
        val relativeY = if (sourceBounds.height <= 1e-3f) {
            0.5f
        } else {
            ((y - sourceBounds.top) / sourceBounds.height).coerceIn(0f, 1f)
        }
        val newX = targetBounds.clampX(targetBounds.left + targetBounds.width * relativeX)
        val newY = targetBounds.clampY(targetBounds.top + targetBounds.height * relativeY)
        return newX to newY
    }

    fun parsePageModel(xml: String): PageModel? {
        val root = parseXmlRoot(xml) ?: return null
        val nodes = mutableListOf<UiNode>()
        val elements = root.getElementsByTagName("*")
        for (i in 0 until elements.length) {
            val element = elements.item(i) as? Element ?: continue
            val bounds = parseBounds(element.getAttribute("bounds")) ?: continue
            if (bounds.width <= 0f || bounds.height <= 0f) continue
            val className = element.stringAttr("class-name").ifEmpty {
                element.stringAttr("class")
            }
            val resourceId = element.stringAttr("resource-id")
            val clickable = element.boolAttr("clickable")
            val focusable = element.boolAttr("focusable")
            val editable = element.boolAttr("editable")
            val scrollable = element.boolAttr("scrollable")
            nodes += UiNode(
                index = i,
                bounds = bounds,
                className = className,
                classSuffix = classSuffix(className),
                resourceId = resourceId,
                resourceTail = resourceTail(resourceId),
                text = normalizeText(element.getAttribute("text")),
                contentDesc = normalizeText(element.getAttribute("content-desc")),
                hintText = normalizeText(element.getAttribute("hint-text")),
                subtreeText = if (clickable) subtreeLabelText(element) else "",
                packageName = normalizeText(element.getAttribute("package")),
                clickable = clickable,
                longClickable = element.boolAttr("long-clickable"),
                focusable = focusable,
                editable = editable,
                scrollable = scrollable,
                enabled = element.boolAttr("enabled", defaultValue = true),
                visible = element.boolAttr("visible-to-user", defaultValue = true) &&
                    element.boolAttr("displayed", defaultValue = true),
                selected = element.boolAttr("selected"),
                checkable = element.boolAttr("checkable"),
                focused = element.boolAttr("focused"),
                isLeaf = elementIsLeaf(element),
                hasSiblings = elementHasSiblings(element),
                structSignature = subtreeSignature(element, depth = 2),
                depth = elementDepth(element),
            )
        }
        if (nodes.isEmpty()) return null
        val rootBounds = parseBounds(root.getAttribute("bounds")) ?: inferRootBounds(nodes)
        return PageModel(rootBounds = rootBounds, nodes = nodes)
    }

    private fun subtreeLabelText(element: Element): String {
        val labels = mutableListOf<String>()
        fun visit(current: Element) {
            labels += normalizeText(current.getAttribute("text"))
            labels += normalizeText(current.getAttribute("content-desc"))
            labels += normalizeText(current.getAttribute("hint-text"))
            val children = current.childNodes
            for (index in 0 until children.length) {
                val child = children.item(index) as? Element ?: continue
                visit(child)
            }
        }
        visit(element)
        return labels
            .asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
    }

    private fun parseXmlRoot(xml: String): Element? =
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
                .documentElement
        }.getOrNull()

    private fun elementDepth(element: Element): Int {
        var depth = 0
        var node: org.w3c.dom.Node? = element.parentNode
        while (node is Element) {
            depth++
            node = node.parentNode
        }
        return depth
    }

    private fun elementIsLeaf(element: Element): Boolean =
        (0 until element.childNodes.length).none { element.childNodes.item(it) is Element }

    private fun elementHasSiblings(element: Element): Boolean {
        val parent = element.parentNode as? Element ?: return false
        return (0 until parent.childNodes.length).count { parent.childNodes.item(it) is Element } > 1
    }

    private fun subtreeSignature(element: Element, depth: Int): String {
        val cn = element.stringAttr("class").ifEmpty { element.stringAttr("class-name") }.substringAfterLast('.')
        val hasText = element.getAttribute("text").isNotBlank() || element.getAttribute("content-desc").isNotBlank()
        val children = (0 until element.childNodes.length)
            .mapNotNull { element.childNodes.item(it) as? Element }
        val token = "$cn|t${if (hasText) 1 else 0}|c${children.size.coerceAtMost(5)}"
        if (depth <= 0 || children.isEmpty()) return token
        val childSigs = children.take(3).map { subtreeSignature(it, depth - 1) }.sorted()
        return "$token->[${childSigs.joinToString(",")}]"
    }

    private fun inferRootBounds(nodes: List<UiNode>): Rect {
        val left = nodes.minOf { it.bounds.left }
        val top = nodes.minOf { it.bounds.top }
        val right = nodes.maxOf { it.bounds.right }
        val bottom = nodes.maxOf { it.bounds.bottom }
        return Rect(left, top, right, bottom)
    }

    private fun isAnchorCandidate(node: UiNode, rootBounds: Rect): Boolean {
        if (!node.visible || !node.enabled || node.area <= 1f) return false
        val rootArea = rootBounds.area.coerceAtLeast(1f)
        val fullScreenLike = node.area / rootArea >= 0.96f
        if (fullScreenLike && node.resourceId.isBlank() && node.text.isBlank() && node.contentDesc.isBlank()) {
            return false
        }
        return node.interactive || node.resourceId.isNotBlank() || node.text.isNotBlank() || node.contentDesc.isNotBlank()
    }

    fun summarizeNode(node: UiNode): Map<String, Any?> = mapOf(
        "index" to node.index,
        "bounds" to listOf(node.bounds.left, node.bounds.top, node.bounds.right, node.bounds.bottom),
        "class" to node.className,
        "resource_id" to node.resourceId,
        "text" to node.text,
        "content_desc" to node.contentDesc,
        "scrollable" to node.scrollable,
        "clickable" to node.clickable,
        "editable" to node.editable,
    )

    fun summarizeBounds(bounds: Rect): Map<String, Any?> = mapOf(
        "bounds" to listOf(bounds.left, bounds.top, bounds.right, bounds.bottom),
        "x" to bounds.centerX,
        "y" to bounds.centerY,
        "width" to bounds.width,
        "height" to bounds.height,
    )

    fun parseBounds(bounds: String?): Rect? {
        val text = bounds?.trim().orEmpty()
        if (text.isEmpty()) return null
        val match = BOUNDS_REGEX.find(text) ?: return null
        val left = match.groupValues[1].toFloatOrNull() ?: return null
        val top = match.groupValues[2].toFloatOrNull() ?: return null
        val right = match.groupValues[3].toFloatOrNull() ?: return null
        val bottom = match.groupValues[4].toFloatOrNull() ?: return null
        if (right <= left || bottom <= top) return null
        return Rect(left, top, right, bottom)
    }

    fun normalizeText(value: String?): String =
        value.orEmpty().trim().lowercase().replace(Regex("\\s+"), " ")

    fun resourceTail(resourceId: String): String {
        if (resourceId.isBlank()) return ""
        return resourceId.substringAfterLast('/').substringAfterLast(':').lowercase()
    }

    fun nodeLabelText(node: UiNode): String =
        listOf(node.text, node.contentDesc, node.hintText, node.resourceTail)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .lowercase()

    fun nodeLabelForKeyboard(node: UiNode): String =
        listOf(
            node.text,
            node.contentDesc,
            node.hintText,
            node.resourceId,
            node.packageName,
            node.className,
        ).filter { it.isNotBlank() }.joinToString(" ").lowercase()

    fun looksLikeSparseOverlayPage(page: PageModel): Boolean {
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

    private fun UiNode.toNodeInfo(rootArea: Float) = ActionTransferNodeMatcher.NodeInfo(
        resourceId = resourceId,
        resourceTail = resourceTail,
        text = text,
        contentDesc = contentDesc,
        hintText = hintText,
        classSuffix = classSuffix,
        clickable = clickable,
        longClickable = longClickable,
        focusable = focusable,
        editable = editable,
        scrollable = scrollable,
        checkable = checkable,
        enabled = enabled,
        selected = selected,
        focused = focused,
        isLeaf = isLeaf,
        hasSiblings = hasSiblings,
        structSignature = structSignature,
        areaRatio = area / rootArea.coerceAtLeast(1f),
        centerX = centerX,
        centerY = centerY,
        depth = depth,
    )

    private fun Element.stringAttr(name: String): String = getAttribute(name).trim()

    private fun Element.boolAttr(name: String, defaultValue: Boolean = false): Boolean {
        val value = getAttribute(name)?.trim()?.lowercase().orEmpty()
        if (value.isEmpty()) return defaultValue
        return value == "true" || value == "1" || value == "yes"
    }

    private fun stringArg(args: Map<String, Any?>, vararg keys: String): String? {
        for (key in keys) {
            val value = args[key] ?: continue
            val text = value.toString().trim()
            if (text.isNotEmpty()) return text
        }
        return null
    }

    private fun firstNonBlank(vararg values: Any?): String {
        for (value in values) {
            val text = value?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    private fun mapArg(value: Any?): Map<String, Any?> =
        when (value) {
            is Map<*, *> -> value.entries.associate { it.key.toString() to it.value }
            else -> emptyMap()
        }

    private fun classSuffix(className: String): String =
        className.substringAfterLast('.').lowercase()

    private fun optionalFloatArg(value: Any?): Float? =
        when (value) {
            is Number -> value.toFloat()
            is String -> value.trim().toFloatOrNull()
            else -> null
        }

    private fun requiresConcreteSourcePoint(tool: String): Boolean =
        tool == OobActionSchema.TOOL_CLICK ||
            tool == OobActionSchema.TOOL_LONG_PRESS ||
            tool == OobActionSchema.TOOL_INPUT_TEXT

    private fun isPageBackgroundSourceNode(node: UiNode, page: PageModel): Boolean {
        val rootArea = page.rootBounds.area.coerceAtLeast(1f)
        val label = nodeLabelText(node)
        return !node.interactive &&
            label.isBlank() &&
            node.area >= rootArea * 0.85f
    }

    private fun isUnusableSourcePointNode(node: UiNode, page: PageModel): Boolean =
        isPageBackgroundSourceNode(node, page) ||
            isKeyboardContainerSourceNode(node)

    private fun isKeyboardContainerSourceNode(node: UiNode): Boolean =
        !node.interactive &&
            nodeLabelText(node).isBlank() &&
            (
                node.className.contains("KeyboardView", ignoreCase = true) ||
                    node.packageName.contains("inputmethod", ignoreCase = true)
                )

    private val BOUNDS_REGEX = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")
    private val COORDINATE_ONLY_TARGET_REGEX = Regex(
        """^(屏幕坐标|screen coordinate|coordinates?|coordinate)\s*[:：]?\s*-?\d+(?:\.\d+)?\s*[,，]\s*-?\d+(?:\.\d+)?$""",
        RegexOption.IGNORE_CASE,
    )
    private val GENERIC_TARGET_TEXT_TOKENS = setOf(
        "click", "tap", "press", "button", "view", "viewgroup", "textview",
        "imageview", "android", "widget", "点击", "按钮", "文本", "视图",
    )
    private const val SPARSE_OVERLAY_MAX_VISIBLE_NODES = 6
    private const val SPARSE_OVERLAY_MAX_INTERACTIVE_NODES = 2
    private const val FULLSCREEN_INTERACTIVE_AREA_RATIO = 0.65f
}
