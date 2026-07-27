package cn.com.omnimind.bot.mcp

import android.content.Context
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CoroutineScope

/**
 * MCP 端点路由注册。
 *
 * 从 McpServerManager 拆分而来，包含 JSON-RPC 与文件传输工具。
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
                call.respond(mapOf("tools" to McpToolDefinitions.allTools))
            }
            post("/mcp/list_tools") {
                call.respond(mapOf("tools" to McpToolDefinitions.allTools))
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
                    "capabilities" to mapOf("tools" to mapOf<String, Any>()),
                    "serverInfo" to mapOf("name" to "小万Mcp", "version" to "1.0")
                )
            )
            "notifications/initialized" -> null
            "tools/list" -> mapOf(
                "jsonrpc" to "2.0",
                "id" to id,
                "result" to mapOf("tools" to McpToolDefinitions.allTools)
            )
            "tools/call" -> {
                val params = request["params"] as? Map<String, Any?>
                val name = params?.get("name") as? String
                val args = params?.get("arguments") as? Map<String, Any?>
                val execResult = executeTool(context, serverScope, name, args)
                mapOf("jsonrpc" to "2.0", "id" to id, "result" to execResult)
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
        return when (name) {
            "file_transfer" -> McpToolExecutors.executeFileTransfer(args)
            else -> McpResponseBuilder.buildErrorText("Unknown tool: $name")
        }
    }
}
