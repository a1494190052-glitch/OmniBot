package cn.com.omnimind.bot.plugin.official

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniLinkAgentToolsTest {
    @Test
    fun `plugin exposes generic collaboration primitives`() {
        val definitions = OmniLinkAgentTools.definitions()

        assertEquals(OmniLinkAgentTools.TOOL_NAMES, definitions.mapTo(linkedSetOf()) { it.name })
        assertEquals(4, definitions.size)
        assertTrue(
            definitions.first { it.name == OmniLinkAgentTools.DEVICES }
                .description.contains("电量"),
        )
        val notificationTool = definitions.first {
            it.name == OmniLinkAgentTools.SUBSCRIBE_EVENTS
        }
        assertTrue(notificationTool.parameters["required"].toString().contains("device_id"))
        assertTrue(notificationTool.parameters["required"].toString().contains("mode"))
        assertTrue(notificationTool.parameters.toString().contains("start"))
        assertTrue(notificationTool.parameters.toString().contains("stop"))
    }
}
