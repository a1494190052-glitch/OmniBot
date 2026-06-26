package cn.com.omnimind.bot.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.mcp.VlmTaskRequest
import cn.com.omnimind.bot.util.AssistsUtil
import cn.com.omnimind.bot.vlm.VlmToolCoordinator
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File

class DebugVlmRecallHitReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val goal = intent.decodeBase64Extra("goalBase64")
            ?: intent?.getStringExtra("goal").orEmpty()
        val packageName = intent?.getStringExtra("packageName")
            ?: intent?.getStringExtra("package_name")
        val timeoutSeconds = intent?.getIntExtra("timeoutSeconds", 180)
            ?.takeIf { it > 0 }
            ?: 180

        scope.launch {
            val result = runCatching {
                if (!AssistsUtil.Core.isInitialized()) {
                    AssistsUtil.Core.initCore(appContext)
                }
                withTimeout(timeoutSeconds * 1000L) {
                    VlmToolCoordinator.tryExecuteRecallHitOnly(
                        context = appContext,
                        request = VlmTaskRequest(
                            goal = goal,
                            packageName = packageName,
                            needSummary = false,
                            skipGoHome = true,
                        ),
                        scope = scope,
                    )
                }
            }.fold(
                onSuccess = { outcome ->
                    linkedMapOf<String, Any?>(
                        "success" to (outcome.status == cn.com.omnimind.bot.vlm.VlmToolOutcomeStatus.FINISHED),
                        "phase" to if (outcome.status == cn.com.omnimind.bot.vlm.VlmToolOutcomeStatus.FINISHED) {
                            "executed"
                        } else {
                            "error"
                        },
                        "goal" to goal,
                        "packageName" to packageName,
                        "executionRoute" to outcome.executionRoute,
                        "outcome" to outcome.toPayload(),
                    )
                },
                onFailure = { error ->
                    linkedMapOf<String, Any?>(
                        "success" to false,
                        "phase" to "exception",
                        "goal" to goal,
                        "packageName" to packageName,
                        "error_message" to error.message.orEmpty(),
                        "error_type" to error.javaClass.name,
                    )
                },
            )
            val json = gson.toJson(result)
            File(appContext.filesDir, RESULT_FILE).writeText(json)
            OmniLog.i(TAG, json)
        }
    }

    private fun Intent?.decodeBase64Extra(name: String): String? {
        val raw = this?.getStringExtra(name)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8).trim()
                .takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    companion object {
        private const val TAG = "DebugVlmRecallHitReceiver"
        private const val RESULT_FILE = "debug-vlm-recall-hit-result.json"
        private val gson = GsonBuilder().disableHtmlEscaping().create()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
