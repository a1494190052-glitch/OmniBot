import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/task/run_log/run_log_function_registration_coordinator.dart';
import 'package:ui/services/assists_core_service.dart';

void main() {
  test('completed child run id is the only registration identity', () async {
    String? loadedRunId;
    final coordinator = RunLogFunctionRegistrationCoordinator(
      loadRunLog: (runId) async {
        loadedRunId = runId;
        return eligibleRunLog()..['run_id'] = runId;
      },
      registerRunLog: (_) async => registeredFunction(),
    );

    await coordinator.handleRunLogFinished(
      runId: 'child-vlm-run',
      confirm: (_) async => false,
    );

    expect(loadedRunId, 'child-vlm-run');
  });

  test('failed VLM run can register its successful actions', () async {
    var promptCount = 0;
    var registerCount = 0;
    final coordinator = coordinatorWith(
      runLog: eligibleRunLog()
        ..['status'] = 'failed'
        ..['success'] = false,
      onRegister: () => registerCount += 1,
    );

    final outcome = await coordinator.handleRunLogFinished(
      runId: 'run-1',
      confirm: (_) async {
        promptCount += 1;
        return true;
      },
    );

    expect(outcome, RunLogFunctionRegistrationOutcome.registered);
    expect(promptCount, 1);
    expect(registerCount, 1);
  });

  test(
    'failed VLM run without successful replayable actions does not prompt',
    () async {
      var promptCount = 0;
      final runLog = eligibleRunLog()
        ..['status'] = 'failed'
        ..['success'] = false;
      (runLog['steps'] as List).first['result'] = <String, dynamic>{
        'success': false,
        'error': 'failed',
      };
      final coordinator = coordinatorWith(runLog: runLog);

      final outcome = await coordinator.handleRunLogFinished(
        runId: 'run-no-success',
        confirm: (_) async {
          promptCount += 1;
          return true;
        },
      );

      expect(outcome, RunLogFunctionRegistrationOutcome.ignored);
      expect(promptCount, 0);
    },
  );

  test('same run id only prompts once while first prompt is active', () async {
    final promptEntered = Completer<void>();
    final decision = Completer<bool>();
    var promptCount = 0;
    final coordinator = coordinatorWith(runLog: eligibleRunLog());

    final first = coordinator.handleRunLogFinished(
      runId: 'run-1',
      confirm: (_) {
        promptCount += 1;
        promptEntered.complete();
        return decision.future;
      },
    );
    await promptEntered.future;
    final duplicate = await coordinator.handleRunLogFinished(
      runId: 'run-1',
      confirm: (_) async => true,
    );
    decision.complete(false);

    expect(duplicate, RunLogFunctionRegistrationOutcome.ignored);
    expect(await first, RunLogFunctionRegistrationOutcome.declined);
    expect(promptCount, 1);
  });

  test('declining does not register', () async {
    var registerCount = 0;
    final coordinator = coordinatorWith(
      runLog: eligibleRunLog(),
      onRegister: () => registerCount += 1,
    );

    final outcome = await coordinator.handleRunLogFinished(
      runId: 'run-1',
      confirm: (_) async => false,
    );

    expect(outcome, RunLogFunctionRegistrationOutcome.declined);
    expect(registerCount, 0);
  });

  test('recalled Function run does not prompt again', () async {
    var promptCount = 0;
    final coordinator = coordinatorWith(
      runLog: eligibleRunLog()
        ..['diagnostics'] = <String, dynamic>{
          'source': 'function',
          'tool_name': 'call_tool',
          'function_id': 'fn-meituan',
        },
    );

    final outcome = await coordinator.handleRunLogFinished(
      runId: 'run-1',
      confirm: (_) async {
        promptCount += 1;
        return true;
      },
    );

    expect(outcome, RunLogFunctionRegistrationOutcome.ignored);
    expect(promptCount, 0);
  });

  test('confirmation performs one compile-and-register operation', () async {
    final events = <String>[];
    final coordinator = coordinatorWith(
      runLog: eligibleRunLog(),
      onRegister: () => events.add('register'),
    );

    final outcome = await coordinator.handleRunLogFinished(
      runId: 'run-1',
      confirm: (_) async {
        events.add('confirm');
        return true;
      },
    );

    expect(outcome, RunLogFunctionRegistrationOutcome.registered);
    expect(events, ['confirm', 'register']);
  });

  test('accepted registration reports compiler failure', () async {
    final coordinator = RunLogFunctionRegistrationCoordinator(
      loadRunLog: (_) async => eligibleRunLog(),
      registerRunLog: (_) async => <String, dynamic>{
        'success': false,
        'error_message': 'no replayable actions',
      },
    );

    final outcome = await coordinator.handleRunLogFinished(
      runId: 'run-1',
      confirm: (_) async => true,
    );

    expect(outcome, RunLogFunctionRegistrationOutcome.registrationFailed);
  });

  testWidgets('RunLog finish waits for the root navigator before prompting', (
    tester,
  ) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(AssistsMessageService.assistCore, (
          call,
        ) async {
          if (call.method == 'getInternalRunLogTimeline') {
            return eligibleRunLog()..['run_id'] = 'queued-child-run';
          }
          fail('Unexpected native call: ${call.method}');
        });
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(AssistsMessageService.assistCore, null),
    );

    AssistsMessageService.initialize();
    RunLogFunctionRegistrationPrompt.initialize();
    await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .handlePlatformMessage(
          'cn.com.omnimind.bot/AssistCoreEvent',
          const StandardMethodCodec().encodeMethodCall(
            const MethodCall('onRunLogFinished', <String, dynamic>{
              'run_id': 'queued-child-run',
              'source': 'vlm',
              'tool_name': 'vlm_task',
              'success': true,
            }),
          ),
          (_) {},
        );

    await tester.pumpWidget(
      MaterialApp(
        navigatorKey: GoRouterManager.rootNavigatorKey,
        theme: ThemeData(splashFactory: InkRipple.splashFactory),
        home: const Scaffold(),
      ),
    );
    await tester.pump();
    await tester.pump();

    expect(find.byType(AlertDialog), findsOneWidget);
    final cancelButton = find.byKey(
      const ValueKey('run-log-function-registration-cancel'),
    );
    expect(cancelButton, findsOneWidget);
    expect(
      find.descendant(
        of: cancelButton,
        matching: find.textContaining(RegExp(r'^(取消|Cancel)$')),
      ),
      findsOneWidget,
    );
    await tester.tap(cancelButton);
    await tester.pumpAndSettle();
    expect(find.byType(AlertDialog), findsNothing);
  });
}

RunLogFunctionRegistrationCoordinator coordinatorWith({
  required Map<String, dynamic> runLog,
  void Function()? onRegister,
}) {
  return RunLogFunctionRegistrationCoordinator(
    loadRunLog: (_) async => runLog,
    registerRunLog: (_) async {
      onRegister?.call();
      return registeredFunction();
    },
  );
}

Map<String, dynamic> eligibleRunLog() => <String, dynamic>{
  'schema_version': 'omniflow.canonical_run_log.v1',
  'status': 'succeeded',
  'success': true,
  'run_id': 'run-1',
  'goal': '在美团搜索咖啡',
  'diagnostics': <String, dynamic>{
    'source': 'vlm',
    'tool_name': 'vlm_task',
    'step_count': 2,
  },
  'steps': <Map<String, dynamic>>[
    <String, dynamic>{
      'step_index': 0,
      'before_state_id': 'state-0',
      'action': <String, dynamic>{
        'tool': 'wait',
        'args': <String, dynamic>{'duration_ms': 1000},
      },
      'result': <String, dynamic>{'success': true},
      'after_state_id': 'state-1',
      'metadata': <String, dynamic>{
        'step_id': 'run-1-step-0',
        'status': 'succeeded',
      },
    },
  ],
};

Map<String, dynamic> registeredFunction() => <String, dynamic>{
  'success': true,
  'registered': true,
  'function_id': 'fn-run-1',
  'function': <String, dynamic>{
    'schema_version': 'omniflow.function.v2',
    'function_id': 'fn-run-1',
    'name': '在美团搜索咖啡',
    'description': '在美团搜索咖啡',
    'input_schema': <String, dynamic>{
      'type': 'object',
      'properties': <String, dynamic>{},
      'required': <dynamic>[],
      'additionalProperties': false,
    },
    'bindings': <dynamic>[],
    'steps': <Map<String, dynamic>>[
      <String, dynamic>{
        'step_index': 0,
        'source_state_id': 'state-0',
        'action': <String, dynamic>{
          'tool': 'wait',
          'args': <String, dynamic>{'duration_ms': 1000},
        },
      },
    ],
    'checker_rules': <dynamic>[],
    'agent_visible': true,
  },
};
