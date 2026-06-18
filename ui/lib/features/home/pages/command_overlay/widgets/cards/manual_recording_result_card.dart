import 'package:flutter/material.dart';
import 'package:ui/services/agent_tool_card_policy.dart';

import 'agent_tool_summary_card.dart';

const String kManualRecordingResultCardType = 'manual_recording_result';

Map<String, dynamic> manualRecordingResultAgentToolCardData(
  Map<String, dynamic> cardData,
) {
  final success = cardData['success'] == true;
  final recordingSuccess =
      cardData['recordingSuccess'] == true ||
      cardData['recording_success'] == true ||
      success;
  final conversionSuccess =
      cardData['conversionSuccess'] == true ||
      cardData['conversion_success'] == true;
  final runId = (cardData['runId'] ?? cardData['run_id'] ?? '')
      .toString()
      .trim();
  final actionCount = _asInt(
    cardData['actionCount'] ?? cardData['action_count'],
  );
  final functionId = (cardData['functionId'] ?? cardData['function_id'] ?? '')
      .toString()
      .trim();
  final diagnostics = _asMap(cardData['diagnostics']);
  final manualDiagnostics = _asMap(diagnostics['manual_recording']);
  final rawTouchDiagnostics = _asMap(diagnostics['raw_touch']);
  final completeness = (manualDiagnostics['completeness'] ?? '')
      .toString()
      .trim();
  final actionSource = (manualDiagnostics['action_source'] ?? '')
      .toString()
      .trim();
  final recordingMode = switch (actionSource) {
    'mixed' => '混合采集',
    'raw_touch' || 'raw_touch_only' =>
      completeness == 'raw_touch_interrupted' ? 'raw 中断' : 'raw touch',
    'accessibility_event' => 'A11 事件',
    _ => rawTouchDiagnostics['available'] == true ? 'raw touch' : '',
  };
  final warning =
      (cardData['warningMessage'] ??
              cardData['warning_message'] ??
              cardData['recordingWarning'] ??
              cardData['recording_warning'] ??
              manualDiagnostics['warning_message'] ??
              '')
          .toString()
          .trim();
  final error = (cardData['errorMessage'] ?? cardData['error_message'] ?? '')
      .toString()
      .trim();
  final title = recordingSuccess
      ? (conversionSuccess ? '手动录制完成' : '手动录制已保存')
      : '手动录制失败';
  final summaryParts = <String>[title, if (actionCount > 0) '$actionCount 步'];
  final previewParts = <String>[
    if (warning.isNotEmpty) warning,
    if (error.isNotEmpty) error,
    if (recordingMode.isNotEmpty) recordingMode,
    if (functionId.isNotEmpty) '复用指令 $functionId',
    if (runId.isNotEmpty) 'RunLog $runId',
  ];
  final cardId = _firstNonBlank([
    cardData['cardId'],
    cardData['card_id'],
    runId.isNotEmpty ? 'manual-recording-$runId' : null,
  ]);
  final status = recordingSuccess ? 'success' : 'error';
  final statusLabel = recordingSuccess
      ? (conversionSuccess ? '已完成' : '已保存')
      : '失败';

  return <String, dynamic>{
    ...cardData,
    'type': kAgentToolSummaryCardType,
    'sourceCardType': kManualRecordingResultCardType,
    'source_card_type': kManualRecordingResultCardType,
    'cardId': cardId,
    'toolCallId': cardId,
    'tool_call_id': cardId,
    'status': status,
    'statusLabel': statusLabel,
    'status_label': statusLabel,
    'toolType': 'oob_function',
    'tool_type': 'oob_function',
    'toolName': 'manual_recording',
    'tool_name': 'manual_recording',
    'toolTitle': title,
    'tool_title': title,
    'toolTypeLabel': '手动录制',
    'tool_type_label': '手动录制',
    'displayName': '手动录制',
    'display_name': '手动录制',
    'summary': _uniqueNonBlank(summaryParts).join(' · '),
    'progress': _uniqueNonBlank(previewParts).join(' · '),
    'runLogId': runId,
    'run_log_id': runId,
    'runId': runId,
    'run_id': runId,
    'functionId': functionId,
    'function_id': functionId,
    'openRunLogAsTimeline': true,
    'open_run_log_as_timeline': true,
  }..removeWhere((_, value) {
    if (value == null) return true;
    return value is String && value.trim().isEmpty;
  });
}

class ManualRecordingResultCard extends StatelessWidget {
  const ManualRecordingResultCard({super.key, required this.cardData});

  final Map<String, dynamic> cardData;

  @override
  Widget build(BuildContext context) {
    return AgentToolSummaryCard(
      cardData: manualRecordingResultAgentToolCardData(cardData),
    );
  }
}

int _asInt(dynamic value) {
  if (value is int) return value;
  if (value is num) return value.round();
  return int.tryParse(value?.toString() ?? '') ?? 0;
}

Map<String, dynamic> _asMap(dynamic value) {
  if (value is Map<String, dynamic>) return value;
  if (value is Map) {
    return value.map((key, item) => MapEntry(key.toString(), item));
  }
  return const <String, dynamic>{};
}

String _firstNonBlank(Iterable<Object?> values) {
  for (final value in values) {
    final text = value?.toString().trim() ?? '';
    if (text.isNotEmpty) return text;
  }
  return '';
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
