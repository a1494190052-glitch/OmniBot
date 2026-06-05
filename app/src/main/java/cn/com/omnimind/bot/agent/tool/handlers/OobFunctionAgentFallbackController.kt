package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.bot.agent.AgentToolJson.mapToJsonElement
import cn.com.omnimind.bot.omniflow.OobFunctionJson.firstNonBlank
import cn.com.omnimind.bot.runlog.UIStepExecutor

/**
 * Builds legacy recovery context for replay failures. The replay handler
 * decides when model continuation is required; this controller owns
 * prompt/recovery shaping for the next fresh VLM step.
 */
class OobFunctionAgentFallbackController {
    fun prompt(
        step: Map<String, Any?>,
        stepTitle: String,
        recovery: Map<String, Any?> = emptyMap(),
    ): String {
        val basePrompt = (step["agent_call"] as? Map<*, *>)
            ?.get("args")?.let { (it as? Map<*, *>)?.get("prompt")?.toString() }
            ?: (step["fallback"] as? Map<*, *>)?.get("prompt")?.toString()
            ?: stepTitle
        val args = UIStepExecutor.normalizeArgsMap(step["args"])
        val argsText = if (args.isNotEmpty()) {
            "\n\n当前已物化参数：${mapToJsonElement(args)}"
        } else {
            ""
        }
        return "$basePrompt$argsText${recoveryPromptSuffix(recovery)}"
    }

    suspend fun refetchCurrentPageForFailedStep(reason: String): Map<String, Any?> =
        runCatching {
            UIStepExecutor.currentPageSnapshotForRecovery(reason)
        }.getOrElse { error ->
            linkedMapOf(
                "refetched_current_page" to false,
                "reason" to reason,
                "error_message" to error.message.orEmpty(),
            )
        }

    private fun recoveryPromptSuffix(recovery: Map<String, Any?>): String {
        if (recovery.isEmpty()) return ""
        val packageName = firstNonBlank(recovery["effective_package"], recovery["package_name"])
        val activityName = firstNonBlank(recovery["activity_name"])
        val xml = recovery["observation_xml"]?.toString()?.take(MAX_RECOVERY_PROMPT_XML_CHARS).orEmpty()
        return buildString {
            append("\n\n上一次复用步骤执行失败后，系统已重新获取当前页面。")
            if (packageName.isNotBlank()) append("\n当前包名：$packageName")
            if (activityName.isNotBlank()) append("\n当前 Activity：$activityName")
            if (xml.isNotBlank()) append("\n当前页面 XML（截断）：\n").append(xml)
            append("\n请基于这个最新页面继续，不要沿用失败步骤里的旧坐标或旧页面。")
        }
    }

    private companion object {
        const val MAX_RECOVERY_PROMPT_XML_CHARS = 6000
    }
}
