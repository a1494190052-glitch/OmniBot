package cn.com.omnimind.bot.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import com.google.gson.reflect.TypeToken
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.runlog.RunLogStepRecord
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.function.FunctionRun
import cn.com.omnimind.bot.function.FunctionService
import cn.com.omnimind.bot.omniflow.omniFlowRecordStepExecutor
import cn.com.omnimind.bot.util.AssistsUtil
import cn.com.omnimind.uikit.settings.CompanionOverlaySettings
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.io.File

class DebugRunLogFunctionReplayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val runId = intent?.getStringExtra("runId")
            ?: intent?.getStringExtra("run_id")
            ?: ""
        val functionId = intent?.getStringExtra("functionId")
            ?: intent?.getStringExtra("function_id")
            ?: ""
        val name = intent.decodeBase64Extra("nameBase64")
            ?: intent?.getStringExtra("name").orEmpty()
        val description = intent.decodeBase64Extra("descriptionBase64")
            ?: intent?.getStringExtra("description").orEmpty()
        val goal = intent.decodeBase64Extra("goalBase64")
            ?: intent?.getStringExtra("goal").orEmpty()
        val effectiveGoal = goal.ifBlank { description }
        val shouldRun = intent?.getBooleanExtra("run", true) ?: true
        val shouldEnhance = intent?.getBooleanExtra("enhance", false) ?: false
        val agentVisible = intent.booleanExtra("agentVisible")
            ?: intent.booleanExtra("agent_visible")
            ?: false
        val enhancementInstruction = intent.decodeBase64Extra("enhancementInstructionBase64")
            ?: intent.decodeBase64Extra("instructionBase64")
            ?: intent?.getStringExtra("enhancementInstruction")
            ?: intent?.getStringExtra("instruction")
            ?: ""
        val enhancementAnalysis = intent.decodeJsonMapBase64Extra("enhancementAnalysisBase64")
            ?: intent.decodeJsonMapBase64Extra("analysisBase64")
            ?: emptyMap()
        val enhancementPatch = intent.decodeJsonMapBase64Extra("enhancementPatchBase64")
            ?: intent.decodeJsonMapBase64Extra("patchBase64")
            ?: emptyMap()
        val replayArguments = intent.decodeJsonMapBase64Extra("argumentsBase64")
            ?: intent.decodeJsonMapBase64Extra("replayArgumentsBase64")
            ?: emptyMap()
        val rawFunctionSpec = intent.decodeJsonMapBase64Extra("functionBase64")
            ?: intent.readJsonMapPath(appContext, "functionPath")
            ?: emptyMap()
        val rawRunLog = intent.decodeBase64Extra("runLogBase64")
            ?.let(::decodeRunLog)
            ?: intent.readRunLogPath(appContext)
            ?: emptyMap()
        val rawRunLogHasReplayableSteps = rawRunLog.hasReplayableSteps()

        scope.launch {
            val result = runCatching {
                if (shouldRun) waitForAccessibility(appContext)
                CompanionOverlaySettings.init(appContext)
                CompanionOverlaySettings.dismissFloatingUi()
                delay(300L)
                val service = FunctionService(appContext)
                val sourceRunId = if (rawRunLogHasReplayableSteps) {
                    persistInlineRunLog(appContext, runId, rawRunLog, effectiveGoal)
                } else {
                    runId
                }
                val convertArgs = linkedMapOf<String, Any?>(
                    "run_id" to sourceRunId,
                    "register" to true,
                    "agent_visible" to agentVisible,
                )
                val inlineFunctionId = firstNonBlank(
                    functionId,
                    rawFunctionSpec["function_id"],
                )
                inlineFunctionId.takeIf { it.isNotBlank() }?.let {
                    convertArgs["function_id"] = it
                }
                name.takeIf { it.isNotBlank() }?.let {
                    convertArgs["name"] = it
                }
                description.takeIf { it.isNotBlank() }?.let {
                    convertArgs["description"] = it
                }

                val convert = when {
                    rawRunLogHasReplayableSteps -> service.convertRunLog(convertArgs)
                    rawFunctionSpec.isNotEmpty() -> linkedMapOf<String, Any?>(
                        "success" to true,
                        "function_id" to inlineFunctionId,
                        "function" to rawFunctionSpec,
                        "source" to "omniflow.function.v2",
                    )
                    else -> service.convertRunLog(convertArgs)
                }
                val inlineFunctionSpec = when {
                    rawRunLogHasReplayableSteps -> {
                        (convert["function"] as? Map<*, *>)
                            ?.mapKeys { it.key?.toString().orEmpty() }
                    }
                    rawFunctionSpec.isNotEmpty() -> rawFunctionSpec
                    else -> null
                }
                val inlineRegistration = if (
                    shouldRun &&
                    convert["success"] == true &&
                    inlineFunctionSpec != null
                ) {
                    service.registerFunction(linkedMapOf("function" to inlineFunctionSpec))
                } else {
                    null
                }
                val resolvedFunctionId = convert["function_id"]?.toString()
                    ?: inlineRegistration?.get("function_id")?.toString()
                    ?: ""
                val functionSpec = convert["function"]
                val enhance = buildOfflineEnhanceStatus(
                    requested = shouldEnhance,
                    functionId = resolvedFunctionId,
                    runId = convert["run_id"]?.toString()
                        ?: runId.ifBlank { rawRunLog["run_id"]?.toString().orEmpty() },
                    goal = effectiveGoal,
                    instruction = enhancementInstruction,
                    analysis = enhancementAnalysis,
                    patch = enhancementPatch,
                )
                val enhanceCost = buildEnhanceCost(
                    requested = shouldEnhance,
                    enhance = enhance,
                )
                val functionSpecHash = stableHash(functionSpec)
                val canReplay = shouldRun &&
                    convert["success"] == true &&
                    inlineRegistration?.get("success") != false &&
                    resolvedFunctionId.isNotBlank()
                val functionSpecBeforeReplay = if (canReplay) {
                    service.getFunction(linkedMapOf("function_id" to resolvedFunctionId))
                } else {
                    null
                }
                val functionSpecBeforeReplayHash = stableHash(functionSpecBeforeReplay)
                val replay: Map<String, Any?>? = if (
                    canReplay
                ) {
                    FunctionRun(appContext).runFunction(
                        linkedMapOf(
                            "function_id" to resolvedFunctionId,
                            "goal" to effectiveGoal,
                            "arguments" to replayArguments,
                            "confirmed" to true,
                            "frontend_parent" to "debug_replay",
                        )
                    )
                } else {
                    null
                }
                val replaySkippedReason = if (!shouldRun) {
                    "run_not_requested"
                } else if (convert["success"] != true) {
                    "convert_failed"
                } else if (inlineRegistration?.get("success") == false) {
                    "registration_failed"
                } else if (resolvedFunctionId.isBlank()) {
                    "missing_function_id"
                } else {
                    null
                }

                linkedMapOf<String, Any?>(
                    "success" to (
                        convert["success"] == true &&
                            (replay?.get("success") != false)
                        ),
                    "run_id" to (
                        convert["run_id"] ?: runId.ifBlank {
                            rawRunLog["run_id"] ?: rawRunLog["runId"]
                        }
                    ),
                    "function_id" to resolvedFunctionId,
                    "agent_visible" to agentVisible,
                    "convert" to convert,
                    "inline_registration" to inlineRegistration,
                    "enhance_requested" to shouldEnhance,
                    "enhance" to enhance,
                    "enhance_cost" to enhanceCost,
                    "function" to functionSpec,
                    "function_hash" to functionSpecHash.takeIf { it.isNotBlank() },
                    "function_before_replay_hash" to functionSpecBeforeReplayHash.takeIf { it.isNotBlank() },
                    "replay_arguments" to replayArguments,
                    "replay" to replay,
                    "replay_uses_enhanced_function" to false,
                    "enhancement_policy" to "offline_only",
                    "replay_skipped_reason" to replaySkippedReason,
                    "run_requested" to shouldRun,
                )
            }.getOrElse { error ->
                OmniLog.e(TAG, "debug runlog function replay failed: ${error.fullMessage()}", error)
                linkedMapOf<String, Any?>(
                    "success" to false,
                    "phase" to "exception",
                    "run_id" to runId,
                    "function_id" to functionId,
                    "error_message" to error.fullMessage(),
                    "error_type" to error.javaClass.name,
                    "error_cause_chain" to error.causeChain(),
                )
            }
            val json = gson.toJson(result)
            File(appContext.filesDir, "debug-runlog-function-replay-result.json").writeText(json)
            OmniLog.i(TAG, json)
        }
    }

    companion object {
        private const val TAG = "DebugRunLogFunctionReplayReceiver"
        private const val ACCESSIBILITY_ATTEMPTS = 300
        private const val ACCESSIBILITY_INTERVAL_MS = 200L
        private val gson = GsonBuilder().disableHtmlEscaping().create()
        private val mapType = object : TypeToken<Map<String, Any?>>() {}.type
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private suspend fun waitForAccessibility(context: Context) {
        if (!AssistsUtil.Core.isInitialized()) {
            AssistsUtil.Core.initCore(context)
        }
        repeat(ACCESSIBILITY_ATTEMPTS) {
            if (AssistsService.instance != null && AccessibilityController.initController()) {
                return
            }
            delay(ACCESSIBILITY_INTERVAL_MS)
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

    private fun Intent?.decodeJsonMapBase64Extra(name: String): Map<String, Any?>? {
        val raw = decodeBase64Extra(name) ?: return null
        return decodeRunLog(raw).takeIf { it.isNotEmpty() }
    }

    private fun stableHash(value: Any?): String {
        if (value !is Map<*, *> || value.isEmpty()) return ""
        val json = gson.toJson(value)
        val digest = MessageDigest.getInstance("SHA-256").digest(json.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun buildEnhanceCost(
        requested: Boolean,
        enhance: Map<String, Any?>?,
    ): Map<String, Any?> {
        if (!requested) {
            return linkedMapOf(
                "requested" to false,
                "policy" to "offline_only",
                "backend" to "offline_update_function",
            )
        }
        val updateCostPayload = firstMap(enhance?.get("cost"))
        val usage = firstMap(
            enhance?.get("usage"),
            enhance?.get("token_usage"),
            enhance?.get("tokenUsage"),
            updateCostPayload["usage"],
        )
        val cost = firstMap(
            updateCostPayload["cost"],
            enhance?.get("cost_estimate"),
            enhance?.get("costEstimate"),
        )
        return linkedMapOf<String, Any?>(
            "requested" to true,
            "success" to (enhance?.get("success") == true),
            "policy" to "offline_only",
            "backend" to (enhance?.get("source")?.toString()?.takeIf { it.isNotBlank() } ?: "offline_update_function"),
            "duration_ms" to 0L,
            "usage" to usage.takeIf { it.isNotEmpty() },
            "cost" to cost.takeIf { it.isNotEmpty() },
        ).filterValues { it != null }
    }

    private fun buildOfflineEnhanceStatus(
        requested: Boolean,
        functionId: String,
        runId: String,
        goal: String,
        instruction: String,
        analysis: Map<String, Any?>,
        patch: Map<String, Any?>,
    ): Map<String, Any?>? {
        if (!requested) return null
        val canQueue = functionId.isNotBlank()
        return linkedMapOf<String, Any?>(
            "success" to canQueue,
            "status" to if (canQueue) "queued" else "skipped",
            "policy" to "offline_only",
            "source" to "offline_update_function",
            "function_id" to functionId.takeIf { it.isNotBlank() },
            "run_id" to runId.takeIf { it.isNotBlank() },
            "mode" to "enhance",
            "instruction" to instruction.takeIf { it.isNotBlank() },
            "analysis" to analysis.takeIf { it.isNotEmpty() },
            "patch" to patch.takeIf { it.isNotEmpty() },
            "goal" to goal.takeIf { it.isNotBlank() },
            "message" to if (canQueue) {
                "Enhancement is offline-only; replay uses the registered Function as-is."
            } else {
                "Enhancement is offline-only but no registered Function was available to queue."
            },
        ).filterValues { it != null }
    }

    private fun firstMap(vararg values: Any?): Map<String, Any?> {
        for (value in values) {
            val mapped = (value as? Map<*, *>)
                ?.mapKeys { it.key?.toString().orEmpty() }
                ?.filterKeys { it.isNotBlank() }
            if (!mapped.isNullOrEmpty()) return mapped
        }
        return emptyMap()
    }

    private fun Intent?.readRunLogPath(context: Context): Map<String, Any?>? {
        return readJsonMapPath(context, "runLogPath", "run_log_path")
    }

    private fun Intent?.readJsonMapPath(context: Context, vararg names: String): Map<String, Any?>? {
        val rawPath = names.firstNotNullOfOrNull { name ->
            this?.getStringExtra(name)?.trim()?.takeIf { it.isNotEmpty() }
        } ?: return null
        val path = rawPath.trim().takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            val baseDir = context.filesDir.canonicalFile
            val file = File(path).let { candidate ->
                if (candidate.isAbsolute) candidate else File(baseDir, path)
            }.canonicalFile
            require(file.path.startsWith(baseDir.path + File.separator)) {
                "JSON path must be inside app files directory"
            }
            decodeRunLog(file.readText())
        }.getOrElse { error ->
            OmniLog.e(TAG, "read JSON path failed: ${error.message}", error)
            emptyMap()
        }
    }

    private fun firstNonBlank(vararg values: Any?): String =
        values.firstNotNullOfOrNull { value ->
            value?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }.orEmpty()

    private fun Map<String, Any?>.hasReplayableSteps(): Boolean =
        this["schema_version"] == "omniflow.canonical_run_log.v1" &&
            (this["steps"] as? List<*>)?.isNotEmpty() == true

    private suspend fun persistInlineRunLog(
        context: Context,
        requestedRunId: String,
        runLog: Map<String, Any?>,
        fallbackGoal: String,
    ): String {
        require(runLog["schema_version"] == "omniflow.canonical_run_log.v1") {
            "canonical_run_log_required"
        }
        val runId = firstNonBlank(requestedRunId, runLog["run_id"])
            .ifBlank { "debug_inline_${System.currentTimeMillis()}" }
        val goal = firstNonBlank(runLog["goal"], fallbackGoal)
        val rawSteps = (runLog["steps"] as? List<*>) ?: emptyList<Any?>()
        val stepConverter = omniFlowRecordStepExecutor(context)
        val steps = rawSteps.mapNotNull { raw ->
            val step = (raw as? Map<*, *>)?.entries
                ?.associate { (key, value) -> key.toString() to value }
                ?: return@mapNotNull null
            stepConverter.recordStep(
                RunLogStepRecord(step = step, states = emptyList()),
            ).step
        }
        InternalRunLogStore.replaceRun(
            context = context,
            runId = runId,
            goal = goal,
            source = "debug_inline_runlog_import",
            operationDescription = goal,
            steps = steps,
            success = runLog["success"] == true,
            doneReason = "debug_inline_runlog_imported",
            finalStateId = firstNonBlank(runLog["final_state_id"]).takeIf(String::isNotEmpty),
        )
        return runId
    }

    private fun Intent?.booleanExtra(name: String): Boolean? =
        this?.takeIf { it.hasExtra(name) }?.getBooleanExtra(name, false)

    private fun decodeRunLog(rawJson: String): Map<String, Any?> {
        return runCatching {
            val decoded = gson.fromJson<Map<String, Any?>>(rawJson, mapType)
            decoded ?: emptyMap()
        }.getOrDefault(emptyMap())
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
