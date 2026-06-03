package cn.com.omnimind.bot.vlm

import android.content.Context
import cn.com.omnimind.assists.task.vlmserver.UIContext
import cn.com.omnimind.assists.task.vlmserver.VLMPageContextRequest
import cn.com.omnimind.assists.task.vlmserver.VLMRecallContextProvider
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.bot.omniflow.OobFunctionJson.listArg
import cn.com.omnimind.bot.omniflow.OobFunctionJson.mapArg
import cn.com.omnimind.bot.omniflow.OobFunctionSkillProfile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

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
        val dynamicTools = recalledFunctionToolDefinitions(guidance.payload)
        val dynamicToolNames = dynamicTools.mapNotNull { definition ->
            (definition["function"] as? JsonObject)
                ?.get("name")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                ?.takeIf(String::isNotEmpty)
        }
        diagnostics["omniflow_dynamic_function_tool_count"] = dynamicTools.size.toString()
        if (dynamicToolNames.isNotEmpty()) {
            diagnostics["omniflow_dynamic_function_tool_names"] = dynamicToolNames.joinToString(",")
        }
        if (guidance.guidance.isBlank()) {
            return baseContext.copy(
                stepSkillGuidance = cleanedSkillGuidance,
                dynamicToolDefinitions = dynamicTools,
                pageDiagnostics = diagnostics,
            )
        }

        val recallBlock = buildString {
            appendLine(RECALL_START_MARKER)
            appendLine("OmniFlow Function recall for this current VLM step:")
            if (dynamicToolNames.isNotEmpty()) {
                appendLine("The recalled Functions are exposed as real model tools this turn: ${dynamicToolNames.joinToString(", ")}")
                appendLine("If one clearly matches the user goal, call that Function tool directly and fill its arguments from the user request.")
                appendLine("Function semantics match the agent path: a Function is a composable reusable segment, not automatic task completion proof; after it runs, continue from the fresh result/page.")
            }
            appendLine(guidance.guidance)
            appendLine(
                "Policy: these are optional callable Functions from fresh current-page recall. " +
                    "Dynamic Function tools are preferred over the legacy oob_function_run alias. " +
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
            dynamicToolDefinitions = dynamicTools,
            pageDiagnostics = diagnostics,
        )
    }

    private fun recalledFunctionToolDefinitions(payload: Map<String, Any?>): List<JsonObject> {
        if (payload["success"] != true) return emptyList()
        return recalledFunctionCandidates(payload)
            .mapNotNull { candidate ->
                OobFunctionSkillProfile.dynamicFunctionToolDefinition(
                    spec = candidate,
                    locale = AppLocaleManager.currentPromptLocale(),
                )
            }
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
        private const val MAX_STEP_SKILL_GUIDANCE_CHARS = 2_500
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
