import 'package:flutter/material.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/agent_tool_summary_card.dart';
import 'package:ui/l10n/app_text_localizer.dart';
import 'package:ui/services/agent_tool_card_policy.dart';
import 'package:ui/services/assists_core_service.dart';

const String kOobFunctionRunProgressCardType = 'oob_function_run_progress';

String oobFunctionRunProgressCardIdForEvent(OobFunctionRunProgressEvent event) {
  for (final value in <String>[event.taskId, event.runId, event.functionId]) {
    final normalized = value.trim();
    if (normalized.isNotEmpty) {
      return 'oob-function-run-progress-$normalized';
    }
  }
  return '';
}

Map<String, dynamic> oobFunctionRunProgressCardDataForEvent(
  OobFunctionRunProgressEvent event,
) {
  return oobFunctionRunProgressAgentToolCardDataForEvent(event);
}

Map<String, dynamic> oobFunctionRunProgressAgentToolCardData(
  Map<String, dynamic> cardData,
) {
  return oobFunctionRunProgressAgentToolCardDataForEvent(
    OobFunctionRunProgressEvent.fromMap(cardData),
  );
}

Map<String, dynamic> oobFunctionRunProgressAgentToolCardDataForEvent(
  OobFunctionRunProgressEvent event,
) {
  final derivedCardId = oobFunctionRunProgressCardIdForEvent(event);
  final cardId = derivedCardId.isNotEmpty
      ? derivedCardId
      : _firstNonBlank([event.rawJson['cardId'], event.rawJson['card_id']]);
  final stepLabel = OobFunctionRunProgressCard._stepLabel(event);
  final detail = OobFunctionRunProgressCard._messageFor(event, stepLabel);
  final title = OobFunctionRunProgressCard._titleFor(event);
  final summary = OobFunctionRunProgressCard._summaryFor(
    title: title,
    stepLabel: stepLabel,
    detail: detail,
  );
  final runLogId = event.runLogId;
  return <String, dynamic>{
    ...event.rawJson,
    'type': kAgentToolSummaryCardType,
    'sourceCardType': kOobFunctionRunProgressCardType,
    'source_card_type': kOobFunctionRunProgressCardType,
    'cardId': cardId,
    'toolCallId': cardId,
    'tool_call_id': cardId,
    'status': OobFunctionRunProgressCard._agentToolStatusFor(event),
    'rawStatus': event.status,
    'raw_status': event.status,
    'toolType': 'oob_function',
    'tool_type': 'oob_function',
    'toolName': 'oob_function_run',
    'tool_name': 'oob_function_run',
    'toolTitle': title,
    'tool_title': title,
    'toolTypeLabel': OobFunctionRunProgressCard._choose(
      zh: '复用指令',
      en: 'Reusable command',
    ),
    'tool_type_label': OobFunctionRunProgressCard._choose(
      zh: '复用指令',
      en: 'Reusable command',
    ),
    'displayName': OobFunctionRunProgressCard._choose(
      zh: '复用指令',
      en: 'Reusable command',
    ),
    'display_name': OobFunctionRunProgressCard._choose(
      zh: '复用指令',
      en: 'Reusable command',
    ),
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

class OobFunctionRunProgressCard extends StatelessWidget {
  const OobFunctionRunProgressCard({
    super.key,
    required this.event,
    this.elevated = false,
    this.maxWidth,
  });

  factory OobFunctionRunProgressCard.fromCardData(
    Map<String, dynamic> cardData, {
    Key? key,
  }) {
    return OobFunctionRunProgressCard(
      key: key,
      event: OobFunctionRunProgressEvent.fromMap(cardData),
    );
  }

  final OobFunctionRunProgressEvent event;
  final bool elevated;
  final double? maxWidth;

  @override
  Widget build(BuildContext context) {
    return AgentToolSummaryCard(
      cardData: oobFunctionRunProgressAgentToolCardDataForEvent(event),
    );
  }

  static bool _looksFailed(String message) => message.contains('失败');

  static String _titleFor(OobFunctionRunProgressEvent event) {
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

  static String _stepLabel(OobFunctionRunProgressEvent event) {
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

  static String _messageFor(
    OobFunctionRunProgressEvent event,
    String stepLabel,
  ) {
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

  static String _stripStepPrefix(String message) {
    return message
        .replaceFirst(RegExp(r'^第\s*\d+\s*/\s*\d+\s*步\s*'), '')
        .trim();
  }

  static String _summaryFor({
    required String title,
    required String stepLabel,
    required String detail,
  }) {
    return _uniqueNonBlank([title, stepLabel, detail]).join(' · ');
  }

  static String _agentToolStatusFor(OobFunctionRunProgressEvent event) {
    if (event.isRunning) return 'running';
    if (event.status == 'stopped') return 'interrupted';
    if (event.status == 'failed' || _looksFailed(event.message)) {
      return 'error';
    }
    return 'success';
  }

  static String _choose({required String zh, required String en}) {
    return AppTextLocalizer.choose(zh: zh, en: en);
  }
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
