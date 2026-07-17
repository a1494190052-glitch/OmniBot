package cn.com.omnimind.bot.function

import android.content.Context
import cn.com.omnimind.assists.task.vlmserver.AndroidDeviceOperator
import cn.com.omnimind.assists.task.vlmserver.DeviceOperator
import cn.com.omnimind.baselib.runlog.InternalRunLogRecord
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.omniflow.OmniFlowPythonRuntime
import cn.com.omnimind.bot.omniflow.OmniFlowFunctionRecallAdapter
import cn.com.omnimind.bot.omniflow.omniFlowRunLogHostCall
import cn.com.omnimind.bot.runlog.boolArg
import cn.com.omnimind.bot.runlog.boolArgOrDefault
import cn.com.omnimind.bot.runlog.firstNonBlank
import cn.com.omnimind.bot.runlog.intArg
import cn.com.omnimind.bot.runlog.listArg
import cn.com.omnimind.bot.runlog.mapArg

/**
 * OOB-native implementation of Function management tools.
 *
 * Kotlin owns local Function files and Android-facing management calls.
 * OmniFlow owns compilation, recall, materialization, and replay policy.
 */
class FunctionService(
    private val context: Context,
    private val deviceOperator: DeviceOperator = AndroidDeviceOperator(null, context),
    private val workspaceFunctionStore: FunctionStore = FunctionStore(
        AgentWorkspaceManager.rootDirectory(context)
    ),
) {
    private val recallAdapter = OmniFlowFunctionRecallAdapter(
        enabled = OmniFlowPythonRuntime::isReady,
        bridgeCall = { operation, payload ->
            OmniFlowPythonRuntime.call(context, operation, payload)
        },
    )

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

    suspend fun recall(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args.orEmpty()
        val currentPackage = firstNonBlank(
            request["current_package"],
            request["currentPackage"],
            runCatching { deviceOperator.currentPackageName() }.getOrNull(),
        )
        return recallAdapter.recall(
            request = request + ("current_package" to currentPackage),
            functionSpecs = listFunctionSpecs(limit = 500),
        )
    }

    fun listFunctionSpecs(limit: Int = 100, includeHidden: Boolean = false): List<Map<String, Any?>> =
        listSpecsPage(limit = limit, offset = 0, includeHidden = includeHidden).specs

    suspend fun ingestRunLog(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val register = boolArgOrDefault(request["register"], defaultValue = false)
        val agentVisible = boolArgOrDefault(request["agent_visible"], defaultValue = false)
        val runId = firstNonBlank(request["run_id"])
        val result = if (runId.isNotEmpty()) {
            convertStoredRunLog(runId = runId, register = register, agentVisible = agentVisible)
        } else {
            linkedMapOf(
                "success" to false,
                "error_code" to "RUN_LOG_EMPTY",
                "error_message" to "ingest_run_log requires run_id"
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
        val functionSpec = mapArg(request["function_spec"])
        if (functionSpec.isEmpty()) {
            return errorPayload(
                code = "FUNCTION_SPEC_REQUIRED",
                message = "function_spec is required"
            )
        }
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
                "registration_input_mode" to "function_spec",
                "function" to savedFunction,
                "function_spec" to savedFunction,
            )
        )
    }

    private fun saveFunctionSpec(functionSpec: Map<String, Any?>): Map<String, Any?> {
        val rawSpec = FunctionJson.sanitizeMap(functionSpec)
        val rawFunctionId = functionIdFromSpec(rawSpec)
        if (rawFunctionId.isEmpty()) {
            return errorPayload(
                code = "FUNCTION_ID_EMPTY",
                message = "function_id is required"
            )
        }
        val functionId = normalizeFunctionId(rawFunctionId)
        val indexSpec = linkedMapOf<String, Any?>().apply {
            putAll(rawSpec)
            put("function_id", functionId)
            putIfAbsent("name", functionId)
        }
        val spec = FunctionContract.sanitize(indexSpec)
        val alreadyExists = containsFunctionSpec(functionId)
        val agentVisible = isAgentVisible(spec)
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
        return linkedMapOf(
            "success" to deletedWorkspace,
            "function_id" to normalized,
            "deleted" to deletedWorkspace,
            "deleted_workspace" to deletedWorkspace,
            "source" to "function_service",
        )
    }

    private fun clearFunctionSpecs(): Map<String, Any?> {
        val functionIds = workspaceFunctionStore.functionIds()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val workspaceResult = workspaceFunctionStore.clear()
        return linkedMapOf(
            "success" to true,
            "deleted" to true,
            "deleted_count" to functionIds.size,
            "function_ids" to functionIds,
            "workspace" to workspaceResult,
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

    private suspend fun convertStoredRunLog(
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
        val compiled = compileRunLog(normalizedRunId, record.goal)
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

    private fun applyFunctionUpdateRequest(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args.orEmpty()
        val functionId = firstNonBlank(request["function_id"])
        if (functionId.isEmpty()) {
            return errorPayload(code = "FUNCTION_ID_EMPTY", message = "update_function requires function_id")
        }
        val original = getFunctionSpec(functionId)
            ?: return errorPayload(
                code = "OOB_FUNCTION_NOT_FOUND",
                message = "Function not found: $functionId",
                functionId = functionId,
            )
        val patch = mapArg(request["patch"])
        val edits = listArg(patch["action_edits"])
            .mapNotNull { mapArg(it).takeIf { edit -> edit.isNotEmpty() } }
        val updated = FunctionJson.mutableJsonMap(original)
        val changes = FunctionActionEdits.apply(updated, edits)
        val dryRun = boolArg(request["dry_run"])

        if (changes.isEmpty()) {
            return linkedMapOf(
                "success" to true,
                "function_id" to functionId,
                "changed" to false,
                "saved" to false,
                "dry_run" to dryRun,
                "function" to original,
                "updated_function" to original,
                "changes" to emptyList<Map<String, Any?>>(),
                "message" to "No applicable action edits.",
                "source" to "function_service",
            )
        }
        updated["function_id"] = functionId
        if (dryRun) {
            return linkedMapOf(
                "success" to true,
                "function_id" to functionId,
                "changed" to true,
                "saved" to false,
                "dry_run" to true,
                "function" to original,
                "updated_function" to updated,
                "changes" to changes,
                "message" to "Function update preview generated.",
                "source" to "function_service",
            )
        }

        val save = saveFunctionSpec(updated)
        val saved = save["success"] == true && firstNonBlank(save["function_id"]) == functionId
        val savedUpdated = if (saved) getFunctionSpec(functionId) ?: updated else updated
        return linkedMapOf(
            "success" to saved,
            "function_id" to functionId,
            "changed" to true,
            "saved" to saved,
            "dry_run" to false,
            "function" to original,
            "updated_function" to savedUpdated,
            "changes" to changes,
            "save" to save,
            "message" to if (saved) "Function updated." else "Function update failed.",
            "source" to "function_service",
        )
    }

    suspend fun updateFunction(args: Map<String, Any?>?): Map<String, Any?> {
        return withFunctionPromptPayload(applyFunctionUpdateRequest(args))
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

    suspend fun convertRunLog(args: Map<String, Any?>?): Map<String, Any?> {
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

    private suspend fun compileRunLog(runId: String, runGoal: String): Map<String, Any?>? {
        val result = OmniFlowPythonRuntime.call(
            context = context,
            operation = "compile",
            payload = mapOf("run_id" to runId),
            hostCall = omniFlowRunLogHostCall(context),
        )
        if (result["success"] != true) return null
        val function = mapArg(result["function"])
        val functionId = firstNonBlank(function["function_id"], function["id"])
        if (functionId.isBlank()) return null
        val goal = firstNonBlank(runGoal, function["description"])
        val checkerRules = listArg(function["checker_rules"])
        return linkedMapOf<String, Any?>().apply {
            putAll(function)
            put("function_id", functionId)
            put("name", firstNonBlank(function["name"], goal, functionId).take(80))
            put("description", firstNonBlank(function["description"], goal, functionId))
            put(
                "metadata",
                linkedMapOf<String, Any?>().apply {
                    putAll(mapArg(function["metadata"]))
                    put("source", "run_log_import")
                    put("source_run_ids", listArg(function["source_run_ids"]))
                    if (checkerRules.isNotEmpty()) put("checker_rules", checkerRules)
                }
            )
            put(
                "source",
                linkedMapOf(
                    "kind" to "run_log",
                    "run_id" to runId,
                    "goal" to goal,
                )
            )
        }
    }

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
            appendLine("Function: $name")
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
        private const val MAX_FUNCTION_ID_LENGTH = 64
        private val FUNCTION_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,$MAX_FUNCTION_ID_LENGTH}$")

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

        private val STEP_ARG_BINDING_REGEX =
            Regex("""^\$\.execution\.steps\[(\d+)]\.args\.([A-Za-z0-9_]+)(?:\..*)?$""")
    }
}
