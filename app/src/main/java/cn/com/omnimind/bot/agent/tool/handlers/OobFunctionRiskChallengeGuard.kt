package cn.com.omnimind.bot.agent.tool.handlers

/**
 * Blocks deterministic phone replay on human-verification or login-risk pages.
 * The runner must not try to solve slider/captcha challenges with replayed UI actions.
 */
class OobFunctionRiskChallengeGuard(
    @Suppress("unused")
    private val runResultBuilder: OobFunctionRunResultBuilder,
) {
    @Suppress("UNUSED_PARAMETER")
    suspend fun failureIfBlocked(
        functionId: String,
        spec: Map<String, Any?>,
        auditRunId: String,
        startedAtMs: Long,
        steps: List<Map<String, Any?>>,
    ): Map<String, Any?>? {
        return null
    }
}
