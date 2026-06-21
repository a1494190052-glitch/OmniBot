package cn.com.omnimind.bot.agent

import cn.com.omnimind.bot.omniflow.OobFunctionToolNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolExposurePolicyTest {
    @Test
    fun `omniflow profile exposes focused reusable command tools`() {
        val policy = AgentToolExposurePolicy.fromRaw(" omniflow ", null)

        assertEquals("omniflow", policy.profile)
        assertTrue(policy.isLightweightProfile())
        assertEquals(
            OobFunctionToolNames.profileTools,
            policy.effectiveAllowedTools(),
        )
    }

    @Test
    fun `legacy function management profile normalizes to omniflow`() {
        val policy = AgentToolExposurePolicy.fromRaw(
            profile = " function-management ",
            allowedTools = null,
        )

        assertEquals("omniflow", policy.profile)
        assertTrue(policy.isLightweightProfile())
        assertTrue(policy.effectiveAllowedTools()?.contains(OobFunctionToolNames.FUNCTION_RUN) == true)
    }
}
