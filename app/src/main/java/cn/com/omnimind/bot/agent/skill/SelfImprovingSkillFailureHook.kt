package cn.com.omnimind.bot.agent

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

data class FailureLearningHookPayload(
    val entryId: String,
    val logFile: File,
    val logShellPath: String? = null,
    val guidance: String,
    val relatedHints: List<String>
) {
    fun toPayload(): Map<String, Any?> {
        return linkedMapOf(
            "skillId" to SelfImprovingSkillFailureHook.SKILL_ID,
            "entryId" to entryId,
            "logPath" to (logShellPath ?: logFile.absolutePath),
            "guidance" to guidance,
            "relatedHints" to relatedHints
        )
    }
}

object SelfImprovingSkillFailureHook {
    const val SKILL_ID = "self-improving-agent"

    private const val ERRORS_FILE_NAME = "ERRORS.md"
    private const val ERRORS_HEADER = "# Errors\n"
    private const val MAX_FIELD_CHARS = 2000
    private const val MAX_GUIDANCE_CHARS = 1200

    fun resolveInstalledSkill(
        installedSkills: List<SkillIndexEntry>,
        skillLoader: SkillLoader
    ): ResolvedSkillContext? {
        val entry = installedSkills.firstOrNull { it.id == SKILL_ID } ?: return null
        val compatibility = SkillCompatibilityChecker.evaluate(entry)
        if (!compatibility.available) {
            return null
        }
        return skillLoader.load(entry, "失败后自动读取")
    }

    fun shouldHandle(result: ToolExecutionResult): Boolean {
        return when (result) {
            is ToolExecutionResult.Error -> true
            is ToolExecutionResult.TerminalResult -> !result.success
            is ToolExecutionResult.Interrupted -> false
            is ToolExecutionResult.ContextResult -> !result.success
            is ToolExecutionResult.MemoryResult -> !result.success
            is ToolExecutionResult.McpResult -> !result.success
            is ToolExecutionResult.ScheduleResult -> !result.success
            else -> false
        }
    }

    fun capture(
        skillsRoot: File,
        skill: ResolvedSkillContext,
        userMessage: String,
        toolName: String,
        toolType: String,
        argumentsJson: String?,
        result: ToolExecutionResult
    ): FailureLearningHookPayload? {
        return runCatching {
            val skillRoot = File(skillsRoot, SKILL_ID)
            val dataDir = File(skillRoot, "data").apply { mkdirs() }
            val errorsFile = File(dataDir, ERRORS_FILE_NAME)
            ensureErrorsFile(errorsFile)

            val entryId = newEntryId()
            val summary = truncateText(failureSummary(result), 160)
            val details = truncateText(failureDetails(result), MAX_FIELD_CHARS)
            val userGoal = truncateText(userMessage.trim(), 400)
            val argsBlock = truncateText(argumentsJson?.trim().orEmpty(), 800)
            val timestamp = Instant.now().toString()
            val block = buildString {
                appendLine("## [$entryId] $toolName")
                appendLine()
                appendLine("**记录时间**: $timestamp")
                appendLine("**优先级**: high")
                appendLine("**状态**: pending")
                appendLine("**领域**: runtime")
                appendLine()
                appendLine("### 摘要")
                appendLine(summary)
                appendLine()
                appendLine("### Error")
                appendLine("```")
                appendLine(details)
                appendLine("```")
                appendLine()
                appendLine("### Context")
                appendLine("- 用户目标: ${userGoal.ifBlank { "（空）" }}")
                appendLine("- 工具名称: $toolName")
                appendLine("- 工具类型: $toolType")
                appendLine("- 工具参数: ${argsBlock.ifBlank { "（空）" }}")
                appendLine()
                appendLine("### 建议修复")
                appendLine("（待补充）")
                appendLine()
                appendLine("### 元数据")
                appendLine("- 来源: auto_failure_hook")
                appendLine("- 作用域: skill")
                appendLine("- 关联技能: $SKILL_ID")
                appendLine()
                appendLine("---")
            }
            errorsFile.appendText(block)

            val relatedHints = relatedHints(errorsFile, toolName, entryId)
            val guidance = buildGuidance(skill, relatedHints)
            FailureLearningHookPayload(
                entryId = entryId,
                logFile = errorsFile,
                guidance = truncateText(guidance, MAX_GUIDANCE_CHARS),
                relatedHints = relatedHints
            )
        }.getOrNull()
    }

    private fun buildGuidance(
        skill: ResolvedSkillContext,
        relatedHints: List<String>
    ): String {
        val base = buildString {
            appendLine("self-improving-agent 已自动读取并记录本次失败。")
            appendLine(skill.stepGuidance(800))
            if (relatedHints.isNotEmpty()) {
                appendLine("相关历史：")
                relatedHints.forEach { hint ->
                    appendLine("- $hint")
                }
            }
            append("不要机械重复刚刚失败的同一步骤；先依据失败结果修正方案。")
            appendLine()
            append(
                "找到修复方法后，用 memory_write_daily 写一条“遇到X先Y”的简短规则" +
                    "（跨会话稳定则用 memory_upsert_longterm），并回填本条 ERRORS 的“建议修复”与状态。" +
                    "历史失败教训已纳入记忆检索，相关操作前会被自动召回。"
            )
        }.trim()
        return base
    }

    /**
     * Compact one-liners extracted from `data/ERRORS.md` for the memory
     * retrieval index, so past tool/environment failures surface proactively via
     * `memory_search` / prefetch — not only when the same tool fails again.
     * Format: `[toolName] summary` (plus ` → fix` once 建议修复 is filled).
     * Newest first, capped at [limit].
     */
    fun collectSearchableLessons(skillsRoot: File, limit: Int = 40): List<String> {
        return runCatching {
            val errorsFile = File(File(File(skillsRoot, SKILL_ID), "data"), ERRORS_FILE_NAME)
            if (!errorsFile.exists()) {
                return emptyList()
            }
            extractBlocks(errorsFile.readText())
                .asReversed()
                .mapNotNull { block -> lessonLineFromBlock(block) }
                .take(limit)
        }.getOrDefault(emptyList())
    }

    private fun lessonLineFromBlock(block: String): String? {
        val toolName = Regex("^## \\[[^\\]]+]\\s*(.*)$", RegexOption.MULTILINE)
            .find(block)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            .orEmpty()
        val summary = extractSectionFirstLine(block, "### 摘要")?.trim().orEmpty()
        if (summary.isBlank()) {
            return null
        }
        val fix = extractSectionFirstLine(block, "### 建议修复")
            ?.trim()
            ?.takeUnless { it.isBlank() || it == "（待补充）" }
        return buildString {
            if (toolName.isNotBlank()) {
                append("[").append(toolName).append("] ")
            }
            append(summary)
            if (fix != null) {
                append(" → ").append(fix)
            }
        }.trim().takeUnless { it.isBlank() }
    }

    private fun failureSummary(result: ToolExecutionResult): String {
        return when (result) {
            is ToolExecutionResult.Error -> result.message
            is ToolExecutionResult.TerminalResult -> result.summaryText
            is ToolExecutionResult.Interrupted -> result.summaryText
            is ToolExecutionResult.ContextResult -> result.summaryText
            is ToolExecutionResult.MemoryResult -> result.summaryText
            is ToolExecutionResult.McpResult -> result.summaryText
            is ToolExecutionResult.ScheduleResult -> result.summaryText
            else -> "工具调用失败"
        }.ifBlank { "工具调用失败" }
    }

    private fun failureDetails(result: ToolExecutionResult): String {
        return when (result) {
            is ToolExecutionResult.Error -> result.message
            is ToolExecutionResult.TerminalResult -> {
                result.rawResultJson.ifBlank {
                    result.terminalOutput.ifBlank { result.summaryText }
                }
            }
            is ToolExecutionResult.Interrupted -> {
                result.rawResultJson.ifBlank {
                    result.terminalOutput.ifBlank { result.summaryText }
                }
            }
            is ToolExecutionResult.ContextResult -> result.rawResultJson.ifBlank { result.summaryText }
            is ToolExecutionResult.MemoryResult -> result.rawResultJson.ifBlank { result.summaryText }
            is ToolExecutionResult.McpResult -> result.rawResultJson.ifBlank { result.summaryText }
            is ToolExecutionResult.ScheduleResult -> result.previewJson.ifBlank { result.summaryText }
            else -> "工具调用失败"
        }.ifBlank { "工具调用失败" }
    }

    private fun ensureErrorsFile(file: File) {
        file.parentFile?.mkdirs()
        if (!file.exists()) {
            file.writeText(ERRORS_HEADER)
        }
    }

    private fun newEntryId(): String {
        val date = DateTimeFormatter.BASIC_ISO_DATE
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())
        val suffix = UUID.randomUUID().toString()
            .replace("-", "")
            .take(3)
            .uppercase(Locale.US)
        return "ERR-$date-$suffix"
    }

    private fun relatedHints(
        errorsFile: File,
        toolName: String,
        currentEntryId: String,
        limit: Int = 2
    ): List<String> {
        val normalizedToolName = toolName.trim().lowercase(Locale.ROOT)
        if (normalizedToolName.isBlank() || !errorsFile.exists()) {
            return emptyList()
        }
        return extractBlocks(errorsFile.readText())
            .asReversed()
            .mapNotNull { block ->
                val entryId = Regex("^## \\[([^\\]]+)]", RegexOption.MULTILINE)
                    .find(block)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?: return@mapNotNull null
                if (entryId == currentEntryId) {
                    return@mapNotNull null
                }
                if (!block.lowercase(Locale.ROOT).contains(normalizedToolName)) {
                    return@mapNotNull null
                }
                val summary = extractSectionFirstLine(block, "### 摘要")
                    ?: return@mapNotNull null
                "$entryId $summary"
            }
            .take(limit)
    }

    private fun extractBlocks(content: String): List<String> {
        val matcher = Regex("^## \\[", RegexOption.MULTILINE)
            .findAll(content)
            .toList()
        if (matcher.isEmpty()) {
            return emptyList()
        }
        return matcher.mapIndexed { index, matchResult ->
            val start = matchResult.range.first
            val end = matcher.getOrNull(index + 1)?.range?.first ?: content.length
            content.substring(start, end).trim()
        }
    }

    private fun extractSectionFirstLine(
        block: String,
        heading: String
    ): String? {
        val lines = block.lines()
        val startIndex = lines.indexOfFirst { it.trim() == heading }
        if (startIndex < 0) {
            return null
        }
        for (index in startIndex + 1 until lines.size) {
            val line = lines[index].trim()
            if (line.isBlank()) {
                continue
            }
            if (line.startsWith("### ")) {
                return null
            }
            return line
        }
        return null
    }

    private fun truncateText(text: String, maxChars: Int): String {
        val normalized = text.replace("\r\n", "\n").trim()
        return if (normalized.length <= maxChars) {
            normalized
        } else {
            normalized.take(maxChars) + "\n..."
        }
    }
}
