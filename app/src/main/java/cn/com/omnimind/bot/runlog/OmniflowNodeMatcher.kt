package cn.com.omnimind.bot.runlog

import java.security.MessageDigest
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Faithful Kotlin port of OmniFlow's element_embedding.py + probabilistic_matching.py.
 *
 * Vector construction (matches Python exactly):
 *   - MD5 bigram hash with ^text$ padding + text preprocessing (truncate 10 chars)
 *   - Per-component L2-normalization before inserting into 64-dim vector
 *   - struct_hash = subtree signature (class|t|c->[children]) NOT classSuffix repeat
 *   - hierarchy = is_leaf / has_siblings (from actual tree, not class heuristic)
 *   - attributes[1] = long_clickable (Python order)
 *   - visual_state[2] = focused (not editable||focusable)
 *   - affordance = class-based detection OR flag
 *
 * Anchor-vote matching (matches Python probabilistic_matching.py production path):
 *   - Candidate score is an explicit weighted average of source-target element similarity
 *     and anchor projection support.
 *   - The source action node is never used as its own anchor vote.
 *   - Support, margin, action similarity, and anchor votes are kept in debug diagnostics.
 */
internal object OmniflowNodeMatcher {

    // ── Dimensions & weights ──────────────────────────────────────────────────
    const val ELEMENT_DIM = 64
    const val HUMAN_DIM = 32
    const val PROGRAMMER_DIM = 32
    const val HUMAN_WEIGHT = 0.55f
    const val PROGRAMMER_WEIGHT = 0.45f

    // ── Text encoding ─────────────────────────────────────────────────────────
    private const val TEXT_TRUNCATE_LEN = 10          // Python: text_truncate_len = 10
    private val TEXT_CLEAN_REGEX = Regex("[^a-zA-Z0-9_\\u4e00-\\u9fff]")

    // ── Matching parameters ───────────────────────────────────────────────────
    const val GEOMETRIC_SIGMA = 0.15f
    private const val MATCH_PROBABILITY_SCALE = 8f
    private const val MIN_MATCH_SUPPORT = 0.45f
    private const val MIN_MATCH_MARGIN = 0.05f
    private const val MIN_DIFFUSE_BEST_PROBABILITY = 0.12f
    private const val MIN_ACTION_SIMILARITY = 0.30f
    private const val MIN_HIGH_ACTION_SIMILARITY_WITHOUT_ANCHOR = 0.90f
    private const val MIN_ANCHOR_VOTES = 1f
    private const val ACTION_SIMILARITY_WEIGHT = 0.5f
    private const val TRANSFER_SUPPORT_WEIGHT = 0.5f
    const val MIN_ANCHOR_SIMILARITY = 0.5f
    const val MAX_ANCHOR_COUNT = 5

    // ── Anchor proximity kernel ───────────────────────────────────────────────
    const val ANCHOR_LOCAL_SIGMA = 0.25f
    const val ANCHOR_TREE_LAMBDA = 0.3f

    // ── Icon/affordance class detection ──────────────────────────────────────
    // Python: config.icon_class_names (exact suffix match)
    private val ICON_CLASS_SUFFIXES = setOf(
        "ImageView", "ImageButton", "AppCompatImageView",
        "CircleImageView", "RoundedImageView", "ShapeableImageView",
    )

    // ── Data structures ───────────────────────────────────────────────────────

    data class NodeInfo(
        val resourceId: String,
        val resourceTail: String,
        val text: String,
        val contentDesc: String,
        val hintText: String,
        val classSuffix: String,
        val clickable: Boolean,
        val longClickable: Boolean,
        val focusable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean,
        val checkable: Boolean,
        val enabled: Boolean,
        val selected: Boolean,
        val focused: Boolean,
        val isLeaf: Boolean,
        val hasSiblings: Boolean,
        val structSignature: String,
        val areaRatio: Float,
        val centerX: Float,
        val centerY: Float,
        val depth: Int = 0,
    )

    data class Anchor(val src: NodeInfo, val tgt: NodeInfo, val sim: Float)

    data class ComponentScores(
        val score: Float,
        val text: Float,
        val resource: Float,
        val classType: Float,
        val affordance: Float,
        val structure: Float,
        val prominence: Float,
        val state: Float,
        val textConflict: Boolean,
        val resourceExact: Boolean,
        val sourceHasText: Boolean,
        val targetHasText: Boolean,
    ) {
        fun toDebugMap(): Map<String, Any?> = mapOf(
            "score" to score,
            "text" to text,
            "resource" to resource,
            "class_type" to classType,
            "affordance" to affordance,
            "structure" to structure,
            "prominence" to prominence,
            "state" to state,
            "text_conflict" to textConflict,
            "resource_exact" to resourceExact,
            "source_has_text" to sourceHasText,
            "target_has_text" to targetHasText,
        )
    }

    data class MatchResult(
        val index: Int,
        val abstain: Boolean,
        val pBest: Float,
        val pNull: Float,
        val confidence: Float,
        val mode: String,
        val debug: Map<String, Any?>,
    )

    private data class AnchorContribution(
        val anchorIndex: Int,
        val vote: Float,
        val anchorSimilarity: Float,
        val geometricSupport: Float,
        val normalizedDistance: Float,
        val projectedX: Float,
        val projectedY: Float,
        val sourceAnchor: NodeInfo,
        val targetAnchor: NodeInfo,
    )

    private data class CandidateScore(
        val index: Int,
        val vectorSimilarity: Float,
        val identitySimilarity: Float,
        val componentScores: ComponentScores,
        val actionSimilarity: Float,
        val transferScore: Float,
        val matchScore: Float,
        val anchorVoteCount: Float,
        val contributions: List<AnchorContribution>,
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * 64-dim element vector. Exact port of ElementVectorizer.to_vector() +
     * TextHashEncoder._encode_clean_text() from element_embedding.py.
     */
    fun vector(node: NodeInfo): FloatArray {
        val result = FloatArray(ELEMENT_DIM)

        // ── Human observation [0..31] ─────────────────────────────────────────

        // [0..15] content_hash: MD5 bigram of best text, padded ^text$, L2-normalized
        val rawContent = firstNonBlank(node.text, node.contentDesc, node.hintText)
        val cleanContent = textPreprocess(rawContent)
        bigramHashNormalizedInto(result, 0, 16, "^$cleanContent$")

        // [16..19] content_type one-hot: text(0) / icon(1) / neither(2) / both(3)
        val hasText = cleanContent.isNotBlank()
        val isIcon = node.classSuffix in ICON_CLASS_SUFFIXES
        result[16 + when { hasText && isIcon -> 3; hasText -> 0; isIcon -> 1; else -> 2 }] = 1f

        // [20..23] affordance: class-based detection OR interaction flag
        val cs = node.classSuffix.lowercase()
        result[20] = if (node.clickable || cs.isButtonLike()) 1f else 0f
        result[21] = if (node.editable || cs.isEditLike()) 1f else 0f
        result[22] = if (node.scrollable || cs.isScrollLike()) 1f else 0f
        result[23] = if (node.checkable || cs.isToggleLike()) 1f else 0f

        // [24..27] prominence: one-hot area bucket [<0.5%, 0.5–2%, 2–8%, ≥8%]
        result[24 + when {
            node.areaRatio < 0.005f -> 0
            node.areaRatio < 0.02f -> 1
            node.areaRatio < 0.08f -> 2
            else -> 3
        }] = 1f

        // [28..31] visual_state: selected / disabled / focused / primary
        result[28] = if (node.selected) 1f else 0f
        result[29] = if (!node.enabled) 1f else 0f
        result[30] = if (node.focused) 1f else 0f          // Python: focused attr
        result[31] = if (node.clickable && node.areaRatio >= 0.02f) 1f else 0f

        // ── Programmer observation [32..63] ───────────────────────────────────

        // [32..39] class_type: MD5 bigram of classSuffix, L2-normalized
        val cleanClass = textPreprocess(node.classSuffix)
        bigramHashNormalizedInto(result, 32, 8, "^$cleanClass$")

        // [40..47] attributes: exact Python order
        // [0]=clickable [1]=long_clickable [2]=focusable [3]=editable
        // [4]=scrollable [5]=checkable [6]=enabled [7]=selected
        result[40] = if (node.clickable) 1f else 0f
        result[41] = if (node.longClickable) 1f else 0f
        result[42] = if (node.focusable) 1f else 0f
        result[43] = if (node.editable) 1f else 0f
        result[44] = if (node.scrollable) 1f else 0f
        result[45] = if (node.checkable) 1f else 0f
        result[46] = if (node.enabled) 1f else 0f
        result[47] = if (node.selected) 1f else 0f

        // [48..59] struct_hash: MD5 bigram of subtree signature, L2-normalized
        bigramHashNormalizedInto(result, 48, 12, node.structSignature)

        // [60..61] hierarchy: is_leaf / has_siblings (actual tree, not class heuristic)
        result[60] = if (node.isLeaf) 1f else 0f
        result[61] = if (node.hasSiblings) 1f else 0f

        // [62..63] id_hint: suggests_action / suggests_input
        val tail = node.resourceTail.lowercase()
        result[62] = if (ACTION_ID_PREFIXES.any { tail.startsWith(it) }) 1f else 0f
        result[63] = if (INPUT_ID_PREFIXES.any { tail.startsWith(it) }) 1f else 0f

        // Segment normalization with weights (matches _normalize_vector())
        l2normalizeSegment(result, 0, HUMAN_DIM, HUMAN_WEIGHT)
        l2normalizeSegment(result, HUMAN_DIM, ELEMENT_DIM, PROGRAMMER_WEIGHT)

        return result
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        if (na <= 1e-12 || nb <= 1e-12) return 0f
        return (dot / sqrt(na * nb)).toFloat().coerceIn(-1f, 1f)
    }

    fun findAnchors(
        srcNodes: List<NodeInfo>, srcVecs: List<FloatArray>,
        tgtNodes: List<NodeInfo>, tgtVecs: List<FloatArray>,
        maxCount: Int = MAX_ANCHOR_COUNT,
        minSim: Float = MIN_ANCHOR_SIMILARITY,
    ): List<Anchor> {
        if (srcNodes.isEmpty() || tgtNodes.isEmpty()) return emptyList()
        val sims = Array(srcNodes.size) { si ->
            FloatArray(tgtNodes.size) { ti ->
                sim(srcNodes[si], tgtNodes[ti], srcVecs[si], tgtVecs[ti])
            }
        }
        val bestTgtBySrc = IntArray(srcNodes.size) { si ->
            (0 until tgtNodes.size).maxByOrNull { sims[si][it] } ?: 0
        }
        val bestSrcByTgt = IntArray(tgtNodes.size) { ti ->
            (0 until srcNodes.size).maxByOrNull { sims[it][ti] } ?: 0
        }
        return srcNodes.indices
            .mapNotNull { si ->
                val ti = bestTgtBySrc[si]
                val sim = sims[si][ti]
                if (bestSrcByTgt[ti] == si && sim >= minSim) Anchor(srcNodes[si], tgtNodes[ti], sim)
                else null
            }
            .sortedByDescending { it.sim }
            .take(maxCount)
    }

    @Suppress("UNUSED_PARAMETER")
    internal fun sim(
        source: NodeInfo,
        target: NodeInfo,
        sourceVector: FloatArray,
        targetVector: FloatArray,
    ): Float = simComponents(source, target).score

    internal fun simComponents(source: NodeInfo, target: NodeInfo): ComponentScores {
        val sourceTexts = semanticTexts(source)
        val targetTexts = semanticTexts(target)
        val sourceHasText = sourceTexts.isNotEmpty()
        val targetHasText = targetTexts.isNotEmpty()
        val bothHaveText = sourceHasText && targetHasText
        val text = semanticTextSimilarity(sourceTexts, targetTexts)
        val resource = resourceTailSimilarity(source, target)
        val classType = classSimilarity(source, target)
        val affordance = affordanceSimilarity(source, target)
        val structure = structureSimilarity(source, target)
        val prominence = prominenceSimilarity(source, target)
        val state = stateSimilarity(source, target)
        val resourceExact = source.resourceTail.isNotBlank() && source.resourceTail == target.resourceTail
        val textConflict = bothHaveText && text < 0.30f

        val score = when {
            text >= 0.98f -> (
                0.78f * text +
                    0.07f * classType +
                    0.06f * affordance +
                    0.04f * structure +
                    0.03f * resource +
                    0.02f * prominence
                )
            bothHaveText && text >= 0.55f -> (
                0.55f * text +
                    0.16f * resource +
                    0.10f * classType +
                    0.09f * affordance +
                    0.06f * structure +
                    0.04f * prominence
                )
            textConflict && resourceExact -> minOf(
                0.48f,
                0.42f * resource +
                    0.18f * classType +
                    0.20f * affordance +
                    0.12f * structure +
                    0.08f * prominence
            )
            textConflict -> minOf(
                0.42f,
                0.42f * resource +
                    0.18f * classType +
                    0.20f * affordance +
                    0.12f * structure +
                    0.08f * prominence
            )
            else -> (
                0.42f * resource +
                    0.16f * classType +
                    0.18f * affordance +
                    0.14f * structure +
                    0.08f * prominence +
                    0.02f * state
                )
        }.coerceIn(0f, 1f)

        return ComponentScores(
            score = score,
            text = text,
            resource = resource,
            classType = classType,
            affordance = affordance,
            structure = structure,
            prominence = prominence,
            state = state,
            textConflict = textConflict,
            resourceExact = resourceExact,
            sourceHasText = sourceHasText,
            targetHasText = targetHasText,
        )
    }

    fun match(
        src: NodeInfo, srcVec: FloatArray,
        candidates: List<NodeInfo>, candidateVecs: List<FloatArray>,
        anchors: List<Anchor>,
        srcDiagonal: Float,
        pageDiagonal: Float, scaleX: Float, scaleY: Float,
    ): MatchResult {
        if (candidates.isEmpty()) return MatchResult(
            index = -1, abstain = true, pBest = 0f, pNull = 1f, confidence = 0f,
            mode = "no_candidates", debug = emptyMap(),
        )

        val usableAnchors = anchors.filterNot { isSameElement(src, it.src) }
        val hasAnchorProjection = usableAnchors.isNotEmpty()
        val scoredCandidates = ArrayList<CandidateScore>(candidates.size)
        for (ti in candidates.indices) {
            val cand = candidates[ti]
            val vectorSimilarity = cosine(srcVec, candidateVecs[ti]).coerceIn(0f, 1f)
            val identitySimilarity = identitySimilarity(src, cand)
            val componentScores = simComponents(src, cand)
            val actionSimilarity = componentScores.score.coerceIn(0f, 1f)
            val contributions = anchorContributions(
                src = src,
                candidate = cand,
                anchors = usableAnchors,
                pageDiagonal = pageDiagonal,
                scaleX = scaleX,
                scaleY = scaleY,
            )
            val transferScore = aggregateTransferScore(contributions)
            val totalWeight = if (hasAnchorProjection) {
                ACTION_SIMILARITY_WEIGHT + TRANSFER_SUPPORT_WEIGHT
            } else {
                ACTION_SIMILARITY_WEIGHT
            }.coerceAtLeast(1e-6f)
            val matchScore = if (hasAnchorProjection) {
                ((ACTION_SIMILARITY_WEIGHT * actionSimilarity) +
                    (TRANSFER_SUPPORT_WEIGHT * transferScore)) / totalWeight
            } else {
                actionSimilarity
            }
            scoredCandidates += CandidateScore(
                index = ti,
                vectorSimilarity = vectorSimilarity,
                identitySimilarity = identitySimilarity,
                componentScores = componentScores,
                actionSimilarity = actionSimilarity,
                transferScore = transferScore,
                matchScore = matchScore.coerceIn(0f, 1f),
                anchorVoteCount = contributions.count { it.vote > 1e-4f }.toFloat(),
                contributions = contributions,
            )
        }

        val allLogits = scoredCandidates.map { it.matchScore * MATCH_PROBABILITY_SCALE }
            .toMutableList()
            .also { it += MIN_MATCH_SUPPORT * MATCH_PROBABILITY_SCALE }
        val probs = softmax(allLogits)
        val best = scoredCandidates.maxWithOrNull(
            compareBy<CandidateScore> { it.matchScore }.thenByDescending { it.index }
        )
        val bestIdx = best?.index ?: -1
        val bestScore = best?.matchScore ?: 0f
        val secondScore = scoredCandidates
            .filter { it.index != bestIdx }
            .maxOfOrNull { it.matchScore } ?: 0f
        val margin = bestScore - secondScore
        val pBest = if (bestIdx >= 0) probs[bestIdx] else 0f
        val pNull = probs.last()
        val gate = voteGate(best, margin, hasAnchorProjection, pBest, pNull)
        val abstain = bestIdx < 0 || gate["decision"] != "execute"

        return MatchResult(
            index = bestIdx,
            abstain = abstain,
            pBest = pBest, pNull = pNull,
            confidence = entropyConfidence(probs),
            mode = if (abstain) "anchor_vote_abstain" else "omniflow_anchor_vote",
            debug = mapOf(
                "algorithm" to "omniflow_anchor_vote",
                "p_best" to pBest, "p_null" to pNull,
                "best_match_score" to bestScore,
                "vote_margin" to margin,
                "gate" to gate,
                "best_vector_cosine" to (best?.vectorSimilarity ?: 0f),
                "best_explicit_score_components" to best?.componentScores?.toDebugMap(),
                "best_action_similarity" to (best?.actionSimilarity ?: 0f),
                "best_transfer_score" to (best?.transferScore ?: 0f),
                "best_anchor_vote_count" to (best?.anchorVoteCount ?: 0f),
                "anchor_count" to anchors.size,
                "usable_anchor_count" to usableAnchors.size,
                "candidate_count" to candidates.size,
                "semantic_weight" to if (hasAnchorProjection) ACTION_SIMILARITY_WEIGHT else 1f,
                "geometric_weight" to if (hasAnchorProjection) TRANSFER_SUPPORT_WEIGHT else 0f,
                "anchor_node_votes" to anchorNodeVoteDebug(scoredCandidates, candidates),
                "top" to scoredCandidates.sortedByDescending { it.matchScore }.take(3).map {
                    candidateDebugEntry(it, probs.getOrNull(it.index) ?: 0f)
                },
            ),
        )
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Preprocess text as Python TextHashEncoder does:
     * lowercase → remove whitespace → remove non-word/non-Chinese → truncate to 10.
     */
    internal fun textPreprocess(text: String): String =
        text.lowercase()
            .replace(Regex("\\s+"), "")
            .replace(TEXT_CLEAN_REGEX, "")
            .take(TEXT_TRUNCATE_LEN)

    /**
     * MD5 bigram hash into result[offset..offset+dim], then L2-normalize.
     * Matches Python TextHashEncoder._encode_clean_text(): each component is
     * independently normalized before being placed into the 64-dim vector.
     */
    internal fun bigramHashNormalizedInto(result: FloatArray, offset: Int, dim: Int, text: String) {
        if (text.length < 2 || dim == 0) return
        val tmp = FloatArray(dim)
        for (i in 0 until text.length - 1) {
            val hash = md5(text.substring(i, i + 2))
            val bucket = (hash[0].toInt() and 0xff) % dim
            val sign = if ((hash[1].toInt() and 0xff) % 2 == 0) 1f else -1f
            tmp[bucket] += sign
        }
        val norm = l2norm(tmp)
        if (norm > 1e-6f) for (i in tmp.indices) result[offset + i] = tmp[i] / norm
    }

    private fun l2normalizeSegment(vec: FloatArray, from: Int, to: Int, weight: Float) {
        var sum = 0.0
        for (i in from until to) sum += vec[i] * vec[i]
        val norm = sqrt(sum).toFloat()
        if (norm > 1e-6f) for (i in from until to) vec[i] = vec[i] / norm * weight
    }

    private fun anchorContributions(
        src: NodeInfo,
        candidate: NodeInfo,
        anchors: List<Anchor>,
        pageDiagonal: Float,
        scaleX: Float,
        scaleY: Float,
    ): List<AnchorContribution> {
        if (anchors.isEmpty()) return emptyList()
        val norm = pageDiagonal.coerceAtLeast(1f)
        return anchors.mapIndexed { index, anchor ->
            val predX = anchor.tgt.centerX + (src.centerX - anchor.src.centerX) * scaleX
            val predY = anchor.tgt.centerY + (src.centerY - anchor.src.centerY) * scaleY
            val nd = dis(predX, predY, candidate.centerX, candidate.centerY, norm)
            val geometricSupport = exp(
                -(nd * nd) / (2.0 * GEOMETRIC_SIGMA * GEOMETRIC_SIGMA)
            ).toFloat().coerceIn(0f, 1f)
            val vote = (anchor.sim.coerceIn(0f, 1f) * geometricSupport).coerceIn(0f, 1f)
            AnchorContribution(
                anchorIndex = index,
                vote = vote,
                anchorSimilarity = anchor.sim,
                geometricSupport = geometricSupport,
                normalizedDistance = nd,
                projectedX = predX,
                projectedY = predY,
                sourceAnchor = anchor.src,
                targetAnchor = anchor.tgt,
            )
        }.sortedByDescending { it.vote }
    }

    private fun aggregateTransferScore(contributions: List<AnchorContribution>): Float {
        if (contributions.isEmpty()) return 0f
        var missProbability = 1f
        for (vote in contributions.take(12).map { it.vote.coerceIn(0f, 1f) }) {
            missProbability *= (1f - vote)
        }
        return (1f - missProbability).coerceIn(0f, 1f)
    }

    private fun voteGate(
        best: CandidateScore?,
        margin: Float,
        hasAnchorProjection: Boolean,
        pBest: Float,
        pNull: Float,
    ): Map<String, Any?> {
        if (best == null) {
            return mapOf(
                "decision" to "abstain",
                "reason" to "no_candidate",
                "support" to 0f,
                "margin" to 0f,
                "action_similarity" to 0f,
                "anchor_vote_count" to 0f,
            )
        }
        val decision: String
        val reason: String
        val highActionSimilarity = best.actionSimilarity >= MIN_HIGH_ACTION_SIMILARITY_WITHOUT_ANCHOR
        when {
            best.matchScore < MIN_MATCH_SUPPORT -> {
                decision = "abstain"; reason = "low_support"
            }
            best.componentScores.textConflict && !highActionSimilarity -> {
                decision = "abstain"; reason = "semantic_label_conflict"
            }
            margin < MIN_MATCH_MARGIN && !highActionSimilarity -> {
                decision = "abstain"; reason = "low_margin"
            }
            pBest < MIN_DIFFUSE_BEST_PROBABILITY && !highActionSimilarity -> {
                decision = "abstain"; reason = "diffuse_probability"
            }
            best.actionSimilarity < MIN_ACTION_SIMILARITY -> {
                decision = "abstain"; reason = "low_action_similarity"
            }
            hasAnchorProjection &&
                best.anchorVoteCount < MIN_ANCHOR_VOTES &&
                best.actionSimilarity < MIN_HIGH_ACTION_SIMILARITY_WITHOUT_ANCHOR -> {
                decision = "abstain"; reason = "low_anchor_votes"
            }
            pBest <= pNull -> {
                decision = "abstain"; reason = "null_prior"
            }
            else -> {
                decision = "execute"; reason = "ok"
            }
        }
        return mapOf(
            "decision" to decision,
            "reason" to reason,
            "support" to best.matchScore,
            "margin" to margin,
            "action_similarity" to best.actionSimilarity,
            "transfer_score" to best.transferScore,
            "anchor_vote_count" to best.anchorVoteCount,
            "p_best" to pBest,
            "p_null" to pNull,
            "min_support" to MIN_MATCH_SUPPORT,
            "min_margin" to MIN_MATCH_MARGIN,
            "min_diffuse_best_probability" to MIN_DIFFUSE_BEST_PROBABILITY,
            "min_action_similarity" to MIN_ACTION_SIMILARITY,
            "min_anchor_votes" to if (hasAnchorProjection) MIN_ANCHOR_VOTES else 0f,
            "min_high_action_similarity_without_anchor" to
                if (hasAnchorProjection) MIN_HIGH_ACTION_SIMILARITY_WITHOUT_ANCHOR else 0f,
        )
    }

    private fun anchorNodeVoteDebug(
        scores: List<CandidateScore>,
        candidates: List<NodeInfo>,
    ): List<Map<String, Any?>> {
        val anchorIndexes = scores
            .flatMap { score -> score.contributions.map { it.anchorIndex } }
            .distinct()
            .sorted()
        return anchorIndexes.mapNotNull { anchorIndex ->
            val votes = scores.mapNotNull { score ->
                score.contributions
                    .firstOrNull { it.anchorIndex == anchorIndex }
                    ?.let { contribution -> score to contribution }
            }.sortedByDescending { it.second.vote }
            val best = votes.firstOrNull() ?: return@mapNotNull null
            val bestScore = best.first
            val bestContribution = best.second
            mapOf(
                "anchor_index" to anchorIndex,
                "selected_candidate_index" to bestScore.index,
                "selected_vote" to bestContribution.vote,
                "selected_candidate" to summarizeMatcherNode(candidates[bestScore.index]),
                "projected_source_node_center" to mapOf(
                    "x" to bestContribution.projectedX,
                    "y" to bestContribution.projectedY,
                ),
                "anchor_similarity" to bestContribution.anchorSimilarity,
                "source_anchor" to summarizeMatcherNode(bestContribution.sourceAnchor),
                "target_anchor" to summarizeMatcherNode(bestContribution.targetAnchor),
                "candidate_votes" to votes.take(3).map { (score, contribution) ->
                    mapOf(
                        "candidate_index" to score.index,
                        "vote" to contribution.vote,
                        "geometric_support" to contribution.geometricSupport,
                        "normalized_distance" to contribution.normalizedDistance,
                    )
                },
            )
        }.take(8)
    }

    private fun candidateDebugEntry(score: CandidateScore, probability: Float): Map<String, Any?> =
        mapOf(
            "i" to score.index,
            "probability" to probability,
            "match_score" to score.matchScore,
            "vector_similarity" to score.vectorSimilarity,
            "identity_similarity" to score.identitySimilarity,
            "explicit_score_components" to score.componentScores.toDebugMap(),
            "action_similarity" to score.actionSimilarity,
            "transfer_score" to score.transferScore,
            "anchor_vote_count" to score.anchorVoteCount,
            "top_anchor_contributions" to score.contributions.take(3).map { contribution ->
                mapOf(
                    "anchor_index" to contribution.anchorIndex,
                    "vote" to contribution.vote,
                    "anchor_similarity" to contribution.anchorSimilarity,
                    "geometric_support" to contribution.geometricSupport,
                    "normalized_distance" to contribution.normalizedDistance,
                    "projected" to mapOf(
                        "x" to contribution.projectedX,
                        "y" to contribution.projectedY,
                    ),
                    "source_anchor" to summarizeMatcherNode(contribution.sourceAnchor),
                    "target_anchor" to summarizeMatcherNode(contribution.targetAnchor),
                )
            },
        )

    private fun summarizeMatcherNode(node: NodeInfo): Map<String, Any?> =
        mapOf(
            "resource_tail" to node.resourceTail,
            "text" to node.text,
            "content_desc" to node.contentDesc,
            "class_suffix" to node.classSuffix,
            "center" to mapOf("x" to node.centerX, "y" to node.centerY),
        )

    private fun isSameElement(a: NodeInfo, b: NodeInfo): Boolean {
        if (a.resourceId.isNotBlank() && a.resourceId == b.resourceId) return true
        val sameCenter = kotlin.math.abs(a.centerX - b.centerX) < 1f &&
            kotlin.math.abs(a.centerY - b.centerY) < 1f
        if (!sameCenter) return false
        return a.classSuffix == b.classSuffix &&
            a.text == b.text &&
            a.contentDesc == b.contentDesc &&
            a.hintText == b.hintText
    }

    private fun identitySimilarity(source: NodeInfo, target: NodeInfo): Float {
        val fields = listOf(
            source.resourceId to target.resourceId,
            source.text to target.text,
            source.contentDesc to target.contentDesc,
            source.hintText to target.hintText,
        ).filter { (left, right) -> left.isNotBlank() || right.isNotBlank() }
        if (fields.isEmpty()) return 0.5f
        var score = 0f
        for ((left, right) in fields) {
            score += when {
                left.isBlank() || right.isBlank() -> 0.5f
                left == right -> 1f
                else -> 0f
            }
        }
        return (score / fields.size).coerceIn(0f, 1f)
    }

    internal fun dis(
        sourceX: Float,
        sourceY: Float,
        targetX: Float,
        targetY: Float,
        norm: Float,
    ): Float = (hypot(sourceX - targetX, sourceY - targetY) / norm.coerceAtLeast(1f))
        .coerceAtLeast(0f)

    private fun semanticTextSimilarity(sourceTexts: List<String>, targetTexts: List<String>): Float {
        if (sourceTexts.isEmpty() || targetTexts.isEmpty()) return 0f
        return sourceTexts.maxOf { left ->
            targetTexts.maxOf { right -> normalizedTextSimilarity(left, right) }
        }.coerceIn(0f, 1f)
    }

    private fun semanticTexts(node: NodeInfo): List<String> =
        listOf(node.text, node.contentDesc, node.hintText)
            .map(::normalizeSemanticText)
            .filter { it.isNotBlank() }
            .distinct()

    private fun normalizedTextSimilarity(left: String, right: String): Float {
        if (left.isBlank() || right.isBlank()) return 0f
        if (left == right) return 1f
        if (left.contains(right) || right.contains(left)) {
            val shorter = minOf(left.length, right.length).toFloat()
            val longer = maxOf(left.length, right.length).toFloat().coerceAtLeast(1f)
            return (0.70f + 0.25f * (shorter / longer)).coerceIn(0f, 0.95f)
        }
        val leftTokens = semanticTokens(left)
        val rightTokens = semanticTokens(right)
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0f
        val overlap = leftTokens.intersect(rightTokens).size.toFloat()
        val union = leftTokens.union(rightTokens).size.toFloat().coerceAtLeast(1f)
        return (overlap / union * 0.88f).coerceIn(0f, 0.88f)
    }

    private fun resourceTailSimilarity(source: NodeInfo, target: NodeInfo): Float {
        val left = source.resourceTail
        val right = target.resourceTail
        if (left.isBlank() || right.isBlank()) return 0f
        if (left == right) return if (isGenericResourceTail(left)) 0.35f else 1f
        val leftTokens = resourceTokens(left)
        val rightTokens = resourceTokens(right)
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0f
        val overlap = leftTokens.intersect(rightTokens).size.toFloat()
        if (overlap <= 0f) return 0f
        val union = leftTokens.union(rightTokens).size.toFloat().coerceAtLeast(1f)
        val smaller = minOf(leftTokens.size, rightTokens.size).toFloat().coerceAtLeast(1f)
        val jaccard = overlap / union
        val coverage = overlap / smaller
        return (0.20f * jaccard + 0.75f * coverage).coerceIn(0f, 0.90f)
    }

    private fun classSimilarity(source: NodeInfo, target: NodeInfo): Float = when {
        source.classSuffix == target.classSuffix -> 1f
        source.classSuffix.isButtonLike() && target.classSuffix.isButtonLike() -> 0.7f
        source.classSuffix.isEditLike() && target.classSuffix.isEditLike() -> 0.7f
        source.classSuffix.isScrollLike() && target.classSuffix.isScrollLike() -> 0.7f
        source.classSuffix.isToggleLike() && target.classSuffix.isToggleLike() -> 0.7f
        source.clickable && target.clickable -> 0.45f
        source.focusable && target.focusable -> 0.35f
        else -> 0f
    }

    private fun affordanceSimilarity(source: NodeInfo, target: NodeInfo): Float {
        val pairs = listOf(
            source.clickable to target.clickable,
            source.longClickable to target.longClickable,
            source.focusable to target.focusable,
            source.editable to target.editable,
            source.scrollable to target.scrollable,
            source.checkable to target.checkable,
        )
        var score = 0f
        for ((left, right) in pairs) {
            score += when {
                left == right -> 1f
                left || right -> 0f
                else -> 1f
            }
        }
        return (score / pairs.size.coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    private fun structureSimilarity(source: NodeInfo, target: NodeInfo): Float {
        if (source.structSignature.isNotBlank() && source.structSignature == target.structSignature) {
            return 1f
        }
        val leaf = if (source.isLeaf == target.isLeaf) 1f else 0f
        val siblings = if (source.hasSiblings == target.hasSiblings) 1f else 0f
        val depthDelta = kotlin.math.abs(source.depth - target.depth)
        val depth = (1f - depthDelta / 6f).coerceIn(0f, 1f)
        val classFamily = classSimilarity(source, target)
        return (
            0.32f * leaf +
                0.18f * siblings +
                0.28f * depth +
                0.22f * classFamily
            ).coerceIn(0f, 1f)
    }

    private fun prominenceSimilarity(source: NodeInfo, target: NodeInfo): Float {
        val sourceArea = source.areaRatio.coerceAtLeast(1e-6f)
        val targetArea = target.areaRatio.coerceAtLeast(1e-6f)
        val area = minOf(sourceArea, targetArea) / maxOf(sourceArea, targetArea)
        val sourceBucket = areaBucket(source.areaRatio)
        val targetBucket = areaBucket(target.areaRatio)
        val bucket = when (kotlin.math.abs(sourceBucket - targetBucket)) {
            0 -> 1f
            1 -> 0.65f
            else -> 0.25f
        }
        return (0.55f * area + 0.45f * bucket).coerceIn(0f, 1f)
    }

    private fun stateSimilarity(source: NodeInfo, target: NodeInfo): Float {
        val pairs = listOf(
            source.enabled to target.enabled,
            source.selected to target.selected,
            source.focused to target.focused,
        )
        return pairs.count { it.first == it.second }.toFloat() / pairs.size.toFloat()
    }

    private fun areaBucket(areaRatio: Float): Int = when {
        areaRatio < 0.005f -> 0
        areaRatio < 0.02f -> 1
        areaRatio < 0.08f -> 2
        else -> 3
    }

    private fun normalizeSemanticText(text: String): String =
        text.trim()
            .lowercase()
            .replace(Regex("\\s+"), " ")

    private fun semanticTokens(text: String): Set<String> =
        text.split(Regex("""[\s,，:：;；/|()\[\]{}<>._-]+"""))
            .map(::normalizeSemanticText)
            .filter { it.length >= 2 }
            .filterNot { it in GENERIC_SEMANTIC_TOKENS }
            .toSet()

    private fun resourceTokens(text: String): Set<String> =
        text.split(Regex("""[^a-zA-Z0-9\u4e00-\u9fff]+"""))
            .map { it.lowercase() }
            .filter { it.length >= 2 }
            .filterNot { it in GENERIC_RESOURCE_TOKENS }
            .toSet()

    private fun isGenericResourceTail(tail: String): Boolean =
        tail.lowercase() in GENERIC_RESOURCE_TOKENS

    private fun entropyConfidence(probs: List<Float>): Float {
        var entropy = 0.0
        for (p in probs) if (p > 0f) entropy -= p * ln(p.toDouble())
        val maxEntropy = ln(probs.size.toDouble())
        return if (maxEntropy > 0) (1.0 - entropy / maxEntropy).toFloat().coerceIn(0f, 1f) else 1f
    }

    internal fun softmax(logits: List<Float>): List<Float> {
        val maxLogit = logits.maxOrNull() ?: 0f
        val exps = logits.map { exp((it - maxLogit).toDouble()).toFloat() }
        val sum = exps.sum().coerceAtLeast(1e-12f)
        return exps.map { it / sum }
    }

    internal fun logSumExp(values: List<Float>): Float {
        if (values.isEmpty()) return Float.NEGATIVE_INFINITY
        val maxVal = values.maxOrNull() ?: return Float.NEGATIVE_INFINITY
        var sumExp = 0.0
        for (v in values) sumExp += exp((v - maxVal).toDouble())
        return (maxVal + ln(sumExp)).toFloat()
    }

    private fun l2norm(v: FloatArray): Float {
        var s = 0.0; for (x in v) s += x * x; return sqrt(s).toFloat()
    }

    private fun hypot(dx: Float, dy: Float): Float = sqrt((dx * dx + dy * dy).toDouble()).toFloat()

    private fun firstNonBlank(vararg values: String) = values.firstOrNull { it.isNotBlank() }.orEmpty()

    private fun md5(text: String): ByteArray =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))

    private fun String.isButtonLike() = contains("button", true) || contains("fab", true) || contains("chip", true) || contains("card", true)
    private fun String.isEditLike() = contains("edittext", true) || contains("textinput", true) || contains("searchview", true) || contains("autocomplete", true)
    private fun String.isScrollLike() = contains("recyclerview", true) || contains("listview", true) || contains("scrollview", true) || contains("gridview", true) || contains("viewpager", true)
    private fun String.isToggleLike() = contains("switch", true) || contains("checkbox", true) || contains("radiobutton", true) || contains("togglebutton", true)

    private val ACTION_ID_PREFIXES = listOf("btn_", "button_", "ib_", "fab_", "action_")
    private val INPUT_ID_PREFIXES = listOf("et_", "edit_", "input_", "search_", "txt_")
    private val GENERIC_SEMANTIC_TOKENS = setOf(
        "button", "view", "text", "image", "layout", "item", "container", "content",
        "android", "widget", "com", "id",
    )
    private val GENERIC_RESOURCE_TOKENS = GENERIC_SEMANTIC_TOKENS + setOf(
        "btn", "fab", "iv", "tv", "ll", "rl", "fl", "root", "icon", "label",
        "action", "menu", "accessibility", "button", "toggle", "mode",
    )

    // ── Backward compat stubs (used by tests / legacy callers) ────────────────
    @Deprecated("Use vector()", ReplaceWith("vector(node)"))
    fun elementVector(node: NodeInfo): FloatArray = vector(node)

    data class MatcherAnchor(
        val sourceCenterX: Float, val sourceCenterY: Float,
        val targetCenterX: Float, val targetCenterY: Float,
        val similarity: Float,
    )

    @Deprecated("Use match() with Anchor list")
    fun matchBayesian(
        sourceNode: NodeInfo, sourceVec: FloatArray,
        candidates: List<NodeInfo>, candidateVecs: List<FloatArray>,
        anchors: List<MatcherAnchor>,
        pageDiagonal: Float, scaleX: Float, scaleY: Float,
    ) = match(
        src = sourceNode, srcVec = sourceVec,
        candidates = candidates, candidateVecs = candidateVecs,
        anchors = anchors.map { a ->
            Anchor(
                src = sourceNode.copy(centerX = a.sourceCenterX, centerY = a.sourceCenterY),
                tgt = sourceNode.copy(centerX = a.targetCenterX, centerY = a.targetCenterY),
                sim = a.similarity,
            )
        },
        srcDiagonal = pageDiagonal,
        pageDiagonal = pageDiagonal, scaleX = scaleX, scaleY = scaleY,
    )
}
