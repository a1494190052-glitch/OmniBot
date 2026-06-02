package cn.com.omnimind.bot.runlog

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniflowNodeMatcherTest {

    private fun node(
        resourceId: String = "",
        text: String = "",
        contentDesc: String = "",
        hintText: String = "",
        classSuffix: String = "TextView",
        clickable: Boolean = false,
        focusable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
        checkable: Boolean = false,
        enabled: Boolean = true,
        selected: Boolean = false,
        areaRatio: Float = 0.01f,
        centerX: Float = 100f,
        centerY: Float = 200f,
    ) = OmniflowNodeMatcher.NodeInfo(
        resourceId = resourceId,
        resourceTail = resourceId.substringAfterLast('/'),
        text = text,
        contentDesc = contentDesc,
        hintText = hintText,
        classSuffix = classSuffix,
        clickable = clickable,
        focusable = focusable,
        editable = editable,
        scrollable = scrollable,
        checkable = checkable,
        enabled = enabled,
        selected = selected,
        areaRatio = areaRatio,
        centerX = centerX,
        centerY = centerY,
    )

    // ── Element vector ────────────────────────────────────────────────────────

    @Test
    fun `elementVector outputs exactly 64 dimensions`() {
        val v = OmniflowNodeMatcher.elementVector(node(text = "hello"))
        assertEquals(OmniflowNodeMatcher.ELEMENT_DIM, v.size)
    }

    @Test
    fun `Human and Programmer contributions respect weight bounds`() {
        val n = node(resourceId = "com.foo/bar", text = "Search", classSuffix = "EditText",
            editable = true, clickable = true)
        val v = OmniflowNodeMatcher.elementVector(n)
        val human = v.slice(0 until OmniflowNodeMatcher.HUMAN_DIM)
        val prog = v.slice(OmniflowNodeMatcher.HUMAN_DIM until OmniflowNodeMatcher.ELEMENT_DIM)
        val normH = sqrt(human.sumOf { (it * it).toDouble() }).toFloat()
        val normP = sqrt(prog.sumOf { (it * it).toDouble() }).toFloat()
        // Each part is L2-normalised then scaled by weight, so norm ≤ weight
        assertTrue("Human norm $normH should be ≤ HUMAN_WEIGHT + ε",
            normH <= OmniflowNodeMatcher.HUMAN_WEIGHT + 1e-4f)
        assertTrue("Programmer norm $normP should be ≤ PROGRAMMER_WEIGHT + ε",
            normP <= OmniflowNodeMatcher.PROGRAMMER_WEIGHT + 1e-4f)
    }

    @Test
    fun `same node has cosine = 1`() {
        val n = node(text = "确认", classSuffix = "Button", clickable = true)
        val v = OmniflowNodeMatcher.elementVector(n)
        val sim = OmniflowNodeMatcher.cosine(v, v)
        assertEquals(1f, sim, 1e-4f)
    }

    @Test
    fun `identical nodes have higher cosine than completely different nodes`() {
        val a = node(resourceId = "com.app/btn_confirm", text = "Confirm",
            classSuffix = "Button", clickable = true)
        val b = node(resourceId = "com.app/btn_confirm", text = "Confirm",
            classSuffix = "Button", clickable = true)
        val c = node(resourceId = "com.other/tv_price", text = "¥99",
            classSuffix = "TextView", scrollable = true)
        val va = OmniflowNodeMatcher.elementVector(a)
        val vb = OmniflowNodeMatcher.elementVector(b)
        val vc = OmniflowNodeMatcher.elementVector(c)
        assertTrue(OmniflowNodeMatcher.cosine(va, vb) > OmniflowNodeMatcher.cosine(va, vc))
    }

    @Test
    fun `blank-text node still produces valid 64-dim vector`() {
        val v = OmniflowNodeMatcher.elementVector(node())
        assertEquals(64, v.size)
        // Vector should not be all zeros (class/attribute bits contribute)
        assertFalse(v.all { it == 0f })
    }

    // ── Cosine ────────────────────────────────────────────────────────────────

    @Test
    fun `cosine of zero vector returns 0`() {
        val zero = FloatArray(64)
        val v = OmniflowNodeMatcher.elementVector(node(text = "hi"))
        assertEquals(0f, OmniflowNodeMatcher.cosine(zero, v), 1e-6f)
    }

    @Test
    fun `cosine is symmetric`() {
        val a = OmniflowNodeMatcher.elementVector(node(text = "保存"))
        val b = OmniflowNodeMatcher.elementVector(node(text = "取消"))
        assertEquals(OmniflowNodeMatcher.cosine(a, b), OmniflowNodeMatcher.cosine(b, a), 1e-5f)
    }

    // ── Bayesian matching ─────────────────────────────────────────────────────

    @Test
    fun `high-confidence exact match executes rather than abstains`() {
        val src = node(resourceId = "com.app/btn_ok", text = "OK",
            classSuffix = "Button", clickable = true, centerX = 300f, centerY = 500f)
        val tgt1 = src.copy(centerX = 310f, centerY = 505f)  // nearly identical, slight drift
        val tgt2 = node(text = "Cancel", classSuffix = "Button", clickable = true,
            centerX = 100f, centerY = 800f)

        val srcVec = OmniflowNodeMatcher.elementVector(src)
        val candidates = listOf(tgt1, tgt2)
        val candidateVecs = candidates.map { OmniflowNodeMatcher.elementVector(it) }

        val result = OmniflowNodeMatcher.matchBayesian(
            sourceNode = src, sourceVec = srcVec,
            candidates = candidates, candidateVecs = candidateVecs,
            anchors = emptyList(), pageDiagonal = 2000f, scaleX = 1f, scaleY = 1f,
        )

        assertFalse("Should execute on high-confidence match", result.abstain)
        assertEquals(0, result.index)  // tgt1 is the best match
        assertTrue(result.pBest > result.pNull)
    }

    @Test
    fun `all-different candidates with no anchors triggers abstain`() {
        val src = node(resourceId = "com.a/foo", text = "Foo", classSuffix = "Button",
            clickable = true, centerX = 100f, centerY = 100f)
        // Candidates with completely different attributes and text
        val candidates = listOf(
            node(resourceId = "com.b/bar", text = "xyz123", classSuffix = "ScrollView",
                scrollable = true, centerX = 500f, centerY = 900f),
            node(resourceId = "com.b/baz", text = "abc456", classSuffix = "RecyclerView",
                scrollable = true, centerX = 540f, centerY = 1200f),
        )
        val srcVec = OmniflowNodeMatcher.elementVector(src)
        val candidateVecs = candidates.map { OmniflowNodeMatcher.elementVector(it) }

        val result = OmniflowNodeMatcher.matchBayesian(
            sourceNode = src, sourceVec = srcVec,
            candidates = candidates, candidateVecs = candidateVecs,
            anchors = emptyList(), pageDiagonal = 2000f, scaleX = 1f, scaleY = 1f,
        )

        // Null prior (1.6) should dominate over two poor-fit candidates
        assertTrue("Should abstain when no good match exists", result.abstain)
        assertTrue(result.pNull > result.pBest)
    }

    @Test
    fun `anchor geometric prediction improves candidate selection`() {
        val src = node(text = "Add", classSuffix = "Button", clickable = true,
            centerX = 200f, centerY = 400f)
        val correct = node(text = "Add", classSuffix = "Button", clickable = true,
            centerX = 220f, centerY = 420f)  // slight positional drift
        val impostor = node(text = "Add", classSuffix = "Button", clickable = true,
            centerX = 800f, centerY = 1500f)  // same text but far away

        val anchor = OmniflowNodeMatcher.MatcherAnchor(
            sourceCenterX = 100f, sourceCenterY = 100f,
            targetCenterX = 120f, targetCenterY = 110f,  // ~same displacement as src→correct
            similarity = 0.9f,
        )

        val srcVec = OmniflowNodeMatcher.elementVector(src)
        val candidates = listOf(correct, impostor)
        val candidateVecs = candidates.map { OmniflowNodeMatcher.elementVector(it) }

        val result = OmniflowNodeMatcher.matchBayesian(
            sourceNode = src, sourceVec = srcVec,
            candidates = candidates, candidateVecs = candidateVecs,
            anchors = listOf(anchor), pageDiagonal = 2000f, scaleX = 1f, scaleY = 1f,
        )

        assertFalse(result.abstain)
        assertEquals("Geometrically close candidate should win", 0, result.index)
    }

    @Test
    fun `empty candidates list always abstains`() {
        val src = node(text = "submit")
        val srcVec = OmniflowNodeMatcher.elementVector(src)
        val result = OmniflowNodeMatcher.matchBayesian(
            sourceNode = src, sourceVec = srcVec,
            candidates = emptyList(), candidateVecs = emptyList(),
            anchors = emptyList(), pageDiagonal = 2000f, scaleX = 1f, scaleY = 1f,
        )
        assertTrue(result.abstain)
        assertEquals(-1, result.index)
    }

    // ── Identity logit ────────────────────────────────────────────────────────

    @Test
    fun `matching resourceId boosts posterior over mismatched resourceId`() {
        val src = node(resourceId = "com.app/btn_submit", text = "Submit", classSuffix = "Button",
            clickable = true, centerX = 300f, centerY = 600f)
        val matchId = src.copy(centerX = 305f, centerY = 605f)
        val wrongId = src.copy(resourceId = "com.app/btn_cancel", centerX = 305f, centerY = 605f)

        val srcVec = OmniflowNodeMatcher.elementVector(src)
        val candidates = listOf(matchId, wrongId)
        val candidateVecs = candidates.map { OmniflowNodeMatcher.elementVector(it) }

        val result = OmniflowNodeMatcher.matchBayesian(
            sourceNode = src, sourceVec = srcVec,
            candidates = candidates, candidateVecs = candidateVecs,
            anchors = emptyList(), pageDiagonal = 2000f, scaleX = 1f, scaleY = 1f,
        )

        assertFalse(result.abstain)
        assertEquals(0, result.index)  // matchId should win
    }

    // ── 2-gram hash helper ────────────────────────────────────────────────────

    @Test
    fun `bigramHashInto with blank text leaves vector unchanged`() {
        val v = FloatArray(16)
        val after = OmniflowNodeMatcher.bigramHashInto(v, 0, 16, "")
        assertEquals(16, after)
        assertTrue(v.all { it == 0f })
    }

    @Test
    fun `bigramHashInto with single-char text leaves vector unchanged`() {
        val v = FloatArray(16)
        OmniflowNodeMatcher.bigramHashInto(v, 0, 16, "a")
        assertTrue(v.all { it == 0f })
    }

    @Test
    fun `bigramHashInto with two-char text modifies exactly one bucket`() {
        val v = FloatArray(16)
        OmniflowNodeMatcher.bigramHashInto(v, 0, 16, "ab")
        assertEquals(1, v.count { abs(it) > 0f })
    }

    // ── softmax / logSumExp helpers ───────────────────────────────────────────

    @Test
    fun `softmax outputs sum to 1`() {
        val probs = OmniflowNodeMatcher.softmax(listOf(1f, 2f, 3f, 0f))
        assertEquals(1f, probs.sum(), 1e-4f)
    }

    @Test
    fun `softmax highest logit gets highest probability`() {
        val probs = OmniflowNodeMatcher.softmax(listOf(0f, 5f, 1f))
        assertEquals(1, probs.indexOf(probs.max()))
    }

    @Test
    fun `logSumExp of equal values equals log(n * exp(v))`() {
        val v = 2f
        val n = 4
        val expected = v + Math.log(n.toDouble()).toFloat()
        val actual = OmniflowNodeMatcher.logSumExp(List(n) { v })
        assertEquals(expected, actual, 1e-4f)
    }
}
