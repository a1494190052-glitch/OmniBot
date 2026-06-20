package cn.com.omnimind.assists.task.vlmserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VLMIndexedActionProposerTest {
    @Test
    fun `proposes click for visible search box without model call`() {
        val proposal = VLMIndexedActionProposer.propose(
            context = UIContext(overallTask = "点击设置搜索框"),
            currentXml = SETTINGS_XML,
            displayWidth = 720,
            displayHeight = 1280,
            stepIndex = 0,
        )

        assertNotNull(proposal)
        val action = proposal!!.step.action as ClickAction
        assertEquals("Search settings", action.targetDescription)
        assertEquals(398f, action.x, 1.0f)
        assertEquals(354f, action.y, 1.0f)
        assertEquals("matched", proposal.diagnostics["indexed_action_proposal"])
        assertEquals("click", proposal.diagnostics["indexed_action_proposal_tool"])
    }

    @Test
    fun `proposes click for unique form field search target when label does not say search`() {
        val proposal = VLMIndexedActionProposer.propose(
            context = UIContext(overallTask = "点击设置搜索框"),
            currentXml = SETTINGS_SEARCH_ICON_XML,
            displayWidth = 1060,
            displayHeight = 2376,
            stepIndex = 0,
        )

        assertNotNull(proposal)
        val action = proposal!!.step.action as ClickAction
        assertEquals("移动网络", action.targetDescription)
        assertEquals(272f, action.x, 1.0f)
        assertEquals(79f, action.y, 1.0f)
        assertEquals("unique_form_field", proposal.diagnostics["indexed_action_proposal_matcher"])
    }

    @Test
    fun `does not propose unique field fallback when multiple form fields exist`() {
        val proposal = VLMIndexedActionProposer.propose(
            context = UIContext(overallTask = "点击搜索框"),
            currentXml = MULTI_FORM_XML,
            displayWidth = 720,
            displayHeight = 1280,
            stepIndex = 0,
        )

        assertNull(proposal)
    }

    @Test
    fun `proposes click for explicit visible row target`() {
        val proposal = VLMIndexedActionProposer.propose(
            context = UIContext(overallTask = "Open Connected devices settings"),
            currentXml = SETTINGS_XML,
            displayWidth = 720,
            displayHeight = 1280,
            stepIndex = 0,
        )

        assertNotNull(proposal)
        val action = proposal!!.step.action as ClickAction
        assertEquals("Connected devices", action.targetDescription)
        assertTrue(action.x in 430f..440f)
        assertTrue(action.y in 605f..615f)
    }

    @Test
    fun `does not propose when target description is ambiguous`() {
        val proposal = VLMIndexedActionProposer.propose(
            context = UIContext(overallTask = "点击 Phone"),
            currentXml = AMBIGUOUS_XML,
            displayWidth = 720,
            displayHeight = 1280,
            stepIndex = 0,
        )

        assertNull(proposal)
    }

    @Test
    fun `proposes focus click for multi step input request first`() {
        val proposal = VLMIndexedActionProposer.propose(
            context = UIContext(overallTask = "点击搜索框并输入奶茶"),
            currentXml = SETTINGS_XML,
            displayWidth = 720,
            displayHeight = 1280,
            stepIndex = 0,
        )

        assertNotNull(proposal)
        val action = proposal!!.step.action as ClickAction
        assertEquals("Search settings", action.targetDescription)
        assertEquals("click", proposal.diagnostics["indexed_action_proposal_tool"])
    }

    @Test
    fun `proposes focused input text after search box is focused`() {
        val proposal = VLMIndexedActionProposer.propose(
            context = UIContext(
                overallTask = "点击搜索框并输入奶茶",
                trace = listOf(
                    UIStep(
                        observation = "done",
                        thought = "clicked",
                        action = ClickAction(targetDescription = "Search settings", x = 286f, y = 453f)
                    )
                )
            ),
            currentXml = FOCUSED_SEARCH_XML,
            displayWidth = 720,
            displayHeight = 1280,
            stepIndex = 1,
        )

        assertNotNull(proposal)
        val action = proposal!!.step.action as InputTextAction
        assertEquals("Search settings", action.targetDescription)
        assertEquals("奶茶", action.text)
        assertEquals("search_bar", action.nodeId)
        assertEquals("input_text", proposal.diagnostics["indexed_action_proposal_tool"])
    }

    @Test
    fun `does not treat input target noun as text entry`() {
        val proposal = VLMIndexedActionProposer.propose(
            context = UIContext(
                overallTask = "点击搜索输入框",
                trace = listOf(
                    UIStep(
                        observation = "done",
                        thought = "clicked",
                        action = ClickAction(targetDescription = "Search settings", x = 286f, y = 453f)
                    )
                )
            ),
            currentXml = FOCUSED_SEARCH_XML,
            displayWidth = 720,
            displayHeight = 1280,
            stepIndex = 1,
        )

        assertNull(proposal)
    }

    @Test
    fun `does not propose after first step`() {
        val proposal = VLMIndexedActionProposer.propose(
            context = UIContext(
                overallTask = "点击设置搜索框",
                trace = listOf(
                    UIStep(
                        observation = "done",
                        thought = "clicked",
                        action = ClickAction(targetDescription = "Search settings", x = 398f, y = 354f)
                    )
                )
            ),
            currentXml = SETTINGS_XML,
            displayWidth = 720,
            displayHeight = 1280,
            stepIndex = 1,
        )

        assertNull(proposal)
    }

    private companion object {
        private const val SETTINGS_XML =
            """
            <hierarchy>
              <node bounds="[0,0][720,1280]" scrollable="true">
                <node text="Settings" bounds="[48,256][312,353]" />
                <node text="Search settings" bounds="[152,426][421,480]" clickable="true" />
                <node text="Network &amp; internet" bounds="[144,579][475,633]" clickable="true" />
                <node text="Mobile, Wi-Fi, hotspot" bounds="[144,633][412,671]" />
                <node text="Connected devices" bounds="[144,755][482,809]" clickable="true" />
                <node text="Bluetooth, pairing" bounds="[144,809][361,847]" />
              </node>
            </hierarchy>
            """

        private const val SETTINGS_SEARCH_ICON_XML =
            """
            <hierarchy>
              <node id="0" class="android.widget.FrameLayout" enabled="true" bounds="[0,0][1060,2376]">
                <node id="11" class="androidx.recyclerview.widget.RecyclerView" enabled="true" focusable="true" bounds="[0,0][1060,2376]">
                  <node id="13" class="android.widget.LinearLayout" enabled="true" clickable="true" focusable="true" bounds="[0,453][1060,597]">
                    <node id="22" text="移动数据" class="android.widget.TextView" resource-id="android:id/title" enabled="true" bounds="[72,492][264,557]" />
                  </node>
                </node>
                <node id="57" class="android.widget.FrameLayout" resource-id="com.android.settings:id/content_header_container" enabled="true" clickable="true" focusable="true" bounds="[0,0][1060,273]">
                  <node id="60" content-desc="向上导航" class="android.widget.ImageButton" enabled="true" clickable="true" focusable="true" bounds="[48,105][168,273]" />
                  <node id="63" text="移动网络" class="android.widget.TextView" resource-id="android:id/action_bar_title" enabled="true" focusable="true" bounds="[168,148][408,229]" />
                </node>
              </node>
            </hierarchy>
            """

        private const val MULTI_FORM_XML =
            """
            <hierarchy>
              <node bounds="[0,0][720,1280]">
                <node id="name" text="Name" class="android.widget.EditText" enabled="true" clickable="true" focusable="true" editable="true" bounds="[80,120][640,220]" />
                <node id="phone" text="Phone" class="android.widget.EditText" enabled="true" clickable="true" focusable="true" editable="true" bounds="[80,260][640,360]" />
              </node>
            </hierarchy>
            """

        private const val AMBIGUOUS_XML =
            """
            <hierarchy>
              <node bounds="[0,0][720,1280]">
                <node text="Phone" class="android.widget.TextView" clickable="true" bounds="[80,100][300,180]" />
                <node text="Phone" class="android.widget.TextView" clickable="true" bounds="[80,220][300,300]" />
              </node>
            </hierarchy>
            """

        private const val FOCUSED_SEARCH_XML =
            """
            <hierarchy>
              <node bounds="[0,0][720,1280]">
                <node id="search_bar" text="Search settings" class="android.widget.EditText" enabled="true" clickable="true" focusable="true" focused="true" editable="true" bounds="[152,426][421,480]" />
              </node>
            </hierarchy>
            """
    }
}
