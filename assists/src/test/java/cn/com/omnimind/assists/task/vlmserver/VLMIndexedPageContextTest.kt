package cn.com.omnimind.assists.task.vlmserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VLMIndexedPageContextTest {
    @Test
    fun clickableStructuralParentInheritsDescendantLabel() {
        val xml = """
            <hierarchy>
              <node id="parent" class="android.view.ViewGroup" bounds="[0,0][300,120]" clickable="true">
                <node id="child" class="android.widget.TextView" bounds="[20,20][280,100]" text="保存联系人" />
              </node>
            </hierarchy>
        """.trimIndent()

        val target = VLMIndexedPageContext.uniqueElementTargetByDescription(
            currentXml = xml,
            displayWidth = 1080,
            displayHeight = 2400,
            targetDescription = "保存联系人",
        )

        assertNotNull(target)
        assertEquals("保存联系人", target?.label)
        assertEquals(150f, target?.centerX)
        assertEquals(60f, target?.centerY)

        val rendered = VLMIndexedPageContext.render(xml, 1080, 2400)
        assertTrue(rendered.contains("click c=(139,25)"))
        assertTrue(rendered.contains("label=\"保存联系人\""))
        assertFalse(rendered.contains("#0"))
        assertFalse(rendered.contains("node_id"))
    }
}
