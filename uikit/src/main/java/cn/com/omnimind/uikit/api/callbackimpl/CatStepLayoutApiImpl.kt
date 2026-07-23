package cn.com.omnimind.uikit.api.callbackimpl

import cn.com.omnimind.assists.AgentVlmUiSession
import cn.com.omnimind.assists.AssistsCore
import cn.com.omnimind.assists.HumanTrajectoryLearningSession
import cn.com.omnimind.assists.FunctionUiSession
import cn.com.omnimind.assists.api.eventapi.ExecutingTaskType
import cn.com.omnimind.baselib.util.VibrationUtil
import cn.com.omnimind.uikit.UIKit
import cn.com.omnimind.uikit.api.callback.CatStepLayoutApi
import cn.com.omnimind.uikit.loader.ManualRecordingControlOverlay
import cn.com.omnimind.uikit.loader.cat.DraggableBallInstance
import cn.com.omnimind.uikit.settings.CompanionOverlaySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CatStepLayoutApiImpl : CatStepLayoutApi {
    private val manualRecordingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onResumeClick() {
        VibrationUtil.vibrateLight()

        // 只有暂停状态会回调该方法
        if (UIKit.executionTaskEventApi?.taskType == ExecutingTaskType.VLM) {
            // 快速恢复可先更新 UI
            DraggableBallInstance.doingTask("用户操作已完成", "智能执行中")
            UIKit.executionTaskEventApi?.vlmTask?.resumeFromPause()
        }
    }

    override fun onCompleteClick() {
        VibrationUtil.vibrateLight()
        val completed = UIKit.executionTaskEventApi?.vlmTask
            ?.completeManualTakeover("任务已完成") == true
        if (completed) {
            DraggableBallInstance.finishDoingTask("任务已完成")
        }
    }

    override fun onStopClick() {
        if (HumanTrajectoryLearningSession.isActive()) {
            ManualRecordingControlOverlay.cancelRecording("人工轨迹学习已取消")
            DraggableBallInstance.finishDoingTask("录制已取消")
            return
        }
        if (stopActiveVlmUiSession()) {
            return
        }
        if (stopActiveFunctionUiSession()) {
            return
        }
        DraggableBallInstance.finishDoingTask("任务已取消")
        if (UIKit.executionTaskEventApi?.taskType == ExecutingTaskType.VLM) {
            UIKit.executionTaskEventApi?.vlmTask?.finishTask()
        } else {
            AssistsCore.finishDoingTask()
        }
        if (!CompanionOverlaySettings.isEnabled()) {
            CompanionOverlaySettings.dismissFloatingUi()
        }
    }

    override fun onPauseClick() {
        VibrationUtil.vibrateLight()

        if (HumanTrajectoryLearningSession.isActive()) {
            val shouldResume = HumanTrajectoryLearningSession.isPaused()
            manualRecordingScope.launch {
                val updated = if (shouldResume) {
                    HumanTrajectoryLearningSession.resumeActive()
                } else {
                    HumanTrajectoryLearningSession.pauseActive()
                }
                withContext(Dispatchers.Main) {
                    if (!updated) {
                        return@withContext
                    }
                    if (shouldResume) {
                        ManualRecordingControlOverlay.markRecording()
                    } else {
                        ManualRecordingControlOverlay.markPaused()
                    }
                }
            }
            return
        }
        if (UIKit.executionTaskEventApi?.taskType == ExecutingTaskType.VLM) {
            DraggableBallInstance.pauseTask("用户已接管任务")
            UIKit.executionTaskEventApi?.vlmTask?.requestPause()
            return
        }
        if (FunctionUiSession.requestStopActiveSession()) {
            DraggableBallInstance.finishDoingTask("用户已接管任务")
            if (!CompanionOverlaySettings.isEnabled()) {
                CompanionOverlaySettings.dismissFloatingUi()
            }
        }
    }

    private fun stopActiveFunctionUiSession(): Boolean {
        if (!FunctionUiSession.requestStopActiveSession()) {
            return false
        }
        DraggableBallInstance.finishDoingTask("任务已取消")
        if (!CompanionOverlaySettings.isEnabled()) {
            CompanionOverlaySettings.dismissFloatingUi()
        }
        return true
    }

    private fun stopActiveVlmUiSession(): Boolean {
        val currentTaskId = UIKit.executionTaskEventApi?.vlmTask?.id
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val stopped = currentTaskId?.let {
            AgentVlmUiSession.requestStopSession(it)
        } ?: AgentVlmUiSession.requestStopActiveSession()
        if (!stopped) {
            return false
        }
        DraggableBallInstance.finishDoingTask("任务已取消")
        UIKit.executionTaskEventApi?.vlmTask?.finishTask()
        if (!CompanionOverlaySettings.isEnabled()) {
            CompanionOverlaySettings.dismissFloatingUi()
        }
        return true
    }
}
