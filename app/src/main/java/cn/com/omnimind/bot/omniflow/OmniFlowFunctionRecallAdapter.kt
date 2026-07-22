package cn.com.omnimind.bot.omniflow

import cn.com.omnimind.bot.function.FunctionSchema
import cn.com.omnimind.bot.runlog.firstNonBlank
import cn.com.omnimind.bot.runlog.listArg
import cn.com.omnimind.bot.runlog.mapArg
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class OmniFlowFunctionRecallAdapter(
    private val bridgeCall: suspend (String, Map<String, Any?>) -> Map<String, Any?>,
) {
    private val syncMutex = Mutex()
    private var syncedFingerprint: Int? = null
    private var invalidFunctions: Map<String, String> = emptyMap()

    suspend fun recall(
        request: Map<String, Any?>,
        functionSpecs: List<Map<String, Any?>>,
    ): Map<String, Any?> {
        val startedAt = System.currentTimeMillis()
        return runCatching {
            val invalidFunctions = syncCatalog(functionSpecs)
            val functionCatalog = functionSpecs.mapNotNull { spec ->
                FunctionSchema.functionIdFromSpec(spec)
                    .takeIf(String::isNotBlank)
                    ?.let { functionId -> functionId to spec }
            }.toMap()
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
            val candidates = normalizeCandidates(result, request, functionCatalog)
            if (candidates.isEmpty()) {
                miss("python_recall_miss", startedAt, invalidFunctions = invalidFunctions)
            } else {
                linkedMapOf<String, Any?>(
                    "success" to true,
                    "retrieval_state" to "has_candidates",
                    "candidates" to candidates,
                    "count" to candidates.size,
                    "reason" to "omniflow_python_match",
                    "current_package" to firstNonBlank(
                        request["current_package"],
                    ).takeIf(String::isNotBlank),
                    "source" to "function_recall",
                    "runtime_source" to "omniflow_python",
                    "invalid_functions" to invalidFunctions,
                    "duration_ms" to elapsed(startedAt),
                ).filterValues { it != null }
            }
        }.getOrElse { error ->
            val detail = error.message.orEmpty().ifBlank { error.javaClass.simpleName }
            miss(
                "python_recall_error:${detail.take(160)}",
                startedAt,
                available = false,
            )
        }
    }

    private suspend fun syncCatalog(
        functionSpecs: List<Map<String, Any?>>,
    ): Map<String, String> {
        val fingerprint = functionSpecs.fold(1) { value, spec -> 31 * value + spec.hashCode() }
        return syncMutex.withLock {
            if (syncedFingerprint == fingerprint) return@withLock invalidFunctions
            val result = bridgeCall(
                "catalog",
                mapOf(
                    "action" to "replace",
                    "functions" to functionSpecs,
                ),
            )
            invalidFunctions = mapArg(result["invalid_functions"])
                .mapValues { (_, value) -> value?.toString().orEmpty() }
                .filterValues(String::isNotBlank)
            syncedFingerprint = fingerprint
            invalidFunctions
        }
    }

    private fun normalizeCandidates(
        result: Map<String, Any?>,
        request: Map<String, Any?>,
        functionCatalog: Map<String, Map<String, Any?>>,
    ): List<Map<String, Any?>> {
        val limit = (request["k"] as? Number)?.toInt()?.coerceIn(1, 50) ?: 8
        return listArg(result["functions"])
            .mapNotNull { raw ->
                val function = mapArg(raw)
                val functionId = firstNonBlank(function["function_id"])
                if (functionId.isBlank()) return@mapNotNull null
                val sourceSpec = functionCatalog[functionId] ?: return@mapNotNull null
                val inputSchema = FunctionSchema.inputSchema(sourceSpec)
                val stepSummaries = FunctionSchema.stepSummaries(sourceSpec)
                linkedMapOf<String, Any?>(
                    "function_id" to functionId,
                    "name" to firstNonBlank(sourceSpec["name"], function["name"], functionId),
                    "description" to firstNonBlank(
                        sourceSpec["description"],
                        function["description"],
                        functionId,
                    ),
                    "input_schema" to inputSchema,
                    "step_summaries" to stepSummaries,
                    "runtime_source" to "omniflow_python",
                )
            }
            .take(limit)
    }

    private fun miss(
        reason: String,
        startedAt: Long,
        available: Boolean = true,
        invalidFunctions: Map<String, String> = emptyMap(),
    ): Map<String, Any?> = linkedMapOf(
        "success" to available,
        "retrieval_state" to if (available) "miss" else "unavailable",
        "candidates" to emptyList<Any>(),
        "count" to 0,
        "reason" to reason,
        "runtime_source" to "omniflow_python",
        "invalid_functions" to invalidFunctions,
        "duration_ms" to elapsed(startedAt),
    )

    private fun elapsed(startedAt: Long): Long =
        (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
}
