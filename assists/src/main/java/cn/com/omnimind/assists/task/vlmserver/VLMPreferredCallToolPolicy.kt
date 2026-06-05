package cn.com.omnimind.assists.task.vlmserver

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

object VLMPreferredCallToolPolicy {
    private const val PreferredCallToolMarker = "preferred_call_tool:"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    fun preferredNoArgFunctionId(context: UIContext): String? {
        val recallCount = context.pageDiagnostics["omniflow_call_tool_function_count"]
            ?.trim()
            ?.toIntOrNull()
            ?: 0
        if (recallCount <= 0) return null
        val preferredJson = extractPreferredCallToolJson(context.stepSkillGuidance) ?: return null
        val root = runCatching { json.parseToJsonElement(preferredJson) as? JsonObject }
            .getOrNull()
            ?: return null
        if (root.string("name") != "call_tool") return null
        val arguments = root["arguments"] as? JsonObject ?: return null
        val functionId = arguments.string("function_id")?.takeIf { it.isNotBlank() } ?: return null
        val forwardedArguments = arguments["arguments"] as? JsonObject ?: return null
        if (forwardedArguments.isNotEmpty()) return null
        return functionId
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

    fun shouldRewrite(action: UIAction, context: UIContext, functionId: String): Boolean =
        shouldRewrite(action) && !hasSuccessfulFunctionCall(context, functionId)

    fun rewriteStep(step: UIStep, functionId: String): UIStep {
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

    private fun extractPreferredCallToolJson(guidance: String): String? {
        val markerIndex = guidance.indexOf(PreferredCallToolMarker)
        if (markerIndex < 0) return null
        val start = guidance.indexOf('{', startIndex = markerIndex + PreferredCallToolMarker.length)
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaping = false
        for (index in start until guidance.length) {
            val char = guidance[index]
            if (inString) {
                when {
                    escaping -> escaping = false
                    char == '\\' -> escaping = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return guidance.substring(start, index + 1)
                    }
                }
            }
        }
        return null
    }

    private fun hasSuccessfulFunctionCall(context: UIContext, functionId: String): Boolean =
        context.trace.any { step ->
            val action = step.action as? FunctionRunAction ?: return@any false
            if (action.functionId != functionId) return@any false
            val resultText = step.result.orEmpty()
            if (resultText.startsWith("执行失败")) return@any false
            val resultData = step.actionResultData as? JsonObject
            val explicitSuccess = resultData?.get("success")?.jsonPrimitive?.booleanOrNull
            explicitSuccess ?: resultText.isNotBlank()
        }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.trim()
}
