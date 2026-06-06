import 'dart:async';

import 'package:flutter/material.dart';
import 'package:ui/l10n/app_text_localizer.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/oob_function_run_progress_card.dart';

Future<void> showOobFunctionRunProgressDialog(
  BuildContext context,
  OobFunctionRunProgressEvent event,
) {
  return showDialog<void>(
    context: context,
    barrierDismissible: true,
    barrierColor: Colors.black.withValues(alpha: 0.2),
    builder: (context) => _OobFunctionRunProgressDialog(event: event),
  );
}

class _OobFunctionRunProgressDialog extends StatefulWidget {
  const _OobFunctionRunProgressDialog({required this.event});

  final OobFunctionRunProgressEvent event;

  @override
  State<_OobFunctionRunProgressDialog> createState() =>
      _OobFunctionRunProgressDialogState();
}

class _OobFunctionRunProgressDialogState
    extends State<_OobFunctionRunProgressDialog> {
  late OobFunctionRunProgressEvent _event;
  StreamSubscription<OobFunctionRunProgressEvent>? _subscription;

  @override
  void initState() {
    super.initState();
    _event = widget.event;
    _subscription = AssistsMessageService.oobFunctionRunProgressStream.listen((
      event,
    ) {
      if (!_isSameRunProgressEvent(_event, event) || !mounted) return;
      setState(() => _event = event);
    });
  }

  @override
  void dispose() {
    _subscription?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Dialog(
      backgroundColor: Colors.transparent,
      insetPadding: const EdgeInsets.symmetric(horizontal: 24, vertical: 48),
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: palette.surfacePrimary.withValues(alpha: 0.96),
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: palette.borderSubtle),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withValues(alpha: 0.14),
              blurRadius: 24,
              offset: const Offset(0, 12),
            ),
          ],
        ),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      AppTextLocalizer.choose(
                        zh: '复用指令执行进度',
                        en: 'Reusable Function progress',
                      ),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: palette.textPrimary,
                        fontSize: 16,
                        fontWeight: FontWeight.w800,
                        height: 1.2,
                      ),
                    ),
                  ),
                  IconButton(
                    tooltip: AppTextLocalizer.choose(zh: '关闭', en: 'Close'),
                    onPressed: () => Navigator.of(context).pop(),
                    icon: const Icon(Icons.close_rounded, size: 20),
                    visualDensity: VisualDensity.compact,
                    padding: EdgeInsets.zero,
                    constraints: const BoxConstraints.tightFor(
                      width: 32,
                      height: 32,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              OobFunctionRunProgressCard(
                event: _event,
                elevated: true,
                maxWidth: double.infinity,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

bool _isSameRunProgressEvent(
  OobFunctionRunProgressEvent current,
  OobFunctionRunProgressEvent next,
) {
  for (final ids in <(String, String)>[
    (current.runId, next.runId),
    (current.taskId, next.taskId),
    (current.functionId, next.functionId),
  ]) {
    final currentId = ids.$1.trim();
    final nextId = ids.$2.trim();
    if (currentId.isNotEmpty && nextId.isNotEmpty) {
      return currentId == nextId;
    }
  }
  return false;
}
