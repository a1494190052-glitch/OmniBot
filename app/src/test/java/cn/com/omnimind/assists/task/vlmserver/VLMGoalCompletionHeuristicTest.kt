package cn.com.omnimind.assists.task.vlmserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VLMGoalCompletionHeuristicTest {
    @Test
    fun `matches explicit related page completion condition`() {
        val step = UIStep(
            observation = "Settings",
            thought = "Open connected devices",
            action = ClickAction(targetDescription = "Connected devices Bluetooth, pairing", x = 540f, y = 612f),
            result = "点击成功",
            afterObservationXml = """
                <hierarchy>
                  <node text="Navigate up" />
                  <node text="Pair new device" />
                  <node text="Connection preferences" />
                  <node text="Bluetooth, Android Auto" />
                </hierarchy>
            """.trimIndent(),
            afterPackageName = "com.android.settings",
        )

        val match = VLMGoalCompletionHeuristic.match(
            goal = "当前在设置页，打开蓝牙设置。如果已经在蓝牙相关页面就完成。不要重复点击同一位置。",
            step = step,
        )

        assertNotNull(match)
        assertEquals("蓝牙", match!!.target)
        assertEquals("bluetooth", match.keyword)
    }

    @Test
    fun `does not match without explicit completion condition`() {
        val step = UIStep(
            observation = "Settings",
            thought = "Open connected devices",
            action = ClickAction(targetDescription = "Connected devices Bluetooth, pairing", x = 540f, y = 612f),
            result = "点击成功",
            afterObservationXml = """<node text="Bluetooth, Android Auto" />""",
            afterPackageName = "com.android.settings",
        )

        val match = VLMGoalCompletionHeuristic.match(
            goal = "当前在设置页，打开蓝牙设置。",
            step = step,
        )

        assertNull(match)
    }

    @Test
    fun `matches direct click search box goal after successful click`() {
        val step = UIStep(
            observation = "Settings",
            thought = "Tap search field",
            action = ClickAction(targetDescription = "设置页面顶部的搜索框，用于搜索设置项", x = 593f, y = 573f),
            result = "点击坐标 (593.0, 573.0) 成功",
            observationXml = """<node text="搜索设置项" class="android.widget.AutoCompleteTextView" editable="true" />""",
            packageName = "com.android.settings",
        )

        val match = VLMGoalCompletionHeuristic.match(
            goal = "点击设置搜索框",
            step = step,
        )

        assertNotNull(match)
        assertEquals("搜索框", match!!.target)
        assertEquals("direct_click_target", match.keyword)
    }

    @Test
    fun `does not finish click search box goal when input remains`() {
        val step = UIStep(
            observation = "Settings",
            thought = "Tap search field",
            action = ClickAction(targetDescription = "设置页面顶部的搜索框，用于搜索设置项", x = 593f, y = 573f),
            result = "点击坐标 (593.0, 573.0) 成功",
            observationXml = """<node text="搜索设置项" class="android.widget.AutoCompleteTextView" editable="true" />""",
            packageName = "com.android.settings",
        )

        val match = VLMGoalCompletionHeuristic.match(
            goal = "点击设置搜索框并输入蓝牙",
            step = step,
        )

        assertNull(match)
    }

    @Test
    fun `builds synthetic finished ui step`() {
        val source = UIStep(
            observation = "Settings",
            thought = "Open connected devices",
            action = ClickAction(targetDescription = "Connected devices Bluetooth, pairing", x = 540f, y = 612f),
            result = "点击成功",
            afterObservationXml = """<node text="Bluetooth, Android Auto" />""",
            afterPackageName = "com.android.settings",
        )

        val finished = VLMGoalCompletionHeuristic.buildFinishedStep(
            source = source,
            match = VLMGoalCompletionHeuristic.Match(target = "蓝牙", keyword = "bluetooth"),
        )

        assertTrue(finished.action is FinishedAction)
        assertEquals("com.android.settings", finished.packageName)
        assertEquals("matched", finished.pageDiagnostics["vlm_goal_completion"])
    }
}
