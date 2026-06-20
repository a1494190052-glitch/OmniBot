package cn.com.omnimind.bot.omniflow

import android.content.Context
import android.content.ContextWrapper
import cn.com.omnimind.baselib.i18n.PromptLocale
import cn.com.omnimind.bot.runlog.RunLogReplayPolicy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OobFunctionSkillProfileTest {
    @Test
    fun `saved Functions are not exposed as dynamic model tools`() {
        val context = MinimalContext()

        assertTrue(
            OobFunctionSkillProfile.dynamicFunctionToolDefinitions(
                context = context,
                locale = PromptLocale.EN_US,
            ).isEmpty()
        )
        assertTrue(
            OobFunctionSkillProfile.dynamicFunctionToolDefinitions(
                context = context,
                locale = PromptLocale.EN_US,
                forceInclude = true,
            ).isEmpty()
        )
        assertTrue(OobFunctionSkillProfile.runtimeToolDefinitions(PromptLocale.EN_US).isEmpty())
    }

    @Test
    fun `function management profile exposes lifecycle tools only`() {
        val toolNames = OobFunctionSkillProfile.staticToolDefinitions(PromptLocale.EN_US)
            .mapNotNull { definition ->
                (definition["function"] as? JsonObject)
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.contentOrNull
            }
            .toSet()

        assertEquals(OobFunctionToolNames.profileTools, toolNames)
        assertFalse(toolNames.contains(RunLogReplayPolicy.TOOL_CALL_TOOL))
        assertFalse(toolNames.any { it.startsWith("omniflow.call") })
    }

    @Test
    fun `function management profile is owned by omniflow skill`() {
        assertEquals("function_management", OobFunctionSkillProfile.PROFILE)
        assertEquals("omniflow", OobFunctionSkillProfile.SKILL_ID)
    }

    private class MinimalContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }
}
