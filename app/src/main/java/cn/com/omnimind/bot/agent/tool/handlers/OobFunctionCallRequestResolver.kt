package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.bot.omniflow.OobFunctionJson.firstNonBlank
import cn.com.omnimind.bot.omniflow.OobFunctionJson.mapArg
import cn.com.omnimind.bot.runlog.RunLogReplayPolicy

/**
 * Resolves Function and call_tool arguments from model calls and replay steps.
 * The replay handler decides how to execute the request; this class only owns
 * canonical argument shapes across recorded RunLogs and current tool calls.
 */
class OobFunctionCallRequestResolver {
    fun stepArgs(step: Map<String, Any?>): Map<String, Any?> {
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

    fun nestedFunctionArguments(args: Map<String, Any?>): Map<String, Any?> {
        return nestedArguments(args)
    }

    fun resolve(
        args: Map<String, Any?>,
        step: Map<String, Any?> = emptyMap(),
        isKnownFunction: (String) -> Boolean,
    ): Request {
        val targetTool = targetTool(args, step)
        val targetArgs = callToolArguments(args)
        val functionId = firstNonBlank(
            functionId(args, step),
            if (RunLogReplayPolicy.isOmniflowFunctionTool(targetTool)) {
                functionId(targetArgs, emptyMap())
            } else {
                null
            },
            targetTool.takeIf { it.isNotEmpty() && isKnownFunction(it) },
        )
        return Request(
            targetTool = targetTool,
            targetArgs = targetArgs,
            functionId = functionId,
        )
    }

    fun functionId(args: Map<String, Any?>, step: Map<String, Any?>): String = firstNonBlank(
        args["function_id"],
        step["function_id"],
    )

    fun targetTool(args: Map<String, Any?>, step: Map<String, Any?>): String = firstNonBlank(
        args["tool_name"],
        args["target_tool"],
        args["tool"],
        step["tool_name"],
        step["target_tool"],
    )

    private fun callToolArguments(args: Map<String, Any?>): Map<String, Any?> {
        return nestedArguments(args)
    }

    private fun nestedArguments(args: Map<String, Any?>): Map<String, Any?> =
        mapArg(args["arguments"])

    private fun Map<String, Any?>.hasExecutionArgs(): Boolean =
        EXECUTION_ARG_KEYS.any { key -> this[key] != null }

    data class Request(
        val targetTool: String,
        val targetArgs: Map<String, Any?>,
        val functionId: String,
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
        )
    }
}
