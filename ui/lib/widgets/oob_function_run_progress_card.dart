import 'package:flutter/material.dart';
import 'package:ui/features/task/pages/execution_history/run_log_timeline_page.dart';
import 'package:ui/l10n/app_text_localizer.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/theme/theme_context.dart';

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
  final cardId = oobFunctionRunProgressCardIdForEvent(event);
  return <String, dynamic>{
    ...event.rawJson,
    'type': kOobFunctionRunProgressCardType,
    'cardId': cardId,
    'status': event.status,
    'runLogId': event.runLogId,
    'run_log_id': event.runLogId,
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
    final palette = context.omniPalette;
    final running = event.isRunning;
    final isStopped = event.status == 'stopped';
    final isFailed = event.status == 'failed' || _looksFailed(event.message);
    final accent = isFailed
        ? const Color(0xFFB42318)
        : isStopped
        ? const Color(0xFFD97706)
        : running
        ? palette.accentPrimary
        : const Color(0xFF117A37);
    final title = _titleFor(event);
    final stepLabel = _stepLabel(event);
    final message = _messageFor(event, stepLabel);
    final runLogId = event.runLogId;

    return Align(
      alignment: Alignment.centerLeft,
      child: ConstrainedBox(
        constraints: BoxConstraints(
          maxWidth: maxWidth ?? MediaQuery.of(context).size.width * 0.86,
        ),
        child: Material(
          color: Colors.transparent,
          child: DecoratedBox(
            decoration: BoxDecoration(
              color: elevated
                  ? palette.surfacePrimary.withValues(alpha: 0.98)
                  : (context.isDarkTheme
                        ? Color.alphaBlend(
                            accent.withValues(alpha: 0.10),
                            palette.surfaceSecondary,
                          )
                        : accent.withValues(alpha: 0.08)),
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: accent.withValues(alpha: 0.28)),
              boxShadow: elevated
                  ? [
                      BoxShadow(
                        color: Colors.black.withValues(alpha: 0.14),
                        blurRadius: 18,
                        offset: const Offset(0, 8),
                      ),
                    ]
                  : null,
            ),
            child: Padding(
              padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  SizedBox(
                    width: 22,
                    height: 22,
                    child: running
                        ? CircularProgressIndicator(
                            strokeWidth: 2.4,
                            valueColor: AlwaysStoppedAnimation<Color>(accent),
                          )
                        : Icon(_terminalIcon(event), size: 22, color: accent),
                  ),
                  const SizedBox(width: 10),
                  Flexible(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Flexible(
                              child: Text(
                                title,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: TextStyle(
                                  color: palette.textPrimary,
                                  fontSize: 13,
                                  fontWeight: FontWeight.w900,
                                  height: 1.15,
                                ),
                              ),
                            ),
                            if (stepLabel.isNotEmpty) ...[
                              const SizedBox(width: 8),
                              Text(
                                stepLabel,
                                maxLines: 1,
                                style: TextStyle(
                                  color: accent,
                                  fontSize: 12,
                                  fontWeight: FontWeight.w900,
                                  height: 1.15,
                                  fontFeatures: const [
                                    FontFeature.tabularFigures(),
                                  ],
                                ),
                              ),
                            ],
                          ],
                        ),
                        if (message.isNotEmpty) ...[
                          const SizedBox(height: 4),
                          Text(
                            message,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              color: palette.textSecondary,
                              fontSize: 11.5,
                              fontWeight: FontWeight.w700,
                              height: 1.2,
                            ),
                          ),
                        ],
                      ],
                    ),
                  ),
                  if (runLogId.isNotEmpty) ...[
                    const SizedBox(width: 8),
                    Tooltip(
                      message: _choose(zh: '查看执行步骤', en: 'View run log'),
                      child: InkResponse(
                        radius: 18,
                        onTap: () => showRunLogTimelineSheet(
                          context,
                          runId: runLogId,
                          title: title,
                        ),
                        child: Icon(
                          Icons.account_tree_outlined,
                          size: 18,
                          color: accent,
                        ),
                      ),
                    ),
                  ],
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  static bool _looksFailed(String message) => message.contains('失败');

  static IconData _terminalIcon(OobFunctionRunProgressEvent event) {
    if (event.status == 'stopped') return Icons.stop_circle_outlined;
    if (event.status == 'failed' || _looksFailed(event.message)) {
      return Icons.error_outline_rounded;
    }
    return Icons.check_circle_outline_rounded;
  }

  static String _titleFor(OobFunctionRunProgressEvent event) {
    if (event.isRunning) {
      return _choose(zh: '复用指令执行中', en: 'Reusable Function running');
    }
    if (event.status == 'stopped') {
      return _choose(zh: '复用指令已停止', en: 'Reusable Function stopped');
    }
    if (event.status == 'failed' || _looksFailed(event.message)) {
      return _choose(zh: '复用指令执行失败', en: 'Reusable Function failed');
    }
    return _choose(zh: '复用指令执行完成', en: 'Reusable Function completed');
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

  static String _choose({required String zh, required String en}) {
    return AppTextLocalizer.choose(zh: zh, en: en);
  }
}
