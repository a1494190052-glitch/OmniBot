package cn.com.omnimind.bot.vlm

import android.content.Context
import cn.com.omnimind.assists.task.vlmserver.FunctionRunAction
import cn.com.omnimind.assists.task.vlmserver.UIAction
import cn.com.omnimind.assists.task.vlmserver.VLMRecallActionProvider
import cn.com.omnimind.assists.task.vlmserver.VLMRecallContextRequest
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionStreamOptions
import cn.com.omnimind.baselib.llm.contentText
import cn.com.omnimind.assists.task.vlmserver.HttpVLMStreamClient
import cn.com.omnimind.bot.omniflow.OobFunctionJson.listArg
import cn.com.omnimind.bot.omniflow.OobFunctionJson.mapArg
import cn.com.omnimind.bot.runlog.OobOmniFlowToolkitService
import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class VlmRecallFunctionSelector(context: Context) : VLMRecallActionProvider {
    private val appContext = context.applicationContext
    private val TAG = "[VlmRecallFunctionSelector]"
    private val MAX_CANDIDATES = 3
    private val MAX_STEP_SUMMARIES = 3
    private val SELECTOR_MODEL = "scene.vlm.operation.primary"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun selectAction(request: VLMRecallContextRequest): UIAction? {
        val goal = request.context.activeGoal().ifBlank { request.context.overallTask }.trim()
        if (goal.isBlank()) return null

        val recallResult = runCatching {
            OobOmniFlowToolkitService(appContext).recall(
                mapOf(
                    "goal" to goal,
                    "current_package" to request.currentPackageName,
                    "current_xml" to request.currentXml,
                    "k" to MAX_CANDIDATES,
                )
            )
        }.onFailure { OmniLog.w(TAG, "recall failed: ${it.message}") }
            .getOrNull() ?: return null

        val decision = recallResult["decision"]?.toString() ?: return null

        if (decision == "hit") {
            val hit = mapArg(recallResult["hit"])
            val functionId = hit["function_id"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
                ?: return null
            OmniLog.d(TAG, "recall direct hit: $functionId score=${hit["score"]}")
            return FunctionRunAction(functionId = functionId)
        }

        if (decision != "recall") return null
        val candidates = listArg(recallResult["candidates"])
            .filterIsInstance<Map<*, *>>()
            .mapNotNull { c ->
                @Suppress("UNCHECKED_CAST")
                c as? Map<String, Any?>
            }
            .take(MAX_CANDIDATES)
        if (candidates.isEmpty()) return null

        return runCatching {
            selectWithModel(goal, request.currentPackageName, candidates)
        }.onFailure { OmniLog.w(TAG, "model selection failed: ${it.message}") }
            .getOrNull()
    }

    private suspend fun selectWithModel(
        goal: String,
        packageName: String?,
        candidates: List<Map<String, Any?>>,
    ): UIAction? {
        val knownFunctions = runCatching {
            VlmGuidanceManager.getInstance(appContext).loadFunctionsSection(packageName)
        }.getOrNull().orEmpty()
        val prompt = buildSelectionPrompt(goal, packageName, candidates, knownFunctions)
        val request = ChatCompletionRequest(
            model = SELECTOR_MODEL,
            messages = listOf(
                ChatCompletionMessage(role = "user", content = JsonPrimitive(prompt)),
            ),
            maxCompletionTokens = 256,
            streamOptions = ChatCompletionStreamOptions(includeUsage = false),
        )
        val raw = coroutineScope {
            HttpVLMStreamClient(this).streamTurn(request).turn.message.contentText()
        }
        val functionId = parseSelectedFunctionId(raw) ?: return null
        val candidateIds = candidates.mapNotNull { it["function_id"]?.toString()?.trim() }.toSet()
        if (functionId !in candidateIds) {
            OmniLog.w(TAG, "model selected unknown function_id=$functionId, ignoring")
            return null
        }
        OmniLog.d(TAG, "model selected recall function: $functionId")
        return FunctionRunAction(functionId = functionId)
    }

    private fun buildSelectionPrompt(
        goal: String,
        packageName: String?,
        candidates: List<Map<String, Any?>>,
        knownFunctionHints: String = "",
    ): String = buildString {
        append("Goal: $goal\n")
        if (!packageName.isNullOrBlank()) append("Current app: $packageName\n")
        if (knownFunctionHints.isNotBlank()) append("\nKnown useful functions:\n$knownFunctionHints\n")
        append("\nSaved functions:\n")
        candidates.forEachIndexed { i, c ->
            val id = c["function_id"]?.toString() ?: return@forEachIndexed
            val desc = c["description"]?.toString().orEmpty()
            val steps = listArg(c["step_summaries"])
                .take(MAX_STEP_SUMMARIES)
                .joinToString("; ") { it.toString() }
            append("${i + 1}. $id: $desc")
            if (steps.isNotBlank()) append("\n   Steps: $steps")
            append("\n")
        }
        append("\nCan any function above complete this goal exactly as stated?")
        append("\nReply with JSON only: {\"selected\":\"<function_id or null>\"}")
    }

    private fun parseSelectedFunctionId(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val jsonText = raw.substring(start, end + 1)
        val obj = runCatching { json.parseToJsonElement(jsonText) as? JsonObject }
            .getOrNull() ?: return null
        return obj["selected"]?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "null" }
    }
}
