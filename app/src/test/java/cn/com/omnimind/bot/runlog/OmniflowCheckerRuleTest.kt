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

    @Test
    fun `checker rule accepts shared object schema`() {
        val rule = OmniflowCheckerRule.fromMap(
            mapOf(
                "id" to "dismiss_optional_overlay",
                "phase" to "pre_transfer",
                "condition" to mapOf(
                    "type" to "overlay_blocking",
                    "text_any" to listOf("稍后"),
                ),
                "action" to mapOf(
                    "type" to "dismiss",
                    "text_any" to listOf("关闭"),
                ),
                "budget" to mapOf(
                    "max_triggers_per_run" to 2,
                    "max_triggers_per_step" to 1,
                ),
                "source" to "function_metadata",
            )
        )

        requireNotNull(rule)
        assertEquals("dismiss_optional_overlay", rule.id)
        assertEquals(OmniflowCheckerRule.COND_OVERLAY_BLOCKING, rule.condition)
        assertEquals(OmniflowCheckerRule.ACTION_DISMISS, rule.action)
        assertEquals(OmniflowCheckerRule.PHASE_PRE_TRANSFER, rule.phase)
        assertEquals(listOf("稍后"), rule.params["text_any"])
        assertEquals(listOf("关闭"), rule.params["action_text_any"])
        assertEquals(2, rule.params["max_triggers"])
        assertEquals(1, rule.params["max_triggers_per_step"])
        assertEquals("function_metadata", rule.params["source"])
    }
}
