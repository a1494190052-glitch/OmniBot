package cn.com.omnimind.bot.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.runlog.OobOmniFlowToolkitService
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

class DebugOobFunctionUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val pending = goAsync()

        scope.launch {
            val result = runCatching {
                val functionId = intent.firstStringExtra("functionId", "function_id")
                val runId = intent.firstStringExtra("runId", "run_id")
                val mode = intent.firstStringExtra("mode", "operation").ifBlank { "enhance" }
                val instruction = intent.decodeBase64Extra("instructionBase64")
                    ?: intent.firstStringExtra("instruction", "request", "user_instruction")
                val analysis = intent.decodeJsonMapBase64Extra("analysisBase64")
                    ?: intent.decodeJsonMapBase64Extra("enhancementAnalysisBase64")
                    ?: emptyMap()
                val patch = intent.decodeJsonMapBase64Extra("patchBase64")
                    ?: intent.decodeJsonMapBase64Extra("enhancementPatchBase64")
                    ?: directPatchFromExtras(intent)
                val extraArgs = intent.decodeJsonMapBase64Extra("extraArgsBase64")
                    ?: emptyMap()
                val dryRun = intent.booleanExtra("dryRun")
                    ?: intent.booleanExtra("dry_run")
                val allowExecutionChange = intent.booleanExtra("allowExecutionChange")
                    ?: intent.booleanExtra("allow_execution_change")
                val allowStructuralChange = intent.booleanExtra("allowStructuralChange")
                    ?: intent.booleanExtra("allow_structural_change")
                val backgroundEnhancement = intent.booleanExtra("backgroundEnhancement")
                    ?: intent.booleanExtra("background_enhancement")
                val autoAnalyzeWithModel = intent.booleanExtra("autoAnalyzeWithModel")
                    ?: intent.booleanExtra("auto_analyze_with_model")

                val args = linkedMapOf<String, Any?>().apply {
                    putAll(extraArgs)
                    put("offline_job", true)
                    put("debug_offline_update", true)
                    put("function_id", functionId)
                    put("mode", mode)
                    if (runId.isNotBlank()) put("run_id", runId)
                    if (instruction.isNotBlank()) put("instruction", instruction)
                    if (analysis.isNotEmpty()) put("analysis", analysis)
                    if (patch.isNotEmpty()) put("patch", patch)
                    dryRun?.let { put("dry_run", it) }
                    allowExecutionChange?.let { put("allow_execution_change", it) }
                    allowStructuralChange?.let { put("allow_structural_change", it) }
                    backgroundEnhancement?.let { put("background_enhancement", it) }
                    autoAnalyzeWithModel?.let { put("auto_analyze_with_model", it) }
                }.filterValues { it != null }

                val service = OobOmniFlowToolkitService(appContext)
                val before = if (functionId.isNotBlank()) {
                    service.getFunction(linkedMapOf("function_id" to functionId))
                } else {
                    emptyMap()
                }
                val updateStartedAtMs = System.currentTimeMillis()
                val update = service.updateFunction(args)
                val updateEndedAtMs = System.currentTimeMillis()
                val afterFunctionId = update["function_id"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: functionId
                val after = if (afterFunctionId.isNotBlank()) {
                    service.getFunction(linkedMapOf("function_id" to afterFunctionId))
                } else {
                    emptyMap()
                }

                linkedMapOf<String, Any?>(
                    "success" to (update["success"] == true),
                    "function_id" to afterFunctionId,
                    "run_id" to runId.takeIf { it.isNotBlank() },
                    "mode" to mode,
                    "changed" to update["changed"],
                    "saved" to update["saved"],
                    "requires_confirmation" to update["requires_confirmation"],
                    "needs_agent_analysis" to update["needs_agent_analysis"],
                    "before_found" to (before["success"] == true),
                    "after_found" to (after["success"] == true),
                    "before_hash" to stableHash(before["function"]),
                    "after_hash" to stableHash(after["function"]),
                    "hash_changed" to (
                        stableHash(before["function"]).isNotBlank() &&
                            stableHash(after["function"]).isNotBlank() &&
                            stableHash(before["function"]) != stableHash(after["function"])
                        ),
                    "duration_ms" to (updateEndedAtMs - updateStartedAtMs).coerceAtLeast(0L),
                    "args" to args,
                    "update" to update,
                    "source" to "debug_oob_function_update_receiver",
                )
            }.getOrElse { error ->
                OmniLog.e(TAG, "debug update_function failed: ${error.fullMessage()}", error)
                linkedMapOf<String, Any?>(
                    "success" to false,
                    "phase" to "exception",
                    "error_message" to error.fullMessage(),
                    "error_type" to error.javaClass.name,
                    "error_cause_chain" to error.causeChain(),
                    "source" to "debug_oob_function_update_receiver",
                )
            }

            val json = gson.toJson(result)
            File(appContext.filesDir, RESULT_FILE).writeText(json)
            OmniLog.i(TAG, json)
            pending.finish()
        }
    }

    companion object {
        const val RESULT_FILE = "debug-oob-function-update-result.json"
        private const val TAG = "DebugOobFunctionUpdateReceiver"
        private val gson = GsonBuilder().disableHtmlEscaping().create()
        private val mapType = object : TypeToken<Map<String, Any?>>() {}.type
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private fun Intent?.firstStringExtra(vararg names: String): String {
        if (this == null) return ""
        for (name in names) {
            val value = getStringExtra(name)?.trim()
            if (!value.isNullOrEmpty()) return value
        }
        return ""
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
            gson.fromJson<Map<String, Any?>>(raw, mapType)
                ?.filterKeys { it.isNotBlank() }
                ?: emptyMap()
        }.getOrNull()
    }

    private fun Intent?.booleanExtra(name: String): Boolean? =
        this?.takeIf { it.hasExtra(name) }?.getBooleanExtra(name, false)

    private fun directPatchFromExtras(intent: Intent?): Map<String, Any?> {
        if (intent == null) return emptyMap()
        val patch = linkedMapOf<String, Any?>()
        val name = intent.decodeBase64Extra("nameBase64")
            ?: intent.firstStringExtra("name")
        if (name.isNotBlank()) patch["name"] = name
        val description = intent.decodeBase64Extra("descriptionBase64")
            ?: intent.firstStringExtra("description")
        if (description.isNotBlank()) patch["description"] = description
        return patch
    }

    private fun stableHash(value: Any?): String {
        if (value !is Map<*, *> || value.isEmpty()) return ""
        val json = gson.toJson(value)
        val digest = MessageDigest.getInstance("SHA-256").digest(json.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun Throwable.fullMessage(): String {
        val parts = mutableListOf<String>()
        var current: Throwable? = this
        val seen = mutableSetOf<Throwable>()
        while (current != null && seen.add(current)) {
            parts += current.message?.takeIf(String::isNotBlank)
                ?.let { "${current.javaClass.name}: $it" }
                ?: current.javaClass.name
            current = current.cause
        }
        return parts.joinToString(" <- ")
    }

    private fun Throwable.causeChain(): List<Map<String, String>> {
        val output = mutableListOf<Map<String, String>>()
        var current: Throwable? = this
        val seen = mutableSetOf<Throwable>()
        while (current != null && seen.add(current)) {
            output += linkedMapOf(
                "type" to current.javaClass.name,
                "message" to current.message.orEmpty(),
            )
            current = current.cause
        }
        return output
    }
}
