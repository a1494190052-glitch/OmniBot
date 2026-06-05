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
