package cn.com.omnimind.bot.omniflow

import cn.com.omnimind.bot.agent.AgentToolJson
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Stepwise update_function enhancement.
 *
 * Instead of one large prompt asking the agent to analyze everything,
 * this orchestrator makes two small, focused calls:
 *   1. Rewrite the function description
 *   2. Suggest parameter definitions
 *
 * Each call sees only the information it needs. Results are merged into
 * a patch and committed through OobFunctionUpdateService in one save.
 */
class OobFunctionStepwiseUpdateOrchestrator(
    private val updateService: OobFunctionUpdateService,
    private val agentRequester: suspend (prompt: String, responseJsonObject: Boolean) -> String? = { prompt, json ->
        OobFunctionUpdateAgentOrchestrator.requestAgentAnalysis(prompt, json)
    },
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = false }

    suspend fun updateFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val initial = updateService.updateFunction(request)
        // Caller already supplied analysis or a patch — let the update service
        // apply it directly without running another round of model calls.
        if (initial["needs_agent_analysis"] != true || hasAnalysisOrPatch(request)) {
            return initial
        }

        val context = OobFunctionJson.mapArg(initial["analysis_context"])
        if (context.isEmpty()) return initial

        val functionSpec = OobFunctionJson.mapArg(context["function"])
        val functionId = OobFunctionJson.firstNonBlank(context["function_id"])
        val steps = OobFunctionJson.listArg(functionSpec["steps"])
            .mapNotNull { OobFunctionJson.mapArg(it).takeIf { m -> m.isNotEmpty() } }
        if (steps.isEmpty()) return initial

        val patch = linkedMapOf<String, Any?>()

        // Two focused calls instead of one big prompt: each model call sees only
        // what it needs, making the response reliable regardless of function size.

        // Step 1: description — one sentence summarising what the function does.
        val newDescription = callForDescription(functionSpec, steps)
        if (!newDescription.isNullOrBlank()) patch["description"] = newDescription

        // Step 2: parameters — which values change per call and where they bind.
        // Bindings are computed in code from model-provided step_index+arg pairs,
        // not written by the model directly, to avoid JSONPath format errors.
        val newParameters = callForParameters(functionSpec, steps)
        if (newParameters != null) patch["parameters"] = newParameters

        if (patch.isEmpty()) {
            return initial + linkedMapOf<String, Any?>(
                "needs_agent_analysis" to false,
                "stepwise" to true,
                "message" to "逐步增强完成，未生成有效更新。",
            )
        }

        val nextArgs = linkedMapOf<String, Any?>().apply {
            putAll(request)
            put("patch", patch)
            put("source", "oob_function_stepwise_update_orchestrator")
        }
        val updated = updateService.updateFunction(nextArgs)
        return updated + linkedMapOf<String, Any?>(
            "stepwise" to true,
            "stepwise_patch_keys" to patch.keys.toList(),
        )
    }

    // --- Step 1: description ---

    private suspend fun callForDescription(
        spec: Map<String, Any?>,
        steps: List<Map<String, Any?>>,
    ): String? {
        val name = OobFunctionJson.firstNonBlank(spec["name"])
        val current = OobFunctionJson.firstNonBlank(spec["description"])
        val stepLines = stepSummaryLines(steps)
        val prompt = buildString {
            appendLine("为下面的 OOB Function 写一句准确、简洁的中文描述（不超过 40 字）。")
            appendLine()
            if (name.isNotBlank()) appendLine("名称: $name")
            if (current.isNotBlank()) appendLine("当前描述: $current")
            appendLine()
            appendLine("步骤:")
            stepLines.forEach { appendLine("  $it") }
            appendLine()
            append("""输出 JSON（不含其他文字）：{"description":"新描述"}""")
        }
        val raw = request(prompt) ?: return null
        val parsed = extractJsonObject(raw) ?: return null
        return OobFunctionJson.firstNonBlank(parsed["description"])
            .takeIf { it.isNotBlank() && it != current }
    }

    // --- Step 2: parameters with bindings ---

    private suspend fun callForParameters(
        spec: Map<String, Any?>,
        steps: List<Map<String, Any?>>,
    ): List<Map<String, Any?>>? {
        val name = OobFunctionJson.firstNonBlank(spec["name"])
        val description = OobFunctionJson.firstNonBlank(spec["description"])
        val prompt = buildString {
            appendLine("分析下面 OOB Function，找出每次调用时会变化的输入值（如搜索词、消息文本、金额等），将它们定义为参数并说明绑定到哪些步骤的哪个字段。")
            appendLine("不要将包名、坐标、UI 目标描述列为参数。同一参数可绑定多个步骤（全链路复用）。若无需参数，输出空数组。")
            appendLine()
            if (name.isNotBlank()) appendLine("名称: $name")
            if (description.isNotBlank()) appendLine("描述: $description")
            appendLine()
            appendLine("步骤（含可绑定字段）:")
            steps.forEachIndexed { i, step ->
                val tool = OobFunctionJson.firstNonBlank(step["tool"])
                val title = OobFunctionJson.firstNonBlank(step["title"], step["id"])
                val args = OobFunctionJson.mapArg(step["args"])
                val bindableArgs = bindableArgsForTool(tool, args)
                val titleSuffix = if (title.isNotBlank()) "  ($title)" else ""
                if (bindableArgs.isEmpty()) {
                    appendLine("  $i. $tool$titleSuffix")
                } else {
                    val argStr = bindableArgs.entries.joinToString("  ") { (k, v) -> "$k=\"$v\"" }
                    appendLine("  $i. $tool  $argStr$titleSuffix  ← 可绑定: ${bindableArgs.keys.joinToString(", ")}")
                }
            }
            appendLine()
            appendLine("输出 JSON（不含其他文字）：")
            appendLine("""{"parameters":[{"name":"param_name","type":"string","description":"说明","bindings":[{"step_index":N,"arg":"字段名"}]}]}""")
            append("""无参数时：{"parameters":[]}""")
        }
        val raw = request(prompt) ?: return null
        val parsed = extractJsonObject(raw) ?: return null
        val list = OobFunctionJson.listArg(parsed["parameters"])
        if (list.isEmpty()) return emptyList()
        return list.mapNotNull { raw ->
            val param = OobFunctionJson.mapArg(raw).toMutableMap()
            if (param.isEmpty()) return@mapNotNull null
            // Convert {step_index, arg} binding specs to canonical JSONPath strings
            val bindingSpecs = OobFunctionJson.listArg(param["bindings"])
            val jsonPaths = bindingSpecs.mapNotNull { spec ->
                val b = OobFunctionJson.mapArg(spec)
                val stepIdx = OobFunctionJson.intArg(b["step_index"], b["stepIndex"], defaultValue = -1)
                val argKey = OobFunctionJson.firstNonBlank(b["arg"], b["arg_key"], b["field"])
                if (stepIdx >= 0 && argKey.isNotBlank()) {
                    "$.execution.steps[$stepIdx].args.$argKey"
                } else null
            }.distinct()
            if (jsonPaths.isNotEmpty()) param["bindings"] = jsonPaths
            linkedMapOf<String, Any?>().apply { putAll(param) }
        }
    }

    private fun bindableArgsForTool(tool: String, args: Map<String, Any?>): Map<String, String> =
        when (tool) {
            "input_text" -> listOf("text").mapNotNull { k ->
                val v = OobFunctionJson.firstNonBlank(args[k])
                if (v.isNotBlank()) k to v.take(30) else null
            }.toMap()
            "call_tool" -> OobFunctionJson.mapArg(args["arguments"]).entries
                .filter { (_, v) -> v is String && v.isNotBlank() }
                .associate { (k, v) -> "arguments.$k" to v.toString().take(30) }
            else -> emptyMap()
        }

    // --- Helpers ---

    private fun stepSummaryLines(steps: List<Map<String, Any?>>): List<String> =
        steps.mapIndexed { i, step ->
            val tool = OobFunctionJson.firstNonBlank(step["tool"])
            val title = OobFunctionJson.firstNonBlank(step["title"], step["id"])
            val args = OobFunctionJson.mapArg(step["args"])
            val argHint = when (tool) {
                "click", "long_press" ->
                    OobFunctionJson.firstNonBlank(args["target_description"])
                        .takeIf { it.isNotBlank() }?.let { "\"$it\"" } ?: ""
                "input_text" ->
                    OobFunctionJson.firstNonBlank(args["text"])
                        .takeIf { it.isNotBlank() }?.let { "text=\"${it.take(30)}\"" } ?: ""
                "open_app" ->
                    OobFunctionJson.firstNonBlank(args["package_name"]).let { "pkg=$it" }
                "swipe" -> OobFunctionJson.firstNonBlank(args["direction"])
                "press_key" -> OobFunctionJson.firstNonBlank(args["key"])
                else -> ""
            }
            val parts = listOfNotNull(
                tool.takeIf { it.isNotBlank() },
                argHint.takeIf { it.isNotBlank() },
                title.takeIf { it.isNotBlank() }?.let { "($it)" },
            )
            "$i. ${parts.joinToString("  ")}"
        }

    private suspend fun request(prompt: String): String? = try {
        agentRequester(prompt, true).orEmpty()
            .ifBlank { agentRequester(prompt, false).orEmpty() }
            .takeIf { it.isNotBlank() }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun hasAnalysisOrPatch(request: Map<String, Any?>): Boolean =
        listOf("analysis", "evidence_analysis", "runlog_analysis").any {
            OobFunctionJson.mapArg(request[it]).isNotEmpty()
        } || listOf("patch", "function_patch", "updates").any {
            OobFunctionJson.mapArg(request[it]).isNotEmpty()
        }

    private fun extractJsonObject(raw: String): Map<String, Any?>? {
        val trimmed = raw.trim()
        for (candidate in listOf(trimmed, stripJsonFence(trimmed), substringJsonObject(trimmed))) {
            val map = parseJsonObject(candidate)
            if (!map.isNullOrEmpty()) return map
        }
        return null
    }

    private fun stripJsonFence(raw: String): String =
        JSON_FENCE_REGEX.find(raw.trim())?.groupValues?.getOrNull(1)?.trim() ?: raw

    private fun substringJsonObject(raw: String): String {
        val start = raw.indexOf('{')
        if (start < 0) return ""
        var depth = 0
        for (i in start..raw.lastIndex) {
            when (raw[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return raw.substring(start, i + 1)
            }
        }
        return ""
    }

    private fun parseJsonObject(raw: String): Map<String, Any?>? {
        val text = raw.trim().ifEmpty { return null }
        return runCatching {
            AgentToolJson.jsonObjectToMap(
                json.parseToJsonElement(text) as? JsonObject ?: return null
            )
        }.getOrNull()
    }

    private companion object {
        private val JSON_FENCE_REGEX = Regex(
            "^```(?:json)?\\s*([\\s\\S]*?)\\s*```$",
            setOf(RegexOption.IGNORE_CASE),
        )
    }
}
