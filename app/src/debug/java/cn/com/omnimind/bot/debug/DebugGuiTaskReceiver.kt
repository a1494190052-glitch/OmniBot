package cn.com.omnimind.bot.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import cn.com.omnimind.baselib.runlog.CanonicalRunLogRecord
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.manager.AssistsCoreManager
import cn.com.omnimind.bot.util.AssistsUtil
import cn.com.omnimind.bot.webchat.AgentRunService
import cn.com.omnimind.bot.webchat.ConversationDomainService
import com.google.gson.GsonBuilder
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DebugGuiTaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val requestId = intent?.getStringExtra("requestId")?.trim().orEmpty()
        val goal = intent.decodeBase64Extra("goalBase64")
            ?: "Open Android Settings, tap Network & internet, then finish."
        val packageName = intent?.getStringExtra("packageName")?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: "com.android.settings"
        val waitMs = intent?.getLongExtra("waitMs", DEFAULT_WAIT_MS)
            ?.coerceIn(1_000L, MAX_WAIT_MS)
            ?: DEFAULT_WAIT_MS
        val profileId = intent?.getStringExtra("profileId")?.trim().orEmpty()
        val modelId = intent?.getStringExtra("modelId")?.trim().orEmpty()
        val disableFunctionRecall = intent?.getBooleanExtra("disableFunctionRecall", false) == true
        val resultFile = File(appContext.filesDir, RESULT_FILE)
        resultFile.delete()

        scope.launch {
            val result = runCatching {
                runValidation(
                    context = appContext,
                    requestId = requestId,
                    goal = goal,
                    packageName = packageName,
                    waitMs = waitMs,
                    profileId = profileId,
                    modelId = modelId,
                    disableFunctionRecall = disableFunctionRecall,
                )
            }.getOrElse { error ->
                linkedMapOf<String, Any?>(
                    "success" to false,
                    "request_id" to requestId,
                    "phase" to "exception",
                    "error_message" to error.message.orEmpty(),
                    "error_type" to error.javaClass.name,
                )
            }
            val json = gson.toJson(result)
            resultFile.writeText(json)
            OmniLog.i(TAG, json)
        }
    }

    private suspend fun runValidation(
        context: Context,
        requestId: String,
        goal: String,
        packageName: String,
        waitMs: Long,
        profileId: String,
        modelId: String,
        disableFunctionRecall: Boolean,
    ): Map<String, Any?> {
        if (!AssistsUtil.Core.isInitialized()) {
            AssistsUtil.Core.initCore(context)
        }
        val existingRunIds = InternalRunLogStore.listRunRecords(context, limit = 200)
            .mapTo(mutableSetOf(), CanonicalRunLogRecord::runId)
        val conversationService = ConversationDomainService(context)
        val conversation = conversationService.createConversation(
            title = "Debug GUI Task Validation",
            mode = "normal",
        )
        val conversationId = (conversation["id"] as? Number)?.toLong()
            ?: conversation["id"]?.toString()?.toLongOrNull()
            ?: error("conversation id is invalid")
        val taskId = "debug-gui-task-${System.currentTimeMillis()}"
        val request = linkedMapOf<String, Any?>(
            "taskId" to taskId,
            "userMessage" to buildAgentPrompt(goal, packageName),
            "title" to "Debug GUI Task Validation",
            "conversationMode" to "normal",
            "allowedTools" to listOf("vlm_task"),
            "runtimeOptions" to if (disableFunctionRecall) {
                mapOf("vlm_task" to mapOf("disable_function_recall" to true))
            } else {
                emptyMap<String, Any?>()
            },
        )
        if (profileId.isNotEmpty() && modelId.isNotEmpty()) {
            request["modelOverride"] = mapOf(
                "providerProfileId" to profileId,
                "modelId" to modelId,
            )
        }

        val accepted = AgentRunService(context).startConversationRun(conversationId, request)
        waitForAgentIdle(context, waitMs)
        delay(SETTLE_MS)

        val messages = conversationService.listConversationMessages(
            conversationId = conversationId,
            conversationMode = "normal",
        )
        val newGuiRuns = InternalRunLogStore.listRunRecords(context, limit = 200)
            .filter { run ->
                run.runId !in existingRunIds &&
                    run.source == "vlm" &&
                    run.toolName == "vlm_task"
            }
            .sortedBy(CanonicalRunLogRecord::startedAtMs)
        val agentCalledGuiTool = newGuiRuns.isNotEmpty()
        val successfulRun = newGuiRuns.lastOrNull { run -> run.success && run.steps.isNotEmpty() }
        val canonicalStepsValid = newGuiRuns
            .flatMap(CanonicalRunLogRecord::steps)
            .all(::isCanonicalStep)
        val success = agentCalledGuiTool && successfulRun != null && canonicalStepsValid

        return linkedMapOf(
            "success" to success,
            "request_id" to requestId,
            "source" to "debug_gui_task",
            "agent_path" to "AgentRunService -> AgentOrchestrator -> vlm_task -> OmniFlow Python -> Android Host",
            "conversation_id" to conversationId,
            "task_id" to taskId,
            "accepted" to accepted,
            "allowed_tools" to listOf("vlm_task"),
            "function_recall_disabled" to disableFunctionRecall,
            "goal" to goal,
            "package_name" to packageName,
            "agent_called_gui_tool" to agentCalledGuiTool,
            "gui_run_count" to newGuiRuns.size,
            "canonical_steps_valid" to canonicalStepsValid,
            "gui_runs" to newGuiRuns.map(::runEvidence),
            "assistant_tail" to messages.asReversed()
                .firstOrNull { it["role"]?.toString() == "assistant" }
                ?.get("text")
                ?.toString()
                ?.take(1_000),
            "message_count" to messages.size,
        )
    }

    private suspend fun waitForAgentIdle(context: Context, waitMs: Long) {
        val manager = AssistsCoreManager.sharedInstanceOrCreate(context)
        val startedAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startedAt < waitMs) {
            if (!manager.hasActiveAgentRuns()) return
            delay(500L)
        }
        error("GUI task validation did not finish within ${waitMs}ms")
    }

    private fun runEvidence(run: CanonicalRunLogRecord): Map<String, Any?> {
        val functionIds = run.steps.mapNotNull { step ->
            (step["metadata"] as? Map<*, *>)?.get("function_id")
                ?.toString()
                ?.trim()
                ?.takeIf(String::isNotEmpty)
        }.distinct()
        return linkedMapOf(
            "schema_version" to run.schemaVersion,
            "run_id" to run.runId,
            "status" to run.status,
            "success" to run.success,
            "error" to run.error,
            "step_count" to run.steps.size,
            "tools" to run.steps.mapNotNull { step ->
                (step["action"] as? Map<*, *>)?.get("tool")?.toString()
            },
            "function_ids" to functionIds,
            "canonical_steps_valid" to run.steps.all(::isCanonicalStep),
        )
    }

    private fun isCanonicalStep(step: Map<String, Any?>): Boolean {
        val required = setOf(
            "step_index",
            "before_state_id",
            "action",
            "result",
            "after_state_id",
        )
        return required.all(step::containsKey) &&
            listOf("step_id", "status", "thinking", "summary", "diagnostics")
                .none(step::containsKey)
    }

    private fun Intent?.decodeBase64Extra(name: String): String? {
        val raw = this?.getStringExtra(name)?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return runCatching {
            String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8).trim()
                .takeIf(String::isNotEmpty)
        }.getOrNull()
    }

    private fun buildAgentPrompt(
        goal: String,
        packageName: String,
    ): String =
        """
        You must use the vlm_task tool to operate the Android screen. Do not answer from memory and do not use any other tool.
        Call vlm_task exactly once with the single argument:
        - goal: $goal Target Android package: $packageName
        Wait for the GUI task result, then briefly report whether it succeeded.
        """.trimIndent()

    companion object {
        private const val TAG = "DebugGuiTask"
        private const val RESULT_FILE = "debug-gui-task-result.json"
        private const val DEFAULT_WAIT_MS = 240_000L
        private const val MAX_WAIT_MS = 600_000L
        private const val SETTLE_MS = 1_000L
        private val gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
