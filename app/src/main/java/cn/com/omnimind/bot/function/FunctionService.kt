package cn.com.omnimind.bot.function

import android.content.Context
import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.assists.task.vlmserver.AndroidDeviceOperator
import cn.com.omnimind.assists.task.vlmserver.DeviceOperator
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.bot.omniflow.OmniFlowFunctionRecallAdapter
import cn.com.omnimind.bot.omniflow.OmniFlowPythonHostCall
import cn.com.omnimind.bot.omniflow.OmniFlowPythonRuntime
import cn.com.omnimind.bot.omniflow.omniFlowRunLogHostCall
import cn.com.omnimind.bot.runlog.firstNonBlank
import cn.com.omnimind.bot.runlog.intArg
import cn.com.omnimind.bot.runlog.mapArg

/** Android adapter for the Python-owned Function catalog and compiler. */
class FunctionService(
    private val context: Context,
    private val deviceOperator: DeviceOperator = AndroidDeviceOperator(null, context),
) {
    private val recallAdapter = OmniFlowFunctionRecallAdapter(::bridgeCall)
    private val runLogHostCall = omniFlowRunLogHostCall(context)

    suspend fun executeTool(name: String?, args: Map<String, Any?>?): Map<String, Any?> =
        when (name) {
            FunctionApi.FUNCTION_RECALL -> recall(args)
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

    suspend fun listFunctions(args: Map<String, Any?>?): Map<String, Any?> =
        catalog("list", args.orEmpty())

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

    suspend fun registerFunction(args: Map<String, Any?>?): Map<String, Any?> =
        catalog("put", args.orEmpty())

    suspend fun deleteFunction(args: Map<String, Any?>?): Map<String, Any?> =
        catalog("delete", args.orEmpty())

    suspend fun clearFunctions(args: Map<String, Any?>?): Map<String, Any?> =
        catalog("clear", args.orEmpty())

    suspend fun updateFunction(args: Map<String, Any?>?): Map<String, Any?> =
        managementCall("update_function", args.orEmpty())

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

    suspend fun convertRunLog(args: Map<String, Any?>?): Map<String, Any?> =
        managementCall("compile", args.orEmpty())

    private suspend fun functionSpec(functionId: String): Map<String, Any?>? =
        mapArg(catalog("get", mapOf("function_id" to functionId))["function"])
            .takeIf(Map<String, Any?>::isNotEmpty)

    private suspend fun catalog(
        action: String,
        payload: Map<String, Any?>,
    ): Map<String, Any?> = managementCall(
        "catalog",
        linkedMapOf<String, Any?>("action" to action).apply { putAll(payload) },
    )

    private suspend fun bridgeCall(
        operation: String,
        payload: Map<String, Any?>,
    ): Map<String, Any?> = OmniFlowPythonRuntime.call(context, operation, payload)

    private suspend fun managementCall(
        operation: String,
        payload: Map<String, Any?>,
    ): Map<String, Any?> = runCatching {
        OmniFlowPythonRuntime.call(
            context = context,
            operation = operation,
            payload = payload,
            hostCall = functionManagementHostCall(),
        )
    }.getOrElse { error ->
        errorPayload(
            "OOB_OMNIFLOW_FUNCTION_FAILED",
            error.message ?: "OmniFlow Function operation failed",
            firstNonBlank(payload["function_id"]),
        )
    }

    private fun functionManagementHostCall(): OmniFlowPythonHostCall =
        OmniFlowPythonHostCall { method, payload ->
            if (method != "complete_json") {
                return@OmniFlowPythonHostCall runLogHostCall.invoke(method, payload)
            }
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
}
