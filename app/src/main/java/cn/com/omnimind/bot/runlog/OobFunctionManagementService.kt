package cn.com.omnimind.bot.runlog

import android.content.Context
import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.assists.task.vlmserver.AndroidDeviceOperator
import cn.com.omnimind.assists.task.vlmserver.DeviceOperator
import cn.com.omnimind.baselib.runlog.InternalRunLogRecord
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentToolJson
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.omniflow.OobFunctionRecallService
import cn.com.omnimind.bot.omniflow.OobFunctionRepository
import cn.com.omnimind.bot.omniflow.OobFunctionSchemaBuilder
import cn.com.omnimind.bot.omniflow.OobFunctionToolNames
import cn.com.omnimind.bot.omniflow.OobFunctionUpdateService
import cn.com.omnimind.bot.omniflow.WorkspaceFunctionStore
import cn.com.omnimind.bot.runlog.boolArgOrDefault
import cn.com.omnimind.bot.runlog.firstNonBlank
import cn.com.omnimind.bot.runlog.intArg
import cn.com.omnimind.bot.runlog.listArg
import cn.com.omnimind.bot.runlog.longArg
import cn.com.omnimind.bot.runlog.mapArg
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * OOB-native implementation of Function management tools.
 *
 * The service deliberately keeps Function management local: Functions are
 * registered in OOB stores and recall is deterministic. Function execution is
 * owned by OobFunctionToolHandler.
 */
class OobFunctionManagementService(
    private val context: Context,
    private val deviceOperator: DeviceOperator = AndroidDeviceOperator(null, context),
    private val workspaceFunctionStore: WorkspaceFunctionStore = WorkspaceFunctionStore(
        AgentWorkspaceManager.rootDirectory(context)
    ),
    private val updateAgentRequester: suspend (prompt: String, responseJsonObject: Boolean) -> String? =
        { prompt, responseJsonObject ->
            requestAgentAnalysis(prompt, responseJsonObject)
        },
) {
    private val functionRepository = OobFunctionRepository(context, workspaceFunctionStore)
    private val runLogConverter = OobRunLogFunctionConverter(context, workspaceFunctionStore, functionRepository)
    private val functionRecallService = OobFunctionRecallService(context, functionRepository, deviceOperator)
    private val functionUpdateService = OobFunctionUpdateService(context, functionRepository)
    private val updateAgentResponseJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    suspend fun executeTool(name: String?, args: Map<String, Any?>?): Map<String, Any?> {
        return when (name) {
            "omniflow.recall" -> recall(args)
            "omniflow.ingest_run_log" -> ingestRunLog(args)
            OobFunctionToolNames.FUNCTION_LIST -> listFunctions(args)
            OobFunctionToolNames.FUNCTION_GET -> getFunction(args)
            OobFunctionToolNames.FUNCTION_REGISTER -> registerFunction(args)
            OobFunctionToolNames.FUNCTION_UPDATE -> updateFunction(args)
            OobFunctionToolNames.FUNCTION_DELETE -> deleteFunction(args)
            OobFunctionToolNames.FUNCTION_CLEAR -> clearFunctions(args)
            OobFunctionToolNames.RUN_LOG_LIST -> listRunLogs(args)
            OobFunctionToolNames.RUN_LOG_GET -> getRunLog(args)
            OobFunctionToolNames.RUN_LOG_CONVERT -> convertRunLog(args)
            null, "" -> errorPayload(code = "TOOL_NAME_EMPTY", message = "Missing Function management tool name")
            else -> errorPayload(code = "UNKNOWN_FUNCTION_MANAGEMENT_TOOL", message = "Unknown Function management tool: $name")
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
            runLogConverter.convertRunLog(runId = runId, register = register, agentVisible = agentVisible)
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
            "source" to "oob_function_management"
        )
    }

    fun listFunctions(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
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
        val name = firstNonBlank(request["name"], request["title"], rawFunctionId).ifBlank { "Reusable function" }
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

    suspend fun updateFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val initial = functionUpdateService.updateFunction(request)
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

        val prompt = firstNonBlank(initial["agent_prompt"])
        if (prompt.isBlank()) {
            return agentFailure(
                initial = initial,
                code = "AGENT_PROMPT_EMPTY",
                message = "update_function returned needs_agent_analysis without agent_prompt",
                agentInvoked = false,
            )
        }

        val raw = try {
            val jsonResponse = updateAgentRequester(prompt, true).orEmpty()
            if (jsonResponse.trim().isNotEmpty()) {
                jsonResponse
            } else {
                updateAgentRequester(prompt, false).orEmpty()
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
            put("source", "oob_function_management")
            put("agent_model_invoked", true)
        }
        val updated = functionUpdateService.updateFunction(nextArgs)
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
        return runLogConverter.convertRunLog(
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
                "source" to "oob_function_management"
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
            boolArg(request["offlineJob"])
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
            val jsonObject = updateAgentResponseJson.parseToJsonElement(text) as? JsonObject
                ?: return null
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
        return if (hasAnalysisShape) cn.com.omnimind.bot.omniflow.OobFunctionJson.sanitizeMap(json) else emptyMap()
    }

    private fun patchFromAgentUpdateJson(
        json: Map<String, Any?>,
        analysis: Map<String, Any?>,
    ): Map<String, Any?> {
        val direct = firstNonEmptyMap(json, listOf("patch", "function_patch", "updates", "recommended_patch"))
        if (direct.isNotEmpty()) return direct
        val directShape = directPatchShape(json)
        if (directShape.isNotEmpty()) return directShape
        val analysisPatch = firstNonEmptyMap(analysis, listOf("recommended_patch", "patch", "function_patch"))
        if (analysisPatch.isNotEmpty()) return analysisPatch
        val toolArgs = firstNonEmptyMap(json, listOf("arguments", "args", "tool_args", "toolArgs"))
        return if (toolArgs.isNotEmpty()) patchFromAgentUpdateJson(toolArgs, analysis) else emptyMap()
    }

    private fun directPatchShape(source: Map<String, Any?>): Map<String, Any?> {
        val patch = linkedMapOf<String, Any?>()
        DIRECT_PATCH_KEYS.forEach { key ->
            if (source.containsKey(key)) patch[key] = source[key]
        }
        return cn.com.omnimind.bot.omniflow.OobFunctionJson.sanitizeMap(patch)
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
            is Map<*, *> -> cn.com.omnimind.bot.omniflow.OobFunctionJson.sanitizeMap(value)
            is String -> parseJsonObject(value) ?: emptyMap()
            else -> emptyMap()
        }

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
        const val TAG = "OobFunctionManagementService"
        private const val AGENT_MODEL = "scene.dispatch.model"

        private suspend fun requestAgentAnalysis(prompt: String, responseJsonObject: Boolean): String? =
            HttpController.postLLMRequest(AGENT_MODEL, prompt, responseJsonObject).message

        private val JSON_FENCE_REGEX = Regex(
            "^```(?:json)?\\s*([\\s\\S]*?)\\s*```$",
            setOf(RegexOption.IGNORE_CASE),
        )

        private val DIRECT_PATCH_KEYS = listOf(
            "name",
            "description",
            "steps",
            "parameters",
            "agent_reuse",
            "agentReuse",
            "metadata",
            "checker_rules",
            "checkerRules",
            "ops",
            "operations",
            "repairs",
            "replace_target",
            "replaceTarget",
        )
    }
}
