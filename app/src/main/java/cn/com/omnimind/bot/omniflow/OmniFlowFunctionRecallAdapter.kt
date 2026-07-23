package cn.com.omnimind.bot.omniflow

import cn.com.omnimind.bot.runlog.firstNonBlank

internal class OmniFlowFunctionRecallAdapter(
    private val bridgeCall: suspend (String, Map<String, Any?>) -> Map<String, Any?>,
) {
    suspend fun recall(request: Map<String, Any?>): Map<String, Any?> {
        val startedAtMs = System.currentTimeMillis()
        return runCatching {
            bridgeCall(
                "recall",
                linkedMapOf(
                    "goal" to firstNonBlank(request["goal"]),
                    "state" to OmniFlowState.build(
                        xml = firstNonBlank(request["current_xml"]),
                        packageName = firstNonBlank(request["current_package"]),
                    ),
                    "limit" to ((request["k"] as? Number)?.toInt() ?: 8).coerceIn(1, 50),
                ),
            )
        }.getOrElse { error ->
            linkedMapOf(
                "success" to false,
                "retrieval_state" to "unavailable",
                "candidates" to emptyList<Any>(),
                "count" to 0,
                "reason" to "python_recall_error:${error.message.orEmpty().take(160)}",
                "runtime_source" to "omniflow_python",
                "duration_ms" to (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L),
            )
        }
    }
}
