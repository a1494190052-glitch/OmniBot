import 'package:ui/l10n/app_text_localizer.dart';
import 'package:ui/services/agent_tool_card_policy.dart';
import 'package:ui/services/assists_core_service.dart';

String functionRunToolCardIdForEvent(OobFunctionRunProgressEvent event) {
  for (final value in <String>[event.taskId, event.runId, event.functionId]) {
    final normalized = value.trim();
    if (normalized.isNotEmpty) {
      return 'function-run-$normalized';
    }
  }
  return '';
}

Map<String, dynamic> functionRunToolCardDataForEvent(
  OobFunctionRunProgressEvent event,
) {
  final derivedCardId = functionRunToolCardIdForEvent(event);
  final cardId = derivedCardId.isNotEmpty
      ? derivedCardId
      : _firstNonBlank([event.rawJson['cardId'], event.rawJson['card_id']]);
  final stepLabel = _stepLabel(event);
  final detail = _messageFor(event, stepLabel);
  final title = _titleFor(event);
  final statusLabel = _statusLabelFor(event);
  final summary = _uniqueNonBlank([title, stepLabel, detail]).join(' · ');
  final runLogId = event.runLogId;
  return <String, dynamic>{
    ...event.rawJson,
    'type': kAgentToolSummaryCardType,
    'cardId': cardId,
    'toolCallId': cardId,
    'tool_call_id': cardId,
    'status': _agentToolStatusFor(event),
    'rawStatus': event.status,
    'raw_status': event.status,
    'statusLabel': statusLabel,
    'status_label': statusLabel,
    'toolType': 'oob_function',
    'tool_type': 'oob_function',
    'toolName': 'function_run',
    'tool_name': 'function_run',
    'toolTitle': title,
    'tool_title': title,
    'toolTypeLabel': _choose(zh: '复用指令', en: 'Reusable command'),
    'tool_type_label': _choose(zh: '复用指令', en: 'Reusable command'),
    'displayName': _choose(zh: '复用指令', en: 'Reusable command'),
    'display_name': _choose(zh: '复用指令', en: 'Reusable command'),
    'summary': summary,
    'progress': detail,
    'runLogId': runLogId,
    'run_log_id': runLogId,
    'runId': event.runId,
    'run_id': event.runId,
    'taskId': event.taskId,
    'task_id': event.taskId,
    'functionId': event.functionId,
    'function_id': event.functionId,
    'label': event.label,
    'message': event.message,
    'stepCount': event.stepCount,
    'step_count': event.stepCount,
    'currentStepIndex': event.currentStepIndex,
    'current_step_index': event.currentStepIndex,
    'currentStepNumber': event.currentStepNumber,
    'current_step_number': event.currentStepNumber,
    'embeddedInVlmTask': event.embeddedInVlmTask,
    'embedded_in_vlm_task': event.embeddedInVlmTask,
    'timestampMs': event.timestampMs,
    'timestamp_ms': event.timestampMs,
    'openRunLogAsTimeline': true,
    'open_run_log_as_timeline': true,
  }..removeWhere((_, value) {
    if (value == null) return true;
    return value is String && value.trim().isEmpty;
  });
}

String _titleFor(OobFunctionRunProgressEvent event) {
  if (event.isRunning) {
    return _choose(zh: '复用指令执行中', en: 'Reusable command running');
  }
  if (event.status == 'stopped') {
    return _choose(zh: '复用指令已停止', en: 'Reusable command stopped');
  }
  if (event.status == 'failed' || _looksFailed(event.message)) {
    return _choose(zh: '复用指令执行失败', en: 'Reusable command failed');
  }
  return _choose(zh: '复用指令执行完成', en: 'Reusable command completed');
}

String _stepLabel(OobFunctionRunProgressEvent event) {
  final currentStep = event.displayStepNumber;
  final stepCount = event.stepCount;
  if (currentStep != null && currentStep > 0) {
    final value = stepCount > 0 ? '$currentStep/$stepCount' : '$currentStep';
    return _choose(zh: '第 $value 步', en: 'Step $value');
  }
  if (stepCount > 0 && event.isRunning) {
    return _choose(zh: '$stepCount 步', en: '$stepCount steps');
  }
  return '';
}

String _messageFor(OobFunctionRunProgressEvent event, String stepLabel) {
  final message = event.message.trim();
  final label = event.label.trim();
  final cleaned = _stripStepPrefix(message);
  if (cleaned.isNotEmpty &&
      cleaned != stepLabel &&
      cleaned != '任务已完成' &&
      cleaned != '任务执行失败') {
    return cleaned;
  }
  return label;
}

String _stripStepPrefix(String message) {
  return message.replaceFirst(RegExp(r'^第\s*\d+\s*/\s*\d+\s*步\s*'), '').trim();
}

String _agentToolStatusFor(OobFunctionRunProgressEvent event) {
  if (event.isRunning) return 'running';
  if (event.status == 'stopped') return 'interrupted';
  if (event.status == 'failed' || _looksFailed(event.message)) {
    return 'error';
  }
  return 'success';
}

String _statusLabelFor(OobFunctionRunProgressEvent event) {
  if (event.status == 'stopped') {
    return _choose(zh: '已停止', en: 'Stopped');
  }
  return '';
}

bool _looksFailed(String message) => message.contains('失败');

String _choose({required String zh, required String en}) {
  return AppTextLocalizer.choose(zh: zh, en: en);
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
