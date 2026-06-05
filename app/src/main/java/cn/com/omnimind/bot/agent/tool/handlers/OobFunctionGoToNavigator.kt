package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.baselib.runlog.OobReusableFunctionStore
import cn.com.omnimind.bot.runlog.OobActionCodec
import cn.com.omnimind.bot.runlog.OobUdegNodeStore
import cn.com.omnimind.bot.runlog.UIStepExecutor
import cn.com.omnimind.bot.runlog.RunLogReplayPolicy

/**
 * Handles pre-execution navigation: if the current screen doesn't match the
 * Function's recorded start page, finds a route Function in the UDEG graph,
 * runs it, and verifies the landing page before the main Function executes.
 */
class OobFunctionGoToNavigator(
    private val context: android.content.Context,
    private val runResultBuilder: OobFunctionRunResultBuilder,
) {
    data class Result(
        val failure: Map<String, Any?>? = null,
        val goToResult: Map<String, Any?>? = null,
    )

    suspend fun navigate(
        functionId: String,
        spec: Map<String, Any?>,
        auditRunId: String,
        startedAtMs: Long,
        activeSteps: List<Map<String, Any?>>,
        allowAgentFallback: Boolean,
        allowToolDelegationWithoutRouter: Boolean,
        callStack: List<String>,
        getSpec: (String) -> Map<String, Any?>?,
        runFunction: suspend (String, Map<String, Any?>, Map<String, Any?>) -> Map<String, Any?>,
    ): Result {
        if (activeSteps.isEmpty() || isGoToFunction(functionId, spec)) return Result()
        val firstSource = firstExecutableSource(activeSteps) ?: return Result()
        val udegStore = OobUdegNodeStore(context)
        val targetNodeId = udegStore.bestNodeIdForPage(
            pageXml = firstSource.pageXml,
            packageName = firstSource.packageName,
        )
        if (targetNodeId.isBlank() || !udegStore.hasNode(targetNodeId)) return Result()
        val current = UIStepExecutor.currentPageSnapshotForRecovery("function_start_check")
        val currentXml = OobActionCodec.firstNonBlank(current["observation_xml"])
        val currentPackage = OobActionCodec.firstNonBlank(
            current["effective_package"],
            current["package_name"],
        )
        if (currentXml.isBlank()) return Result()
        if (udegStore.pageMatchesNode(currentXml, currentPackage, targetNodeId)) return Result()

        val ctx = NavContext(functionId, spec, auditRunId, startedAtMs, targetNodeId, activeSteps)
        val goToEdges = udegStore.findGoToFunctions(
            currentXml = currentXml,
            currentPackage = currentPackage,
            targetNodeId = targetNodeId,
        )
        if (goToEdges.isEmpty()) {
            return Result(goToResult = mapOf(
                "success" to false,
                "result" to "No route-safe Function path found; continuing with recorded Function steps.",
                "target_node_id" to targetNodeId,
                "current_node_id" to udegStore.bestNodeIdForPage(currentXml, currentPackage),
                "current_package" to currentPackage,
            ))
        }

        val goToRuns = mutableListOf<Map<String, Any?>>()
        goToEdges.forEachIndexed { index, goToEdge ->
            val goToFunctionId = OobActionCodec.firstNonBlank(goToEdge["function_id"])
            if (goToFunctionId.isBlank()) {
                return Result(failure = ctx.notReached("Route-safe UDEG edge is missing function_id.",
                    extras = mapOf("go_to_edge" to goToEdge)))
            }
            if (goToFunctionId in callStack) {
                return Result(failure = ctx.notReached("Function route recursion detected: $goToFunctionId",
                    extras = mapOf("go_to_function_id" to goToFunctionId)))
            }
            val goToSpec = getSpec(goToFunctionId)
                ?: return Result(failure = ctx.notReached(
                    "Route Function is referenced by UDEG but missing from Function store: $goToFunctionId",
                    extras = mapOf("go_to_function_id" to goToFunctionId)))
            val missingGoToArgs = OobReusableFunctionStore.missingRequiredArguments(goToSpec, emptyMap())
            if (missingGoToArgs.isNotEmpty()) {
                return Result(failure = ctx.notReached(
                    "Route Function requires arguments and cannot be used automatically: $goToFunctionId",
                    extras = mapOf("go_to_function_id" to goToFunctionId, "missing_required_arguments" to missingGoToArgs)))
            }
            val goToRun = runFunction(
                goToFunctionId,
                goToSpec,
                OobReusableFunctionStore.materialize(goToSpec, emptyMap()),
            )
            goToRuns += mapOf(
                "path_index" to index,
                "function_id" to goToFunctionId,
                "from_node_id" to goToEdge["from_node_id"],
                "to_node_id" to goToEdge["to_node_id"],
                "run" to compactToolResult(goToRun),
            )
            if (goToRun["success"] != true) {
                return Result(failure = ctx.notReached(
                    "Route Function failed before starting target Function: $goToFunctionId",
                    errorCode = "OOB_FUNCTION_GO_TO_FAILED",
                    extras = mapOf("go_to_function_id" to goToFunctionId, "go_to_path" to goToRuns)))
            }
        }

        val afterGoTo = UIStepExecutor.currentPageSnapshotForRecovery("after_go_to_function")
        val afterXml = OobActionCodec.firstNonBlank(afterGoTo["observation_xml"])
        val afterPackage = OobActionCodec.firstNonBlank(afterGoTo["effective_package"], afterGoTo["package_name"])
        if (!udegStore.pageMatchesNode(afterXml, afterPackage, targetNodeId)) {
            return Result(failure = ctx.notReached(
                "Route Function path completed but target Function start page was not reached.",
                extras = mapOf("go_to_path" to goToRuns, "after_go_to_page" to afterGoTo)))
        }
        return Result(goToResult = mapOf(
            "success" to true,
            "result" to "Reached Function start page with route-safe Function path",
            "function_ids" to goToEdges.mapNotNull {
                OobActionCodec.firstNonBlank(it["function_id"]).takeIf { id -> id.isNotBlank() }
            },
            "target_node_id" to targetNodeId,
            "path" to goToRuns,
        ))
    }

    private inner class NavContext(
        val functionId: String,
        val spec: Map<String, Any?>,
        val auditRunId: String,
        val startedAtMs: Long,
        val targetNodeId: String,
        val activeSteps: List<Map<String, Any?>>,
    ) {
        fun notReached(
            message: String,
            errorCode: String = "OOB_FUNCTION_SOURCE_NOT_REACHED",
            extras: Map<String, Any?> = emptyMap(),
        ): Map<String, Any?> {
            val firstStep = activeSteps.firstOrNull().orEmpty()
            val stepId = firstStep["id"]?.toString().orEmpty().ifBlank { "step_1" }
            val action = UIStepExecutor.actionNameForStep(firstStep).ifBlank {
                firstStep["tool"]?.toString().orEmpty().ifBlank { "unknown" }
            }
            val progress = linkedMapOf<String, Any?>(
                "target_start_node_id" to targetNodeId,
                "step_count" to activeSteps.size,
                "active_step_count" to activeSteps.size,
                "failed_step_index" to 0,
                "current_step_index" to 0,
                "current_step_number" to 1,
                "step_results" to listOf(
                    runResultBuilder.failureStep(
                        stepId = stepId,
                        tool = action,
                        executor = RunLogReplayPolicy.EXECUTOR_OMNIFLOW,
                        summary = message,
                        errorCode = errorCode,
                        extras = linkedMapOf("index" to 0),
                    )
                ),
            )
            return runResultBuilder.failedRun(
                functionId = functionId,
                spec = spec,
                auditRunId = auditRunId,
                startedAtMs = startedAtMs,
                errorCode = errorCode,
                errorMessage = message,
                extras = progress.apply { putAll(extras) },
            )
        }
    }

    private fun firstExecutableSource(activeSteps: List<Map<String, Any?>>): FunctionSourcePage? {
        activeSteps.forEach { step ->
            val action = UIStepExecutor.actionNameForStep(step)
            if (action !in OobActionCodec.executableActions ||
                action == OobActionCodec.ACTION_OPEN_APP ||
                action == OobActionCodec.ACTION_FINISHED
            ) return@forEach
            val sourceContext = OobActionCodec.sourceContextForStep(step)
            val srcCtx = OobActionCodec.mapArg(sourceContext["src_ctx"])
            val pageXml = OobActionCodec.pageXmlFromContext(srcCtx)
            if (pageXml.isBlank()) return null
            val packageName = OobActionCodec.firstNonBlank(srcCtx["package_name"], srcCtx["packageName"])
            return FunctionSourcePage(pageXml = pageXml, packageName = packageName)
        }
        return null
    }

    private fun isGoToFunction(functionId: String, spec: Map<String, Any?>): Boolean {
        val id = functionId.trim().lowercase()
        val name = OobActionCodec.firstNonBlank(spec["name"]).lowercase()
        return id == "go_to" || id.startsWith("go_to_") || id.contains("_go_to_") ||
            name == "go to" || name.startsWith("go to ") || name == "go_to" || name.startsWith("go_to_")
    }

    private fun compactToolResult(result: Map<String, Any?>): Map<String, Any?> = mapOf(
        "success" to (result["success"] == true),
        "result" to OobActionCodec.firstNonBlank(
            result["result"], result["summary"], result["error_message"], result["description"],
        ),
    )

    private data class FunctionSourcePage(val pageXml: String, val packageName: String)
}
