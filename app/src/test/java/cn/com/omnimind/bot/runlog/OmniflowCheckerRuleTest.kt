package cn.com.omnimind.bot.runlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniflowCheckerRuleTest {
    @Test
    fun `built in checker rules are loaded from rule library by phase`() {
        val libraryIds = OmniflowCheckerRule.BUILTIN_RULE_LIBRARY
            .mapNotNull { it["id"]?.toString() }
        val ruleIds = OmniflowCheckerRule.BUILTIN_RULES.map { it.id }

        assertEquals(libraryIds, ruleIds)
        assertEquals(ruleIds.size, ruleIds.distinct().size)
        assertTrue(
            OmniflowCheckerRule.globalRulesForPhase(OmniflowCheckerRule.PHASE_PRE_TRANSFER)
                .any { it.id == "auto_grant_permission" }
        )
        assertTrue(
            OmniflowCheckerRule.globalRulesForPhase(OmniflowCheckerRule.PHASE_PRE_ACTION)
                .any { it.id == "hide_keyboard_if_obscuring" }
        )
        assertTrue(
            OmniflowCheckerRule.globalRulesForPhase(OmniflowCheckerRule.PHASE_POST_ACTION)
                .any { it.id == "dismiss_app_upgrade_prompt_after_open_app" }
        )
    }
}
