package cn.com.omnimind.bot.vlm

import android.content.Context
import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
import cn.com.omnimind.assists.task.vlmserver.UIContext
import cn.com.omnimind.assists.task.vlmserver.VLMContextEvent
import cn.com.omnimind.assists.task.vlmserver.VLMCurrentPageSnapshot
import cn.com.omnimind.assists.task.vlmserver.VLMIndexedPageContext
import cn.com.omnimind.assists.task.vlmserver.VLMRecallContextProviderRegistry
import cn.com.omnimind.assists.task.vlmserver.VLMRecallContextRequest
import cn.com.omnimind.assists.task.vlmserver.VLMTokenUsageMapper
import cn.com.omnimind.assists.task.vlmserver.VlmTaskEngineHost
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionTool
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import cn.com.omnimind.baselib.runlog.OobActionSchema
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolCatalog
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolExecutor
import cn.com.omnimind.bot.agent.AgentToolJson
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.AgentTurnContextProvider
import cn.com.omnimind.bot.agent.AgentTurnObserver
import cn.com.omnimind.bot.agent.AgentTurnRequestOptions
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.omniflow.OmniFlowPythonHostCall
import cn.com.omnimind.bot.omniflow.OmniFlowPythonRuntime
import cn.com.omnimind.bot.omniflow.omniFlowAndroidHostCall
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.Base64

internal data class AndroidGuiTaskConfig(
    val runId: String,
    val goal: String,
    val model: String,
    val maxSteps: Int?,
    val packageName: String?,
    val stepSkillGuidance: String,
    val disableFunctionRecall: Boolean,
)

internal class AndroidGuiToolbox(
    context: Context?,
    private val config: AndroidGuiTaskConfig,
    private val host: VlmTaskEngineHost,
    private val policy: AndroidGuiPolicy = AndroidGuiPolicy(),
    private val installedApps: suspend () -> Map<String, String> = {
        AccessibilityController.mapInstalledApplications()
    },
    androidHostOverride: OmniFlowPythonHostCall? = null,
    private val functionRunnerOverride: (suspend (
        payload: Map<String, Any?>,
        hostCall: OmniFlowPythonHostCall,
    ) -> Map<String, Any?>)? = null,
) : AgentToolCatalog, AgentToolExecutor, AgentTurnContextProvider, AgentTurnObserver {
    data class Terminal(
        val success: Boolean,
        val reason: String,
        val content: String,
    )

    private data class ActionOutcome(
        val code: String,
        val stateChanged: Boolean,
        val needsReview: Boolean,
    )

    private val appContext = context?.applicationContext
    private val recordedSteps = mutableListOf<Map<String, Any?>>()
    private val androidHost: OmniFlowPythonHostCall = androidHostOverride
        ?: omniFlowAndroidHostCall(
            context = requireNotNull(appContext) { "android_gui_context_required" },
            deviceOperator = host.deviceOperator,
            onAction = { action -> host.onActionStarted(action, turnMetadata) },
            onStep = ::recordFunctionStep,
        )

    private var preparedContext: ChatCompletionMessage? = null
    private var initialSystemMessage: ChatCompletionMessage? = null
    private var initialRequestOptions: AgentTurnRequestOptions? = null
    private var currentState: Map<String, Any?>? = null
    private var reuseCurrentState = false
    private var dynamicFunctionMappings: Map<String, String> = emptyMap()
    private lateinit var currentTurnRequest: AndroidGuiTurnRequest
    private var turnMetadata: Map<String, Any?> = emptyMap()
    private var turnIndex = 0
    private var attachTurnMetadataToNextFunctionStep = false
    private var pendingReviewState: Map<String, Any?>? = null
    private var failureStreak = 0

    override var toolsForModel: List<ChatCompletionTool> = emptyList()
        private set

    var terminal: Terminal? = null
        private set

    val finalStateId: String?
        get() = currentState?.get("state_id")?.toString()?.trim()?.takeIf(String::isNotEmpty)

    suspend fun prepare() {
        if (preparedContext != null) return
        val envelope = buildTurnEnvelope()
        initialSystemMessage = envelope.request.messages.firstOrNull { it.role == "system" }
            ?: error("vlm_system_message_required")
        preparedContext = envelope.request.messages.lastOrNull { it.role == "user" }
            ?: error("vlm_user_observation_required")
        initialRequestOptions = AgentTurnRequestOptions(
            model = envelope.request.model,
            modelOverride = envelope.request.modelOverride,
            maxCompletionTokens = envelope.request.maxCompletionTokens,
            temperature = envelope.request.temperature,
            toolChoice = envelope.request.toolChoice,
            parallelToolCalls = false,
            enableThinking = envelope.request.enableThinking,
            reasoningEffort = envelope.request.reasoningEffort,
            thinking = envelope.request.thinking,
            maxModelRounds = (config.maxSteps ?: DEFAULT_MAX_STEPS).coerceAtLeast(1) + 4,
            maxToolCallsPerTurn = 1,
        )
    }

    fun initialMessages(): List<ChatCompletionMessage> = listOf(
        requireNotNull(initialSystemMessage) { "android_gui_toolbox_not_prepared" },
        ChatCompletionMessage(
            role = "user",
            content = JsonPrimitive("GUI task: ${config.goal}"),
        ),
    )

    fun requestOptions(): AgentTurnRequestOptions =
        requireNotNull(initialRequestOptions) { "android_gui_toolbox_not_prepared" }

    override suspend fun currentContext(): ChatCompletionMessage {
        preparedContext?.let { prepared ->
            preparedContext = null
            return prepared
        }
        val envelope = buildTurnEnvelope()
        return envelope.request.messages.lastOrNull { it.role == "user" }
            ?: error("vlm_user_observation_required")
    }

    override suspend fun onTurn(turn: ChatCompletionTurn) {
        turnIndex += 1
        val metadata = policy.metadata(turn)
        val usage = VLMTokenUsageMapper.fromTurn(
            turn = turn,
            resolvedModel = requestOptions().modelOverride ?: requestOptions().model,
            attemptIndex = turnIndex,
            stabilityAttempt = 0,
            toolRetryIndex = 0,
        )?.let(VLMTokenUsageMapper::toRunLogMap)
        turnMetadata = linkedMapOf<String, Any?>().apply {
            metadata.thinking.trim().takeIf(String::isNotEmpty)?.let { put("thinking", it) }
            metadata.summary.trim().takeIf(String::isNotEmpty)?.let { put("summary", it) }
            usage?.takeIf(Map<String, Any?>::isNotEmpty)?.let { put("token_usage", it) }
        }
        runCatching { host.onModelTurn(turnMetadata) }
    }

    override fun runtimeDescriptor(toolName: String): AgentToolRegistry.RuntimeToolDescriptor =
        AgentToolRegistry.RuntimeToolDescriptor(
            name = toolName,
            displayName = toolName,
            toolType = if (toolName in dynamicFunctionMappings) "oob_function" else "vlm_action",
        )

    override fun modelTurnContractViolation(turn: ChatCompletionTurn): String? =
        policy.modelTurnContractViolation(turn)

    override fun adaptModelArguments(toolName: String, arguments: JsonObject): JsonObject =
        policy.adaptModelArguments(toolName, arguments)

    override fun validateArguments(toolName: String, arguments: JsonObject) {
        policy.validateArguments(currentTurnRequest, toolName, arguments)
    }

    override suspend fun execute(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle,
    ): ToolExecutionResult {
        val toolName = toolCall.function.name
        AndroidGuiModelAdapter.summary(toolName, args).takeIf(String::isNotBlank)?.let { summary ->
            turnMetadata = turnMetadata.toMutableMap().apply { put("summary", summary) }
        }
        val arguments = AgentToolJson.jsonObjectToMap(policy.executionArguments(toolName, args))
        return try {
            when {
                toolName in dynamicFunctionMappings -> executeFunction(toolName, arguments)
                toolName in OobActionSchema.replayableToolNames -> executePrimitive(toolName, arguments)
                toolName == OobActionSchema.TOOL_FINISHED -> finish(arguments)
                toolName == OobActionSchema.TOOL_INFO ||
                    toolName == OobActionSchema.TOOL_REQUIRE_USER_CHOICE ||
                    toolName == OobActionSchema.TOOL_REQUIRE_USER_CONFIRMATION -> requestInput(toolName, arguments)
                toolName == OobActionSchema.TOOL_FEEDBACK || toolName == OobActionSchema.TOOL_ABORT ->
                    abort(toolName, arguments)
                else -> ToolExecutionResult.Error(toolName, "Unsupported VLM tool: $toolName")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ToolExecutionResult.Error(
                toolName = toolName,
                message = error.message?.trim().orEmpty().ifBlank { error.javaClass.simpleName },
            )
        }
    }

    private suspend fun buildTurnEnvelope(): AndroidGuiTurnRequest {
        host.beforeStep()
        val reviewState = pendingReviewState
        val state = if (reuseCurrentState) {
            requireNotNull(currentState)
        } else {
            observe()
        }
        reuseCurrentState = false
        currentState = state
        val display = state["display"].asStringMap()
        val xml = state["xml"]?.toString()
        val screenshot = screenshotDataUri(state["screenshot_path"]?.toString())
        val externalEvents = host.consumeExternalEvents().mapNotNull(::contextEvent)
        val maxSteps = config.maxSteps?.coerceAtLeast(1)
        var context = UIContext(
            overallTask = config.goal,
            currentStepGoal = config.goal,
            stepSkillGuidance = config.stepSkillGuidance,
            installedApplications = installedApps(),
            targetPackageName = config.packageName.orEmpty(),
            currentPackageName = state["package_name"]?.toString().orEmpty(),
            displayWidth = display["width"].asInt() ?: 0,
            displayHeight = display["height"].asInt() ?: 0,
            maxSteps = maxSteps,
            stepsUsed = recordedSteps.size,
            stepsRemaining = maxSteps?.minus(recordedSteps.size)?.coerceAtLeast(0),
            transientEvents = externalEvents,
        )
        context = VLMIndexedPageContext.enrich(
            context = context,
            currentXml = xml,
            displayWidth = context.displayWidth,
            displayHeight = context.displayHeight,
        )
        context = VLMRecallContextProviderRegistry.enrich(
            VLMRecallContextRequest(
                context = context,
                currentXml = xml,
                currentPackageName = context.currentPackageName,
                screenshotBase64 = screenshot,
                stepIndex = recordedSteps.size,
                snapshot = VLMCurrentPageSnapshot(
                    packageName = context.currentPackageName,
                    xml = xml,
                    screenshotBase64 = screenshot,
                    displayWidth = context.displayWidth,
                    displayHeight = context.displayHeight,
                    capturedAtMs = System.currentTimeMillis(),
                ),
                disableFunctionRecall = config.disableFunctionRecall,
            )
        )
        val envelope = policy.buildRequest(
            context = context,
            screenshot = screenshot,
            previousScreenshot = screenshotDataUri(reviewState?.get("screenshot_path")?.toString()),
            model = config.model,
        )
        pendingReviewState = null
        toolsForModel = envelope.request.tools
        dynamicFunctionMappings = envelope.dynamicFunctionToolMappings
        currentTurnRequest = envelope
        return envelope
    }

    private suspend fun executePrimitive(
        toolName: String,
        arguments: Map<String, Any?>,
    ): ToolExecutionResult {
        if (recordedSteps.size >= (config.maxSteps ?: DEFAULT_MAX_STEPS)) {
            return stopForStepLimit()
        }
        val before = requireNotNull(currentState) { "vlm_before_state_required" }
        val action = linkedMapOf<String, Any?>("tool" to toolName, "args" to arguments)
        val actionResult = try {
            androidHost.invoke("act", mapOf("action" to action, "state" to before))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mapOf(
                "success" to false,
                "error" to error.message.orEmpty().ifBlank { error.javaClass.simpleName },
            )
        }
        var afterCaptureError: String? = null
        val after = try {
            observe()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            afterCaptureError = error.message.orEmpty().ifBlank { error.javaClass.simpleName }
            before
        }
        currentState = after
        reuseCurrentState = afterCaptureError == null
        val success = actionResult["success"] == true
        val error = if (success) null else firstNonBlank(
            actionResult["error"],
            actionResult["message"],
            actionResult["extra"].asStringMap()["message"],
        ).ifBlank { "action_failed:$toolName" }
        val outcome = actionOutcome(
            toolName = toolName,
            success = success,
            before = before,
            after = after,
            afterCaptureError = afterCaptureError,
        )
        updateFailureContext(outcome, before)
        val step = canonicalStep(
            beforeStateId = requiredStateId(before),
            action = action,
            success = success,
            error = error,
            afterStateId = requiredStateId(after),
            metadata = metadataForAction(toolName, arguments) + linkedMapOf<String, Any?>().apply {
                put("step_id", "${config.runId}-vlm-${recordedSteps.size}")
                put("status", if (success) "succeeded" else "failed")
                put("outcome", outcome.code)
                afterCaptureError?.let { put("after_state_capture_error", it) }
            },
        )
        host.recordStep(step)
        recordedSteps += step
        return contextResult(
            toolName = toolName,
            success = success,
            summary = outcomeSummary(outcome, error),
            payload = linkedMapOf<String, Any?>(
                "success" to success,
                "before_state_id" to requiredStateId(before),
                "after_state_id" to requiredStateId(after),
                "outcome" to outcome.code,
                "state_changed" to outcome.stateChanged,
                "failure_streak" to failureStreak.takeIf { it > 0 },
                "error" to error,
            ).filterValues { it != null },
        )
    }

    private suspend fun executeFunction(
        toolName: String,
        arguments: Map<String, Any?>,
    ): ToolExecutionResult {
        if (recordedSteps.size >= (config.maxSteps ?: DEFAULT_MAX_STEPS)) {
            return stopForStepLimit()
        }
        val before = requireNotNull(currentState) { "vlm_before_state_required" }
        val functionId = requireNotNull(dynamicFunctionMappings[toolName])
        attachTurnMetadataToNextFunctionStep = true
        val result = try {
            runFunction(
                payload = linkedMapOf(
                    "function_id" to functionId,
                    "arguments" to arguments,
                    "run_id" to config.runId,
                    "execution_mode" to "foreground",
                    "started_at_ms" to System.currentTimeMillis(),
                ),
            )
        } finally {
            attachTurnMetadataToNextFunctionStep = false
        }
        var afterCaptureError: String? = null
        val after = try {
            observe()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            afterCaptureError = error.message.orEmpty().ifBlank { error.javaClass.simpleName }
            null
        }
        if (after != null) {
            currentState = after
            reuseCurrentState = true
        } else {
            reuseCurrentState = false
        }
        val success = result["success"] == true
        val error = firstNonBlank(result["error_message"], result["error"])
        val resolvedAfter = after ?: before
        val outcome = actionOutcome(
            toolName = toolName,
            success = success,
            before = before,
            after = resolvedAfter,
            afterCaptureError = afterCaptureError,
        )
        updateFailureContext(outcome, before)
        return contextResult(
            toolName = toolName,
            success = success,
            summary = outcomeSummary(outcome, error),
            payload = linkedMapOf<String, Any?>(
                "success" to success,
                "function_id" to functionId,
                "before_state_id" to requiredStateId(before),
                "after_state_id" to requiredStateId(resolvedAfter),
                "outcome" to outcome.code,
                "state_changed" to outcome.stateChanged,
                "failure_streak" to failureStreak.takeIf { it > 0 },
                "error" to error.takeIf(String::isNotBlank),
            ).filterValues { it != null },
        )
    }

    private suspend fun recordFunctionStep(rawStep: Map<String, Any?>) {
        require(rawStep.keys.all { it in CANONICAL_STEP_FIELDS }) {
            "function_step_non_canonical_fields"
        }
        val action = rawStep["action"].asStringMap()
        val result = rawStep["result"].asStringMap()
        val metadata = rawStep["metadata"].asStringMap().toMutableMap()
        if (attachTurnMetadataToNextFunctionStep) {
            metadata.putAll(turnMetadata)
            attachTurnMetadataToNextFunctionStep = false
        }
        if (metadata["summary"]?.toString()?.isNotBlank() != true) {
            metadata["summary"] = actionSummary(action)
        }
        metadata.putIfAbsent("step_id", "${config.runId}-vlm-${recordedSteps.size}")
        metadata.putIfAbsent("status", if (result["success"] == true) "succeeded" else "failed")
        val step = canonicalStep(
            beforeStateId = rawStep["before_state_id"]?.toString().orEmpty(),
            action = action,
            success = result["success"] == true,
            error = result["error"]?.toString(),
            afterStateId = rawStep["after_state_id"]?.toString().orEmpty(),
            metadata = metadata,
        )
        host.recordStep(step)
        recordedSteps += step
    }

    private suspend fun finish(arguments: Map<String, Any?>): ToolExecutionResult {
        val content = firstNonBlank(arguments["content"], turnMetadata["summary"]).ifBlank { "任务完成" }
        terminal = Terminal(success = true, reason = "finished", content = content)
        return ToolExecutionResult.ChatMessage(content)
    }

    private suspend fun abort(
        toolName: String,
        arguments: Map<String, Any?>,
    ): ToolExecutionResult {
        val content = firstNonBlank(arguments["value"], turnMetadata["summary"]).ifBlank {
            if (toolName == OobActionSchema.TOOL_FEEDBACK) "需要重新规划" else "任务终止"
        }
        terminal = Terminal(success = false, reason = toolName, content = content)
        return ToolExecutionResult.ChatMessage(content)
    }

    private suspend fun requestInput(
        toolName: String,
        arguments: Map<String, Any?>,
    ): ToolExecutionResult {
        val prompt = when (toolName) {
            OobActionSchema.TOOL_REQUIRE_USER_CHOICE -> {
                val options = (arguments["options"] as? List<*>)
                    .orEmpty()
                    .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
                buildString {
                    append(firstNonBlank(arguments["prompt"]))
                    if (options.isNotEmpty()) append("\n可选项：${options.joinToString(" / ")}")
                }
            }
            OobActionSchema.TOOL_REQUIRE_USER_CONFIRMATION -> firstNonBlank(arguments["prompt"])
            else -> firstNonBlank(arguments["value"])
        }
        require(prompt.isNotBlank()) { "request_input_question_required" }
        val answer = host.requestUserInput(prompt)
        reuseCurrentState = false
        return contextResult(
            toolName = toolName,
            success = true,
            summary = "User input received",
            payload = mapOf("success" to true, "value" to answer),
        )
    }

    private suspend fun stopForStepLimit(): ToolExecutionResult {
        val content = "max_steps_exceeded:${config.maxSteps ?: DEFAULT_MAX_STEPS}"
        terminal = Terminal(success = false, reason = "max_steps_exceeded", content = content)
        return ToolExecutionResult.ChatMessage(content)
    }

    private suspend fun runFunction(payload: Map<String, Any?>): Map<String, Any?> {
        functionRunnerOverride?.let { return it(payload, androidHost) }
        return OmniFlowPythonRuntime.call(
            context = requireNotNull(appContext) { "android_gui_context_required" },
            operation = "run",
            payload = payload,
            hostCall = androidHost,
        )
    }

    private suspend fun observe(): Map<String, Any?> =
        androidHost.invoke("observe", emptyMap()).also(::requiredStateId)

    private fun canonicalStep(
        beforeStateId: String,
        action: Map<String, Any?>,
        success: Boolean,
        error: String?,
        afterStateId: String,
        metadata: Map<String, Any?>,
    ): Map<String, Any?> {
        require(beforeStateId.isNotBlank()) { "before_state_id_required" }
        require(afterStateId.isNotBlank()) { "after_state_id_required" }
        val tool = action["tool"]?.toString()?.trim().orEmpty()
        require(tool in OobActionSchema.replayableToolNames) { "canonical_action_tool_invalid:$tool" }
        val args = action["args"].asStringMap()
        return linkedMapOf(
            "step_index" to recordedSteps.size,
            "before_state_id" to beforeStateId,
            "action" to linkedMapOf("tool" to tool, "args" to args),
            "result" to linkedMapOf<String, Any?>("success" to success).apply {
                error?.trim()?.takeIf(String::isNotEmpty)?.let { put("error", it) }
            },
            "after_state_id" to afterStateId,
            "metadata" to metadata.filterValues { it != null },
        )
    }

    private fun contextResult(
        toolName: String,
        success: Boolean,
        summary: String,
        payload: Map<String, Any?>,
    ): ToolExecutionResult.ContextResult {
        val json = AgentToolJson.mapToJsonElement(payload).toString()
        return ToolExecutionResult.ContextResult(
            toolName = toolName,
            summaryText = summary,
            previewJson = json,
            rawResultJson = json,
            success = success,
        )
    }

    private fun screenshotDataUri(path: String?): String? {
        val file = path?.trim()?.takeIf(String::isNotEmpty)?.let(::File) ?: return null
        val bytes = runCatching { file.takeIf(File::isFile)?.readBytes() }.getOrNull()
            ?.takeIf(ByteArray::isNotEmpty)
            ?: return null
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes)
    }

    private fun contextEvent(value: Map<String, Any?>): VLMContextEvent? {
        val text = value["text"]?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return null
        return VLMContextEvent(
            type = value["type"]?.toString()?.trim().orEmpty().ifBlank { "external_event" },
            text = text,
            source = value["source"]?.toString()?.trim().orEmpty().ifBlank { "external" },
            suggestCompletion = value["suggest_completion"] == true,
        )
    }

    private fun requiredStateId(state: Map<String, Any?>): String =
        state["state_id"]?.toString()?.trim()?.takeIf(String::isNotEmpty)
            ?: error("state_id_required")

    private fun Any?.asStringMap(): Map<String, Any?> {
        val source = this as? Map<*, *> ?: return emptyMap()
        return source.entries.associateTo(linkedMapOf()) { (key, value) ->
            key?.toString().orEmpty() to value
        }
    }

    private fun Any?.asInt(): Int? = when (this) {
        is Number -> toInt()
        else -> toString().trim().toIntOrNull()
    }

    private fun firstNonBlank(vararg values: Any?): String =
        values.firstNotNullOfOrNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()

    private fun metadataForAction(
        toolName: String,
        arguments: Map<String, Any?>,
    ): Map<String, Any?> = turnMetadata.toMutableMap().apply {
        putIfAbsent("summary", actionSummary(mapOf("tool" to toolName, "args" to arguments)))
    }

    private fun actionOutcome(
        toolName: String,
        success: Boolean,
        before: Map<String, Any?>,
        after: Map<String, Any?>,
        afterCaptureError: String?,
    ): ActionOutcome {
        val stateChanged = requiredStateId(before) != requiredStateId(after)
        val packageChanged = firstNonBlank(before["package_name"]) != firstNonBlank(after["package_name"])
        val activityChanged = firstNonBlank(before["activity_name"]) != firstNonBlank(after["activity_name"])
        val code = when {
            afterCaptureError != null -> OUTCOME_OBSERVE_FAILED
            !success -> OUTCOME_ACTION_FAILED
            packageChanged -> OUTCOME_PACKAGE_CHANGED
            activityChanged -> OUTCOME_ACTIVITY_CHANGED
            stateChanged -> OUTCOME_STATE_CHANGED
            else -> OUTCOME_STATE_UNCHANGED
        }
        val needsReview = afterCaptureError != null ||
            !success ||
            (!stateChanged && toolName in CHANGE_EXPECTED_TOOLS)
        return ActionOutcome(
            code = code,
            stateChanged = stateChanged,
            needsReview = needsReview,
        )
    }

    private fun updateFailureContext(
        outcome: ActionOutcome,
        before: Map<String, Any?>,
    ) {
        if (outcome.needsReview) {
            failureStreak += 1
            pendingReviewState = before
        } else {
            failureStreak = 0
            pendingReviewState = null
        }
    }

    private fun outcomeSummary(outcome: ActionOutcome, error: String?): String = when (outcome.code) {
        OUTCOME_ACTION_FAILED -> "Action failed: ${error.orEmpty().ifBlank { "unknown error" }}"
        OUTCOME_OBSERVE_FAILED -> "Action completed, but the current page could not be observed; inspect again"
        OUTCOME_PACKAGE_CHANGED -> "The foreground app changed; inspect the current page"
        OUTCOME_ACTIVITY_CHANGED -> "The page activity changed; inspect the current page"
        OUTCOME_STATE_CHANGED -> "The page state changed"
        OUTCOME_STATE_UNCHANGED -> if (failureStreak >= 2) {
            "No structural change after $failureStreak attempts; re-plan from the user goal"
        } else {
            "No structural change; compare the previous and current screenshots"
        }
        else -> outcome.code
    }

    private fun actionSummary(action: Map<String, Any?>): String {
        val tool = action["tool"]?.toString()?.trim().orEmpty()
        val args = action["args"].asStringMap()
        val target = firstNonBlank(
            args["target_description"],
            args["app_name"],
            args["package_name"],
        )
        return when (tool) {
            "click" -> target.takeIf(String::isNotBlank)?.let { "点击「$it」" }
                ?: "点击 (${firstNonBlank(args["x"])}, ${firstNonBlank(args["y"])})"
            "long_press" -> target.takeIf(String::isNotBlank)?.let { "长按「$it」" }
                ?: "长按目标位置"
            "input_text" -> "输入「${firstNonBlank(args["text"])}」"
            "press_key" -> "按下 ${firstNonBlank(args["key"])}"
            "open_app" -> "打开「${target.ifBlank { "应用" }}」"
            "swipe" -> firstNonBlank(args["direction"]).takeIf(String::isNotBlank)
                ?.let { "向${it}滑动" }
                ?: "滑动页面"
            "wait" -> "等待 ${firstNonBlank(args["duration_ms"])}ms"
            else -> tool
        }
    }

    private companion object {
        const val DEFAULT_MAX_STEPS = 20
        const val OUTCOME_ACTION_FAILED = "action_failed"
        const val OUTCOME_OBSERVE_FAILED = "observe_failed"
        const val OUTCOME_PACKAGE_CHANGED = "package_changed"
        const val OUTCOME_ACTIVITY_CHANGED = "activity_changed"
        const val OUTCOME_STATE_CHANGED = "state_changed"
        const val OUTCOME_STATE_UNCHANGED = "state_unchanged"
        val CHANGE_EXPECTED_TOOLS = setOf(
            OobActionSchema.TOOL_CLICK,
            OobActionSchema.TOOL_LONG_PRESS,
            OobActionSchema.TOOL_INPUT_TEXT,
            OobActionSchema.TOOL_SWIPE,
            OobActionSchema.TOOL_OPEN_APP,
            OobActionSchema.TOOL_PRESS_KEY,
        )
        val CANONICAL_STEP_FIELDS = setOf(
            "step_index",
            "before_state_id",
            "action",
            "result",
            "after_state_id",
            "metadata",
        )
    }
}
