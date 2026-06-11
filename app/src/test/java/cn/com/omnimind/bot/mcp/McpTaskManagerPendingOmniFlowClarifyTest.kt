package cn.com.omnimind.bot.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class McpTaskManagerPendingOmniFlowClarifyTest {
    @Test
    fun `resolves run id to pending omniflow function task`() {
        val runId = "agent-run-pending-omniflow"
        val taskId = "vlm-task-pending-omniflow"
        McpTaskManager.removeTask(runId)
        McpTaskManager.removeTask(taskId)
        try {
            McpTaskManager.createTask(
                taskId = taskId,
                goal = "小红书搜索猫猫",
                status = TaskStatus.WAITING_INPUT
            ).pendingOmniFlowFunctionCall = PendingOmniFlowFunctionCall(
                functionId = "xhs_search_keyword",
                goal = "小红书搜索猫猫",
                requiredArgumentNames = listOf("keyword"),
                allArgumentNames = listOf("keyword"),
            )

            McpTaskManager.bindPendingOmniFlowClarifyTask(runId, taskId)

            assertEquals(taskId, McpTaskManager.resolvePendingOmniFlowClarifyTask(runId))
            assertEquals(taskId, McpTaskManager.resolvePendingOmniFlowClarifyTask(taskId))
        } finally {
            McpTaskManager.removeTask(runId)
            McpTaskManager.removeTask(taskId)
        }
    }

    @Test
    fun `does not resolve stale mapping after pending function clears`() {
        val runId = "agent-run-stale-omniflow"
        val taskId = "vlm-task-stale-omniflow"
        McpTaskManager.removeTask(runId)
        McpTaskManager.removeTask(taskId)
        try {
            val state = McpTaskManager.createTask(
                taskId = taskId,
                goal = "小红书搜索猫猫",
                status = TaskStatus.WAITING_INPUT
            )
            state.pendingOmniFlowFunctionCall = PendingOmniFlowFunctionCall(
                functionId = "xhs_search_keyword",
                goal = "小红书搜索猫猫",
                requiredArgumentNames = listOf("keyword"),
                allArgumentNames = listOf("keyword"),
            )
            McpTaskManager.bindPendingOmniFlowClarifyTask(runId, taskId)

            state.pendingOmniFlowFunctionCall = null
            state.status = TaskStatus.FINISHED

            assertNull(McpTaskManager.resolvePendingOmniFlowClarifyTask(runId))
        } finally {
            McpTaskManager.removeTask(runId)
            McpTaskManager.removeTask(taskId)
        }
    }

    @Test
    fun `resolves unique pending omniflow function task without explicit id`() {
        val taskId = "vlm-task-unique-pending-omniflow"
        McpTaskManager.removeTask(taskId)
        try {
            McpTaskManager.createTask(
                taskId = taskId,
                goal = "小红书搜索猫猫",
                status = TaskStatus.WAITING_INPUT
            ).pendingOmniFlowFunctionCall = PendingOmniFlowFunctionCall(
                functionId = "xhs_search_keyword",
                goal = "小红书搜索猫猫",
                requiredArgumentNames = listOf("keyword"),
                allArgumentNames = listOf("keyword"),
            )

            assertEquals(taskId, McpTaskManager.resolveUniquePendingOmniFlowClarifyTask())
        } finally {
            McpTaskManager.removeTask(taskId)
        }
    }

    @Test
    fun `does not resolve unique pending omniflow function task when ambiguous`() {
        val taskId1 = "vlm-task-ambiguous-pending-omniflow-1"
        val taskId2 = "vlm-task-ambiguous-pending-omniflow-2"
        McpTaskManager.removeTask(taskId1)
        McpTaskManager.removeTask(taskId2)
        try {
            listOf(taskId1, taskId2).forEach { taskId ->
                McpTaskManager.createTask(
                    taskId = taskId,
                    goal = "小红书搜索猫猫",
                    status = TaskStatus.WAITING_INPUT
                ).pendingOmniFlowFunctionCall = PendingOmniFlowFunctionCall(
                    functionId = "xhs_search_keyword",
                    goal = "小红书搜索猫猫",
                    requiredArgumentNames = listOf("keyword"),
                    allArgumentNames = listOf("keyword"),
                )
            }

            assertNull(McpTaskManager.resolveUniquePendingOmniFlowClarifyTask())
        } finally {
            McpTaskManager.removeTask(taskId1)
            McpTaskManager.removeTask(taskId2)
        }
    }
}
