import 'dart:async';

import 'package:flutter/material.dart';
import 'package:ui/features/home/pages/command_overlay/services/manual_recording_permission_guard.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/manual_recording_result_card.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/utils/ui.dart';

class ManualRecordingFlowMessageIds {
  const ManualRecordingFlowMessageIds({
    required this.userMessageId,
    required this.aiMessageId,
  });

  final String userMessageId;
  final String aiMessageId;
}

typedef ManualRecordingAuthorizer =
    Future<bool> Function(BuildContext context);

typedef ManualRecordingNativeStarter =
    Future<Map<String, dynamic>> Function({
      required bool enableDebugScreenshots,
    });

class ManualRecordingFlowController {
  const ManualRecordingFlowController._();

  static bool isCommand(String messageText) {
    final normalized = messageText.trim().toLowerCase();
    return normalized == '手动录制' ||
        normalized == '开始手动录制' ||
        normalized == '人工录制' ||
        normalized == '录制轨迹' ||
        normalized == '开始录制轨迹' ||
        normalized == '轨迹录制' ||
        normalized == 'manual recording' ||
        normalized == 'manual record' ||
        normalized == 'record trajectory' ||
        normalized == 'start recording';
  }

  static Map<String, dynamic> resultCardData({
    required String messageId,
    required Map<String, dynamic> result,
  }) {
    final success = result['success'] == true;
    final recordingSuccess =
        result['recording_success'] ?? result['recordingSuccess'] ?? success;
    final conversionSuccess =
        result['conversion_success'] ?? result['conversionSuccess'];
    final runId = (result['run_id'] ?? result['runId'] ?? '').toString();
    final actionCount = result['action_count'] ?? result['actionCount'] ?? 0;
    final functionId = result['function_id'] ?? result['functionId'];
    final functionRegistered =
        result['function_registered'] ?? result['functionRegistered'];
    final agentVisible = result['agent_visible'] ?? result['agentVisible'];
    final errorMessage = result['error_message'] ?? result['errorMessage'];
    return manualRecordingResultAgentToolCardData(
      <String, dynamic>{
        'type': kManualRecordingResultCardType,
        'cardId': messageId,
        'success': success,
        'recordingSuccess': recordingSuccess,
        'recording_success': recordingSuccess,
        'conversionSuccess': conversionSuccess,
        'conversion_success': conversionSuccess,
        'runId': runId,
        'run_id': runId,
        'actionCount': actionCount,
        'action_count': actionCount,
        'functionId': functionId,
        'function_id': functionId,
        'functionRegistered': functionRegistered,
        'function_registered': functionRegistered,
        'agent_visible': agentVisible,
        'summary': result['summary'],
        'errorMessage': errorMessage,
        'error_message': errorMessage,
      }..removeWhere((_, value) => value == null || value.toString().isEmpty),
    );
  }

  static Future<bool> start({
    required BuildContext context,
    required FocusNode inputFocusNode,
    required String userMessageText,
    required bool recordDebugScreenshots,
    required bool Function() isMounted,
    required ManualRecordingFlowMessageIds Function(String text) addUserMessage,
    FutureOr<void> Function(ManualRecordingFlowMessageIds ids)?
    afterUserMessageAdded,
    required void Function(String messageId, Map<String, dynamic> result)
    insertResultMessage,
    FutureOr<void> Function()? beforeNativeRecording,
    FutureOr<void> Function()? afterNativeRecording,
    FutureOr<void> Function()? onFinally,
    void Function(String runId)? openRunLogTimeline,
    ManualRecordingAuthorizer? ensureAuthorized,
    ManualRecordingNativeStarter? startNativeRecording,
  }) async {
    final canRecord = await (ensureAuthorized ??
            ManualRecordingPermissionGuard.ensureAuthorized)
        .call(context);
    if (!isMounted() || !canRecord) return false;

    inputFocusNode.unfocus();
    ManualRecordingFlowMessageIds? messageIds;

    var shouldRestoreNativeSurface = false;
    var didRestoreNativeSurface = false;
    Future<void> restoreNativeSurfaceIfNeeded() async {
      if (!shouldRestoreNativeSurface || didRestoreNativeSurface) return;
      didRestoreNativeSurface = true;
      try {
        await afterNativeRecording?.call();
      } catch (error) {
        debugPrint('Failed to restore after manual recording: $error');
      }
    }

    try {
      messageIds = addUserMessage(userMessageText);
      await afterUserMessageAdded?.call(messageIds);
      showToast('开始手动录制。请执行操作，结束后点小万「完成学习」。');
      if (beforeNativeRecording != null) {
        shouldRestoreNativeSurface = true;
        await beforeNativeRecording();
      }
      final result = startNativeRecording != null
          ? await startNativeRecording(
              enableDebugScreenshots: recordDebugScreenshots,
            )
          : await AssistsMessageService.startHumanTrajectoryLearning(
              enableDebugScreenshots: recordDebugScreenshots,
            );
      await restoreNativeSurfaceIfNeeded();
      if (!isMounted()) return true;
      insertResultMessage(messageIds.aiMessageId, result);
      _showCompletionToast(result);
      _openRunLogTimelineIfAvailable(result, isMounted, openRunLogTimeline);
    } catch (error) {
      await restoreNativeSurfaceIfNeeded();
      if (!isMounted()) return true;
      final failedMessageId = messageIds?.aiMessageId;
      if (failedMessageId != null) {
        insertResultMessage(failedMessageId, {
          'success': false,
          'error_message': error.toString(),
        });
      }
      showToast(error.toString(), type: ToastType.error);
    } finally {
      if (isMounted()) {
        await onFinally?.call();
      }
    }
    return true;
  }

  static void _showCompletionToast(Map<String, dynamic> result) {
    final success = result['success'] == true;
    final conversionSuccess =
        result['conversion_success'] == true ||
        result['conversionSuccess'] == true ||
        (result['function_id'] ?? result['functionId'])
            .toString()
            .trim()
            .isNotEmpty;
    showToast(
      success
          ? (conversionSuccess ? '手动录制完成，复用指令已保存' : '手动录制完成，RunLog 已生成')
          : '手动录制失败',
      type: success ? ToastType.success : ToastType.error,
    );
  }

  static void _openRunLogTimelineIfAvailable(
    Map<String, dynamic> result,
    bool Function() isMounted,
    void Function(String runId)? openRunLogTimeline,
  ) {
    final success = result['success'] == true;
    final runId = (result['run_id'] ?? result['runId'] ?? '').toString().trim();
    if (!success || runId.isEmpty || !isMounted()) return;
    openRunLogTimeline?.call(runId);
  }
}
