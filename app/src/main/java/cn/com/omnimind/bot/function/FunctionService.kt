package cn.com.omnimind.bot.function

import android.content.Context
import cn.com.omnimind.assists.task.vlmserver.AndroidDeviceOperator
import cn.com.omnimind.assists.task.vlmserver.DeviceOperator
import cn.com.omnimind.baselib.runlog.CanonicalRunLogRecord
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
    private val llmEnhancer = FunctionLlmEnhancer()
    private val recallAdapter = OmniFlowFunctionRecallAdapter(
        bridgeCall = { operation, payload ->
            OmniFlowPythonRuntime.call(context, operation, payload)
        },
    )

    suspend fun executeTool(name: String?, args: Map<String, Any?>?): Map<String, Any?> {
        return when (name) {
            FunctionApi.FUNCTION_RECALL -> recall(args)
            FunctionApi.FUNCTION_INGEST_RUN_LOG -> ingestRunLog(args)
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
            includeHidden = boolArg(request["include_hidden"]),
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
        return spec
    }

    fun deleteFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val functionId = firstNonBlank(args?.get("function_id"))
        return deleteFunctionSpec(functionId)
    }

    fun clearFunctions(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val confirmed = boolArg(request["confirm"])
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
        val function = mapArg(request["function"])
        if (function.isEmpty()) {
            return errorPayload(
                code = "FUNCTION_REQUIRED",
                message = "function is required"
            )
        }
        val registration = saveFunctionSpec(function)
        val registeredId = firstNonBlank(
            registration["function_id"],
            FunctionSchema.functionId(function),
        )
        val savedFunction = if (registration["success"] == true && registeredId.isNotBlank()) {
            getFunctionSpec(registeredId) ?: function
        } else {
            function
        }
        return registration + linkedMapOf(
            "function" to savedFunction,
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
        val functionId = rawFunctionId
        val spec = runCatching { FunctionContract.canonical(rawSpec) }
            .getOrElse { error ->
                return errorPayload(
                    code = "FUNCTION_SCHEMA_INVALID",
                    message = error.message ?: "Invalid Function",
                    functionId = functionId,
                )
            }
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
        return linkedMapOf(
            "success" to success,
            "function_id" to functionId,
            "imported" to success,
            "already_exists" to alreadyExists,
            "agent_visible" to agentVisible,
            "workspace" to workspaceResult,
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
            "functions" to page.specs,
            "include_hidden" to includeHidden,
        )
    }

    private fun getFunctionSpec(functionId: String): Map<String, Any?>? {
        val normalized = functionId.trim()
        if (normalized.isEmpty()) return null
        val workspaceSpec = workspaceFunctionStore.get(normalized)
        return workspaceSpec?.let(FunctionJson::sanitizeMap)
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
                "function" to spec,
            ).apply {
                putAll(conversionDiagnostics(record, spec, runStatusWarnings))
            }
        }

        val registration = saveFunctionSpec(spec).toMutableMap()
        return registration.apply {
            put("registered", this["success"] == true)
            put("run_id", normalizedRunId)
            put("function", spec)
            putAll(conversionDiagnostics(record, spec, runStatusWarnings))
        }
    }

    private fun noReplayableStepDiagnostics(record: CanonicalRunLogRecord): Map<String, Any?> =
        linkedMapOf(
            "step_count" to record.steps.size,
            "successful_step_count" to successfulStepCount(record),
        )

    private fun conversionDiagnostics(
        record: CanonicalRunLogRecord,
        spec: Map<String, Any?>,
        runStatusWarnings: List<RunStatusWarning> = runStatusWarnings(record),
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "step_count" to record.steps.size,
        "successful_step_count" to successfulStepCount(record),
        "compiled_step_count" to compiledStepCount(spec),
    ).apply {
        putAll(runStatusWarningDiagnostics(runStatusWarnings))
    }.filterValues { it != null }

    private fun successfulStepCount(record: CanonicalRunLogRecord): Int =
        record.steps.count { step ->
            val result = mapArg(step["result"])
            result["success"] != false
        }

    private fun applyRunLogOverrides(
        spec: Map<String, Any?>,
        functionIdOverride: String?,
        nameOverride: String?,
        descriptionOverride: String?,
        agentVisible: Boolean,
    ): Map<String, Any?> {
        val functionId = functionIdOverride?.trim()?.takeIf { it.isNotEmpty() }
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
        val request = args.orEmpty()
        val shouldEnhanceWithModel = boolArg(request["auto_analyze_with_model"]) &&
            (boolArg(request["offline_job"]) || boolArg(request["background_enhancement"])) &&
            mapArg(request["patch"]).isEmpty()
        return if (shouldEnhanceWithModel) {
            applyBackgroundLlmEnhancement(request)
        } else {
            applyFunctionUpdateRequest(request)
        }
    }

    private suspend fun applyBackgroundLlmEnhancement(
        request: Map<String, Any?>,
    ): Map<String, Any?> {
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
        val enhancement = runCatching { llmEnhancer.enhance(original) }
            .getOrElse { error ->
                OmniLog.w(TAG, "background Function enhancement failed: $functionId, ${error.message}")
                return errorPayload(
                    code = "FUNCTION_ENHANCEMENT_FAILED",
                    message = error.message ?: "Background LLM enhancement failed",
                    functionId = functionId,
                ) + linkedMapOf(
                    "changed" to false,
                    "saved" to false,
                    "function" to original,
                    "updated_function" to original,
                    "enhancement_status" to "failed",
                )
            }
        if (firstNonBlank(enhancement.updated["function_id"]) != functionId) {
            return errorPayload(
                code = "FUNCTION_ID_CHANGED",
                message = "Background enhancement must preserve function_id",
                functionId = functionId,
            )
        }
        val save = saveFunctionSpec(enhancement.updated)
        val saved = save["success"] == true && firstNonBlank(save["function_id"]) == functionId
        val savedFunction = if (saved) getFunctionSpec(functionId) ?: enhancement.updated else original
        return linkedMapOf(
            "success" to saved,
            "function_id" to functionId,
            "changed" to enhancement.changes.isNotEmpty(),
            "saved" to saved,
            "function" to original,
            "updated_function" to savedFunction,
            "changes" to enhancement.changes,
            "enhancement_status" to enhancement.status,
            "save" to save,
            "message" to if (saved) {
                "Background LLM enhancement completed."
            } else {
                "Background LLM enhancement could not be saved."
            },
            "source" to "function_service",
        )
    }

    fun listRunLogs(args: Map<String, Any?>?): Map<String, Any?> {
        val limit = intArg(args?.get("limit"), defaultValue = 50).coerceIn(1, 200)
        val offset = intArg(args?.get("offset"), defaultValue = 0).coerceAtLeast(0)
        return InternalRunLogStore.listRuns(
            context = context,
            limit = limit,
            offset = offset,
            source = firstNonBlank(args?.get("source")),
            status = firstNonBlank(args?.get("status")),
            model = firstNonBlank(args?.get("model")),
            query = firstNonBlank(args?.get("query")),
        )
    }

    fun getRunLog(args: Map<String, Any?>?): Map<String, Any?> {
        val runId = firstNonBlank(args?.get("run_id"))
        if (runId.isEmpty()) {
            return errorPayload(code = "RUN_LOG_ID_EMPTY", message = "run_id is required")
        }
        return InternalRunLogStore.timelinePayload(context, runId)
    }

    fun getRunLogState(args: Map<String, Any?>?): Map<String, Any?> {
        val stateId = firstNonBlank(args?.get("state_id"))
        if (stateId.isEmpty()) {
            return errorPayload(code = "STATE_ID_EMPTY", message = "state_id is required")
        }
        return InternalRunLogStore.statePayload(context, stateId).ifEmpty {
            errorPayload(code = "STATE_NOT_FOUND", message = "RunLog state not found")
        }
    }

    suspend fun convertRunLog(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val runId = firstNonBlank(request["run_id"])
        return convertStoredRunLog(
            runId = runId,
            register = boolArgOrDefault(request["register"], defaultValue = false),
            agentVisible = boolArgOrDefault(request["agent_visible"], defaultValue = false),
            functionIdOverride = firstNonBlank(request["function_id"]).takeIf { it.isNotEmpty() },
            nameOverride = firstNonBlank(request["name"]).takeIf { it.isNotEmpty() },
            descriptionOverride = firstNonBlank(request["description"]).takeIf { it.isNotEmpty() }
        )
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
        val functionId = firstNonBlank(function["function_id"])
        if (functionId.isBlank()) return null
        val goal = firstNonBlank(runGoal, function["description"])
        return linkedMapOf<String, Any?>().apply {
            putAll(function)
            put("function_id", functionId)
            put("name", firstNonBlank(function["name"], goal, functionId).take(80))
            put("description", firstNonBlank(function["description"], goal, functionId))
        }
    }

    private fun compiledStepCount(spec: Map<String, Any?>): Int? {
        return FunctionJson.listArg(spec["steps"]).size
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

    private fun runStatusWarnings(record: CanonicalRunLogRecord): List<RunStatusWarning> =
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
        }

    private data class RunStatusWarning(
        val code: String,
        val message: String
    )

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
        fun functionIdFromSpec(spec: Map<String, Any?>): String =
            FunctionSchema.functionIdFromSpec(spec)

        fun isAgentVisible(spec: Map<String, Any?>): Boolean {
            return spec["agent_visible"] == true
        }
    }
}
