import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/task/run_log/vlm_function_registration_coordinator.dart';
import 'package:ui/services/assists_core_service.dart';

void main() {
  test('completed child run id is the only registration identity', () async {
    String? loadedRunId;
    final coordinator = VlmFunctionRegistrationCoordinator(
      loadRunLog: (runId) async {
        loadedRunId = runId;
        return eligibleRunLog()..['run_id'] = runId;
      },
      compileRunLog: (_) async => compiledFunction(),
      registerFunction: (_) async => <String, dynamic>{'success': false},
      enhanceFunction: (_, _) async => <String, dynamic>{'success': true},
    );

    await coordinator.handleRunLogFinished(
      runId: 'child-vlm-run',
      confirm: (_) async => false,
    );

    expect(loadedRunId, 'child-vlm-run');
  });

  test('failed VLM run does not prompt', () async {
    var promptCount = 0;
    var compileCount = 0;
    final coordinator = coordinatorWith(
      runLog: eligibleRunLog()
        ..['status'] = 'failed'
        ..['success'] = false,
      onCompile: () => compileCount += 1,
    );

    final outcome = await coordinator.handleRunLogFinished(
      runId: 'run-1',
      confirm: (_) async {
        promptCount += 1;
        return true;
      },
    );

    expect(outcome, VlmFunctionRegistrationOutcome.ignored);
    expect(promptCount, 0);
    expect(compileCount, 0);
  });

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

    expect(duplicate, VlmFunctionRegistrationOutcome.ignored);
    expect(await first, VlmFunctionRegistrationOutcome.declined);
    expect(promptCount, 1);
  });

  test('declining does not register or enhance', () async {
    var compileCount = 0;
    var registerCount = 0;
    var enhanceCount = 0;
    final coordinator = coordinatorWith(
      runLog: eligibleRunLog(),
      onCompile: () => compileCount += 1,
      onRegister: () => registerCount += 1,
      onEnhance: () => enhanceCount += 1,
    );

    final outcome = await coordinator.handleRunLogFinished(
      runId: 'run-1',
      confirm: (_) async => false,
    );

    expect(outcome, VlmFunctionRegistrationOutcome.declined);
    expect(compileCount, 0);
    expect(registerCount, 0);
    expect(enhanceCount, 0);
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

    expect(outcome, VlmFunctionRegistrationOutcome.ignored);
    expect(promptCount, 0);
  });

  test(
    'confirmation registers before starting background enhancement',
    () async {
      final events = <String>[];
      final enhanced = Completer<void>();
      final coordinator = coordinatorWith(
        runLog: eligibleRunLog(),
        onCompile: () => events.add('compile'),
        onRegister: () => events.add('register'),
        onEnhance: () {
          events.add('enhance');
          enhanced.complete();
        },
      );

      final outcome = await coordinator.handleRunLogFinished(
        runId: 'run-1',
        confirm: (_) async {
          events.add('confirm');
          return true;
        },
      );
      await enhanced.future;

      expect(outcome, VlmFunctionRegistrationOutcome.registered);
      expect(events, ['confirm', 'compile', 'register', 'enhance']);
    },
  );

  test('accepted registration reports compiler failure', () async {
    var registerCount = 0;
    var enhanceCount = 0;
    final coordinator = VlmFunctionRegistrationCoordinator(
      loadRunLog: (_) async => eligibleRunLog(),
      compileRunLog: (_) async => <String, dynamic>{
        'success': false,
        'error_message': 'no replayable actions',
      },
      registerFunction: (_) async {
        registerCount += 1;
        return <String, dynamic>{'success': true};
      },
      enhanceFunction: (_, _) async {
        enhanceCount += 1;
        return <String, dynamic>{'success': true};
      },
    );

    final outcome = await coordinator.handleRunLogFinished(
      runId: 'run-1',
      confirm: (_) async => true,
    );

    expect(outcome, VlmFunctionRegistrationOutcome.registrationFailed);
    expect(registerCount, 0);
    expect(enhanceCount, 0);
  });

  test('enhancement failure keeps successful registration', () async {
    final enhancementAttempted = Completer<void>();
    final coordinator = VlmFunctionRegistrationCoordinator(
      loadRunLog: (_) async => eligibleRunLog(),
      compileRunLog: (_) async => compiledFunction(),
      registerFunction: (_) async => {
        'success': true,
        'function_id': 'fn-run-1',
      },
      enhanceFunction: (_, _) async {
        enhancementAttempted.complete();
        throw StateError('offline model unavailable');
      },
    );

    final outcome = await coordinator.handleRunLogFinished(
      runId: 'run-1',
      confirm: (_) async => true,
    );
    await enhancementAttempted.future;
    await Future<void>.delayed(Duration.zero);

    expect(outcome, VlmFunctionRegistrationOutcome.registered);
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
    VlmFunctionRegistrationPrompt.initialize();
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
        home: const Scaffold(),
      ),
    );
    await tester.pump();
    await tester.pump();

    expect(find.byType(AlertDialog), findsOneWidget);
    await tester.tap(find.byType(TextButton));
    await tester.pumpAndSettle();
  });
}

VlmFunctionRegistrationCoordinator coordinatorWith({
  required Map<String, dynamic> runLog,
  void Function()? onCompile,
  void Function()? onRegister,
  void Function()? onEnhance,
}) {
  return VlmFunctionRegistrationCoordinator(
    loadRunLog: (_) async => runLog,
    compileRunLog: (_) async {
      onCompile?.call();
      return compiledFunction();
    },
    registerFunction: (_) async {
      onRegister?.call();
      return {'success': true, 'function_id': 'fn-run-1'};
    },
    enhanceFunction: (_, _) async {
      onEnhance?.call();
      return {'success': true};
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
      'diagnostics': <String, dynamic>{},
    },
  ],
};

Map<String, dynamic> compiledFunction() => <String, dynamic>{
  'success': true,
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
