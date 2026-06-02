package cn.com.omnimind.bot.runlog

import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Local, model-free implementation of OmniFlow's Bayesian node matching algorithm.
 *
 * Mirrors the Python probabilistic_matching.py design:
 *   - 64-dim element vectors: Human view (32-dim, w=0.55) + Programmer view (32-dim, w=0.45)
 *   - Text encoded via character 2-gram signed hash projection (same as Python)
 *   - Bayesian posterior: semantic × geometric × identity, vs null prior
 *   - Bayes risk gate: execute only when P(wrong_click) < P(abstain)
 *
 * All constants ported directly from the Python implementation.
 */
internal object OmniflowNodeMatcher {

    // ── Dimensions & weights ──────────────────────────────────────────────────
    const val ELEMENT_DIM = 64
    const val HUMAN_DIM = 32
    const val PROGRAMMER_DIM = 32
    const val HUMAN_WEIGHT = 0.55f
    const val PROGRAMMER_WEIGHT = 0.45f

    // ── Bayesian parameters (from Python probabilistic_matching.py) ───────────
    const val SEMANTIC_TEMPERATURE = 0.05f   // τ: sharpens semantic distribution
    const val GEOMETRIC_SIGMA = 0.15f        // σ: normalized-distance Gaussian std
    const val IDENTITY_MATCH_LOGIT = 2.5f    // +logit when id/text hash matches
    const val IDENTITY_MISMATCH_LOGIT = -1.0f // −logit when field present but differs
    const val NULL_PRIOR_LOGIT = 1.6f        // prior log-odds of "no valid target"

    // ── Risk gate loss ratios ─────────────────────────────────────────────────
    const val WRONG_CLICK_LOSS = 1.5f  // cost of executing on wrong element
    const val ABSTAIN_LOSS = 1.0f      // cost of abstaining

    // ── Anchor finding ────────────────────────────────────────────────────────
    const val MIN_ANCHOR_SIMILARITY = 0.5f
    const val MAX_ANCHOR_COUNT = 5

    // ── Data structures ───────────────────────────────────────────────────────

    data class NodeInfo(
        val resourceId: String,
        val resourceTail: String,
        val text: String,
        val contentDesc: String,
        val hintText: String,
        val classSuffix: String,
        val clickable: Boolean,
        val focusable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean,
        val checkable: Boolean,
        val enabled: Boolean,
        val selected: Boolean,
        val areaRatio: Float,   // element area / root area, pre-computed by caller
        val centerX: Float,
        val centerY: Float,
    )

    data class MatcherAnchor(
        val sourceCenterX: Float,
        val sourceCenterY: Float,
        val targetCenterX: Float,
        val targetCenterY: Float,
        val similarity: Float,
    )

    data class BayesianMatchResult(
        val index: Int,          // index into candidates list; -1 when abstaining
        val abstain: Boolean,
        val pBest: Float,
        val pNull: Float,
        val confidence: Float,
        val mode: String,
        val debug: Map<String, Any?>,
    )

    // ── Element vector ────────────────────────────────────────────────────────

    fun elementVector(node: NodeInfo): FloatArray {
        val human = FloatArray(HUMAN_DIM)
        humanView(node, human)

        val prog = FloatArray(PROGRAMMER_DIM)
        programmerView(node, prog)

        val normH = l2norm(human).coerceAtLeast(1e-6f)
        val normP = l2norm(prog).coerceAtLeast(1e-6f)

        val result = FloatArray(ELEMENT_DIM)
        for (i in 0 until HUMAN_DIM) result[i] = human[i] / normH * HUMAN_WEIGHT
        for (i in 0 until PROGRAMMER_DIM) result[HUMAN_DIM + i] = prog[i] / normP * PROGRAMMER_WEIGHT
        return result
    }

    private fun humanView(node: NodeInfo, v: FloatArray) {
        var off = 0

        // [0..15] content_hash — character 2-gram of best text
        val content = firstNonBlank(node.text, node.contentDesc, node.hintText)
        off = bigramHashInto(v, off, 16, content.lowercase().take(40))

        // [16..19] content_type — one-hot: text / icon / neither / both
        val hasText = content.isNotBlank()
        val isIcon = isIconLikeClass(node.classSuffix)
        v[off + when {
            hasText && isIcon -> 3
            hasText -> 0
            isIcon -> 1
            else -> 2
        }] = 1f
        off += 4

        // [20..23] affordance — tappable / typeable / scrollable / toggleable
        v[off] = if (node.clickable) 1f else 0f
        v[off + 1] = if (node.editable) 1f else 0f
        v[off + 2] = if (node.scrollable) 1f else 0f
        v[off + 3] = if (node.checkable) 1f else 0f
        off += 4

        // [24..27] prominence — one-hot area bucket
        v[off + when {
            node.areaRatio < 0.005f -> 0
            node.areaRatio < 0.02f -> 1
            node.areaRatio < 0.08f -> 2
            else -> 3
        }] = 1f
        off += 4

        // [28..31] visual_state — selected / disabled / typeable / primary_action
        v[off] = if (node.selected) 1f else 0f
        v[off + 1] = if (!node.enabled) 1f else 0f
        v[off + 2] = if (node.editable || node.focusable) 1f else 0f
        v[off + 3] = if (node.clickable && node.areaRatio >= 0.02f) 1f else 0f
    }

    private fun programmerView(node: NodeInfo, v: FloatArray) {
        var off = 0

        // [0..7] class_type — 2-gram of classSuffix
        off = bigramHashInto(v, off, 8, node.classSuffix.lowercase().take(20))

        // [8..15] attributes — binary interaction flags
        v[off] = if (node.clickable) 1f else 0f
        v[off + 1] = if (node.focusable) 1f else 0f
        v[off + 2] = if (node.editable) 1f else 0f
        v[off + 3] = if (node.scrollable) 1f else 0f
        v[off + 4] = if (node.checkable) 1f else 0f
        v[off + 5] = if (node.enabled) 1f else 0f
        v[off + 6] = 1f  // all matche candidates are visible by the time we reach here
        v[off + 7] = if (node.selected) 1f else 0f
        off += 8

        // [16..27] struct_hash — 2-gram of classSuffix (structural fingerprint)
        off = bigramHashInto(v, off, 12, node.classSuffix.lowercase().take(20))

        // [28..29] hierarchy
        v[off] = if (isLeafLikeClass(node.classSuffix)) 1f else 0f
        v[off + 1] = if (node.resourceId.isNotBlank()) 1f else 0f
        off += 2

        // [30..31] id_hint — resource tail semantics
        val tail = node.resourceTail.lowercase()
        v[off] = if (ACTION_ID_PREFIXES.any { tail.startsWith(it) }) 1f else 0f
        v[off + 1] = if (INPUT_ID_PREFIXES.any { tail.startsWith(it) }) 1f else 0f
    }

    // ── Cosine similarity ─────────────────────────────────────────────────────

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na <= 1e-12 || nb <= 1e-12) return 0f
        return (dot / sqrt(na * nb)).toFloat().coerceIn(-1f, 1f)
    }

    // ── Bayesian matching ─────────────────────────────────────────────────────

    /**
     * Scores all candidates via Bayesian posterior (semantic + geometric + identity)
     * and applies the Bayes risk gate to decide whether to execute or abstain.
     *
     * @param sourceNode    The recorded node to find in the current page.
     * @param sourceVec     Pre-computed element vector for sourceNode.
     * @param candidates    Candidate nodes in the current page (visible, enabled).
     * @param candidateVecs Pre-computed element vectors for candidates.
     * @param anchors       Mutual-best-match anchor pairs for geometric prediction.
     * @param pageDiagonal  Diagonal of the current page bounds (for normalising distances).
     * @param scaleX/scaleY Page scale ratio source→target.
     * @return              BayesianMatchResult with best candidate index or abstain flag.
     */
    fun matchBayesian(
        sourceNode: NodeInfo,
        sourceVec: FloatArray,
        candidates: List<NodeInfo>,
        candidateVecs: List<FloatArray>,
        anchors: List<MatcherAnchor>,
        pageDiagonal: Float,
        scaleX: Float,
        scaleY: Float,
    ): BayesianMatchResult {
        if (candidates.isEmpty()) {
            return BayesianMatchResult(
                index = -1, abstain = true,
                pBest = 0f, pNull = 1f, confidence = 0f,
                mode = "bayesian_no_candidates", debug = emptyMap()
            )
        }

        val logPosts = FloatArray(candidates.size)
        val debugEntries = mutableListOf<Map<String, Any?>>()

        for (ti in candidates.indices) {
            val candidate = candidates[ti]

            // Semantic: cosine / temperature
            val sim = cosine(sourceVec, candidateVecs[ti]).coerceIn(-1f, 1f)
            val logSem = sim / SEMANTIC_TEMPERATURE

            // Geometric: log-sum-exp of weighted Gaussians from each anchor
            val logGeo = if (anchors.isEmpty()) {
                -ln(candidates.size.toFloat().coerceAtLeast(1f))
            } else {
                val logTerms = anchors.map { a ->
                    val predX = a.targetCenterX + (sourceNode.centerX - a.sourceCenterX) * scaleX
                    val predY = a.targetCenterY + (sourceNode.centerY - a.sourceCenterY) * scaleY
                    val dist = hypot(predX - candidate.centerX, predY - candidate.centerY)
                    val nd = dist / pageDiagonal.coerceAtLeast(1f)
                    val logGauss = -(nd * nd) / (2f * GEOMETRIC_SIGMA * GEOMETRIC_SIGMA)
                    val weight = a.similarity * a.similarity
                    weight + logGauss
                }
                logSumExp(logTerms)
            }

            // Identity: exact field match evidence
            val logIdentity = identityLogit(sourceNode, candidate)
            val rawLogPost = logSem + logGeo + logIdentity
            val weakUnanchoredCandidate = (
                anchors.isEmpty() &&
                    sim < MIN_ANCHOR_SIMILARITY &&
                    logIdentity <= 0f
                )
            val logPost = if (weakUnanchoredCandidate) {
                rawLogPost.coerceAtMost(NULL_PRIOR_LOGIT - 0.5f)
            } else {
                rawLogPost
            }
            val unanchoredLowConfidencePenalty = logPost - rawLogPost

            logPosts[ti] = logPost
            debugEntries += mapOf(
                "i" to ti,
                "cosine" to sim,
                "log_sem" to logSem,
                "log_geo" to logGeo,
                "log_id" to logIdentity,
                "log_unanchored_low_confidence_penalty" to unanchoredLowConfidencePenalty,
                "log_post" to logPost,
            )
        }

        // Softmax over candidates + null option
        val allLogits = logPosts.toMutableList().also { it += NULL_PRIOR_LOGIT }
        val probs = softmax(allLogits)

        val bestIdx = logPosts.indices.maxByOrNull { logPosts[it] } ?: -1
        val pBest = if (bestIdx >= 0) probs[bestIdx] else 0f
        val pNull = probs.last()
        val bestCosine = (debugEntries.getOrNull(bestIdx)?.get("cosine") as? Float) ?: 0f
        val bestIdentityLogit = if (bestIdx >= 0) identityLogit(sourceNode, candidates[bestIdx]) else 0f
        val weakUnanchoredMatch = anchors.isEmpty() &&
            bestIdx >= 0 &&
            bestCosine < MIN_ANCHOR_SIMILARITY &&
            bestIdentityLogit <= 0f

        // Bayes risk gate: execute if expected cost of executing < expected cost of abstaining
        val riskExecute = WRONG_CLICK_LOSS * (1f - pBest)
        val riskAbstain = ABSTAIN_LOSS * (1f - pNull)
        val abstain = bestIdx < 0 || riskExecute >= riskAbstain || weakUnanchoredMatch

        return BayesianMatchResult(
            index = if (abstain) -1 else bestIdx,
            abstain = abstain,
            pBest = pBest,
            pNull = pNull,
            confidence = pBest,
            mode = if (abstain) "bayesian_abstain" else "omniflow_bayesian",
            debug = mapOf(
                "algorithm" to "omniflow_bayesian",
                "p_best" to pBest,
                "p_null" to pNull,
                "risk_execute" to riskExecute,
                "risk_abstain" to riskAbstain,
                "best_cosine" to bestCosine,
                "best_identity_logit" to bestIdentityLogit,
                "weak_unanchored_match" to weakUnanchoredMatch,
                "abstain" to abstain,
                "anchor_count" to anchors.size,
                "candidate_count" to candidates.size,
                "top" to debugEntries.sortedByDescending { (it["log_post"] as? Float) ?: 0f }.take(3),
            )
        )
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun identityLogit(source: NodeInfo, target: NodeInfo): Float {
        var logit = 0f
        // resourceId
        if (source.resourceId.isNotBlank()) {
            if (source.resourceId == target.resourceId) logit += IDENTITY_MATCH_LOGIT
            else if (target.resourceId.isNotBlank()) logit += IDENTITY_MISMATCH_LOGIT
        }
        // text hash
        val sh = stableHash(source.text)
        val th = stableHash(target.text)
        if (sh != null) {
            if (sh == th) logit += IDENTITY_MATCH_LOGIT
            else if (th != null) logit += IDENTITY_MISMATCH_LOGIT
        }
        // contentDesc hash
        val sd = stableHash(source.contentDesc)
        val td = stableHash(target.contentDesc)
        if (sd != null) {
            if (sd == td) logit += IDENTITY_MATCH_LOGIT
            else if (td != null) logit += IDENTITY_MISMATCH_LOGIT
        }
        return logit
    }

    private fun stableHash(text: String): String? {
        if (text.isBlank()) return null
        return sha256(text.lowercase().trim())
            .take(4)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    internal fun bigramHashInto(vector: FloatArray, offset: Int, dim: Int, text: String): Int {
        if (text.length < 2 || dim == 0) return offset + dim
        for (i in 0 until text.length - 1) {
            val hash = sha256(text.substring(i, i + 2))
            val bucket = (hash[0].toInt() and 0xff) % dim
            val sign = if ((hash[1].toInt() and 0xff) and 1 == 0) 1f else -1f
            vector[offset + bucket] += sign
        }
        return offset + dim
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
        var s = 0.0
        for (x in v) s += x * x
        return sqrt(s).toFloat()
    }

    private fun hypot(dx: Float, dy: Float): Float =
        sqrt((dx * dx + dy * dy).toDouble()).toFloat()

    private fun isIconLikeClass(classSuffix: String): Boolean =
        classSuffix.contains("image", ignoreCase = true) ||
            classSuffix.contains("icon", ignoreCase = true)

    private fun isLeafLikeClass(classSuffix: String): Boolean =
        classSuffix.contains("text", ignoreCase = true) ||
            classSuffix.contains("image", ignoreCase = true) ||
            classSuffix.contains("button", ignoreCase = true) ||
            classSuffix.contains("checkbox", ignoreCase = true)

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() }.orEmpty()

    private fun sha256(text: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

    private val ACTION_ID_PREFIXES = listOf("btn_", "button_", "ib_", "fab_", "action_")
    private val INPUT_ID_PREFIXES = listOf("et_", "edit_", "input_", "search_", "txt_")
}
