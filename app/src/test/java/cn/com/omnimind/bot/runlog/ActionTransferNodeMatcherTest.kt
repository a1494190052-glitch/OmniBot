package cn.com.omnimind.bot.runlog

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionTransferNodeMatcherTest {

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
    ) = ActionTransferNodeMatcher.NodeInfo(
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
        val v = ActionTransferNodeMatcher.vector(node(text = "hello"))
        assertEquals(ActionTransferNodeMatcher.ELEMENT_DIM, v.size)
    }

    @Test
    fun `Human and Programmer contributions respect weight bounds`() {
        val n = node(resourceId = "com.foo/bar", text = "Search", classSuffix = "EditText",
            editable = true, clickable = true)
        val v = ActionTransferNodeMatcher.vector(n)
        val human = v.slice(0 until ActionTransferNodeMatcher.HUMAN_DIM)
        val prog = v.slice(ActionTransferNodeMatcher.HUMAN_DIM until ActionTransferNodeMatcher.ELEMENT_DIM)
        val normH = sqrt(human.sumOf { (it * it).toDouble() }).toFloat()
        val normP = sqrt(prog.sumOf { (it * it).toDouble() }).toFloat()
        assertTrue("Human norm $normH should be ≤ HUMAN_WEIGHT + ε",
            normH <= ActionTransferNodeMatcher.HUMAN_WEIGHT + 1e-4f)
        assertTrue("Programmer norm $normP should be ≤ PROGRAMMER_WEIGHT + ε",
            normP <= ActionTransferNodeMatcher.PROGRAMMER_WEIGHT + 1e-4f)
    }

    @Test
    fun `same node has cosine = 1`() {
        val n = node(text = "确认", classSuffix = "Button", clickable = true)
        val v = ActionTransferNodeMatcher.vector(n)
        assertEquals(1f, ActionTransferNodeMatcher.cosine(v, v), 1e-4f)
    }

    @Test
    fun `identical nodes have higher cosine than completely different nodes`() {
        val a = node(resourceId = "com.app/btn_confirm", text = "Confirm",
            classSuffix = "Button", clickable = true)
        val b = node(resourceId = "com.app/btn_confirm", text = "Confirm",
            classSuffix = "Button", clickable = true)
        val c = node(resourceId = "com.other/tv_price", text = "¥99",
            classSuffix = "TextView", scrollable = true)
        val va = ActionTransferNodeMatcher.vector(a)
        val vb = ActionTransferNodeMatcher.vector(b)
        val vc = ActionTransferNodeMatcher.vector(c)
        assertTrue(ActionTransferNodeMatcher.cosine(va, vb) > ActionTransferNodeMatcher.cosine(va, vc))
    }

    @Test
    fun `blank-text node still produces valid 64-dim vector`() {
        val v = ActionTransferNodeMatcher.vector(node())
        assertEquals(64, v.size)
        assertFalse(v.all { it == 0f })
    }

    // ── Cosine ────────────────────────────────────────────────────────────────

    @Test
    fun `cosine of zero vector returns 0`() {
        val zero = FloatArray(64)
        val v = ActionTransferNodeMatcher.vector(node(text = "hi"))
        assertEquals(0f, ActionTransferNodeMatcher.cosine(zero, v), 1e-6f)
    }

    @Test
    fun `cosine is symmetric`() {
        val a = ActionTransferNodeMatcher.vector(node(text = "保存"))
        val b = ActionTransferNodeMatcher.vector(node(text = "取消"))
        assertEquals(ActionTransferNodeMatcher.cosine(a, b), ActionTransferNodeMatcher.cosine(b, a), 1e-5f)
    }

    // ── findAnchors ───────────────────────────────────────────────────────────

    @Test
    fun `findAnchors returns mutual best-match pair`() {
        val srcNode = node(text = "OK", classSuffix = "Button", clickable = true)
        val tgtNode = srcNode.copy(centerX = 310f, centerY = 505f)
        val unrelated = node(text = "xyz", classSuffix = "ScrollView", scrollable = true)
        val sv = ActionTransferNodeMatcher.vector(srcNode)
        val tv = ActionTransferNodeMatcher.vector(tgtNode)
        val uv = ActionTransferNodeMatcher.vector(unrelated)
        val anchors = ActionTransferNodeMatcher.findAnchors(
            listOf(srcNode), listOf(sv),
            listOf(tgtNode, unrelated), listOf(tv, uv),
        )
        assertEquals(1, anchors.size)
        assertTrue(anchors[0].sim >= ActionTransferNodeMatcher.MIN_ANCHOR_SIMILARITY)
    }

    @Test
    fun `findAnchors gives exact text match high sim over resource-only candidate`() {
        val srcNode = node(
            resourceId = "com.app/action_primary",
            text = "Start",
            classSuffix = "Button",
            clickable = true,
        )
        val textMatch = node(
            resourceId = "com.other/action_secondary",
            text = "Start",
            classSuffix = "Button",
            clickable = true,
            centerX = 310f,
            centerY = 505f,
        )
        val resourceOnly = node(
            resourceId = "com.app/action_primary",
            text = "Stop",
            classSuffix = "Button",
            clickable = true,
            centerX = 510f,
            centerY = 805f,
        )

        val anchors = ActionTransferNodeMatcher.findAnchors(
            listOf(srcNode), listOf(ActionTransferNodeMatcher.vector(srcNode)),
            listOf(resourceOnly, textMatch), listOf(resourceOnly, textMatch).map(ActionTransferNodeMatcher::vector),
        )
        val resourceOnlySim = ActionTransferNodeMatcher.sim(
            srcNode,
            resourceOnly,
            ActionTransferNodeMatcher.vector(srcNode),
            ActionTransferNodeMatcher.vector(resourceOnly),
        )

        assertEquals(1, anchors.size)
        assertEquals("Start", anchors.single().tgt.text)
        assertTrue("exact text anchor should be near-certain", anchors.single().sim >= 0.96f)
        assertTrue(
            "same resource with conflicting text should stay below anchor threshold",
            resourceOnlySim < ActionTransferNodeMatcher.MIN_ANCHOR_SIMILARITY,
        )
    }

    @Test
    fun `text-conflicting resource overlap stays below high explicit gate`() {
        val source = node(
            resourceId = "com.app/accessibility_mode_toggle_button",
            text = "MODE LIST",
            contentDesc = "Toggle mode list",
            classSuffix = "Button",
            clickable = true,
            focusable = true,
            areaRatio = 0.019f,
            centerX = 94.5f,
            centerY = 48f,
        )
        val target = node(
            resourceId = "com.app/mode_options_toggle",
            contentDesc = "Options",
            classSuffix = "LinearLayout",
            clickable = true,
            focusable = true,
            areaRatio = 0.010f,
            centerX = 634.5f,
            centerY = 967.5f,
        )

        val score = ActionTransferNodeMatcher.simComponents(source, target)

        assertTrue("generic resource token overlap should stay weak", score.resource <= 0.15f)
        assertTrue("label drift should be explicit in diagnostics", score.textConflict)
        assertTrue("conflicting labels cannot be upgraded into a high explicit match", score.score < 0.50f)
    }

    @Test
    fun `anchor transfer abstains when text-conflicting candidate has no geometric support`() {
        val source = node(
            resourceId = "com.app/accessibility_mode_toggle_button",
            text = "MODE LIST",
            contentDesc = "Toggle mode list",
            classSuffix = "Button",
            clickable = true,
            focusable = true,
            areaRatio = 0.019f,
            centerX = 94.5f,
            centerY = 48f,
        )
        val sourceOptions = node(
            resourceId = "com.app/mode_options_toggle",
            contentDesc = "Options",
            classSuffix = "LinearLayout",
            clickable = true,
            focusable = true,
            areaRatio = 0.010f,
            centerX = 647.5f,
            centerY = 919.5f,
        )
        val targetOptions = sourceOptions.copy(centerX = 634.5f, centerY = 967.5f)
        val sourceShutter = node(
            resourceId = "com.app/shutter_button",
            contentDesc = "Shutter",
            classSuffix = "ImageView",
            clickable = true,
            focusable = true,
            areaRatio = 0.18f,
            centerX = 360f,
            centerY = 1112f,
        )
        val targetShutter = sourceShutter.copy(centerX = 360.5f, centerY = 1136f)

        val candidates = listOf(targetOptions, targetShutter)
        val result = ActionTransferNodeMatcher.match(
            src = source,
            srcVec = ActionTransferNodeMatcher.vector(source),
            candidates = candidates,
            candidateVecs = candidates.map(ActionTransferNodeMatcher::vector),
            anchors = listOf(
                ActionTransferNodeMatcher.Anchor(sourceOptions, targetOptions, 1f),
                ActionTransferNodeMatcher.Anchor(sourceShutter, targetShutter, 1f),
            ),
            srcDiagonal = 1468f,
            pageDiagonal = 1468f,
            scaleX = 1f,
            scaleY = 1f,
        )

        assertTrue("wrong semantic candidate with no anchor vote should abstain", result.abstain)
        assertEquals(-1, result.index)
    }

    @Test
    fun `anchor transfer abstains on same resource with conflicting semantic label`() {
        val source = node(
            resourceId = "com.google.android.deskclock:id/fab",
            contentDesc = "Start",
            classSuffix = "Button",
            clickable = true,
            focusable = true,
            areaRatio = 0.025f,
            centerX = 360f,
            centerY = 944f,
        )
        val wrongFab = source.copy(contentDesc = "Add city")
        val targetAlarm = node(
            resourceId = "com.google.android.deskclock:id/tab_menu_alarm",
            contentDesc = "Alarm",
            classSuffix = "FrameLayout",
            clickable = true,
            focusable = true,
            centerX = 72f,
            centerY = 1152f,
        )
        val sourceTimer = node(
            resourceId = "com.google.android.deskclock:id/tab_menu_timer",
            contentDesc = "Timer",
            classSuffix = "FrameLayout",
            clickable = true,
            focusable = true,
            centerX = 360f,
            centerY = 1152f,
        )
        val targetTimer = sourceTimer.copy()
        val candidates = listOf(wrongFab, targetAlarm)

        val result = ActionTransferNodeMatcher.match(
            src = source,
            srcVec = ActionTransferNodeMatcher.vector(source),
            candidates = candidates,
            candidateVecs = candidates.map(ActionTransferNodeMatcher::vector),
            anchors = listOf(ActionTransferNodeMatcher.Anchor(sourceTimer, targetTimer, 0.95f)),
            srcDiagonal = 1468f,
            pageDiagonal = 1468f,
            scaleX = 1f,
            scaleY = 1f,
        )

        val gate = result.debug["gate"] as Map<*, *>
        assertTrue("same resource with conflicting label must not execute", result.abstain)
        assertEquals(-1, result.index)
        assertEquals("semantic_label_conflict", gate["reason"])
    }

    @Test
    fun `findAnchors returns empty when no mutual match above threshold`() {
        val a = node(text = "A", classSuffix = "Button", clickable = true)
        val b = node(classSuffix = "FrameLayout", areaRatio = 0.6f)
        val va = ActionTransferNodeMatcher.vector(a)
        val vb = ActionTransferNodeMatcher.vector(b)
        val anchors = ActionTransferNodeMatcher.findAnchors(listOf(a), listOf(va), listOf(b), listOf(vb))
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

        val srcVec = ActionTransferNodeMatcher.vector(src)
        val candidates = listOf(tgt1, tgt2)
        val candidateVecs = candidates.map { ActionTransferNodeMatcher.vector(it) }

        val result = ActionTransferNodeMatcher.match(
            src = src, srcVec = srcVec,
            candidates = candidates, candidateVecs = candidateVecs,
            anchors = emptyList(), srcDiagonal = 2000f, pageDiagonal = 2000f, scaleX = 1f, scaleY = 1f,
        )

        assertFalse("Should execute on high-confidence match", result.abstain)
        assertEquals(0, result.index)
        assertTrue(result.pBest > result.pNull)
    }

    @Test
    fun `different-but-non-empty candidates abstain without support`() {
        val src = node(resourceId = "com.a/foo", text = "Foo", classSuffix = "Button",
            clickable = true, centerX = 100f, centerY = 100f)
        val candidates = listOf(
            node(resourceId = "com.b/bar", text = "xyz123", classSuffix = "ScrollView",
                scrollable = true, centerX = 500f, centerY = 900f),
            node(resourceId = "com.b/baz", text = "abc456", classSuffix = "RecyclerView",
                scrollable = true, centerX = 540f, centerY = 1200f),
        )
        val srcVec = ActionTransferNodeMatcher.vector(src)
        val candidateVecs = candidates.map { ActionTransferNodeMatcher.vector(it) }

        val result = ActionTransferNodeMatcher.match(
            src = src, srcVec = srcVec,
            candidates = candidates, candidateVecs = candidateVecs,
            anchors = emptyList(), srcDiagonal = 2000f, pageDiagonal = 2000f, scaleX = 1f, scaleY = 1f,
        )

        assertTrue("Risk gate should abstain when no candidate has enough semantic support", result.abstain)
        assertEquals(-1, result.index)
    }

    @Test
    fun `many low-cosine candidates trigger abstain via risk gate`() {
        val src = node(text = "Submit", classSuffix = "Button",
            clickable = true, areaRatio = 0.01f, centerX = 300f, centerY = 600f)
        val candidates = (0 until 25).map { i ->
            node(classSuffix = "FrameLayout", areaRatio = 0.6f,
                centerX = (100f + i * 50), centerY = (200f + i * 80))
        }
        val srcVec = ActionTransferNodeMatcher.vector(src)
        val candidateVecs = candidates.map { ActionTransferNodeMatcher.vector(it) }

        val result = ActionTransferNodeMatcher.match(
            src = src, srcVec = srcVec,
            candidates = candidates, candidateVecs = candidateVecs,
            anchors = emptyList(), srcDiagonal = 2000f, pageDiagonal = 2000f, scaleX = 1f, scaleY = 1f,
        )

        assertTrue("Risk gate should abstain when probability is spread over many poor-fit candidates",
            result.abstain)
        assertTrue(result.pNull > result.pBest)
    }

    @Test
    fun `low margin text-conflicting setup page candidate abstains`() {
        val src = node(
            resourceId = "com.dimowner.audiorecorder/btn_record",
            contentDesc = "Recording: %s",
            classSuffix = "ImageButton",
            clickable = true,
            focusable = true,
            areaRatio = 0.03f,
            centerX = 360f,
            centerY = 1116f,
        )
        val candidates = listOf(
            node(text = "96 kbps", classSuffix = "TextView", clickable = true,
                centerX = 326.5f, centerY = 781f),
            node(text = "128 kbps", classSuffix = "TextView", clickable = true,
                centerX = 326.5f, centerY = 862f),
            node(text = "recording format:", resourceId = "com.dimowner.audiorecorder/setting_title",
                classSuffix = "TextView", clickable = true, centerX = 382f, centerY = 422.5f),
        )
        val anchors = listOf(
            ActionTransferNodeMatcher.Anchor(
                src = node(resourceId = "com.dimowner.audiorecorder/txt_record_info",
                    text = "0.07 Mb, M4a, 16kHz", centerX = 359.5f, centerY = 941f),
                tgt = node(text = "16kHz", centerX = 275.5f, centerY = 619f),
                sim = 0.60f,
            ),
            ActionTransferNodeMatcher.Anchor(
                src = node(resourceId = "com.dimowner.audiorecorder/btn_records_list",
                    classSuffix = "ImageButton", clickable = true, centerX = 636f, centerY = 1116f),
                tgt = node(resourceId = "com.dimowner.audiorecorder/setting_btn_info",
                    classSuffix = "ImageButton", clickable = true, centerX = 718f, centerY = 420f),
                sim = 0.40f,
            ),
        )

        val result = ActionTransferNodeMatcher.match(
            src = src,
            srcVec = ActionTransferNodeMatcher.vector(src),
            candidates = candidates,
            candidateVecs = candidates.map { ActionTransferNodeMatcher.vector(it) },
            anchors = anchors,
            srcDiagonal = 1468f,
            pageDiagonal = 1468f,
            scaleX = 1f,
            scaleY = 1f,
        )

        assertTrue("Text-conflicting setup page controls must not receive the record click", result.abstain)
        val gate = result.debug["gate"] as Map<*, *>
        assertTrue(gate["reason"] == "semantic_label_conflict" || gate["reason"] == "low_margin")
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
        val anchor = ActionTransferNodeMatcher.Anchor(src = anchorSrc, tgt = anchorTgt, sim = 0.9f)

        val srcVec = ActionTransferNodeMatcher.vector(src)
        val candidates = listOf(correct, impostor)
        val candidateVecs = candidates.map { ActionTransferNodeMatcher.vector(it) }

        val result = ActionTransferNodeMatcher.match(
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
        val srcVec = ActionTransferNodeMatcher.vector(src)
        val result = ActionTransferNodeMatcher.match(
            src = src, srcVec = srcVec,
            candidates = emptyList(), candidateVecs = emptyList(),
            anchors = emptyList(), srcDiagonal = 2000f, pageDiagonal = 2000f, scaleX = 1f, scaleY = 1f,
        )
        assertTrue(result.abstain)
        assertEquals(-1, result.index)
    }

    @Test
    fun `dis returns normalized euclidean distance`() {
        assertEquals(0.5f, ActionTransferNodeMatcher.dis(0f, 0f, 3f, 4f, 10f), 1e-5f)
    }

    // ── Identity logit ────────────────────────────────────────────────────────

    @Test
    fun `matching resourceId boosts posterior over mismatched resourceId`() {
        val src = node(resourceId = "com.app/btn_submit", text = "Submit", classSuffix = "Button",
            clickable = true, centerX = 300f, centerY = 600f)
        val matchId = src.copy(centerX = 305f, centerY = 605f)
        val wrongId = src.copy(resourceId = "com.app/btn_cancel", centerX = 305f, centerY = 605f)

        val srcVec = ActionTransferNodeMatcher.vector(src)
        val candidates = listOf(matchId, wrongId)
        val candidateVecs = candidates.map { ActionTransferNodeMatcher.vector(it) }

        val result = ActionTransferNodeMatcher.match(
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
        val srcVec = ActionTransferNodeMatcher.vector(src)

        val confident = ActionTransferNodeMatcher.match(
            src, srcVec, listOf(exactMatch, vague1), listOf(exactMatch, vague1).map { ActionTransferNodeMatcher.vector(it) },
            emptyList(), srcDiagonal = 2000f, pageDiagonal = 2000f, scaleX = 1f, scaleY = 1f,
        )
        val ambiguous = ActionTransferNodeMatcher.match(
            src, srcVec, listOf(vague1, vague2), listOf(vague1, vague2).map { ActionTransferNodeMatcher.vector(it) },
            emptyList(), srcDiagonal = 2000f, pageDiagonal = 2000f, scaleX = 1f, scaleY = 1f,
        )
        assertTrue("Confident match should have higher entropy-confidence",
            confident.confidence > ambiguous.confidence)
    }

    // ── MD5 bigram hash helpers ───────────────────────────────────────────────

    @Test
    fun `textPreprocess lowercases and removes special chars`() {
        // Python: lowercase + remove whitespace + remove non-word/non-Chinese + truncate 10
        assertEquals("helloworld", ActionTransferNodeMatcher.textPreprocess("Hello World!"))
        assertEquals("确认", ActionTransferNodeMatcher.textPreprocess("确认"))
        assertEquals("btn_1", ActionTransferNodeMatcher.textPreprocess("btn_1"))  // underscore kept
    }

    @Test
    fun `textPreprocess truncates to 10 characters`() {
        val long = "abcdefghijk"  // 11 chars
        assertEquals(10, ActionTransferNodeMatcher.textPreprocess(long).length)
    }

    @Test
    fun `bigramHashNormalizedInto with short text leaves vector unchanged`() {
        val v = FloatArray(64)
        ActionTransferNodeMatcher.bigramHashNormalizedInto(v, 0, 16, "")
        assertTrue(v.slice(0..15).all { it == 0f })
        ActionTransferNodeMatcher.bigramHashNormalizedInto(v, 0, 16, "a")
        assertTrue(v.slice(0..15).all { it == 0f })
    }

    @Test
    fun `bigramHashNormalizedInto produces L2-unit-norm output`() {
        val v = FloatArray(64)
        ActionTransferNodeMatcher.bigramHashNormalizedInto(v, 0, 16, "hello")
        val norm = sqrt(v.slice(0..15).sumOf { (it * it).toDouble() }).toFloat()
        assertEquals(1f, norm, 1e-5f)
    }

    // ── softmax / logSumExp helpers ───────────────────────────────────────────

    @Test
    fun `softmax outputs sum to 1`() {
        val probs = ActionTransferNodeMatcher.softmax(listOf(1f, 2f, 3f, 0f))
        assertEquals(1f, probs.sum(), 1e-4f)
    }

    @Test
    fun `softmax highest logit gets highest probability`() {
        val probs = ActionTransferNodeMatcher.softmax(listOf(0f, 5f, 1f))
        assertEquals(1, probs.indexOf(probs.max()))
    }

    @Test
    fun `logSumExp of equal values equals log(n * exp(v))`() {
        val v = 2f
        val n = 4
        val expected = v + Math.log(n.toDouble()).toFloat()
        val actual = ActionTransferNodeMatcher.logSumExp(List(n) { v })
        assertEquals(expected, actual, 1e-4f)
    }
}
