package cn.com.omnimind.bot.vlm

import android.content.Context
import cn.com.omnimind.assists.task.vlmserver.OperationResult
import cn.com.omnimind.assists.task.vlmserver.VLMFunctionRunHandler
import cn.com.omnimind.assists.task.vlmserver.VLMFunctionRunRequest
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentToolJson
import cn.com.omnimind.bot.runlog.OobOmniFlowToolkitService

class VlmFunctionRunHandlerImpl(context: Context) : VLMFunctionRunHandler {

    private val appContext = context.applicationContext
    private val TAG = "VlmFunctionRunHandlerImpl"

    override suspend fun runFunction(request: VLMFunctionRunRequest): OperationResult {
        val arguments = AgentToolJson.jsonObjectToMap(request.arguments)
        val callArgs = buildMap<String, Any?> {
            put("function_id", request.functionId)
            put("arguments", arguments)
            if (request.taskId.isNotBlank()) put("frontend_task_id", request.taskId)
            if (request.runId.isNotBlank()) put("frontend_run_id", request.runId)
        }
        val result = runCatching {
            OobOmniFlowToolkitService(appContext).runFunction(callArgs)
        }.onFailure {
            OmniLog.w(TAG, "runFunction failed for ${request.functionId}: ${it.message}")
        }.getOrElse { e ->
            return OperationResult(
                success = false,
                message = e.message ?: "function execution failed",
                data = null,
            )
        }
        val success = result["success"] == true
        val message = listOfNotNull(
            result["error_message"]?.toString(),
            result["execution_summary"]?.toString(),
        ).firstOrNull { it.isNotBlank() } ?: ""
        OmniLog.d(TAG, "runFunction ${request.functionId} success=$success")
        return OperationResult(success = success, message = message, data = null)
    }
}
