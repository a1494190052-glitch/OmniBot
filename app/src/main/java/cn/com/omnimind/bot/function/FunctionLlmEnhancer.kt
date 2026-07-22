package cn.com.omnimind.bot.function

import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.bot.agent.AgentToolJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal data class FunctionLlmEnhancement(
    val updated: Map<String, Any?>,
    val changes: List<Map<String, Any?>>,
    val status: String,
)

internal class FunctionLlmEnhancer(
    private val requester: suspend (String) -> String = { prompt ->
        HttpController.postLLMRequest(
            model = MODEL,
            text = prompt,
            responseJsonObject = true,
            maxTokens = 1800,
            temperature = 0.1,
        ).message
    },
) {
    suspend fun enhance(spec: Map<String, Any?>): FunctionLlmEnhancement {
        val proposal = parseProposal(requester(buildPrompt(spec)))
        return applyProposal(spec, proposal)
    }

    internal fun applyProposal(
        spec: Map<String, Any?>,
        proposal: Map<String, Any?>,
    ): FunctionLlmEnhancement {
        val updated = FunctionJson.mutableJsonMap(spec)
        val functionId = FunctionJson.firstNonBlank(updated["function_id"])
        val changes = mutableListOf<Map<String, Any?>>()
        replaceText(updated, "name", proposal["name"], 80, changes)
        replaceText(updated, "description", proposal["description"], 2000, changes)

        val status = if (changes.isEmpty()) "unchanged" else "enhanced"
        updated["function_id"] = functionId
        return FunctionLlmEnhancement(updated, changes, status)
    }

    private fun buildPrompt(spec: Map<String, Any?>): String {
        val actions = readActions(spec).mapIndexed { index, raw ->
            val action = FunctionJson.mapArg(raw)
            val args = FunctionJson.mapArg(action["args"])
            linkedMapOf<String, Any?>(
                "index" to index,
                "tool" to FunctionJson.firstNonBlank(action["tool"]),
                "title" to FunctionJson.firstNonBlank(
                    action["title"],
                    action["description"],
                ).take(120),
                "target" to FunctionJson.firstNonBlank(args["target_description"])
                    .take(120)
                    .takeIf { it.isNotBlank() },
            ).filterValues { it != null && it != "" }
        }
        val brief = linkedMapOf(
            "function_id" to FunctionJson.firstNonBlank(spec["function_id"]),
            "name" to FunctionJson.firstNonBlank(spec["name"]),
            "description" to FunctionJson.firstNonBlank(spec["description"]),
            "steps" to actions,
        )
        return """
            Improve the reusable Android automation Function below for future recall.
            Return one JSON object with optional keys: name and description.
            Describe when to reuse the Function, visible operations, inputs, success signal, and avoid cases.
            Never add, remove, reorder, or alter actions, tools, arguments, coordinates, selectors, or function_id.
            Do not invent app state. Use the same language as the current name/description.

            Function:
            ${AgentToolJson.mapToJsonElement(brief)}
        """.trimIndent()
    }

    private fun parseProposal(raw: String): Map<String, Any?> {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        require(start >= 0 && end > start) { "LLM enhancement returned no JSON object" }
        val parsed = json.parseToJsonElement(raw.substring(start, end + 1)) as? JsonObject
            ?: error("LLM enhancement returned invalid JSON")
        return AgentToolJson.jsonObjectToMap(parsed)
    }

    private fun readActions(spec: Map<String, Any?>): List<Any?> {
        return FunctionJson.listArg(spec["steps"]).map { raw ->
            FunctionJson.mapArg(FunctionJson.mapArg(raw)["action"])
        }
    }

    private fun replaceText(
        target: MutableMap<String, Any?>,
        key: String,
        raw: Any?,
        limit: Int,
        changes: MutableList<Map<String, Any?>>,
    ) {
        val value = safeText(raw, limit)
        if (value.isNotEmpty() && target[key] != value) {
            target[key] = value
            changes += change("function", key)
        }
    }

    private fun safeText(value: Any?, limit: Int): String =
        value?.toString()?.trim()?.take(limit).orEmpty()

    private fun change(part: String, field: String, index: Int? = null): Map<String, Any?> =
        linkedMapOf("part" to part, "field" to field, "step_index" to index)
            .filterValues { it != null }

    companion object {
        private const val MODEL = "scene.dispatch.model"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
