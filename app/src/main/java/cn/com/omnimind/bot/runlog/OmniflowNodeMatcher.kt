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
 * Bayesian matching (matches Python probabilistic_matching.py):
 *   - Geometric term: normalized Gaussian mixture with proximity kernel on anchor weights
 *   - φ(s,a) = sim² × exp(−d_pixel²/2σ² − λ·d_tree)
 *   - Bayes risk gate + entropy-based confidence
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

    // ── Bayesian parameters ───────────────────────────────────────────────────
    const val SEMANTIC_TEMPERATURE = 0.05f
    const val GEOMETRIC_SIGMA = 0.15f
    const val IDENTITY_MATCH_LOGIT = 2.5f
    const val IDENTITY_MISMATCH_LOGIT = -1.0f
    const val NULL_PRIOR_LOGIT = 1.6f
    const val WRONG_CLICK_LOSS = 1.5f
    const val ABSTAIN_LOSS = 1.0f
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

    data class MatchResult(
        val index: Int,
        val abstain: Boolean,
        val pBest: Float,
        val pNull: Float,
        val confidence: Float,
        val mode: String,
        val debug: Map<String, Any?>,
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
            FloatArray(tgtNodes.size) { ti -> cosine(srcVecs[si], tgtVecs[ti]) }
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

        val logAnchorWeights = if (anchors.isNotEmpty()) {
            val norm = srcDiagonal.coerceAtLeast(1f)
            val rawW = FloatArray(anchors.size) { k ->
                val a = anchors[k]
                val dPixel = hypot(src.centerX - a.src.centerX, src.centerY - a.src.centerY) / norm
                val dTree = kotlin.math.abs(src.depth - a.src.depth).toFloat()
                val phi = exp(
                    -(dPixel * dPixel) / (2.0 * ANCHOR_LOCAL_SIGMA * ANCHOR_LOCAL_SIGMA)
                    - ANCHOR_TREE_LAMBDA * dTree
                ).toFloat()
                a.sim * a.sim * phi
            }
            val wSum = rawW.sum().coerceAtLeast(1e-10f)
            FloatArray(anchors.size) { ln((rawW[it] / wSum).toDouble() + 1e-10).toFloat() }
        } else FloatArray(0)

        val logPosts = FloatArray(candidates.size)
        val debugEntries = ArrayList<Map<String, Any?>>(candidates.size)

        for (ti in candidates.indices) {
            val cand = candidates[ti]
            val sim = cosine(srcVec, candidateVecs[ti]).coerceIn(-1f, 1f)
            val logSem = sim / SEMANTIC_TEMPERATURE
            val logGeo = if (anchors.isEmpty()) {
                -ln(candidates.size.toFloat())
            } else {
                val logTerms = anchors.indices.map { k ->
                    val a = anchors[k]
                    val predX = a.tgt.centerX + (src.centerX - a.src.centerX) * scaleX
                    val predY = a.tgt.centerY + (src.centerY - a.src.centerY) * scaleY
                    val nd = hypot(predX - cand.centerX, predY - cand.centerY) / pageDiagonal.coerceAtLeast(1f)
                    -(nd * nd) / (2f * GEOMETRIC_SIGMA * GEOMETRIC_SIGMA) + logAnchorWeights[k]
                }
                logSumExp(logTerms)
            }
            val logId = identityLogit(src, cand)
            val logPost = logSem + logGeo + logId
            logPosts[ti] = logPost
            debugEntries += mapOf(
                "i" to ti, "cosine" to sim,
                "log_sem" to logSem, "log_geo" to logGeo, "log_id" to logId,
                "log_post" to logPost,
            )
        }

        val allLogits = logPosts.toMutableList().also { it += NULL_PRIOR_LOGIT }
        val probs = softmax(allLogits)
        val bestIdx = logPosts.indices.maxByOrNull { logPosts[it] } ?: -1
        val pBest = if (bestIdx >= 0) probs[bestIdx] else 0f
        val pNull = probs.last()
        val riskExecute = WRONG_CLICK_LOSS * (1f - pBest)
        val riskAbstain = ABSTAIN_LOSS * (1f - pNull)
        val abstain = bestIdx < 0 || riskExecute >= riskAbstain

        return MatchResult(
            index = if (abstain) -1 else bestIdx,
            abstain = abstain,
            pBest = pBest, pNull = pNull,
            confidence = entropyConfidence(probs),
            mode = if (abstain) "bayesian_abstain" else "omniflow_bayesian",
            debug = mapOf(
                "algorithm" to "omniflow_bayesian",
                "p_best" to pBest, "p_null" to pNull,
                "risk_execute" to riskExecute, "risk_abstain" to riskAbstain,
                "best_cosine" to ((debugEntries.getOrNull(bestIdx)?.get("cosine") as? Float) ?: 0f),
                "anchor_count" to anchors.size, "candidate_count" to candidates.size,
                "top" to debugEntries.sortedByDescending { (it["log_post"] as? Float) ?: 0f }.take(3),
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

    private fun identityLogit(source: NodeInfo, target: NodeInfo): Float {
        var logit = 0f
        if (source.resourceId.isNotBlank()) {
            logit += if (source.resourceId == target.resourceId) IDENTITY_MATCH_LOGIT
            else if (target.resourceId.isNotBlank()) IDENTITY_MISMATCH_LOGIT else 0f
        }
        val sh = stableHash(source.text); val th = stableHash(target.text)
        if (sh != null) logit += if (sh == th) IDENTITY_MATCH_LOGIT else if (th != null) IDENTITY_MISMATCH_LOGIT else 0f
        val sd = stableHash(source.contentDesc); val td = stableHash(target.contentDesc)
        if (sd != null) logit += if (sd == td) IDENTITY_MATCH_LOGIT else if (td != null) IDENTITY_MISMATCH_LOGIT else 0f
        return logit
    }

    private fun stableHash(text: String): String? {
        if (text.isBlank()) return null
        return md5(text.lowercase().trim()).take(4).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

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
