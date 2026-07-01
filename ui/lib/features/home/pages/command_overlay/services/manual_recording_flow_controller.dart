import 'dart:async';

import 'package:flutter/material.dart';
import 'package:ui/features/home/pages/command_overlay/services/manual_recording_permission_guard.dart';
import 'package:ui/services/agent_tool_card_policy.dart';
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

typedef ManualRecordingAuthorizer = Future<bool> Function(BuildContext context);

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
        result['recording_success'] == true ||
        result['recordingSuccess'] == true ||
        success;
    final conversionSuccess =
        result['conversion_success'] == true ||
        result['conversionSuccess'] == true;
    final runId = (result['run_id'] ?? result['runId'] ?? '').toString().trim();
    final actionCount = _asInt(result['action_count'] ?? result['actionCount']);
    final functionId = (result['function_id'] ?? result['functionId'] ?? '')
        .toString()
        .trim();
    final functionRegistered =
        result['function_registered'] ?? result['functionRegistered'];
    final agentVisible = result['agent_visible'] ?? result['agentVisible'];
    final errorMessage =
        (result['error_message'] ?? result['errorMessage'] ?? '')
            .toString()
            .trim();
    final warningMessage =
        (result['warning_message'] ??
                result['warningMessage'] ??
                result['recording_warning'] ??
                result['recordingWarning'] ??
                '')
            .toString()
            .trim();
    final title = recordingSuccess
        ? (conversionSuccess ? '手动录制完成' : '手动录制已保存')
        : '手动录制失败';
    final status = recordingSuccess ? 'success' : 'error';
    final statusLabel = recordingSuccess
        ? (conversionSuccess ? '已完成' : '已保存')
        : '失败';
    final summary = _uniqueNonBlank([
      (result['summary'] ?? '').toString(),
      title,
      if (actionCount > 0) '$actionCount 步',
    ]).join(' · ');
    final progress = _uniqueNonBlank([
      warningMessage,
      errorMessage,
      if (functionId.isNotEmpty) '复用指令 $functionId',
      if (runId.isNotEmpty) '轨迹 $runId',
    ]).join(' · ');
    return <String, dynamic>{
      'type': kAgentToolSummaryCardType,
      'cardId': messageId,
      'toolCallId': messageId,
      'tool_call_id': messageId,
      'success': success,
      'recordingSuccess': recordingSuccess,
      'recording_success': recordingSuccess,
      'conversionSuccess': conversionSuccess,
      'conversion_success': conversionSuccess,
      'runLogId': runId,
      'run_log_id': runId,
      'runId': runId,
      'run_id': runId,
      'actionCount': actionCount,
      'action_count': actionCount,
      'functionId': functionId,
      'function_id': functionId,
      'functionRegistered': functionRegistered,
      'function_registered': functionRegistered,
      'agent_visible': agentVisible,
      'status': status,
      'statusLabel': statusLabel,
      'status_label': statusLabel,
      'toolType': 'reusable_function',
      'tool_type': 'reusable_function',
      'toolName': 'manual_recording',
      'tool_name': 'manual_recording',
      'toolTitle': title,
      'tool_title': title,
      'toolTypeLabel': '手动录制',
      'tool_type_label': '手动录制',
      'displayName': '手动录制',
      'display_name': '手动录制',
      'summary': summary,
      'progress': progress,
      'errorMessage': errorMessage,
      'error_message': errorMessage,
      'openRunLogAsTimeline': true,
      'open_run_log_as_timeline': true,
    }..removeWhere((_, value) {
      if (value == null) return true;
      return value is String && value.trim().isEmpty;
    });
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
    final canRecord =
        await (ensureAuthorized ??
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
          ? (conversionSuccess ? '手动录制完成，复用指令已保存' : '手动录制完成，轨迹已生成')
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

int _asInt(dynamic value) {
  if (value is int) return value;
  if (value is num) return value.round();
  return int.tryParse(value?.toString() ?? '') ?? 0;
}

List<String> _uniqueNonBlank(Iterable<String> values) {
  final emitted = <String>{};
  final result = <String>[];
  for (final value in values) {
    final text = value.trim();
    if (text.isEmpty || !emitted.add(text)) continue;
    result.add(text);
  }
  return result;
}
