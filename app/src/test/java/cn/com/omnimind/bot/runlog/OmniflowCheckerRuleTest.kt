package cn.com.omnimind.bot.runlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniflowCheckerRuleTest {
    @Test
    fun `checker rule accepts xpath object schema`() {
        val rule = OmniflowCheckerRule.fromMap(
            mapOf(
                "id" to "dismiss_optional_overlay_by_xpath",
                "phase" to "pre_transfer",
                "when" to mapOf(
                    "xpath_exists" to "//node[@clickable='true' and contains(@text,'关闭')]",
                ),
                "then" to mapOf(
                    "action" to "click",
                    "target_xpath" to "//node[@clickable='true' and contains(@text,'关闭')]",
                ),
                "budget" to mapOf(
                    "max_triggers_per_run" to 2,
                    "max_triggers_per_step" to 1,
                ),
                "source" to "function_metadata",
            )
        )

        requireNotNull(rule)
        assertEquals("dismiss_optional_overlay_by_xpath", rule.id)
        assertEquals(OmniflowCheckerRule.COND_XPATH_EXISTS, rule.condition)
        assertEquals(OmniflowCheckerRule.ACTION_CLICK, rule.action)
        assertEquals(OmniflowCheckerRule.PHASE_PRE_TRANSFER, rule.phase)
        assertEquals("//node[@clickable='true' and contains(@text,'关闭')]", rule.params["xpath_exists"])
        assertEquals("//node[@clickable='true' and contains(@text,'关闭')]", rule.params["target_xpath"])
        assertEquals(2, rule.params["max_triggers"])
        assertEquals(1, rule.params["max_triggers_per_step"])
        assertEquals("function_metadata", rule.params["source"])
    }

    @Test
    fun `checker rule accepts flat package and keyboard rules`() {
        val packageRule = OmniflowCheckerRule.fromMap(
            mapOf(
                "id" to "package_mismatch_recovery",
                "condition" to "package_mismatch",
                "action" to "open_app",
                "params" to mapOf("package_name" to "com.example"),
            )
        )
        val keyboardRule = OmniflowCheckerRule.fromMap(
            mapOf(
                "id" to "hide_keyboard_if_obscuring",
                "when" to mapOf("keyboard_obscuring" to true),
                "then" to mapOf("action" to "hide_keyboard"),
            )
        )

        requireNotNull(packageRule)
        requireNotNull(keyboardRule)
        assertEquals(OmniflowCheckerRule.COND_PACKAGE_MISMATCH, packageRule.condition)
        assertEquals(OmniflowCheckerRule.ACTION_OPEN_APP, packageRule.action)
        assertEquals(OmniflowCheckerRule.PHASE_PRE_TRANSFER, packageRule.phase)
        assertEquals(OmniflowCheckerRule.COND_KEYBOARD_OBSCURING, keyboardRule.condition)
        assertEquals(OmniflowCheckerRule.ACTION_HIDE_KEYBOARD, keyboardRule.action)
        assertEquals(OmniflowCheckerRule.PHASE_PRE_ACTION, keyboardRule.phase)
    }
}
