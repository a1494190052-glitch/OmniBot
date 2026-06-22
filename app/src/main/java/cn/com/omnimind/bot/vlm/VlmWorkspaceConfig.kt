package cn.com.omnimind.bot.vlm

import android.content.Context
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class VlmWorkspaceConfig private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "VlmWorkspaceConfig"
        private const val CONFIG_FILE = "vlm_config.json"

        const val DEFAULT_PRIMARY_MODEL = "scene.vlm.operation.primary"
        const val DEFAULT_RECALL_MODEL = "scene.vlm.operation.primary"
        const val DEFAULT_DISTILL_MODEL = "scene.memory.rollup"

        // Safety bounds — Kotlin's last line of defense regardless of file content
        private val ALLOWED_TOOLS = setOf(
            "click", "long_press", "input_text", "swipe", "open_app",
            "press_key", "wait", "finished", "info", "feedback",
            "abort", "require_user_choice", "require_user_confirmation",
        )

        private val CONFIG_SEED = """
            {
              "primary_model": "$DEFAULT_PRIMARY_MODEL",
              "recall_selector_model": "$DEFAULT_RECALL_MODEL",
              "distill_model": "$DEFAULT_DISTILL_MODEL",
              "recall_enabled": true,
              "recall_max_candidates": 3,
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
    }

    @Serializable
    private data class ConfigFile(
        val primary_model: String = DEFAULT_PRIMARY_MODEL,
        val recall_selector_model: String = DEFAULT_RECALL_MODEL,
        val distill_model: String = DEFAULT_DISTILL_MODEL,
        val recall_enabled: Boolean = true,
        val recall_max_candidates: Int = 3,
        val distill_min_trace_steps: Int = 2,
        val distill_max_skill_chars: Int = 400,
        val disabled_tools: List<String> = emptyList(),
    )

    data class Snapshot(
        val primaryModel: String,
        val recallSelectorModel: String,
        val distillModel: String,
        val recallEnabled: Boolean,
        val recallMaxCandidates: Int,
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
        val recallModel = raw.recall_selector_model.trim().ifBlank { DEFAULT_RECALL_MODEL }
        val distillModel = raw.distill_model.trim().ifBlank { DEFAULT_DISTILL_MODEL }
        val maxCandidates = raw.recall_max_candidates.coerceIn(1, 10)
        val minSteps = raw.distill_min_trace_steps.coerceIn(1, 10)
        val maxChars = raw.distill_max_skill_chars.coerceIn(100, 1200)
        val denied = raw.disabled_tools.map { it.trim() }.filter { it in ALLOWED_TOOLS }.toSet()

        if (maxCandidates != raw.recall_max_candidates)
            OmniLog.w(TAG, "recall_max_candidates clamped to $maxCandidates")
        if (minSteps != raw.distill_min_trace_steps)
            OmniLog.w(TAG, "distill_min_trace_steps clamped to $minSteps")
        if (maxChars != raw.distill_max_skill_chars)
            OmniLog.w(TAG, "distill_max_skill_chars clamped to $maxChars")

        return Snapshot(
            primaryModel = primaryModel,
            recallSelectorModel = recallModel,
            distillModel = distillModel,
            recallEnabled = raw.recall_enabled,
            recallMaxCandidates = maxCandidates,
            distillMinTraceSteps = minSteps,
            distillMaxSkillChars = maxChars,
            disabledTools = denied,
        )
    }

    private fun defaultSnapshot() = Snapshot(
        primaryModel = DEFAULT_PRIMARY_MODEL,
        recallSelectorModel = DEFAULT_RECALL_MODEL,
        distillModel = DEFAULT_DISTILL_MODEL,
        recallEnabled = true,
        recallMaxCandidates = 3,
        distillMinTraceSteps = 2,
        distillMaxSkillChars = 400,
        disabledTools = emptySet(),
    )
}
