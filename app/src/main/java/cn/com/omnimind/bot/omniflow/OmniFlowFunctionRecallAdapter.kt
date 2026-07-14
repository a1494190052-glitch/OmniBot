package cn.com.omnimind.bot.omniflow

import cn.com.omnimind.bot.function.FunctionSchema
import cn.com.omnimind.bot.runlog.firstNonBlank
import cn.com.omnimind.bot.runlog.listArg
import cn.com.omnimind.bot.runlog.mapArg
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class OmniFlowFunctionRecallAdapter(
    private val enabled: () -> Boolean,
    private val bridgeCall: suspend (String, Map<String, Any?>) -> Map<String, Any?>,
) {
    private val syncMutex = Mutex()
    private var syncedFingerprint: Int? = null

    suspend fun recall(
        request: Map<String, Any?>,
        functionSpecs: List<Map<String, Any?>>,
    ): Map<String, Any?> {
        val startedAt = System.currentTimeMillis()
        if (!enabled()) return miss("python_not_ready", startedAt, available = false)
        return runCatching {
            syncCatalog(functionSpecs)
            val functionCatalog = functionSpecs.mapNotNull { spec ->
                FunctionSchema.functionIdFromSpec(spec)
                    .takeIf(String::isNotBlank)
                    ?.let { functionId -> functionId to spec }
            }.toMap()
            val result = bridgeCall(
                "recall",
                linkedMapOf(
                    "goal" to firstNonBlank(request["goal"], request["query"], request["task"]),
                    "observation" to linkedMapOf(
                        "xml" to firstNonBlank(request["current_xml"], request["currentXml"]),
                        "package_name" to firstNonBlank(
                            request["current_package"],
                            request["currentPackage"],
                        ),
                    ),
                ),
            )
            val candidates = normalizeCandidates(result, request, functionCatalog)
            if (candidates.isEmpty()) {
                miss("python_recall_miss", startedAt)
            } else {
                linkedMapOf<String, Any?>(
                    "success" to true,
                    "retrieval_state" to "has_candidates",
                    "candidates" to candidates,
                    "count" to candidates.size,
                    "reason" to "omniflow_python_match",
                    "current_package" to firstNonBlank(
                        request["current_package"],
                        request["currentPackage"],
                    ).takeIf(String::isNotBlank),
                    "source" to "function_recall",
                    "runtime_source" to "omniflow_python",
                    "page_id" to result["page_id"],
                    "page_score" to result["page_score"],
                    "duration_ms" to elapsed(startedAt),
                ).filterValues { it != null }
            }
        }.getOrElse { error ->
            miss(
                "python_recall_error:${error.message.orEmpty().take(160)}",
                startedAt,
                available = false,
            )
        }
    }

    private suspend fun syncCatalog(functionSpecs: List<Map<String, Any?>>) {
        val fingerprint = functionSpecs.fold(1) { value, spec -> 31 * value + spec.hashCode() }
        syncMutex.withLock {
            if (syncedFingerprint == fingerprint) return
            bridgeCall("catalog", mapOf("action" to "clear"))
            functionSpecs.forEach { spec ->
                bridgeCall(
                    "catalog",
                    mapOf(
                        "action" to "put",
                        "function" to spec,
                    ),
                )
            }
            syncedFingerprint = fingerprint
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
                val functionId = firstNonBlank(function["function_id"], function["id"])
                if (functionId.isBlank()) return@mapNotNull null
                val sourceSpec = functionCatalog[functionId] ?: return@mapNotNull null
                val parameters = FunctionSchema.inputSchema(sourceSpec).ifEmpty {
                    mapArg(function["parameters"])
                }
                val stepSummaries = FunctionSchema.stepSummaries(sourceSpec).ifEmpty {
                    listArg(function["actions"]).mapIndexedNotNull { index, actionRaw ->
                        val action = mapArg(actionRaw)
                        val tool = firstNonBlank(action["tool"], action["type"])
                        if (tool.isBlank()) null else linkedMapOf(
                            "index" to index,
                            "tool" to tool,
                            "title" to tool,
                        )
                    }
                }
                linkedMapOf<String, Any?>(
                    "function_id" to functionId,
                    "name" to firstNonBlank(sourceSpec["name"], function["name"], functionId),
                    "description" to firstNonBlank(
                        sourceSpec["description"],
                        function["description"],
                        functionId,
                    ),
                    "parameters" to parameters,
                    "input_schema" to parameters,
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
    ): Map<String, Any?> = linkedMapOf(
        "success" to available,
        "retrieval_state" to if (available) "miss" else "unavailable",
        "candidates" to emptyList<Any>(),
        "count" to 0,
        "reason" to reason,
        "runtime_source" to "omniflow_python",
        "duration_ms" to elapsed(startedAt),
    )

    private fun elapsed(startedAt: Long): Long =
        (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
}
