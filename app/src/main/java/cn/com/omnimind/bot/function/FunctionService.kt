package cn.com.omnimind.bot.function

import android.content.Context
import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.assists.task.vlmserver.AndroidDeviceOperator
import cn.com.omnimind.assists.task.vlmserver.DeviceOperator
import cn.com.omnimind.baselib.runlog.InternalRunLogRecord
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentToolJson
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.runlog.OobUdegNodeStore
import cn.com.omnimind.bot.runlog.ReplayCheckerRule
import cn.com.omnimind.bot.runlog.boolArg
import cn.com.omnimind.bot.runlog.boolArgOrDefault
import cn.com.omnimind.bot.runlog.firstNonBlank
import cn.com.omnimind.bot.runlog.intArg
import cn.com.omnimind.bot.runlog.listArg
import cn.com.omnimind.bot.runlog.longArg
import cn.com.omnimind.bot.runlog.mapArg
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject

/**
 * OOB-native implementation of Function management tools.
 *
 * The service deliberately keeps Function management local: Functions are
 * registered in OOB stores and recall is deterministic. Function execution is
 * owned by FunctionRun.
 */
class FunctionService(
    private val context: Context,
    private val deviceOperator: DeviceOperator = AndroidDeviceOperator(null, context),
    private val workspaceFunctionStore: FunctionStore = FunctionStore(
        AgentWorkspaceManager.rootDirectory(context)
    ),
    private val updateAgentRequester: suspend (prompt: String, responseJsonObject: Boolean) -> String? =
        { prompt, responseJsonObject ->
            requestAgentAnalysis(prompt, responseJsonObject)
        },
) {
    private val updateAgentResponseJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    suspend fun executeTool(name: String?, args: Map<String, Any?>?): Map<String, Any?> {
        return when (name) {
            FunctionApi.FUNCTION_RECALL,
            FunctionApi.LEGACY_FUNCTION_RECALL -> recall(args)
            FunctionApi.FUNCTION_INGEST_RUN_LOG,
            FunctionApi.LEGACY_FUNCTION_INGEST_RUN_LOG -> ingestRunLog(args)
            FunctionApi.FUNCTION_LIST -> listFunctions(args)
            FunctionApi.FUNCTION_GET -> getFunction(args)
            FunctionApi.FUNCTION_REGISTER -> registerFunction(args)
            FunctionApi.FUNCTION_UPDATE -> updateFunction(args)
            FunctionApi.FUNCTION_DELETE -> deleteFunction(args)
            FunctionApi.FUNCTION_CLEAR -> clearFunctions(args)
            FunctionApi.RUN_LOG_LIST -> listRunLogs(args)
            FunctionApi.RUN_LOG_GET -> getRunLog(args)
            FunctionApi.RUN_LOG_CONVERT -> convertRunLog(args)
            null, "" -> errorPayload(code = "TOOL_NAME_EMPTY", message = "Missing Function management tool name")
            else -> errorPayload(code = "UNKNOWN_FUNCTION_MANAGEMENT_TOOL", message = "Unknown Function management tool: $name")
        }
    }

    fun recall(args: Map<String, Any?>?): Map<String, Any?> {
        val startedAt = System.currentTimeMillis()
        val request = args ?: emptyMap()
        val goal = firstNonBlank(request["goal"], request["query"], request["task"])
        val includeDebug = boolArg(request["include_debug"]) ||
            boolArg(request["includeDebug"]) ||
            boolArg(request["debug"])
        val currentPackage = firstNonBlank(
            request["current_package"],
            request["currentPackage"],
            runCatching { deviceOperator.currentPackageName() }.getOrNull(),
        )
        val limit = intArg(request["k"], defaultValue = DEFAULT_RECALL_LIMIT)
            .coerceIn(1, MAX_RECALLED_FUNCTIONS)
        val hits = workspaceFunctionStore.recall(goal = goal, limit = limit)
        val candidates = hits.mapNotNull { hit ->
            val spec = getFunctionSpec(hit.functionId) ?: return@mapNotNull null
            if (!isAgentVisible(spec)) return@mapNotNull null
            recallCandidateMap(spec = spec, hit = hit, currentPackage = currentPackage)
        }
        val decision = if (candidates.isNotEmpty()) "recall" else "miss"
        return linkedMapOf<String, Any?>(
            "success" to true,
            "decision" to decision,
            "candidates" to candidates,
            "count" to candidates.size,
            "reason" to when {
                goal.isBlank() -> "empty_goal"
                candidates.isEmpty() -> "no_function_index_match"
                else -> "function_index_match"
            },
            "current_package" to currentPackage.takeIf { it.isNotBlank() },
            "source" to "function_recall",
            "payload_mode" to if (includeDebug) "debug_full" else "agent_compact",
            "timing" to linkedMapOf(
                "source" to "function_recall",
                "decision" to decision,
                "duration_ms" to (System.currentTimeMillis() - startedAt).coerceAtLeast(0L),
                "counts" to linkedMapOf(
                    "index_hits" to hits.size,
                    "function_candidates" to candidates.size,
                )
            ),
        ).filterValues { it != null }
    }

    fun listFunctionSpecs(limit: Int = 100, includeHidden: Boolean = false): List<Map<String, Any?>> =
        listSpecsPage(limit = limit, offset = 0, includeHidden = includeHidden).specs

    fun ingestRunLog(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val register = boolArgOrDefault(request["register"], defaultValue = false)
        val agentVisible = boolArgOrDefault(request["agent_visible"], defaultValue = false)
        val runId = firstNonBlank(request["run_id"])
        val rawRunLog = mapArg(request["run_log"])
        val result = if (runId.isNotEmpty()) {
            convertStoredRunLog(runId = runId, register = register, agentVisible = agentVisible)
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
        return listFunctionsPage(
            limit = intArg(request["limit"], defaultValue = 100),
            offset = intArg(request["offset"], defaultValue = 0),
            includeHidden = boolArg(request["include_hidden"]) || boolArg(request["includeHidden"]),
        )
    }

    fun getFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val functionId = firstNonBlank(args?.get("function_id"))
        val spec = getFunctionSpec(functionId)
        if (spec == null) {
            return errorPayload(
                code = "OOB_FUNCTION_NOT_FOUND",
                message = "Function not found: $functionId",
                functionId = functionId
            )
        }
        val displaySpec = withFunctionPromptSpec(spec)
        return linkedMapOf<String, Any?>().apply {
            putAll(displaySpec)
            put("success", true)
            put("function", displaySpec)
            put("function_id", firstNonBlank(FunctionSchema.functionId(spec), functionId))
            put("summary", functionAgentSummary(spec))
            put("response_source", "oob_native_function_store")
        }
    }

    fun deleteFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val functionId = firstNonBlank(args?.get("function_id"))
        return deleteFunctionSpec(functionId)
    }

    fun clearFunctions(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val confirmed = boolArg(request["confirm"]) ||
            boolArg(request["confirmed"]) ||
            firstNonBlank(request["action"]).equals("clear_all", ignoreCase = true)
        if (!confirmed) {
            return errorPayload(
                code = "OOB_FUNCTION_CLEAR_CONFIRMATION_REQUIRED",
                message = "Set confirm=true to clear all registered Functions"
            )
        }
        return clearFunctionSpecs()
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
        val registration = saveFunctionSpec(functionSpec)
        val registeredId = firstNonBlank(
            registration["function_id"],
            registration["created_function_id"],
            FunctionSchema.functionId(functionSpec),
        )
        val savedFunction = if (registration["success"] == true && registeredId.isNotBlank()) {
            getFunctionSpec(registeredId) ?: functionSpec
        } else {
            functionSpec
        }
        return withFunctionPromptPayload(
            registration + linkedMapOf(
                "registration_input_mode" to mode,
                "simple_schema_supported" to true,
                "function" to savedFunction,
                "function_spec" to savedFunction,
            )
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
            cn.com.omnimind.bot.function.FunctionStepNormalizer.normalizeSimpleRegisteredStep(
                raw = raw,
                index = index,
                inheritedSourceContext = if (index == 0) sourceContext else emptyMap(),
            )
        }
        val capabilities = cn.com.omnimind.bot.function.FunctionStepNormalizer.executionCapabilities(normalizedSteps)
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
                "function_step_count" to capabilities["function_step_count"],
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

    private fun saveFunctionSpec(functionSpec: Map<String, Any?>): Map<String, Any?> {
        val rawSpec = FunctionParameterBindingNormalizer.normalize(
            FunctionJson.sanitizeMap(functionSpec)
        )
        val rawFunctionId = functionIdFromSpec(rawSpec)
        if (rawFunctionId.isEmpty()) {
            return errorPayload(
                code = "FUNCTION_ID_EMPTY",
                message = "function_id is required"
            )
        }
        val functionId = normalizeFunctionId(rawFunctionId)
        val spec = linkedMapOf<String, Any?>().apply {
            putAll(rawSpec)
            put("function_id", functionId)
            putIfAbsent("name", functionId)
        }
        val alreadyExists = containsFunctionSpec(functionId)
        val agentVisible = isAgentVisible(spec)
        val udegResult = runCatching {
            OobUdegNodeStore(context).upsertFunction(functionId, spec)
        }.getOrElse { error ->
            linkedMapOf(
                "success" to false,
                "indexed" to false,
                "error_message" to error.message.orEmpty()
            )
        }

        val workspaceResult = runCatching {
            workspaceFunctionStore.register(spec)
        }.getOrElse { error ->
            OmniLog.w(TAG, "workspace function save failed: $functionId, ${error.message}")
            linkedMapOf(
                "success" to false,
                "error_code" to "WORKSPACE_REGISTER_FAILED",
                "error_message" to error.message.orEmpty()
            )
        }
        val success = workspaceResult["success"] == true
        val registeredSpec = if (success) workspaceFunctionStore.get(functionId) else null
        val sourceRunIds = registeredSpec?.let(::sourceRunIds) ?: sourceRunIds(spec)
        val runLogBindings = if (success) {
            sourceRunIds.mapNotNull { runId ->
                runCatching {
                    InternalRunLogStore.bindRegisteredFunction(
                        context = context,
                        runId = runId,
                        functionId = functionId,
                        functionSpec = registeredSpec ?: spec
                    )
                }.onFailure { error ->
                    OmniLog.w(
                        TAG,
                        "bind registered function to runlog failed: $runId -> $functionId, ${error.message}"
                    )
                }.getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { binding ->
                        linkedMapOf(
                            "run_id" to runId,
                            "function_id" to functionId,
                            "success" to true,
                            "binding" to binding
                        )
                    }
            }
        } else {
            emptyList()
        }
        return linkedMapOf(
            "success" to success,
            "function_id" to functionId,
            "created_function_id" to functionId,
            "imported" to success,
            "already_exists" to alreadyExists,
            "function_kind" to functionKind(spec),
            "asset_state" to assetState(spec),
            "agent_visible" to agentVisible,
            "visibility" to visibility(spec),
            "runner" to "oob_agent_reusable_function",
            "parameter_binding_normalization" to FunctionJson.mapArg(spec["metadata"])
                ["oob_parameter_binding_normalization"],
            "workspace" to workspaceResult,
            "udeg" to udegResult,
            "normalized_from_function_id" to rawFunctionId.takeIf { it != functionId },
            "source_run_ids" to sourceRunIds,
            "run_log_bindings" to runLogBindings,
            "run_log_binding_count" to runLogBindings.size
        )
    }

    private fun listFunctionsPage(
        limit: Int = 100,
        offset: Int = 0,
        includeHidden: Boolean = false,
    ): Map<String, Any?> {
        val page = listSpecsPage(limit = limit, offset = offset, includeHidden = includeHidden)
        return linkedMapOf(
            "success" to true,
            "count" to page.specs.size,
            "limit" to page.limit,
            "offset" to page.offset,
            "next_offset" to (page.offset + page.specs.size),
            "has_more" to page.hasMore,
            "functions" to page.specs.map(::summaryMap),
            "function_kind" to "oob_reusable_function",
            "asset_state" to "native_local",
            "include_hidden" to includeHidden,
            "source" to "function_service"
        )
    }

    private fun getFunctionSpec(functionId: String): Map<String, Any?>? {
        val normalized = functionId.trim()
        if (normalized.isEmpty()) return null
        val workspaceSpec = workspaceFunctionStore.get(normalized)
        return workspaceSpec?.let { withSourceRunSummary(FunctionJson.sanitizeMap(it)) }
    }

    private fun deleteFunctionSpec(functionId: String): Map<String, Any?> {
        val normalized = functionId.trim()
        if (normalized.isEmpty()) {
            return errorPayload(
                code = "FUNCTION_ID_EMPTY",
                message = "function_id is required"
            )
        }
        val deletedWorkspace = workspaceFunctionStore.delete(normalized)
        val udegResult = OobUdegNodeStore(context).removeFunctionReferences(setOf(normalized))
        return linkedMapOf(
            "success" to deletedWorkspace,
            "function_id" to normalized,
            "deleted" to deletedWorkspace,
            "deleted_workspace" to deletedWorkspace,
            "udeg" to udegResult,
            "source" to "function_service",
        )
    }

    private fun clearFunctionSpecs(): Map<String, Any?> {
        val functionIds = workspaceFunctionStore.functionIds()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val workspaceResult = workspaceFunctionStore.clear()
        val udegResult = OobUdegNodeStore(context).clearFunctionReferences()
        return linkedMapOf(
            "success" to true,
            "deleted" to true,
            "deleted_count" to functionIds.size,
            "function_ids" to functionIds,
            "workspace" to workspaceResult,
            "udeg" to udegResult,
            "source" to "function_service",
        )
    }

    private fun containsFunctionSpec(functionId: String): Boolean {
        val normalized = functionId.trim()
        return normalized.isNotEmpty() && workspaceFunctionStore.canHandle(normalized)
    }

    private fun summaryMap(spec: Map<String, Any?>): Map<String, Any?> {
        val callable = FunctionSchema.callableSummary(spec)
        val execution = spec["execution"] as? Map<*, *>
        val steps = FunctionSchema.materializedSteps(spec)
        val registry = spec["_oob_registry"] as? Map<*, *>
        val source = spec["source"] as? Map<*, *>
        val runStats = registry?.get("run_stats") as? Map<*, *>
        val sourceRunIds = sourceRunIds(spec)
        return linkedMapOf(
            "function_id" to callable["function_id"],
            "name" to callable["name"],
            "description" to callable["description"],
            "parameters" to callable["parameters"],
            "input_schema" to callable["parameters"],
            "step_count" to (execution?.get("step_count") ?: steps.size),
            "card_count" to (
                FunctionJson.intArg(source?.get("card_count"), defaultValue = 0)
                    .takeIf { it > 0 }
                    ?: FunctionJson.intArg(source?.get("replayable_card_count"), defaultValue = 0)
                    .takeIf { it > 0 }
                    ?: steps.size
            ),
            "function_step_count" to (
                execution?.get("function_step_count")
                    ?: execution?.get("omniflow_step_count")
            ),
            "parameter_names" to callable["argument_names"],
            "step_summaries" to FunctionSchema.stepSummaries(spec),
            "function_kind" to functionKind(spec),
            "asset_state" to assetState(spec),
            "agent_visible" to isAgentVisible(spec),
            "visibility" to visibility(spec),
            "runner" to "oob_agent_reusable_function",
            "registered_at" to registry?.get("registered_at"),
            "updated_at" to registry?.get("updated_at"),
            "source_run_ids" to sourceRunIds,
            "source_run_count" to sourceRunIds.size,
            "source" to spec["source"],
            "run_stats" to FunctionJson.sanitizeValue(
                runStats ?: emptyMap<Any?, Any?>()
            ),
            "last_run" to FunctionJson.sanitizeValue(
                runStats?.get("last_run") ?: emptyMap<Any?, Any?>()
            )
        )
    }

    private fun withSourceRunSummary(spec: Map<String, Any?>): Map<String, Any?> {
        val functionId = functionIdFromSpec(spec)
        val sourceRunIds = sourceRunIds(spec)
        val sourceRuns = InternalRunLogStore.sourceRunSummariesForFunction(
            context = context,
            functionId = functionId,
            sourceRunIds = sourceRunIds
        )
        return linkedMapOf<String, Any?>().apply {
            putAll(spec)
            put("source_run_ids", sourceRuns["source_run_ids"])
            put("source_run_count", sourceRuns["source_run_count"])
            put("source_runs", sourceRuns["source_runs"])
            put("source_run_summary_count", sourceRuns["source_run_summary_count"])
            put("missing_source_run_ids", sourceRuns["missing_source_run_ids"])
            put("missing_source_run_count", sourceRuns["missing_source_run_count"])
        }
    }

    private fun listSpecsPage(
        limit: Int = 100,
        offset: Int = 0,
        includeHidden: Boolean = false,
    ): FunctionSpecPage {
        val safeLimit = limit.coerceIn(1, 500)
        val safeOffset = offset.coerceAtLeast(0)
        val scanLimit = (safeOffset + safeLimit + 1).coerceIn(1, 500)
        val visibleSpecs = workspaceFunctionStore.list(scanLimit)
            .map(FunctionJson::sanitizeMap)
            .filter { includeHidden || isAgentVisible(it) }
        val window = visibleSpecs.drop(safeOffset).take(safeLimit + 1).toList()
        return FunctionSpecPage(
            specs = window.take(safeLimit),
            limit = safeLimit,
            offset = safeOffset,
            hasMore = window.size > safeLimit
        )
    }

    private data class FunctionSpecPage(
        val specs: List<Map<String, Any?>>,
        val limit: Int,
        val offset: Int,
        val hasMore: Boolean
    )

    private fun convertStoredRunLog(
        runId: String,
        register: Boolean = false,
        agentVisible: Boolean = false,
        functionIdOverride: String? = null,
        nameOverride: String? = null,
        descriptionOverride: String? = null,
    ): Map<String, Any?> {
        val normalizedRunId = runId.trim()
        if (normalizedRunId.isEmpty()) {
            return errorPayload(
                code = "RUN_LOG_ID_EMPTY",
                message = "run_id is required",
                functionId = normalizedRunId
            )
        }
        val record = InternalRunLogStore.getRun(context, normalizedRunId)
            ?: return errorPayload(
                code = "RUN_LOG_NOT_FOUND",
                message = "RunLog not found: $normalizedRunId",
                functionId = normalizedRunId
            )
        val runStatusWarnings = runStatusWarnings(record)
        val compiled = FunctionCompiler.compile(record)
            ?: return errorPayload(
                code = "RUN_LOG_NO_REPLAYABLE_STEPS",
                message = "RunLog has no replayable steps",
                functionId = normalizedRunId,
            ) + noReplayableStepDiagnostics(record) + runStatusWarningDiagnostics(runStatusWarnings)
        val spec = applyRunLogOverrides(
            spec = compiled,
            functionIdOverride = functionIdOverride,
            nameOverride = nameOverride,
            descriptionOverride = descriptionOverride,
            agentVisible = agentVisible,
        )
        val functionId = functionIdFromSpec(spec)
        if (!register) {
            return linkedMapOf<String, Any?>(
                "success" to true,
                "registered" to false,
                "run_id" to normalizedRunId,
                "function_id" to functionId,
                "created_function_id" to functionId,
                "function_spec" to spec,
                "summary" to summaryMap(spec),
                "function_kind" to "oob_reusable_function",
                "asset_state" to "native_local",
                "source" to "function_service"
            ).apply {
                putAll(conversionDiagnostics(record, spec, runStatusWarnings))
            }
        }

        runCatching { workspaceFunctionStore.mirrorRunLog(record) }
            .onFailure { error ->
                OmniLog.w(TAG, "mirror runlog before register failed: ${record.runId}, ${error.message}")
            }
        val registration = saveFunctionSpec(spec).toMutableMap()
        return registration.apply {
            put("registered", this["success"] == true)
            put("run_id", normalizedRunId)
            put("function_spec", spec)
            put("summary", summaryMap(spec))
            put("source", "function_service")
            putAll(conversionDiagnostics(record, spec, runStatusWarnings))
        }
    }

    private fun noReplayableStepDiagnostics(record: InternalRunLogRecord): Map<String, Any?> =
        linkedMapOf(
            "card_count" to record.cards.size,
            "successful_card_count" to successfulCardCount(record),
            "source_run_finished" to (record.finishedAtMs != null),
            "source_run_success" to (record.success == true),
            "source_run_done_reason" to record.doneReason.takeIf { it.isNotBlank() },
            "source_run_error_message" to record.errorMessage.takeIf { it.isNotBlank() },
        )

    private fun conversionDiagnostics(
        record: InternalRunLogRecord,
        spec: Map<String, Any?>,
        runStatusWarnings: List<RunStatusWarning> = runStatusWarnings(record),
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "card_count" to record.cards.size,
        "successful_card_count" to successfulCardCount(record),
        "compiled_step_count" to compiledStepCount(spec),
        "source_run_finished" to (record.finishedAtMs != null),
        "source_run_success" to (record.success == true),
        "source_run_done_reason" to record.doneReason.takeIf { it.isNotBlank() },
        "source_run_error_message" to record.errorMessage.takeIf { it.isNotBlank() },
    ).apply {
        putAll(runStatusWarningDiagnostics(runStatusWarnings))
    }.filterValues { it != null }

    private fun successfulCardCount(record: InternalRunLogRecord): Int =
        record.cards.count { card ->
            card["success"] != false &&
                (card["header"] as? Map<*, *>)?.get("success") != false
        }

    private fun applyRunLogOverrides(
        spec: Map<String, Any?>,
        functionIdOverride: String?,
        nameOverride: String?,
        descriptionOverride: String?,
        agentVisible: Boolean,
    ): Map<String, Any?> {
        val functionId = functionIdOverride?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { normalizeFunctionId(it) }
        val name = nameOverride?.trim()?.takeIf { it.isNotEmpty() }
        val description = descriptionOverride?.trim()?.takeIf { it.isNotEmpty() }
        return linkedMapOf<String, Any?>().apply {
            putAll(spec)
            functionId?.let { put("function_id", it) }
            name?.let { put("name", it) }
            description?.let { put("description", it) }
            putAll(
                if (agentVisible) {
                    markAgentReusableSpec(this)
                } else {
                    markManualFunctionSpec(this)
                }
            )
        }
    }

    private fun markAgentReusableSpec(spec: Map<String, Any?>): Map<String, Any?> =
        linkedMapOf<String, Any?>().apply {
            putAll(spec)
            put("agent_visible", true)
            put("visibility", "agent_reusable")
            val metadata = (spec["metadata"] as? Map<*, *>)
                ?.mapNotNull { (key, value) -> key?.toString()?.let { it to value } }
                ?.toMap()
                ?: emptyMap()
            put(
                "metadata",
                linkedMapOf<String, Any?>().apply {
                    putAll(metadata)
                    put("agent_visible", true)
                    put("visibility", "agent_reusable")
                    put("registered_via", metadata["registered_via"] ?: "run_log_agent_visible_convert")
                    putIfAbsent("enhancement_policy", "offline_only")
                }
            )
        }

    private fun recallCandidateMap(
        spec: Map<String, Any?>,
        hit: FunctionStore.RecallHit,
        currentPackage: String,
    ): Map<String, Any?> {
        val callable = FunctionSchema.callableSummary(spec)
        val functionId = firstNonBlank(callable["function_id"], FunctionSchema.functionId(spec))
        val packageNames = packageScopes(spec)
        return linkedMapOf<String, Any?>(
            "capability_type" to "function",
            "function_id" to functionId,
            "description" to firstNonBlank(callable["description"], callable["name"], functionId),
            "name" to callable["name"],
            "parameters" to callable["parameters"],
            "inputSchema" to callable["parameters"],
            "input_schema" to callable["parameters"],
            "score" to hit.score,
            "score_order" to "local_token_overlap_descending",
            "reason" to "local_token_overlap",
            "recall_scope" to "function_index",
            "current_package_match" to (
                currentPackage.isNotBlank() && packageNames.contains(currentPackage)
                ),
            "package_names" to packageNames.takeIf { it.isNotEmpty() },
            "requires_arguments" to !isNoArgumentFunction(spec),
            "resolve_policy" to argumentResolvePolicy(spec),
            "execution_scope" to "function",
            "step_count" to FunctionSchema.materializedSteps(spec).size,
            "step_summaries" to FunctionSchema.stepSummaries(spec),
            "function_profile" to functionProfile(spec),
            "function_kind" to "oob_reusable_function",
            "asset_state" to "native_local",
            "source" to "function_recall",
        ).filterValues { it != null }
    }

    private fun functionProfile(spec: Map<String, Any?>): Map<String, Any?> {
        val metadata = mapArg(spec["metadata"])
        val agentReuse = mapArg(spec["agent_reuse"])
            .ifEmpty { mapArg(metadata["agent_reuse"]) }
        val source = mapArg(spec["source"])
        return linkedMapOf<String, Any?>(
            "purpose" to firstNonBlank(
                spec["description"],
                spec["name"],
                FunctionSchema.functionId(spec),
            ),
            "use_when" to firstNonBlank(
                agentReuse["use_when"],
                agentReuse["reuse_when"],
                source["goal"],
            ).takeIf { it.isNotBlank() },
            "success_signal" to firstNonBlank(
                agentReuse["success_signal"],
                agentReuse["successSignal"],
            ).takeIf { it.isNotBlank() },
            "limitations" to listArg(agentReuse["limitations"]).take(5).takeIf { it.isNotEmpty() },
            "common_situations" to listArg(agentReuse["common_situations"])
                .ifEmpty { listArg(agentReuse["commonSituations"]) }
                .take(5)
                .takeIf { it.isNotEmpty() },
            "package_name" to packageScopes(spec).firstOrNull(),
        ).filterValues { it != null }
    }

    private fun packageScopes(spec: Map<String, Any?>): Set<String> {
        val constraints = mapArg(spec["constraints"])
        val source = mapArg(spec["source"])
        return buildList {
            listOf(
                constraints["package_name"],
                constraints["packageName"],
                source["package_name"],
                source["packageName"],
            ).map { firstNonBlank(it) }
                .filterTo(this) { it.isNotBlank() }
            FunctionSchema.materializedSteps(spec).forEach { step ->
                val args = mapArg(step["args"])
                val sourceContext = mapArg(step["source_context"])
                val srcCtx = mapArg(sourceContext["src_ctx"])
                val dstCtx = mapArg(sourceContext["dst_ctx"])
                val sourceAction = mapArg(sourceContext["action"])
                listOf(
                    args["package_name"],
                    args["packageName"],
                    srcCtx["package_name"],
                    srcCtx["packageName"],
                    dstCtx["package_name"],
                    dstCtx["packageName"],
                    sourceAction["package_name"],
                    sourceAction["packageName"],
                ).map { firstNonBlank(it) }
                    .filterTo(this) { it.isNotBlank() }
            }
        }.toSet()
    }

    private fun isNoArgumentFunction(spec: Map<String, Any?>): Boolean {
        val schema = FunctionSchema.inputSchema(spec)
        return listArg(schema["required"]).isEmpty() && mapArg(schema["properties"]).isEmpty()
    }

    private fun argumentResolvePolicy(spec: Map<String, Any?>): String =
        if (isNoArgumentFunction(spec)) {
            "no_arguments_required"
        } else {
            "goal_bound_arguments_required"
        }

    private fun applyFunctionUpdateRequest(args: Map<String, Any?>?): Map<String, Any?> {
        val updateStartedAtMs = System.currentTimeMillis()
        val request = args ?: emptyMap()
        val runId = firstNonBlank(request["run_id"])
        val runLogTimeline = if (runId.isNotEmpty()) {
            val timeline = InternalRunLogStore.timelinePayload(context, runId)
            if (timeline["success"] != true) {
                return errorPayload(code = "RUN_LOG_NOT_FOUND", message = "RunLog not found: $runId")
            }
            timeline
        } else {
            emptyMap()
        }
        val functionId = firstNonBlank(
            request["function_id"], request["functionId"],
            runLogTimeline["registered_function_id"],
            mapArg(runLogTimeline["registered_function_spec"])["function_id"],
        )
        if (functionId.isEmpty()) {
            return errorPayload(code = "FUNCTION_ID_EMPTY", message = "update_function requires function_id")
        }
        val original = getFunctionSpec(functionId)
            ?: return errorPayload(
                code = "OOB_FUNCTION_NOT_FOUND",
                message = "Function not found: $functionId",
                functionId = functionId
            )
        val mode = firstNonBlank(request["mode"], request["operation"]).lowercase().ifBlank { "enhance" }
        val dryRun = boolArg(request["dry_run"]) || boolArg(request["dryRun"])
        val instruction = firstNonBlank(request["instruction"], request["request"], request["user_instruction"])
        val analysis = mapArg(request["analysis"]).ifEmpty { mapArg(request["evidence_analysis"]) }
        val patch = mapArg(request["patch"])
            .ifEmpty { mapArg(request["function_patch"]) }
            .ifEmpty { mapArg(request["updates"]) }
            .ifEmpty { mapArg(analysis["recommended_patch"]) }
        val updateCost = updateCostPayload(
            request = request,
            analysis = analysis,
            patch = patch,
            startedAtMs = updateStartedAtMs,
            mode = mode,
        )

        if (runId.isNotEmpty() && analysis.isEmpty() && patch.isEmpty()) {
            val analysisContext = buildRunLogAnalysisContext(
                functionId = functionId,
                functionSpec = original,
                runLogTimeline = runLogTimeline,
                instruction = instruction,
            )
            return linkedMapOf(
                "success" to true,
                "function_id" to functionId,
                "run_id" to runId,
                "mode" to mode,
                "changed" to false,
                "saved" to false,
                "dry_run" to dryRun,
                "requires_confirmation" to false,
                "function" to original,
                "updated_function" to original,
                "needs_agent_analysis" to true,
                "analysis_context" to analysisContext,
                "agent_prompt" to buildFunctionReviewPrompt(analysisContext),
                "message" to "已读取 Function 和 RunLog，等待 agent 分析后再保存。",
                "cost" to updateCost,
                "source" to "function_service"
            )
        }

        val updated = FunctionJson.mutableJsonMap(original)
        val changes = mutableListOf<Map<String, Any?>>()
        if (patch.isNotEmpty()) changes += applyFunctionPatch(updated, patch)
        if (analysis.isNotEmpty()) changes += applyRunLogEvidenceAnalysis(updated, runId, analysis)
        updated["function_id"] = functionId

        val changed = changes.isNotEmpty()
        appendUpdateAudit(
            spec = updated,
            mode = mode,
            instruction = instruction,
            changed = changed,
            dryRun = dryRun,
            changes = changes,
            updateCost = updateCost,
        )
        if (!changed) {
            return linkedMapOf(
                "success" to true,
                "function_id" to functionId,
                "mode" to mode,
                "changed" to false,
                "saved" to false,
                "dry_run" to dryRun,
                "requires_confirmation" to false,
                "message" to "未找到可安全应用的 Function 更新。",
                "function" to original,
                "updated_function" to original,
                "changes" to changes,
                "cost" to updateCost,
                "source" to "function_service"
            )
        }
        if (dryRun) {
            return linkedMapOf(
                "success" to true,
                "function_id" to functionId,
                "mode" to mode,
                "changed" to true,
                "saved" to false,
                "dry_run" to true,
                "requires_confirmation" to false,
                "changes" to changes,
                "function" to original,
                "updated_function" to updated,
                "message" to "已生成 Function 更新预览，未保存。",
                "cost" to updateCost,
                "source" to "function_service"
            )
        }

        val save = saveFunctionSpec(updated)
        val savedFunctionId = firstNonBlank(save["function_id"], functionId)
        val identityPreserved = savedFunctionId == functionId && firstNonBlank(updated["function_id"]) == functionId
        val saved = save["success"] == true && identityPreserved
        val savedUpdated = if (saved) {
            getFunctionSpec(savedFunctionId) ?: updated
        } else {
            updated
        }
        return linkedMapOf(
            "success" to saved,
            "function_id" to savedFunctionId,
            "updated_function_id" to firstNonBlank(updated["function_id"], functionId),
            "mode" to mode,
            "changed" to changed,
            "saved" to saved,
            "dry_run" to false,
            "requires_confirmation" to false,
            "changes" to changes,
            "save" to save,
            "function" to original,
            "updated_function" to savedUpdated,
            "message" to if (saved) {
                "Function 已更新并保存。"
            } else if (!identityPreserved) {
                "Function 更新必须保持同一个 function_id。"
            } else {
                save["error_message"]?.toString() ?: "Function 更新保存失败。"
            },
            "cost" to updateCost,
            "source" to "function_service"
        )
    }

    private fun buildRunLogAnalysisContext(
        functionId: String,
        functionSpec: Map<String, Any?>,
        runLogTimeline: Map<String, Any?>,
        instruction: String,
    ): Map<String, Any?> {
        val steps = FunctionSchema.materializedSteps(functionSpec)
        return linkedMapOf(
            "schema_version" to "oob.function_runlog_analysis_context.v1",
            "function_id" to functionId,
            "user_instruction" to instruction.takeIf { it.isNotBlank() },
            "function" to linkedMapOf(
                "function_id" to firstNonBlank(functionSpec["function_id"], functionId),
                "name" to firstNonBlank(functionSpec["name"]),
                "description" to firstNonBlank(functionSpec["description"]),
                "parameters" to listArg(functionSpec["parameters"]),
                "steps" to steps,
                "metadata" to mapArg(functionSpec["metadata"]),
            ),
            "runlog" to linkedMapOf(
                "run_id" to firstNonBlank(runLogTimeline["run_id"]),
                "goal" to firstNonBlank(runLogTimeline["goal"]),
                "run_success" to (runLogTimeline["run_success"] == true),
                "run_status" to firstNonBlank(runLogTimeline["run_status"]),
                "done_reason" to firstNonBlank(runLogTimeline["done_reason"]),
                "error_message" to firstNonBlank(runLogTimeline["error_message"]),
                "step_count" to runLogTimeline["step_count"],
                "duration_ms" to runLogTimeline["duration_ms"],
                "diagnostics" to mapArg(runLogTimeline["diagnostics"]).takeIf { it.isNotEmpty() },
                "cards" to listArg(runLogTimeline["cards"]),
            ).filterValues { it != null },
        ).filterValues { it != null }
    }

    private fun buildFunctionReviewPrompt(context: Map<String, Any?>): String {
        val contextJson = JSONObject(FunctionJson.sanitizeMap(context)).toString(2)
        val guidance = functionEnhancementGuidance().trim()
        val guidanceBlock = guidance.takeIf { it.isNotEmpty() }?.let {
            """
            Enhancement guidance:
            ```markdown
            $it
            ```

            """.trimIndent()
        }.orEmpty()
        return """
            Analyze this Function with the provided RunLog evidence, then return exactly one JSON object with top-level keys "analysis" and "patch".

            ${guidanceBlock}Context JSON:
            ```json
            $contextJson
            ```

            Patch contract:
            - Return {"analysis": {...}, "patch": {...}}.
            - The patch may update name, description, parameters, per-step labels, metadata, agent_reuse, and checker_rules.
            - Do not change function_id.
            - Do not invent coordinates, XML paths, resource ids, or source_context.
            - If unsure, return an empty patch.
        """.trimIndent()
    }

    private fun functionEnhancementGuidance(): String =
        runCatching {
            context.assets.open("builtin_skills/omniflow/references/function-enhancement.md")
                .bufferedReader()
                .use { it.readText() }
        }.getOrDefault("")

    private fun applyFunctionPatch(
        spec: MutableMap<String, Any?>,
        patch: Map<String, Any?>,
    ): List<Map<String, Any?>> {
        val changes = mutableListOf<Map<String, Any?>>()
        setStringFieldIfChanged(spec, "name", patch["name"], changes, "header")
        setStringFieldIfChanged(spec, "description", patch["description"], changes, "header")
        applyStepLabelPatches(spec, patch, changes)
        applyParameterPatch(spec, patch, changes)
        applyAgentReusePatch(spec, patch, changes)
        applyMetadataPatch(spec, patch, changes)
        applyTopLevelCheckerRulesPatch(spec, patch, changes)
        return changes
    }

    private fun applyRunLogEvidenceAnalysis(
        spec: MutableMap<String, Any?>,
        runId: String,
        analysis: Map<String, Any?>,
    ): List<Map<String, Any?>> {
        val metadata = FunctionJson.mutableJsonMap(mapArg(spec["metadata"]))
        val existing = FunctionJson.mutableJsonMap(mapArg(metadata["oob_function_evidence"]))
        val sourceRunIds = sourceRunIds(spec).toMutableList()
        listArg(existing["source_run_ids"]).forEach { raw ->
            raw?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let { existingRunId ->
                if (existingRunId !in sourceRunIds) sourceRunIds += existingRunId
            }
        }
        if (runId.isNotBlank() && sourceRunIds.none { it == runId }) sourceRunIds += runId
        val evidence = linkedMapOf<String, Any?>().apply {
            putAll(existing)
            put("schema_version", "oob.function_evidence.v1")
            put("source", "update_function.runlog_analysis")
            put("latest_run_id", runId.takeIf { it.isNotBlank() })
            put("source_run_ids", sourceRunIds)
            put("latest_analysis", FunctionJson.mutableJsonValue(analysis))
            put("updated_at_ms", System.currentTimeMillis())
        }.filterValues { it != null }
        val oldMetadataSourceRunIds = listArg(metadata["source_run_ids"])
        metadata["source_run_ids"] = sourceRunIds
        metadata["oob_function_evidence"] = evidence
        spec["metadata"] = metadata
        val changes = mutableListOf<Map<String, Any?>>()
        if (oldMetadataSourceRunIds != sourceRunIds) {
            changes += changeMap("metadata", "source_run_ids", oldMetadataSourceRunIds, sourceRunIds)
        }
        if (existing != evidence) {
            changes += changeMap("metadata", "oob_function_evidence", existing.takeIf { it.isNotEmpty() }, evidence)
        }
        return changes
    }

    private fun appendUpdateAudit(
        spec: MutableMap<String, Any?>,
        mode: String,
        instruction: String,
        changed: Boolean,
        dryRun: Boolean,
        changes: List<Map<String, Any?>>,
        updateCost: Map<String, Any?>,
    ) {
        val metadata = FunctionJson.mutableJsonMap(mapArg(spec["metadata"]))
        metadata["oob_function_update"] = linkedMapOf(
            "schema_version" to "oob.function_update.v1",
            "tool" to FunctionApi.FUNCTION_UPDATE,
            "mode" to mode,
            "status" to if (changed) "updated" else "unchanged",
            "changed" to changed,
            "dry_run" to dryRun,
            "instruction" to instruction.takeIf { it.isNotBlank() },
            "change_count" to changes.size,
            "updated_at_ms" to System.currentTimeMillis(),
            "cost" to updateCost.takeIf { it.isNotEmpty() },
        ).filterValues { it != null }
        if (mode == "enhance" || metadata["oob_enhancement"] != null) {
            metadata["oob_enhancement"] = linkedMapOf(
                "schema_version" to "oob.function_enhancement.v1",
                "source" to FunctionApi.FUNCTION_UPDATE,
                "status" to if (changed) "enhanced" else "unchanged",
                "changed" to changed,
                "message" to if (changed) {
                    "Agent enhancement applied through update_function."
                } else {
                    "No safe useful enhancement was applied."
                },
                "updated_at_ms" to System.currentTimeMillis(),
                "cost" to updateCost.takeIf { it.isNotEmpty() },
            )
        }
        spec["metadata"] = metadata
    }

    private fun updateCostPayload(
        request: Map<String, Any?>,
        analysis: Map<String, Any?>,
        patch: Map<String, Any?>,
        startedAtMs: Long,
        mode: String,
    ): Map<String, Any?> {
        val usage = firstPatchMap(
            request["usage"],
            request["token_usage"],
            request["tokenUsage"],
            analysis["usage"],
            analysis["token_usage"],
            analysis["tokenUsage"],
            patch["usage"],
            patch["token_usage"],
            patch["tokenUsage"],
        )
        val cost = firstCostMap(
            request["cost"],
            request["cost_estimate"],
            request["costEstimate"],
            analysis["cost"],
            analysis["cost_estimate"],
            analysis["costEstimate"],
            patch["cost"],
            patch["cost_estimate"],
            patch["costEstimate"],
        )
        val endedAtMs = System.currentTimeMillis()
        return linkedMapOf(
            "mode" to mode.takeIf { it.isNotBlank() },
            "backend" to (firstNonBlank(request["source"], analysis["source"]).takeIf { it.isNotBlank() }
                ?: "function_service"),
            "started_at_ms" to startedAtMs,
            "ended_at_ms" to endedAtMs,
            "duration_ms" to (endedAtMs - startedAtMs).coerceAtLeast(0L),
            "usage" to usage.takeIf { it.isNotEmpty() },
            "cost" to cost.takeIf { it.isNotEmpty() },
        ).filterValues { it != null }
    }

    private fun firstPatchMap(vararg values: Any?): Map<String, Any?> {
        for (value in values) {
            val mapped = mapArg(value).filterKeys { it.isNotBlank() }
            if (mapped.isNotEmpty()) return mapped
        }
        return emptyMap()
    }

    private fun firstCostMap(vararg values: Any?): Map<String, Any?> {
        for (value in values) {
            val mapped = mapArg(value).filterKeys { it.isNotBlank() }
            if (mapped.isNotEmpty()) return mapped
            val text = value?.toString()?.trim().orEmpty()
            if (text.isNotBlank()) return linkedMapOf("total" to text)
        }
        return emptyMap()
    }

    private fun applyStepLabelPatches(
        spec: MutableMap<String, Any?>,
        patch: Map<String, Any?>,
        changes: MutableList<Map<String, Any?>>,
    ) {
        val stepPatches = listArg(patch["steps"]).mapNotNull { mapArg(it).takeIf { sp -> sp.isNotEmpty() } }
        if (stepPatches.isEmpty()) return
        val execution = FunctionJson.mutableJsonMap(mapArg(spec["execution"]))
        val steps = FunctionJson.mutableJsonList(listArg(execution["steps"]))
        stepPatches.forEach { sp ->
            val index = intArg(sp["index"], sp["step_index"], sp["stepIndex"], defaultValue = -1)
            val stepIndex = if (index >= 0) index else {
                val stepId = firstNonBlank(sp["id"], sp["step_id"], sp["stepId"])
                steps.indexOfFirst { firstNonBlank(mapArg(it)["id"]) == stepId }
            }
            if (stepIndex !in steps.indices) return@forEach
            val step = FunctionJson.mutableJsonMap(mapArg(steps[stepIndex]))
            setStringFieldIfChanged(step, "title", sp["title"], changes, "step_label", stepIndex)
            setStringFieldIfChanged(step, "summary", sp["summary"], changes, "step_label", stepIndex)
            setStringFieldIfChanged(step, "description", sp["description"], changes, "step_label", stepIndex)
            val cleanup = mapArg(sp["cleanup_annotation"]).ifEmpty { mapArg(sp["cleanupAnnotation"]) }
            if (cleanup.isNotEmpty()) {
                val metadata = FunctionJson.mutableJsonMap(mapArg(step["metadata"]))
                val old = metadata["cleanup_annotation"]
                metadata["cleanup_annotation"] = cleanup
                step["metadata"] = metadata
                if (old != cleanup) changes += changeMap("step_metadata", "cleanup_annotation", old, cleanup, stepIndex)
            }
            steps[stepIndex] = step
        }
        execution["steps"] = steps
        spec["execution"] = execution
    }

    private fun applyParameterPatch(
        spec: MutableMap<String, Any?>,
        patch: Map<String, Any?>,
        changes: MutableList<Map<String, Any?>>,
    ) {
        val parameters = listArg(patch["parameters"]).mapNotNull { mapArg(it).takeIf(::isSafeParameterPatch) }
        if (parameters.isEmpty()) return
        val old = spec["parameters"]
        spec["parameters"] = parameters
        if (old != parameters) changes += changeMap("schema", "parameters", old, parameters)
    }

    private fun applyAgentReusePatch(
        spec: MutableMap<String, Any?>,
        patch: Map<String, Any?>,
        changes: MutableList<Map<String, Any?>>,
    ) {
        val agentReuse = mapArg(patch["agent_reuse"]).ifEmpty { mapArg(patch["agentReuse"]) }
        if (agentReuse.isEmpty()) return
        val old = spec["agent_reuse"]
        spec["agent_reuse"] = agentReuse
        if (old != agentReuse) changes += changeMap("metadata", "agent_reuse", old, agentReuse)
    }

    private fun applyMetadataPatch(
        spec: MutableMap<String, Any?>,
        patch: Map<String, Any?>,
        changes: MutableList<Map<String, Any?>>,
    ) {
        val metadataPatch = mapArg(patch["metadata"])
        if (metadataPatch.isEmpty()) return
        val metadata = FunctionJson.mutableJsonMap(mapArg(spec["metadata"]))
        metadataPatch.forEach { (key, value) ->
            when (key) {
                "checker_rules", "checkerRules" -> changes += applyCheckerRulesPatch(metadata, value)
                else -> {
                    val old = metadata[key]
                    metadata[key] = FunctionJson.mutableJsonValue(value)
                    if (old != metadata[key]) changes += changeMap("metadata", key, old, metadata[key])
                }
            }
        }
        spec["metadata"] = metadata
    }

    private fun applyTopLevelCheckerRulesPatch(
        spec: MutableMap<String, Any?>,
        patch: Map<String, Any?>,
        changes: MutableList<Map<String, Any?>>,
    ) {
        val rawRules = patch["checker_rules"] ?: patch["checkerRules"] ?: return
        val metadata = FunctionJson.mutableJsonMap(mapArg(spec["metadata"]))
        changes += applyCheckerRulesPatch(metadata, rawRules)
        spec["metadata"] = metadata
    }

    private fun isSafeParameterPatch(parameter: Map<String, Any?>): Boolean {
        val name = firstNonBlank(parameter["name"])
        if (!isValidParameterName(name)) return false
        val bindings = listArg(parameter["bindings"]).mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
        if (bindings.isEmpty()) return true
        val forbidden = listOf("x", "y", "coordinate", "bounds", "width", "height", "screenshot", "xml", "source_context")
        return bindings.all { binding -> forbidden.none { binding.lowercase().contains(it) } }
    }

    private fun isValidParameterName(name: String): Boolean =
        Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$").matches(name)

    private fun setStringFieldIfChanged(
        target: MutableMap<String, Any?>,
        field: String,
        rawValue: Any?,
        changes: MutableList<Map<String, Any?>>,
        part: String,
        stepIndex: Int? = null,
    ) {
        val value = rawValue?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val old = target[field]?.toString()
        if (old == value) return
        target[field] = value
        changes += changeMap(part, field, old, value, stepIndex)
    }

    private fun applyCheckerRulesPatch(
        metadata: MutableMap<String, Any?>,
        rawRules: Any?,
    ): List<Map<String, Any?>> {
        val additions = listArg(rawRules).mapNotNull { raw -> sanitizeCheckerRule(mapArg(raw)) }
        if (additions.isEmpty()) return emptyList()
        val existing = listArg(metadata["checker_rules"])
        val merged = mergeCheckerRules(existing, additions)
        if (merged == existing) return emptyList()
        metadata["checker_rules"] = merged
        return listOf(changeMap("metadata", "checker_rules", existing, merged))
    }

    private fun sanitizeCheckerRule(raw: Map<String, Any?>): Map<String, Any?>? {
        if (raw.isEmpty()) return null
        val condition = firstNonBlank(raw["condition"]).lowercase()
        val action = firstNonBlank(raw["action"]).lowercase()
        if (!ReplayCheckerRule.isSupportedPair(condition, action)) return null
        val params = mapArg(raw["params"]).filterValues { it != null }
        val id = safeCheckerRuleId(firstNonBlank(raw["id"]).ifBlank { "$condition-$action" })
        return linkedMapOf<String, Any?>(
            "id" to id,
            "condition" to condition,
            "action" to action,
            "enabled" to (raw["enabled"] as? Boolean ?: true),
            "params" to params.takeIf { it.isNotEmpty() },
        ).filterValues { it != null }
    }

    private fun mergeCheckerRules(
        existing: List<Any?>,
        additions: List<Map<String, Any?>>,
    ): List<Any?> {
        val output = existing.toMutableList()
        val signatures = existing.mapNotNull { mapArg(it).takeIf { rule -> rule.isNotEmpty() } }
            .map(::checkerRuleSignature)
            .toMutableSet()
        val usedIds = existing.mapNotNull { firstNonBlank(mapArg(it)["id"]).takeIf(String::isNotEmpty) }.toMutableSet()
        additions.forEach { rule ->
            val signature = checkerRuleSignature(rule)
            if (signature in signatures) return@forEach
            val id = uniqueCheckerRuleId(firstNonBlank(rule["id"]), usedIds)
            output += linkedMapOf<String, Any?>().apply {
                putAll(rule)
                put("id", id)
            }
            signatures += signature
            usedIds += id
        }
        return output
    }

    private fun checkerRuleSignature(rule: Map<String, Any?>): String {
        val condition = firstNonBlank(rule["condition"])
        val action = firstNonBlank(rule["action"])
        val params = mapArg(rule["params"]).toSortedMap().entries.joinToString("|") { "${it.key}=${it.value}" }
        return "$condition::$action::$params"
    }

    private fun safeCheckerRuleId(raw: String): String {
        val normalized = raw.lowercase().replace(Regex("[^a-z0-9_-]+"), "_").trim('_', '-')
        return normalized.take(48).ifBlank { "checker_rule" }
    }

    private fun uniqueCheckerRuleId(raw: String, usedIds: MutableSet<String>): String {
        val base = safeCheckerRuleId(raw)
        if (base !in usedIds) return base
        var suffix = 2
        while (true) {
            val candidate = "${base}_$suffix"
            if (candidate !in usedIds) return candidate
            suffix += 1
        }
    }

    private fun changeMap(
        part: String,
        field: String,
        old: Any?,
        new: Any?,
        stepIndex: Int? = null,
    ): Map<String, Any?> =
        linkedMapOf("part" to part, "field" to field, "old" to old, "new" to new, "step_index" to stepIndex)
            .filterValues { it != null }

    suspend fun updateFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val initial = applyFunctionUpdateRequest(request)
        if (initial["needs_agent_analysis"] != true || requestHasAnalysisOrPatch(request)) {
            return withFunctionPromptPayload(initial)
        }
        if (!shouldAutoAnalyze(request)) {
            return withFunctionPromptPayload(linkedMapOf<String, Any?>().apply {
                putAll(initial)
                put("agent_model_invoked", false)
                put("analysis_policy", "offline_only")
                put(
                    "message",
                    "update_function enhancement analysis is queued for an explicit offline/background step."
                )
            })
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
        val updated = applyFunctionUpdateRequest(nextArgs)
        return withFunctionPromptPayload(linkedMapOf<String, Any?>().apply {
            putAll(updated)
            if (!containsKey("function")) put("function", initial["function"])
            if (!containsKey("updated_function")) {
                put("updated_function", initial["updated_function"] ?: initial["function"])
            }
            put("needs_agent_analysis", false)
            put("agent_model_invoked", true)
            put("agent_analysis_initial", initial)
            put("agent_response", agentJson)
        })
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
        return withFunctionPromptPayload(convertStoredRunLog(
            runId = runId,
            register = boolArgOrDefault(request["register"], defaultValue = false),
            agentVisible = boolArgOrDefault(request["agent_visible"], defaultValue = false),
            functionIdOverride = firstNonBlank(request["function_id"], request["functionId"])
                .takeIf { it.isNotEmpty() },
            nameOverride = firstNonBlank(request["name"]).takeIf { it.isNotEmpty() },
            descriptionOverride = firstNonBlank(request["description"]).takeIf { it.isNotEmpty() }
        ))
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
        val spec = FunctionCompiler.compile(record)
            ?: return errorPayload(
                code = "RUN_LOG_NO_REPLAYABLE_STEPS",
                message = "RunLog has no replayable steps"
            ) + inlineConversionDiagnostics(record, emptyMap(), runStatusWarnings)
        val effectiveSpec = if (agentVisible) spec else markManualFunctionSpec(spec)
        val functionId = functionIdFromSpec(effectiveSpec)
        if (!register) {
            return linkedMapOf(
                "success" to true,
                "registered" to false,
                "run_id" to record.runId,
                "function_id" to functionId,
                "created_function_id" to functionId,
                "function_spec" to effectiveSpec,
                "summary" to summaryMap(effectiveSpec),
                "source" to "oob_function_management"
            ) + inlineConversionDiagnostics(record, effectiveSpec, runStatusWarnings)
        }
        runCatching { workspaceFunctionStore.mirrorRunLog(record) }
            .onFailure { error ->
                OmniLog.w(TAG, "mirror inline runlog before register failed: ${record.runId}, ${error.message}")
            }
        val registration = saveFunctionSpec(effectiveSpec)
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

    private fun withFunctionPromptPayload(payload: Map<String, Any?>): Map<String, Any?> {
        if (payload.isEmpty()) return payload
        val result = linkedMapOf<String, Any?>().apply { putAll(payload) }
        val function = enrichFunctionPrompt(result, "function")
        val updated = enrichFunctionPrompt(result, "updated_function")
        val spec = enrichFunctionPrompt(result, "function_spec")
        val source = updated.ifEmpty { spec.ifEmpty { function.ifEmpty { result } } }
        val displayPrompt = firstNonBlank(
            source["agent_prompt"],
            source["display_prompt"],
        ).ifBlank { functionAgentPrompt(source) }
        if (displayPrompt.isNotBlank()) {
            result["display_prompt"] = displayPrompt
            if (firstNonBlank(result["agent_prompt"]).isBlank()) {
                result["agent_prompt"] = displayPrompt
            }
        }
        return result
    }

    private fun enrichFunctionPrompt(
        payload: MutableMap<String, Any?>,
        key: String,
    ): Map<String, Any?> {
        val raw = mapArg(payload[key])
        if (raw.isEmpty()) return emptyMap()
        val enriched = withFunctionPromptSpec(raw)
        payload[key] = enriched
        return enriched
    }

    private fun withFunctionPromptSpec(spec: Map<String, Any?>): Map<String, Any?> {
        if (spec.isEmpty()) return spec
        val prompt = functionAgentPrompt(spec)
        if (prompt.isBlank()) return spec
        return linkedMapOf<String, Any?>().apply {
            putAll(spec)
            put("agent_prompt", prompt)
            put("display_prompt", prompt)
        }
    }

    private fun functionAgentPrompt(spec: Map<String, Any?>): String {
        val functionId = firstNonBlank(FunctionSchema.functionId(spec), spec["function_id"])
        if (functionId.isBlank()) return ""
        val name = firstNonBlank(spec["name"], functionId)
        val description = firstNonBlank(spec["description"])
        val parameters = promptParameters(spec)
        val steps = materializedSteps(spec).take(12)
        val bindings = promptBindingsByStepArg(spec)
        return buildString {
            appendLine("Saved Function: $name")
            appendLine("function_id: $functionId")
            if (description.isNotBlank()) {
                appendLine("description: $description")
            }
            if (parameters.isNotEmpty()) {
                appendLine("parameters:")
                parameters.forEach { parameter ->
                    val parameterName = firstNonBlank(parameter["name"])
                    val parameterDescription = firstNonBlank(parameter["description"], parameter["title"])
                    if (parameterName.isNotBlank()) {
                        append("- ")
                        append(parameterName)
                        if (parameter["required"] == true) append(" (required)")
                        if (parameterDescription.isNotBlank()) append(": $parameterDescription")
                        appendLine()
                    }
                }
            }
            if (steps.isNotEmpty()) {
                appendLine("steps:")
                steps.forEachIndexed { index, step ->
                    val tool = firstNonBlank(step["tool"], step["action"])
                    val title = firstNonBlank(step["title"], step["summary"], step["description"], tool)
                    val detail = promptStepDetail(index, step, bindings)
                    appendLine("${index + 1}. $title${if (detail.isBlank()) "" else " ($detail)"}")
                }
            }
            append("Call this Function only when it clearly matches the user goal; fill runtime arguments from the user request.")
        }
    }

    private fun promptParameters(spec: Map<String, Any?>): List<Map<String, Any?>> {
        val legacy = listArg(spec["parameters"]).mapNotNull { raw ->
            mapArg(raw).takeIf { it.isNotEmpty() }
        }
        if (legacy.isNotEmpty()) return legacy
        val schema = FunctionSchema.inputSchema(spec)
        val required = listArg(schema["required"]).map { it.toString() }.toSet()
        return mapArg(schema["properties"]).mapNotNull { (name, rawProperty) ->
            val property = mapArg(rawProperty)
            if (name.isBlank() || property.isEmpty()) return@mapNotNull null
            linkedMapOf<String, Any?>(
                "name" to name,
                "description" to firstNonBlank(property["description"], property["title"]),
                "required" to (name in required),
            )
        }
    }

    private fun promptBindingsByStepArg(spec: Map<String, Any?>): Map<String, String> {
        val schema = FunctionSchema.inputSchema(spec)
        val output = linkedMapOf<String, String>()
        mapArg(schema["properties"]).forEach { (name, rawProperty) ->
            val property = mapArg(rawProperty)
            val bindings = listArg(property["x_oob_bindings"]) +
                listArg(property["x-oob-bindings"]) +
                listArg(property["bindings"])
            bindings.forEach { rawBinding ->
                val match = STEP_ARG_BINDING_REGEX.matchEntire(rawBinding?.toString()?.trim().orEmpty())
                    ?: return@forEach
                output["${match.groupValues[1]}:${match.groupValues[2]}"] = name
            }
        }
        return output
    }

    private fun promptStepDetail(
        index: Int,
        step: Map<String, Any?>,
        bindings: Map<String, String>,
    ): String {
        val args = mapArg(step["args"])
        val tool = firstNonBlank(step["tool"], step["action"]).lowercase()
        val target = firstNonBlank(args["target_description"], args["targetDescription"])
        return when (tool) {
            "input_text", "input", "type" -> {
                val value = bindings["$index:text"]?.let { "\${$it}" }
                    ?: firstNonBlank(args["text"]).take(48)
                listOf(
                    "input_text",
                    value.takeIf { it.isNotBlank() }?.let { "text=$it" },
                    target.takeIf { it.isNotBlank() }?.let { "target=$it" },
                ).filterNotNull().joinToString(", ")
            }
            "click" -> listOf(
                "click",
                target.takeIf { it.isNotBlank() }?.let { "target=$it" },
            ).filterNotNull().joinToString(", ")
            else -> tool
        }
    }

    private fun functionAgentSummary(spec: Map<String, Any?>): Map<String, Any?> {
        val execution = mapArg(spec["execution"])
        val steps = materializedSteps(spec)
        val functionId = FunctionSchema.functionId(spec)
        return linkedMapOf(
            "function_id" to functionId,
            "name" to spec["name"],
            "description" to spec["description"],
            "step_count" to (execution["step_count"] ?: steps.size),
            "function_step_count" to (
                execution["function_step_count"]
                    ?: execution["omniflow_step_count"]
            ),
            "parameter_names" to FunctionSchema.parameterNames(spec),
            "step_summaries" to stepSummaries(spec),
            "source" to spec["source"],
            "constraints" to spec["constraints"],
        ).filterValues { it != null }
    }

    private fun stepSummaries(spec: Map<String, Any?>): List<Map<String, Any?>> {
        return FunctionSchema.stepSummaries(spec)
    }

    private fun materializedSteps(spec: Map<String, Any?>): List<Map<String, Any?>> {
        return FunctionSchema.materializedSteps(spec)
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
        return if (hasAnalysisShape) cn.com.omnimind.bot.function.FunctionJson.sanitizeMap(json) else emptyMap()
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
        return cn.com.omnimind.bot.function.FunctionJson.sanitizeMap(patch)
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
            is Map<*, *> -> cn.com.omnimind.bot.function.FunctionJson.sanitizeMap(value)
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
        const val TAG = "FunctionService"
        private const val AGENT_MODEL = "scene.dispatch.model"
        private const val DEFAULT_RECALL_LIMIT = 50
        private const val MAX_RECALLED_FUNCTIONS = 50
        private const val MAX_FUNCTION_ID_LENGTH = 64
        private val FUNCTION_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,$MAX_FUNCTION_ID_LENGTH}$")

        private suspend fun requestAgentAnalysis(prompt: String, responseJsonObject: Boolean): String? =
            HttpController.postLLMRequest(AGENT_MODEL, prompt, responseJsonObject).message

        fun functionIdFromSpec(spec: Map<String, Any?>): String =
            FunctionSchema.functionIdFromSpec(spec)

        fun normalizeFunctionId(value: String): String {
            val trimmed = value.trim()
            if (FUNCTION_ID_REGEX.matches(trimmed)) return trimmed
            val normalized = trimmed
                .lowercase()
                .replace(Regex("[^a-z0-9_-]+"), "_")
                .replace(Regex("_+"), "_")
                .trim('_', '-')
            val prefixed = when {
                normalized.isEmpty() -> "oob_function"
                normalized.first().isLetter() -> normalized
                else -> "oob_$normalized"
            }
            return prefixed.take(MAX_FUNCTION_ID_LENGTH).trim('_', '-').ifBlank {
                "oob_function"
            }
        }

        fun sourceRunIds(spec: Map<String, Any?>): List<String> =
            FunctionSchema.sourceRunIds(spec)

        fun isAgentVisible(spec: Map<String, Any?>): Boolean {
            val metadata = spec["metadata"] as? Map<*, *>
            val visibility = visibility(spec)
            val explicit = spec["agent_visible"] ?: metadata?.get("agent_visible")
            if (explicit is Boolean) return explicit
            val explicitText = explicit?.toString()?.trim()?.lowercase().orEmpty()
            if (explicitText in setOf("false", "0", "no", "hidden")) return false
            if (visibility in setOf("manual_function", "manual_draft", "draft", "hidden")) return false
            return true
        }

        fun visibility(spec: Map<String, Any?>): String {
            val metadata = spec["metadata"] as? Map<*, *>
            return (
                spec["visibility"]
                    ?: metadata?.get("visibility")
                    ?: if ((spec["agent_visible"] ?: metadata?.get("agent_visible")) == false) {
                        "manual_function"
                    } else {
                        "agent_reusable"
                    }
                )
                .toString()
                .trim()
                .lowercase()
                .ifBlank { "agent_reusable" }
        }

        fun functionKind(spec: Map<String, Any?>): String =
            if (isAgentVisible(spec)) "oob_reusable_function" else "oob_manual_function"

        fun assetState(spec: Map<String, Any?>): String =
            if (isAgentVisible(spec)) "native_local" else "manual_function"

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
        )

        private val STEP_ARG_BINDING_REGEX =
            Regex("""^\$\.execution\.steps\[(\d+)]\.args\.([A-Za-z0-9_]+)(?:\..*)?$""")
    }
}
