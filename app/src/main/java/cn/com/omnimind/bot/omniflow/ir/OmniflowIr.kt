package cn.com.omnimind.bot.omniflow.ir

import cn.com.omnimind.baselib.runlog.OobCanonicalActionSchema

// ---------------------------------------------------------------------------
// Parameter binding
// ---------------------------------------------------------------------------

sealed class ParameterValue {
    data class Literal(val value: String) : ParameterValue()
    data class Bound(val parameterId: String) : ParameterValue()

    fun resolve(bindings: Map<String, String>): String = when (this) {
        is Literal -> value
        is Bound   -> bindings[parameterId] ?: ""
    }

    companion object {
        fun of(value: Any?): ParameterValue = Literal(value?.toString() ?: "")
    }
}

/**
 * Points to a specific argument within a step's args map, including nested paths.
 * argPath is everything after "$.execution.steps[N].args." in the legacy JSONPath format.
 * Examples: "text", "text.segment[1]", "content.items[0].value"
 */
data class StepArgBinding(
    val stepIndex: Int,
    val argPath: String,
)

fun resolveArgPath(args: Map<String, Any?>, path: String): Any? {
    val segments = parseArgPath(path)
    var current: Any? = args
    for (segment in segments) {
        current = when {
            current is Map<*, *> && segment.index == null ->
                current[segment.key]
            current is List<*> && segment.index != null ->
                current.getOrNull(segment.index)
            else -> return null
        }
    }
    return current
}

private data class PathSegment(val key: String, val index: Int?)

private fun parseArgPath(path: String): List<PathSegment> {
    val segments = mutableListOf<PathSegment>()
    for (part in path.split(".")) {
        val bracketIdx = part.indexOf('[')
        if (bracketIdx >= 0) {
            val key = part.substring(0, bracketIdx)
            val idx = part.substring(bracketIdx + 1, part.indexOf(']')).toIntOrNull()
            if (key.isNotEmpty()) segments += PathSegment(key, null)
            if (idx != null) segments += PathSegment("", idx)
        } else {
            segments += PathSegment(part, null)
        }
    }
    return segments
}

// ---------------------------------------------------------------------------
// Function parameter definition
// ---------------------------------------------------------------------------

data class FunctionParameter(
    val id: String,
    val type: OobCanonicalActionSchema.Type = OobCanonicalActionSchema.Type.STRING,
    val required: Boolean = false,
    val default: String? = null,
    val description: String = "",
    val bindings: List<StepArgBinding> = emptyList(),
)

// ---------------------------------------------------------------------------
// Checker rule (typed wrapper — mirrors OmniflowCheckerRule)
// ---------------------------------------------------------------------------

data class OmniflowStepCheckerRule(
    val id: String,
    val phase: String,
    val condition: String,
    val action: String,
    val enabled: Boolean = true,
    val params: Map<String, Any?> = emptyMap(),
)

// ---------------------------------------------------------------------------
// Agent call context (for steps recorded from model tool calls)
// ---------------------------------------------------------------------------

data class AgentCallContext(
    val originalTool: String,
    val originalArgs: Map<String, Any?> = emptyMap(),
    val reason: String = "",
    val fallback: Map<String, Any?> = emptyMap(),
)

// ---------------------------------------------------------------------------
// Step — the single IR unit. Everything is a tool call.
// ---------------------------------------------------------------------------

data class OmniflowStep(
    val id: String,
    val title: String,
    val toolName: String,
    val arguments: Map<String, ParameterValue> = emptyMap(),
    /** True for observation-only steps (get_state, status_update, wait…) — skip during replay. */
    val skip: Boolean = false,
    val sourceContext: Map<String, Any?>? = null,
    val checkerRules: List<OmniflowStepCheckerRule> = emptyList(),
    /** Non-null when this step was recorded from a model agent_call, not a direct user action. */
    val agentCallContext: AgentCallContext? = null,
) {
    fun resolveArguments(bindings: Map<String, String>): Map<String, String> =
        arguments.mapValues { (_, v) -> v.resolve(bindings) }
}

// ---------------------------------------------------------------------------
// Function metadata
// ---------------------------------------------------------------------------

data class FunctionRegistryStats(
    val runCount: Int = 0,
    val successCount: Int = 0,
    val failCount: Int = 0,
    val lastRunAt: String? = null,
    val lastSuccess: Boolean? = null,
)

data class FunctionMetadata(
    val packageConstraint: String? = null,
    val checkerRules: List<OmniflowStepCheckerRule> = emptyList(),
    /** UDEG node ID for the function's starting page — used by OobFunctionGoToNavigator. */
    val sourceNodeId: String? = null,
    val sourceRunId: String? = null,
    val registryStats: FunctionRegistryStats? = null,
)

// ---------------------------------------------------------------------------
// OmniflowFunction — the stored unit
// ---------------------------------------------------------------------------

data class OmniflowFunction(
    val id: String,
    val name: String,
    val description: String = "",
    val parameters: List<FunctionParameter> = emptyList(),
    val steps: List<OmniflowStep>,
    val metadata: FunctionMetadata = FunctionMetadata(),
) {
    val executableSteps: List<OmniflowStep> get() = steps.filter { !it.skip }

    fun materialize(args: Map<String, String>): OmniflowFunction {
        val resolvedSteps = steps.map { step ->
            step.copy(
                arguments = step.arguments.mapValues { (_, v) ->
                    when (v) {
                        is ParameterValue.Literal -> v
                        is ParameterValue.Bound   -> ParameterValue.Literal(args[v.parameterId] ?: v.resolve(args))
                    }
                }
            )
        }
        return copy(steps = resolvedSteps)
    }

    fun missingRequiredArguments(suppliedArgs: Map<String, String>): List<String> =
        parameters.filter { it.required && it.id !in suppliedArgs && it.default == null }
            .map { it.id }
}
