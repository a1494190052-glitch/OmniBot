package cn.com.omnimind.bot.runlog
import cn.com.omnimind.baselib.runlog.OobActionSchema

/**
 * A single checker rule evaluated by ReplayHelper before each step.
 *
 * Rules are matched by [condition] against the current device state and the
 * pending replay action. When the condition is satisfied, [action] is executed.
 *
 * The Function replay path supplies one ordered checker list. It is loaded
 * from workspace checker rules and Function metadata.checker_rules, then
 * evaluated before action transfer.
 *
 * Execution stops after the first rule whose action produces an effect
 * (i.e. runs a recovery action). Condition-only / continue rules do not stop
 * the chain. Repeated side effects are capped by the executor's shared checker
 * budget; rules may override the default cap with params.max_triggers.
 */
data class ReplayCheckerRule(
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
        /** A workspace XPath expression matches the current UI XML. */
        const val COND_XPATH_EXISTS = "xpath_exists"
        /** A workspace XPath expression matches a node covering the replay target. */
        const val COND_TARGET_COVERED_BY_XPATH = "target_covered_by_xpath"
        /** Soft keyboard overlaps the action target. */
        const val COND_KEYBOARD_OBSCURING = "keyboard_obscuring"

        // ── Actions ─────────────────────────────────────────────────────────
        /** Launch the expected app (params: package_name overrides step inference). */
        const val ACTION_OPEN_APP = OobActionSchema.TOOL_OPEN_APP
        /** Click a node selected by XPath. */
        const val ACTION_CLICK = OobActionSchema.TOOL_CLICK
        /** Hide the soft keyboard. */
        const val ACTION_HIDE_KEYBOARD = "hide_keyboard"
        /** Wait [params.delay_ms] ms before continuing. */
        const val ACTION_WAIT = "wait"

        private val hideKeyboardActionAliases = setOf(
            ACTION_HIDE_KEYBOARD,
            "dismiss_keyboard",
            "close_keyboard",
        )

        // ── Factories ────────────────────────────────────────────────────────

        fun fromMap(map: Map<*, *>): ReplayCheckerRule? {
            val id = map["id"]?.toString()?.trim().orEmpty().ifBlank { return null }
            val conditionValue = map["condition"] ?: map["when"] ?: map["type"]
            val actionValue = map["action"] ?: map["then"] ?: map["effect"]
            val condition = normalizeCondition(
                firstNonBlank(
                    checkerType(conditionValue),
                    conditionValue,
                )
            ).ifBlank { return null }
            val action = normalizeAction(
                raw = firstNonBlank(
                    checkerType(actionValue),
                    actionValue,
                ),
                condition = condition,
            ).ifBlank { return null }
            if (!isSupportedPair(condition, action)) return null
            val params = checkerParams(map)
            return ReplayCheckerRule(
                id = id,
                condition = condition,
                action = action,
                params = params,
                phase = map["phase"]?.toString()?.trim()?.ifBlank { null } ?: phaseForCondition(condition),
                enabled = map["enabled"]?.let(::boolArg) ?: true,
            )
        }

        /** Extracts checker rules from a Function spec's metadata.checker_rules list. */
        fun fromSpec(spec: Map<*, *>): List<ReplayCheckerRule> {
            val metadata = mapArg(spec["metadata"])
            val rules = listArg(metadata["checker_rules"])
            return rules.mapNotNull { mapArg(it).takeIf { rule -> rule.isNotEmpty() }?.let(::fromMap) }
        }

        fun checkerType(raw: Any?): String {
            val map = mapArg(raw)
            if (map.containsKey("xpath_exists") || map.containsKey("xpath")) return COND_XPATH_EXISTS
            if (map.containsKey("target_covered_by_xpath")) return COND_TARGET_COVERED_BY_XPATH
            if (map.containsKey("package_mismatch")) return COND_PACKAGE_MISMATCH
            if (map.containsKey("keyboard_obscuring") || map.containsKey("keyboard_obscures_target")) {
                return COND_KEYBOARD_OBSCURING
            }
            if (map.containsKey("target_xpath")) return ACTION_CLICK
            return firstNonBlank(map["type"], map["action"], map["kind"], map["name"])
        }

        fun checkerParams(map: Map<*, *>): Map<String, Any?> {
            val params = linkedMapOf<String, Any?>()
            params.putAll(mapArg(map["params"]))
            val condition = mapArg(map["condition"])
                .ifEmpty { mapArg(map["when"]) }
            val action = mapArg(map["action"])
                .ifEmpty { mapArg(map["then"]) }
            val budget = mapArg(map["budget"])
            params.putAll(condition)
            params.putAll(action)
            copyListParam(condition, params, "text_any", "text_any")
            copyListParam(condition, params, "resource_id_any", "resource_id_any")
            copyListParam(condition, params, "package_any", "package_any")
            copyListParam(condition, params, "class_any", "class_any")
            copyListParam(action, params, "text_any", "action_text_any")
            copyListParam(action, params, "resource_id_any", "action_resource_id_any")
            copyFirstParam(condition, params, "xpath_exists", "xpath_exists")
            copyFirstParam(condition, params, "xpath", "xpath")
            copyFirstParam(condition, params, "target_covered_by_xpath", "target_covered_by_xpath")
            copyFirstParam(action, params, "target_xpath", "target_xpath")
            copyFirstParam(action, params, "package_name", "package_name")
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
                "xpath",
                "xpath_exists",
                "xml_xpath",
                "node_xpath" -> COND_XPATH_EXISTS
                "target_covered_by_xpath",
                "target_covered" -> COND_TARGET_COVERED_BY_XPATH
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
                text in hideKeyboardActionAliases -> ACTION_HIDE_KEYBOARD
                canonicalAction == OobActionSchema.TOOL_OPEN_APP ||
                    text == "start_app" -> ACTION_OPEN_APP
                canonicalAction == OobActionSchema.TOOL_CLICK ||
                    text == "dismiss" ||
                    text == "close" ||
                    text == "skip" -> ACTION_CLICK
                text == ACTION_WAIT -> ACTION_WAIT
                else -> ""
            }
        }

        fun actionForCondition(condition: String): String =
            when (condition) {
                COND_KEYBOARD_OBSCURING -> ACTION_HIDE_KEYBOARD
                COND_PACKAGE_MISMATCH -> ACTION_OPEN_APP
                else -> ACTION_CLICK
            }

        fun phaseForCondition(condition: String): String =
            when (condition) {
                COND_KEYBOARD_OBSCURING -> PHASE_PRE_ACTION
                else -> PHASE_PRE_TRANSFER
            }

        fun isSupportedPair(condition: String, action: String): Boolean =
            (condition == COND_XPATH_EXISTS && action in setOf(ACTION_CLICK, ACTION_WAIT, ACTION_HIDE_KEYBOARD)) ||
                (condition == COND_TARGET_COVERED_BY_XPATH && action == ACTION_CLICK) ||
                (condition == COND_KEYBOARD_OBSCURING && action == ACTION_HIDE_KEYBOARD) ||
                (condition == COND_PACKAGE_MISMATCH && action == ACTION_OPEN_APP)
    }
}
