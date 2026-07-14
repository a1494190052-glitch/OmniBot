package cn.com.omnimind.bot.omniflow

import cn.com.omnimind.assists.task.vlmserver.ActionExecutor
import cn.com.omnimind.assists.task.vlmserver.OperationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal enum class ActionDecisionKind {
    READY,
    RECOVER,
    BLOCK,
}

internal data class ActionDecision(
    val kind: ActionDecisionKind,
    val action: String = "",
    val args: Map<String, Any?> = emptyMap(),
    val reason: String = "",
    val functionId: String = "",
    val diagnostics: Map<String, Any?> = emptyMap(),
)

internal class ActionPipeline(
    private val dispatch: suspend (
        action: String,
        args: Map<String, Any?>,
        source: String,
        diagnostics: Map<String, Any?>,
        stopRequested: (() -> Boolean)?,
    ) -> OperationResult,
    private val settle: suspend () -> Unit = { delay(RECOVERY_SETTLE_MS) },
) {
    constructor(actionExecutor: ActionExecutor) : this(
        dispatch = { action, args, source, diagnostics, stopRequested ->
            actionExecutor.act(
                action = action,
                args = args,
                source = source,
                diagnostics = diagnostics,
                stopRequested = stopRequested,
            )
        },
    )

    suspend fun execute(
        action: String,
        args: Map<String, Any?>,
        prepare: suspend (String, Map<String, Any?>) -> ActionDecision,
        stopRequested: (() -> Boolean)? = null,
        source: String = "function_replay",
    ): OperationResult {
        val controls = mutableListOf<Map<String, Any?>>()
        while (true) {
            throwIfStopped(stopRequested)
            val decision = prepare(action, args)
            when (decision.kind) {
                ActionDecisionKind.BLOCK -> return failure(
                    reason = decision.reason.ifBlank { "action_blocked" },
                    code = "OOB_OMNIFLOW_CONTROL_FAILED",
                    diagnostics = decision.diagnostics + controlDiagnostics(controls),
                )

                ActionDecisionKind.RECOVER -> {
                    if (controls.size >= MAX_RECOVERY_ACTIONS) {
                        return failure(
                            reason = "checker_recovery_limit_exceeded",
                            code = "OOB_OMNIFLOW_RECOVERY_LIMIT",
                            diagnostics = decision.diagnostics + controlDiagnostics(controls),
                        )
                    }
                    val recovery = dispatch(
                        decision.action,
                        decision.args,
                        "omniflow_checker_recovery",
                        decision.diagnostics + mapOf(
                            "recovery_function_id" to decision.functionId.takeIf(String::isNotBlank),
                        ).filterValues { it != null },
                        stopRequested,
                    )
                    controls += linkedMapOf(
                        "function_id" to decision.functionId.takeIf(String::isNotBlank),
                        "action" to decision.action,
                        "args" to decision.args,
                        "success" to recovery.success,
                        "error" to recovery.message.takeIf { !recovery.success },
                    ).filterValues { it != null }
                    if (!recovery.success) {
                        return recovery.copy(
                            diagnostics = recovery.diagnostics +
                                controlDiagnostics(controls).toStringDiagnostics(),
                        )
                    }
                    settle()
                }

                ActionDecisionKind.READY -> return dispatch(
                    decision.action,
                    decision.args,
                    source,
                    decision.diagnostics + controlDiagnostics(controls),
                    stopRequested,
                )
            }
        }
    }

    private fun failure(
        reason: String,
        code: String,
        diagnostics: Map<String, Any?>,
    ): OperationResult = OperationResult(
        success = false,
        message = reason,
        diagnostics = (diagnostics + mapOf(
            "error" to reason,
            "local_action_error_code" to code,
        )).toStringDiagnostics(),
    )

    private fun controlDiagnostics(controls: List<Map<String, Any?>>): Map<String, Any?> =
        mapOf(
            "control_count" to controls.size,
            "controls" to controls.takeIf { it.isNotEmpty() },
        ).filterValues { it != null }

    private fun throwIfStopped(stopRequested: (() -> Boolean)?) {
        if (stopRequested?.invoke() == true) {
            throw CancellationException("Function execution stopped manually")
        }
    }

    private fun Map<String, Any?>.toStringDiagnostics(): Map<String, String> =
        mapValues { (_, value) -> value?.toString().orEmpty() }
            .filterValues { it.isNotBlank() }

    private companion object {
        const val MAX_RECOVERY_ACTIONS = 3
        const val RECOVERY_SETTLE_MS = 1_000L
    }
}
