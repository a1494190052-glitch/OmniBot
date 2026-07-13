package cn.com.omnimind.bot.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.terminal.EmbeddedTerminalRuntime
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class DebugOmniFlowPythonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val pending = goAsync()
        scope.launch {
            val startedAtMs = System.currentTimeMillis()
            val result = runCatching {
                val command = intent.decodeBase64Extra("commandBase64")
                    ?: intent?.getStringExtra("command")?.takeIf(String::isNotBlank)
                    ?: error("command is required")
                val workingDirectory = intent?.getStringExtra("workingDirectory")
                    ?.takeIf(String::isNotBlank)
                    ?: "/workspace"
                val timeoutSeconds = intent?.getIntExtra("timeoutSeconds", DEFAULT_TIMEOUT_SECONDS)
                    ?.coerceIn(1, MAX_TIMEOUT_SECONDS)
                    ?: DEFAULT_TIMEOUT_SECONDS
                val commandResult = EmbeddedTerminalRuntime.executeCommand(
                    context = appContext,
                    command = command,
                    workingDirectory = workingDirectory,
                    timeoutSeconds = timeoutSeconds,
                )
                linkedMapOf<String, Any?>(
                    "success" to commandResult.success,
                    "timed_out" to commandResult.timedOut,
                    "exit_code" to commandResult.exitCode,
                    "output" to commandResult.output,
                    "error_message" to commandResult.errorMessage,
                    "session_id" to commandResult.sessionId,
                    "working_directory" to workingDirectory,
                    "duration_ms" to (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L),
                    "source" to "debug_omniflow_python_receiver",
                )
            }.getOrElse { error ->
                linkedMapOf<String, Any?>(
                    "success" to false,
                    "error_message" to error.message.orEmpty(),
                    "error_type" to error.javaClass.name,
                    "duration_ms" to (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L),
                    "source" to "debug_omniflow_python_receiver",
                )
            }
            val json = gson.toJson(result)
            File(appContext.filesDir, RESULT_FILE).writeText(json)
            OmniLog.i(TAG, json)
            pending.finish()
        }
    }

    private fun Intent?.decodeBase64Extra(name: String): String? {
        val encoded = this?.getStringExtra(name)?.takeIf(String::isNotBlank) ?: return null
        return runCatching {
            String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
                .takeIf(String::isNotBlank)
        }.getOrNull()
    }

    companion object {
        const val RESULT_FILE = "debug-omniflow-python-result.json"
        private const val TAG = "DebugOmniFlowPythonReceiver"
        private const val DEFAULT_TIMEOUT_SECONDS = 120
        private const val MAX_TIMEOUT_SECONDS = 600
        private val gson = GsonBuilder().disableHtmlEscaping().create()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
