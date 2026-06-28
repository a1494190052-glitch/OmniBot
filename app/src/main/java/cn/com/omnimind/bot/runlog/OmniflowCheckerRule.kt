package cn.com.omnimind.bot.runlog
import cn.com.omnimind.baselib.runlog.OobActionSchema

/**
 * A single checker rule evaluated by ReplayHelper before each step.
 *
 * Rules are matched by [condition] against the current device state and the
 * pending replay action. When the condition is satisfied, [action] is executed.
 *
 * Three rule scopes compose in order — global rules run first, then
 * function-level rules, then node-level rules:
 *   - Global   : loaded from the built-in checker rule library
 *   - Function : loaded from Function spec metadata.checker_rules
 *   - Node     : loaded from UDEG node skill (future)
 *
 * Execution stops after the first rule whose action produces an effect
 * (i.e. runs a recovery action). Condition-only / continue rules do not stop
 * the chain. Repeated side effects are capped by the executor's shared checker
 * budget; rules may override the default cap with params.max_triggers.
 */
data class OmniflowCheckerRule(
    val id: String,
    val condition: String,
    val action: String,
    val params: Map<String, Any?> = emptyMap(),
    val phase: String = phaseForCondition(condition),
    val enabled: Boolean = true,
) {
    companion object {
        // ── Phases ──────────────────────────────────────────────────────────
        /** Runs before anchor-based action transfer. */
        const val PHASE_PRE_TRANSFER = "pre_transfer"
        /** Runs after transfer, immediately before action dispatch. */
        const val PHASE_PRE_ACTION = "pre_action"
        /** Runs after action dispatch when the action itself may surface a system dialog. */
        const val PHASE_POST_ACTION = "post_action"

        // ── Conditions ──────────────────────────────────────────────────────
        /** Foreground package ≠ step's source-context package. */
        const val COND_PACKAGE_MISMATCH = "package_mismatch"
        /** A dismissible blocking overlay covers the target. */
        const val COND_OVERLAY_BLOCKING = "overlay_blocking"
        /** A dismissible ad/splash/interstitial blocks the current Function step. */
        const val COND_AD_BLOCKING = "ad_blocking"
        /** Soft keyboard overlaps the action target. */
        const val COND_KEYBOARD_OBSCURING = "keyboard_obscuring"
        /** An Android system permission request dialog is visible. */
        const val COND_PERMISSION_DIALOG = "permission_dialog"
        /** Android's "Open with" / resolver default-app dialog is visible. */
        const val COND_RESOLVER_DIALOG = "resolver_dialog"
        /** An app launch surfaced a non-mandatory upgrade/update prompt. */
        const val COND_APP_UPGRADE_PROMPT = "app_upgrade_prompt"

        // ── Actions ─────────────────────────────────────────────────────────
        /** Launch the expected app (params: package_name overrides step inference). */
        const val ACTION_OPEN_APP = OobActionSchema.TOOL_OPEN_APP
        /** Dismiss the blocking overlay by clicking its best dismiss candidate. */
        const val ACTION_DISMISS = "dismiss"
        /** Hide the soft keyboard. */
        const val ACTION_HIDE_KEYBOARD = "hide_keyboard"
        /** Click the Allow button on an Android permission request dialog. */
        const val ACTION_ALLOW = "allow"
        /** Click the "Always open" button on an Android resolver dialog. */
        const val ACTION_CONFIRM_RESOLVER_ALWAYS = "confirm_resolver_always"
        /** Select an app row in Android's resolver dialog before confirming. */
        const val ACTION_SELECT_RESOLVER_APP = "select_resolver_app"
        /** Wait [params.delay_ms] ms before continuing. */
        const val ACTION_WAIT = "wait"
        /** Emit a handoff signal so the host agent resumes. */
        const val ACTION_HANDOFF = "handoff"

        private val dismissActionAliases = setOf(
            ACTION_DISMISS,
            "close",
            "close_popup",
            "click_close",
            "click_dismiss",
            "skip",
        )
        private val allowActionAliases = setOf(
            ACTION_ALLOW,
            "grant",
            "grant_permission",
            "click_allow",
        )
        private val resolverActionAliases = setOf(
            ACTION_CONFIRM_RESOLVER_ALWAYS,
            "always_open",
            "open_always",
            "click_always_open",
            "click_always",
            "confirm_default",
            "set_default",
        )
        private val hideKeyboardActionAliases = setOf(
            ACTION_HIDE_KEYBOARD,
            "dismiss_keyboard",
            "close_keyboard",
        )

        // ── Built-in checker rule library ─────────────────────────────────────
        val BUILTIN_RULE_LIBRARY: List<Map<String, Any?>> = listOf(
            checkerRuleSpec(
                id = "confirm_resolver_always",
                condition = COND_RESOLVER_DIALOG,
                action = ACTION_CONFIRM_RESOLVER_ALWAYS,
                phase = PHASE_PRE_TRANSFER,
            ),
            checkerRuleSpec(
                id = "confirm_resolver_always_after_open_app",
                condition = COND_RESOLVER_DIALOG,
                action = ACTION_CONFIRM_RESOLVER_ALWAYS,
                phase = PHASE_POST_ACTION,
            ),
            checkerRuleSpec(
                id = "auto_grant_permission",
                condition = COND_PERMISSION_DIALOG,
                action = ACTION_ALLOW,
            ),
            checkerRuleSpec(
                id = "dismiss_ad_blocking",
                condition = COND_AD_BLOCKING,
                action = ACTION_DISMISS,
            ),
            checkerRuleSpec(
                id = "package_mismatch_recovery",
                condition = COND_PACKAGE_MISMATCH,
                action = ACTION_OPEN_APP,
            ),
            checkerRuleSpec(
                id = "dismiss_blocking_overlay",
                condition = COND_OVERLAY_BLOCKING,
                action = ACTION_DISMISS,
            ),
            checkerRuleSpec(
                id = "hide_keyboard_if_obscuring",
                condition = COND_KEYBOARD_OBSCURING,
                action = ACTION_HIDE_KEYBOARD,
            ),
            checkerRuleSpec(
                id = "dismiss_app_upgrade_prompt_after_open_app",
                condition = COND_APP_UPGRADE_PROMPT,
                action = ACTION_DISMISS,
                phase = PHASE_POST_ACTION,
            ),
        )

        val BUILTIN_RULES: List<OmniflowCheckerRule> =
            BUILTIN_RULE_LIBRARY.mapNotNull(::fromMap)

        fun globalRulesForPhase(phase: String): List<OmniflowCheckerRule> =
            BUILTIN_RULES.filter { it.phase == phase && it.enabled }

        fun globalRules(): List<OmniflowCheckerRule> =
            BUILTIN_RULES.filter { it.enabled }

        private fun checkerRuleSpec(
            id: String,
            condition: String,
            action: String,
            params: Map<String, Any?> = emptyMap(),
            phase: String? = null,
            enabled: Boolean = true,
        ): Map<String, Any?> = linkedMapOf(
            "id" to id,
            "condition" to condition,
            "action" to action,
            "params" to params.takeIf { it.isNotEmpty() },
            "phase" to phase?.takeIf { it.isNotBlank() },
            "enabled" to enabled,
        ).filterValues { it != null }

        // ── Factories ────────────────────────────────────────────────────────

        fun fromMap(map: Map<*, *>): OmniflowCheckerRule? {
            val id = map["id"]?.toString()?.trim().orEmpty().ifBlank { return null }
            val condition = normalizeCondition(
                firstNonBlank(
                    checkerType(map["condition"]),
                    checkerType(map["when"]),
                    checkerType(map["type"]),
                    map["condition"],
                    map["when"],
                    map["type"],
                )
            ).ifBlank { return null }
            val action = normalizeAction(
                raw = firstNonBlank(
                    checkerType(map["action"]),
                    checkerType(map["then"]),
                    checkerType(map["effect"]),
                    map["action"],
                    map["then"],
                    map["effect"],
                ),
                condition = condition,
            ).ifBlank { return null }
            if (!isSupportedPair(condition, action)) return null
            val params = checkerParams(map)
            return OmniflowCheckerRule(
                id = id,
                condition = condition,
                action = action,
                params = params,
                phase = map["phase"]?.toString()?.trim()?.ifBlank { null } ?: phaseForCondition(condition),
                enabled = map["enabled"]?.let(::boolArg) ?: true,
            )
        }

        /** Extracts checker rules from a Function spec's metadata.checker_rules list. */
        fun fromSpec(spec: Map<*, *>): List<OmniflowCheckerRule> {
            val metadata = mapArg(spec["metadata"])
            val rules = listArg(metadata["checker_rules"])
            return rules.mapNotNull { mapArg(it).takeIf { rule -> rule.isNotEmpty() }?.let(::fromMap) }
        }

        fun checkerType(raw: Any?): String {
            val map = mapArg(raw)
            return firstNonBlank(map["type"], map["kind"], map["name"])
        }

        fun checkerParams(map: Map<*, *>): Map<String, Any?> {
            val params = linkedMapOf<String, Any?>()
            params.putAll(mapArg(map["params"]))
            val condition = mapArg(map["condition"])
            val action = mapArg(map["action"])
            val budget = mapArg(map["budget"])
            copyListParam(condition, params, "text_any", "text_any")
            copyListParam(condition, params, "resource_id_any", "resource_id_any")
            copyListParam(condition, params, "package_any", "package_any")
            copyListParam(condition, params, "class_any", "class_any")
            copyListParam(action, params, "text_any", "action_text_any")
            copyListParam(action, params, "resource_id_any", "action_resource_id_any")
            copyFirstParam(action, params, "wait_ms", "delay_ms")
            copyFirstParam(budget, params, "max_triggers_per_run", "max_triggers")
            copyFirstParam(budget, params, "max_triggers_per_step", "max_triggers_per_step")
            copyFirstParam(budget, params, "cooldown_ms", "cooldown_ms")
            copyFirstParam(map, params, "priority", "priority")
            copyFirstParam(map, params, "source", "source")
            return params.filterValues { value ->
                value != null && value.toString().trim().isNotEmpty()
            }
        }

        private fun copyFirstParam(
            from: Map<*, *>,
            to: MutableMap<String, Any?>,
            fromKey: String,
            toKey: String,
        ) {
            val value = from[fromKey] ?: return
            to.putIfAbsent(toKey, value)
        }

        private fun copyListParam(
            from: Map<*, *>,
            to: MutableMap<String, Any?>,
            fromKey: String,
            toKey: String,
        ) {
            val values = listArg(from[fromKey]).ifEmpty {
                firstNonBlank(from[fromKey]).takeIf { it.isNotBlank() }?.let(::listOf).orEmpty()
            }
            if (values.isNotEmpty()) to.putIfAbsent(toKey, values)
        }

        fun normalizeCondition(raw: String): String =
            when (raw.trim().lowercase().replace('-', '_')) {
                "overlay_blocking",
                "blocking_overlay",
                "popup_blocking",
                "popup",
                "banner",
                "coupon",
                "obstruction",
                "conditional_obstruction" -> COND_OVERLAY_BLOCKING
                "ad_blocking",
                "blocking_ad",
                "ad_popup",
                "ad",
                "ads",
                "splash_ad",
                "interstitial_ad",
                "skip_ad",
                "advertising" -> COND_AD_BLOCKING
                "permission_dialog",
                "permission",
                "permission_prompt",
                "permission_nudge" -> COND_PERMISSION_DIALOG
                "resolver_dialog",
                "open_with_dialog",
                "chooser_dialog",
                "intent_resolver",
                "intent_resolver_dialog",
                "default_app_dialog",
                "always_open_dialog" -> COND_RESOLVER_DIALOG
                "app_upgrade_prompt",
                "upgrade_prompt",
                "update_prompt",
                "app_update_dialog",
                "app_upgrade_dialog",
                "version_update",
                "version_upgrade",
                "hi_upgrade",
                "hi_upgrade_prompt",
                "hi_update",
                "hi_update_prompt",
                "应用升级",
                "应用更新",
                "版本升级",
                "版本更新" -> COND_APP_UPGRADE_PROMPT
                "keyboard_obscuring",
                "keyboard",
                "ime_obscuring",
                "soft_keyboard" -> COND_KEYBOARD_OBSCURING
                "package_mismatch",
                "wrong_app",
                "app_mismatch",
                "foreground_package_mismatch" -> COND_PACKAGE_MISMATCH
                else -> ""
            }

        fun normalizeAction(raw: String, condition: String): String {
            val text = raw.trim().lowercase().replace('-', '_')
            if (text.isBlank()) return actionForCondition(condition)
            val canonicalAction = resolveActionName(text)
            return when {
                text in dismissActionAliases -> ACTION_DISMISS
                text in allowActionAliases -> ACTION_ALLOW
                text in resolverActionAliases -> ACTION_CONFIRM_RESOLVER_ALWAYS
                text in hideKeyboardActionAliases -> ACTION_HIDE_KEYBOARD
                canonicalAction == OobActionSchema.TOOL_OPEN_APP ||
                    text == "start_app" -> ACTION_OPEN_APP
                canonicalAction == OobActionSchema.TOOL_CLICK -> when (condition) {
                    COND_OVERLAY_BLOCKING -> ACTION_DISMISS
                    COND_AD_BLOCKING -> ACTION_DISMISS
                    COND_APP_UPGRADE_PROMPT -> ACTION_DISMISS
                    COND_PERMISSION_DIALOG -> ACTION_ALLOW
                    COND_RESOLVER_DIALOG -> ACTION_CONFIRM_RESOLVER_ALWAYS
                    else -> ""
                }
                else -> ""
            }
        }

        fun actionForCondition(condition: String): String =
            when (condition) {
                COND_KEYBOARD_OBSCURING -> ACTION_HIDE_KEYBOARD
                COND_PERMISSION_DIALOG -> ACTION_ALLOW
                COND_RESOLVER_DIALOG -> ACTION_CONFIRM_RESOLVER_ALWAYS
                COND_PACKAGE_MISMATCH -> ACTION_OPEN_APP
                else -> ACTION_DISMISS
            }

        fun phaseForCondition(condition: String): String =
            when (condition) {
                COND_KEYBOARD_OBSCURING -> PHASE_PRE_ACTION
                COND_RESOLVER_DIALOG -> PHASE_POST_ACTION
                COND_APP_UPGRADE_PROMPT -> PHASE_POST_ACTION
                else -> PHASE_PRE_TRANSFER
            }

        fun isSupportedPair(condition: String, action: String): Boolean =
            (condition == COND_OVERLAY_BLOCKING && action == ACTION_DISMISS) ||
                (condition == COND_AD_BLOCKING && action == ACTION_DISMISS) ||
                (condition == COND_APP_UPGRADE_PROMPT && action == ACTION_DISMISS) ||
                (condition == COND_PERMISSION_DIALOG && action == ACTION_ALLOW) ||
                (condition == COND_RESOLVER_DIALOG && action == ACTION_CONFIRM_RESOLVER_ALWAYS) ||
                (condition == COND_KEYBOARD_OBSCURING && action == ACTION_HIDE_KEYBOARD) ||
                (condition == COND_PACKAGE_MISMATCH && action == ACTION_OPEN_APP)
    }
}
