package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.bot.omniflow.OobFunctionJson.firstNonBlank
import cn.com.omnimind.bot.omniflow.OobFunctionJson.listArg
import cn.com.omnimind.bot.omniflow.OobFunctionJson.mapArg
import cn.com.omnimind.bot.runlog.OmniflowCheckerRule
import cn.com.omnimind.bot.runlog.UIStepExecutor
import cn.com.omnimind.bot.runlog.RunLogReplayPolicy
import cn.com.omnimind.baselib.runlog.OobCanonicalActionSchema
import kotlinx.coroutines.CancellationException

/**
 * Executes OmniFlow graph/UTG steps by resolving them into primitive local
 * OmniFlow actions. The Function tool handler owns the main replay loop; this
 * class owns graph path selection and edge-to-step lowering.
 */
class OobFunctionGraphStepRunner(
    private val runResultBuilder: OobFunctionRunResultBuilder = OobFunctionRunResultBuilder(),
) {
    suspend fun execute(
        step: Map<String, Any?>,
        stepId: String,
        stepTitle: String,
        callableTool: String,
        checkerRules: List<OmniflowCheckerRule> = emptyList(),
        checkerBudget: UIStepExecutor.CheckerTriggerBudget = UIStepExecutor.CheckerTriggerBudget(),
    ): Map<String, Any?> {
        val path = resolveGraphPath(step, callableTool)
        if (path.isEmpty()) {
            return failureStepResult(
                stepId = stepId,
                tool = callableTool.ifEmpty { RunLogReplayPolicy.TOOL_GO_TO_NODE },
                summary = "$stepTitle has no executable UTG path",
                errorCode = "OOB_UTG_PATH_EMPTY",
            )
        }

        val primitiveResults = mutableListOf<Map<String, Any?>>()
        for ((index, primitiveStep) in path.withIndex()) {
            val pathStepId = "${stepId}_path_${index + 1}"
            val pathTitle = primitiveStep["title"]?.toString()?.takeIf { it.isNotBlank() }
                ?: "$stepTitle path ${index + 1}"
            val startedAtMs = System.currentTimeMillis()
            val result = try {
                UIStepExecutor.execute(
                    step = primitiveStep,
                    stepId = pathStepId,
                    stepTitle = pathTitle,
                    checkerRules = checkerRules,
                    checkerBudget = checkerBudget,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failureStepResult(
                    stepId = pathStepId,
                    tool = UIStepExecutor.actionNameForStep(primitiveStep),
                    executor = RunLogReplayPolicy.EXECUTOR_OMNIFLOW,
                    summary = e.message ?: "UTG path action failed",
                    errorCode = "OOB_UTG_ACTION_FAILED",
                )
            }
            val finishedAtMs = System.currentTimeMillis()
            primitiveResults += LinkedHashMap<String, Any?>().apply {
                putAll(result)
                putIfAbsent("index", index)
                putIfAbsent("started_at_ms", startedAtMs)
                putIfAbsent("finished_at_ms", finishedAtMs)
                putIfAbsent("duration_ms", (finishedAtMs - startedAtMs).coerceAtLeast(0))
            }
            if (result["success"] == false) break
        }

        val success = primitiveResults.size == path.size &&
            primitiveResults.none { it["success"] == false }
        return linkedMapOf<String, Any?>(
            "step_id" to stepId,
            "tool" to callableTool.ifEmpty { RunLogReplayPolicy.TOOL_GO_TO_NODE },
            "executor" to "omniflow_graph",
            "model_free" to true,
            "success" to success,
            "path_length" to path.size,
            "success_path_step_count" to primitiveResults.count { it["success"] != false },
            "step_results" to primitiveResults,
            "summary" to if (success) {
                "$stepTitle completed via local UTG path"
            } else {
                primitiveResults.lastOrNull()?.get("summary")?.toString()
                    ?: "$stepTitle failed in local UTG path"
            },
        )
    }

    private fun resolveGraphPath(
        step: Map<String, Any?>,
        callableTool: String,
    ): List<Map<String, Any?>> {
        val args = stepArgs(step)
        val directPath = listArg(args["path"]).ifEmpty { listArg(step["path"]) }
        val utg = mapArg(args["utg"])
            .ifEmpty { mapArg(step["utg"]) }
            .ifEmpty { mapArg(args["graph"]) }
            .ifEmpty { mapArg(step["graph"]) }
        val utgPathIds = listArg(utg["path"])
        val utgEdges = listArg(utg["edges"])
            .mapNotNull { mapArg(it).takeIf { edge -> edge.isNotEmpty() } }
        val edges = listArg(args["edges"])
            .ifEmpty { listArg(step["edges"]) }
            .mapNotNull { mapArg(it).takeIf { edge -> edge.isNotEmpty() } }
            .ifEmpty { utgEdges }

        val rawPath = when {
            directPath.isNotEmpty() -> directPath
            edges.isNotEmpty() && RunLogReplayPolicy.isOmniflowClickNodeGraphTool(callableTool) -> {
                selectClickNodeEdges(edges, args)
            }
            utgPathIds.isNotEmpty() && utgEdges.isNotEmpty() -> {
                val edgeById = utgEdges.associateBy { firstNonBlank(it["edge_id"], it["edgeId"], it["id"]) }
                utgPathIds.mapNotNull { rawId -> edgeById[rawId?.toString().orEmpty()] }
            }
            edges.isNotEmpty() -> selectGoToNodeEdges(edges, args)
            else -> emptyList()
        }
        return rawPath.mapNotNull { raw ->
            val edge = mapArg(raw)
            edgeToUIStep(edge)
        }
    }

    private fun selectClickNodeEdges(
        edges: List<Map<String, Any?>>,
        args: Map<String, Any?>,
    ): List<Map<String, Any?>> {
        val edgeId = firstNonBlank(args["edge_id"])
        val actionId = firstNonBlank(args["action_id"])
        val targetNodeId = firstNonBlank(args["node_id"], args["target_node_id"])
        val selected = edges.firstOrNull { edge ->
            (edgeId.isNotEmpty() && firstNonBlank(edge["edge_id"], edge["id"]) == edgeId) ||
                (actionId.isNotEmpty() && firstNonBlank(edge["action_id"]) == actionId) ||
                (targetNodeId.isNotEmpty() &&
                    firstNonBlank(edge["to_node_id"], edge["node_id"]) == targetNodeId)
        } ?: edges.firstOrNull()
        return selected?.let { listOf(it) }.orEmpty()
    }

    private fun selectGoToNodeEdges(
        edges: List<Map<String, Any?>>,
        args: Map<String, Any?>,
    ): List<Map<String, Any?>> {
        val targetNodeId = firstNonBlank(args["node_id"], args["target_node_id"])
        if (targetNodeId.isEmpty()) return edges
        val targetIndex = edges.indexOfFirst { edge ->
            firstNonBlank(edge["to_node_id"], edge["node_id"]) == targetNodeId
        }
        return if (targetIndex >= 0) edges.take(targetIndex + 1) else emptyList()
    }

    private fun edgeToUIStep(edge: Map<String, Any?>): Map<String, Any?>? {
        val action = firstNonBlank(edge["tool"], edge["type"], edge["action"])
        val localAction = RunLogReplayPolicy.omniflowActionForToolName(action) ?: return null
        val edgeArgs = linkedMapOf<String, Any?>()
        edgeArgs.putAll(mapArg(edge["args"]))
        for (key in EDGE_ARG_KEYS) {
            if (edgeArgs[key] == null && edge.containsKey(key)) {
                edgeArgs[key] = edge[key]
            }
        }
        return linkedMapOf(
            "title" to firstNonBlank(edge["title"], edge["summary"], edge["target_description"], localAction),
            "kind" to "function",
            "executor" to RunLogReplayPolicy.EXECUTOR_OMNIFLOW,
            "model_free" to true,
            "scriptable" to true,
            "tool" to localAction,
            "args" to edgeArgs,
            "source_context" to mapArg(edge["source_context"]).takeIf { it.isNotEmpty() },
        ).filterValues { it != null }
    }

    private fun stepArgs(step: Map<String, Any?>): Map<String, Any?> {
        val directArgs = mapArg(step["args"])
        val agentCall = mapArg(step["agent_call"])
        val agentArgs = mapArg(agentCall["args"])
        val originalArgs = mapArg(directArgs["original_args"])
            .ifEmpty { mapArg(directArgs["originalArgs"]) }
            .ifEmpty { mapArg(agentArgs["original_args"]) }
            .ifEmpty { mapArg(agentArgs["originalArgs"]) }
        val topLevelArgs = buildMap {
            for (key in EXECUTION_ARG_KEYS) {
                if (step.containsKey(key)) put(key, step[key])
            }
        }
        return when {
            directArgs.hasExecutionArgs() -> directArgs
            originalArgs.isNotEmpty() -> originalArgs
            topLevelArgs.isNotEmpty() -> topLevelArgs
            else -> directArgs
        }
    }

    private fun Map<String, Any?>.hasExecutionArgs(): Boolean =
        EXECUTION_ARG_KEYS.any { key -> this[key] != null }

    private fun failureStepResult(
        stepId: String,
        tool: String,
        executor: String = "omniflow_graph",
        summary: String,
        errorCode: String,
    ): Map<String, Any?> = runResultBuilder.failureStep(
        stepId = stepId,
        tool = tool,
        executor = executor,
        summary = summary,
        errorCode = errorCode,
    )

    private companion object {
        val EXECUTION_ARG_KEYS = setOf(
            "function_id",
            "tool_name",
            "target_tool",
            "node_id",
            "target_node_id",
            "edge_id",
            "action_id",
            "path",
            "edges",
            "utg",
            "graph",
            "arguments",
            "args",
            "input",
        )

        val EDGE_ARG_KEYS = OobCanonicalActionSchema.sourceContextArgNames
    }
}
