package cn.com.omnimind.bot.function

import android.content.Context
import cn.com.omnimind.assists.HumanTrajectoryLearningResult
import cn.com.omnimind.assists.HumanTrajectoryLearningSession
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.manager.buildManualRecordingFinalizedPayload
import cn.com.omnimind.uikit.loader.ManualRecordingControlOverlay
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class FunctionChannel(
    context: Context,
    private val service: FunctionService,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun handle(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getInternalRunLogs" -> runApi(result, "GET_INTERNAL_RUN_LOGS_ERROR") {
                service.executeTool(FunctionApi.RUN_LOG_LIST, call.args())
            }
            "getInternalRunLogTimeline" -> runApi(result, "GET_INTERNAL_RUN_LOG_TIMELINE_ERROR") {
                service.executeTool(FunctionApi.RUN_LOG_GET, call.args())
            }
            "getInternalRunLogState" -> runApi(result, "GET_INTERNAL_RUN_LOG_STATE_ERROR") {
                service.getRunLogState(call.args())
            }
            "convertInternalRunLogToFunction" -> runApi(
                result,
                "CONVERT_INTERNAL_RUN_LOG_TO_FUNCTION_ERROR",
            ) {
                service.executeTool(FunctionApi.RUN_LOG_CONVERT, call.args())
            }
            "listFunctions" -> runApi(result, "LIST_FUNCTIONS_ERROR") {
                service.executeTool(FunctionApi.FUNCTION_LIST, call.args())
            }
            "getFunction" -> runApi(result, "GET_FUNCTION_ERROR") {
                service.executeTool(FunctionApi.FUNCTION_GET, call.args())
            }
            "registerFunction" -> runApi(result, "REGISTER_FUNCTION_ERROR") {
                service.executeTool(FunctionApi.FUNCTION_REGISTER, call.args())
            }
            "updateFunction" -> runApi(result, "UPDATE_FUNCTION_ERROR") {
                service.executeTool(FunctionApi.FUNCTION_UPDATE, call.args())
            }
            "deleteFunction" -> runApi(result, "DELETE_FUNCTION_ERROR") {
                service.executeTool(FunctionApi.FUNCTION_DELETE, call.args())
            }
            "runFunction" -> runApi(result, "RUN_FUNCTION_ERROR") {
                FunctionRun(appContext).runFunction(call.args())
            }
            "startHumanTrajectoryLearning" -> startHumanTrajectoryLearning(call, result)
            "pauseHumanTrajectoryLearning" -> pauseHumanTrajectoryLearning(result)
            "resumeHumanTrajectoryLearning" -> resumeHumanTrajectoryLearning(result)
            "getHumanTrajectoryLearningStatus" -> result.success(
                humanTrajectoryStatusPayload("status", true),
            )
            else -> result.notImplemented()
        }
    }

    private fun runApi(
        result: MethodChannel.Result,
        errorCode: String,
        block: suspend () -> Map<String, Any?>,
    ) {
        scope.launch {
            try {
                val payload = block()
                withContext(Dispatchers.Main) { result.success(payload) }
            } catch (error: Exception) {
                OmniLog.e(TAG, "$errorCode: ${error.message}")
                withContext(Dispatchers.Main) {
                    result.error(errorCode, error.displayMessage(), null)
                }
            }
        }
    }

    private fun startHumanTrajectoryLearning(call: MethodCall, result: MethodChannel.Result) {
        val args = call.args()
        val name = args.text("name").ifBlank { "人工录制轨迹" }
        val description = args.text("description").ifBlank { name }
        val enableRawTouch = args.bool("enable_raw_touch")
        val enableDebugScreenshots = args.bool("enable_debug_screenshots")

        scope.launch {
            val payload = runCatching {
                val learningResult = HumanTrajectoryLearningSession.start(
                    context = appContext,
                    name = name,
                    description = description,
                    enableRawTouch = enableRawTouch,
                    enableDebugScreenshots = enableDebugScreenshots,
                )
                val runId = HumanTrajectoryLearningSession.activeRunId()
                if (!HumanTrajectoryLearningSession.isActive() || runId == null) {
                    return@runCatching finalizedPayload(learningResult.await(), "failed")
                }
                if (!HumanTrajectoryLearningSession.pauseActive()) {
                    HumanTrajectoryLearningSession.cancelActive(
                        expectedRunId = runId,
                        message = "无法进入手动录制待机状态",
                    )
                    return@runCatching finalizedPayload(learningResult.await(), "failed")
                }

                val overlayShown = withContext(Dispatchers.Main) {
                    ManualRecordingControlOverlay.show(
                        context = appContext,
                        runId = runId,
                        state = ManualRecordingControlOverlay.State.READY,
                        onCaptureState = { humanTrajectoryStatusPayload("status", true) },
                    )
                }
                if (!overlayShown) {
                    HumanTrajectoryLearningSession.cancelActive(
                        expectedRunId = runId,
                        message = "悬浮窗无法显示，轨迹学习已取消",
                    )
                }
                finalizedPayload(
                    result = learningResult.await(),
                    phase = if (overlayShown) "finished" else "cancelled",
                )
            }.getOrElse { error ->
                OmniLog.e(TAG, "startHumanTrajectoryLearning failed: ${error.message}", error)
                mapOf(
                    "success" to false,
                    "phase" to "failed",
                    "error_code" to "HUMAN_TRAJECTORY_LEARNING_FAILED",
                    "error_message" to error.displayMessage(),
                )
            }
            withContext(Dispatchers.Main) { result.success(payload) }
        }
    }

    private fun pauseHumanTrajectoryLearning(result: MethodChannel.Result) {
        scope.launch {
            val paused = HumanTrajectoryLearningSession.pauseActive()
            if (paused) withContext(Dispatchers.Main) { ManualRecordingControlOverlay.markPaused() }
            withContext(Dispatchers.Main) {
                result.success(
                    humanTrajectoryStatusPayload(
                        phase = "paused",
                        success = paused,
                        errorCode = if (paused) null else "NO_ACTIVE_RECORDING",
                        errorMessage = if (paused) null else "No active human recording session",
                    ),
                )
            }
        }
    }

    private fun resumeHumanTrajectoryLearning(result: MethodChannel.Result) {
        scope.launch {
            val resumed = HumanTrajectoryLearningSession.resumeActive()
            if (resumed) withContext(Dispatchers.Main) {
                ManualRecordingControlOverlay.markRecording()
            }
            withContext(Dispatchers.Main) {
                result.success(
                    humanTrajectoryStatusPayload(
                        phase = "recording",
                        success = resumed,
                        errorCode = if (resumed) null else "NO_ACTIVE_RECORDING",
                        errorMessage = if (resumed) null else "No active human recording session",
                    ),
                )
            }
        }
    }

    private fun humanTrajectoryStatusPayload(
        phase: String,
        success: Boolean,
        errorCode: String? = null,
        errorMessage: String? = null,
    ): Map<String, Any?> {
        val status = HumanTrajectoryLearningSession.status().asMap()
        return linkedMapOf<String, Any?>(
            "success" to success,
            "phase" to phase,
            "recording_active" to status["recording_active"],
            "recording_paused" to status["recording_paused"],
            "run_id" to status["run_id"],
            "name" to status["name"],
            "description" to status["description"],
            "started_at_ms" to status["started_at_ms"],
            "action_count" to status["action_count"],
            "latest_action_summary" to status["latest_action_summary"],
            "pending_action_summary" to status["pending_action_summary"],
            "recording_backend" to status["recording_backend"],
            "raw_touch_enabled" to status["raw_touch_enabled"],
            "raw_touch_available" to status["raw_touch_available"],
            "debug_screenshots_enabled" to status["debug_screenshots_enabled"],
            "debug_screenshot_stored_count" to status["debug_screenshot_stored_count"],
            "debug_screenshot_failed_count" to status["debug_screenshot_failed_count"],
            "debug_screenshot_skipped_count" to status["debug_screenshot_skipped_count"],
            "status" to status,
            "error_code" to errorCode,
            "error_message" to errorMessage,
            "source" to "oob_manual_recording",
        ).filterValues { it != null }
    }

    private suspend fun finalizedPayload(
        result: HumanTrajectoryLearningResult,
        phase: String,
    ): Map<String, Any?> {
        val runLog = InternalRunLogStore.timelinePayload(appContext, result.runId)
        val conversion = if (result.success && result.actionCount > 0) {
            runCatching {
                service.executeTool(
                    FunctionApi.RUN_LOG_CONVERT,
                    mapOf(
                        "run_id" to result.runId,
                        "register" to true,
                        "agent_visible" to true,
                        "name" to result.name,
                        "description" to result.description,
                    ),
                )
            }.getOrElse { error ->
                OmniLog.e(TAG, "human recording conversion failed: ${error.message}", error)
                mapOf(
                    "success" to false,
                    "error_code" to "HUMAN_TRAJECTORY_CONVERT_FAILED",
                    "error_message" to error.displayMessage(),
                )
            }
        } else {
            null
        }
        return buildManualRecordingFinalizedPayload(
            recordingSuccess = result.success,
            phase = phase,
            diagnostics = result.diagnostics,
            recordingErrorMessage = result.errorMessage,
            runLog = runLog,
            conversion = conversion,
        )
    }

    private fun MethodCall.args(): Map<String, Any?> {
        val raw = arguments as? Map<*, *> ?: return emptyMap()
        return buildMap {
            raw.forEach { (key, value) -> if (key != null) put(key.toString(), value) }
        }
    }

    private fun Map<String, Any?>.text(key: String): String = get(key)?.toString()?.trim().orEmpty()

    private fun Map<String, Any?>.bool(key: String): Boolean = when (val value = get(key)) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.trim().lowercase() in setOf("true", "1", "yes", "y")
        else -> false
    }

    private fun Throwable.displayMessage(): String =
        message?.takeIf(String::isNotBlank) ?: toString()

    private companion object {
        const val TAG = "FunctionChannel"
    }
}
