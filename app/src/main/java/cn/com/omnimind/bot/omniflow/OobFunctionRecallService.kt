package cn.com.omnimind.bot.omniflow

import android.content.Context
import cn.com.omnimind.assists.task.vlmserver.AndroidDeviceOperator
import cn.com.omnimind.assists.task.vlmserver.DeviceOperator
import cn.com.omnimind.bot.omniflow.OobFunctionJson.boolArg
import cn.com.omnimind.bot.omniflow.OobFunctionJson.firstNonBlank
import cn.com.omnimind.bot.omniflow.OobFunctionJson.intArg
import cn.com.omnimind.bot.omniflow.OobFunctionJson.listArg
import cn.com.omnimind.bot.omniflow.OobFunctionJson.mapArg

class OobFunctionRecallService(
    private val context: Context,
    private val functionRepository: OobFunctionRepository,
    private val deviceOperator: DeviceOperator = AndroidDeviceOperator(null, context),
) {
    fun recall(args: Map<String, Any?>?): Map<String, Any?> {
        val startedAt = System.currentTimeMillis()
        val request = args ?: emptyMap()
        val goal = firstNonBlank(request["goal"], request["query"], request["task"])
        val includeDebug = boolArg(request["include_debug"]) ||
            boolArg(request["includeDebug"]) ||
            boolArg(request["debug"])
        val currentPackage = firstNonBlank(
            request["current_package"],
            request["currentPackage"],
            runCatching { deviceOperator.currentPackageName() }.getOrNull(),
        )
        val limit = intArg(request["k"], defaultValue = DEFAULT_RECALL_LIMIT)
            .coerceIn(1, MAX_RECALLED_FUNCTIONS)
        val hits = functionRepository.recall(goal = goal, limit = limit)
        val candidates = hits.mapNotNull { hit ->
            val spec = functionRepository.get(hit.functionId) ?: return@mapNotNull null
            if (!OobFunctionRepository.isAgentVisible(spec)) return@mapNotNull null
            candidateMap(spec = spec, hit = hit, currentPackage = currentPackage)
        }
        val decision = if (candidates.isNotEmpty()) "recall" else "miss"
        return linkedMapOf<String, Any?>(
            "success" to true,
            "decision" to decision,
            "candidates" to candidates,
            "count" to candidates.size,
            "reason" to when {
                goal.isBlank() -> "empty_goal"
                candidates.isEmpty() -> "no_function_index_match"
                else -> "function_index_match"
            },
            "current_package" to currentPackage.takeIf { it.isNotBlank() },
            "source" to "oob_function_recall_index",
            "payload_mode" to if (includeDebug) "debug_full" else "agent_compact",
            "timing" to linkedMapOf(
                "source" to "oob_function_recall_index",
                "decision" to decision,
                "duration_ms" to (System.currentTimeMillis() - startedAt).coerceAtLeast(0L),
                "counts" to linkedMapOf(
                    "index_hits" to hits.size,
                    "function_candidates" to candidates.size,
                )
            ),
        ).filterValues { it != null }
    }

    private fun candidateMap(
        spec: Map<String, Any?>,
        hit: FunctionRecallIndex.Hit,
        currentPackage: String,
    ): Map<String, Any?> {
        val functionId = OobFunctionSchemaBuilder.functionId(spec)
        val packageNames = packageScopes(spec)
        return linkedMapOf<String, Any?>(
            "capability_type" to "function",
            "function_id" to functionId,
            "description" to firstNonBlank(spec["description"], spec["name"], functionId),
            "name" to spec["name"],
            "inputSchema" to OobFunctionSchemaBuilder.inputSchema(spec),
            "score" to hit.score,
            "score_order" to "sqlite_fts5_bm25_ascending",
            "reason" to "sqlite_fts5_bm25",
            "recall_scope" to "function_index",
            "current_package_match" to (
                currentPackage.isNotBlank() && packageNames.contains(currentPackage)
                ),
            "package_names" to packageNames.takeIf { it.isNotEmpty() },
            "requires_arguments" to !isNoArgumentFunction(spec),
            "resolve_policy" to argumentResolvePolicy(spec),
            "execution_scope" to "function",
            "step_count" to OobFunctionSchemaBuilder.materializedSteps(spec).size,
            "step_summaries" to OobFunctionSchemaBuilder.stepSummaries(spec),
            "function_profile" to functionProfile(spec),
            "function_kind" to "oob_reusable_function",
            "asset_state" to "native_local",
            "source" to "oob_function_recall_index",
        ).filterValues { it != null }
    }

    private fun functionProfile(spec: Map<String, Any?>): Map<String, Any?> {
        val metadata = mapArg(spec["metadata"])
        val agentReuse = mapArg(spec["agent_reuse"])
            .ifEmpty { mapArg(metadata["agent_reuse"]) }
        val source = mapArg(spec["source"])
        return linkedMapOf<String, Any?>(
            "purpose" to firstNonBlank(
                spec["description"],
                spec["name"],
                OobFunctionSchemaBuilder.functionId(spec),
            ),
            "use_when" to firstNonBlank(
                agentReuse["use_when"],
                agentReuse["reuse_when"],
                source["goal"],
            ).takeIf { it.isNotBlank() },
            "success_signal" to firstNonBlank(
                agentReuse["success_signal"],
                agentReuse["successSignal"],
            ).takeIf { it.isNotBlank() },
            "limitations" to listArg(agentReuse["limitations"]).take(5).takeIf { it.isNotEmpty() },
            "common_situations" to listArg(agentReuse["common_situations"])
                .ifEmpty { listArg(agentReuse["commonSituations"]) }
                .take(5)
                .takeIf { it.isNotEmpty() },
            "package_name" to packageScopes(spec).firstOrNull(),
        ).filterValues { it != null }
    }

    private fun packageScopes(spec: Map<String, Any?>): Set<String> {
        val constraints = mapArg(spec["constraints"])
        val source = mapArg(spec["source"])
        return buildList {
            listOf(
                constraints["package_name"],
                constraints["packageName"],
                source["package_name"],
                source["packageName"],
            ).map { firstNonBlank(it) }
                .filterTo(this) { it.isNotBlank() }
            OobFunctionSchemaBuilder.materializedSteps(spec).forEach { step ->
                val args = mapArg(step["args"])
                val sourceContext = mapArg(step["source_context"])
                val srcCtx = mapArg(sourceContext["src_ctx"])
                val dstCtx = mapArg(sourceContext["dst_ctx"])
                val sourceAction = mapArg(sourceContext["action"])
                listOf(
                    args["package_name"],
                    args["packageName"],
                    srcCtx["package_name"],
                    srcCtx["packageName"],
                    dstCtx["package_name"],
                    dstCtx["packageName"],
                    sourceAction["package_name"],
                    sourceAction["packageName"],
                ).map { firstNonBlank(it) }
                    .filterTo(this) { it.isNotBlank() }
            }
        }.toSet()
    }

    private fun isNoArgumentFunction(spec: Map<String, Any?>): Boolean {
        val schema = OobFunctionSchemaBuilder.inputSchema(spec)
        return listArg(schema["required"]).isEmpty() && mapArg(schema["properties"]).isEmpty()
    }

    private fun argumentResolvePolicy(spec: Map<String, Any?>): String =
        if (isNoArgumentFunction(spec)) {
            "no_arguments_required"
        } else {
            "goal_bound_arguments_required"
        }

    private companion object {
        private const val DEFAULT_RECALL_LIMIT = 50
        private const val MAX_RECALLED_FUNCTIONS = 50
    }
}
