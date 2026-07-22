package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.assists.runlog.OmniFlowRecordStepExecutor
import cn.com.omnimind.baselib.runlog.CanonicalActionConverter
import cn.com.omnimind.baselib.runlog.RunLogStepRecord

internal object ManualRunLogStepRecorder {
    suspend fun record(
        index: Int,
        stepId: String,
        action: ManualVlmRecordedAction,
        source: String,
        executor: OmniFlowRecordStepExecutor,
    ): RunLogStepRecord = executor.recordStep(
        build(index, stepId, action, source),
    )

    fun build(
        index: Int,
        stepId: String,
        action: ManualVlmRecordedAction,
        source: String,
    ): RunLogStepRecord {
        val durationMs = (action.finishedAtMs - action.startedAtMs).coerceAtLeast(0L)
        val displaySize = displaySize(action)
        val canonicalAction = CanonicalActionConverter.convert(
            tool = action.action.tool,
            args = action.action.argsMap(),
            replayableOnly = true,
        )
        val beforeState = state(
            stateId = "$stepId-before",
            xml = action.beforeXml,
            screenshotPath = action.beforeScreenshot?.path,
            packageName = action.beforePackageName,
            displaySize = displaySize,
        )
        val afterState = state(
            stateId = "$stepId-after",
            xml = action.afterXml,
            screenshotPath = action.afterScreenshot?.path,
            packageName = action.afterPackageName,
            displaySize = displaySize,
        )
        return RunLogStepRecord(
                step = linkedMapOf(
                "step_index" to index,
                "before_state_id" to beforeState.getValue("state_id"),
                "action" to canonicalAction,
                "result" to linkedMapOf(
                    "success" to true,
                ),
                "after_state_id" to afterState.getValue("state_id"),
                "diagnostics" to linkedMapOf(
                    "step_id" to stepId,
                    "title" to action.title,
                    "status" to "success",
                    "duration_ms" to durationMs,
                    "started_at_ms" to action.startedAtMs,
                    "finished_at_ms" to action.finishedAtMs,
                    "source" to source,
                    "recording_backend" to action.recordingBackend,
                    "event_context" to action.eventContext.takeIf { it.isNotEmpty() },
                    "evidence_complete" to action.evidenceComplete,
                    "evidence_error" to action.evidenceError,
                ).filterValues { it != null },
                ),
                states = listOf(beforeState, afterState),
        )
    }

    private fun displaySize(action: ManualVlmRecordedAction): CanonicalActionConverter.DisplaySize? {
        if (action.displayWidth > 0 && action.displayHeight > 0) {
            return CanonicalActionConverter.DisplaySize(
                action.displayWidth.toDouble(),
                action.displayHeight.toDouble(),
            )
        }
        return null
    }

    private fun state(
        stateId: String,
        xml: String?,
        screenshotPath: String?,
        packageName: String?,
        displaySize: CanonicalActionConverter.DisplaySize?,
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "state_id" to stateId,
        "xml" to xml,
        "screenshot_path" to screenshotPath,
        "display" to displaySize?.let {
            linkedMapOf("width" to it.width.toInt(), "height" to it.height.toInt())
        },
        "package_name" to packageName,
    ).filterValues { it != null }
}
