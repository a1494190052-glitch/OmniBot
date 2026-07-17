package cn.com.omnimind.bot.omniflow

import android.content.Context
import android.os.SystemClock
import cn.com.omnimind.assists.task.vlmserver.DeviceOperator
import cn.com.omnimind.baselib.runlog.OobActionSchema
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.runlog.ReplayHelper
import cn.com.omnimind.bot.runlog.firstNonBlank
import cn.com.omnimind.bot.runlog.mapArg
import cn.com.omnimind.bot.runlog.resolveActionName
import cn.com.omnimind.bot.runlog.sourceContextForStep

internal class OmniFlowReplayAdapter(
    private val observe: suspend () -> Map<String, Any?>,
    private val enabled: () -> Boolean,
    private val bridgeCall: suspend (
        String,
        Map<String, Any?>,
    ) -> Map<String, Any?>,
) {
    constructor(
        context: Context,
        deviceOperator: DeviceOperator,
    ) : this(
        observe = {
            val snapshot = ReplayHelper.readBackendSnapshot(deviceOperator)
            linkedMapOf(
                "xml" to snapshot.xml,
                "package_name" to snapshot.rawPackage,
                "activity_name" to snapshot.activityName,
            )
        },
        enabled = OmniFlowPythonRuntime::isReady,
        bridgeCall = { operation, payload ->
            OmniFlowPythonRuntime.call(
                context = context.applicationContext,
                operation = operation,
                payload = payload,
                hostCall = omniFlowRunLogHostCall(context),
            )
        },
    )

    suspend fun prepareAct(
        functionId: String,
        step: Map<String, Any?>,
        sourceRunId: String = "",
        sourceActionIndex: Int = -1,
        action: String,
        args: Map<String, Any?>,
        rules: List<Map<String, Any?>>,
    ): ActionDecision {
        if (!enabled()) {
            return failure("python_not_ready", emptyMap())
        }
        val startedAt = SystemClock.elapsedRealtime()
        val observation = observe()
        val normalizedAction = resolveActionName(action) ?: OobActionSchema.normalizeToolName(action)
        val sourceContext = sourceContextForStep(step)
        val coordinateSpace = if (
            normalizedAction in OobActionSchema.coordinateToolNames &&
            sourceRunId.isNotBlank() &&
            sourceActionIndex >= 0
        ) {
            "relative_0_1000"
        } else {
            "absolute_passthrough"
        }
        val controlArgs = LinkedHashMap(args).apply {
            if (sourceContext.isNotEmpty()) put("source_context", sourceContext)
        }
        return runCatching {
            val response = bridgeCall(
                "prepare_action",
                linkedMapOf(
                    "function_id" to functionId,
                    "source_run_id" to sourceRunId.takeIf(String::isNotBlank),
                    "source_action_index" to sourceActionIndex.takeIf { it >= 0 },
                    "coordinate_space" to coordinateSpace,
                    "action" to canonicalAction(normalizedAction, controlArgs),
                    "observation" to observation,
                    "checker_rules" to rules,
                ).filterValues { it != null },
            )
            responseAttempt(
                response = response,
                expectedAction = normalizedAction,
                startedAt = startedAt,
                observation = observation,
                coordinateSpace = coordinateSpace,
            )
        }.getOrElse { error ->
            OmniLog.w(TAG, "prepare_action failed: ${error.message}")
            ActionDecision(
                kind = ActionDecisionKind.BLOCK,
                reason = "python_control_error",
                diagnostics = linkedMapOf(
                    "runtime_source" to "omniflow_python",
                    "operation" to "prepare_action",
                    "duration_ms" to elapsed(startedAt),
                    "error_type" to error.javaClass.name,
                    "error_message" to error.message.orEmpty(),
                ),
            )
        }
    }

    private fun responseAttempt(
        response: Map<String, Any?>,
        expectedAction: String,
        startedAt: Long,
        observation: Map<String, Any?>,
        coordinateSpace: String,
    ): ActionDecision {
        val returnedAction = mapArg(response["action"])
        val returnedTool = resolveActionName(firstNonBlank(returnedAction["tool"], returnedAction["type"]))
            ?: OobActionSchema.normalizeToolName(firstNonBlank(returnedAction["tool"], returnedAction["type"]))
        val kind = when (firstNonBlank(response["decision"], response["phase"]).lowercase()) {
            "ready" -> ActionDecisionKind.READY
            "recover" -> ActionDecisionKind.RECOVER
            else -> ActionDecisionKind.BLOCK
        }
        val valid = kind == ActionDecisionKind.BLOCK ||
            (response["success"] == true && returnedTool.isNotBlank())
        val returnedArgs = mapArg(returnedAction["args"])
            .ifEmpty { mapArg(returnedAction["params"]) }
            .toMutableMap()
            .apply { remove("source_context") }
        val reason = firstNonBlank(response["reason"]).ifBlank {
            if (valid) "omniflow_python_control" else "python_control_not_applied"
        }
        return ActionDecision(
            kind = if (valid) kind else ActionDecisionKind.BLOCK,
            action = returnedTool,
            args = returnedArgs,
            reason = reason,
            functionId = firstNonBlank(response["function_id"]),
            diagnostics = linkedMapOf(
                "runtime_source" to "omniflow_python",
                "operation" to "prepare_action",
                "duration_ms" to elapsed(startedAt),
                "decision" to response["decision"],
                "reason" to reason,
                "coordinate_space" to (response["coordinate_space"] ?: coordinateSpace),
                "current_xml_chars" to firstNonBlank(observation["xml"]).length,
                "expected_action" to expectedAction,
                "prepared_action" to returnedTool,
                "recovery_function_id" to response["function_id"],
                "unsupported_conditions" to response["unsupported_conditions"],
            ).filterValues { it != null },
        )
    }

    private fun canonicalAction(action: String, args: Map<String, Any?>): Map<String, Any?> =
        linkedMapOf("tool" to action, "args" to args)

    private fun failure(reason: String, diagnostics: Map<String, Any?>): ActionDecision =
        ActionDecision(
            kind = ActionDecisionKind.BLOCK,
            reason = reason,
            diagnostics = linkedMapOf(
                "runtime_source" to "omniflow_python",
                "operation" to "prepare_action",
                "reason" to reason,
            ) + diagnostics,
        )

    private fun elapsed(startedAt: Long): Long =
        (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)

    companion object {
        private const val TAG = "OmniFlowReplayAdapter"
    }
}
