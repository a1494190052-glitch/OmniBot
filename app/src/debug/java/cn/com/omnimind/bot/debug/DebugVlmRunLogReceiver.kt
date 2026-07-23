package cn.com.omnimind.bot.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.runlog.RunLogStepRecord
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentAiCapabilityConfigSync
import cn.com.omnimind.bot.agent.ResolvedSkillContext
import cn.com.omnimind.bot.manager.AssistsCoreManager
import cn.com.omnimind.bot.mcp.VlmTaskRequest
import cn.com.omnimind.bot.function.FunctionService
import cn.com.omnimind.bot.omniflow.omniFlowRecordStepExecutor
import cn.com.omnimind.bot.util.AssistsUtil
import cn.com.omnimind.bot.vlm.VlmToolCoordinator
import cn.com.omnimind.bot.vlm.VlmToolOutcomeStatus
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class DebugVlmRunLogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val requestId = intent?.getStringExtra("requestId")?.trim().orEmpty()
        File(appContext.filesDir, "debug-vlm-runlog-result.json").delete()
        val goal = intent.decodeBase64Extra("goalBase64")
            ?: intent?.getStringExtra("goal")?.takeIf { it.isNotBlank() }
            ?: "打开 Settings"
        val startFromCurrent = intent?.getBooleanExtra("startFromCurrent", false) ?: false
        val skipGoHome = intent?.getBooleanExtra("skipGoHome", startFromCurrent) ?: startFromCurrent
        val prelaunch = intent?.getBooleanExtra("prelaunch", true) ?: true
        val shouldPrelaunch = prelaunch && !startFromCurrent && !skipGoHome
        val targetPackageName = intent?.getStringExtra("packageName")?.takeIf { it.isNotBlank() }
        val prelaunchPackageName = if (shouldPrelaunch) {
            targetPackageName ?: "com.android.settings"
        } else {
            null
        }
        val maxSteps = intent?.getIntExtra("maxSteps", 1)?.takeIf { it > 0 } ?: 1
        val waitTimeoutMs = intent.readWaitTimeoutMs()
        val register = intent?.getBooleanExtra("register", true) ?: true
        val profileId = intent?.getStringExtra("profileId")?.trim().orEmpty()
        val modelId = intent?.getStringExtra("modelId")?.trim().orEmpty()
        val skillId = intent?.getStringExtra("skillId")?.trim().orEmpty()
        val disableFunctionRecall = intent.readBooleanExtra(
            "disableFunctionRecall",
            "disable_function_recall",
            "disableOmniFlowRecall",
            "disable_omniflow_recall",
            "disableRecall",
            "disable_recall"
        )
        val parseOnly = intent.readBooleanExtra("parseOnly", "parse_only", "dryRun", "dry_run")
        val offlineSeed = intent.readBooleanExtra("offlineSeed", "offline_seed", "seedRunLog", "seed_run_log")
        val stepSkillGuidance = intent.decodeBase64Extra("stepSkillGuidanceBase64")
            ?: intent?.getStringExtra("stepSkillGuidance")?.trim().orEmpty()
                .takeIf { it.isNotBlank() }
            ?: loadBuiltinSkillGuidance(appContext, skillId)

        scope.launch {
            val result = runCatching {
                run(
                    appContext,
                    goal,
                    targetPackageName,
                    prelaunchPackageName,
                    maxSteps,
                    waitTimeoutMs,
                    register,
                    profileId,
                    modelId,
                    shouldPrelaunch,
                    startFromCurrent,
                    skipGoHome,
                    stepSkillGuidance,
                    disableFunctionRecall,
                    parseOnly,
                    offlineSeed,
                )
                    .withRequestId(requestId)
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
            File(appContext.filesDir, "debug-vlm-runlog-result.json").writeText(json)
            OmniLog.i(TAG, json)
        }
    }

    private fun Map<String, Any?>.withRequestId(requestId: String): Map<String, Any?> {
        if (requestId.isBlank()) return this
        return linkedMapOf<String, Any?>("request_id" to requestId).apply {
            putAll(this@withRequestId)
        }
    }

    private suspend fun run(
        context: Context,
        goal: String,
        targetPackageName: String?,
        prelaunchPackageName: String?,
        maxSteps: Int,
        waitTimeoutMs: Long?,
        register: Boolean,
        profileId: String,
        modelId: String,
        prelaunch: Boolean,
        startFromCurrent: Boolean,
        skipGoHome: Boolean,
        stepSkillGuidance: String,
        disableFunctionRecall: Boolean,
        parseOnly: Boolean,
        offlineSeed: Boolean,
    ): Map<String, Any?> {
        if (!AssistsUtil.Core.isInitialized()) {
            AssistsUtil.Core.initCore(context)
        }
        val configuredBinding = configureVlmBindingIfRequested(context, profileId, modelId)
        val effectiveBinding = effectiveVlmBindingPayload()
        waitForAccessibility()

        if (offlineSeed) {
            return seedSuccessfulVlmRunLog(
                context = context,
                goal = goal,
                targetPackageName = targetPackageName,
                prelaunchPackageName = prelaunchPackageName,
                prelaunch = prelaunch,
                startFromCurrent = startFromCurrent,
                skipGoHome = skipGoHome,
                disableFunctionRecall = disableFunctionRecall,
                waitTimeoutMs = waitTimeoutMs,
                register = register,
                stepSkillGuidance = stepSkillGuidance,
                configuredBinding = configuredBinding,
            )
        }

        if (parseOnly) {
            val result = VlmToolCoordinator.parseOnlyNextAction(
                context = context,
                request = VlmTaskRequest(
                    goal = goal,
                    model = modelId.ifEmpty { null },
                    packageName = targetPackageName,
                    maxSteps = maxSteps,
                    waitTimeoutMs = waitTimeoutMs,
                    needSummary = false,
                    skipGoHome = startFromCurrent || skipGoHome,
                    stepSkillGuidance = stepSkillGuidance,
                    disableFunctionRecall = disableFunctionRecall,
                ),
                scope = scope,
            )
            return linkedMapOf(
                "success" to result.success,
                "parse_only" to true,
                "executed" to false,
                "goal" to goal,
                "packageName" to targetPackageName,
                "prelaunchPackageName" to prelaunchPackageName,
                "prelaunch" to prelaunch,
                "startFromCurrent" to startFromCurrent,
                "skipGoHome" to skipGoHome,
                "disable_function_recall" to disableFunctionRecall,
                "wait_timeout_ms" to waitTimeoutMs,
                "step_skill_guidance_chars" to stepSkillGuidance.length,
                "configured_binding" to configuredBinding,
                "effective_binding" to effectiveBinding,
                "parse_result" to result.toPayload(),
            )
        }

        val outcome = VlmToolCoordinator.executeNewTask(
            context = context,
            request = VlmTaskRequest(
                goal = goal,
                model = modelId.ifEmpty { null },
                packageName = targetPackageName,
                maxSteps = maxSteps,
                waitTimeoutMs = waitTimeoutMs,
                needSummary = false,
                skipGoHome = startFromCurrent || skipGoHome,
                stepSkillGuidance = stepSkillGuidance,
                disableFunctionRecall = disableFunctionRecall,
            ),
            scope = scope,
        )
        val runId = outcome.taskId
        val record = InternalRunLogStore.getRun(context, runId)
        val outcomePayload = outcome.toPayload()
        val vlmTaskFinished = outcome.status == VlmToolOutcomeStatus.FINISHED
        val runLogSuccessful = record?.success == true
        var timeline = record?.let { InternalRunLogStore.timelinePayload(context, runId) }
        val convert = if (register && vlmTaskFinished && runLogSuccessful) {
            FunctionService(context).convertRunLog(
                mapOf(
                    "run_id" to runId,
                    "register" to register,
                    "function_id" to "debug_${runId.replace('-', '_')}",
                    "name" to goal.take(120),
                    "description" to goal,
                    "agent_visible" to register,
                )
            )
        } else {
            null
        }
        if (convert != null && record != null) {
            timeline = InternalRunLogStore.timelinePayload(context, runId)
        }
        val timelineDiagnostics = timeline?.get("diagnostics") as? Map<*, *>
        val tokenUsage = timelineDiagnostics?.get("token_usage") as? Map<*, *>

        return linkedMapOf(
            "success" to vlmTaskFinished,
            "goal" to goal,
            "packageName" to targetPackageName,
            "prelaunchPackageName" to prelaunchPackageName,
            "prelaunch" to prelaunch,
            "startFromCurrent" to startFromCurrent,
            "skipGoHome" to skipGoHome,
            "disable_function_recall" to disableFunctionRecall,
            "wait_timeout_ms" to waitTimeoutMs,
            "step_skill_guidance_chars" to stepSkillGuidance.length,
            "configured_binding" to configuredBinding,
            "effective_binding" to effectiveBinding,
            "outcome" to outcomePayload,
            "vlm_task_finished" to vlmTaskFinished,
            "run_id" to runId,
            "runlog_found" to (record != null),
            "runlog_success" to runLogSuccessful,
            "runlog_step_count" to (record?.steps?.size ?: 0),
            "run_log" to timeline,
            "token_usage" to (tokenUsage ?: emptyMap<String, Any?>()),
            "token_usage_total" to tokenUsage?.get("total_tokens"),
            "token_usage_by_step" to (
                timelineDiagnostics?.get("token_usage_by_step") ?: emptyList<Map<String, Any?>>()
            ),
            "token_usage_by_call" to (
                timelineDiagnostics?.get("token_usage_by_call") ?: emptyList<Map<String, Any?>>()
            ),
            "convert" to convert,
            "convert_success" to (convert?.get("success") == true),
        )
    }

    private suspend fun seedSuccessfulVlmRunLog(
        context: Context,
        goal: String,
        targetPackageName: String?,
        prelaunchPackageName: String?,
        prelaunch: Boolean,
        startFromCurrent: Boolean,
        skipGoHome: Boolean,
        disableFunctionRecall: Boolean,
        waitTimeoutMs: Long?,
        register: Boolean,
        stepSkillGuidance: String,
        configuredBinding: Map<String, Any?>?,
    ): Map<String, Any?> {
        val runId = "debug-vlm-seed-${System.currentTimeMillis()}"
        val currentSnapshot = waitForUsableAccessibilitySnapshot(targetPackageName)
        val currentPackage = currentSnapshot.packageName
        val currentXml = currentSnapshot.xml

        InternalRunLogStore.beginRun(
            context = context,
            runId = runId,
            goal = goal,
            source = "vlm",
            toolName = "vlm_task",
            operationDescription = goal,
        )
        val canonicalStep = omniFlowRecordStepExecutor().recordStep(
            RunLogStepRecord(
                step = linkedMapOf(
                    "step_index" to 0,
                    "before_state_id" to "$runId-before",
                    "action" to linkedMapOf(
                        "tool" to "wait",
                        "args" to linkedMapOf("duration_ms" to 100L),
                    ),
                    "result" to linkedMapOf("success" to true),
                    "after_state_id" to "$runId-after",
                    "metadata" to linkedMapOf(
                        "step_id" to "$runId-step-0",
                        "status" to "succeeded",
                        "summary" to "Seeded debug step",
                        "seeded" to true,
                    ),
                ),
                states = listOf(
                    linkedMapOf(
                        "state_id" to "$runId-before",
                        "package_name" to currentPackage,
                        "xml" to currentXml,
                    ),
                    linkedMapOf(
                        "state_id" to "$runId-after",
                        "package_name" to currentPackage,
                        "xml" to currentXml,
                    ),
                ),
            )
        )
        InternalRunLogStore.appendRecordedStep(
            context = context,
            runId = runId,
            record = canonicalStep,
        )
        InternalRunLogStore.finishRun(
            context = context,
            runId = runId,
            success = true,
            doneReason = "offline_seed_finished",
        )

        val convert = FunctionService(context).convertRunLog(
            mapOf(
                "run_id" to runId,
                "register" to register,
                "function_id" to "debug_${runId.replace('-', '_')}",
                "name" to "Debug seeded RunLog",
                "description" to goal,
            )
        )
        val refreshedTimeline = InternalRunLogStore.timelinePayload(context, runId)

        return linkedMapOf(
            "success" to (convert["success"] == true),
            "phase" to "offline_seed",
            "offline_seed" to true,
            "goal" to goal,
            "packageName" to targetPackageName,
            "current_package" to currentPackage,
            "prelaunchPackageName" to prelaunchPackageName,
            "prelaunch" to prelaunch,
            "startFromCurrent" to startFromCurrent,
            "skipGoHome" to skipGoHome,
            "disable_function_recall" to disableFunctionRecall,
            "wait_timeout_ms" to waitTimeoutMs,
            "step_skill_guidance_chars" to stepSkillGuidance.length,
            "configured_binding" to configuredBinding,
            "effective_binding" to effectiveVlmBindingPayload(),
            "outcome" to linkedMapOf(
                "success" to true,
                "status" to "FINISHED",
                "taskId" to runId,
                "executionRoute" to "offline_seed_runlog",
                "message" to "Seeded successful vlm_task RunLog for debug recall-loop smoke.",
            ),
            "vlm_task_finished" to true,
            "run_id" to runId,
            "runlog_found" to true,
            "runlog_success" to true,
            "runlog_step_count" to 1,
            "run_log" to refreshedTimeline,
            "token_usage" to emptyMap<String, Any?>(),
            "token_usage_total" to 0,
            "token_usage_by_step" to emptyList<Map<String, Any?>>(),
            "token_usage_by_call" to emptyList<Map<String, Any?>>(),
            "convert" to convert,
            "convert_success" to (convert["success"] == true),
        )
    }

    private fun configureVlmBindingIfRequested(
        context: Context,
        profileId: String,
        modelId: String,
    ): Map<String, Any?>? {
        if (profileId.isEmpty() && modelId.isEmpty()) return null

        val resolvedProfileId = profileId.ifEmpty {
            ModelProviderConfigStore.getEditingProfileId()
        }
        val resolvedModelId = modelId.ifEmpty {
            SceneModelBindingStore.getBinding("scene.dispatch.model")?.modelId.orEmpty()
        }
        require(resolvedProfileId.isNotEmpty()) { "profileId is empty" }
        require(resolvedModelId.isNotEmpty()) { "modelId is empty" }

        SceneModelBindingStore.saveBinding(
            sceneId = "scene.vlm.operation.primary",
            providerProfileId = resolvedProfileId,
            modelId = resolvedModelId,
        )
        AgentAiCapabilityConfigSync.get(context).syncFileFromStores()
        AssistsCoreManager.dispatchAgentAiConfigChanged(
            source = "debug_vlm_runlog",
            path = "scene.vlm.operation.primary",
        )
        return linkedMapOf(
            "sceneId" to "scene.vlm.operation.primary",
            "profileId" to resolvedProfileId,
            "modelId" to resolvedModelId,
        )
    }

    private fun effectiveVlmBindingPayload(): Map<String, Any?> {
        val sceneId = "scene.vlm.operation.primary"
        val binding = SceneModelBindingStore.getBinding(sceneId)
        val profile = binding?.providerProfileId?.let(ModelProviderConfigStore::getProfile)
        return linkedMapOf(
            "sceneId" to sceneId,
            "providerProfileId" to binding?.providerProfileId,
            "modelId" to binding?.modelId,
            "profileName" to profile?.name,
            "baseUrl" to profile?.baseUrl,
            "protocolType" to profile?.protocolType,
            "wireApi" to profile?.wireApi,
            "apiKeyConfigured" to (profile?.apiKey?.isNotBlank() == true),
            "configured" to (profile?.isConfigured() == true),
        ).filterValues { it != null }
    }

    private suspend fun waitForAccessibility() {
        repeat(50) {
            if (AssistsService.instance != null) return
            delay(200L)
        }
        error("OOB accessibility service is not bound")
    }

    private suspend fun waitForUsableAccessibilitySnapshot(
        targetPackageName: String?,
        timeoutMs: Long = 5_000L,
    ): AccessibilitySnapshot {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastPackage = ""
        var lastXmlChars = 0
        while (System.currentTimeMillis() < deadline) {
            lastPackage = AccessibilityController.getPackageName()?.trim().orEmpty()
            val xml = AccessibilityController.getCaptureScreenShotXml(true)?.trim().orEmpty()
            lastXmlChars = xml.length
            if (xml.hasUsableAccessibilityNodes()) {
                return AccessibilitySnapshot(
                    packageName = lastPackage.ifBlank { targetPackageName.orEmpty() },
                    xml = xml,
                )
            }
            delay(200L)
        }
        error(
            "current accessibility XML is empty or unusable after ${timeoutMs}ms; " +
                "lastPackage=${lastPackage.ifBlank { "<blank>" }}, lastXmlChars=$lastXmlChars"
        )
    }

    private fun String.hasUsableAccessibilityNodes(): Boolean =
        contains("<node") && !contains("<hierarchy />")

    private data class AccessibilitySnapshot(
        val packageName: String,
        val xml: String,
    )

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

    private fun Intent?.readBooleanExtra(vararg names: String, default: Boolean = false): Boolean {
        val intent = this ?: return default
        names.forEach { name ->
            if (!intent.hasExtra(name)) return@forEach
            @Suppress("DEPRECATION")
            return when (val value = intent.extras?.get(name)) {
                is Boolean -> value
                is String -> value.trim().toBooleanStrictOrNull() ?: value.trim().toIntOrNull()?.let { it != 0 } ?: false
                is Number -> value.toLong() != 0L
                else -> false
            }
        }
        return default
    }

    private fun loadBuiltinSkillGuidance(context: Context, skillId: String): String {
        val normalizedSkillId = skillId.trim()
        if (normalizedSkillId.isBlank() || !SAFE_SKILL_ID.matches(normalizedSkillId)) {
            return ""
        }
        val body = runCatching {
            context.assets.open("builtin_skills/$normalizedSkillId/SKILL.md")
                .bufferedReader()
                .use { it.readText() }
        }.getOrNull() ?: return ""
        return ResolvedSkillContext(
            skillId = normalizedSkillId,
            frontmatter = mapOf("name" to normalizedSkillId),
            bodyMarkdown = body,
            triggerReason = "debug_vlm_runlog"
        ).vlmStepGuidance()
    }

    companion object {
        private const val TAG = "DebugVlmRunLogReceiver"
        private val SAFE_SKILL_ID = Regex("""[A-Za-z0-9_-]+""")
        private val gson = GsonBuilder().disableHtmlEscaping().create()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
