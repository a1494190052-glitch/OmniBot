package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentConversationModePolicy
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.AgentTodoItem
import cn.com.omnimind.bot.agent.ToolExecutionResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class TodoToolHandler(
    private val helper: SharedHelper
) : ToolHandler {
    override val toolNames: Set<String> = setOf(AgentConversationModePolicy.TODO_WRITE_TOOL)

    override suspend fun execute(
        toolCall: AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        helper.ensureRunActive()
        val toolName = AgentConversationModePolicy.TODO_WRITE_TOOL
        if (!AgentConversationModePolicy.isPlanMode(env.conversationMode)) {
            return ToolExecutionResult.Error(
                toolName,
                helper.localized("todo_write 只能在 Plan 模式中使用")
            )
        }
        val todos = parseTodos(args)
        val state = env.todoState
        val oldTodos = state?.replace(todos) ?: emptyList()
        callback.onTodoListSnapshot(todos = todos, oldTodos = oldTodos)
        val payload = mapOf(
            "toolName" to toolName,
            "status" to "updated",
            "todos" to todos.map { it.toPayload() },
            "oldTodos" to oldTodos.map { it.toPayload() },
            "todoCount" to todos.size
        )
        val payloadJson = helper.encodeLocalizedPayload(payload)
        return ToolExecutionResult.ContextResult(
            toolName = toolName,
            summaryText = helper.localized("Todo list 已更新"),
            previewJson = payloadJson,
            rawResultJson = payloadJson,
            success = true
        )
    }

    private fun parseTodos(args: JsonObject): List<AgentTodoItem> {
        val todos = args["todos"] as? JsonArray ?: emptyList()
        return todos.mapNotNull { item ->
            val todo = item as? JsonObject ?: return@mapNotNull null
            val content = todo["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val activeForm = todo["activeForm"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val status = todo["status"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (content.isEmpty() || activeForm.isEmpty()) {
                null
            } else {
                AgentTodoItem(
                    content = content,
                    activeForm = activeForm,
                    status = status
                )
            }
        }
    }
}
