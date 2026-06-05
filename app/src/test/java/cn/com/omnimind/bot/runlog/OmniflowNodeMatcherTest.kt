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
        longClickable = false,
        focusable = focusable,
        editable = editable,
        scrollable = scrollable,
        checkable = checkable,
        enabled = enabled,
        selected = selected,
        focused = false,
        isLeaf = true,
        hasSiblings = false,
        structSignature = "${classSuffix}|t${if (text.isNotBlank()) 1 else 0}|c0",
        areaRatio = areaRatio,
        centerX = centerX,
        centerY = centerY,
    )

    // ── Element vector ────────────────────────────────────────────────────────

    @Test
    fun `vector outputs exactly 64 dimensions`() {
        val v = OmniflowNodeMatcher.vector(node(text = "hello"))
        assertEquals(OmniflowNodeMatcher.ELEMENT_DIM, v.size)
    }

    @Test
    fun `Human and Programmer contributions respect weight bounds`() {
        val n = node(resourceId = "com.foo/bar", text = "Search", classSuffix = "EditText",
            editable = true, clickable = true)
        val v = OmniflowNodeMatcher.vector(n)
        val human = v.slice(0 until OmniflowNodeMatcher.HUMAN_DIM)
        val prog = v.slice(OmniflowNodeMatcher.HUMAN_DIM until OmniflowNodeMatcher.ELEMENT_DIM)
        val normH = sqrt(human.sumOf { (it * it).toDouble() }).toFloat()
        val normP = sqrt(prog.sumOf { (it * it).toDouble() }).toFloat()
        assertTrue("Human norm $normH should be ≤ HUMAN_WEIGHT + ε",
            normH <= OmniflowNodeMatcher.HUMAN_WEIGHT + 1e-4f)
        assertTrue("Programmer norm $normP should be ≤ PROGRAMMER_WEIGHT + ε",
            normP <= OmniflowNodeMatcher.PROGRAMMER_WEIGHT + 1e-4f)
    }

    @Test
    fun `same node has cosine = 1`() {
        val n = node(text = "确认", classSuffix = "Button", clickable = true)
        val v = OmniflowNodeMatcher.vector(n)
        assertEquals(1f, OmniflowNodeMatcher.cosine(v, v), 1e-4f)
    }

    @Test
    fun `identical nodes have higher cosine than completely different nodes`() {
        val a = node(resourceId = "com.app/btn_confirm", text = "Confirm",
            classSuffix = "Button", clickable = true)
        val b = node(resourceId = "com.app/btn_confirm", text = "Confirm",
            classSuffix = "Button", clickable = true)
        val c = node(resourceId = "com.other/tv_price", text = "¥99",
            classSuffix = "TextView", scrollable = true)
        val va = OmniflowNodeMatcher.vector(a)
        val vb = OmniflowNodeMatcher.vector(b)
        val vc = OmniflowNodeMatcher.vector(c)
        assertTrue(OmniflowNodeMatcher.cosine(va, vb) > OmniflowNodeMatcher.cosine(va, vc))
    }

    @Test
    fun `blank-text node still produces valid 64-dim vector`() {
        val v = OmniflowNodeMatcher.vector(node())
        assertEquals(64, v.size)
        assertFalse(v.all { it == 0f })
    }

    // ── Cosine ────────────────────────────────────────────────────────────────

    @Test
    fun `cosine of zero vector returns 0`() {
        val zero = FloatArray(64)
        val v = OmniflowNodeMatcher.vector(node(text = "hi"))
        assertEquals(0f, OmniflowNodeMatcher.cosine(zero, v), 1e-6f)
    }

    @Test
    fun `cosine is symmetric`() {
        val a = OmniflowNodeMatcher.vector(node(text = "保存"))
        val b = OmniflowNodeMatcher.vector(node(text = "取消"))
        assertEquals(OmniflowNodeMatcher.cosine(a, b), OmniflowNodeMatcher.cosine(b, a), 1e-5f)
    }

    // ── findAnchors ───────────────────────────────────────────────────────────

    @Test
    fun `findAnchors returns mutual best-match pair`() {
        val srcNode = node(text = "OK", classSuffix = "Button", clickable = true)
        val tgtNode = srcNode.copy(centerX = 310f, centerY = 505f)
        val unrelated = node(text = "xyz", classSuffix = "ScrollView", scrollable = true)
        val sv = OmniflowNodeMatcher.vector(srcNode)
        val tv = OmniflowNodeMatcher.vector(tgtNode)
        val uv = OmniflowNodeMatcher.vector(unrelated)
        val anchors = OmniflowNodeMatcher.findAnchors(
            listOf(srcNode), listOf(sv),
            listOf(tgtNode, unrelated), listOf(tv, uv),
        )
        assertEquals(1, anchors.size)
        assertTrue(anchors[0].sim >= OmniflowNodeMatcher.MIN_ANCHOR_SIMILARITY)
    }

    @Test
    fun `findAnchors returns empty when no mutual match above threshold`() {
        val a = node(text = "A", classSuffix = "Button", clickable = true)
        val b = node(classSuffix = "FrameLayout", areaRatio = 0.6f)
        val va = OmniflowNodeMatcher.vector(a)
        val vb = OmniflowNodeMatcher.vector(b)
        val anchors = OmniflowNodeMatcher.findAnchors(listOf(a), listOf(va), listOf(b), listOf(vb))
        assertTrue(anchors.isEmpty())
    }

    // ── Bayesian matching ─────────────────────────────────────────────────────

    @Test
    fun `high-confidence exact match executes rather than abstains`() {
        val src = node(resourceId = "com.app/btn_ok", text = "OK",
            classSuffix = "Button", clickable = true, centerX = 300f, centerY = 500f)
        val tgt1 = src.copy(centerX = 310f, centerY = 505f)
        val tgt2 = node(text = "Cancel", classSuffix = "Button", clickable = true,
            centerX = 100f, centerY = 800f)

        val srcVec = OmniflowNodeMatcher.vector(src)
        val candidates = listOf(tgt1, tgt2)
        val candidateVecs = candidates.map { OmniflowNodeMatcher.vector(it) }

        val result = OmniflowNodeMatcher.match(
            src = src, srcVec = srcVec,
            candidates = candidates, candidateVecs = candidateVecs,
            anchors = emptyList(), srcDiagonal = 2000f, pageDiagonal = 2000f, scaleX = 1f, scaleY = 1f,
        )

        assertFalse("Should execute on high-confidence match", result.abstain)
        assertEquals(0, result.index)
        assertTrue(result.pBest > result.pNull)
    }

    @Test
    fun `different-but-non-empty candidates execute best available without anchors`() {
        val src = node(resourceId = "com.a/foo", text = "Foo", classSuffix = "Button",
            clickable = true, centerX = 100f, centerY = 100f)
        val candidates = listOf(
            node(resourceId = "com.b/bar", text = "xyz123", classSuffix = "ScrollView",
                scrollable = true, centerX = 500f, centerY = 900f),
            node(resourceId = "com.b/baz", text = "abc456", classSuffix = "RecyclerView",
                scrollable = true, centerX = 540f, centerY = 1200f),
        )
        val srcVec = OmniflowNodeMatcher.vector(src)
        val candidateVecs = candidates.map { OmniflowNodeMatcher.vector(it) }

        val result = OmniflowNodeMatcher.match(
            src = src, srcVec = srcVec,
            candidates = candidates, candidateVecs = candidateVecs,
            anchors = emptyList(), srcDiagonal = 2000f, pageDiagonal = 2000f, scaleX = 1f, scaleY = 1f,
        )

        assertFalse("Should execute best candidate when it dominates null prior", result.abstain)
        assertTrue(result.index in candidates.indices)
    }

    @Test
    fun `many low-cosine candidates trigger abstain via risk gate`() {
        val src = node(text = "Submit", classSuffix = "Button",
            clickable = true, areaRatio = 0.01f, centerX = 300f, centerY = 600f)
        val candidates = (0 until 25).map { i ->
            node(classSuffix = "FrameLayout", areaRatio = 0.6f,
                centerX = (100f + i * 50), centerY = (200f + i * 80))
        }
        val srcVec = OmniflowNodeMatcher.vector(src)
        val candidateVecs = candidates.map { OmniflowNodeMatcher.vector(it) }

        val result = OmniflowNodeMatcher.match(
            src = src, srcVec = srcVec,
            candidates = candidates, candidateVecs = candidateVecs,
            anchors = emptyList(), srcDiagonal = 2000f, pageDiagonal = 2000f, scaleX = 1f, scaleY = 1f,
        )

        assertTrue("Risk gate should abstain when probability is spread over many poor-fit candidates",
            result.abstain)
        assertTrue(result.pNull > result.pBest)
    }

    @Test
    fun `anchor geometric prediction selects geometrically close candidate`() {
        val src = node(text = "Add", classSuffix = "Button", clickable = true,
            centerX = 200f, centerY = 400f)
        val correct = node(text = "Add", classSuffix = "Button", clickable = true,
            centerX = 220f, centerY = 420f)
        val impostor = node(text = "Add", classSuffix = "Button", clickable = true,
            centerX = 800f, centerY = 1500f)

        val anchorSrc = node(text = "Back", classSuffix = "Button", clickable = true,
            centerX = 100f, centerY = 100f)
        val anchorTgt = anchorSrc.copy(centerX = 120f, centerY = 110f)
        val anchor = OmniflowNodeMatcher.Anchor(src = anchorSrc, tgt = anchorTgt, sim = 0.9f)

        val srcVec = OmniflowNodeMatcher.vector(src)
        val candidates = listOf(correct, impostor)
        val candidateVecs = candidates.map { OmniflowNodeMatcher.vector(it) }

        val result = OmniflowNodeMatcher.match(
            src = src, srcVec = srcVec,
            candidates = candidates, candidateVecs = candidateVecs,
            anchors = listOf(anchor), srcDiagonal = 2000f, pageDiagonal = 2000f, scaleX = 1f, scaleY = 1f,
        )

        assertFalse(result.abstain)
        assertEquals("Geometrically close candidate should win", 0, result.index)
    }

    @Test
    fun `empty candidates list always abstains`() {
        val src = node(text = "submit")
        val srcVec = OmniflowNodeMatcher.vector(src)
        val result = OmniflowNodeMatcher.match(
            src = src, srcVec = srcVec,
            candidates = emptyList(), candidateVecs = emptyList(),
            anchors = emptyList(), srcDiagonal = 2000f, pageDiagonal = 2000f, scaleX = 1f, scaleY = 1f,
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

        val srcVec = OmniflowNodeMatcher.vector(src)
        val candidates = listOf(matchId, wrongId)
        val candidateVecs = candidates.map { OmniflowNodeMatcher.vector(it) }

        val result = OmniflowNodeMatcher.match(
            src = src, srcVec = srcVec,
            candidates = candidates, candidateVecs = candidateVecs,
            anchors = emptyList(), srcDiagonal = 2000f, pageDiagonal = 2000f, scaleX = 1f, scaleY = 1f,
        )

        assertFalse(result.abstain)
        assertEquals(0, result.index)
    }

    // ── confidence is entropy-based ───────────────────────────────────────────

    @Test
    fun `confident match has higher confidence than ambiguous match`() {
        val src = node(resourceId = "com.app/btn_ok", text = "OK",
            classSuffix = "Button", clickable = true, centerX = 300f, centerY = 500f)
        val exactMatch = src.copy(centerX = 305f, centerY = 505f)
        val vague1 = node(text = "OK", classSuffix = "Button", clickable = true, centerX = 100f, centerY = 200f)
        val vague2 = node(text = "OK", classSuffix = "Button", clickable = true, centerX = 200f, centerY = 300f)
        val srcVec = OmniflowNodeMatcher.vector(src)

        val confident = OmniflowNodeMatcher.match(
            src, srcVec, listOf(exactMatch, vague1), listOf(exactMatch, vague1).map { OmniflowNodeMatcher.vector(it) },
            emptyList(), srcDiagonal = 2000f, pageDiagonal = 2000f, scaleX = 1f, scaleY = 1f,
        )
        val ambiguous = OmniflowNodeMatcher.match(
            src, srcVec, listOf(vague1, vague2), listOf(vague1, vague2).map { OmniflowNodeMatcher.vector(it) },
            emptyList(), srcDiagonal = 2000f, pageDiagonal = 2000f, scaleX = 1f, scaleY = 1f,
        )
        assertTrue("Confident match should have higher entropy-confidence",
            confident.confidence > ambiguous.confidence)
    }

    // ── MD5 bigram hash helpers ───────────────────────────────────────────────

    @Test
    fun `textPreprocess lowercases and removes special chars`() {
        // Python: lowercase + remove whitespace + remove non-word/non-Chinese + truncate 10
        assertEquals("helloworld", OmniflowNodeMatcher.textPreprocess("Hello World!"))
        assertEquals("确认", OmniflowNodeMatcher.textPreprocess("确认"))
        assertEquals("btn_1", OmniflowNodeMatcher.textPreprocess("btn_1"))  // underscore kept
    }

    @Test
    fun `textPreprocess truncates to 10 characters`() {
        val long = "abcdefghijk"  // 11 chars
        assertEquals(10, OmniflowNodeMatcher.textPreprocess(long).length)
    }

    @Test
    fun `bigramHashNormalizedInto with short text leaves vector unchanged`() {
        val v = FloatArray(64)
        OmniflowNodeMatcher.bigramHashNormalizedInto(v, 0, 16, "")
        assertTrue(v.slice(0..15).all { it == 0f })
        OmniflowNodeMatcher.bigramHashNormalizedInto(v, 0, 16, "a")
        assertTrue(v.slice(0..15).all { it == 0f })
    }

    @Test
    fun `bigramHashNormalizedInto produces L2-unit-norm output`() {
        val v = FloatArray(64)
        OmniflowNodeMatcher.bigramHashNormalizedInto(v, 0, 16, "hello")
        val norm = sqrt(v.slice(0..15).sumOf { (it * it).toDouble() }).toFloat()
        assertEquals(1f, norm, 1e-5f)
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
