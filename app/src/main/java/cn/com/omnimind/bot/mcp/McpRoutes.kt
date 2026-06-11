package cn.com.omnimind.bot.mcp

import android.content.Context
import cn.com.omnimind.bot.agent.AgentToolNames
import cn.com.omnimind.bot.omniflow.OobFunctionSchemaExport
import cn.com.omnimind.bot.omniflow.OobFunctionToolNames
import cn.com.omnimind.bot.runlog.OobOmniFlowToolkitService
import cn.com.omnimind.bot.runlog.RunLogReplayPolicy
import cn.com.omnimind.bot.util.AssistsUtil
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.host
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CoroutineScope

/**
 * MCP 端点路由注册。
 *
 * 从 McpServerManager 拆分而来，包含 JSON-RPC、工具发现/调用、传统 VLM 任务端点。
 */
object McpRoutes {

    fun Route.registerMcpRoutes(
        context: Context,
        serverScope: CoroutineScope
    ) {
        // 健康检查（无需认证）
        get("/mcp/health") {
            call.respond(mapOf("status" to "ok"))
        }

        // 文件下载（使用文件token或Bearer token）
        get("/mcp/file/{fileId}") {
            McpServerManager.handleFileDownload(call)
        }

        authenticate("bearer-auth") {
            // 服务状态
            get("/mcp/state") {
                call.respond(McpServerManager.currentState().toMap())
            }

            // MCP JSON-RPC 端点
            post("/mcp") {
                handleJsonRpc(call, context, serverScope)
            }

            // 工具发现
            get("/mcp/list_tools") {
                call.respond(mapOf("tools" to listMcpTools(context)))
            }
            post("/mcp/list_tools") {
                call.respond(mapOf("tools" to listMcpTools(context)))
            }

            // REST 风格工具调用
            post("/mcp/call_tool") {
                val params = call.receive<Map<String, Any?>>()
                val result = executeTool(
                    context,
                    serverScope,
                    params["name"] as? String,
                    params["arguments"] as? Map<String, Any?>
                )
                call.respond(result)
            }

            // 传统 VLM 任务端点（保持兼容）
            post("/mcp/v1/task/vlm") {
                handleLegacyVlmTask(call, context, serverScope)
            }

            // 任务状态查询
            get("/mcp/v1/task/{taskId}/status") {
                val taskId = call.parameters["taskId"]
                val state = taskId?.let { McpTaskManager.getTask(it) }
                if (state == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Task not found"))
                } else {
                    call.respond(state.toResponseMap())
                }
            }

            // 任务回复
            post("/mcp/v1/task/{taskId}/reply") {
                handleLegacyTaskReply(call, context)
            }
        }
    }

    // ==================== JSON-RPC 处理 ====================

    private suspend fun handleJsonRpc(
        call: io.ktor.server.application.ApplicationCall,
        context: Context,
        serverScope: CoroutineScope
    ) {
        val request = runCatching { call.receive<Map<String, Any?>>() }.getOrNull()
        if (request == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid JSON"))
            return
        }
        val id = request["id"]
        val method = request["method"] as? String

        val response = when (method) {
            "initialize" -> mapOf(
                "jsonrpc" to "2.0",
                "id" to id,
                "result" to mapOf(
                    "protocolVersion" to "2024-11-05",
                    "capabilities" to mapOf(
                        "tools" to mapOf<String, Any>(),
                        "resources" to mapOf<String, Any>(),
                        "prompts" to mapOf<String, Any>()
                    ),
                    "serverInfo" to mapOf("name" to "小万Mcp", "version" to "1.0")
                )
            )
            "notifications/initialized" -> null
            "tools/list" -> mapOf(
                "jsonrpc" to "2.0",
                "id" to id,
                "result" to mapOf("tools" to listMcpTools(context))
            )
            "tools/call" -> {
                val params = request["params"] as? Map<String, Any?>
                val name = params?.get("name") as? String
                val args = params?.get("arguments") as? Map<String, Any?>
                val execResult = executeTool(context, serverScope, name, args)
                mapOf("jsonrpc" to "2.0", "id" to id, "result" to execResult)
            }
            "resources/list" -> mapOf(
                "jsonrpc" to "2.0",
                "id" to id,
                "result" to mapOf("resources" to listOf(McpToolDefinitions.schemaExportResource))
            )
            "resources/read" -> {
                val params = request["params"] as? Map<String, Any?>
                val uri = params?.get("uri")?.toString()?.trim().orEmpty()
                if (uri == OobFunctionSchemaExport.RESOURCE_URI) {
                    mapOf(
                        "jsonrpc" to "2.0",
                        "id" to id,
                        "result" to mapOf(
                            "contents" to listOf(
                                mapOf(
                                    "uri" to OobFunctionSchemaExport.RESOURCE_URI,
                                    "mimeType" to "application/json",
                                    "text" to McpServerManager.gson.toJson(McpToolDefinitions.schemaExportBundle),
                                )
                            )
                        )
                    )
                } else {
                    mapOf(
                        "jsonrpc" to "2.0",
                        "id" to id,
                        "error" to mapOf(
                            "code" to -32602,
                            "message" to "Unknown MCP resource: $uri"
                        )
                    )
                }
            }
            "prompts/list" -> mapOf(
                "jsonrpc" to "2.0",
                "id" to id,
                "result" to mapOf("prompts" to emptyList<Map<String, Any?>>())
            )
            "prompts/get" -> {
                mapOf(
                    "jsonrpc" to "2.0",
                    "id" to id,
                    "error" to mapOf(
                        "code" to -32602,
                        "message" to "No MCP prompts are available"
                    )
                )
            }
            else -> {
                if (method?.startsWith("$/") == true || method?.startsWith("notifications/") == true) null
                else mapOf(
                    "jsonrpc" to "2.0",
                    "id" to id,
                    "error" to mapOf("code" to -32601, "message" to "Method not found: $method")
                )
            }
        }

        if (response != null) {
            call.respond(response)
        } else {
            call.respond(HttpStatusCode.OK)
        }
    }

    // ==================== 工具执行 ====================

    private suspend fun executeTool(
        context: Context,
        serverScope: CoroutineScope,
        name: String?,
        args: Map<String, Any?>?
    ): Map<String, Any?> {
        return runCatching {
            val omniflowToolkit by lazy { OobOmniFlowToolkitService(context) }
            when (name) {
            AgentToolNames.VLM_TASK -> McpToolExecutors.executeVlmTask(context, args, serverScope)
            "task_status" -> McpToolExecutors.executeTaskStatus(args)
            "task_reply" -> McpToolExecutors.executeTaskReply(context, args)
            "task_wait_unlock" -> McpToolExecutors.executeTaskWaitUnlock(context, args, serverScope)
            "get_state" -> McpToolExecutors.executeGetState(context, args)
            "act" -> McpToolExecutors.executeAct(context, args)
            "file_transfer" -> McpToolExecutors.executeFileTransfer(args)
            "agent_run" -> McpToolExecutors.executeAgentRun(context, args)
            RunLogReplayPolicy.TOOL_CALL_TOOL -> McpToolExecutors.executeOobToolCall(context, args)
            else -> {
                if (isOmniflowMcpTool(name)) {
                    omniflowToolkit.executeTool(name, args)
                } else if (name.isNullOrBlank()) {
                    McpResponseBuilder.buildErrorText("Missing tool name")
                } else {
                    McpResponseBuilder.buildErrorText("Unknown MCP tool: $name")
                }
            }
            }
        }.getOrElse { error ->
            McpResponseBuilder.buildErrorText(error.message ?: "Tool execution failed")
        }
    }

    private fun listMcpTools(context: Context): List<Map<String, Any?>> {
        return McpToolDefinitions.fixedTools
    }

    private val OMNIFLOW_MCP_TOOL_NAMES: Set<String> =
        OobFunctionToolNames.profileTools + setOf(
            "omniflow.recall",
            "omniflow.ingest_run_log",
            "omniflow.explore_replay",
        )

    private fun isOmniflowMcpTool(name: String?): Boolean =
        !name.isNullOrBlank() && name in OMNIFLOW_MCP_TOOL_NAMES

    // ==================== 传统端点处理（保持兼容） ====================

    private suspend fun handleLegacyVlmTask(
        call: io.ktor.server.application.ApplicationCall,
        context: Context,
        serverScope: CoroutineScope
    ) {
        val remoteHost = call.request.headers["X-Forwarded-For"]
            ?.split(",")
            ?.firstOrNull()
            ?.trim()
            ?: call.request.headers["X-Real-IP"]
            ?: call.request.host()

        if (!McpNetworkUtils.isLanAddress(remoteHost)) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "LAN_ONLY"))
            return
        }

        val payload = runCatching { call.receive<VlmTaskRequest>() }
            .getOrElse {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "INVALID_BODY"))
                return
            }

        if (payload.goal.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "EMPTY_GOAL"))
            return
        }

        val args = legacyVlmRequestToToolArgs(payload)

        val result = McpToolExecutors.executeVlmTask(context, args, serverScope)
        call.respond(HttpStatusCode.OK, result)
    }

    internal fun legacyVlmRequestToToolArgs(payload: VlmTaskRequest): Map<String, Any?> =
        linkedMapOf(
            "goal" to payload.goal,
            "model" to payload.model,
            "maxSteps" to payload.maxSteps,
            "waitTimeoutMs" to payload.waitTimeoutMs,
            "packageName" to payload.packageName,
            "needSummary" to payload.needSummary,
            "skipGoHome" to payload.skipGoHome,
            "disableOmniFlowRecall" to payload.disableOmniFlowRecall,
        )

    private suspend fun handleLegacyTaskReply(
        call: io.ktor.server.application.ApplicationCall,
        context: Context,
    ) {
        val taskId = call.parameters["taskId"]
        val body = call.receive<Map<String, Any?>>()
        val reply = body["reply"] as? String ?: body["input"] as? String

        if (taskId == null || reply == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing taskId or reply"))
            return
        }

        val state = McpTaskManager.getTask(taskId)
        if (state == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Task not found"))
            return
        }

        if (state.status != TaskStatus.WAITING_INPUT) {
            call.respond(
                HttpStatusCode.Conflict,
                mapOf("error" to "Task is not waiting for input", "status" to state.status.name)
            )
            return
        }

        if (state.pendingOmniFlowFunctionCall != null) {
            val result = McpToolExecutors.executeTaskReply(
                context = context,
                args = mapOf("taskId" to taskId, "reply" to reply)
            )
            call.respond(HttpStatusCode.OK, result)
            return
        }

        val success = AssistsUtil.Core.provideUserInputToVLMTask(reply)
        if (success) {
            state.status = TaskStatus.RUNNING
            state.waitingQuestion = null
            call.respond(mapOf("success" to true, "taskId" to taskId, "status" to "RUNNING"))
        } else {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to provide input"))
        }
    }
}
