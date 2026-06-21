package cn.com.omnimind.bot.vlm

import android.content.Context
import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.assists.task.vlmserver.UIStep
import cn.com.omnimind.assists.task.vlmserver.VLMPostTaskHook
import cn.com.omnimind.assists.task.vlmserver.VLMSystemPromptRegistry
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VlmGuidanceManager private constructor(private val appContext: Context) : VLMPostTaskHook {

    companion object {
        private const val TAG = "VlmGuidanceManager"
        private const val GLOBAL_SKILL_ID = "vlm-guidance"
        private const val APP_SKILL_PREFIX = "vlm-app-"
        private const val SCENE_DISTILL = "scene.memory.rollup"
        private const val MAX_SKILL_CHARS = 400
        private const val VLM_STRATEGIES_FILE = "vlm_strategies.md"

        @Volatile
        private var _instance: VlmGuidanceManager? = null

        fun getInstance(context: Context): VlmGuidanceManager =
            _instance ?: synchronized(this) {
                _instance ?: VlmGuidanceManager(context.applicationContext).also { _instance = it }
            }
    }

    fun initialize() {
        runCatching {
            val file = AgentWorkspaceManager(appContext).agentDirectory().resolve(VLM_STRATEGIES_FILE)
            val content = file.takeIf { it.exists() }?.readText()?.trim()
            VLMSystemPromptRegistry.set(content)
            if (content != null) OmniLog.d(TAG, "loaded vlm_strategies.md (${content.length} chars)")
        }.onFailure { OmniLog.w(TAG, "failed to load $VLM_STRATEGIES_FILE: ${it.message}") }
    }

    fun loadGuidance(packageName: String?): String {
        val manager = AgentWorkspaceManager(appContext)
        val parts = mutableListOf<String>()

        readSkillBody(manager, GLOBAL_SKILL_ID)?.let { parts.add(it) }
        if (!packageName.isNullOrBlank()) {
            readSkillBody(manager, APP_SKILL_PREFIX + sanitizePkg(packageName))?.let { parts.add(it) }
        }

        return parts.filter { it.isNotBlank() }.joinToString("\n\n").take(480)
    }

    override suspend fun onTaskCompleted(
        goal: String,
        packageName: String?,
        executedFunctionId: String?,
        success: Boolean,
        executionTrace: List<UIStep>,
    ) {
        if (!success || goal.isBlank()) return
        withContext(Dispatchers.IO) {
            val manager = AgentWorkspaceManager(appContext)
            val traceText = executionTrace.takeLast(6)
                .mapNotNull { step ->
                    val obs = step.observation?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    obs.take(80)
                }
                .joinToString("; ")

            distillSkill(manager, GLOBAL_SKILL_ID, goal, null, executedFunctionId, traceText)
            if (!packageName.isNullOrBlank()) {
                distillSkill(
                    manager,
                    APP_SKILL_PREFIX + sanitizePkg(packageName),
                    goal, packageName, executedFunctionId, traceText,
                )
            }
        }
    }

    private suspend fun distillSkill(
        manager: AgentWorkspaceManager,
        skillId: String,
        goal: String,
        packageName: String?,
        functionId: String?,
        traceText: String,
    ) {
        val current = readSkillBody(manager, skillId) ?: ""
        val prompt = buildDistillPrompt(skillId, goal, packageName, functionId, traceText, current)
        val result = runCatching {
            HttpController.postLLMRequest(SCENE_DISTILL, prompt, maxTokens = MAX_SKILL_CHARS).message.trim()
        }.onFailure { OmniLog.w(TAG, "distill failed for $skillId: ${it.message}") }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: return

        writeSkillBody(manager, skillId, result)
        OmniLog.d(TAG, "updated skill $skillId (${result.length} chars)")
    }

    private fun buildDistillPrompt(
        skillId: String,
        goal: String,
        packageName: String?,
        functionId: String?,
        traceText: String,
        current: String,
    ): String = buildString {
        appendLine("你是 VLM planner 经验蒸馏器。将本次执行的洞察合并到现有策略中，输出更新后的内容。")
        appendLine()
        appendLine("## 本次执行")
        appendLine("目标: $goal")
        if (!packageName.isNullOrBlank()) appendLine("应用: $packageName")
        if (!functionId.isNullOrBlank()) appendLine("使用的函数: $functionId")
        if (traceText.isNotBlank()) appendLine("执行摘要: $traceText")
        appendLine()
        appendLine("## 现有策略内容 (${skillId})")
        appendLine(if (current.isBlank()) "(空)" else current)
        appendLine()
        appendLine("## 要求")
        appendLine("- 提炼0-2条新的通用策略合并进去")
        appendLine("- 若无新洞察则保持原内容")
        appendLine("- 保持 ## Strategies 和 ## Functions 两个章节结构")
        appendLine("- ## Functions 章节记录已知有用的函数ID及其适用场景（格式: `- funcId: 场景`）")
        appendLine("- 总字数严格控制在 $MAX_SKILL_CHARS 字以内")
        appendLine("- 直接输出更新后的 Markdown 内容，不要任何解释")
    }

    private fun readSkillBody(manager: AgentWorkspaceManager, skillId: String): String? {
        val file = manager.skillsRoot().resolve("$skillId/SKILL.md")
        if (!file.exists()) return null
        val content = file.readText()
        val lines = content.lines()
        if (lines.firstOrNull()?.trim() != "---") return content.trim()
        val endIdx = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (endIdx < 0) return content.trim()
        return lines.drop(endIdx + 2).joinToString("\n").trim().takeIf { it.isNotBlank() }
    }

    private fun writeSkillBody(manager: AgentWorkspaceManager, skillId: String, body: String) {
        val dir = manager.skillsRoot().resolve(skillId)
        dir.mkdirs()
        dir.resolve("SKILL.md").writeText(
            "---\nname: $skillId\ndescription: VLM planner guidance\n---\n$body"
        )
    }

    private fun sanitizePkg(pkg: String) = pkg.replace(".", "_").take(40)
}
