package cn.com.omnimind.bot.vlm

import android.content.Context
import cn.com.omnimind.assists.task.vlmserver.VLMSystemPromptRegistry
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VlmGuidanceManager private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "VlmGuidanceManager"

        const val GLOBAL_SKILL_ID = "vlm-guidance"
        const val APP_SKILL_PREFIX = "vlm-app-"

        private const val VLM_STRATEGIES_FILE = "vlm_strategies.md"

        private val STRATEGIES_SEED = """
            # VLM Planner 自定义策略
            # 编辑此文件可在不更新 App 的情况下调整 VLM 规划行为。
            # 此内容会追加到 VLM 的 system prompt 末尾。
            #
            # 示例：
            # - 遇到广告弹窗立即关闭，不要等待
            # - 输入前先点击输入框确认已聚焦
        """.trimIndent()

        @Volatile
        private var _instance: VlmGuidanceManager? = null

        fun getInstance(context: Context): VlmGuidanceManager =
            _instance ?: synchronized(this) {
                _instance ?: VlmGuidanceManager(context.applicationContext).also { _instance = it }
            }
    }

    private val config get() = VlmWorkspaceConfig.getInstance(appContext).get()

    @Volatile
    private var strategiesLastModified = 0L

    fun initialize() {
        VlmWorkspaceConfig.getInstance(appContext).initialize()
        loadSystemPromptIfChanged()
        seedStrategiesTemplate()
    }

    suspend fun reloadSystemPrompt() {
        withContext(Dispatchers.IO) { loadSystemPromptIfChanged() }
    }

    private fun loadSystemPromptIfChanged() {
        runCatching {
            val file = AgentWorkspaceManager(appContext).agentDirectory().resolve(VLM_STRATEGIES_FILE)
            if (!file.exists()) return
            val modified = file.lastModified()
            if (modified == strategiesLastModified) return
            val content = file.readText().trim()
                .lines()
                .filterNot { it.trimStart().startsWith("#") }
                .joinToString("\n")
                .trim()
            VLMSystemPromptRegistry.set(content.takeIf { it.isNotBlank() })
            strategiesLastModified = modified
            OmniLog.d(TAG, "reloaded $VLM_STRATEGIES_FILE (${content.length} chars)")
        }.onFailure { OmniLog.w(TAG, "failed to load $VLM_STRATEGIES_FILE: ${it.message}") }
    }

    private fun seedStrategiesTemplate() {
        runCatching {
            val file = AgentWorkspaceManager(appContext).agentDirectory().resolve(VLM_STRATEGIES_FILE)
            if (!file.exists()) {
                file.writeText(STRATEGIES_SEED)
                OmniLog.d(TAG, "seeded $VLM_STRATEGIES_FILE")
            }
        }.onFailure { OmniLog.w(TAG, "failed to seed $VLM_STRATEGIES_FILE: ${it.message}") }
    }

    suspend fun loadGuidance(packageName: String?): String =
        withContext(Dispatchers.IO) {
            val manager = AgentWorkspaceManager(appContext)
            val parts = mutableListOf<String>()
            readSkillBody(manager, GLOBAL_SKILL_ID)?.let { parts.add(it) }
            if (!packageName.isNullOrBlank()) {
                readSkillBody(manager, APP_SKILL_PREFIX + sanitizePkg(packageName))?.let { parts.add(it) }
            }
            parts.filter { it.isNotBlank() }.joinToString("\n\n").take(config.distillMaxSkillChars)
        }

    private fun readSkillBody(manager: AgentWorkspaceManager, skillId: String): String? {
        val file = manager.skillsRoot().resolve("$skillId/SKILL.md")
        // Skip exists() check — just try reading; avoids TOCTOU race between check and read
        val content = runCatching { file.readText() }.getOrNull() ?: return null
        val lines = content.lines()
        if (lines.firstOrNull()?.trim() != "---") return content.trim()
        val endIdx = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (endIdx < 0) return content.trim()
        return lines.drop(endIdx + 2).joinToString("\n").trim().takeIf { it.isNotBlank() }
    }

    private fun sanitizePkg(pkg: String) = pkg.replace(".", "_").take(40)
}
