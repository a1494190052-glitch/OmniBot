package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import cn.com.omnimind.baselib.llm.ModelSceneRegistry
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.sse.EventSource

const val ACTION_FAILURE_PREFIX = "执行失败"

@Serializable
sealed class VLMCommand {
    abstract val name: String
}

@Serializable
@SerialName("action")
data class Action(
    val tool: String,
    val args: JsonObject = buildJsonObject {},
) : VLMCommand() {
    override val name: String get() = tool
}

@Serializable
@SerialName("observe")
data class Observe(
    val reason: String = "",
) : VLMCommand() {
    override val name: String = "get_state"
}

@Serializable
@SerialName("function_invocation")
data class FunctionInvocation(
    @SerialName("function_id")
    val functionId: String,
    val arguments: JsonObject = buildJsonObject {}
) : VLMCommand() {
    override val name: String = "call_tool"
}

@Serializable
@SerialName("record")
data class RecordMemory(
    val content: String
) : VLMCommand() {
    override val name: String = "record"
}

@Serializable
sealed class AgentDecision : VLMCommand()

@Serializable
@SerialName("finished")
data class FinishedDecision(
    val content: String = ""
) : AgentDecision() {
    override val name: String = "finished"
}

@Serializable
@SerialName("info")
data class InfoDecision(
    val value: String
) : AgentDecision() {
    override val name: String = "info"
}

@Serializable
@SerialName("abort")
data class AbortDecision(
    val value: String = ""
) : AgentDecision() {
    override val name: String = "abort"
}

fun actionOf(tool: String, args: Map<String, Any?> = emptyMap()): Action = Action(
    tool = tool,
    args = JsonObject(args.mapValues { (_, value) -> value.toJsonElement() }),
)

fun Action.toCanonicalMap(): Map<String, Any?> = linkedMapOf(
    "tool" to tool,
    "args" to args.toValueMap(),
)

fun Action.argsMap(): Map<String, Any?> = args.toValueMap()

internal fun Action.stringArg(name: String): String =
    args[name]?.let(JsonElement::toValue)?.toString()?.trim().orEmpty()

internal fun Action.floatArg(name: String): Float =
    args[name]?.let(JsonElement::toValue)?.toString()?.toFloatOrNull() ?: 0f

internal fun Action.longArg(name: String, defaultValue: Long = 0L): Long =
    args[name]?.let(JsonElement::toValue)?.toString()?.toDoubleOrNull()?.toLong() ?: defaultValue

internal fun Action.withArgs(values: Map<String, Any?>): Action = actionOf(tool, values)

private fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.associate { (key, value) ->
        require(key is String) { "canonical_action_arg_key_invalid" }
        key to value.toJsonElement()
    })
    is Iterable<*> -> JsonArray(map { it.toJsonElement() })
    is Array<*> -> JsonArray(map { it.toJsonElement() })
    else -> error("canonical_action_arg_type_invalid:${this::class.java.simpleName}")
}

private fun JsonObject.toValueMap(): Map<String, Any?> =
    entries.associateTo(linkedMapOf()) { (key, value) -> key to value.toValue() }

private fun JsonElement.toValue(): Any? = when (this) {
    JsonNull -> null
    is JsonObject -> toValueMap()
    is JsonArray -> map(JsonElement::toValue)
    is JsonPrimitive -> when {
        isString -> content
        content == "true" -> true
        content == "false" -> false
        else -> content.toLongOrNull() ?: content.toDoubleOrNull() ?: content
    }
}

// ==================== 步骤和上下文 ====================

@Serializable
data class StateDisplay(
    val width: Int,
    val height: Int,
)

@Serializable
data class State(
    @SerialName("state_id")
    val stateId: String,
    val xml: String? = null,
    @SerialName("package_name")
    val packageName: String? = null,
    @SerialName("activity_name")
    val activityName: String? = null,
    val display: StateDisplay? = null,
    @Transient
    val screenshotBase64: String? = null,
)

@Serializable
data class UIStep(
    val observation: String,
    val thought: String,
    val action: VLMCommand,
    val result: String? = null,
    val summary: String = "",  // 添加summary字段用于历史总结
    @SerialName("before_state")
    val beforeState: State? = null,
    @SerialName("after_state")
    val afterState: State? = null,
    @SerialName("action_result_data")
    val actionResultData: JsonElement? = null,
    @SerialName("started_at_ms")
    val startedAtMs: Long? = null,
    @SerialName("finished_at_ms")
    val finishedAtMs: Long? = null,
    @SerialName("token_usage")
    val tokenUsage: VLMTokenUsage? = null,
    @SerialName("token_usage_attempts")
    val tokenUsageAttempts: List<VLMTokenUsage> = emptyList(),
    @SerialName("page_diagnostics")
    val pageDiagnostics: Map<String, String> = emptyMap(),
)

@Serializable
data class VLMTokenUsage(
    @SerialName("prompt_tokens")
    val promptTokens: Int? = null,
    @SerialName("completion_tokens")
    val completionTokens: Int? = null,
    @SerialName("total_tokens")
    val totalTokens: Int? = null,
    @SerialName("reasoning_tokens")
    val reasoningTokens: Int? = null,
    @SerialName("text_tokens")
    val textTokens: Int? = null,
    @SerialName("cached_tokens")
    val cachedTokens: Int? = null,
    @SerialName("prefill_tokens_per_second")
    val prefillTokensPerSecond: Double? = null,
    @SerialName("decode_tokens_per_second")
    val decodeTokensPerSecond: Double? = null,
    @SerialName("resolved_model")
    val resolvedModel: String? = null,
    val route: String? = null,
    @SerialName("attempt_index")
    val attemptIndex: Int? = null,
    @SerialName("stability_attempt")
    val stabilityAttempt: Int? = null,
    @SerialName("tool_retry_index")
    val toolRetryIndex: Int? = null,
    @SerialName("attempt_count")
    val attemptCount: Int? = null
)

@Serializable
data class UIContext(
    @SerialName("overall_task")
    val overallTask: String,
    @SerialName("current_step_goal")
    val currentStepGoal: String = "",
    @SerialName("step_skill_guidance")
    val stepSkillGuidance: String = "",
    @SerialName("installed_applications")
    val installedApplications: Map<String, String> = emptyMap(),
    @SerialName("target_package_name")
    val targetPackageName: String = "",
    @SerialName("current_package_name")
    val currentPackageName: String = "",
    @SerialName("display_width")
    val displayWidth: Int = 0,
    @SerialName("display_height")
    val displayHeight: Int = 0,
    @SerialName("current_page_summary")
    val currentPageSummary: String = "",
    @SerialName("first_step_guidance")
    val firstStepGuidance: String = "",
    @SerialName("page_diagnostics")
    val pageDiagnostics: Map<String, String> = emptyMap(),
    @SerialName("dynamic_tool_definitions")
    val dynamicToolDefinitions: List<JsonObject> = emptyList(),
    @SerialName("key_memory")
    val keyMemory: List<String> = emptyList(),
    @SerialName("max_steps")
    val maxSteps: Int? = null,
    @SerialName("steps_used")
    val stepsUsed: Int = 0,
    @SerialName("steps_remaining")
    val stepsRemaining: Int? = null,
    @SerialName("transient_events")
    val transientEvents: List<VLMContextEvent> = emptyList(),
    @SerialName("priority_event")
    val priorityEvent: String? = null,  // High-priority event message (e.g., file received)
    @SerialName("priority_event_type")
    val priorityEventType: String? = null,  // Event type (e.g., "file_received")
    @SerialName("suggest_completion")
    val suggestCompletion: Boolean = false,  // Hint that task should complete
    @SerialName("allowed_vlm_tool_names")
    val allowedVlmToolNames: List<String> = emptyList()
    // 注意：screenshot不在这里，会单独传递（对应Python中的exclude=True）
) {
    fun activeGoal(): String = currentStepGoal.ifBlank { overallTask }
}

data class SceneChatCompletionResponse(
    val success: Boolean,
    val code: String,
    val message: String,
    val parser: ModelSceneRegistry.ResponseParser,
    val route: String? = null,
    val content: String = "",
    val reasoning: String = "",
    val finishReason: String? = null,
    val toolCalls: List<AssistantToolCall> = emptyList(),
    val rawResponseBody: String? = null
)

data class SceneChatCompletionStreamHandle(
    val eventSource: EventSource,
    val parser: ModelSceneRegistry.ResponseParser,
    val route: String? = null,
    val resolvedModel: String
)

data class SceneChatCompletionTurn(
    val parser: ModelSceneRegistry.ResponseParser,
    val route: String? = null,
    val resolvedModel: String,
    val turn: ChatCompletionTurn,
    val requestVariant: String? = null,
    val requestHadTools: Boolean? = null,
    val requestToolChoice: String? = null,
    val requestParallelToolCalls: Boolean? = null
)

@Serializable
data class StepMetadataPayload(
    val observation: String = "",
    val thought: String = "",
    val summary: String = ""
)

@Serializable
data class VLMThinkingContext(
    val observation: String = "",
    val thought: String = "",
    val summary: String = "",
    val reasoning: String = "",
    val rawContent: String = "",
    val finishReason: String? = null
)

@Serializable
data class VLMToolCallFailure(
    val code: String,
    @SerialName("tool_name")
    val toolName: String? = null,
    @SerialName("required_fields")
    val requiredFields: List<String> = emptyList(),
    @SerialName("provided_fields")
    val providedFields: List<String> = emptyList(),
    @SerialName("argument_types")
    val argumentTypes: Map<String, String> = emptyMap(),
    @SerialName("missing_fields")
    val missingFields: List<String> = emptyList(),
    @SerialName("safe_arguments_preview")
    val safeArgumentsPreview: String? = null,
    val message: String
)

data class VLMToolCallRetryState(
    val retryIndex: Int,
    val thinking: VLMThinkingContext,
    val failureReason: String? = null,
    val previousToolCall: AssistantToolCall? = null,
    val toolCallFailure: VLMToolCallFailure? = null
)

@Serializable
data class VLMContextEvent(
    val type: String,
    val text: String,
    val source: String = "external",
    @SerialName("created_at_ms")
    val createdAtMs: Long = System.currentTimeMillis(),
    @SerialName("suggest_completion")
    val suggestCompletion: Boolean = false,
)

data class VLMRequestEnvelope(
    val request: cn.com.omnimind.baselib.llm.ChatCompletionRequest,
    val currentUserText: String,
    val dynamicFunctionToolNames: Set<String> = emptySet(),
    val dynamicFunctionToolMappings: Map<String, String> = emptyMap(),
    val dynamicFunctionRequiredArguments: Map<String, Set<String>> = emptyMap(),
    val toolNames: List<String> = emptyList(),
    val defaultToolCount: Int = 0,
    val selectedBaseToolNames: Set<String> = emptySet(),
    val systemPromptChars: Int = 0,
    val currentUserTextChars: Int = currentUserText.length,
)

@Serializable
data class OperationResult(
    val success: Boolean,
    val message: String,
    val data: JsonElement? = null,
    val providerRunLogJson: String? = null,
    val providerRunLogPath: String? = null,
    val canonicalRunLogPath: String? = null,
    val diagnostics: Map<String, String> = emptyMap(),
    val beforeState: State? = null,
    val afterState: State? = null,
)

@Serializable
data class VLMResult(
    val success: Boolean,
    val step: UIStep? = null,
    val error: String? = null,
    val thinking: VLMThinkingContext? = null,
    val shouldRetryForToolCall: Boolean = false,
    val previousToolCall: AssistantToolCall? = null,
    val toolCallFailure: VLMToolCallFailure? = null
)

fun UIContext.budgetDiagnostics(): Map<String, String> = linkedMapOf(
    "vlm_context_current_page_summary_chars" to currentPageSummary.length.toString(),
    "vlm_context_step_skill_guidance_chars" to stepSkillGuidance.length.toString(),
    "vlm_context_key_memory_count" to keyMemory.size.toString(),
    "vlm_context_transient_event_count" to transientEvents.size.toString(),
    "vlm_context_transient_event_chars" to transientEvents.sumOf { it.text.length }.toString(),
    "vlm_context_installed_app_count" to installedApplications.size.toString(),
    "vlm_context_dynamic_tool_definition_count" to dynamicToolDefinitions.size.toString(),
)
