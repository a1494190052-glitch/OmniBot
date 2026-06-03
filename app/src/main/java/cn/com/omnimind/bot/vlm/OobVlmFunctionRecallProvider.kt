package cn.com.omnimind.bot.vlm

import android.content.Context
import cn.com.omnimind.assists.task.vlmserver.UIContext
import cn.com.omnimind.assists.task.vlmserver.VLMPageContextRequest
import cn.com.omnimind.assists.task.vlmserver.VLMRecallContextProvider

class OobVlmFunctionRecallProvider(
    context: Context,
) : VLMRecallContextProvider {
    private val appContext = context.applicationContext

    override suspend fun enrich(request: VLMPageContextRequest): UIContext {
        val baseContext = request.context
        val goal = baseContext.activeGoal().ifBlank { baseContext.overallTask }.trim()
        if (goal.isBlank()) return baseContext
        val cleanedSkillGuidance = stripPriorRecall(baseContext.stepSkillGuidance)
        if (request.disableOmniFlowRecall) {
            return baseContext.copy(
                stepSkillGuidance = cleanedSkillGuidance,
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
        if (guidance.guidance.isBlank()) {
            return baseContext.copy(
                stepSkillGuidance = cleanedSkillGuidance,
                pageDiagnostics = diagnostics,
            )
        }

        val recallBlock = buildString {
            appendLine(RECALL_START_MARKER)
            appendLine("OmniFlow function recall candidates for this current VLM step:")
            appendLine(guidance.guidance)
            appendLine(
                "Policy: these are optional callable Functions from fresh current-page recall. " +
                    "If one clearly matches the user goal, choose oob_function_run with function_id and filled arguments. " +
                    "Otherwise continue with normal VLM actions."
            )
            append(RECALL_END_MARKER)
        }.trim()
        val merged = listOf(cleanedSkillGuidance, recallBlock)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString("\n\n")
            .take(MAX_STEP_SKILL_GUIDANCE_CHARS)
        diagnostics["omniflow_recall_injected"] = "true"
        return baseContext.copy(
            stepSkillGuidance = merged,
            pageDiagnostics = diagnostics,
        )
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
        private const val MAX_STEP_SKILL_GUIDANCE_CHARS = 6_000
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
