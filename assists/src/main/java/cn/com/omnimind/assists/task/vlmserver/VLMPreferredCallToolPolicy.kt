package cn.com.omnimind.assists.task.vlmserver

object VLMPreferredCallToolPolicy {
    private val noArgPreferredCallToolRegex = Regex(
        pattern = """preferred_call_tool:\s*\{"name"\s*:\s*"call_tool"\s*,\s*"arguments"\s*:\s*\{"function_id"\s*:\s*"([^"]+)"\s*,\s*"arguments"\s*:\s*\{\s*}\s*}\s*}"""
    )

    fun preferredNoArgFunctionId(context: UIContext): String? {
        val recallCount = context.pageDiagnostics["omniflow_call_tool_function_count"]
            ?.trim()
            ?.toIntOrNull()
            ?: 0
        if (recallCount <= 0) return null
        return noArgPreferredCallToolRegex.find(context.stepSkillGuidance)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    fun shouldRewrite(action: UIAction): Boolean =
        when (action) {
            is ClickAction,
            is InputTextAction,
            is SwipeAction,
            is LongPressAction,
            is OpenAppAction,
            is PressKeyAction,
            is WaitAction -> true
            else -> false
        }

    fun rewriteStep(step: VLMStep, functionId: String): VLMStep {
        val originalAction = step.action.name
        if (!shouldRewrite(step.action)) return step
        return step.copy(
            thought = listOf(
                step.thought.trim(),
                "preferred_call_tool matched; dispatching call_tool($functionId) instead of repeating $originalAction."
            ).filter(String::isNotEmpty).joinToString("\n"),
            action = FunctionRunAction(functionId = functionId),
            summary = step.summary.ifBlank { "call_tool: $functionId" },
        )
    }

    fun diagnostics(functionId: String, originalAction: String): Map<String, String> =
        mapOf(
            "preferred_call_tool_rewrite" to "true",
            "preferred_call_tool_function_id" to functionId,
            "preferred_call_tool_original_action" to originalAction,
        )
}
