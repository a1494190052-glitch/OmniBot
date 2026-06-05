package cn.com.omnimind.assists

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualRecordingPolicyTest {
    @Test
    fun `a11 only replay actions stay disabled except post input app click`() {
        val source = readSource(
            "assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/ManualVlmTraceRecorder.kt"
        )

        assertFalse(source.contains("private fun recordClick("))
        assertFalse(source.contains("private fun recordFocusedTextTarget("))
        assertFalse(source.contains("private fun recordScrolled("))
        assertFalse(source.contains("ManualClickGrounding"))
        assertFalse(source.contains("ManualScrollEventPolicy"))
        assertFalse(source.contains("\"recording_backend\" to \"accessibility_event\""))
        assertTrue(source.contains("\"records_replayable_actions\" to false"))
        assertTrue(source.contains("\"a11_replay_actions_enabled\" to false"))
        assertTrue(source.contains("\"a11_post_input_click_enabled\" to true"))
        assertTrue(source.contains("private fun hasPostInputActionWindowLocked"))
        assertTrue(source.contains("recordPostInputActionLocked"))
        assertTrue(source.contains("isPostInputSourceFromExpectedApp"))
        assertTrue(
            Regex(
                "AccessibilityEvent\\.TYPE_VIEW_CLICKED,\\s*" +
                    "AccessibilityEvent\\.TYPE_VIEW_LONG_CLICKED -> \\{\\s*" +
                    "val nowMs = System\\.currentTimeMillis\\(\\)\\s*" +
                    "if \\(hasPostInputActionWindowLocked\\(nowMs\\)\\)",
                RegexOption.DOT_MATCHES_ALL
            ).containsMatchIn(source)
        )
        assertTrue(
            Regex(
                "AccessibilityEvent\\.TYPE_VIEW_TEXT_CHANGED -> \\{\\s*" +
                    "recordTextChanged\\(event, packageName, lastXmlSnapshot, lastScreenshotSnapshot, sourceSnapshot\\)",
                RegexOption.DOT_MATCHES_ALL
            ).containsMatchIn(source)
        )
        assertTrue(source.contains("AccessibilityEvent.TYPE_VIEW_FOCUSED -> suppressA11OnlyActionEvent(event)"))
        assertFalse(source.contains("handleTextInputFocus"))
        assertTrue(source.contains("AccessibilityEvent.TYPE_VIEW_SCROLLED -> suppressA11OnlyActionEvent(event)"))
    }

    @Test
    fun `a11 text input records text changed even without touch anchor`() {
        val source = readSource(
            "assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/ManualVlmTraceRecorder.kt"
        )

        assertTrue(source.contains("private data class TextInputAnchor("))
        assertTrue(source.contains("rememberTextInputAnchorFromRealTouch("))
        assertFalse(source.contains("rememberTextInputAnchorFromFocus("))
        assertTrue(source.contains("\"a11_text_input_anchor_policy\" to \"text_event_or_touch_anchor\""))
        assertTrue(
            Regex(
                "val anchor = textInputAnchor\\s*" +
                    "if \\(anchor == null\\) \\{\\s*" +
                    "recordUnanchoredTextChanged\\(" +
                    ".*" +
                    "return\\s*\\}",
                RegexOption.DOT_MATCHES_ALL
            ).containsMatchIn(source)
        )
        assertTrue(source.contains("private fun recordUnanchoredTextChanged("))
        assertTrue(source.contains("private fun unanchoredTextReplayTarget("))
        assertTrue(source.contains("private fun lastRecordedTouchTextTarget("))
        assertTrue(source.contains("private fun textEventAnchorId("))
        assertFalse(source.contains("private fun textInputAnchorFromTextEvent("))
        assertTrue(source.contains("private const val A11Y_TEXT_EVENT_BACKEND = \"a11y_text_event\""))
        assertTrue(source.contains("\"records_text_input_from_text_changed_without_anchor\" to true"))
        assertTrue(source.contains("\"unanchored_text_changed_recorded_count\" to unanchoredTextChangedRecordedCount"))
        assertTrue(source.contains("private fun preSeedFocusedTextInputAnchorFrom("))
        assertTrue(source.contains("textInputAnchor = preSeedFocusedTextInputAnchorFrom(lastXmlSnapshot, lastScreenshotSnapshot)"))
        assertTrue(source.contains("private fun focusedTextInputCandidateFromXml("))
        assertTrue(source.contains("requireText: Boolean = true"))
        assertTrue(source.contains("hasFocusedTextInputTargetInXml(lastXmlSnapshot)"))
        assertTrue(source.contains("private const val FOCUSED_XML_TEXT_INPUT_BACKEND = \"focused_xml_text_input\""))
        assertFalse(source.contains("recordFocusedTextTarget("))
    }

    @Test
    fun `manual recording does not collect after evidence`() {
        val source = readSource(
            "assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/ManualVlmTraceRecorder.kt"
        )

        assertFalse(source.contains("recordWindowTransitionObservation"))
        assertFalse(source.contains("afterXml = afterXml"))
        assertFalse(source.contains("afterScreenshot = afterScreenshot"))
        assertFalse(source.contains("RAW_TOUCH_SETTLE_MS"))
        assertFalse(source.contains("\"after_fingerprint\""))
        assertFalse(source.contains("\"after_page\""))
        assertTrue(source.contains("afterXml = null"))
        assertTrue(source.contains("afterScreenshot = null"))
    }

    @Test
    fun `manual recording persists each action incrementally without duplicate append on finish`() {
        val recorderSource = readSource(
            "assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/ManualVlmTraceRecorder.kt"
        )
        val sessionSource = readSource(
            "assists/src/main/java/cn/com/omnimind/assists/HumanTrajectoryLearningSession.kt"
        )

        assertTrue(recorderSource.contains("private val onActionRecorded: ((Int, ManualVlmRecordedAction) -> Unit)? = null"))
        assertTrue(recorderSource.contains("val index = recordedActions.size + 1"))
        assertTrue(recorderSource.contains("callback(index, action)"))
        assertTrue(sessionSource.contains("onActionRecorded = { index, action ->"))
        assertTrue(sessionSource.contains("persistRecordedAction("))
        assertTrue(sessionSource.contains("InternalRunLogStore.upsertCard("))
        assertFalse(sessionSource.contains("InternalRunLogStore.appendCards("))
    }

    @Test
    fun `overlay replay result distinguishes execution from recording`() {
        assertFalse(ManualOverlayGestureReplayResult(executed = false).recorded)
        assertTrue(ManualOverlayGestureReplayResult(executed = true).recorded)
        assertFalse(ManualOverlayGestureReplayResult(executed = true, recorded = false).recorded)
    }

    @Test
    fun `touch overlay drains bounded while recording is unfinished`() {
        val source = readSource(
            "uikit/src/main/java/cn/com/omnimind/uikit/loader/ManualTouchRecordLoader.kt"
        )
        val recorderSource = readSource(
            "assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/ManualVlmTraceRecorder.kt"
        )

        assertFalse(source.contains("PROCESSING_RESET_TIMEOUT_MS"))
        assertTrue(source.contains("withTimeoutOrNull(gestureProcessTimeoutMs(gesture))"))
        assertTrue(source.contains("manual gesture processing timeout"))
        assertTrue(source.contains("recoverAfterGestureProcessingTimeout()"))
        assertTrue(source.contains("if (error is CancellationException) throw error"))
        assertTrue(source.contains("recorded = replayResult.recorded"))
        assertTrue(source.contains("executed && !recorded"))
        assertTrue(source.contains("lockTouchLocked()"))
        assertTrue(recorderSource.contains("if (error is CancellationException && !currentCoroutineContext().isActive)"))
        assertFalse(recorderSource.contains("runCatching { performOverlayGesture(gesture) }"))
        assertTrue(
            Regex(
                "try \\{\\s*" +
                    "val dispatchOutcome = try \\{.*" +
                    "return ManualOverlayGestureReplayResult\\(.*" +
                    "\\} finally \\{\\s*" +
                    "synchronized\\(recordingLock\\) \\{\\s*" +
                    "decrementOverlayGestureActiveLocked\\(\\)",
                RegexOption.DOT_MATCHES_ALL
            ).containsMatchIn(recorderSource)
        )
        assertTrue(recorderSource.contains("while (overlayGestureActiveCount > 0)"))
        assertTrue(recorderSource.contains("OVERLAY_RECORD_DRAIN_TIMEOUT_MS"))
        assertTrue(recorderSource.contains("manual overlay drain timeout"))
        assertTrue(recorderSource.contains("if (!isStarted || isPaused)"))
        assertTrue(recorderSource.contains("recordingLock.wait(min(OVERLAY_RECORD_DRAIN_POLL_MS, remainingMs))"))
        assertTrue(recorderSource.contains("recordingLock.notifyAll()"))
    }

    @Test
    fun `accessibility source snapshot stays outside recording lock`() {
        val source = readSource(
            "assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/ManualVlmTraceRecorder.kt"
        )

        assertTrue(source.contains("val sourceSnapshot = when (event.eventType)"))
        assertTrue(source.contains("handleAccessibilityEventLocked(event, sourceSnapshot)"))
        assertTrue(source.contains("recordTextChanged(event, packageName, lastXmlSnapshot, lastScreenshotSnapshot, sourceSnapshot)"))
        assertTrue(source.contains("recordPostInputActionLocked(event, packageName, nowMs, sourceSnapshot)"))
        assertFalse(source.contains("val source = sourceSnapshotFromEvent(event)\n        val bounds = source?.bounds"))
    }

    @Test
    fun `ime opening keeps keyboard pass through while app area stays recorded`() {
        val source = readSource(
            "uikit/src/main/java/cn/com/omnimind/uikit/loader/ManualTouchRecordLoader.kt"
        )
        val recorderSource = readSource(
            "assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/ManualVlmTraceRecorder.kt"
        )

        assertFalse(recorderSource.contains("onGestureReplayStarted"))
        assertFalse(recorderSource.contains("onGestureReplayFinished"))
        assertFalse(source.contains("onGestureReplayStarted"))
        assertFalse(source.contains("onGestureReplayFinished"))
        assertTrue(source.contains("if (keyboardBlackBoxGesture)"))
        assertTrue(source.contains("manual keyboard touch consumed by overlay; cropped overlay without replay"))
        assertFalse(source.contains("HumanTrajectoryLearningSession.replayOverlayGestureWithoutRecording(gesture)"))
        assertTrue(source.contains("HumanTrajectoryLearningSession.recordOverlayGesture(gesture) {"))
        assertTrue(source.contains("private fun isKeyboardBlackBoxGestureLocked(gesture: ManualOverlayTouchGesture): Boolean"))
        assertFalse(source.contains("shouldRefreshFocusedInputForGestureLocked(gesture)"))
        assertFalse(source.contains("HumanTrajectoryLearningSession.refreshFocusedTextInputTarget()"))
        assertFalse(source.contains("HumanTrajectoryLearningSession.refreshFocusedTextInputValue()"))
        assertTrue(source.contains("HumanTrajectoryLearningSession.hasActiveTextInputAnchor()"))
        assertTrue(source.contains("minOf(imeTop, estimatedTop)"))
        assertTrue(source.contains("beginSyntheticReplaySuppressionLocked()"))
        assertTrue(source.contains("shouldSuppressReplayTouch(event)"))
        assertFalse(recorderSource.contains("suspend fun replayOverlayGestureWithoutRecording("))
        assertTrue(recorderSource.contains("fun hasActiveTextInputAnchor(): Boolean"))
        assertFalse(recorderSource.contains("fun refreshFocusedTextInputTarget(): Boolean"))
        assertFalse(recorderSource.contains("fun refreshFocusedTextInputValue(): Boolean"))
        assertFalse(recorderSource.contains("focused_xml_ime_poll"))
        assertFalse(recorderSource.contains("private const val FOCUSED_XML_TEXT_TARGET_BACKEND = \"focused_xml_text_target\""))
        assertFalse(recorderSource.contains("recorded = false"))
        assertFalse(source.contains("scheduleReplayRelockLocked"))
        assertFalse(source.contains("enterImeBypassLocked"))
        assertFalse(source.contains("ManualRecordingImeBypassSignal"))
        assertTrue(source.contains("scheduleImeVisibilityProbeLocked(clearWhenMissing = !mayOpenIme)"))
        assertTrue(source.contains("overlayHeightForParamsLocked(touchable, displaySize.y)"))
        assertTrue(source.contains("imeTouchableTopLocked(fullHeight)?.coerceIn(1, touchableBottom)"))
        assertTrue(source.contains("private fun touchableBottomLocked(fullHeight: Int): Int"))
        assertFalse(source.contains("shouldKeepImePassthroughWithoutTopLocked()"))
        assertFalse(source.contains("?: if (shouldKeepImePassthroughWithoutTopLocked()) 1 else touchableBottom"))
        assertTrue(source.contains("private fun imeTopLocked(): Int?"))
        assertTrue(source.contains("private fun keyboardTopForGestureLocked(displayHeight: Int): Int?"))
        assertTrue(source.contains("WindowInsets.Type.ime()"))
        assertFalse(source.contains("scheduleImeGeometryProbeLocked"))
        assertFalse(source.contains("HumanTrajectoryLearningSession.probeManualImeOverlayTop("))
        assertTrue(source.contains("AccessibilityWindowInfo.TYPE_INPUT_METHOD"))
        assertFalse(source.contains("scanImeNodeTop"))
        assertTrue(source.contains("private fun trustedImeTopLocked(top: Int, displayHeight: Int): Int?"))
        assertFalse(source.contains("private fun cachedReliableImeTopLocked(displayHeight: Int): Int?"))
        assertFalse(source.contains("lastReliableImeTop"))
        assertFalse(source.contains("lastImeGeometrySeenAtMs"))
        assertTrue(source.contains("private fun estimatedImeTopLocked(displayHeight: Int): Int?"))
        assertTrue(source.contains("val hasTextInput = HumanTrajectoryLearningSession.hasActiveTextInputAnchor()"))
        assertTrue(source.contains("if (!hasTextInput && !expectedIme)"))
        assertTrue(source.contains("rememberImeOpenExpectedLocked()"))
        assertTrue(source.contains("IME_ESTIMATED_TOP_RATIO"))
        assertFalse(source.contains("fallbackImeTop(displayHeight)"))
        assertTrue(source.contains("scheduleImeRelockLocked()"))
        assertFalse(source.contains("awaitImeVisible"))
        assertTrue(source.contains("private fun isKeyboardSubmitGestureLocked(gesture: ManualOverlayTouchGesture): Boolean"))
        assertTrue(source.contains("HumanTrajectoryLearningSession.prepareImeSubmitRecording()"))
        assertFalse(source.contains("HumanTrajectoryLearningSession.recordImeSubmitGesture(gesture)"))
        assertTrue(recorderSource.contains("TEXT_INPUT_ANCHOR_ACTIVE_TTL_MS"))
        assertFalse(recorderSource.contains("if (target == null && !beforeXml.isNullOrBlank())"))
        assertFalse(recorderSource.contains("val anchorTarget = target ?: coordinateTextAnchorTarget("))
        assertTrue(recorderSource.contains("if (target == null) {"))
        assertTrue(recorderSource.contains("textInputAnchor = null"))
        assertTrue(recorderSource.contains("if (!hasTextChangedFromAnchor(anchor, safeText)) return false"))
        assertTrue(recorderSource.contains("materializePendingTextFromXml("))
        assertTrue(recorderSource.contains("FOCUSED_XML_TEXT_FALLBACK"))
        assertTrue(recorderSource.contains("normalizeInputTextContent("))
        assertTrue(recorderSource.contains("\"搜索\""))
        assertTrue(recorderSource.contains("if (xml.isNullOrBlank()) return false"))
        assertTrue(recorderSource.contains("if (candidates.isEmpty()) return false"))
        assertTrue(recorderSource.contains("suppressRecentOverlayTextChangeClickLocked(now, anchor, target)"))
        assertTrue(recorderSource.contains("TEXT_INPUT_KEYBOARD_CLICK_SUPPRESS_WINDOW_MS"))
        assertTrue(recorderSource.contains("TEXT_INPUT_ANCHOR_CLICK_GRACE_MS"))
        assertTrue(recorderSource.contains("keyboard_click_suppressed_count"))
        assertTrue(
            Regex(
                "if \\(action\\.actionName != \"click\"\\) return\\s*" +
                    "if \\(action\\.params\\[\"recording_backend\"\\]\\?\\.toString\\(\\) != OVERLAY_TOUCH_BACKEND\\) return\\s*" +
                    "if \\(anchor != null &&",
                RegexOption.DOT_MATCHES_ALL
            ).containsMatchIn(recorderSource)
        )
        assertTrue(
            Regex(
                "val elapsedMs = nowMs - action\\.finishedAtMs\\s*" +
                    "if \\(elapsedMs !in 0L\\.\\.TEXT_INPUT_KEYBOARD_CLICK_SUPPRESS_WINDOW_MS\\) return\\s*" +
                    "val x = action\\.params\\[\"x\"\\]\\.asFloatOrNull\\(\\) \\?: return\\s*" +
                    "val y = action\\.params\\[\"y\"\\]\\.asFloatOrNull\\(\\) \\?: return\\s*" +
                    "if \\(target\\.bounds\\.containsPoint\\(x, y\\) \\|\\| anchor\\?\\.target\\?\\.bounds\\?\\.containsPoint\\(x, y\\) == true\\)",
                RegexOption.DOT_MATCHES_ALL
            ).containsMatchIn(recorderSource)
        )
        assertFalse(recorderSource.contains("probeImeOverlayTop("))
        assertFalse(recorderSource.contains("foregroundAppVisibleBottomFromFilteredXml("))
        assertFalse(recorderSource.contains("IME_FILTERED_APP_TOP_MAX_RATIO"))
        assertTrue(recorderSource.contains("fun prepareImeSubmitRecording(): Boolean"))
        assertTrue(recorderSource.contains("focused_xml_ime_submit"))
        assertFalse(recorderSource.contains("fun recordImeSubmitGesture(gesture: ManualOverlayTouchGesture): Boolean"))
        assertFalse(recorderSource.contains("actionName = \"press_key\""))
        assertFalse(recorderSource.contains("\"recording_backend\" to IME_SUBMIT_BACKEND"))
        assertFalse(recorderSource.contains("private const val IME_SUBMIT_BACKEND = \"ime_submit\""))
        assertFalse(recorderSource.contains("\"event_type\" to \"IME_SUBMIT_KEY\""))
    }

    @Test
    fun `missing source xml records coordinate only without stale fallback`() {
        val source = readSource(
            "assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/ManualVlmTraceRecorder.kt"
        )

        assertTrue(source.contains("captureCurrentXmlTimed("))
        assertTrue(source.contains("TimedXmlCapture("))
        assertTrue(source.contains("recordXmlCaptureTiming(reason, durationMs, xml != null)"))
        assertFalse(source.contains("} ?: synchronized(recordingLock) { lastXmlSnapshot }"))
        assertFalse(source.contains("?: AccessibilityController.getCaptureScreenShotXml(true)"))
        assertTrue(source.contains("\"before_xml_present\" to !beforeXml.isNullOrBlank()"))
        assertTrue(source.contains("\"before_xml_capture_ms\" to beforeXmlCaptureMs"))
        assertTrue(source.contains("\"xml_capture\" to linkedMapOf"))
        assertTrue(source.contains("\"schema_version\" to \"oob.manual_recording.xml_capture_timing.v1\""))
        assertTrue(source.contains("\"avg_ms\" to if (xmlCaptureCount > 0)"))
        assertFalse(source.contains("coordinateTextAnchorTarget("))
        assertFalse(source.contains("coordinate_text_anchor_unresolved"))
        assertTrue(source.contains("if (target == null) {"))
        val sessionSource = readSource(
            "assists/src/main/java/cn/com/omnimind/assists/HumanTrajectoryLearningSession.kt"
        )
        assertTrue(sessionSource.contains("SOURCE_CONTEXT_MODE_COORDINATE_ONLY_NO_XML"))
        assertTrue(sessionSource.contains("\"source_context_mode\" to SOURCE_CONTEXT_MODE_COORDINATE_ONLY_NO_XML.takeIf"))
        assertTrue(sessionSource.contains("\"missing_source_xml\" to true.takeIf"))
        assertTrue(sessionSource.contains("\"runlog_persistence_timing\" to persistenceTiming"))
        assertTrue(sessionSource.contains("\"schema_version\" to \"oob.manual_recording.persist_timing.v1\""))
    }

    @Test
    fun `manual overlays do not use accessibility overlay or per-step z order rebuild`() {
        val source = readSource(
            "uikit/src/main/java/cn/com/omnimind/uikit/loader/ManualTouchRecordLoader.kt"
        )
        val controlSource = readSource(
            "uikit/src/main/java/cn/com/omnimind/uikit/loader/ManualRecordingControlOverlay.kt"
        )

        assertFalse(source.contains("WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY"))
        assertFalse(controlSource.contains("WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY"))
        assertFalse(source.contains("ensureOnTop"))
        assertTrue(source.contains("WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY"))
        assertTrue(controlSource.contains("WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY"))
        assertFalse(controlSource.contains("fun ensureOnTop"))
        assertTrue(controlSource.contains("showTransientStatus(\"开启悬浮窗权限\""))
        assertFalse(source.contains("ManualRecordingControlOverlay.showTransientStatus"))
        assertFalse(source.contains("ClickIndicator("))
        assertFalse(controlSource.contains("moveControlOverlayToNextPosition"))
        assertFalse(controlSource.contains("dockPositionIndex"))
        assertFalse(controlSource.contains("移动"))
        assertFalse(controlSource.contains("manual_recording_capture_action"))
        assertFalse(controlSource.contains("handleCaptureClick"))
        assertFalse(controlSource.contains("text = \"截图\""))
        assertTrue(controlSource.contains("manual_recording_cancel_action"))
        assertTrue(controlSource.contains("fun cancelRecording(message: String = \"人工轨迹学习已取消\")"))
        assertTrue(controlSource.contains("HumanTrajectoryLearningSession.cancelActive(message)"))
        assertTrue(controlSource.contains("HumanTrajectoryLearningSession.completeActive()"))
        assertTrue(controlSource.contains("visibility = if (state == State.PREPARING) View.GONE else View.VISIBLE"))
        assertTrue(controlSource.contains("contentDescription = \"完成并保存手动录制\""))
        assertTrue(controlSource.contains("text = \"录制\""))
        assertTrue(controlSource.contains("keepControlsAboveTouchRecorderOnce()"))
        val catStepSource = readSource(
            "uikit/src/main/java/cn/com/omnimind/uikit/api/callbackimpl/CatStepLayoutApiImpl.kt"
        )
        val manualStopBranch = Regex(
            "if \\(HumanTrajectoryLearningSession\\.isActive\\(\\)\\) \\{(.*?)\\n\\s*return\\s*\\}",
            RegexOption.DOT_MATCHES_ALL
        ).find(catStepSource)?.groupValues?.get(1).orEmpty()
        assertTrue(manualStopBranch.contains("ManualRecordingControlOverlay.cancelRecording"))
        assertFalse(manualStopBranch.contains("HumanTrajectoryLearningSession.completeActive()"))
        val frontendSource = readSource(
            "app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/OobFunctionFrontendSessionController.kt"
        )
        assertTrue(frontendSource.contains("isShowStop = false"))
        assertTrue(frontendSource.contains("isTouchable = false"))
        assertTrue(frontendSource.contains("forceOnTop = true"))
        assertTrue(frontendSource.contains("val progressText = progress.trim().ifBlank { label }.take(48)"))
        assertTrue(frontendSource.contains("subMessage = helper.localized(progressText)"))
        assertTrue(frontendSource.contains("message = helper.localized(message.ifBlank { \"任务已完成\" })"))
        assertTrue(frontendSource.contains("OobFunctionStopOverlay("))
        assertTrue(frontendSource.contains("OmniFlowUiSession.requestStopSession(runId)"))
        assertTrue(frontendSource.contains("stopOverlay.show()"))
        assertTrue(frontendSource.contains("stopOverlay.hide()"))
        assertTrue(frontendSource.contains("if (closeAfterMs > 0L)"))
        assertTrue(frontendSource.contains("DraggableBallInstance.finishDoingTask("))
        assertFalse(frontendSource.contains("subMessage = helper.localized(\"执行中\")"))
        assertFalse(frontendSource.contains("isShowStop = true"))
        assertFalse(frontendSource.contains("isTouchable = true"))
        val functionHandlerSource = readSource(
            "app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/OobFunctionToolHandler.kt"
        )
        val assistsCoreManagerSource = readSource(
            "app/src/main/java/cn/com/omnimind/bot/manager/AssistsCoreManager.kt"
        )
        assertTrue(functionHandlerSource.contains("frontendSession?.update(\"第 \$stepIndex/\${steps.size} 步 \$stepTitle\")"))
        assertTrue(functionHandlerSource.contains("FRONTEND_SUCCESS_POPUP_VISIBLE_MS = 900L"))
        assertTrue(functionHandlerSource.contains("FRONTEND_TERMINAL_POPUP_VISIBLE_MS = 2500L"))
        assertTrue(functionHandlerSource.contains("frontendSession?.finish(frontendFinishMessage, closeAfterMs = frontendCloseAfterMs)"))
        assertFalse(assistsCoreManagerSource.contains("hideForReplay()"))
        assertFalse(assistsCoreManagerSource.contains("restoreAfterReplay()"))
        assertTrue(
            Regex(
                "frontendSession\\?\\.throwIfStopRequested\\(\\)\\s*" +
                    "toolHandle\\?\\.throwIfStopRequested\\(\\)\\s*" +
                    "val stepFinishedAtMs = System\\.currentTimeMillis\\(\\)",
                RegexOption.DOT_MATCHES_ALL
            ).containsMatchIn(functionHandlerSource)
        )
        val dragSuppression = Regex(
            "private fun beginDragRecordingSuppression\\(\\) \\{(.*?)\\n    \\}",
            RegexOption.DOT_MATCHES_ALL
        ).find(controlSource)?.groupValues?.get(1).orEmpty()
        val dragResume = Regex(
            "private fun endDragRecordingSuppression\\(\\) \\{(.*?)\\n    \\}",
            RegexOption.DOT_MATCHES_ALL
        ).find(controlSource)?.groupValues?.get(1).orEmpty()
        assertFalse(dragSuppression.contains("pauseActive"))
        assertFalse(dragResume.contains("resumeActive"))
    }

    @Test
    fun `vlm execution stop stays clickable and requests stop before complete`() {
        val taskEventSource = readSource(
            "uikit/src/main/java/cn/com/omnimind/uikit/api/uieventimpl/UITaskEventImpl.kt"
        )
        val draggableSource = readSource(
            "uikit/src/main/java/cn/com/omnimind/uikit/loader/cat/DraggableBallInstance.kt"
        )
        val catStepSource = readSource(
            "uikit/src/main/java/cn/com/omnimind/uikit/api/callbackimpl/CatStepLayoutApiImpl.kt"
        )

        assertTrue(taskEventSource.contains("ScreenMaskLoader.loadLockScreenMask()"))
        assertTrue(taskEventSource.contains("forceOnTop = true"))
        assertTrue(draggableSource.contains("forceOnTop: Boolean = false"))
        assertTrue(draggableSource.contains("windowManager.removeViewImmediate(view)"))
        assertTrue(draggableSource.contains("windowManager.addView(view, instance.catDialogShowInfoViewParams)"))
        assertTrue(draggableSource.contains("showInfoView top refresh fallback"))

        val stopIndex = catStepSource.indexOf("AgentVlmUiSession.requestStopActiveSession()")
        val completeIndex = catStepSource.indexOf("completeActiveVlmUiSession()")
        assertTrue(stopIndex >= 0)
        assertTrue(completeIndex >= 0)
        assertTrue(stopIndex < completeIndex)
    }

    private fun readSource(relativePath: String): String {
        val candidates = listOf(
            Paths.get(relativePath),
            Paths.get("..").resolve(relativePath)
        )
        val path = candidates.firstOrNull { Files.exists(it) }
            ?: error("Missing source file: $relativePath from ${Paths.get("").toAbsolutePath()}")
        return String(Files.readAllBytes(path))
    }
}
