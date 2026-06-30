package cn.com.omnimind.bot.agent

import java.io.File

data class AgentWorkspaceDescriptor(
    val id: String,
    val rootPath: String,
    val androidRootPath: String,
    val uriRoot: String,
    val currentCwd: String,
    val androidCurrentCwd: String,
    val shellRootPath: String,
    val retentionPolicy: String
)

data class AgentModelOverride(
    val providerProfileId: String,
    val providerProfileName: String? = null,
    val modelId: String,
    val apiBase: String,
    val apiKey: String,
    val customHeaders: Map<String, String> = emptyMap(),
    val protocolType: String = "openai_compatible",
    val wireApi: String = "chat_completions",
    val contextLimit: Int? = null
)

data class ArtifactAction(
    val type: String,
    val label: String,
    val target: String? = null,
    val payload: Map<String, Any?> = emptyMap()
) {
    fun toPayload(): Map<String, Any?> = mapOf(
        "type" to type,
        "label" to label,
        "target" to target,
        "payload" to payload
    )
}

data class ArtifactRef(
    val id: String,
    val uri: String,
    val title: String,
    val mimeType: String,
    val size: Long,
    val sourceTool: String,
    val workspacePath: String,
    val androidPath: String,
    val previewKind: String,
    val actions: List<ArtifactAction> = emptyList()
) {
    fun fileName(): String = File(androidPath).name

    val embedKind: String
        get() = when (previewKind) {
            "image" -> "image"
            "audio" -> "audio"
            "video" -> "video"
            "pdf" -> "pdf"
            "html" -> "html"
            "office_word", "office_sheet", "office_slide" -> "office"
            else -> "link"
        }

    val inlineRenderable: Boolean
        get() = embedKind != "link"

    val renderMarkdown: String
        get() {
            val safeTitle = title
                .replace("\\", "\\\\")
                .replace("[", "\\[")
                .replace("]", "\\]")
            return if (embedKind == "image") {
                "![${safeTitle}]($uri)"
            } else {
                "[${safeTitle}]($uri)"
            }
        }

    fun toPayload(): Map<String, Any?> = mapOf(
        "id" to id,
        "uri" to uri,
        "title" to title,
        "fileName" to fileName(),
        "mimeType" to mimeType,
        "size" to size,
        "sourceTool" to sourceTool,
        "workspacePath" to workspacePath,
        "androidPath" to androidPath,
        "previewKind" to previewKind,
        "embedKind" to embedKind,
        "inlineRenderable" to inlineRenderable,
        "renderMarkdown" to renderMarkdown,
        "actions" to actions.map { it.toPayload() }
    )
}

data class SkillIndexEntry(
    val id: String,
    val name: String,
    val description: String,
    val compatibility: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val rootPath: String,
    val shellRootPath: String,
    val skillFilePath: String,
    val shellSkillFilePath: String,
    val hasScripts: Boolean,
    val hasReferences: Boolean,
    val hasAssets: Boolean,
    val hasEvals: Boolean,
    val enabled: Boolean = true,
    val source: String = "user",
    val installed: Boolean = true
)

data class ResolvedSkillContext(
    val skillId: String,
    val frontmatter: Map<String, String>,
    val metadata: Map<String, String> = emptyMap(),
    val bodyMarkdown: String,
    val loadedReferences: List<String> = emptyList(),
    val scriptsDir: String? = null,
    val assetsDir: String? = null,
    val triggerReason: String
) {
    fun promptSummary(maxChars: Int = 1800): String {
        val skillName = frontmatter["name"]?.ifBlank { skillId } ?: skillId
        val lines = bodyMarkdown.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .dropWhile { it.startsWith("---") }
            .dropWhile { it.startsWith("#") }
            .take(16)
        val base = buildString {
            appendLine("Skill: $skillName")
            appendLine("Trigger: $triggerReason")
            scriptsDir?.takeIf { it.isNotBlank() }?.let { appendLine("Scripts: $it") }
            assetsDir?.takeIf { it.isNotBlank() }?.let { appendLine("Assets: $it") }
            if (loadedReferences.isNotEmpty()) {
                appendLine("References: ${loadedReferences.joinToString(", ")}")
            }
            appendLine(lines.joinToString("\n"))
        }.trim()
        return if (base.length <= maxChars) base else base.take(maxChars) + "\n..."
    }

    fun stepGuidance(maxChars: Int = 2200): String {
        val sourceLines = bodyMarkdown.lines()
        val bodyLines = if (sourceLines.firstOrNull()?.trim() == "---") {
            sourceLines.drop(1).dropWhile { it.trim() != "---" }.drop(1)
        } else {
            sourceLines
        }
        val guidanceSection = bodyLines
            .dropWhile { it.trim() != "## Step Guidance Essentials" }
            .drop(1)
            .takeWhile { !it.trim().startsWith("## ") }
            .takeIf { it.isNotEmpty() }
            ?: bodyLines
        val lines = guidanceSection
            .map { it.trim() }
            .filter { line ->
                line.isNotEmpty() &&
                    !line.startsWith("---") &&
                    !line.startsWith("#") &&
                    !line.startsWith("```")
            }
            .take(24)
        val base = lines.joinToString("\n")
        return if (base.length <= maxChars) base else base.take(maxChars) + "\n..."
    }

    fun vlmStepGuidance(maxChars: Int = 900): String {
        val base = if (isOmniFlowSkill()) {
            OMNIFLOW_COMPACT_GUIDANCE
        } else {
            ""
        }
        return if (base.length <= maxChars) base else base.take(maxChars) + "\n..."
    }

    private fun isOmniFlowSkill(): Boolean {
        val skillName = frontmatter["name"].orEmpty()
        return skillId == "omniflow" || skillName == "omniflow"
    }

    private companion object {
        private val OMNIFLOW_COMPACT_GUIDANCE = listOf(
            "Use one fresh observation for each action turn.",
            "Choose exactly one executable UI action per step.",
            "Prefer visible label, role, node_id, element_index, or scrollable_index; use coordinates only as fallback.",
            "If an editable field is focused, use input_text; otherwise ground the field before typing.",
            "Call finished only when the requested final state is directly visible.",
            "Replay registered Functions only when current package and page evidence match; otherwise fall back to live VLM.",
            "Do not call update_function or enhance inline before RunLog registration, direct replay, recall-hit replay, or debug convert-and-replay."
        ).joinToString("\n")
    }

}

data class SkillCompatibilityResult(
    val available: Boolean,
    val reason: String? = null
)

data class SkillMatchResult(
    val entry: SkillIndexEntry,
    val confidence: Double,
    val triggerReason: String
)
