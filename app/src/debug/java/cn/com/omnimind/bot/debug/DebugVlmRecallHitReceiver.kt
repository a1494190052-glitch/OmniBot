package cn.com.omnimind.bot.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.mcp.VlmTaskRequest
import cn.com.omnimind.bot.util.AssistsUtil
import cn.com.omnimind.bot.vlm.VlmToolCoordinator
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class DebugVlmRecallHitReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val requestId = intent?.getStringExtra("requestId")?.trim().orEmpty()
        File(appContext.filesDir, RESULT_FILE).delete()
        val goal = intent.decodeBase64Extra("goalBase64")
            ?: intent?.getStringExtra("goal")?.takeIf { it.isNotBlank() }
            ?: "打开 Settings"
        val packageName = intent?.getStringExtra("packageName")
            ?: intent?.getStringExtra("package_name")
        val waitTimeoutMs = intent.readWaitTimeoutMs()

        scope.launch {
            val result = runCatching {
                runRecallHitOnly(
                    context = appContext,
                    goal = goal,
                    packageName = packageName?.trim()?.takeIf { it.isNotEmpty() },
                    waitTimeoutMs = waitTimeoutMs,
                ).withRequestId(requestId)
            }.getOrElse { error ->
                linkedMapOf<String, Any?>(
                    "success" to false,
                    "request_id" to requestId,
                    "phase" to "exception",
                    "goal" to goal,
                    "packageName" to packageName,
                    "error_message" to error.message.orEmpty(),
                    "error_type" to error.javaClass.name,
                )
            }
            val json = gson.toJson(result)
            File(appContext.filesDir, RESULT_FILE).writeText(json)
            OmniLog.i(TAG, json)
        }
    }

    private suspend fun runRecallHitOnly(
        context: Context,
        goal: String,
        packageName: String?,
        waitTimeoutMs: Long?,
    ): Map<String, Any?> {
        if (!AssistsUtil.Core.isInitialized()) {
            AssistsUtil.Core.initCore(context)
        }
        waitForAccessibility()
        val taskId = "debug-vlm-recall-hit-${System.currentTimeMillis()}"
        val progressEvents = mutableListOf<Map<String, Any?>>()
        val outcome = VlmToolCoordinator.tryExecuteRecallHitOnly(
            context = context,
            request = VlmTaskRequest(
                goal = goal,
                packageName = packageName,
                waitTimeoutMs = waitTimeoutMs,
                needSummary = false,
                skipGoHome = true,
                disableOmniFlowRecall = false,
                allowOmniFlowFunctionAutoExecute = true,
            ),
            scope = scope,
            taskIdOverride = taskId,
            progressReporter = { progress, extras ->
                progressEvents += linkedMapOf<String, Any?>(
                    "progress" to progress,
                    "extras" to extras,
                )
            },
        )
        val outcomePayload = outcome?.toPayload()
        return linkedMapOf(
            "success" to (outcome != null),
            "phase" to if (outcome == null) "no_direct_hit" else "executed",
            "goal" to goal,
            "packageName" to packageName,
            "task_id" to taskId,
            "wait_timeout_ms" to waitTimeoutMs,
            "outcome" to outcomePayload,
            "executionRoute" to (outcomePayload?.get("executionRoute") ?: ""),
            "progress_events" to progressEvents,
            "progress_event_count" to progressEvents.size,
        )
    }

    private fun Map<String, Any?>.withRequestId(requestId: String): Map<String, Any?> {
        if (requestId.isBlank()) return this
        return linkedMapOf<String, Any?>("request_id" to requestId).apply {
            putAll(this@withRequestId)
        }
    }

    private suspend fun waitForAccessibility() {
        repeat(50) {
            if (AssistsService.instance != null) return
            delay(200L)
        }
        error("OOB accessibility service is not bound")
    }

    private fun Intent?.decodeBase64Extra(name: String): String? {
        val raw = this?.getStringExtra(name)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8).trim()
                .takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun Intent?.readWaitTimeoutMs(): Long? {
        val intent = this ?: return null
        if (intent.hasExtra("timeoutMs")) {
            return intent.getLongExtra("timeoutMs", 0L).takeIf { it > 0L }
        }
        if (intent.hasExtra("waitTimeoutMs")) {
            return intent.getLongExtra("waitTimeoutMs", 0L).takeIf { it > 0L }
        }
        if (intent.hasExtra("timeoutSeconds")) {
            val seconds = intent.getIntExtra("timeoutSeconds", 0)
            return seconds.takeIf { it > 0 }?.toLong()?.times(1000L)
        }
        return null
    }

    companion object {
        private const val TAG = "DebugVlmRecallHitReceiver"
        private const val RESULT_FILE = "debug-vlm-recall-hit-result.json"
        private val gson = GsonBuilder().disableHtmlEscaping().create()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
