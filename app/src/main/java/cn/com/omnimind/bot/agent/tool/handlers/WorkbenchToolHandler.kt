package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.util.TaskCompletionNavigator
import cn.com.omnimind.bot.workbench.WorkbenchProjectStore
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class WorkbenchToolHandler(
    private val helper: SharedHelper
) : ToolHandler {
    override val toolNames: Set<String> = setOf(
        "workbench_project_create",
        "workbench_project_list",
        "workbench_project_get",
        "workbench_project_update",
        "workbench_api_list",
        "workbench_api_call",
        "workbench_project_export",
        "workbench_project_open",
        "workbench_project_activate",
        "workbench_project_active_get",
        "workbench_project_deactivate",
        "workbench_project_delete",
        "workbench_project_hot_update",
        "workbench_project_ingest_android",
        "workbench_project_ingest_oss",
        "workbench_project_progress_get"
    )

    private val workbenchProjectStore = WorkbenchProjectStore(helper.context)

    override suspend fun execute(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        return when (toolCall.function.name) {
            "workbench_project_create" -> call(toolCall.function.name, env, callback) {
                workbenchProjectStore.createProject(helper.jsonObjectToMap(args))
            }
            "workbench_project_list" -> call(toolCall.function.name, env, callback) {
                linkedMapOf(
                    "success" to true,
                    "count" to workbenchProjectStore.listProjects().size,
                    "projects" to workbenchProjectStore.listProjects()
                )
            }
            "workbench_project_get" -> call(toolCall.function.name, env, callback) {
                val projectId = args["projectId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                workbenchProjectStore.getProject(projectId)
            }
            "workbench_project_update" -> call(toolCall.function.name, env, callback) {
                workbenchProjectStore.updateProject(helper.jsonObjectToMap(args), caller = "ai")
            }
            "workbench_api_list" -> call(toolCall.function.name, env, callback) {
                val projectId = args["projectId"]?.jsonPrimitive?.contentOrNull
                linkedMapOf(
                    "success" to true,
                    "projectId" to projectId,
                    "count" to workbenchProjectStore.listApis(projectId).size,
                    "apis" to workbenchProjectStore.listApis(projectId)
                )
            }
            "workbench_api_call" -> call(toolCall.function.name, env, callback) {
                val projectId = args["projectId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val apiId = args["apiId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val inputs = (args["inputs"] as? JsonObject)?.let(helper::jsonObjectToMap) ?: emptyMap()
                workbenchProjectStore.callApi(projectId, apiId, inputs, caller = "ai")
            }
            "workbench_project_export" -> call(toolCall.function.name, env, callback) {
                val projectId = args["projectId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                workbenchProjectStore.exportProject(projectId)
            }
            "workbench_project_open" -> call(toolCall.function.name, env, callback) {
                val projectId = args["projectId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val route = workbenchProjectStore.routeForProject(projectId)
                TaskCompletionNavigator.navigateToMainRoute(helper.context, route, needClear = false)
                linkedMapOf(
                    "success" to true,
                    "projectId" to projectId,
                    "route" to route
                )
            }
            "workbench_project_activate" -> call(toolCall.function.name, env, callback) {
                val projectId = args["projectId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                workbenchProjectStore.activateProject(projectId)
            }
            "workbench_project_active_get" -> call(toolCall.function.name, env, callback) {
                workbenchProjectStore.getActiveProject()
            }
            "workbench_project_deactivate" -> call(toolCall.function.name, env, callback) {
                workbenchProjectStore.deactivateProject()
            }
            "workbench_project_delete" -> call(toolCall.function.name, env, callback) {
                val projectId = args["projectId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                workbenchProjectStore.deleteProject(projectId)
            }
            "workbench_project_hot_update" -> call(toolCall.function.name, env, callback) {
                val projectId = args["projectId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val prompt = args["prompt"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val frontendContext = (args["frontendContext"] as? JsonObject)?.let(helper::jsonObjectToMap)
                    ?: emptyMap()
                workbenchProjectStore.hotUpdateProject(
                    projectId = projectId,
                    prompt = prompt,
                    caller = "ai",
                    frontendContext = frontendContext
                )
            }
            "workbench_project_ingest_android" -> call(toolCall.function.name, env, callback) {
                val projectId = args["projectId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val sourcePath = args["sourcePath"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val sourceKind = args["sourceKind"]?.jsonPrimitive?.contentOrNull
                val displayName = args["displayName"]?.jsonPrimitive?.contentOrNull
                workbenchProjectStore.ingestAndroidAsset(
                    projectId = projectId,
                    sourcePath = sourcePath,
                    sourceKind = sourceKind,
                    displayName = displayName,
                    caller = "ai"
                )
            }
            "workbench_project_ingest_oss" -> call(toolCall.function.name, env, callback) {
                val projectId = args["projectId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val sourceUrl = args["sourceUrl"]?.jsonPrimitive?.contentOrNull
                val sourcePath = args["sourcePath"]?.jsonPrimitive?.contentOrNull
                val sourceKind = args["sourceKind"]?.jsonPrimitive?.contentOrNull
                val ref = args["ref"]?.jsonPrimitive?.contentOrNull
                val displayName = args["displayName"]?.jsonPrimitive?.contentOrNull
                workbenchProjectStore.ingestOssSource(
                    projectId = projectId,
                    sourceUrl = sourceUrl,
                    sourcePath = sourcePath,
                    sourceKind = sourceKind,
                    ref = ref,
                    displayName = displayName,
                    caller = "ai"
                )
            }
            "workbench_project_progress_get" -> call(toolCall.function.name, env, callback) {
                val projectId = args["projectId"]?.jsonPrimitive?.contentOrNull
                val limit = args["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 50
                workbenchProjectStore.getProjectProgress(projectId, limit)
            }
            else -> ToolExecutionResult.Error(toolCall.function.name, "Unknown workbench tool")
        }
    }

    private suspend fun call(
        toolName: String,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        block: () -> Map<String, Any?>
    ): ToolExecutionResult {
        return try {
            helper.reportToolProgress(callback, toolName, "Running Project tool")
            val payload = block()
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized(payload["summaryText"]?.toString() ?: "$toolName done"),
                previewJson = helper.encodeLocalizedPayload(payload),
                rawResultJson = helper.encodeLocalizedPayload(payload),
                success = payload["success"] == true,
                workspaceId = env.workspaceDescriptor.id
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.errorResult(toolName, e.message, "$toolName failed")
        }
    }
}
