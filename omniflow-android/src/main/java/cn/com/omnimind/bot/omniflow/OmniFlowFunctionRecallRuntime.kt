package cn.com.omnimind.bot.omniflow

import android.content.Context
import cn.com.omnimind.baselib.llm.ChatCompletionFunction
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionStreamOptions
import cn.com.omnimind.baselib.llm.ChatCompletionTool
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal object OmniFlowFunctionRecallRuntime {
    private const val REJECT_TOOL = "reject_recalled_function"
    private const val MAX_VISIBLE_FUNCTIONS = 32
    private val functionNamePattern = Regex("[A-Za-z0-9_-]{1,64}")
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    data class Candidate(
        val functionId: String,
        val name: String,
        val description: String,
        val inputSchema: JsonObject,
    )

    data class Selection(
        val toolCall: OmniFlow.ToolCall,
        val turn: ChatCompletionTurn,
    )

    suspend fun tryExecute(
        context: Context,
        request: OmniVlmPlugin.Request,
        modelClient: OmniFlowModelClient,
        hooks: OmniVlmPlugin.Hooks,
    ): OmniVlmPlugin.Result? {
        val candidates = try {
            loadCandidates(context, modelClient)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return null
        }
        if (candidates.isEmpty()) return null

        val selection = try {
            route(request.goal, candidates, modelClient)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return null
        } ?: return null

        val execution = OmniFlow.callTool(
            context = context,
            toolCall = selection.toolCall,
            goal = request.goal,
            runId = recallRunId(request.runId),
            source = "function",
            runLogToolName = OmniVlmPlugin.RUN_LOG_TOOL,
            modelClient = modelClient,
            hooks = OmniFlow.Hooks(
                beforeOperation = hooks.beforeOperation,
                stopRequested = hooks.stopRequested,
                onProgress = hooks.onProgress,
            ),
        )
        recordRecallDiagnostics(context, recallRunId(request.runId), selection)
        return OmniVlmPlugin.Result(
            payload = execution.payload + mapOf(
                "recall_hit" to true,
                "recalled_function_id" to selection.toolCall.name,
            ),
            finalStateId = execution.finalStateId,
        )
    }

    internal fun recallRunId(runId: String): String = "$runId-recall"

    internal suspend fun route(
        goal: String,
        candidates: List<Candidate>,
        modelClient: OmniFlowModelClient,
    ): Selection? {
        val visible = candidates
            .asSequence()
            .filter { it.functionId != REJECT_TOOL && functionNamePattern.matches(it.functionId) }
            .distinctBy(Candidate::functionId)
            .take(MAX_VISIBLE_FUNCTIONS)
            .toList()
        if (visible.isEmpty()) return null
        val request = buildRequest(goal, visible)
        val turn = modelClient.streamTurn(request)
        val calls = turn.message.toolCalls.orEmpty()
        if (calls.size != 1) return null
        val call = calls.single().function
        val functionId = call.name.trim()
        if (functionId == REJECT_TOOL) return null
        val candidate = visible.singleOrNull { it.functionId == functionId } ?: return null
        val arguments = runCatching {
            json.parseToJsonElement(call.arguments.ifBlank { "{}" }) as? JsonObject
        }.getOrNull() ?: return null
        if (!argumentsMatch(candidate.inputSchema, arguments)) return null
        return Selection(
            toolCall = OmniFlow.ToolCall(
                name = functionId,
                arguments = arguments.mapValues { (_, value) -> value.toRuntimeValue() },
            ),
            turn = turn,
        )
    }

    internal fun buildRequest(
        goal: String,
        candidates: List<Candidate>,
    ): ChatCompletionRequest = ChatCompletionRequest(
        model = OmniVlmPlugin.MODEL_SCENE,
        messages = listOf(
            ChatCompletionMessage(
                role = "system",
                content = JsonPrimitive(
                    "Decide whether one recalled Android GUI Function fully covers the " +
                        "user's complete goal. Select a Function only when its semantic " +
                        "name and description match the whole goal. Fill arguments only " +
                        "from values explicit in the goal; never guess missing values. " +
                        "Otherwise call reject_recalled_function. Return exactly one " +
                        "provided native tool call.",
                ),
            ),
            ChatCompletionMessage(
                role = "user",
                content = buildJsonObject { put("goal", JsonPrimitive(goal.trim())) },
            ),
        ),
        maxCompletionTokens = 256,
        temperature = 0.0,
        stream = true,
        streamOptions = ChatCompletionStreamOptions(),
        reasoningEffort = "none",
        tools = candidates.map { candidate ->
            ChatCompletionTool(
                function = ChatCompletionFunction(
                    name = candidate.functionId,
                    description = listOf(
                        "Semantic name: ${candidate.name}",
                        "Description: ${candidate.description}",
                    ).joinToString("\n"),
                    parameters = candidate.inputSchema,
                ),
            )
        } + ChatCompletionTool(
            function = ChatCompletionFunction(
                name = REJECT_TOOL,
                description = "Reject all recalled Functions because none covers the complete goal.",
                parameters = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("properties", JsonObject(emptyMap()))
                    put("required", JsonArray(emptyList()))
                    put("additionalProperties", JsonPrimitive(false))
                },
            ),
        ),
        toolChoice = JsonPrimitive("required"),
        parallelToolCalls = false,
    )

    private suspend fun loadCandidates(
        context: Context,
        modelClient: OmniFlowModelClient,
    ): List<Candidate> {
        val payload = OmniFlow.callTool(
            context = context,
            toolCall = OmniFlow.ToolCall(
                name = "list_functions",
                arguments = mapOf(
                    "limit" to MAX_VISIBLE_FUNCTIONS,
                    "offset" to 0,
                    "include_hidden" to false,
                ),
            ),
            modelClient = modelClient,
        ).payload
        if (payload["success"] != true) return emptyList()
        return (payload["functions"] as? List<*>)
            .orEmpty()
            .mapNotNull { value -> candidate(mapValue(value)) }
    }

    private fun candidate(value: Map<String, Any?>): Candidate? {
        if (value["agent_visible"] == false) return null
        val functionId = firstText(value["function_id"])
        if (!functionNamePattern.matches(functionId)) return null
        val inputSchema = jsonValue(mapValue(value["input_schema"])) as? JsonObject
            ?: return null
        return Candidate(
            functionId = functionId,
            name = firstText(value["name"], functionId),
            description = firstText(value["description"]),
            inputSchema = inputSchema,
        )
    }

    private fun argumentsMatch(schema: JsonObject, arguments: JsonObject): Boolean {
        val properties = (schema["properties"] as? JsonObject).orEmpty()
        val required = (schema["required"] as? JsonArray)
            .orEmpty()
            .mapNotNull { it.jsonPrimitive.contentOrNull }
        if (required.any { it !in arguments }) return false
        val additionalProperties = schema["additionalProperties"]
            ?.jsonPrimitive
            ?.booleanOrNull
        if (additionalProperties == false && arguments.keys.any { it !in properties }) return false
        return true
    }

    private fun recordRecallDiagnostics(
        context: Context,
        runId: String,
        selection: Selection,
    ) {
        val usage = selection.turn.usage
        InternalRunLogStore.updateDiagnostics(
            context = context,
            runId = runId,
            diagnostics = mapOf(
                "function_recall" to linkedMapOf<String, Any?>(
                    "hit" to true,
                    "function_id" to selection.toolCall.name,
                    "model" to OmniVlmPlugin.MODEL_SCENE,
                    "resolved_model" to selection.turn.resolvedModel,
                    "prompt_tokens" to usage?.promptTokens,
                    "completion_tokens" to usage?.completionTokens,
                    "total_tokens" to usage?.totalTokens,
                ).filterValues { it != null },
            ),
        )
    }

    private fun JsonElement.toRuntimeValue(): Any? = when (this) {
        JsonNull -> null
        is JsonObject -> mapValues { (_, value) -> value.toRuntimeValue() }
        is JsonArray -> map { value -> value.toRuntimeValue() }
        is JsonPrimitive -> when {
            isString -> content
            booleanOrNull != null -> booleanOrNull
            else -> content.toLongOrNull() ?: content.toDoubleOrNull() ?: content
        }
    }
}
