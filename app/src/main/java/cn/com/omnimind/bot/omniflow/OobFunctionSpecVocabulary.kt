package cn.com.omnimind.bot.omniflow

/**
 * Canonical durable Function spec vocabulary.
 *
 * Replay executor names stay in RunLogReplayPolicy. These names describe the
 * persisted reusable Function format and registry projection.
 */
object OobFunctionSpecVocabulary {
    const val SCHEMA_VERSION_V1 = "oob.reusable_function.v1"
    const val EXECUTION_KIND_TOOL_SEQUENCE = "tool_sequence"
    const val EXECUTION_RUNNER_TOOL_SEQUENCE = "oob_tool_sequence"
    const val REGISTRY_RUNNER_AGENT_REUSABLE_FUNCTION = "oob_agent_reusable_function"
    const val FIELD_HAS_AGENT_STEPS = "has_agent_steps"
    const val LEGACY_FIELD_REQUIRES_AGENT_FALLBACK = "requires_agent_fallback"

    fun agentStepFlag(execution: Map<*, *>): Any? =
        execution[FIELD_HAS_AGENT_STEPS]
            ?: execution[LEGACY_FIELD_REQUIRES_AGENT_FALLBACK]
}
