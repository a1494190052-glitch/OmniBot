package cn.com.omnimind.bot.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import cn.com.omnimind.androidgui.AndroidGuiEnvironment
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.function.FunctionRun
import cn.com.omnimind.bot.function.FunctionApi
import cn.com.omnimind.bot.function.FunctionService
import cn.com.omnimind.bot.runlog.RunLogPagePackageInference
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class DebugFunctionRunReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val functionId = intent?.getStringExtra("functionId")
            ?: intent?.getStringExtra("function_id")
            ?: ""
        val goal = intent.decodeBase64Extra("goalBase64")
            ?: intent?.getStringExtra("goal").orEmpty()
        val arguments = intent.decodeJsonMapBase64Extra("argumentsBase64")
            ?: intent.decodeJsonMapBase64Extra("replayArgumentsBase64")
            ?: emptyMap()

        scope.launch {
            val result = runCatching {
                waitForReplayPage(appContext)
                runFunctionWithRunLogFallback(appContext, functionId, goal, arguments)
            }.getOrElse { error ->
                linkedMapOf<String, Any?>(
                    "success" to false,
                    "phase" to "exception",
                    "function_id" to functionId,
                    "arguments" to arguments,
                    "error_message" to error.message.orEmpty(),
                    "error_type" to error.javaClass.name,
                )
            }
            val json = gson.toJson(result)
            File(appContext.filesDir, "debug-oob-function-run-result.json").writeText(json)
            OmniLog.i(TAG, json)
        }
    }

    private suspend fun runFunctionWithRunLogFallback(
        context: Context,
        functionId: String,
        goal: String,
        arguments: Map<String, Any?>,
    ): Map<String, Any?> {
        val service = FunctionService(context)
        val functionRunner = FunctionRun(context)
        val initial = functionRunner.runFunction(functionRunArgs(functionId, goal, arguments))
        if (!isFunctionNotFound(initial)) return initial

        val runId = runIdFromDebugFunctionId(functionId)
            ?: return initial + linkedMapOf(
                "auto_register_attempted" to false,
                "auto_register_unavailable_reason" to "function_id_is_not_debug_run_id",
            )

        val convert = service.executeTool(
            FunctionApi.RUN_LOG_CONVERT,
            linkedMapOf(
                "run_id" to runId,
                "register" to true,
                "function_id" to functionId,
                "name" to "Debug GUI RunLog",
                "description" to goal,
            ),
        )
        if (convert["success"] != true) {
            return initial + linkedMapOf(
                "auto_register_attempted" to true,
                "auto_register_success" to false,
                "auto_register" to summarizeConvert(convert),
            )
        }

        return functionRunner.runFunction(functionRunArgs(functionId, goal, arguments)) + linkedMapOf(
            "auto_register_attempted" to true,
            "auto_register_success" to true,
            "auto_register" to summarizeConvert(convert),
        )
    }

    private fun functionRunArgs(
        functionId: String,
        goal: String,
        arguments: Map<String, Any?>,
    ): Map<String, Any?> =
        linkedMapOf(
            "function_id" to functionId,
            "goal" to goal,
            "arguments" to arguments,
        )

    private fun isFunctionNotFound(result: Map<String, Any?>): Boolean =
        result["error_code"] == "OOB_FUNCTION_NOT_FOUND" ||
            (result["guard"] as? Map<*, *>)?.get("error_code") == "OOB_FUNCTION_NOT_FOUND"

    private fun runIdFromDebugFunctionId(functionId: String): String? {
        val candidate = functionId.trim()
            .removePrefix("debug_")
            .replace('_', '-')
        return runCatching { UUID.fromString(candidate).toString() }.getOrNull()
    }

    private fun summarizeConvert(convert: Map<String, Any?>): Map<String, Any?> =
        linkedMapOf(
            "success" to convert["success"],
            "registered" to convert["registered"],
            "function_id" to convert["function_id"],
            "already_exists" to convert["already_exists"],
            "step_count" to convert["step_count"],
            "compiled_step_count" to convert["compiled_step_count"],
            "error_code" to convert["error_code"],
            "error_message" to convert["error_message"],
            "summary" to convert["summary"],
        ).filterValues { it != null }

    private suspend fun waitForAccessibility(environment: AndroidGuiEnvironment) {
        if (!environment.isAccessibilityEnabled()) {
            error("android_gui_accessibility_disabled")
        }
        repeat(300) {
            if (environment.isReady()) return
            delay(200L)
        }
        error("android_gui_accessibility_not_ready")
    }

    private suspend fun waitForReplayPage(context: Context) {
        val environment = AndroidGuiEnvironment(context)
        waitForAccessibility(environment)
        var lastPackage = ""
        var lastXmlChars = 0
        repeat(PAGE_OBSERVE_ATTEMPTS) { attempt ->
            val state = environment.observe(captureScreenshot = false)
            val xml = state.xml.trim()
            val rawPackage = state.packageName.trim()
            val effectivePackage = RunLogPagePackageInference.effectivePackage(rawPackage, xml)
            lastPackage = effectivePackage.ifBlank { rawPackage }
            lastXmlChars = xml.length
            if (xml.isNotBlank() && effectivePackage.isNotBlank() && !isOobPackage(context, effectivePackage)) {
                return
            }
            if (attempt < PAGE_OBSERVE_ATTEMPTS - 1) {
                delay(PAGE_OBSERVE_INTERVAL_MS)
            }
        }
        OmniLog.w(
            TAG,
            "Replay page XML is not ready; continuing and letting runner retry observations " +
                "last_package=$lastPackage last_xml_chars=$lastXmlChars"
        )
    }

    private fun isOobPackage(context: Context, packageName: String): Boolean =
        packageName == context.packageName || packageName.startsWith("cn.com.omnimind.")

    companion object {
        private const val TAG = "DebugFunctionRunReceiver"
        private const val PAGE_OBSERVE_ATTEMPTS = 80
        private const val PAGE_OBSERVE_INTERVAL_MS = 250L
        private val gson = GsonBuilder().disableHtmlEscaping().create()
        private val mapType = object : TypeToken<Map<String, Any?>>() {}.type
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private fun Intent?.decodeBase64Extra(name: String): String? {
        val raw = this?.getStringExtra(name)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8).trim()
                .takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun Intent?.decodeJsonMapBase64Extra(name: String): Map<String, Any?>? {
        val raw = decodeBase64Extra(name) ?: return null
        return runCatching {
            val decoded = gson.fromJson<Map<String, Any?>>(raw, mapType)
            decoded?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}
