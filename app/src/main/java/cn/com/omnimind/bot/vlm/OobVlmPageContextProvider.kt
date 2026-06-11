package cn.com.omnimind.bot.vlm

import android.content.Context
import cn.com.omnimind.assists.task.vlmserver.UIContext
import cn.com.omnimind.assists.task.vlmserver.VLMIndexedPageContext
import cn.com.omnimind.assists.task.vlmserver.VLMPageContextProvider
import cn.com.omnimind.assists.task.vlmserver.VLMPageContextRequest
import cn.com.omnimind.bot.omniflow.OobFunctionJson.firstNonBlank
import cn.com.omnimind.bot.omniflow.OobFunctionJson.listArg
import cn.com.omnimind.bot.omniflow.OobFunctionJson.mapArg
import cn.com.omnimind.bot.runlog.OobUdegNodeStore

class OobVlmPageContextProvider(
    context: Context,
) : VLMPageContextProvider {
    private val appContext = context.applicationContext

    override suspend fun enrich(request: VLMPageContextRequest): UIContext {
        val snapshot = request.snapshot
        val currentXml = (snapshot?.xml ?: request.currentXml)?.trim().orEmpty()
        if (currentXml.isBlank()) return request.context
        val pageMatchStartedAt = System.currentTimeMillis()
        val observed = OobUdegNodeStore(appContext).observePage(
            OobUdegNodeStore.ObservedPage(
                pageXml = currentXml,
                packageName = snapshot?.packageName ?: request.currentPackageName.orEmpty(),
                screenshotBase64 = snapshot?.screenshotBase64 ?: request.screenshotBase64,
                goal = request.context.activeGoal().ifBlank { request.context.overallTask },
                stepIndex = request.stepIndex,
            )
        ) ?: return request.context
        val pageMatchMs = System.currentTimeMillis() - pageMatchStartedAt
        val guidance = renderGuidance(
            observed = observed,
            currentXml = currentXml,
            displayWidth = snapshot?.displayWidth ?: 0,
            displayHeight = snapshot?.displayHeight ?: 0,
        )
        val diagnostics = buildDiagnostics(
            observed = observed,
            guidance = guidance,
            pageMatchMs = pageMatchMs,
            snapshotTimestampMs = snapshot?.capturedAtMs
        )
        val baseContext = request.context.copy(pageDiagnostics = diagnostics)
        if (guidance.isBlank()) return baseContext

        val pageSummary = listOf(baseContext.currentPageSummary, guidance)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString("\n")
            .take(MAX_CONTEXT_CHARS)
        return baseContext.copy(
            currentPageSummary = pageSummary,
            firstStepGuidance = baseContext.firstStepGuidance,
        )
    }

    private fun renderGuidance(
        observed: OobUdegNodeStore.PageObservationResult,
        currentXml: String,
        displayWidth: Int,
        displayHeight: Int,
    ): String {
        if (observed.firstSeen) return ""
        val payload = observed.toMap()
        val nodeId = payload["node_id"]?.toString()?.trim().orEmpty()
        if (nodeId.isBlank()) return ""
        val pageAnalysis = mapArg(payload["page_analysis"])
        val summary = mapArg(pageAnalysis["summary"])
        val title = firstNonBlank(summary["title"])
        val pageRole = firstNonBlank(summary["page_role"])
        val identityTexts = listArg(summary["visible_texts"])
            .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
            .take(MAX_IDENTITY_TEXTS)
            .joinToString(" / ")
        if (title.isBlank() && pageRole.isBlank() && identityTexts.isBlank()) return ""

        return buildString {
            append("OOB Page Skill (current page identity only):")
            if (title.isNotBlank()) append("\n当前页面: ").append(title)
            if (pageRole.isNotBlank()) append("\n页面类型: ").append(pageRole)
            if (identityTexts.isNotBlank()) append("\n页面识别文本: ").append(identityTexts)
            val representativeElements = VLMIndexedPageContext.renderRepresentativeElements(
                currentXml = currentXml,
                displayWidth = displayWidth,
                displayHeight = displayHeight,
                maxElements = MAX_REPRESENTATIVE_ELEMENTS,
            )
            if (representativeElements.isNotBlank()) {
                append("\n代表 UI 元素（坐标为屏幕绝对像素中心点，非完整可选集合）:")
                append("\n").append(representativeElements)
            }
            append("\n约束: 只描述当前页面是什么；代表元素只是提示，不能限制模型只选择这些元素；动作仍必须基于 live screenshot/XML/indexed evidence/action transfer。复用指令由 Function Recall 注入，弹窗/键盘/广告由 Checker 处理。")
        }.take(MAX_GUIDANCE_CHARS)
    }

    private fun buildDiagnostics(
        observed: OobUdegNodeStore.PageObservationResult,
        guidance: String,
        pageMatchMs: Long,
        snapshotTimestampMs: Long?,
    ): Map<String, String> {
        val payload = observed.toMap()
        val nodeId = payload["node_id"]?.toString()?.trim().orEmpty()
        return linkedMapOf<String, String>().apply {
            if (nodeId.isNotBlank()) put("node_id", nodeId)
            put("page_similarity", observed.pageSimilarity.toString())
            put("page_match_ms", pageMatchMs.coerceAtLeast(0L).toString())
            put("skill_context_chars", guidance.length.toString())
            put("udeg_first_seen", observed.firstSeen.toString())
            put("udeg_context_injected", guidance.isNotBlank().toString())
            snapshotTimestampMs?.let { put("snapshot_timestamp", it.toString()) }
        }
    }

    private companion object {
        private const val MAX_IDENTITY_TEXTS = 6
        private const val MAX_REPRESENTATIVE_ELEMENTS = 4
        private const val MAX_GUIDANCE_CHARS = 600
        private const val MAX_CONTEXT_CHARS = 1_400
    }
}
