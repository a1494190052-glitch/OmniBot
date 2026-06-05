package cn.com.omnimind.bot.vlm

import android.content.Context
import cn.com.omnimind.assists.task.vlmserver.UIContext
import cn.com.omnimind.assists.task.vlmserver.VLMPageContextRequest
import cn.com.omnimind.assists.task.vlmserver.VLMRecallContextProvider
import cn.com.omnimind.bot.omniflow.OobFunctionJson.listArg
import cn.com.omnimind.bot.omniflow.OobFunctionJson.mapArg

class OobVlmFunctionRecallProvider(
    context: Context,
) : VLMRecallContextProvider {
    private val appContext = context.applicationContext

    override suspend fun enrich(request: VLMPageContextRequest): UIContext {
        val baseContext = request.context
        val goal = baseContext.activeGoal().ifBlank { baseContext.overallTask }.trim()
        if (goal.isBlank()) return baseContext.copy(dynamicToolDefinitions = emptyList())
        val cleanedSkillGuidance = stripPriorRecall(baseContext.stepSkillGuidance)
        if (request.disableOmniFlowRecall) {
            return baseContext.copy(
                stepSkillGuidance = cleanedSkillGuidance,
                dynamicToolDefinitions = emptyList(),
                pageDiagnostics = baseContext.pageDiagnostics + mapOf(
                    "omniflow_recall_decision" to "disabled",
                    "omniflow_recall_injected" to "false",
                ),
            )
        }

        val snapshot = request.snapshot
        val guidance = VlmRecallGuidanceBuilder.build(
            context = appContext,
            goal = goal,
            targetPackageName = baseContext.targetPackageName.ifBlank { null },
            currentPackageName = snapshot?.packageName ?: request.currentPackageName,
            currentXml = snapshot?.xml ?: request.currentXml,
            allowDirectExecutionDecision = false,
        )
        val diagnostics = baseContext.pageDiagnostics.toMutableMap().apply {
            put("omniflow_recall_decision", guidance.decision)
            put("omniflow_recall_context_chars", guidance.guidance.length.toString())
            snapshot?.capturedAtMs?.let { put("omniflow_recall_snapshot_timestamp", it.toString()) }
        }
        val recalledFunctions = recalledFunctionCandidates(guidance.payload)
        val recalledFunctionIds = recalledFunctions.mapNotNull { candidate ->
            candidate["function_id"]?.toString()?.trim()?.takeIf(String::isNotEmpty)
        }
        diagnostics["omniflow_call_tool_function_count"] = recalledFunctionIds.size.toString()
        if (recalledFunctionIds.isNotEmpty()) {
            diagnostics["omniflow_call_tool_function_ids"] = recalledFunctionIds.joinToString(",")
        }
        if (guidance.guidance.isBlank()) {
            return baseContext.copy(
                stepSkillGuidance = cleanedSkillGuidance,
                dynamicToolDefinitions = emptyList(),
                pageDiagnostics = diagnostics,
            )
        }

        val recallBlock = buildString {
            appendLine(RECALL_START_MARKER)
            appendLine("OmniFlow tool recall for this current VLM step:")
            if (recalledFunctionIds.isNotEmpty()) {
                appendLine("The recalled saved mobile workflows are available through call_tool this turn: ${recalledFunctionIds.joinToString(", ")}")
                appendLine("Each recalled Function can reuse a previously successful multi-step phone workflow, such as opening a page, searching, filling a form, or saving/sending content.")
                appendLine("If one clearly matches the user goal, call call_tool with function_id set to the listed Function id and arguments filled from the user request.")
                appendLine("After call_tool runs, continue from the tool result, history context, and the next fresh page observe; call another tool if the goal is not done.")
            }
            appendLine(guidance.guidance)
            appendLine(
                "Policy: these are optional saved workflow tools from fresh current-page recall. " +
                    "They use the same call_tool action language as replay, and one call may execute a reusable multi-step phone workflow."
            )
            append(RECALL_END_MARKER)
        }.trim()
        val merged = mergeOobVlmRecallStepGuidance(
            baseGuidance = cleanedSkillGuidance,
            recallBlock = recallBlock,
            maxChars = MAX_STEP_SKILL_GUIDANCE_CHARS,
        )
        diagnostics["omniflow_recall_injected"] = "true"
        return baseContext.copy(
            stepSkillGuidance = merged,
            dynamicToolDefinitions = emptyList(),
            pageDiagnostics = diagnostics,
        )
    }

    private fun recalledFunctionCandidates(payload: Map<String, Any?>): List<Map<String, Any?>> {
        val seen = linkedSetOf<String>()
        val candidates = mutableListOf<Map<String, Any?>>()
        fun addCandidate(candidate: Map<String, Any?>) {
            val functionId = candidate["function_id"]?.toString()?.trim().orEmpty()
            if (functionId.isEmpty() || !seen.add(functionId)) return
            candidates += candidate
        }
        mapArg(payload["hit"]).takeIf { it.isNotEmpty() }?.let(::addCandidate)
        listArg(payload["candidates"]).forEach { raw ->
            mapArg(raw).takeIf { it.isNotEmpty() }?.let(::addCandidate)
        }
        listArg(payload["capability_candidates"]).forEach { raw ->
            mapArg(raw).takeIf { it.isNotEmpty() }?.let(::addCandidate)
        }
        return candidates.take(MAX_DYNAMIC_FUNCTION_TOOLS)
    }

    private fun stripPriorRecall(guidance: String): String {
        if (guidance.isBlank()) return ""
        return recallBlockRegex.replace(guidance, "")
            .replace(PRE_RUN_RECALL_REGEX, "")
            .trim()
    }

    private companion object {
        private const val RECALL_START_MARKER = "[[OOB_OMNIFLOW_STEP_RECALL_START]]"
        private const val RECALL_END_MARKER = "[[OOB_OMNIFLOW_STEP_RECALL_END]]"
        private const val MAX_STEP_SKILL_GUIDANCE_CHARS = 1_400
        private const val MAX_DYNAMIC_FUNCTION_TOOLS = 3
        private val recallBlockRegex = Regex(
            pattern = "\\n*\\Q$RECALL_START_MARKER\\E.*?\\Q$RECALL_END_MARKER\\E\\n*",
            options = setOf(RegexOption.DOT_MATCHES_ALL),
        )
        private val PRE_RUN_RECALL_REGEX = Regex(
            pattern = "\\n*OmniFlow recall candidates for this VLM task:.*?(?=\\n\\n[^\\n]+:|\\z)",
            options = setOf(RegexOption.DOT_MATCHES_ALL),
        )
    }
}

internal fun mergeOobVlmRecallStepGuidance(
    baseGuidance: String,
    recallBlock: String,
    maxChars: Int = 1_400,
): String {
    val budget = maxChars.coerceAtLeast(0)
    if (budget == 0) return ""
    val recall = recallBlock.trim()
    val base = baseGuidance.trim()
    if (recall.isBlank()) return base.take(budget)
    if (base.isBlank()) return recall.take(budget)
    val separator = "\n\n"
    val recallPrefix = recall.take(budget)
    val remaining = budget - recallPrefix.length - separator.length
    if (remaining <= 0) return recallPrefix
    return listOf(recallPrefix, base.take(remaining))
        .map(String::trim)
        .filter(String::isNotEmpty)
        .joinToString(separator)
        .take(budget)
}
