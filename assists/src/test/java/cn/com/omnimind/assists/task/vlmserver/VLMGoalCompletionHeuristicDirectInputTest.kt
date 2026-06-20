package cn.com.omnimind.assists.task.vlmserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VLMGoalCompletionHeuristicDirectInputTest {
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
    fun `matches current page title after previous action opened target page`() {
        val match = VLMGoalCompletionHeuristic.matchCurrentPage(
            goal = "打开网络设置",
            currentXml = MOBILE_NETWORK_XML,
            currentPackageName = "com.android.settings",
            trace = listOf(
                UIStep(
                    observation = "Settings",
                    thought = "Tap network",
                    action = ClickAction(targetDescription = "移动网络", x = 720f, y = 1575f),
                    result = "点击成功",
                )
            ),
        )

        assertNotNull(match)
        assertEquals("网络", match!!.target)
        assertEquals("移动网络", match.keyword)
    }

    @Test
    fun `does not match current page on first step`() {
        val match = VLMGoalCompletionHeuristic.matchCurrentPage(
            goal = "打开网络设置",
            currentXml = MOBILE_NETWORK_XML,
            currentPackageName = "com.android.settings",
            trace = emptyList(),
        )

        assertNull(match)
    }

    private companion object {
        private const val MOBILE_NETWORK_XML = """
            <hierarchy>
              <node text="移动数据" resource-id="android:id/title" enabled="true" bounds="[1129,492][1321,557]" />
              <node text="移动网络" resource-id="android:id/action_bar_title" enabled="true" focusable="true" bounds="[1225,148][1465,229]" />
            </hierarchy>
        """
    }
}
