package cn.com.omnimind.bot.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.runlog.OobOmniFlowToolkitService
import cn.com.omnimind.bot.runlog.OmniflowActionRuntime
import cn.com.omnimind.bot.runlog.RunLogPagePackageInference
import cn.com.omnimind.bot.util.AssistsUtil
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class DebugOobFunctionRunReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val functionId = intent?.getStringExtra("functionId")
            ?: intent?.getStringExtra("function_id")
            ?: ""
        val goal = intent.decodeBase64Extra("goalBase64")
            ?: intent?.getStringExtra("goal").orEmpty()

        scope.launch {
            val result = runCatching {
                waitForReplayPage(appContext)
                runFunctionWithRunLogFallback(appContext, functionId, goal)
            }.getOrElse { error ->
                linkedMapOf<String, Any?>(
                    "success" to false,
                    "phase" to "exception",
                    "function_id" to functionId,
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
    ): Map<String, Any?> {
        val service = OobOmniFlowToolkitService(context)
        val initial = service.runFunction(functionRunArgs(functionId, goal))
        if (!isFunctionNotFound(initial)) return initial

        val runId = runIdFromDebugFunctionId(functionId)
            ?: return initial + linkedMapOf(
                "auto_register_attempted" to false,
                "auto_register_unavailable_reason" to "function_id_is_not_debug_run_id",
            )

        val convert = service.convertRunLog(
            linkedMapOf(
                "run_id" to runId,
                "register" to true,
                "function_id" to functionId,
                "name" to "Debug VLM RunLog",
                "description" to goal,
            )
        )
        if (convert["success"] != true) {
            return initial + linkedMapOf(
                "auto_register_attempted" to true,
                "auto_register_success" to false,
                "auto_register" to summarizeConvert(convert),
            )
        }

        return service.runFunction(functionRunArgs(functionId, goal)) + linkedMapOf(
            "auto_register_attempted" to true,
            "auto_register_success" to true,
            "auto_register" to summarizeConvert(convert),
        )
    }

    private fun functionRunArgs(functionId: String, goal: String): Map<String, Any?> =
        linkedMapOf(
            "function_id" to functionId,
            "goal" to goal,
            "arguments" to emptyMap<String, Any?>(),
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
            "created_function_id" to convert["created_function_id"],
            "already_exists" to convert["already_exists"],
            "card_count" to convert["card_count"],
            "compiled_step_count" to convert["compiled_step_count"],
            "error_code" to convert["error_code"],
            "error_message" to convert["error_message"],
            "summary" to convert["summary"],
        ).filterValues { it != null }

    private suspend fun waitForAccessibility() {
        repeat(50) {
            if (AssistsService.instance != null && AccessibilityController.initController()) return
            delay(200L)
        }
        error("OOB accessibility service is not bound")
    }

    private suspend fun waitForReplayPage(context: Context) {
        if (!AssistsUtil.Core.isInitialized()) {
            AssistsUtil.Core.initCore(context)
        }
        waitForAccessibility()
        var lastPackage = ""
        var lastXmlChars = 0
        repeat(PAGE_OBSERVE_ATTEMPTS) { attempt ->
            val xml = currentXml()
            val rawPackage = currentPackageName()
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

    private suspend fun currentXml(): String =
        runCatching {
            if (AccessibilityController.initController()) {
                withContext(Dispatchers.Main.immediate) {
                    AccessibilityController.getCaptureScreenShotXml(true)
                }
            } else {
                null
            }
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: runCatching { OmniflowActionRuntime.backend.currentXml()?.trim().orEmpty() }.getOrDefault("")

    private suspend fun currentPackageName(): String =
        runCatching {
            if (AccessibilityController.initController()) {
                withContext(Dispatchers.Main.immediate) {
                    AccessibilityController.getPackageName()
                }
            } else {
                null
            }
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: runCatching { OmniflowActionRuntime.backend.currentPackageName()?.trim().orEmpty() }.getOrDefault("")

    private fun isOobPackage(context: Context, packageName: String): Boolean =
        packageName == context.packageName || packageName.startsWith("cn.com.omnimind.")

    companion object {
        private const val TAG = "DebugOobFunctionRunReceiver"
        private const val PAGE_OBSERVE_ATTEMPTS = 80
        private const val PAGE_OBSERVE_INTERVAL_MS = 250L
        private val gson = GsonBuilder().disableHtmlEscaping().create()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private fun Intent?.decodeBase64Extra(name: String): String? {
        val raw = this?.getStringExtra(name)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8).trim()
                .takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}
