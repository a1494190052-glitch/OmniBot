package cn.com.omnimind.bot.omniflow

import cn.com.omnimind.bot.function.FunctionRecallCandidate
import cn.com.omnimind.bot.runlog.firstNonBlank
import cn.com.omnimind.bot.runlog.listArg

internal class OmniFlowFunctionRecallAdapter(
    private val bridgeCall: suspend (String, Map<String, Any?>) -> Map<String, Any?>,
) {
    suspend fun recall(request: Map<String, Any?>): Map<String, Any?> {
        val startedAt = System.currentTimeMillis()
        return runCatching {
            val result = bridgeCall(
                "recall",
                linkedMapOf(
                    "goal" to firstNonBlank(request["goal"]),
                    "state" to OmniFlowState.build(
                        xml = firstNonBlank(request["current_xml"]),
                        packageName = firstNonBlank(request["current_package"]),
                    ),
                ),
            )
            val limit = (request["k"] as? Number)?.toInt()?.coerceIn(1, 50) ?: 8
            val candidates = listArg(result["candidates"])
                .map { raw -> FunctionRecallCandidate.parse(raw).toMap() }
                .take(limit)
            linkedMapOf<String, Any?>(
                "success" to true,
                "retrieval_state" to if (candidates.isEmpty()) "miss" else "has_candidates",
                "candidates" to candidates,
                "count" to candidates.size,
                "reason" to if (candidates.isEmpty()) "python_recall_miss" else "omniflow_python_match",
                "current_package" to firstNonBlank(request["current_package"])
                    .takeIf(String::isNotBlank),
                "source" to "function_recall",
                "runtime_source" to "omniflow_python",
                "duration_ms" to elapsed(startedAt),
            ).filterValues { it != null }
        }.getOrElse { error ->
            linkedMapOf(
                "success" to false,
                "retrieval_state" to "unavailable",
                "candidates" to emptyList<Any>(),
                "count" to 0,
                "reason" to "python_recall_error:${error.message.orEmpty().take(160)}",
                "runtime_source" to "omniflow_python",
                "duration_ms" to elapsed(startedAt),
            )
        }
    }

    private fun elapsed(startedAt: Long): Long =
        (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)

}
