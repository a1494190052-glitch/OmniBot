package cn.com.omnimind.bot.vlm

import android.content.Context
import cn.com.omnimind.assists.task.vlmserver.VLMRuntimeConfig
import cn.com.omnimind.assists.task.vlmserver.VLMRuntimeConfigRegistry
import cn.com.omnimind.assists.task.vlmserver.VLMToolDenylistRegistry
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class VlmWorkspaceConfig private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "VlmWorkspaceConfig"
        private const val CONFIG_FILE = "vlm_config.json"

        const val DEFAULT_PRIMARY_MODEL = "scene.vlm.operation.primary"
        const val DEFAULT_DISTILL_MODEL = "scene.memory.rollup"

        // Safety bounds — Kotlin's last line of defense regardless of file content
        private val ALLOWED_TOOLS = setOf(
            "click", "long_press", "input_text", "swipe", "open_app",
            "press_key", "wait", "finished", "info", "abort",
        )

        private val CONFIG_SEED = """
            {
              "primary_model": "$DEFAULT_PRIMARY_MODEL",
              "distill_model": "$DEFAULT_DISTILL_MODEL",
              "vlm_max_completion_tokens": 384,
              "vlm_image_mode": "always",
              "vlm_temperature": 0.2,
              "vlm_history_rounds": 4,
              "vlm_history_action_chars": 160,
              "vlm_history_result_chars": 220,
              "vlm_tool_result_chars": 900,
              "vlm_default_max_steps": 12,
              "vlm_min_wait_timeout_ms": 30000,
              "vlm_max_wait_timeout_ms": 600000,
              "vlm_dry_run_prompt_preview_chars": 6000,
              "recall_enabled": true,
              "recall_max_candidates": 3,
              "recall_max_tools_per_step": 3,
              "recall_decision_mode": "context_only",
              "recall_tool_name_prefix": "run_recalled_workflow",
              "recall_description_chars": 220,
              "recall_step_summary_count": 2,
              "recall_step_summary_chars": 180,
              "recall_tool_description_chars": 520,
              "distill_min_trace_steps": 2,
              "distill_max_skill_chars": 400,
              "disabled_tools": []
            }
        """.trimIndent()

        @Volatile
        private var _instance: VlmWorkspaceConfig? = null

        fun getInstance(context: Context): VlmWorkspaceConfig =
            _instance ?: synchronized(this) {
                _instance ?: VlmWorkspaceConfig(context.applicationContext).also { _instance = it }
            }

        fun defaultSnapshot(): Snapshot = buildDefaultSnapshot()

        fun defaultSnapshotForTests(): Snapshot = defaultSnapshot()

        private fun buildDefaultSnapshot() = Snapshot(
            primaryModel = DEFAULT_PRIMARY_MODEL,
            distillModel = DEFAULT_DISTILL_MODEL,
            vlmMaxCompletionTokens = 384,
            vlmImageMode = "always",
            vlmTemperature = 0.2,
            vlmHistoryRounds = 4,
            vlmHistoryActionChars = 160,
            vlmHistoryResultChars = 220,
            vlmToolResultChars = 900,
            vlmDefaultMaxSteps = 12,
            vlmMinWaitTimeoutMs = 30_000L,
            vlmMaxWaitTimeoutMs = 600_000L,
            vlmDryRunPromptPreviewChars = 6000,
            recallEnabled = true,
            recallMaxCandidates = 3,
            recallMaxToolsPerStep = 3,
            recallDecisionMode = "context_only",
            recallToolNamePrefix = "run_recalled_workflow",
            recallDescriptionChars = 220,
            recallStepSummaryCount = 2,
            recallStepSummaryChars = 180,
            recallToolDescriptionChars = 520,
            distillMinTraceSteps = 2,
            distillMaxSkillChars = 400,
            disabledTools = emptySet(),
        )
    }

    @Serializable
    private data class ConfigFile(
        val primary_model: String = DEFAULT_PRIMARY_MODEL,
        val distill_model: String = DEFAULT_DISTILL_MODEL,
        val vlm_max_completion_tokens: Int = 384,
        val vlm_image_mode: String = "always",
        val vlm_temperature: Double = 0.2,
        val vlm_history_rounds: Int = 4,
        val vlm_history_action_chars: Int = 160,
        val vlm_history_result_chars: Int = 220,
        val vlm_tool_result_chars: Int = 900,
        val vlm_default_max_steps: Int = 12,
        val vlm_min_wait_timeout_ms: Long = 30_000L,
        val vlm_max_wait_timeout_ms: Long = 600_000L,
        val vlm_dry_run_prompt_preview_chars: Int = 6000,
        val recall_enabled: Boolean = true,
        val recall_max_candidates: Int = 3,
        val recall_max_tools_per_step: Int = 3,
        val recall_decision_mode: String = "context_only",
        val recall_tool_name_prefix: String = "run_recalled_workflow",
        val recall_description_chars: Int = 220,
        val recall_step_summary_count: Int = 2,
        val recall_step_summary_chars: Int = 180,
        val recall_tool_description_chars: Int = 520,
        val distill_min_trace_steps: Int = 2,
        val distill_max_skill_chars: Int = 400,
        val disabled_tools: List<String> = emptyList(),
    )

    data class Snapshot(
        val primaryModel: String,
        val distillModel: String,
        val vlmMaxCompletionTokens: Int,
        val vlmImageMode: String,
        val vlmTemperature: Double,
        val vlmHistoryRounds: Int,
        val vlmHistoryActionChars: Int,
        val vlmHistoryResultChars: Int,
        val vlmToolResultChars: Int,
        val vlmDefaultMaxSteps: Int,
        val vlmMinWaitTimeoutMs: Long,
        val vlmMaxWaitTimeoutMs: Long,
        val vlmDryRunPromptPreviewChars: Int,
        val recallEnabled: Boolean,
        val recallMaxCandidates: Int,
        val recallMaxToolsPerStep: Int,
        val recallDecisionMode: String,
        val recallToolNamePrefix: String,
        val recallDescriptionChars: Int,
        val recallStepSummaryCount: Int,
        val recallStepSummaryChars: Int,
        val recallToolDescriptionChars: Int,
        val distillMinTraceSteps: Int,
        val distillMaxSkillChars: Int,
        val disabledTools: Set<String>,
    )

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var snapshot = defaultSnapshot()
    @Volatile private var lastModified = 0L

    fun get(): Snapshot = snapshot

    fun initialize() {
        seedConfigTemplate()
        reloadIfChanged()
        syncRuntimeConfig(snapshot)
    }

    fun reloadIfChanged() {
        runCatching {
            val file = AgentWorkspaceManager(appContext).agentDirectory().resolve(CONFIG_FILE)
            if (!file.exists()) return
            val modified = file.lastModified()
            if (modified == lastModified) return
            val parsed = json.decodeFromString<ConfigFile>(file.readText())
            snapshot = validate(parsed)
            lastModified = modified
            syncRuntimeConfig(snapshot)
            OmniLog.d(TAG, "reloaded $CONFIG_FILE")
        }.onFailure { OmniLog.w(TAG, "failed to reload $CONFIG_FILE: ${it.message}") }
    }

    private fun seedConfigTemplate() {
        runCatching {
            val file = AgentWorkspaceManager(appContext).agentDirectory().resolve(CONFIG_FILE)
            if (!file.exists()) {
                file.writeText(CONFIG_SEED)
                OmniLog.d(TAG, "seeded $CONFIG_FILE")
            }
        }.onFailure { OmniLog.w(TAG, "failed to seed $CONFIG_FILE: ${it.message}") }
    }

    private fun validate(raw: ConfigFile): Snapshot {
        val primaryModel = raw.primary_model.trim().ifBlank { DEFAULT_PRIMARY_MODEL }
        val distillModel = raw.distill_model.trim().ifBlank { DEFAULT_DISTILL_MODEL }
        val maxCompletionTokens = raw.vlm_max_completion_tokens.coerceIn(64, 2048)
        val imageMode = raw.vlm_image_mode.trim().takeIf { it in setOf("always", "auto") } ?: "always"
        val temperature = raw.vlm_temperature.coerceIn(0.0, 2.0)
        val historyRounds = raw.vlm_history_rounds.coerceIn(0, 12)
        val historyActionChars = raw.vlm_history_action_chars.coerceIn(40, 1000)
        val historyResultChars = raw.vlm_history_result_chars.coerceIn(40, 2000)
        val toolResultChars = raw.vlm_tool_result_chars.coerceIn(120, 4000)
        val defaultMaxSteps = raw.vlm_default_max_steps.coerceIn(1, 64)
        val minWaitTimeoutMs = raw.vlm_min_wait_timeout_ms.coerceIn(5_000L, 600_000L)
        val maxWaitTimeoutMs = raw.vlm_max_wait_timeout_ms.coerceIn(minWaitTimeoutMs, 1_800_000L)
        val dryRunPromptPreviewChars = raw.vlm_dry_run_prompt_preview_chars.coerceIn(500, 30_000)
        val maxCandidates = raw.recall_max_candidates.coerceIn(1, 10)
        val maxRecallTools = raw.recall_max_tools_per_step.coerceIn(0, 10)
        val recallDecisionMode = raw.recall_decision_mode.trim().ifBlank { "context_only" }
        val recallToolNamePrefix = sanitizeToolNamePrefix(raw.recall_tool_name_prefix)
        val recallDescriptionChars = raw.recall_description_chars.coerceIn(40, 1000)
        val recallStepSummaryCount = raw.recall_step_summary_count.coerceIn(0, 5)
        val recallStepSummaryChars = raw.recall_step_summary_chars.coerceIn(40, 1000)
        val recallToolDescriptionChars = raw.recall_tool_description_chars.coerceIn(120, 2000)
        val minSteps = raw.distill_min_trace_steps.coerceIn(1, 10)
        val maxChars = raw.distill_max_skill_chars.coerceIn(100, 1200)
        val denied = raw.disabled_tools.map { it.trim() }.filter { it in ALLOWED_TOOLS }.toSet()

        if (maxCandidates != raw.recall_max_candidates)
            OmniLog.w(TAG, "recall_max_candidates clamped to $maxCandidates")
        if (maxRecallTools != raw.recall_max_tools_per_step)
            OmniLog.w(TAG, "recall_max_tools_per_step clamped to $maxRecallTools")
        if (minSteps != raw.distill_min_trace_steps)
            OmniLog.w(TAG, "distill_min_trace_steps clamped to $minSteps")
        if (maxChars != raw.distill_max_skill_chars)
            OmniLog.w(TAG, "distill_max_skill_chars clamped to $maxChars")

        return Snapshot(
            primaryModel = primaryModel,
            distillModel = distillModel,
            vlmMaxCompletionTokens = maxCompletionTokens,
            vlmImageMode = imageMode,
            vlmTemperature = temperature,
            vlmHistoryRounds = historyRounds,
            vlmHistoryActionChars = historyActionChars,
            vlmHistoryResultChars = historyResultChars,
            vlmToolResultChars = toolResultChars,
            vlmDefaultMaxSteps = defaultMaxSteps,
            vlmMinWaitTimeoutMs = minWaitTimeoutMs,
            vlmMaxWaitTimeoutMs = maxWaitTimeoutMs,
            vlmDryRunPromptPreviewChars = dryRunPromptPreviewChars,
            recallEnabled = raw.recall_enabled,
            recallMaxCandidates = maxCandidates,
            recallMaxToolsPerStep = maxRecallTools,
            recallDecisionMode = recallDecisionMode,
            recallToolNamePrefix = recallToolNamePrefix,
            recallDescriptionChars = recallDescriptionChars,
            recallStepSummaryCount = recallStepSummaryCount,
            recallStepSummaryChars = recallStepSummaryChars,
            recallToolDescriptionChars = recallToolDescriptionChars,
            distillMinTraceSteps = minSteps,
            distillMaxSkillChars = maxChars,
            disabledTools = denied,
        )
    }

    private fun syncRuntimeConfig(snapshot: Snapshot) {
        VLMRuntimeConfigRegistry.set(
            VLMRuntimeConfig(
                primarySceneId = snapshot.primaryModel,
                maxCompletionTokens = snapshot.vlmMaxCompletionTokens,
                imageMode = snapshot.vlmImageMode,
                temperature = snapshot.vlmTemperature,
                defaultMaxSteps = snapshot.vlmDefaultMaxSteps,
                maxHistoryRounds = snapshot.vlmHistoryRounds,
                maxHistoryActionChars = snapshot.vlmHistoryActionChars,
                maxHistoryResultChars = snapshot.vlmHistoryResultChars,
                maxToolResultChars = snapshot.vlmToolResultChars,
            )
        )
        VLMToolDenylistRegistry.set(snapshot.disabledTools)
    }

    private fun sanitizeToolNamePrefix(raw: String): String {
        val sanitized = raw.trim()
            .lowercase()
            .replace(Regex("[^a-z0-9_]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
        return sanitized.takeIf { it.matches(Regex("[a-z][a-z0-9_]{0,48}")) }
            ?: "run_recalled_workflow"
    }
}
