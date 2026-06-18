package cn.com.omnimind.bot.omniflow

import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.bot.agent.AgentToolJson
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Completes the two-step update_function contract behind one native call.
 *
 * OobFunctionUpdateService remains the only mutation path. This wrapper only
 * obtains agent-authored analysis/patch when the first update call asks for it.
 */
class OobFunctionUpdateAgentOrchestrator(
    private val updateService: OobFunctionUpdateService,
    private val agentRequester: suspend (prompt: String, responseJsonObject: Boolean) -> String? = { prompt, responseJsonObject ->
        requestAgentAnalysis(prompt, responseJsonObject)
    },
) {
    private val parser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    suspend fun updateFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val initial = updateService.updateFunction(request)
        if (initial["needs_agent_analysis"] != true || requestHasAnalysisOrPatch(request)) {
            return initial
        }
        if (!shouldAutoAnalyze(request)) {
            return linkedMapOf<String, Any?>().apply {
                putAll(initial)
                put("agent_model_invoked", false)
                put("analysis_policy", "offline_only")
                put(
                    "message",
                    "update_function enhancement analysis is queued for an explicit offline/background step."
                )
            }
        }

        val prompt = OobFunctionJson.firstNonBlank(initial["agent_prompt"])
        if (prompt.isBlank()) {
            return agentFailure(
                initial = initial,
                code = "AGENT_PROMPT_EMPTY",
                message = "update_function returned needs_agent_analysis without agent_prompt",
                agentInvoked = false,
            )
        }

        val raw = try {
            val jsonResponse = agentRequester(prompt, true).orEmpty()
            if (jsonResponse.trim().isNotEmpty()) {
                jsonResponse
            } else {
                agentRequester(prompt, false).orEmpty()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return agentFailure(
                initial = initial,
                code = "AGENT_ANALYSIS_REQUEST_FAILED",
                message = e.message?.takeIf { it.isNotBlank() } ?: "Agent model request failed",
                agentInvoked = true,
            )
        }

        if (raw.trim().isEmpty()) {
            return agentFailure(
                initial = initial,
                code = "AGENT_ANALYSIS_EMPTY_RESPONSE",
                message = "Agent model returned an empty response for update_function",
                agentInvoked = true,
            )
        }

        val agentJson = extractJsonObject(raw)
            ?: return agentFailure(
                initial = initial,
                code = "AGENT_ANALYSIS_UNPARSEABLE",
                message = "Agent model did not return parseable update_function analysis JSON",
                agentInvoked = true,
                rawResponse = raw,
            )
        val analysis = analysisFromAgentUpdateJson(agentJson)
        val patch = patchFromAgentUpdateJson(agentJson, analysis)
        if (analysis.isEmpty() && patch.isEmpty()) {
            return agentFailure(
                initial = initial,
                code = "AGENT_ANALYSIS_EMPTY",
                message = "Agent model returned no analysis or patch for update_function",
                agentInvoked = true,
                agentResponse = agentJson,
            )
        }

        val nextArgs = linkedMapOf<String, Any?>().apply {
            putAll(request)
            if (analysis.isNotEmpty()) put("analysis", analysis)
            if (patch.isNotEmpty()) put("patch", patch)
            put("source", "oob_function_update_agent_orchestrator")
            put("agent_model_invoked", true)
        }
        val updated = updateService.updateFunction(nextArgs)
        return linkedMapOf<String, Any?>().apply {
            putAll(updated)
            if (!containsKey("function")) put("function", initial["function"])
            if (!containsKey("updated_function")) {
                put("updated_function", initial["updated_function"] ?: initial["function"])
            }
            put("needs_agent_analysis", false)
            put("agent_model_invoked", true)
            put("agent_analysis_initial", initial)
            put("agent_response", agentJson)
        }
    }

    private fun requestHasAnalysisOrPatch(request: Map<String, Any?>): Boolean =
        firstNonEmptyMap(
            request,
            listOf("analysis", "evidence_analysis", "runlog_analysis"),
        ).isNotEmpty() ||
            firstNonEmptyMap(
                request,
                listOf("patch", "function_patch", "updates", "recommended_patch"),
            ).isNotEmpty()

    private fun shouldAutoAnalyze(request: Map<String, Any?>): Boolean {
        val explicit = firstNonBlank(
            request["auto_analyze_with_model"],
            request["autoAnalyzeWithModel"],
        )
        val explicitModelAnalysis = explicit.equals("true", ignoreCase = true)
        val offlineJob = boolArg(request["offline_job"]) ||
            boolArg(request["offlineJob"]) ||
            boolArg(request["background_enhancement"]) ||
            boolArg(request["backgroundEnhancement"])
        return offlineJob && explicitModelAnalysis
    }

    private fun extractJsonObject(raw: String): Map<String, Any?>? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val candidates = listOf(
            trimmed,
            stripJsonFence(trimmed),
            substringJsonObject(trimmed),
        )
        for (candidate in candidates) {
            val map = parseJsonObject(candidate)
            if (!map.isNullOrEmpty()) return map
        }
        return null
    }

    private fun stripJsonFence(raw: String): String {
        val match = JSON_FENCE_REGEX.find(raw.trim())
        return match?.groupValues?.getOrNull(1)?.trim() ?: raw
    }

    private fun substringJsonObject(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return ""
        return raw.substring(start, end + 1)
    }

    private fun parseJsonObject(raw: String): Map<String, Any?>? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        return runCatching {
            val jsonObject = parser.parseToJsonElement(text) as? JsonObject ?: return null
            AgentToolJson.jsonObjectToMap(jsonObject)
        }.getOrNull()
    }

    private fun analysisFromAgentUpdateJson(json: Map<String, Any?>): Map<String, Any?> {
        val direct = firstNonEmptyMap(json, listOf("analysis", "evidence_analysis", "runlog_analysis"))
        if (direct.isNotEmpty()) return direct
        val toolArgs = firstNonEmptyMap(json, listOf("arguments", "args", "tool_args", "toolArgs"))
        if (toolArgs.isNotEmpty()) {
            val nested = analysisFromAgentUpdateJson(toolArgs)
            if (nested.isNotEmpty()) return nested
        }
        val hasAnalysisShape = listOf(
            "summary",
            "failure_reason",
            "recommended_patch",
            "evidence",
            "confidence",
        ).any(json::containsKey)
        return if (hasAnalysisShape) OobFunctionJson.sanitizeMap(json) else emptyMap()
    }

    private fun patchFromAgentUpdateJson(
        json: Map<String, Any?>,
        analysis: Map<String, Any?>,
    ): Map<String, Any?> {
        val direct = firstNonEmptyMap(json, listOf("patch", "function_patch", "updates", "recommended_patch"))
        if (direct.isNotEmpty()) return direct
        val analysisPatch = firstNonEmptyMap(analysis, listOf("recommended_patch", "patch", "function_patch"))
        if (analysisPatch.isNotEmpty()) return analysisPatch
        val toolArgs = firstNonEmptyMap(json, listOf("arguments", "args", "tool_args", "toolArgs"))
        return if (toolArgs.isNotEmpty()) patchFromAgentUpdateJson(toolArgs, analysis) else emptyMap()
    }

    private fun firstNonEmptyMap(source: Map<String, Any?>, keys: List<String>): Map<String, Any?> {
        for (key in keys) {
            val value = mapFromAny(source[key])
            if (value.isNotEmpty()) return value
        }
        return emptyMap()
    }

    private fun mapFromAny(value: Any?): Map<String, Any?> =
        when (value) {
            is Map<*, *> -> OobFunctionJson.sanitizeMap(value)
            is String -> parseJsonObject(value) ?: emptyMap()
            else -> emptyMap()
        }

    private fun boolArg(value: Any?): Boolean =
        when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.trim().lowercase() in setOf("1", "true", "yes", "y", "on")
            else -> false
        }

    private fun firstNonBlank(vararg values: Any?): String =
        values.firstNotNullOfOrNull { raw ->
            raw?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }.orEmpty()

    private fun agentFailure(
        initial: Map<String, Any?>,
        code: String,
        message: String,
        agentInvoked: Boolean,
        rawResponse: String? = null,
        agentResponse: Map<String, Any?>? = null,
    ): Map<String, Any?> =
        linkedMapOf<String, Any?>().apply {
            putAll(initial)
            put("success", false)
            put("changed", false)
            put("saved", false)
            put("needs_agent_analysis", false)
            put("error_code", code)
            put("error_message", message)
            put("agent_model_invoked", agentInvoked)
            rawResponse?.let { put("agent_raw_response", it) }
            agentResponse?.let { put("agent_response", it) }
        }

    companion object {
        const val AGENT_MODEL = "scene.dispatch.model"
        suspend fun requestAgentAnalysis(prompt: String, responseJsonObject: Boolean): String? =
            HttpController.postLLMRequest(AGENT_MODEL, prompt, responseJsonObject).message

        private val JSON_FENCE_REGEX = Regex(
            "^```(?:json)?\\s*([\\s\\S]*?)\\s*```$",
            setOf(RegexOption.IGNORE_CASE),
        )
    }
}
