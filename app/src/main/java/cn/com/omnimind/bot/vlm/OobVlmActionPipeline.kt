package cn.com.omnimind.bot.vlm

import cn.com.omnimind.assists.task.vlmserver.ClickAction
import cn.com.omnimind.assists.task.vlmserver.InputTextAction
import cn.com.omnimind.assists.task.vlmserver.LongPressAction
import cn.com.omnimind.assists.task.vlmserver.OpenAppAction
import cn.com.omnimind.assists.task.vlmserver.PressKeyAction
import cn.com.omnimind.assists.task.vlmserver.SwipeAction
import cn.com.omnimind.assists.task.vlmserver.UIAction
import cn.com.omnimind.assists.task.vlmserver.VLMActionPipeline
import cn.com.omnimind.assists.task.vlmserver.VLMActionPipelineRequest
import cn.com.omnimind.assists.task.vlmserver.VLMActionPipelineResult
import cn.com.omnimind.assists.task.vlmserver.UIStep
import cn.com.omnimind.baselib.runlog.OobCanonicalActionSchema
import cn.com.omnimind.bot.runlog.OobActionCodec
import cn.com.omnimind.bot.runlog.UIStepExecutor
import cn.com.omnimind.bot.runlog.RunLogReplayPolicy

class OobVlmActionPipeline : VLMActionPipeline {
    override suspend fun preflight(request: VLMActionPipelineRequest): VLMActionPipelineResult {
        val replayStep = buildReplayStep(request) ?: return VLMActionPipelineResult(
            step = request.step,
            diagnostics = mapOf(
                "vlm_action_pipeline_enabled" to "false",
                "vlm_action_pipeline_reason" to "unsupported_action",
            )
        )
        val startedAtMs = System.currentTimeMillis()
        val preflight = UIStepExecutor.preflight(
            step = replayStep,
            respectFixedReplayPolicy = false,
        )
        val actionName = OobActionCodec.actionNameForStep(replayStep)
        val updatedStep = request.step.copy(
            action = updateActionFromArgs(
                action = request.step.action,
                args = preflight.args,
            )
        )
        return VLMActionPipelineResult(
            step = updatedStep,
            diagnostics = buildDiagnostics(
                actionName = actionName,
                startedAtMs = startedAtMs,
                preflight = preflight,
            ),
            currentXml = preflight.currentXml,
            currentPackageName = preflight.currentPackageName,
        )
    }

    private fun buildReplayStep(request: VLMActionPipelineRequest): Map<String, Any?>? {
        val args = argsForAction(request.step.action) ?: return null
        val actionName = request.step.action.name
        val srcCtx = linkedMapOf<String, Any?>(
            "page" to request.currentXml.orEmpty(),
            "package_name" to request.currentPackageName.orEmpty(),
            "display_width" to request.displayWidth,
            "display_height" to request.displayHeight,
            "captured_at_ms" to (request.snapshot?.capturedAtMs ?: System.currentTimeMillis()),
            "source" to "online_vlm_fresh_state",
            "step_index" to request.stepIndex,
        ).filterValues { value ->
            value != null && value.toString().isNotBlank()
        }
        return linkedMapOf(
            OobCanonicalActionSchema.ROOT_TOOL to actionName,
            OobCanonicalActionSchema.ROOT_ARGS to args,
            "executor" to RunLogReplayPolicy.EXECUTOR_OMNIFLOW,
            "model_free" to true,
            "coordinate_hook" to RunLogReplayPolicy.EXECUTOR_OMNIFLOW,
            "omniflow" to true,
            "source_context" to linkedMapOf(
                "src_ctx" to srcCtx,
                "action" to args,
            ),
        )
    }

    private fun argsForAction(action: UIAction): Map<String, Any?>? = when (action) {
        is ClickAction -> linkedMapOf(
            OobCanonicalActionSchema.ARG_TARGET_DESCRIPTION to action.targetDescription,
            OobCanonicalActionSchema.ARG_ELEMENT_INDEX to action.elementIndex,
            OobCanonicalActionSchema.ARG_NODE_ID to action.nodeId,
            OobCanonicalActionSchema.ARG_X to action.x,
            OobCanonicalActionSchema.ARG_Y to action.y,
        )

        is InputTextAction -> linkedMapOf(
            OobCanonicalActionSchema.ARG_TARGET_DESCRIPTION to action.targetDescription,
            OobCanonicalActionSchema.ARG_TEXT to action.text,
            OobCanonicalActionSchema.ARG_ELEMENT_INDEX to action.elementIndex,
            OobCanonicalActionSchema.ARG_NODE_ID to action.nodeId,
            OobCanonicalActionSchema.ARG_X to action.x,
            OobCanonicalActionSchema.ARG_Y to action.y,
        )

        is SwipeAction -> linkedMapOf(
            OobCanonicalActionSchema.ARG_TARGET_DESCRIPTION to action.targetDescription,
            OobCanonicalActionSchema.ARG_SCROLLABLE_INDEX to action.scrollableIndex,
            OobCanonicalActionSchema.ARG_DIRECTION to action.direction,
            OobCanonicalActionSchema.ARG_X1 to action.x1,
            OobCanonicalActionSchema.ARG_Y1 to action.y1,
            OobCanonicalActionSchema.ARG_X2 to action.x2,
            OobCanonicalActionSchema.ARG_Y2 to action.y2,
            OobCanonicalActionSchema.ARG_DURATION_MS to action.durationMs,
        )

        is LongPressAction -> linkedMapOf(
            OobCanonicalActionSchema.ARG_TARGET_DESCRIPTION to action.targetDescription,
            OobCanonicalActionSchema.ARG_ELEMENT_INDEX to action.elementIndex,
            OobCanonicalActionSchema.ARG_NODE_ID to action.nodeId,
            OobCanonicalActionSchema.ARG_X to action.x,
            OobCanonicalActionSchema.ARG_Y to action.y,
        )

        is OpenAppAction -> linkedMapOf(
            OobCanonicalActionSchema.ARG_PACKAGE_NAME to action.packageName,
        )

        is PressKeyAction -> linkedMapOf(
            OobCanonicalActionSchema.ARG_KEY to action.key,
        )

        else -> null
    }?.filterValues { value -> value != null && value.toString().isNotBlank() }

    private fun updateActionFromArgs(action: UIAction, args: Map<String, Any?>): UIAction = when (action) {
        is ClickAction -> action.copy(
            targetDescription = stringArg(args, OobCanonicalActionSchema.ARG_TARGET_DESCRIPTION)
                ?: action.targetDescription,
            x = floatArg(args, OobCanonicalActionSchema.ARG_X) ?: action.x,
            y = floatArg(args, OobCanonicalActionSchema.ARG_Y) ?: action.y,
        )

        is InputTextAction -> action.copy(
            targetDescription = stringArg(args, OobCanonicalActionSchema.ARG_TARGET_DESCRIPTION)
                ?: action.targetDescription,
            text = stringArg(args, OobCanonicalActionSchema.ARG_TEXT) ?: action.text,
            x = floatArg(args, OobCanonicalActionSchema.ARG_X) ?: action.x,
            y = floatArg(args, OobCanonicalActionSchema.ARG_Y) ?: action.y,
        )

        is SwipeAction -> action.copy(
            targetDescription = stringArg(args, OobCanonicalActionSchema.ARG_TARGET_DESCRIPTION)
                ?: action.targetDescription,
            x1 = floatArg(args, OobCanonicalActionSchema.ARG_X1) ?: action.x1,
            y1 = floatArg(args, OobCanonicalActionSchema.ARG_Y1) ?: action.y1,
            x2 = floatArg(args, OobCanonicalActionSchema.ARG_X2) ?: action.x2,
            y2 = floatArg(args, OobCanonicalActionSchema.ARG_Y2) ?: action.y2,
        )

        is LongPressAction -> action.copy(
            targetDescription = stringArg(args, OobCanonicalActionSchema.ARG_TARGET_DESCRIPTION)
                ?: action.targetDescription,
            x = floatArg(args, OobCanonicalActionSchema.ARG_X) ?: action.x,
            y = floatArg(args, OobCanonicalActionSchema.ARG_Y) ?: action.y,
        )

        is OpenAppAction -> action.copy(
            packageName = stringArg(args, OobCanonicalActionSchema.ARG_PACKAGE_NAME)
                ?: action.packageName,
        )

        else -> action
    }

    private fun buildDiagnostics(
        actionName: String,
        startedAtMs: Long,
        preflight: UIStepExecutor.PreflightResult,
    ): Map<String, String> {
        val timing = preflight.timing
        val phaseMs = OobActionCodec.mapArg(timing["phase_ms"])
        return linkedMapOf<String, String>().apply {
            put("vlm_action_pipeline_enabled", "true")
            put("vlm_action_pipeline_action", actionName)
            put("vlm_action_pipeline_started_at_ms", startedAtMs.toString())
            put("vlm_action_pipeline_finished_at_ms", (timing["finished_at_ms"] ?: System.currentTimeMillis()).toString())
            put("vlm_action_pipeline_duration_ms", (timing["duration_ms"] ?: 0).toString())
            put("vlm_action_pipeline_observe_ms", (phaseMs["observe_ms"] ?: 0).toString())
            put("vlm_action_pipeline_checker_ms", (phaseMs["checker_ms"] ?: 0).toString())
            put("vlm_action_pipeline_action_transfer_ms", (phaseMs["action_transfer_ms"] ?: 0).toString())
            put("vlm_action_transfer_applied", (preflight.transfer["applied"] == true).toString())
            firstNonBlank(preflight.transfer["reason"])?.let {
                put("vlm_action_transfer_reason", it)
            }
            put("vlm_checker_effect_count", preflight.controlEffects.size.toString())
        }
    }

    private fun stringArg(args: Map<String, Any?>, key: String): String? =
        args[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    private fun floatArg(args: Map<String, Any?>, key: String): Float? {
        val value = args[key] ?: return null
        return when (value) {
            is Number -> value.toFloat()
            is String -> value.trim().toFloatOrNull()
            else -> null
        }
    }

    private fun firstNonBlank(value: Any?): String? =
        value?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}
