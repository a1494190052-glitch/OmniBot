package cn.com.omnimind.bot.runlog

import android.content.Context
import cn.com.omnimind.assists.task.vlmserver.AndroidDeviceOperator
import cn.com.omnimind.assists.task.vlmserver.DeviceOperator
import cn.com.omnimind.baselib.runlog.InternalRunLogRecord
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.runlog.OobReusableFunctionStore
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.omniflow.OobFunctionRecallService
import cn.com.omnimind.bot.omniflow.OobFunctionRepository
import cn.com.omnimind.bot.omniflow.OobFunctionSchemaBuilder
import cn.com.omnimind.bot.omniflow.OobFunctionRuntimeResolvePlanner
import cn.com.omnimind.bot.omniflow.OobFunctionStepwiseUpdateOrchestrator
import cn.com.omnimind.bot.omniflow.OobFunctionToolNames
import cn.com.omnimind.bot.omniflow.OobFunctionUpdateAgentOrchestrator
import cn.com.omnimind.bot.omniflow.OobFunctionUpdateService
import cn.com.omnimind.bot.omniflow.OobFunctionRunner
import cn.com.omnimind.bot.omniflow.WorkspaceFunctionStore
import cn.com.omnimind.bot.runlog.boolArg
import cn.com.omnimind.bot.runlog.boolArgOrDefault
import cn.com.omnimind.bot.runlog.firstNonBlank
import cn.com.omnimind.bot.runlog.intArg
import cn.com.omnimind.bot.runlog.listArg
import cn.com.omnimind.bot.runlog.mapArg

/**
 * OOB-native implementation of the public OmniFlow agent toolkit surface.
 *
 * The service deliberately keeps the first version fixed and local: Functions
 * are registered in OOB stores, recall is deterministic, and execution runs
 * through the existing OOB replay dispatcher. External OmniFlow can replace this
 * class later behind the same Function lifecycle shape.
 */
class OobOmniFlowToolkitService(
    private val context: Context,
    private val deviceOperator: DeviceOperator = AndroidDeviceOperator(null, context),
    private val workspaceFunctionStore: WorkspaceFunctionStore = WorkspaceFunctionStore(
        AgentWorkspaceManager.rootDirectory(context)
    ),
    private val runtimeResolvePlanner: OobFunctionRuntimeResolvePlanner? = null,
    private val updateAgentRequester: suspend (prompt: String, responseJsonObject: Boolean) -> String? =
        { prompt, responseJsonObject ->
            OobFunctionUpdateAgentOrchestrator.requestAgentAnalysis(prompt, responseJsonObject)
        },
) {
    private val functionRepository = OobFunctionRepository(context, workspaceFunctionStore)
    private val replayService = OobRunLogReplayService(context, workspaceFunctionStore, functionRepository)
    private val functionRecallService = OobFunctionRecallService(context, functionRepository, deviceOperator)
    private val functionRunner = runtimeResolvePlanner?.let { planner ->
        OobFunctionRunner(
            context = context,
            workspaceFunctionStore = workspaceFunctionStore,
            functionRepository = functionRepository,
            deviceOperator = deviceOperator,
            runtimeResolvePlanner = planner,
        )
    } ?: OobFunctionRunner(
        context = context,
        workspaceFunctionStore = workspaceFunctionStore,
        functionRepository = functionRepository,
        deviceOperator = deviceOperator,
    )
    private val functionUpdateService = OobFunctionUpdateService(context, functionRepository)
    private val functionUpdateOrchestrator =
        OobFunctionUpdateAgentOrchestrator(functionUpdateService, updateAgentRequester)
    private val functionStepwiseUpdateOrchestrator =
        OobFunctionStepwiseUpdateOrchestrator(functionUpdateService, updateAgentRequester)
    private val explorer = OobOmniFlowExplorer(context, deviceOperator)

    suspend fun executeTool(name: String?, args: Map<String, Any?>?): Map<String, Any?> {
        return when (name) {
            "omniflow.recall" -> recall(args)
            "omniflow.ingest_run_log" -> ingestRunLog(args)
            "omniflow.explore_replay" -> exploreAndReplay(args)
            "run_function", "oob_function_run" -> runFunction(args)
            OobFunctionToolNames.FUNCTION_LIST -> listFunctions(args)
            OobFunctionToolNames.FUNCTION_GET -> getFunction(args)
            OobFunctionToolNames.FUNCTION_REGISTER -> registerFunction(args)
            OobFunctionToolNames.FUNCTION_UPDATE -> updateFunction(args)
            OobFunctionToolNames.FUNCTION_DELETE -> deleteFunction(args)
            OobFunctionToolNames.FUNCTION_CLEAR -> clearFunctions(args)
            OobFunctionToolNames.RUN_LOG_LIST -> listRunLogs(args)
            OobFunctionToolNames.RUN_LOG_GET -> getRunLog(args)
            OobFunctionToolNames.RUN_LOG_CONVERT -> convertRunLog(args)
            null, "" -> errorPayload(code = "TOOL_NAME_EMPTY", message = "Missing OmniFlow tool name")
            else -> errorPayload(code = "UNKNOWN_OMNIFLOW_TOOL", message = "Unknown OmniFlow tool: $name")
        }
    }

    fun recall(args: Map<String, Any?>?): Map<String, Any?> =
        functionRecallService.recall(args)

    fun ingestRunLog(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val register = boolArgOrDefault(request["register"], defaultValue = false)
        val agentVisible = boolArgOrDefault(request["agent_visible"], defaultValue = false)
        val runId = firstNonBlank(request["run_id"])
        val rawRunLog = mapArg(request["run_log"])
        val result = if (runId.isNotEmpty()) {
            replayService.convertRunLog(runId = runId, register = register, agentVisible = agentVisible)
        } else if (rawRunLog.isNotEmpty()) {
            ingestInlineRunLog(rawRunLog, register = register, agentVisible = agentVisible)
        } else {
            linkedMapOf(
                "success" to false,
                "error_code" to "RUN_LOG_EMPTY",
                "error_message" to "ingest_run_log requires run_id or run_log"
            )
        }
        val success = result["success"] == true
        return linkedMapOf<String, Any?>(
            "accepted" to success,
            "success" to success,
            "function_id" to result["function_id"],
            "created_function_id" to result["created_function_id"],
            "status" to when {
                !success -> "rejected"
                result["registered"] != true -> "converted"
                result["already_exists"] == true -> "updated"
                else -> "created"
            },
            "reason" to (result["error_message"] ?: ""),
            "result" to result,
            "source" to "oob_native_omniflow_toolkit"
        )
    }

    suspend fun explore(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val register = boolArg(request["register"])
        val functionId = firstNonBlank(request["function_id"], request["functionId"])
        val name = firstNonBlank(request["name"])
        val description = firstNonBlank(request["description"])
        val exploreResult = explorer.explore(request)
        val success = exploreResult["success"] == true
        if (!success) {
            return linkedMapOf(
                "success" to false,
                "phase" to "explore",
                "explore" to exploreResult,
                "error_code" to exploreResult["error_code"],
                "error_message" to exploreResult["error_message"],
                "source" to "oob_native_omniflow_toolkit"
            )
        }
        if (!register) {
            return linkedMapOf(
                "success" to true,
                "phase" to "explore",
                "run_id" to exploreResult["run_id"],
                "utg" to exploreResult["utg"],
                "explore" to exploreResult,
                "registered" to false,
                "source" to "oob_native_omniflow_toolkit"
            )
        }

        val convertResult = replayService.convertRunLog(
            runId = exploreResult["run_id"]?.toString().orEmpty(),
            register = true,
            functionIdOverride = functionId.takeIf { it.isNotEmpty() },
            nameOverride = name.takeIf { it.isNotEmpty() },
            descriptionOverride = description.takeIf { it.isNotEmpty() },
        )
        val converted = convertResult["success"] == true
        return linkedMapOf(
            "success" to converted,
            "phase" to if (converted) "registered" else "convert",
            "run_id" to exploreResult["run_id"],
            "function_id" to convertResult["function_id"],
            "created_function_id" to convertResult["created_function_id"],
            "registered" to converted,
            "utg" to exploreResult["utg"],
            "explore" to exploreResult,
            "convert" to convertResult,
            "error_code" to convertResult["error_code"],
            "error_message" to convertResult["error_message"],
            "source" to "oob_native_omniflow_toolkit"
        )
    }

    suspend fun exploreAndReplay(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val exploreArgs = linkedMapOf<String, Any?>().apply {
            putAll(request)
            put("register", true)
        }
        val exploreResult = explore(exploreArgs)
        if (exploreResult["success"] != true) {
            return linkedMapOf(
                "success" to false,
                "phase" to (exploreResult["phase"] ?: "explore"),
                "explore" to exploreResult,
                "error_code" to exploreResult["error_code"],
                "error_message" to exploreResult["error_message"],
                "source" to "oob_native_omniflow_toolkit"
            )
        }

        val functionId = firstNonBlank(exploreResult["function_id"], exploreResult["created_function_id"])
        val packageName = firstNonBlank(
            request["package_name"],
            request["packageName"],
            request["target_package"],
            request["targetPackage"],
        )
        val shouldReplay = request["replay"] != false &&
            !request["replay"]?.toString().equals("false", ignoreCase = true)
        if (!shouldReplay) {
            return linkedMapOf(
                "success" to true,
                "phase" to "registered",
                "run_id" to exploreResult["run_id"],
                "function_id" to functionId,
                "utg" to exploreResult["utg"],
                "replay_skipped" to true,
                "explore" to exploreResult,
                "source" to "oob_native_omniflow_toolkit"
            )
        }

        val settleDelayMs = longArg(
            request["settle_delay_ms"],
            request["settleDelayMs"],
            defaultValue = 800L
        ).coerceIn(100L, 5_000L)
        val resetBackSteps = intArg(
            request["reset_back_steps"],
            request["resetBackSteps"],
            defaultValue = 1
        ).coerceIn(0, 8)
        if (boolArg(request["reset_before_replay"]) || boolArg(request["resetBeforeReplay"])) {
            explorer.resetBeforeReplay(
                targetPackageName = packageName,
                backSteps = resetBackSteps,
                settleDelayMs = settleDelayMs,
            )
        }

        val replayResult = runFunction(
            linkedMapOf(
                "function_id" to functionId,
                "arguments" to mapArg(request["arguments"]),
                "goal" to firstNonBlank(request["goal"], request["query"], request["task"])
            )
        )
        val replaySuccess = replayResult["success"] == true
        return linkedMapOf(
            "success" to replaySuccess,
            "phase" to if (replaySuccess) "replayed" else "replay",
            "run_id" to exploreResult["run_id"],
            "function_id" to functionId,
            "explore" to exploreResult,
            "replay" to replayResult,
            "utg" to exploreResult["utg"],
            "error" to firstNonBlank(replayResult["error"], replayResult["error_message"]).takeIf { it.isNotEmpty() },
            "source" to "oob_native_omniflow_toolkit"
        )
    }

    fun listFunctions(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        if (boolArg(request["auto_register"]) || boolArg(request["autoRegister"])) {
            runCatching { replayService.autoRegisterRecentRunLogs(limit = 50) }
                .onFailure { OmniLog.w(TAG, "list OOB functions auto-register failed: ${it.message}") }
        }
        return functionRepository.list(
            limit = intArg(request["limit"], defaultValue = 100),
            offset = intArg(request["offset"], defaultValue = 0),
            includeHidden = boolArg(request["include_hidden"]) || boolArg(request["includeHidden"]),
        )
    }

    fun getFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val functionId = firstNonBlank(args?.get("function_id"))
        val spec = functionRepository.get(functionId)
        if (spec == null) {
            return errorPayload(
                code = "OOB_FUNCTION_NOT_FOUND",
                message = "OmniFlow function not found: $functionId",
                functionId = functionId
            )
        }
        return linkedMapOf<String, Any?>().apply {
            putAll(spec)
            put("success", true)
            put("function", spec)
            put("function_id", firstNonBlank(OobFunctionSchemaBuilder.functionId(spec), functionId))
            put("summary", functionAgentSummary(spec))
            put("response_source", "oob_native_function_store")
        }
    }

    fun deleteFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val functionId = firstNonBlank(args?.get("function_id"))
        return functionRepository.delete(functionId)
    }

    fun clearFunctions(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val confirmed = boolArg(request["confirm"]) ||
            boolArg(request["confirmed"]) ||
            firstNonBlank(request["action"]).equals("clear_all", ignoreCase = true)
        if (!confirmed) {
            return errorPayload(
                code = "OOB_FUNCTION_CLEAR_CONFIRMATION_REQUIRED",
                message = "Set confirm=true to clear all registered OmniFlow Functions"
            )
        }
        return functionRepository.clear()
    }

    fun registerFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val functionSpec = buildFunctionSpecForRegistration(request)
        if (functionSpec.isEmpty()) {
            return errorPayload(
                code = "FUNCTION_SPEC_EMPTY",
                message = "functionSpec or steps are required"
            )
        }
        val mode = if (hasExplicitFunctionSpec(request)) "function_spec" else "simple"
        return functionRepository.register(functionSpec) + linkedMapOf(
            "registration_input_mode" to mode,
            "simple_schema_supported" to true,
        )
    }

    private fun hasExplicitFunctionSpec(request: Map<String, Any?>): Boolean =
        mapArg(request["function_spec"]).isNotEmpty() ||
            mapArg(request["functionSpec"]).isNotEmpty() ||
            ((request.containsKey("function_id") || request.containsKey("name")) &&
                (mapArg(request["execution"]).isNotEmpty() || listArg(request["actions"]).isNotEmpty()))

    private fun buildFunctionSpecForRegistration(request: Map<String, Any?>): Map<String, Any?> {
        val explicit = mapArg(request["function_spec"]).ifEmpty { mapArg(request["functionSpec"]) }
            .ifEmpty { if (hasExplicitFunctionSpec(request)) request else emptyMap() }
        if (explicit.isNotEmpty()) return explicit
        val rawSteps = listArg(request["steps"])
            .ifEmpty { listArg(request["execution_steps"]) }
            .ifEmpty { listArg(request["executionSteps"]) }
            .mapNotNull { raw -> mapArg(raw).takeIf { it.isNotEmpty() } }
        if (rawSteps.isEmpty()) return emptyMap()
        val now = System.currentTimeMillis().toString()
        val rawFunctionId = firstNonBlank(request["function_id"], request["functionId"], request["id"])
        val name = firstNonBlank(request["name"], request["title"], rawFunctionId).ifBlank { "OmniFlow reusable function" }
        val description = firstNonBlank(request["description"], request["goal"], request["summary"], name)
        val functionId = rawFunctionId.ifBlank {
            val seed = "$name $description".lowercase().replace(Regex("[^a-z0-9]+"), "_")
                .replace(Regex("_+"), "_").trim('_').take(48).ifBlank { "registered_function" }
            "oob_fn_${seed}_${now.takeLast(6)}"
        }
        val sourceContext = sourceContextFromRegistration(request)
        val sourcePackageName = firstNonBlank(
            mapArg(sourceContext["src_ctx"])["package_name"],
            mapArg(sourceContext["src_ctx"])["packageName"],
        )
        val packageName = firstNonBlank(
            request["packageName"], request["package_name"], request["current_package"], request["currentPackage"],
            mapArg(request["source_page"])["package_name"], mapArg(request["source_page"])["packageName"],
            mapArg(request["sourcePage"])["package_name"], mapArg(request["sourcePage"])["packageName"],
            sourcePackageName,
        )
        val normalizedSteps = rawSteps.mapIndexed { index, raw ->
            cn.com.omnimind.bot.omniflow.OobFunctionStepNormalizer.normalizeSimpleRegisteredStep(
                raw = raw,
                index = index,
                inheritedSourceContext = if (index == 0) sourceContext else emptyMap(),
            )
        }
        val capabilities = cn.com.omnimind.bot.omniflow.OobFunctionStepNormalizer.executionCapabilities(normalizedSteps)
        val explicitAgentVisible = request["agent_visible"] ?: request["agentVisible"]
        val explicitVisibility = firstNonBlank(request["visibility"])
        return linkedMapOf<String, Any?>(
            "schema_version" to "oob.reusable_function.v1",
            "function_id" to functionId,
            "name" to name,
            "description" to description,
            "agent_visible" to explicitAgentVisible,
            "visibility" to explicitVisibility.takeIf { it.isNotBlank() },
            "parameters" to listArg(request["parameters"]).mapNotNull { raw -> mapArg(raw).takeIf { it.isNotEmpty() } },
            "constraints" to linkedMapOf("package_name" to packageName.takeIf { it.isNotBlank() }).filterValues { it != null },
            "source" to linkedMapOf(
                "kind" to "agent_registered_function",
                "goal" to firstNonBlank(request["goal"], description),
                "package_name" to packageName.takeIf { it.isNotBlank() },
                "registered_via" to "oob_function_register.simple",
                "source_context_mode" to firstNonBlank(mapArg(sourceContext["_oob_meta"])["mode"], "none")
                    .takeIf { sourceContext.isNotEmpty() },
                "registered_at" to now,
            ).filterValues { it != null },
            "execution" to linkedMapOf(
                "kind" to "tool_sequence",
                "runner" to "oob_tool_sequence",
                "entrypoint" to "execute",
                "capabilities" to capabilities,
                "steps" to normalizedSteps,
                "step_count" to normalizedSteps.size,
                "omniflow_step_count" to capabilities["omniflow_step_count"],
                "agent_step_count" to capabilities["agent_step_count"],
                "has_agent_steps" to capabilities["has_agent_steps"],
            ),
            "_oob_registry" to linkedMapOf(
                "registered_at" to now,
                "updated_at" to now,
                "runner" to "oob_agent_reusable_function",
                "storage" to "workspace",
                "registration_input_mode" to "simple",
            ),
        ).filterValues { it != null }
    }

    private fun sourceContextFromRegistration(request: Map<String, Any?>): Map<String, Any?> {
        val explicit = mapArg(request["source_context"]).ifEmpty { mapArg(request["sourceContext"]) }
        if (explicit.isNotEmpty()) return explicit
        val sourcePage = mapArg(request["source_page"]).ifEmpty { mapArg(request["sourcePage"]) }
            .ifEmpty { mapArg(request["currentPage"]) }.ifEmpty { mapArg(request["current_page"]) }
        val pageXmlFromRequest = firstNonBlank(
            sourcePage["page"], sourcePage["xml"], sourcePage["observation_xml"], sourcePage["observationXml"],
            request["current_xml"], request["currentXml"], request["source_xml"], request["sourceXml"], request["xml"],
        )
        val requestPackageName = firstNonBlank(
            sourcePage["package_name"], sourcePage["packageName"], request["package_name"],
            request["packageName"], request["current_package"], request["currentPackage"],
        )
        val requestActivityName = firstNonBlank(
            sourcePage["activity_name"], sourcePage["activityName"],
            request["activity_name"], request["activityName"],
        )
        val autoCaptureDisabled = boolArg(request["disable_current_page_capture"]) ||
            boolArg(request["disableCurrentPageCapture"]) ||
            boolArg(request["no_current_page_capture"]) || boolArg(request["noCurrentPageCapture"])
        val capturedPage = if (pageXmlFromRequest.isBlank() && !autoCaptureDisabled) {
            runCatching {
                val pageXml = deviceOperator.currentXml()?.trim().orEmpty()
                if (pageXml.isBlank()) return@runCatching emptyMap()
                val pkg = deviceOperator.currentPackageName()?.trim().orEmpty()
                val act = deviceOperator.currentActivityName()?.trim().orEmpty()
                linkedMapOf("src_ctx" to linkedMapOf<String, Any?>(
                    "page" to pageXml, "package_name" to pkg.takeIf { it.isNotBlank() },
                    "activity_name" to act.takeIf { it.isNotBlank() },
                    "require_unique_action_signature" to false,
                ).filterValues { it != null })
            }.getOrDefault(emptyMap())
        } else emptyMap()
        val capturedSrcCtx = mapArg(capturedPage["src_ctx"])
        val pageXml = firstNonBlank(pageXmlFromRequest, capturedSrcCtx["page"])
        if (pageXml.isBlank()) return emptyMap()
        val packageName = firstNonBlank(requestPackageName, capturedSrcCtx["package_name"], capturedSrcCtx["packageName"])
        val activityName = firstNonBlank(requestActivityName, capturedSrcCtx["activity_name"], capturedSrcCtx["activityName"])
        val mode = if (pageXmlFromRequest.isBlank()) "current_page_capture" else "explicit_request"
        return linkedMapOf(
            "src_ctx" to linkedMapOf<String, Any?>(
                "page" to pageXml,
                "package_name" to packageName.takeIf { it.isNotBlank() },
                "activity_name" to activityName.takeIf { it.isNotBlank() },
                "require_unique_action_signature" to false,
            ).filterValues { it != null },
            "_oob_meta" to linkedMapOf("mode" to mode, "captured_current_page" to (mode == "current_page_capture")),
        )
    }

    suspend fun updateFunction(args: Map<String, Any?>?): Map<String, Any?> =
        if (shouldUseStepwiseEnhancement(args)) {
            functionStepwiseUpdateOrchestrator.updateFunction(args)
        } else {
            functionUpdateOrchestrator.updateFunction(args)
        }

    private fun shouldUseStepwiseEnhancement(args: Map<String, Any?>?): Boolean {
        val request = args ?: return false
        val mode = firstNonBlank(request["mode"], request["operation"]).lowercase().ifBlank { "enhance" }
        return mode == "enhance" &&
            (boolArg(request["background_enhancement"]) || boolArg(request["backgroundEnhancement"]))
    }

    suspend fun runFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val callTiming = OobFunctionCallTiming()
        val request = args ?: emptyMap()
        val functionId = firstNonBlank(request["function_id"], request["functionId"])
        val arguments = functionArguments(request)
        val resumeFromStep = intArg(
            request["resume_from_step"],
            defaultValue = 0
        ).coerceAtLeast(0)
        val frontendRunId = firstNonBlank(request["frontend_run_id"], request["frontendRunId"])
        val frontendTaskId = firstNonBlank(request["frontend_task_id"], request["frontendTaskId"])
        val frontendParent = firstNonBlank(request["frontend_parent"], request["frontendParent"])
        val runtimeResolveGoal = firstNonBlank(
            request["runtime_resolve_goal"],
            request["runtimeResolveGoal"],
            request["goal"],
            request["query"],
            request["task"],
        )
        val runtimeResolveAllowRaw = firstPresent(
            request["allow_runtime_resolve"],
            request["allowRuntimeResolve"],
        )
        val runtimeResolveEnabled = runtimeResolveGoal.isNotBlank() &&
            boolArgOrDefault(runtimeResolveAllowRaw, defaultValue = true)
        val runtimeResolveBudget = intArg(
            request["runtime_resolve_budget"],
            request["runtimeResolveBudget"],
            defaultValue = if (runtimeResolveEnabled) 1 else 0,
        ).coerceAtLeast(0)
        val runtimeResolveModel = firstNonBlank(
            request["runtime_resolve_model"],
            request["runtimeResolveModel"],
            request["model"],
        )
        val executionMode = firstNonBlank(request["execution_mode"])
            .ifBlank { "foreground" }
        var runPayload = callTiming.measureSuspend("execute_function_ms") {
            functionRunner.execute(
                functionId = functionId,
                arguments = arguments,
                allowAgentFallback = false,
                resumeFromStep = resumeFromStep,
                frontendRunId = frontendRunId,
                frontendTaskId = frontendTaskId,
                frontendParent = frontendParent,
                runtimeResolveGoal = runtimeResolveGoal,
                runtimeResolveBudget = runtimeResolveBudget,
                runtimeResolveModel = runtimeResolveModel,
            )
        }
        runPayload = normalizeIncompleteReplay(callTiming.attachTo(runPayload))
        OobReusableFunctionStore.recordRun(
            context = context,
            functionId = functionId,
            success = runPayload["success"] == true,
            runId = runPayload["run_id"]?.toString(),
            runner = runPayload["runner"]?.toString(),
            stepCount = intArg(runPayload["step_count"], defaultValue = 0),
            errorMessage = runPayload["error_message"]?.toString()
        )
        OobFunctionRunLogRecorder.record(
            context = context,
            functionId = functionId,
            runPayload = runPayload,
        )
        val stepResults = listArg(runPayload["step_results"])
        val timing = mapArg(runPayload["timing"])
        val startedAtMs = longArg(timing["started_at_ms"], defaultValue = 0L)
        val finishedAtMs = longArg(timing["finished_at_ms"], defaultValue = 0L)
        val durationMs = longArg(
            timing["call_duration_ms"],
            timing["duration_ms"],
            timing["runner_duration_ms"],
            defaultValue = 0L,
        )
            .takeIf { it > 0L }
            ?: (finishedAtMs - startedAtMs).takeIf { startedAtMs > 0L && finishedAtMs >= startedAtMs }
            ?: stepResults.sumOf { raw ->
                longArg(mapArg(raw)["duration_ms"], defaultValue = 0L).coerceAtLeast(0L)
            }
        val successStepCount = intArg(
            runPayload["success_step_count"],
            defaultValue = stepResults.count { raw -> mapArg(raw)["success"] != false },
        )
        val stepCount = intArg(runPayload["step_count"], defaultValue = stepResults.size)
        val executionSummary = mapArg(runPayload["execution_summary"]).ifEmpty {
            linkedMapOf<String, Any?>(
                "success" to (runPayload["success"] == true),
                "function_id" to functionId,
                "steps" to successStepCount,
                "resolve_calls" to intArg(runPayload["resolve_calls"], defaultValue = 0),
                "model_calls" to intArg(runPayload["model_calls"], defaultValue = 0),
                "tokens" to intArg(runPayload["tokens"], runPayload["total_tokens"], defaultValue = 0),
                "elapsed_ms" to durationMs,
                "failure_reason" to runPayload["error_message"]?.toString()?.takeIf {
                    runPayload["success"] != true && it.isNotBlank()
                },
            ).filterValues { it != null }
        }
        val failedStepIndex = runPayload["failed_step_index"]
        val resumeFromStepResult = runPayload["resume_from_step"]
        val currentStepIndex = runPayload["current_step_index"]
            ?: failedStepIndex
            ?: stepResults.lastOrNull()?.let { mapArg(it)["index"] }
        val currentStepNumber = runPayload["current_step_number"]
            ?: when (currentStepIndex) {
                is Number -> currentStepIndex.toInt().plus(1)
                is String -> currentStepIndex.trim().toIntOrNull()?.plus(1)
                else -> null
            }
        return linkedMapOf<String, Any?>(
            "success" to (runPayload["success"] == true),
            "run_id" to runPayload["run_id"],
            "audit_run_id" to runPayload["audit_run_id"],
            "function_id" to functionId,
            "runner" to runPayload["runner"],
            "step_count" to stepCount,
            "active_step_count" to runPayload["active_step_count"],
            "success_step_count" to successStepCount,
            "completed_step_count" to (runPayload["completed_step_count"] ?: successStepCount),
            "actions_executed" to successStepCount,
            "execution_mode" to executionMode,
            "step_results" to stepResults,
            "started_at_ms" to startedAtMs.takeIf { it > 0L },
            "finished_at_ms" to finishedAtMs.takeIf { it > 0L },
            "duration_ms" to durationMs,
            "runner_duration_ms" to durationMs,
            "timing" to timing,
            "execution_summary" to executionSummary,
            "runtime_resolve_session_id" to runPayload["runtime_resolve_session_id"],
            "failed_step_index" to failedStepIndex,
            "resume_from_step" to resumeFromStepResult,
            "current_step_index" to currentStepIndex,
            "current_step_number" to currentStepNumber,
            "runtime_resolve_attempt" to runPayload["runtime_resolve_attempt"],
            "runtime_resolve_unavailable_reason" to runPayload["runtime_resolve_unavailable_reason"],
            "error_code" to runPayload["error_code"],
            "error_message" to runPayload["error_message"],
            "missing_required_arguments" to runPayload["missing_required_arguments"],
            "result" to runPayload
        ).filterValues { it != null }
    }

    private fun normalizeIncompleteReplay(payload: Map<String, Any?>): Map<String, Any?> {
        if (payload["success"] != true) return payload
        val stepResults = listArg(payload["step_results"])
        val stepCount = intArg(payload["step_count"], defaultValue = stepResults.size)
        val activeStepCount = intArg(payload["active_step_count"], defaultValue = stepCount)
        if (activeStepCount <= 0) return payload
        val successStepCount = intArg(
            payload["success_step_count"],
            defaultValue = stepResults.count { raw -> mapArg(raw)["success"] != false },
        )
        val resumeFromStep = intArg(payload["resume_from_step"], defaultValue = 0).coerceAtLeast(0)
        val completedStepCount = intArg(
            payload["completed_step_count"],
            defaultValue = (resumeFromStep + successStepCount).coerceAtMost(stepCount),
        )
        val completedAllActiveSteps = successStepCount >= activeStepCount &&
            completedStepCount >= (resumeFromStep + activeStepCount).coerceAtMost(stepCount)
        if (completedAllActiveSteps) return payload
        return linkedMapOf<String, Any?>().apply {
            putAll(payload)
            put("success", false)
            put("error_code", "OOB_FUNCTION_INCOMPLETE")
            put(
                "error_message",
                "Function replay finished before all active steps completed: " +
                    "$successStepCount/$activeStepCount"
            )
            put("incomplete_replay_normalized", true)
        }
    }

    private fun functionArguments(request: Map<String, Any?>): Map<String, Any?> =
        mapArg(request["arguments"])

    fun listRunLogs(args: Map<String, Any?>?): Map<String, Any?> {
        val limit = intArg(args?.get("limit"), defaultValue = 50).coerceIn(1, 200)
        val offset = intArg(args?.get("offset"), defaultValue = 0).coerceAtLeast(0)
        return InternalRunLogStore.listRuns(context, limit = limit, offset = offset)
    }

    fun getRunLog(args: Map<String, Any?>?): Map<String, Any?> {
        val runId = firstNonBlank(args?.get("run_id"))
        if (runId.isEmpty()) {
            return errorPayload(code = "RUN_LOG_ID_EMPTY", message = "run_id is required")
        }
        return InternalRunLogStore.timelinePayload(context, runId)
    }

    fun convertRunLog(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val runId = firstNonBlank(request["run_id"])
        return replayService.convertRunLog(
            runId = runId,
            register = boolArgOrDefault(request["register"], defaultValue = false),
            agentVisible = boolArgOrDefault(request["agent_visible"], defaultValue = false),
            functionIdOverride = firstNonBlank(request["function_id"], request["functionId"])
                .takeIf { it.isNotEmpty() },
            nameOverride = firstNonBlank(request["name"]).takeIf { it.isNotEmpty() },
            descriptionOverride = firstNonBlank(request["description"]).takeIf { it.isNotEmpty() }
        )
    }

    private fun ingestInlineRunLog(
        runLog: Map<String, Any?>,
        register: Boolean,
        agentVisible: Boolean,
    ): Map<String, Any?> {
        val runId = firstNonBlank(runLog["run_id"])
            .ifBlank { "inline_${System.currentTimeMillis()}" }
        val resultMap = mapArg(runLog["result"])
        val success = boolArg(runLog["success"]) || boolArg(resultMap["success"])
        val cards = listArg(runLog["cards"]).ifEmpty {
            listArg(runLog["steps"])
        }.map { mapArg(it) }.filter { it.isNotEmpty() }
        val record = InternalRunLogRecord(
            runId = runId,
            goal = firstNonBlank(runLog["goal"], runLog["task"]),
            source = firstNonBlank(runLog["source"]).ifBlank { "external_agent" },
            toolName = firstNonBlank(runLog["tool_name"]),
            operationDescription = firstNonBlank(
                runLog["operation_description"],
                runLog["goal"],
            ),
            startedAtMs = longArg(
                runLog["started_at_ms"],
                defaultValue = System.currentTimeMillis(),
            ),
            finishedAtMs = longArg(
                runLog["finished_at_ms"],
                defaultValue = System.currentTimeMillis(),
            ),
            success = success,
            doneReason = firstNonBlank(resultMap["done_reason"], runLog["done_reason"]),
            errorMessage = firstNonBlank(resultMap["error"], runLog["error_message"]),
            cards = cards,
        )
        val runStatusWarnings = runStatusWarnings(record)
        val spec = RunLogReusableFunctionCompiler.compile(record)
            ?: return errorPayload(
                code = "RUN_LOG_NO_REPLAYABLE_STEPS",
                message = "RunLog has no replayable steps"
            ) + inlineConversionDiagnostics(record, emptyMap(), runStatusWarnings)
        val effectiveSpec = if (agentVisible) spec else markManualFunctionSpec(spec)
        val functionId = OobFunctionRepository.functionIdFromSpec(effectiveSpec)
        if (!register) {
            return linkedMapOf(
                "success" to true,
                "registered" to false,
                "run_id" to record.runId,
                "function_id" to functionId,
                "created_function_id" to functionId,
                "function_spec" to effectiveSpec,
                "summary" to functionRepository.summaryMap(effectiveSpec),
                "source" to "oob_native_omniflow_toolkit"
            ) + inlineConversionDiagnostics(record, effectiveSpec, runStatusWarnings)
        }
        runCatching { workspaceFunctionStore.mirrorRunLog(record) }
            .onFailure { error ->
                OmniLog.w(TAG, "mirror inline runlog before register failed: ${record.runId}, ${error.message}")
            }
        val registration = functionRepository.register(effectiveSpec)
        return registration + linkedMapOf(
            "registered" to (registration["success"] == true),
            "run_id" to record.runId,
            "function_id" to functionId,
            "created_function_id" to functionId,
            "function_spec" to effectiveSpec
        ) + inlineConversionDiagnostics(record, effectiveSpec, runStatusWarnings)
    }

    private fun inlineConversionDiagnostics(
        record: InternalRunLogRecord,
        spec: Map<String, Any?>,
        warnings: List<RunStatusWarning>
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "card_count" to record.cards.size,
        "successful_card_count" to record.cards.count { card ->
            card["success"] != false &&
                (card["header"] as? Map<*, *>)?.get("success") != false
        },
        "compiled_step_count" to compiledStepCount(spec),
        "source_run_finished" to (record.finishedAtMs != null),
        "source_run_success" to (record.success == true),
        "source_run_done_reason" to record.doneReason.takeIf { it.isNotBlank() },
        "source_run_error_message" to record.errorMessage.takeIf { it.isNotBlank() },
    ).apply {
        putAll(runStatusWarningDiagnostics(warnings))
    }.filterValues { it != null }

    private fun compiledStepCount(spec: Map<String, Any?>): Int? {
        val execution = spec["execution"] as? Map<*, *> ?: return null
        return (execution["step_count"] as? Number)?.toInt()
            ?: (execution["steps"] as? List<*>)?.size
    }

    private fun runStatusWarningDiagnostics(
        warnings: List<RunStatusWarning>
    ): Map<String, Any?> {
        if (warnings.isEmpty()) return emptyMap()
        return linkedMapOf(
            "conversion_warning_code" to warnings.first().code,
            "conversion_warning_message" to warnings.first().message,
            "conversion_warning_codes" to warnings.map { it.code },
            "conversion_warnings" to warnings.map { warning ->
                linkedMapOf(
                    "code" to warning.code,
                    "message" to warning.message
                )
            }
        )
    }

    private fun runStatusWarnings(record: InternalRunLogRecord): List<RunStatusWarning> =
        buildList {
            if (record.finishedAtMs == null) {
                add(
                    RunStatusWarning(
                        code = "RUN_LOG_NOT_FINISHED",
                        message = "RunLog is not finished yet: ${record.runId}"
                    )
                )
            }
            if (record.success != true) {
                add(
                    RunStatusWarning(
                        code = "RUN_LOG_NOT_SUCCESSFUL",
                        message = "RunLog did not finish successfully: ${record.runId}"
                    )
                )
            }
        }

    private fun markManualFunctionSpec(spec: Map<String, Any?>): Map<String, Any?> =
        linkedMapOf<String, Any?>().apply {
            putAll(spec)
            put("agent_visible", false)
            put("visibility", "manual_function")
            val metadata = (spec["metadata"] as? Map<*, *>)
                ?.mapNotNull { (key, value) -> key?.toString()?.let { it to value } }
                ?.toMap()
                ?: emptyMap()
            put(
                "metadata",
                linkedMapOf<String, Any?>().apply {
                    putAll(metadata)
                    put("agent_visible", false)
                    put("visibility", "manual_function")
                    put("registered_via", metadata["registered_via"] ?: "run_log_manual_convert")
                    putIfAbsent("enhancement_policy", "offline_only")
                }
            )
        }

    private data class RunStatusWarning(
        val code: String,
        val message: String
    )

    private fun functionAgentSummary(spec: Map<String, Any?>): Map<String, Any?> {
        val execution = mapArg(spec["execution"])
        val steps = materializedSteps(spec)
        val functionId = OobFunctionSchemaBuilder.functionId(spec)
        return linkedMapOf(
            "function_id" to functionId,
            "name" to spec["name"],
            "description" to spec["description"],
            "step_count" to (execution["step_count"] ?: steps.size),
            "omniflow_step_count" to execution["omniflow_step_count"],
            "agent_step_count" to execution["agent_step_count"],
            "has_agent_steps" to (execution["has_agent_steps"] ?: execution["requires_agent_fallback"]),
            "parameter_names" to OobFunctionSchemaBuilder.parameterNames(spec),
            "step_summaries" to stepSummaries(spec),
            "source" to spec["source"],
            "constraints" to spec["constraints"],
        ).filterValues { it != null }
    }

    private fun stepSummaries(spec: Map<String, Any?>): List<Map<String, Any?>> {
        return OobFunctionSchemaBuilder.stepSummaries(spec)
    }

    private fun materializedSteps(spec: Map<String, Any?>): List<Map<String, Any?>> {
        return OobFunctionSchemaBuilder.materializedSteps(spec)
    }

    private fun firstPresent(vararg values: Any?): Any? =
        values.firstOrNull { it != null }

    private fun errorPayload(
        code: String,
        message: String,
        functionId: String = "",
        decision: String? = null,
        riskLevel: String? = null,
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "success" to false,
        "error_code" to code,
        "error_message" to message,
        "function_id" to functionId
    ).apply {
        decision?.let { put("decision", it) }
        riskLevel?.let { put("risk_level", it) }
    }

    private companion object {
        const val TAG = "OobOmniFlowToolkitService"
    }
}
