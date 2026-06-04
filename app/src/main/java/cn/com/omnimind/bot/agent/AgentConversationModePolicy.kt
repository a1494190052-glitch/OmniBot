package cn.com.omnimind.bot.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

object AgentConversationModePolicy {
    const val NORMAL_MODE = "normal"
    const val PLAN_MODE = "plan"
    const val SUBAGENT_MODE = "subagent"
    const val TODO_WRITE_TOOL = "todo_write"

    val todoStatuses = setOf("pending", "in_progress", "completed")

    private val subagentRestrictedToolNames = setOf(
        "schedule_task_create",
        "schedule_task_list",
        "schedule_task_update",
        "schedule_task_delete",
        "alarm_reminder_create",
        "alarm_reminder_list",
        "alarm_reminder_delete",
        "calendar_list",
        "calendar_event_create",
        "calendar_event_list",
        "calendar_event_update",
        "calendar_event_delete"
        // `subagent_dispatch` 的防递归改由 SubagentProfileRegistry.FORBIDDEN
        // (SubagentProfile.kt) 在每个子 Agent 的工具白名单里硬禁用。这样
        // 即便父 Agent 处于 subagent 模式,也能 spawn 真子 Agent;子 Agent
        // 自身看不到 subagent_dispatch,无法再递归。
    )

    fun isSubagentMode(conversationMode: String?): Boolean {
        return conversationMode?.trim()?.equals(SUBAGENT_MODE, ignoreCase = true) == true
    }

    fun isPlanMode(conversationMode: String?): Boolean {
        return conversationMode?.trim()?.equals(PLAN_MODE, ignoreCase = true) == true
    }

    fun isToolRestrictedInConversationMode(
        toolName: String,
        conversationMode: String?
    ): Boolean {
        if (!isSubagentMode(conversationMode)) {
            return false
        }
        return subagentRestrictedToolNames.contains(toolName.trim())
    }

    fun restrictedToolNamesForConversationMode(conversationMode: String?): Set<String> {
        return if (isSubagentMode(conversationMode)) {
            subagentRestrictedToolNames
        } else {
            emptySet()
        }
    }

    fun filterToolDefinitionsForConversationMode(
        definitions: List<JsonObject>,
        conversationMode: String?
    ): List<JsonObject> {
        val restricted = restrictedToolNamesForConversationMode(conversationMode)
        if (restricted.isEmpty()) {
            return definitions
        }
        return definitions.filterNot { definition ->
            val toolName = (definition["function"] as? JsonObject)
                ?.get("name")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                .orEmpty()
            restricted.contains(toolName)
        }
    }

    fun validateTodoWriteArguments(arguments: JsonObject) {
        val todos = arguments["todos"] as? JsonArray
            ?: throw IllegalArgumentException("Tool todo_write missing required argument: todos")
        var inProgressCount = 0
        todos.forEachIndexed { index, element ->
            val todo = element as? JsonObject
                ?: throw IllegalArgumentException("Tool todo_write todos[$index] must be object")
            val content = todo["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val activeForm = todo["activeForm"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val status = todo["status"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (content.isEmpty()) {
                throw IllegalArgumentException("Tool todo_write todos[$index].content is empty")
            }
            if (activeForm.isEmpty()) {
                throw IllegalArgumentException("Tool todo_write todos[$index].activeForm is empty")
            }
            if (!todoStatuses.contains(status)) {
                throw IllegalArgumentException(
                    "Tool todo_write todos[$index].status must be one of pending,in_progress,completed"
                )
            }
            if (status == "in_progress") {
                inProgressCount += 1
            }
        }
        if (inProgressCount > 1) {
            throw IllegalArgumentException("Tool todo_write allows at most one in_progress todo")
        }
    }
}
