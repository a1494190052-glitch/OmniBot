package cn.com.omnimind.bot.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentAiCapabilityConfigSync
import cn.com.omnimind.bot.agent.ResolvedSkillContext
import cn.com.omnimind.bot.manager.AssistsCoreManager
import cn.com.omnimind.bot.mcp.VlmTaskRequest
import cn.com.omnimind.bot.runlog.OobOmniFlowToolkitService
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
        val disableOmniFlowRecall = intent.readBooleanExtra(
            "disableOmniFlowRecall",
            "disable_omniflow_recall",
            "disableRecall",
            "disable_recall"
        )
        val parseOnly = intent.readBooleanExtra("parseOnly", "parse_only", "dryRun", "dry_run")
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
                    disableOmniFlowRecall,
                    parseOnly,
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
        disableOmniFlowRecall: Boolean,
        parseOnly: Boolean,
    ): Map<String, Any?> {
        if (!AssistsUtil.Core.isInitialized()) {
            AssistsUtil.Core.initCore(context)
        }
        val configuredBinding = configureVlmBindingIfRequested(context, profileId, modelId)
        waitForAccessibility()

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
                    disableOmniFlowRecall = disableOmniFlowRecall,
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
                "disable_omniflow_recall" to disableOmniFlowRecall,
                "wait_timeout_ms" to waitTimeoutMs,
                "step_skill_guidance_chars" to stepSkillGuidance.length,
                "configured_binding" to configuredBinding,
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
                disableOmniFlowRecall = disableOmniFlowRecall,
            ),
            scope = scope,
        )
        val runId = outcome.taskId
        val record = InternalRunLogStore.getRun(context, runId)
        val timeline = record?.let { InternalRunLogStore.timelinePayload(context, runId) }
        val convert = if (outcome.status == VlmToolOutcomeStatus.FINISHED && record?.success == true) {
            OobOmniFlowToolkitService(context).convertRunLog(
                mapOf(
                    "run_id" to runId,
                    "register" to register,
                    "function_id" to "debug_${runId.replace('-', '_')}",
                    "name" to "Debug VLM RunLog",
                    "description" to goal,
                )
            )
        } else {
            null
        }

        val outcomePayload = outcome.toPayload()
        val vlmTaskFinished = outcome.status == VlmToolOutcomeStatus.FINISHED
        val runLogSuccessful = record?.success == true

        return linkedMapOf(
            "success" to vlmTaskFinished,
            "goal" to goal,
            "packageName" to targetPackageName,
            "prelaunchPackageName" to prelaunchPackageName,
            "prelaunch" to prelaunch,
            "startFromCurrent" to startFromCurrent,
            "skipGoHome" to skipGoHome,
            "disable_omniflow_recall" to disableOmniFlowRecall,
            "wait_timeout_ms" to waitTimeoutMs,
            "step_skill_guidance_chars" to stepSkillGuidance.length,
            "configured_binding" to configuredBinding,
            "outcome" to outcomePayload,
            "vlm_task_finished" to vlmTaskFinished,
            "run_id" to runId,
            "runlog_found" to (record != null),
            "runlog_success" to runLogSuccessful,
            "runlog_card_count" to (record?.cards?.size ?: 0),
            "run_log" to timeline,
            "token_usage" to (timeline?.get("token_usage") ?: emptyMap<String, Any?>()),
            "token_usage_total" to timeline?.get("token_usage_total"),
            "token_usage_by_step" to (timeline?.get("token_usage_by_step") ?: emptyList<Map<String, Any?>>()),
            "token_usage_by_call" to (timeline?.get("token_usage_by_call") ?: emptyList<Map<String, Any?>>()),
            "convert" to convert,
            "convert_success" to (convert?.get("success") == true),
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

    private fun Intent?.readBooleanExtra(vararg names: String): Boolean {
        val intent = this ?: return false
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
        return false
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
        ).stepGuidance()
    }

    companion object {
        private const val TAG = "DebugVlmRunLogReceiver"
        private val SAFE_SKILL_ID = Regex("""[A-Za-z0-9_-]+""")
        private val gson = GsonBuilder().disableHtmlEscaping().create()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
