package cn.com.omnimind.assists.task.vlmserver

internal enum class RepeatedActionDecision {
    ALLOW,
    REPLAN,
    STOP,
}

internal object VLMRunLogPlannerHistory {
    fun render(steps: List<Map<String, Any?>>): String {
        if (steps.isEmpty()) return "None"
        return steps.mapIndexed { fallbackIndex, step ->
            renderStep(step, fallbackIndex)
        }.joinToString("\n")
    }

    fun evaluateRepeatedAction(
        steps: List<Map<String, Any?>>,
        candidate: VLMCommand,
    ): RepeatedActionDecision {
        val candidateSignature = commandSignature(candidate) ?: return RepeatedActionDecision.ALLOW
        if (steps.any { step ->
                actionSignature(step) == candidateSignature &&
                    failureKind(step) in REPEATED_ACTION_FAILURE_KINDS
            }
        ) {
            return RepeatedActionDecision.STOP
        }

        val recent = steps.takeLast(REQUIRED_STALLED_REPETITIONS)
        if (recent.size < REQUIRED_STALLED_REPETITIONS) return RepeatedActionDecision.ALLOW
        val repeatedWithoutProgress = recent.all { step ->
            actionSignature(step) == candidateSignature && madeNoProgress(step)
        }
        return if (repeatedWithoutProgress) {
            RepeatedActionDecision.REPLAN
        } else {
            RepeatedActionDecision.ALLOW
        }
    }

    private fun renderStep(step: Map<String, Any?>, fallbackIndex: Int): String {
        val action = step["action"].asStringMap().orEmpty()
        val result = step["result"].asStringMap().orEmpty()
        val metadata = step["metadata"].asStringMap().orEmpty()
        val args = action["args"].asStringMap().orEmpty()
        val number = stepIndex(step)?.plus(1) ?: fallbackIndex + 1
        val tool = action["tool"]?.toString()?.trim().orEmpty().ifBlank { "unknown" }
        val success = booleanValue(result["success"])
        val screenChanged = screenChanged(step)
        val failure = failureKind(step)
        val message = listOf(
            result["error"],
            metadata["message"],
            metadata["summary"],
        ).firstNotNullOfOrNull { value -> value?.toString()?.trim()?.takeIf(String::isNotEmpty) }

        return buildString {
            append("#$number $tool")
            if (args.isNotEmpty()) {
                append(' ')
                append(args.entries.joinToString(" ") { (key, value) ->
                    "$key=${compact(canonicalValue(value), MAX_ARGUMENT_CHARS)}"
                })
            }
            append(" success=${success ?: "unknown"}")
            screenChanged?.let { append(" changed=$it") }
            failure?.let { append(" failure=$it") }
            message?.let { append(" result=${compact(it, MAX_RESULT_CHARS)}") }
        }.take(MAX_LINE_CHARS)
    }

    private fun madeNoProgress(step: Map<String, Any?>): Boolean {
        val result = step["result"].asStringMap().orEmpty()
        return booleanValue(result["success"]) == false || screenChanged(step) == false
    }

    private fun commandSignature(command: VLMCommand): String? {
        val action = command as? Action ?: return null
        return actionSignature(action.tool, action.argsMap())
    }

    private fun actionSignature(step: Map<String, Any?>): String? {
        val action = step["action"].asStringMap() ?: return null
        val tool = action["tool"]?.toString()?.trim().orEmpty()
        if (tool.isBlank()) return null
        return actionSignature(tool, action["args"].asStringMap().orEmpty())
    }

    private fun actionSignature(tool: String, args: Map<String, Any?>): String {
        return "${tool.trim()}|${canonicalValue(args)}"
    }

    private fun screenChanged(step: Map<String, Any?>): Boolean? {
        val metadata = step["metadata"].asStringMap().orEmpty()
        val observation = metadata["post_action_observation"].asStringMap().orEmpty()
        return booleanValue(observation["screen_changed"])
    }

    private fun failureKind(step: Map<String, Any?>): String? {
        val metadata = step["metadata"].asStringMap().orEmpty()
        return metadata["failure"].asStringMap()
            ?.get("kind")
            ?.toString()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }

    private fun stepIndex(step: Map<String, Any?>): Int? = when (val value = step["step_index"]) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }

    private fun booleanValue(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is String -> value.toBooleanStrictOrNull()
        else -> null
    }

    private fun Any?.asStringMap(): Map<String, Any?>? {
        val source = this as? Map<*, *> ?: return null
        return source.entries.associateTo(linkedMapOf()) { (key, value) ->
            key?.toString().orEmpty() to value
        }
    }

    private fun canonicalValue(value: Any?): String = when (value) {
        null -> "null"
        is Map<*, *> -> value.entries
            .map { (key, child) -> key?.toString().orEmpty() to child }
            .sortedBy { it.first }
            .joinToString(prefix = "{", postfix = "}") { (key, child) ->
                "${quoted(key)}:${canonicalValue(child)}"
            }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { canonicalValue(it) }
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { canonicalValue(it) }
        is String -> quoted(value)
        is Number -> value.toString().toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString()
            ?: value.toString()
        is Boolean -> value.toString()
        else -> quoted(value.toString())
    }

    private fun quoted(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }

    private fun compact(value: String, maxChars: Int): String {
        val normalized = value.replace(Regex("\\s+"), " ").trim()
        return if (normalized.length <= maxChars) normalized else normalized.take(maxChars - 3) + "..."
    }

    private const val REQUIRED_STALLED_REPETITIONS = 2
    private const val MAX_ARGUMENT_CHARS = 160
    private const val MAX_RESULT_CHARS = 240
    private const val MAX_LINE_CHARS = 640
    private val REPEATED_ACTION_FAILURE_KINDS = setOf(
        "repeated_action_blocked",
        "repeated_action_stopped",
    )
}
