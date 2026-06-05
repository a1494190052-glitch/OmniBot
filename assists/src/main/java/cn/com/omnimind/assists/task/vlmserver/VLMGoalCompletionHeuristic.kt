package cn.com.omnimind.assists.task.vlmserver

object VLMGoalCompletionHeuristic {
    data class Match(
        val target: String,
        val keyword: String,
    )

    fun match(goal: String, step: UIStep): Match? {
        if (step.result?.startsWith("执行失败") == true) return null
        val targets = completionTargets(goal)
        if (targets.isEmpty()) return null
        val pageText = normalizeForMatch(
            listOfNotNull(
                step.afterObservationXml,
                step.afterPackageName,
                step.result,
                step.summary,
            ).joinToString(" ")
        )
        if (pageText.isBlank()) return null
        targets.forEach { target ->
            keywordsForTarget(target).forEach { keyword ->
                if (keyword.isNotBlank() && pageText.contains(keyword)) {
                    return Match(target = target, keyword = keyword)
                }
            }
        }
        return null
    }

    fun buildFinishedStep(source: UIStep, match: Match): UIStep {
        val now = System.currentTimeMillis()
        val message = "当前页面已满足完成条件：${match.target}"
        return UIStep(
            observation = "GOAL_PAGE_MATCHED",
            thought = message,
            action = FinishedAction(content = message),
            result = message,
            summary = message,
            observationXml = source.afterObservationXml ?: source.observationXml,
            afterObservationXml = source.afterObservationXml,
            packageName = source.afterPackageName ?: source.packageName,
            afterPackageName = source.afterPackageName,
            startedAtMs = now,
            finishedAtMs = now,
            pageDiagnostics = source.pageDiagnostics + linkedMapOf(
                "vlm_goal_completion" to "matched",
                "vlm_goal_completion_target" to match.target,
                "vlm_goal_completion_keyword" to match.keyword,
            )
        )
    }

    internal fun completionTargets(goal: String): List<String> {
        val text = goal.trim()
        if (!hasExplicitCompletionCondition(text)) return emptyList()
        val targets = linkedSetOf<String>()
        COMPLETION_PREFIXES.forEach { prefix ->
            COMPLETION_SUFFIXES.forEach { suffix ->
                extractBetween(text, prefix, suffix)?.let { targets += it }
            }
        }
        return targets
            .map(::cleanTarget)
            .filter(::isUsefulTarget)
            .distinct()
            .take(MAX_TARGETS)
    }

    private fun hasExplicitCompletionCondition(text: String): Boolean {
        if (!text.contains("完成")) return false
        return COMPLETION_PREFIXES.any(text::contains) &&
            COMPLETION_SUFFIXES.any(text::contains)
    }

    private fun extractBetween(text: String, prefix: String, suffix: String): String? {
        val start = text.indexOf(prefix)
        if (start < 0) return null
        val bodyStart = start + prefix.length
        val end = text.indexOf(suffix, bodyStart)
        if (end <= bodyStart) return null
        return text.substring(bodyStart, end).take(MAX_RAW_TARGET_CHARS)
    }

    private fun cleanTarget(value: String): String =
        value.trim()
            .trim('，', ',', '。', '.', '；', ';', '：', ':', ' ', '\n', '\t')
            .removePrefix("当前")
            .removePrefix("已经")
            .removePrefix("已")
            .removeSuffix("相关")
            .trim()

    private fun isUsefulTarget(value: String): Boolean {
        if (value.length >= 2) return true
        return value.any { it.code > 127 }
    }

    private fun keywordsForTarget(target: String): List<String> {
        val normalized = normalizeForMatch(target)
        val values = linkedSetOf<String>()
        if (normalized.isNotBlank()) values += normalized
        if (target.contains("蓝牙", ignoreCase = true)) values += "bluetooth"
        if (target.contains("设置", ignoreCase = true)) values += "settings"
        if (
            target.contains("无线", ignoreCase = true) ||
            target.contains("wifi", ignoreCase = true) ||
            target.contains("wi-fi", ignoreCase = true)
        ) {
            values += "wifi"
            values += "wi-fi"
        }
        return values.toList()
    }

    private fun normalizeForMatch(value: String): String =
        collapseWhitespace(
            value.lowercase()
            .replace('\u2010', '-')
            .replace('\u2011', '-')
            .replace('\u2012', '-')
            .replace('\u2013', '-')
            .replace('\u2014', '-')
        ).trim()

    private fun collapseWhitespace(value: String): String {
        val builder = StringBuilder(value.length)
        var pendingSpace = false
        value.forEach { char ->
            if (char.isWhitespace()) {
                pendingSpace = builder.isNotEmpty()
            } else {
                if (pendingSpace) builder.append(' ')
                builder.append(char)
                pendingSpace = false
            }
        }
        return builder.toString()
    }

    private val COMPLETION_PREFIXES = listOf(
        "如果已经在",
        "如果已在",
        "如果当前在",
        "若已经在",
        "若已在",
        "已经在",
        "已在",
    )
    private val COMPLETION_SUFFIXES = listOf(
        "相关页面",
        "相关页",
        "页面",
    )
    private const val MAX_RAW_TARGET_CHARS = 24
    private const val MAX_TARGETS = 3
}
