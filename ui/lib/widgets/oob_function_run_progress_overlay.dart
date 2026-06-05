import 'dart:async';

import 'package:flutter/material.dart';
import 'package:ui/l10n/app_text_localizer.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/theme/theme_context.dart';

class OobFunctionRunProgressOverlay extends StatefulWidget {
  const OobFunctionRunProgressOverlay({super.key});

  @override
  State<OobFunctionRunProgressOverlay> createState() =>
      _OobFunctionRunProgressOverlayState();
}

class _OobFunctionRunProgressOverlayState
    extends State<OobFunctionRunProgressOverlay> {
  static const _terminalVisibleDuration = Duration(milliseconds: 1500);

  final Map<String, OobFunctionRunProgressEvent> _events = {};
  final Map<String, Timer> _terminalTimers = {};
  StreamSubscription<OobFunctionRunProgressEvent>? _subscription;

  @override
  void initState() {
    super.initState();
    _subscription = AssistsMessageService.oobFunctionRunProgressStream.listen(
      _handleEvent,
    );
  }

  @override
  void dispose() {
    _subscription?.cancel();
    for (final timer in _terminalTimers.values) {
      timer.cancel();
    }
    _terminalTimers.clear();
    super.dispose();
  }

  void _handleEvent(OobFunctionRunProgressEvent event) {
    final key = _eventKey(event);
    if (!mounted || key.isEmpty) return;
    _terminalTimers.remove(key)?.cancel();
    setState(() {
      _events[key] = event;
    });
    if (event.isTerminal) {
      _terminalTimers[key] = Timer(_terminalVisibleDuration, () {
        if (!mounted) return;
        setState(() {
          if (identical(_events[key], event)) {
            _events.remove(key);
          }
          _terminalTimers.remove(key)?.cancel();
        });
      });
    }
  }

  String _eventKey(OobFunctionRunProgressEvent event) {
    for (final value in [event.taskId, event.runId, event.functionId]) {
      final key = value.trim();
      if (key.isNotEmpty) return key;
    }
    return '';
  }

  OobFunctionRunProgressEvent? get _latestEvent {
    if (_events.isEmpty) return null;
    final events = _events.values.toList(growable: false)
      ..sort((a, b) => a.timestampMs.compareTo(b.timestampMs));
    return events.last;
  }

  @override
  Widget build(BuildContext context) {
    final event = _latestEvent;
    if (event == null) return const SizedBox.shrink();
    return Positioned.fill(
      child: IgnorePointer(
        child: SafeArea(
          minimum: const EdgeInsets.fromLTRB(12, 10, 12, 0),
          child: Align(
            alignment: Alignment.topCenter,
            child: AnimatedSwitcher(
              duration: const Duration(milliseconds: 180),
              switchInCurve: Curves.easeOutCubic,
              switchOutCurve: Curves.easeInCubic,
              child: _OobFunctionRunProgressCard(
                key: ValueKey('${event.taskId}:${event.status}'),
                event: event,
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _OobFunctionRunProgressCard extends StatelessWidget {
  const _OobFunctionRunProgressCard({super.key, required this.event});

  final OobFunctionRunProgressEvent event;

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

    return Material(
      color: Colors.transparent,
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 360),
        child: DecoratedBox(
          decoration: BoxDecoration(
            color: palette.surfacePrimary.withValues(alpha: 0.98),
            borderRadius: BorderRadius.circular(16),
            border: Border.all(color: accent.withValues(alpha: 0.28)),
            boxShadow: [
              BoxShadow(
                color: Colors.black.withValues(alpha: 0.14),
                blurRadius: 18,
                offset: const Offset(0, 8),
              ),
            ],
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
              ],
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
