package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.omniintelligence.models.AgentRequest.Payload.VLMChatPayload
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Compactor Agent - 负责周期性总结上下文并进行纠错
 * 模型: google/gemini-3-flash-preview
 */
class CompactorAgent {
    private val Tag = "CompactorAgent"
    private val sceneId = "scene.compactor.context"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    data class CompactorResult(
        val summary: String,
        val currentState: String = "",              // 当前屏幕状态描述
        val nextStep: String? = null,               // 建议的下一步操作
        val completedMilestones: List<String> = emptyList(), // 已完成的里程碑
        val keyMemory: List<String> = emptyList(),  // 关键记忆（需保留的信息）
        val needsCorrection: Boolean,
        val correctionGuidance: String? = null
    )

    suspend fun compact(
        goal: String,
        currentScreenshot: String, // Base64
        trace: List<UIStep>,
        existingRunningSummary: String,
        needSummary: Boolean = false
    ): CompactorResult {
        OmniLog.d(Tag, "开始执行上下文压缩与纠错 check...")

        // 构造 Prompt
        val stepHistory = trace.mapIndexed { index, step ->
            "Step ${index + 1}: Action=${step.action.name}, Observation=${step.observation}, Result=${step.result}"
        }.joinToString("\n")

        // 从 ModelSceneRegistry 获取模板并渲染
        val template = cn.com.omnimind.baselib.llm.ModelSceneRegistry.getPrompt(sceneId)
            ?: throw IllegalStateException("$sceneId prompt not found")

        val prompt = cn.com.omnimind.baselib.llm.ModelSceneRegistry.renderPrompt(
            template,
            mapOf(
                "goal" to goal,
                "needSummary" to needSummary.toString(),
                "existingRunningSummary" to existingRunningSummary,
                "stepHistory" to stepHistory
            )
        )

        try {
            // 调用 VLM 接口
            // 注意：这里复用 VLM 相关的网络请求逻辑，但指定 Gemini 模型
            // 如果 HttpController 不支持直接透传 model 字符串到 LLM 服务，可能需要确认
            // 假设 HttpController.postVLMRequest 可以支持传入 arbitrary model string (if backend supports it)
            // 根据需求描述: "agent 请求的模型id为 google/gemini-3-flash-preview"
            
            val payload = VLMChatPayload(
                model = sceneId, // 使用场景 ID,由 HttpController 解析成真实模型名
                text = prompt,
                images = listOf(currentScreenshot)
            )
            
            val response = HttpController.postVLMRequest(payload)
            
            // 空值检查：postVLMRequest 可能返回 null 或 result.message 为 null
            val responseText = response?.message
            if (responseText.isNullOrBlank()) {
                OmniLog.e(Tag, "Compactor 响应为空，降级处理")
                return CompactorResult(
                    summary = existingRunningSummary,
                    needsCorrection = false
                )
            }

            OmniLog.d(Tag, "Compactor Response: $responseText")

            // 解析 JSON
            // 并在 extractJson 增加简单的容错（去掉 ```json 和 ```）
            val jsonContent = extractJson(responseText)
            val parsed = json.decodeFromString<CompactorResult>(jsonContent)
            return if (needSummary) {
                sanitizeForSummaryTask(parsed, existingRunningSummary)
            } else {
                parsed
            }

        } catch (e: Exception) {
            OmniLog.e(Tag, "Compactor error: ${e.message}")
            // 降级：保留原有 summary，不做纠错
            return CompactorResult(
                summary = existingRunningSummary, // 无法压缩时，最好怎么处理？
                // 如果trace太长，不压缩会爆。但如果压缩失败，只能暂时由它去，或者返回简单的拼接
                // 这里暂时返回原有 summary + " (Compaction Failed)"
                needsCorrection = false
            )
        }
    }

    private fun extractJson(text: String): String {
        val start = text.indexOf("{")
        val end = text.lastIndexOf("}")
        if (start != -1 && end != -1 && end > start) {
            return text.substring(start, end + 1)
        }
        return text
    }

    private fun sanitizeForSummaryTask(
        result: CompactorResult,
        existingRunningSummary: String
    ): CompactorResult {
        val sanitizedSummary = sanitizeSummaryField(result.summary, existingRunningSummary)
        val sanitizedNextStep = sanitizeNextStep(result.nextStep)
        val sanitizedMilestones = result.completedMilestones
            .filterNot { containsSummaryIntent(it) }
        val sanitizedGuidance = sanitizeCorrectionGuidance(result.correctionGuidance)
        return result.copy(
            summary = sanitizedSummary,
            nextStep = sanitizedNextStep,
            completedMilestones = sanitizedMilestones,
            correctionGuidance = sanitizedGuidance
        )
    }

    private fun sanitizeSummaryField(summary: String, fallback: String): String {
        if (summary.isBlank()) return fallback
        if (!containsSummaryIntent(summary)) return summary
        val stripped = stripSummaryIntent(summary)
        return if (stripped.isBlank()) {
            fallback.ifBlank { summary }
        } else {
            stripped
        }
    }

    private fun sanitizeNextStep(nextStep: String?): String? {
        if (nextStep.isNullOrBlank()) {
            return "继续浏览并收集关键信息"
        }
        if (!containsSummaryIntent(nextStep) || isSummaryEndHint(nextStep)) return nextStep
        return "继续浏览并收集关键信息"
    }

    private fun sanitizeCorrectionGuidance(guidance: String?): String? {
        if (guidance.isNullOrBlank()) return guidance
        if (!containsSummaryIntent(guidance)) return guidance
        return "返回与目标相关的页面，继续浏览/搜索以收集关键信息"
    }

    private fun containsSummaryIntent(text: String): Boolean {
        val normalized = text.lowercase()
        return SUMMARY_INTENT_KEYWORDS.any { normalized.contains(it) }
    }

    private fun isSummaryEndHint(text: String): Boolean {
        val normalized = text.lowercase()
        return normalized.contains("结束浏览") ||
            normalized.contains("完成信息收集") ||
            normalized.contains("等待后续总结") ||
            normalized.contains("等待总结") ||
            normalized.contains("finish browsing") ||
            normalized.contains("end browsing") ||
            normalized.contains("wait for summary")
    }

    private fun stripSummaryIntent(text: String): String {
        var sanitized = text
        SUMMARY_INTENT_KEYWORDS.forEach { keyword ->
            sanitized = if (keyword.any { it.code < 128 }) {
                sanitized.replace(Regex(Regex.escape(keyword), RegexOption.IGNORE_CASE), "信息收集")
            } else {
                sanitized.replace(keyword, "信息收集")
            }
        }
        return sanitized
    }

    private companion object {
        private val SUMMARY_INTENT_KEYWORDS = listOf(
            "总结",
            "汇总",
            "概括",
            "归纳",
            "提炼",
            "复盘",
            "输出结果",
            "最终回答",
            "最终答复",
            "生成报告",
            "写报告",
            "输出报告",
            "summary",
            "summarize",
            "recap",
            "final answer"
        )
    }
}
