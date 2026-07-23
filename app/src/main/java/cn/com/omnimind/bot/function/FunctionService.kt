package cn.com.omnimind.bot.function

import android.content.Context
import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.assists.task.vlmserver.AndroidDeviceOperator
import cn.com.omnimind.assists.task.vlmserver.DeviceOperator
import cn.com.omnimind.baselib.runlog.CanonicalRunLogRecord
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.bot.omniflow.OmniFlowFunctionRecallAdapter
import cn.com.omnimind.bot.omniflow.OmniFlowPythonHostCall
import cn.com.omnimind.bot.omniflow.OmniFlowPythonRuntime
import cn.com.omnimind.bot.omniflow.omniFlowRunLogHostCall
import cn.com.omnimind.bot.runlog.boolArg
import cn.com.omnimind.bot.runlog.boolArgOrDefault
import cn.com.omnimind.bot.runlog.firstNonBlank
import cn.com.omnimind.bot.runlog.intArg
import cn.com.omnimind.bot.runlog.listArg
import cn.com.omnimind.bot.runlog.mapArg

/** Android adapter for the Python-owned Function catalog and compiler. */
class FunctionService(
    private val context: Context,
    private val deviceOperator: DeviceOperator = AndroidDeviceOperator(null, context),
) {
    private val recallAdapter = OmniFlowFunctionRecallAdapter(::bridgeCall)

    suspend fun executeTool(name: String?, args: Map<String, Any?>?): Map<String, Any?> =
        when (name) {
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
            null, "" -> errorPayload("TOOL_NAME_EMPTY", "Missing Function management tool name")
            else -> errorPayload(
                "UNKNOWN_FUNCTION_MANAGEMENT_TOOL",
                "Unknown Function management tool: $name",
            )
        }

    suspend fun recall(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args.orEmpty()
        val currentPackage = firstNonBlank(
            request["current_package"],
            runCatching { deviceOperator.currentPackageName() }.getOrNull(),
        )
        return recallAdapter.recall(request + ("current_package" to currentPackage))
    }

    suspend fun listFunctions(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args.orEmpty()
        val limit = intArg(request["limit"], defaultValue = 100).coerceIn(1, 500)
        val offset = intArg(request["offset"], defaultValue = 0).coerceAtLeast(0)
        val includeHidden = boolArg(request["include_hidden"])
        val result = catalog(
            "list",
            "limit" to limit,
            "offset" to offset,
            "include_hidden" to includeHidden,
        )
        val functions = listArg(result["functions"])
        val total = intArg(result["total"], defaultValue = functions.size)
        return linkedMapOf(
            "success" to true,
            "count" to functions.size,
            "limit" to limit,
            "offset" to offset,
            "next_offset" to (offset + functions.size),
            "has_more" to (offset + functions.size < total),
            "functions" to functions,
            "include_hidden" to includeHidden,
        )
    }

    suspend fun getFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val functionId = firstNonBlank(args?.get("function_id"))
        if (functionId.isBlank()) return errorPayload("FUNCTION_ID_EMPTY", "function_id is required")
        return functionSpec(functionId)
            ?: errorPayload(
                "OOB_FUNCTION_NOT_FOUND",
                "Function not found: $functionId",
                functionId,
            )
    }

    suspend fun registerFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val function = mapArg(args?.get("function"))
        if (function.isEmpty()) return errorPayload("FUNCTION_REQUIRED", "function is required")
        val functionId = firstNonBlank(function["function_id"])
        if (functionId.isBlank()) return errorPayload("FUNCTION_ID_EMPTY", "function_id is required")
        val alreadyExists = functionSpec(functionId) != null
        return runCatching {
            val result = catalog("put", "function" to function)
            val saved = mapArg(result["function"])
            linkedMapOf(
                "success" to true,
                "function_id" to firstNonBlank(result["function_id"], functionId),
                "imported" to true,
                "already_exists" to alreadyExists,
                "agent_visible" to (saved["agent_visible"] == true),
                "function" to saved,
                "runtime_source" to "omniflow_python",
            )
        }.getOrElse { error ->
            errorPayload(
                "FUNCTION_SCHEMA_INVALID",
                error.message ?: "Invalid Function",
                functionId,
            )
        }
    }

    suspend fun deleteFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val functionId = firstNonBlank(args?.get("function_id"))
        if (functionId.isBlank()) return errorPayload("FUNCTION_ID_EMPTY", "function_id is required")
        val deleted = catalog("delete", "function_id" to functionId)["deleted"] == true
        return linkedMapOf(
            "success" to deleted,
            "function_id" to functionId,
            "deleted" to deleted,
            "runtime_source" to "omniflow_python",
        )
    }

    suspend fun clearFunctions(args: Map<String, Any?>?): Map<String, Any?> {
        if (!boolArg(args?.get("confirm"))) {
            return errorPayload(
                "OOB_FUNCTION_CLEAR_CONFIRMATION_REQUIRED",
                "Set confirm=true to clear all registered Functions",
            )
        }
        val result = catalog("clear")
        return linkedMapOf(
            "success" to true,
            "deleted" to true,
            "deleted_count" to intArg(result["deleted_count"], defaultValue = 0),
            "runtime_source" to "omniflow_python",
        )
    }

    suspend fun updateFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args.orEmpty()
        val functionId = firstNonBlank(request["function_id"])
        if (functionId.isBlank()) {
            return errorPayload("FUNCTION_ID_EMPTY", "update_function requires function_id")
        }
        val enhance = boolArg(request["auto_analyze_with_model"]) &&
            (boolArg(request["offline_job"]) || boolArg(request["background_enhancement"])) &&
            mapArg(request["patch"]).isEmpty()
        return if (enhance) enhanceFunction(functionId, firstNonBlank(request["run_id"]))
        else editFunction(functionId, request)
    }

    suspend fun ingestRunLog(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args.orEmpty()
        val result = convertStoredRunLog(
            runId = firstNonBlank(request["run_id"]),
            register = boolArgOrDefault(request["register"], false),
            agentVisible = boolArgOrDefault(request["agent_visible"], false),
        )
        val success = result["success"] == true
        return linkedMapOf(
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
            "source" to "oob_function_management",
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
        if (runId.isBlank()) return errorPayload("RUN_LOG_ID_EMPTY", "run_id is required")
        return InternalRunLogStore.timelinePayload(context, runId)
    }

    fun getRunLogState(args: Map<String, Any?>?): Map<String, Any?> {
        val stateId = firstNonBlank(args?.get("state_id"))
        if (stateId.isBlank()) return errorPayload("STATE_ID_EMPTY", "state_id is required")
        return InternalRunLogStore.statePayload(context, stateId).ifEmpty {
            errorPayload("STATE_NOT_FOUND", "RunLog state not found")
        }
    }

    suspend fun convertRunLog(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args.orEmpty()
        return convertStoredRunLog(
            runId = firstNonBlank(request["run_id"]),
            register = boolArgOrDefault(request["register"], false),
            agentVisible = boolArgOrDefault(request["agent_visible"], false),
            functionIdOverride = firstNonBlank(request["function_id"]).takeIf(String::isNotEmpty),
            nameOverride = firstNonBlank(request["name"]).takeIf(String::isNotEmpty),
            descriptionOverride = firstNonBlank(request["description"]).takeIf(String::isNotEmpty),
        )
    }

    private suspend fun editFunction(
        functionId: String,
        request: Map<String, Any?>,
    ): Map<String, Any?> {
        val edits = listArg(mapArg(request["patch"])["action_edits"])
            .mapNotNull { mapArg(it).takeIf(Map<String, Any?>::isNotEmpty) }
        val result = catalog(
            "edit",
            "function_id" to functionId,
            "action_edits" to edits,
            "dry_run" to boolArg(request["dry_run"]),
        )
        if (result["found"] != true) {
            return errorPayload(
                "OOB_FUNCTION_NOT_FOUND",
                "Function not found: $functionId",
                functionId,
            )
        }
        val changed = result["changed"] == true
        val saved = result["saved"] == true
        return linkedMapOf<String, Any?>().apply {
            putAll(result)
            put("success", true)
            put("message", when {
                !changed -> "No applicable action edits."
                saved -> "Function updated."
                else -> "Function update preview generated."
            })
            put("source", "omniflow_python")
        }
    }

    private suspend fun enhanceFunction(functionId: String, runId: String): Map<String, Any?> {
        val runLog = runId.takeIf(String::isNotEmpty)
            ?.let { InternalRunLogStore.timelinePayload(context, it) }
            .orEmpty()
        return runCatching {
            val result = OmniFlowPythonRuntime.call(
                context = context,
                operation = "enhance",
                payload = linkedMapOf(
                    "function_id" to functionId,
                    "run_log" to runLog,
                ),
                hostCall = llmHostCall(),
            )
            if (result["found"] != true) {
                return errorPayload(
                    "OOB_FUNCTION_NOT_FOUND",
                    "Function not found: $functionId",
                    functionId,
                )
            }
            linkedMapOf<String, Any?>().apply {
                putAll(result)
                put("success", result["saved"] == true)
                put("message", "Background LLM enhancement completed.")
                put("source", "omniflow_python")
            }
        }.getOrElse { error ->
            errorPayload(
                "FUNCTION_ENHANCEMENT_FAILED",
                error.message ?: "Background LLM enhancement failed",
                functionId,
            ) + mapOf("changed" to false, "saved" to false, "enhancement_status" to "failed")
        }
    }

    private suspend fun convertStoredRunLog(
        runId: String,
        register: Boolean,
        agentVisible: Boolean,
        functionIdOverride: String? = null,
        nameOverride: String? = null,
        descriptionOverride: String? = null,
    ): Map<String, Any?> {
        if (runId.isBlank()) return errorPayload("RUN_LOG_ID_EMPTY", "run_id is required")
        val record = InternalRunLogStore.getRun(context, runId)
            ?: return errorPayload("RUN_LOG_NOT_FOUND", "RunLog not found: $runId", runId)
        val warnings = runStatusWarnings(record)
        val compiled = compileRunLog(runId)
            ?: return errorPayload(
                "RUN_LOG_NO_REPLAYABLE_STEPS",
                "RunLog has no replayable steps",
                runId,
            ) + conversionDiagnostics(record, emptyMap(), warnings)
        val function = linkedMapOf<String, Any?>().apply {
            putAll(compiled)
            functionIdOverride?.let { put("function_id", it) }
            nameOverride?.let { put("name", it) }
            descriptionOverride?.let { put("description", it) }
            put("agent_visible", agentVisible)
        }
        val functionId = firstNonBlank(function["function_id"])
        val base = linkedMapOf<String, Any?>(
            "success" to true,
            "registered" to false,
            "run_id" to runId,
            "function_id" to functionId,
            "function" to function,
        ).apply { putAll(conversionDiagnostics(record, function, warnings)) }
        if (!register) return base
        val registration = registerFunction(mapOf("function" to function))
        return linkedMapOf<String, Any?>().apply {
            putAll(base)
            putAll(registration)
            put("registered", registration["success"] == true)
            put("run_id", runId)
            put("function", mapArg(registration["function"]).ifEmpty { function })
        }
    }

    private suspend fun compileRunLog(runId: String): Map<String, Any?>? {
        val result = OmniFlowPythonRuntime.call(
            context = context,
            operation = "compile",
            payload = mapOf("run_id" to runId),
            hostCall = omniFlowRunLogHostCall(context),
        )
        return mapArg(result["function"]).takeIf { result["success"] == true && it.isNotEmpty() }
    }

    private fun conversionDiagnostics(
        record: CanonicalRunLogRecord,
        function: Map<String, Any?>,
        warnings: List<RunStatusWarning>,
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "step_count" to record.steps.size,
        "successful_step_count" to record.steps.count {
            mapArg(it["result"])["success"] != false
        },
        "compiled_step_count" to listArg(function["steps"]).size.takeIf { function.isNotEmpty() },
        "conversion_warning_code" to warnings.firstOrNull()?.code,
        "conversion_warning_message" to warnings.firstOrNull()?.message,
        "conversion_warning_codes" to warnings.map(RunStatusWarning::code).takeIf(List<String>::isNotEmpty),
        "conversion_warnings" to warnings.map { mapOf("code" to it.code, "message" to it.message) }
            .takeIf(List<Map<String, String>>::isNotEmpty),
    ).filterValues { it != null }

    private fun runStatusWarnings(record: CanonicalRunLogRecord): List<RunStatusWarning> = buildList {
        if (record.finishedAtMs == null) {
            add(RunStatusWarning("RUN_LOG_NOT_FINISHED", "RunLog is not finished yet: ${record.runId}"))
        }
        if (record.success != true) {
            add(RunStatusWarning("RUN_LOG_NOT_SUCCESSFUL", "RunLog did not finish successfully: ${record.runId}"))
        }
    }

    private suspend fun functionSpec(functionId: String): Map<String, Any?>? =
        mapArg(catalog("get", "function_id" to functionId)["function"])
            .takeIf(Map<String, Any?>::isNotEmpty)

    private suspend fun catalog(
        action: String,
        vararg values: Pair<String, Any?>,
    ): Map<String, Any?> = bridgeCall(
        "catalog",
        linkedMapOf<String, Any?>("action" to action).apply { putAll(values) },
    )

    private suspend fun bridgeCall(
        operation: String,
        payload: Map<String, Any?>,
    ): Map<String, Any?> = OmniFlowPythonRuntime.call(context, operation, payload)

    private fun llmHostCall(): OmniFlowPythonHostCall = OmniFlowPythonHostCall { method, payload ->
        require(method == "complete_json") { "unsupported_host_call:$method" }
        val response = HttpController.postLLMRequest(
            model = firstNonBlank(payload["model"], "scene.dispatch.model"),
            text = firstNonBlank(payload["prompt"]),
            responseJsonObject = true,
            maxTokens = intArg(payload["max_tokens"], defaultValue = 1800),
            temperature = (payload["temperature"] as? Number)?.toDouble() ?: 0.1,
            timeoutSeconds = 120L,
        )
        mapOf("content" to response.message)
    }

    private fun errorPayload(
        code: String,
        message: String,
        functionId: String = "",
    ): Map<String, Any?> = linkedMapOf(
        "success" to false,
        "error_code" to code,
        "error_message" to message,
        "function_id" to functionId,
    )

    private data class RunStatusWarning(val code: String, val message: String)
}
