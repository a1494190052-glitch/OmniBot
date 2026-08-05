package cn.com.omnimind.bot.plugin.official

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniFlowManagementToolHandlerTest {
    private val json = Json { explicitNulls = false }

    @Test
    fun `registering a converted runlog defaults to agent visible`() {
        val args = json.parseToJsonElement(
            """{"run_id":"gui-123","register":true,"enhance":true}""",
        ).jsonObject

        val normalized = normalizeOmniFlowManagementArguments(
            OmniFlowManagementTools.CONVERT_RUN_LOG,
            args,
        )

        assertEquals("gui-123", normalized["run_id"])
        assertEquals(true, normalized["register"])
        assertEquals(true, normalized["agent_visible"])
    }

    @Test
    fun `explicitly hidden converted runlog remains hidden`() {
        val args = json.parseToJsonElement(
            """{"run_id":"gui-123","register":true,"agent_visible":false}""",
        ).jsonObject

        val normalized = normalizeOmniFlowManagementArguments(
            OmniFlowManagementTools.CONVERT_RUN_LOG,
            args,
        )

        assertFalse(normalized["agent_visible"] as Boolean)
    }
}
