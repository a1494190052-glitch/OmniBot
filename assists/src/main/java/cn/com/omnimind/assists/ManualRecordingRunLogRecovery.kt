package cn.com.omnimind.assists

import cn.com.omnimind.baselib.runlog.InternalRunLogRecord
import cn.com.omnimind.baselib.runlog.OobCanonicalActionSchema

data class ManualRecordingRunLogRecoveryDecision(
    val success: Boolean,
    val doneReason: String,
    val errorMessage: String?,
    val replayableActionCount: Int,
    val diagnostics: Map<String, Any?>
)

object ManualRecordingRunLogRecovery {
    const val HUMAN_TRAJECTORY_SOURCE = "human_trajectory"
    const val DONE_REASON_RECOVERED_AFTER_RESTART = "recovered_after_restart"
    const val DONE_REASON_EMPTY_AFTER_RESTART = "empty_recording_after_restart"
    const val EMPTY_RECORDING_ERROR = "未记录到可复用的人类操作"

    private val replayableManualActions = setOf(
        OobCanonicalActionSchema.TOOL_CLICK,
        OobCanonicalActionSchema.TOOL_LONG_PRESS,
        OobCanonicalActionSchema.TOOL_SWIPE,
        "scroll",
        OobCanonicalActionSchema.TOOL_INPUT_TEXT,
        OobCanonicalActionSchema.TOOL_PRESS_KEY
    )

    fun decisionFor(record: InternalRunLogRecord): ManualRecordingRunLogRecoveryDecision? {
        if (record.source != HUMAN_TRAJECTORY_SOURCE || record.finishedAtMs != null) {
            return null
        }
        val actionCards = record.cards.filter(::isManualReplayActionCard)
        val success = actionCards.isNotEmpty()
        return ManualRecordingRunLogRecoveryDecision(
            success = success,
            doneReason = if (success) {
                DONE_REASON_RECOVERED_AFTER_RESTART
            } else {
                DONE_REASON_EMPTY_AFTER_RESTART
            },
            errorMessage = if (success) null else EMPTY_RECORDING_ERROR,
            replayableActionCount = actionCards.size,
            diagnostics = recoveredManualRecordingDiagnostics(record.cards)
        )
    }

    fun isManualReplayActionCard(card: Map<String, Any?>): Boolean {
        val compileKind = firstNonBlank(
            card["recall_kind"],
            card["recallKind"],
            card["compile_kind"],
            card["compileKind"]
        ).lowercase()
        val toolType = firstNonBlank(card["tool_type"], card["toolType"]).lowercase()
        if (compileKind != "manual_recording" && toolType != "manual_recording") {
            return false
        }
        val action = firstNonBlank(
            card["action_type"],
            card["tool_name"],
            card["toolName"],
            (card["header"] as? Map<*, *>)?.get("tool_name"),
            (card["tool_call"] as? Map<*, *>)?.get("name"),
            ((card["source_context"] as? Map<*, *>)?.get("action") as? Map<*, *>)?.get("tool")
        ).lowercase()
        return action in replayableManualActions
    }

    fun recoveredManualRecordingDiagnostics(cards: List<Map<String, Any?>>): Map<String, Any?> {
        val actionCards = cards.filter(::isManualReplayActionCard)
        val backends = actionCards.mapNotNull(::recordingBackend)
        if (actionCards.isEmpty()) {
            return linkedMapOf(
                "manual_recording" to linkedMapOf(
                    "schema_version" to "oob.manual_recording.diagnostics.v1",
                    "recovered_after_restart" to false,
                    "action_source" to "none",
                    "completeness" to DONE_REASON_EMPTY_AFTER_RESTART,
                    "guarantees_no_missing_clicks" to false,
                    "a11_replay_actions_enabled" to false,
                    "replayable_action_count" to 0,
                    "recording_backend_counts" to emptyMap<String, Int>(),
                    "error_message" to EMPTY_RECORDING_ERROR
                )
            )
        }

        val completeSyntheticBackends = setOf(
            "overlay_touch",
            "overlay_touch_text_input",
            "ime_submit",
            "manual_control"
        )
        val syntheticComplete = backends.isNotEmpty() && backends.all { it in completeSyntheticBackends }
        val manualControlOnly = backends.isNotEmpty() && backends.all { it == "manual_control" }
        val actionSource = when {
            manualControlOnly -> "manual_control"
            backends.any { it.startsWith("device_getevent") || it == "raw_touch" } -> "mixed_real_touch"
            backends.any { it == "manual_control" } -> "mixed_manual_control"
            syntheticComplete -> "overlay_touch"
            else -> backends.firstOrNull() ?: "recovered_manual_recording"
        }
        return linkedMapOf(
            "manual_recording" to linkedMapOf(
                "schema_version" to "oob.manual_recording.diagnostics.v1",
                "recovered_after_restart" to true,
                "action_source" to actionSource,
                "completeness" to when {
                    manualControlOnly -> "complete_manual_control"
                    syntheticComplete -> "complete_overlay_touch"
                    else -> "recovered_incremental_actions"
                },
                "guarantees_no_missing_clicks" to syntheticComplete,
                "a11_replay_actions_enabled" to false,
                "replayable_action_count" to actionCards.size,
                "recording_backend_counts" to backends.groupingBy { it }.eachCount()
            )
        )
    }

    private fun recordingBackend(card: Map<String, Any?>): String? {
        val params = card["params"] as? Map<*, *>
        val eventContext = card["event_context"] as? Map<*, *>
        val meta = (card["source_context"] as? Map<*, *>)?.get("_oob_meta") as? Map<*, *>
        return firstNonBlank(
            params?.get("recording_backend"),
            eventContext?.get("recording_backend"),
            meta?.get("recording_backend")
        ).takeIf { it.isNotBlank() }
    }

    private fun firstNonBlank(vararg values: Any?): String {
        for (value in values) {
            val normalized = value?.toString()?.trim().orEmpty()
            if (normalized.isNotEmpty()) return normalized
        }
        return ""
    }
}
