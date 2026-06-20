package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.bot.runlog.OobActionCodec
import cn.com.omnimind.bot.runlog.RunLogReplayPolicy
import cn.com.omnimind.bot.runlog.UIStepExecutor

/**
 * Blocks deterministic phone replay on human-verification or login-risk pages.
 * The runner must not try to solve slider/captcha challenges with replayed UI actions.
 */
class OobFunctionRiskChallengeGuard(
    private val runResultBuilder: OobFunctionRunResultBuilder,
) {
    suspend fun failureIfBlocked(
        functionId: String,
        spec: Map<String, Any?>,
        auditRunId: String,
        startedAtMs: Long,
        steps: List<Map<String, Any?>>,
    ): Map<String, Any?>? {
        if (steps.isEmpty()) return null
        val snapshot = UIStepExecutor.currentPageSnapshotForRecovery("function_risk_challenge_check")
        val xml = OobActionCodec.firstNonBlank(snapshot["observation_xml"], snapshot["xml"])
        val challenge = detect(xml) ?: return null
        val firstStep = steps.firstOrNull { !isSkippedStep(it) }.orEmpty()
        val stepId = firstStep["id"]?.toString()?.ifBlank { null } ?: "step_1"
        val action = UIStepExecutor.actionNameForStep(firstStep).ifBlank {
            firstStep["tool"]?.toString()?.trim().orEmpty().ifBlank { "unknown" }
        }
        val message = "当前页面需要人工完成身份核实或验证码，复用指令已安全停止。"
        return runResultBuilder.failedRun(
            functionId = functionId,
            spec = spec,
            auditRunId = auditRunId,
            startedAtMs = startedAtMs,
            errorCode = ERROR_CODE,
            errorMessage = message,
            extras = linkedMapOf(
                "step_count" to steps.size,
                "failed_step_index" to 0,
                "current_step_index" to 0,
                "current_step_number" to 1,
                "blocked_step_index" to 0,
                "risk_challenge" to challenge,
                "requires_user_action" to true,
                "step_results" to listOf(
                    runResultBuilder.failureStep(
                        stepId = stepId,
                        tool = action,
                        executor = RunLogReplayPolicy.EXECUTOR_OMNIFLOW,
                        summary = message,
                        errorCode = ERROR_CODE,
                        extras = linkedMapOf(
                            "index" to 0,
                            "risk_challenge" to challenge,
                            "requires_user_action" to true,
                        )
                    )
                )
            )
        )
    }

    private fun detect(xml: String): Map<String, Any?>? {
        val normalized = xml.lowercase()
        if (normalized.isBlank()) return null
        val cues = HUMAN_CHALLENGE_CUES.filter { it in normalized }
        if (cues.isEmpty()) return null
        val kind = when {
            cues.any { it.contains("puzzle") || it.contains("slider") || it.contains("滑块") || it.contains("拖动") } ->
                "slider_challenge"
            cues.any { it.contains("captcha") || it.contains("验证码") } ->
                "captcha_challenge"
            else -> "identity_verification"
        }
        return linkedMapOf(
            "kind" to kind,
            "matched_cues" to cues.distinct().take(6),
            "recommended_next_action" to "ask_user_to_complete_verification_manually",
        )
    }

    private fun isSkippedStep(step: Map<String, Any?>): Boolean {
        val tool = step["tool"]?.toString()?.trim().orEmpty()
        return listOf(tool, UIStepExecutor.actionNameForStep(step))
            .any { it.isNotBlank() && RunLogReplayPolicy.shouldSkipTool(it) }
    }

    private companion object {
        const val ERROR_CODE = "OOB_RISK_CHALLENGE_REQUIRES_USER"
        val HUMAN_CHALLENGE_CUES = listOf(
            "请拖动下方滑块完成拼图",
            "puzzleslider",
            "puzzle",
            "slider",
            "验证码",
            "captcha",
            "身份核实",
            "身份验证",
            "安全验证",
            "verify you are human",
            "human verification",
        )
    }
}
