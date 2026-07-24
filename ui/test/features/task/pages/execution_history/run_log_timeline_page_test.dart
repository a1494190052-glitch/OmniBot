import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/task/pages/execution_history/run_log_timeline_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  tearDown(() async {
    messenger.setMockMethodCallHandler(channel, null);
  });

  testWidgets('shows canonical model metadata without a step source alias', (
    tester,
  ) async {
    messenger.setMockMethodCallHandler(channel, (call) async {
      expect(call.method, 'getInternalRunLogTimeline');
      return <String, dynamic>{
        'schema_version': 'omniflow.canonical_run_log.v1',
        'run_id': 'vlm-run',
        'goal': '点击创建按钮',
        'status': 'succeeded',
        'success': true,
        'steps': <Map<String, dynamic>>[
          <String, dynamic>{
            'step_index': 0,
            'before_state_id': 'state-0',
            'action': <String, dynamic>{
              'tool': 'click',
              'args': <String, dynamic>{'x': 500, 'y': 250},
            },
            'result': <String, dynamic>{'success': true},
            'after_state_id': 'state-1',
            'metadata': <String, dynamic>{
              'step_id': 'vlm-run-step-0',
              'status': 'succeeded',
              'summary': '点击创建按钮',
              'thinking': '当前页面已经显示创建按钮，下一步点击按钮。',
              'token_usage': <String, dynamic>{
                'prompt_tokens': 2512,
                'completion_tokens': 38,
                'image_tokens': 648,
                'total_tokens': 3198,
              },
            },
          },
        ],
        'diagnostics': <String, dynamic>{
          'source': 'vlm',
          'tool_name': 'vlm_task',
        },
      };
    });

    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: RunLogTimelinePage(runId: 'vlm-run', title: 'VLM 轨迹'),
      ),
    );
    await tester.pumpAndSettle();

    final thinkingPreview = find.byKey(
      const ValueKey('run-log-step-thinking-1'),
    );
    final argumentsPreview = find.byKey(
      const ValueKey('run-log-step-arguments-1'),
    );
    final summaryPreview = find.byKey(const ValueKey('run-log-step-summary-1'));
    expect(thinkingPreview, findsOneWidget);
    expect(argumentsPreview, findsOneWidget);
    expect(summaryPreview, findsOneWidget);
    expect(find.text('3.20k'), findsOneWidget);
    expect(tester.widget<Text>(summaryPreview).data, '点击创建按钮');
    expect(
      find.descendant(
        of: thinkingPreview,
        matching: find.text('当前页面已经显示创建按钮，下一步点击按钮。'),
      ),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: argumentsPreview,
        matching: find.text('{"x":500,"y":250}'),
      ),
      findsOneWidget,
    );

    await tester.tap(
      find.ancestor(of: thinkingPreview, matching: find.byType(InkWell)).first,
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey('run-log-step-detail-thinking')),
      findsOneWidget,
    );
  });

  testWidgets('describes an empty failed run without replay terminology', (
    tester,
  ) async {
    messenger.setMockMethodCallHandler(channel, (call) async {
      expect(call.method, 'getInternalRunLogTimeline');
      return <String, dynamic>{
        'schema_version': 'omniflow.canonical_run_log.v1',
        'run_id': 'cancelled-vlm-run',
        'goal': '执行任务',
        'status': 'failed',
        'success': false,
        'steps': <Map<String, dynamic>>[],
        'diagnostics': <String, dynamic>{
          'source': 'vlm',
          'tool_name': 'vlm_task',
          'done_reason': 'user_cancelled',
        },
      };
    });

    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: RunLogTimelinePage(runId: 'cancelled-vlm-run', title: 'VLM 轨迹'),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('暂无执行步骤'), findsOneWidget);
    expect(find.textContaining('可重放'), findsNothing);
  });

  testWidgets('uses enhancement returned by Function conversion', (
    tester,
  ) async {
    final methods = <String>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      methods.add(call.method);
      if (call.method == 'getInternalRunLogTimeline') {
        return <String, dynamic>{
          'schema_version': 'omniflow.canonical_run_log.v1',
          'run_id': 'enhanced-run',
          'goal': '等待一次',
          'status': 'succeeded',
          'success': true,
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
              'metadata': <String, dynamic>{},
            },
          ],
        };
      }
      if (call.method == 'convertInternalRunLogToFunction') {
        expect((call.arguments as Map)['enhance'], isTrue);
        return <String, dynamic>{
          'success': true,
          'run_id': 'enhanced-run',
          'function_id': 'wait_once',
          'registered': true,
          'enhancement_status': 'enhanced',
          'changes': <Map<String, dynamic>>[
            <String, dynamic>{'part': 'function', 'field': 'name'},
          ],
          'function': <String, dynamic>{
            'function_id': 'wait_once',
            'name': '等待一次',
            'description': '等待一秒钟',
            'steps': <Map<String, dynamic>>[],
          },
        };
      }
      fail('Unexpected method: ${call.method}');
    });

    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: RunLogTimelinePage(runId: 'enhanced-run', title: '增强轨迹'),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(
      find.byKey(const ValueKey('run-log-action-save-function')),
    );
    await tester.pumpAndSettle();

    expect(methods, <String>[
      'getInternalRunLogTimeline',
      'convertInternalRunLogToFunction',
    ]);
    expect(find.text('复用指令已增强'), findsOneWidget);
    expect(
      find.byKey(const ValueKey('run-log-function-enhance')),
      findsNothing,
    );
  });

  testWidgets('retries failed conversion enhancement through update_function', (
    tester,
  ) async {
    final methods = <String>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      methods.add(call.method);
      if (call.method == 'getInternalRunLogTimeline') {
        return <String, dynamic>{
          'schema_version': 'omniflow.canonical_run_log.v1',
          'run_id': 'retry-run',
          'goal': '等待一次',
          'status': 'succeeded',
          'success': true,
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
              'metadata': <String, dynamic>{},
            },
          ],
        };
      }
      if (call.method == 'convertInternalRunLogToFunction') {
        return <String, dynamic>{
          'success': true,
          'run_id': 'retry-run',
          'function_id': 'wait_once',
          'registered': true,
          'enhancement_status': 'failed',
          'changes': <Map<String, dynamic>>[],
          'function': <String, dynamic>{
            'function_id': 'wait_once',
            'name': '等待一次',
            'description': '等待一秒钟',
            'steps': <Map<String, dynamic>>[],
          },
        };
      }
      if (call.method == 'updateFunction') {
        expect((call.arguments as Map)['mode'], 'enhance');
        return <String, dynamic>{
          'success': true,
          'function_id': 'wait_once',
          'enhancement_status': 'enhanced',
          'changes': <Map<String, dynamic>>[
            <String, dynamic>{'part': 'function', 'field': 'description'},
          ],
          'updated_function': <String, dynamic>{
            'function_id': 'wait_once',
            'name': '等待一次',
            'description': '等待一秒钟后继续',
            'steps': <Map<String, dynamic>>[],
          },
        };
      }
      fail('Unexpected method: ${call.method}');
    });

    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: RunLogTimelinePage(runId: 'retry-run', title: '重试轨迹'),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(
      find.byKey(const ValueKey('run-log-action-save-function')),
    );
    await tester.pumpAndSettle();
    expect(
      find.byKey(const ValueKey('run-log-function-enhance')),
      findsOneWidget,
    );

    await tester.tap(find.byKey(const ValueKey('run-log-function-enhance')));
    await tester.pumpAndSettle();

    expect(methods, <String>[
      'getInternalRunLogTimeline',
      'convertInternalRunLogToFunction',
      'updateFunction',
    ]);
    expect(find.text('复用指令已增强'), findsOneWidget);
    expect(
      find.byKey(const ValueKey('run-log-function-enhance')),
      findsNothing,
    );
  });
}
