package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.i18n.PromptLocale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentConversationModePolicyTest {

    @Test
    fun subagentModeFiltersRecursivePlanningTools() {
        val definitions = AgentToolDefinitions.staticTools(PromptLocale.ZH_CN) +
            AgentToolDefinitions.memoryTools(PromptLocale.ZH_CN) +
            AgentToolDefinitions.subagentTools(PromptLocale.ZH_CN)

        val filtered = AgentConversationModePolicy.filterToolDefinitionsForConversationMode(
            definitions = definitions,
            conversationMode = AgentConversationModePolicy.SUBAGENT_MODE
        )
        val toolNames = filtered.mapNotNull { definition ->
            ((definition["function"] as? JsonObject)
                ?.get("name")
                ?.jsonPrimitive
                ?.contentOrNull)
        }

        assertFalse(toolNames.contains("schedule_task_create"))
        assertFalse(toolNames.contains("alarm_reminder_create"))
        assertFalse(toolNames.contains("calendar_event_create"))
        // subagent_dispatch 的防递归已下沉到 SubagentProfileRegistry.FORBIDDEN
        // (每个子 Agent 的工具白名单都不含 subagent_dispatch)，外层不再过滤。
        assertTrue(toolNames.contains("subagent_dispatch"))
        assertTrue(toolNames.contains("vlm_task"))
        assertTrue(toolNames.contains("memory_search"))
    }

    @Test
    fun todoWriteValidationAcceptsKnownStatusesAndSingleInProgress() {
        AgentConversationModePolicy.validateTodoWriteArguments(
            todosArgs(
                todo("Read code", "Reading code", "completed"),
                todo("Patch UI", "Patching UI", "in_progress"),
                todo("Run tests", "Running tests", "pending")
            )
        )
    }

    @Test
    fun todoWriteToolDefinitionIncludesFullModelSchema() {
        val tool = AgentToolDefinitions.todoWriteTool(PromptLocale.EN_US)
        val function = tool["function"] as JsonObject
        val parameters = function["parameters"] as JsonObject
        val properties = parameters["properties"] as JsonObject
        val todos = properties["todos"] as JsonObject
        val item = todos["items"] as JsonObject
        val itemProperties = item["properties"] as JsonObject

        assertTrue(function["description"]?.jsonPrimitive?.contentOrNull.orEmpty().isNotBlank())
        assertTrue(parameters.isNotEmpty())
        assertTrue(itemProperties.containsKey("content"))
        assertTrue(itemProperties.containsKey("activeForm"))
        assertTrue(itemProperties.containsKey("status"))
    }

    @Test
    fun todoWriteValidationRejectsUnknownStatus() {
        val error = expectIllegalArgument {
            AgentConversationModePolicy.validateTodoWriteArguments(
                todosArgs(todo("Patch UI", "Patching UI", "blocked"))
            )
        }

        assertTrue(error.message.orEmpty().contains("status must be one of"))
    }

    @Test
    fun todoWriteValidationRejectsMultipleInProgressItems() {
        val error = expectIllegalArgument {
            AgentConversationModePolicy.validateTodoWriteArguments(
                todosArgs(
                    todo("Patch UI", "Patching UI", "in_progress"),
                    todo("Run tests", "Running tests", "in_progress")
                )
            )
        }

        assertTrue(error.message.orEmpty().contains("at most one in_progress"))
    }

    @Test
    fun todoWriteContextResultIsNotUserVisibleToolOutput() {
        val adapter = AgentEventAdapter(Json)
        val result = ToolExecutionResult.ContextResult(
            toolName = AgentConversationModePolicy.TODO_WRITE_TOOL,
            summaryText = "Todo list updated",
            previewJson = "{}",
            rawResultJson = "{}"
        )

        assertFalse(adapter.hasUserVisibleOutput(result))
    }

    private fun todosArgs(vararg todos: JsonObject): JsonObject {
        return buildJsonObject {
            put("todos", JsonArray(todos.toList()))
        }
    }

    private fun todo(
        content: String,
        activeForm: String,
        status: String
    ): JsonObject {
        return buildJsonObject {
            put("content", JsonPrimitive(content))
            put("activeForm", JsonPrimitive(activeForm))
            put("status", JsonPrimitive(status))
        }
    }

    private fun expectIllegalArgument(block: () -> Unit): IllegalArgumentException {
        return try {
            block()
            fail("Expected IllegalArgumentException")
            error("unreachable")
        } catch (error: IllegalArgumentException) {
            error
        }
    }
}
