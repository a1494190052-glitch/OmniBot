package cn.com.omnimind.bot.vlm

import android.content.Context
import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.assists.task.vlmserver.UIStep
import cn.com.omnimind.assists.task.vlmserver.VLMPostTaskHook
import cn.com.omnimind.assists.task.vlmserver.VLMSystemPromptRegistry
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class VlmGuidanceManager private constructor(private val appContext: Context) : VLMPostTaskHook {

    companion object {
        private const val TAG = "VlmGuidanceManager"

        const val GLOBAL_SKILL_ID = "vlm-guidance"
        const val APP_SKILL_PREFIX = "vlm-app-"
        const val FUNC_SKILL_PREFIX = "vlm-func-"

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

    // Detached scope — distillation must survive Task destruction
    private val distillScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Back-pressure: cap concurrent distillations to avoid unbounded coroutine accumulation
    private val distillSemaphore = Semaphore(8)

    // Per-skill mutex prevents concurrent writes corrupting the same file.
    // computeIfAbsent is atomic on ConcurrentHashMap; getOrPut is not.
    private val skillMutexes = ConcurrentHashMap<String, Mutex>()
    private fun mutexFor(skillId: String) = skillMutexes.computeIfAbsent(skillId) { Mutex() }

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

    suspend fun loadGuidance(packageName: String?, functionId: String? = null): String =
        withContext(Dispatchers.IO) {
            val manager = AgentWorkspaceManager(appContext)
            val parts = mutableListOf<String>()
            readSkillBody(manager, GLOBAL_SKILL_ID)?.let { parts.add(it) }
            if (!packageName.isNullOrBlank()) {
                readSkillBody(manager, APP_SKILL_PREFIX + sanitizePkg(packageName))?.let { parts.add(it) }
            }
            if (!functionId.isNullOrBlank()) {
                readSkillBody(manager, FUNC_SKILL_PREFIX + functionId)?.let { parts.add(it) }
            }
            parts.filter { it.isNotBlank() }.joinToString("\n\n").take(config.distillMaxSkillChars)
        }

    suspend fun loadFunctionsSection(packageName: String?): String =
        withContext(Dispatchers.IO) {
            val manager = AgentWorkspaceManager(appContext)
            buildList {
                readSkillBody(manager, GLOBAL_SKILL_ID)?.let { add(it) }
                if (!packageName.isNullOrBlank()) {
                    readSkillBody(manager, APP_SKILL_PREFIX + sanitizePkg(packageName))?.let { add(it) }
                }
            }.flatMap { body ->
                val start = body.indexOf("## Functions")
                if (start < 0) return@flatMap emptyList()
                val end = body.indexOf("\n##", start + 1).takeIf { it > 0 } ?: body.length
                body.substring(start, end).lines().drop(1).filter { it.trimStart().startsWith("-") }
            }.distinct().joinToString("\n").take(200)
        }

    // Called from VLMOperationTask via VLMPostTaskHookRegistry.
    // Fires distillation on distillScope so it outlives the Task coroutine.
    override suspend fun onTaskCompleted(
        goal: String,
        packageName: String?,
        executedFunctionId: String?,
        success: Boolean,
        executionTrace: List<UIStep>,
    ) {
        if (goal.isBlank() || executionTrace.isEmpty()) return

        val traceText = executionTrace.takeLast(6).mapNotNull { step ->
            val obs = step.observation.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val thought = step.thought.takeIf { it.isNotBlank() }
            if (thought != null) "${thought.take(60)} → ${obs.take(60)}"
            else obs.take(80)
        }.joinToString("; ")

        val goalSnap = goal
        val pkgSnap = packageName
        val funcSnap = executedFunctionId
        val traceSnap = traceText
        val successSnap = success

        if (!distillSemaphore.tryAcquire()) {
            OmniLog.d(TAG, "distill skipped for $pkgSnap: max concurrent distillations reached")
            return
        }
        distillScope.launch {
            try {
                val manager = AgentWorkspaceManager(appContext)
                if (successSnap && executionTrace.size >= config.distillMinTraceSteps) {
                    distillSkill(manager, GLOBAL_SKILL_ID, goalSnap, null, funcSnap, traceSnap, success = true)
                    if (!pkgSnap.isNullOrBlank()) {
                        distillSkill(manager, APP_SKILL_PREFIX + sanitizePkg(pkgSnap), goalSnap, pkgSnap, funcSnap, traceSnap, success = true)
                    }
                    if (!funcSnap.isNullOrBlank()) {
                        distillSkill(manager, FUNC_SKILL_PREFIX + funcSnap, goalSnap, pkgSnap, funcSnap, traceSnap, success = true)
                    }
                } else if (!successSnap) {
                    distillSkill(manager, GLOBAL_SKILL_ID, goalSnap, null, funcSnap, traceSnap, success = false)
                    if (!pkgSnap.isNullOrBlank()) {
                        distillSkill(manager, APP_SKILL_PREFIX + sanitizePkg(pkgSnap), goalSnap, pkgSnap, funcSnap, traceSnap, success = false)
                    }
                }
            } finally {
                distillSemaphore.release()
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
        success: Boolean,
    ) {
        mutexFor(skillId).withLock {
            val current = readSkillBody(manager, skillId) ?: ""
            val prompt = if (success) {
                buildDistillPrompt(skillId, goal, packageName, functionId, traceText, current)
            } else {
                buildFailureDistillPrompt(skillId, goal, packageName, traceText, current)
            }
            val result = runCatching {
                HttpController.postLLMRequest(config.distillModel, prompt)
                    .message
                    .trim()
                    .take(config.distillMaxSkillChars)
            }.onFailure { OmniLog.w(TAG, "distill failed for $skillId: ${it.message}") }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: return
            val written = runCatching {
                writeSkillBodyAtomic(manager, skillId, result)
                true
            }.onFailure { OmniLog.w(TAG, "atomic write failed for $skillId: ${it.message}") }
                .getOrDefault(false)
            if (written) OmniLog.d(TAG, "updated $skillId (${result.length} chars, success=$success)")
        }
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
        if (traceText.isNotBlank()) appendLine("执行摘要 (thought → observation): $traceText")
        appendLine()
        appendLine("## 现有策略 ($skillId)")
        appendLine(if (current.isBlank()) "(空)" else current)
        appendLine()
        appendLine("## 要求")
        appendLine("- 提炼0-2条新的通用策略合并进去；若无新洞察则保持原内容")
        appendLine("- 保持 ## Strategies 和 ## Functions 两个章节结构")
        appendLine("- ## Functions 章节记录已知有用的函数及其适用场景（格式: `- funcId: 场景`）")
        appendLine("- 总字数严格控制在 ${config.distillMaxSkillChars} 字以内")
        appendLine("- 直接输出更新后的 Markdown 内容，不要任何解释")
    }

    private fun buildFailureDistillPrompt(
        skillId: String,
        goal: String,
        packageName: String?,
        traceText: String,
        current: String,
    ): String = buildString {
        appendLine("你是 VLM planner 经验蒸馏器。将本次失败的洞察合并到现有策略中，输出更新后的内容。")
        appendLine()
        appendLine("## 本次失败执行")
        appendLine("目标: $goal")
        if (!packageName.isNullOrBlank()) appendLine("应用: $packageName")
        if (traceText.isNotBlank()) appendLine("执行摘要 (thought → observation): $traceText")
        appendLine()
        appendLine("## 现有策略 ($skillId)")
        appendLine(if (current.isBlank()) "(空)" else current)
        appendLine()
        appendLine("## 要求")
        appendLine("- 提炼0-1条避免规则写入 ## Failures 章节（如无新洞察则保持原内容）")
        appendLine("- 保持 ## Strategies、## Failures 和 ## Functions 章节结构")
        appendLine("- ## Failures 章节格式: `- [操作类型] 失败原因/避免事项`")
        appendLine("- 总字数严格控制在 ${config.distillMaxSkillChars} 字以内")
        appendLine("- 直接输出更新后的 Markdown 内容，不要任何解释")
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

    // Atomic write: write to a .tmp sibling then rename, so a crash mid-write
    // never leaves a truncated SKILL.md.
    private fun writeSkillBodyAtomic(manager: AgentWorkspaceManager, skillId: String, body: String) {
        val dir = manager.skillsRoot().resolve(skillId)
        dir.mkdirs()
        val target = dir.resolve("SKILL.md")
        val tmp = File(dir, "SKILL.md.tmp")
        tmp.writeText("---\nname: $skillId\ndescription: VLM planner guidance\n---\n$body")
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("Failed to atomically write SKILL.md for $skillId")
        }
    }

    private fun sanitizePkg(pkg: String) = pkg.replace(".", "_").take(40)
}
