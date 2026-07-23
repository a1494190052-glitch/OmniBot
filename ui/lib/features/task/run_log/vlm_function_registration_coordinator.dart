import 'dart:async';

import 'package:flutter/material.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/task/run_log/run_log_function_service.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/utils/ui.dart';

typedef VlmRunLogLoader = Future<Map<String, dynamic>> Function(String runId);
typedef VlmRunLogRegistrar =
    Future<Map<String, dynamic>> Function(String runId);
typedef VlmRegistrationConfirmation =
    Future<bool> Function(VlmFunctionRegistrationCandidate candidate);

enum VlmFunctionRegistrationOutcome {
  ignored,
  declined,
  registered,
  registrationFailed,
}

class VlmFunctionRegistrationCandidate {
  const VlmFunctionRegistrationCandidate({
    required this.runId,
    required this.goal,
  });

  final String runId;
  final String goal;
}

class VlmFunctionRegistrationCoordinator {
  VlmFunctionRegistrationCoordinator({
    VlmRunLogLoader? loadRunLog,
    VlmRunLogRegistrar? registerRunLog,
  }) : _loadRunLog =
           loadRunLog ??
           ((runId) =>
               RunLogFunctionService.getInternalRunLogTimeline(runId: runId)),
       _registerRunLog =
           registerRunLog ??
           ((runId) => RunLogFunctionService.convertInternalRunLogToFunction(
             runId: runId,
             register: true,
             agentVisible: true,
           ));

  static final VlmFunctionRegistrationCoordinator instance =
      VlmFunctionRegistrationCoordinator();

  final VlmRunLogLoader _loadRunLog;
  final VlmRunLogRegistrar _registerRunLog;
  final Set<String> _inFlightRunIds = <String>{};
  final Set<String> _handledRunIds = <String>{};

  static const int _maxHandledRunIds = 256;

  Future<VlmFunctionRegistrationOutcome> handleRunLogFinished({
    required String? runId,
    required VlmRegistrationConfirmation confirm,
  }) async {
    final normalizedRunId = runId?.trim() ?? '';
    if (normalizedRunId.isEmpty ||
        _handledRunIds.contains(normalizedRunId) ||
        !_inFlightRunIds.add(normalizedRunId)) {
      return VlmFunctionRegistrationOutcome.ignored;
    }

    try {
      final runLog = await _loadRunLog(normalizedRunId);
      if (!_isEligibleRunLog(runLog)) {
        _markHandled(normalizedRunId);
        return VlmFunctionRegistrationOutcome.ignored;
      }
      final canonicalRunId = _text(runLog['run_id']).isEmpty
          ? normalizedRunId
          : _text(runLog['run_id']);
      final candidate = VlmFunctionRegistrationCandidate(
        runId: canonicalRunId,
        goal: _text(runLog['goal']),
      );
      final accepted = await confirm(candidate);
      _markHandled(normalizedRunId);
      if (!accepted) return VlmFunctionRegistrationOutcome.declined;

      final registration = await _registerRunLog(canonicalRunId);
      final functionSpec = _map(registration['function']);
      final functionId = _text(
        registration['function_id'] ?? functionSpec['function_id'],
      );
      if (registration['success'] != true ||
          registration['registered'] != true ||
          functionSpec.isEmpty ||
          functionId.isEmpty) {
        return VlmFunctionRegistrationOutcome.registrationFailed;
      }
      return VlmFunctionRegistrationOutcome.registered;
    } catch (error, stackTrace) {
      debugPrint('VLM Function registration prompt failed: $error');
      debugPrintStack(stackTrace: stackTrace);
      _markHandled(normalizedRunId);
      return VlmFunctionRegistrationOutcome.registrationFailed;
    } finally {
      _inFlightRunIds.remove(normalizedRunId);
    }
  }

  static bool _isEligibleRunLog(Map<String, dynamic> runLog) {
    final diagnostics = _map(runLog['diagnostics']);
    return _text(runLog['schema_version']) == 'omniflow.canonical_run_log.v1' &&
        runLog['success'] == true &&
        _text(runLog['status']) == 'succeeded' &&
        _text(diagnostics['source']) == 'vlm' &&
        _text(diagnostics['tool_name']) == 'vlm_task' &&
        (runLog['steps'] is List && (runLog['steps'] as List).isNotEmpty);
  }

  void _markHandled(String runId) {
    _handledRunIds.add(runId);
    while (_handledRunIds.length > _maxHandledRunIds) {
      _handledRunIds.remove(_handledRunIds.first);
    }
  }

  static String _text(dynamic value) => value?.toString().trim() ?? '';

  static Map<String, dynamic> _map(dynamic value) {
    if (value is! Map) return <String, dynamic>{};
    return value.map((key, item) => MapEntry(key.toString(), item));
  }
}

class VlmFunctionRegistrationPrompt {
  const VlmFunctionRegistrationPrompt._();

  static StreamSubscription<RunLogFinishedEvent>? _subscription;
  static final List<String> _pendingRunIds = <String>[];
  static bool _draining = false;
  static bool _drainScheduled = false;

  static void initialize() {
    _subscription ??= AssistsMessageService.runLogFinishedStream.listen(
      (event) => unawaited(offerFromRootNavigator(event.runId)),
    );
  }

  static Future<void> offerFromRootNavigator(String? runId) async {
    final normalizedRunId = runId?.trim() ?? '';
    if (normalizedRunId.isEmpty) return;
    if (!_pendingRunIds.contains(normalizedRunId)) {
      _pendingRunIds.add(normalizedRunId);
    }
    await _drainPending();
  }

  static Future<void> _drainPending() async {
    if (_draining || _pendingRunIds.isEmpty) return;
    if (GoRouterManager.rootNavigatorKey.currentContext == null) {
      _scheduleDrainAfterFrame();
      return;
    }

    _draining = true;
    try {
      while (_pendingRunIds.isNotEmpty) {
        final context = GoRouterManager.rootNavigatorKey.currentContext;
        if (context == null) {
          _scheduleDrainAfterFrame();
          return;
        }
        final pendingRunId = _pendingRunIds.removeAt(0);
        await offer(context, pendingRunId);
      }
    } finally {
      _draining = false;
    }
  }

  static void _scheduleDrainAfterFrame() {
    if (_drainScheduled) return;
    _drainScheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _drainScheduled = false;
      unawaited(_drainPending());
    });
    WidgetsBinding.instance.scheduleFrame();
  }

  static Future<void> offer(BuildContext context, String? runId) async {
    final outcome = await VlmFunctionRegistrationCoordinator.instance
        .handleRunLogFinished(
          runId: runId,
          confirm: (_) => _showDialog(context),
        );
    if (!context.mounted) return;
    switch (outcome) {
      case VlmFunctionRegistrationOutcome.registered:
        AppToast.success(
          LegacyTextLocalizer.isEnglish
              ? 'Saved as a reusable command.'
              : '已注册为复用指令。',
        );
      case VlmFunctionRegistrationOutcome.registrationFailed:
        AppToast.error(
          LegacyTextLocalizer.isEnglish
              ? 'Could not register this reusable command.'
              : '复用指令注册失败。',
        );
      case VlmFunctionRegistrationOutcome.ignored:
      case VlmFunctionRegistrationOutcome.declined:
        break;
    }
  }

  static Future<bool> _showDialog(BuildContext context) async {
    if (!context.mounted) return false;
    final useEnglish = LegacyTextLocalizer.isEnglish;
    return await showDialog<bool>(
          context: context,
          useRootNavigator: true,
          builder: (dialogContext) => AlertDialog(
            title: Text(useEnglish ? 'Save for reuse?' : '注册为复用指令？'),
            content: Text(
              useEnglish
                  ? 'Save this successful operation for faster reuse next time. '
                        'You can optionally enhance its description later.'
                  : '保存这次成功操作，下次相似任务可更快复用。'
                        '需要时可稍后手动增强描述。',
            ),
            actions: [
              TextButton(
                key: const ValueKey('vlm-function-registration-cancel'),
                onPressed: () => Navigator.of(dialogContext).pop(false),
                child: Text(useEnglish ? 'Cancel' : '取消'),
              ),
              FilledButton(
                key: const ValueKey('vlm-function-registration-confirm'),
                onPressed: () => Navigator.of(dialogContext).pop(true),
                child: Text(useEnglish ? 'Save' : '注册'),
              ),
            ],
          ),
        ) ??
        false;
  }
}
