import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/task/pages/execution_history/function_run_result_sheet.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/assists_core_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const assistCoreChannel = MethodChannel(
    'cn.com.omnimind.bot/AssistCoreEvent',
  );

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, null);
  });

  testWidgets(
    'Reusable Function result keeps timing internal and hidden from UI',
    (tester) async {
      final result = UtgManualRunResult.fromMap(<String, dynamic>{
        'success': true,
        'goal': 'oob_reusable_function_run:open_settings',
        'function_id': 'open_settings',
        'compile_kind': 'hit',
        'compile_status': 'hit',
        'timing': <String, dynamic>{
          'started_at_ms': 1700000000000,
          'finished_at_ms': 1700000002450,
          'runner_duration_ms': 2450,
          'phase_ms': <String, dynamic>{
            'parse_request_ms': 3,
            'read_current_package_ms': 4,
            'read_current_page_ms': 5,
            'page_match_ms': 6,
            'rank_functions_ms': 7,
            'segment_match_ms': 8,
          },
        },
        'terminal_state': <String, dynamic>{
          'status': 'completed_local',
          'execution_status': 'completed_local',
          'runner': 'oob_omniflow_replay',
          'step_count': 1,
          'success_step_count': 1,
          'current_step_index': 0,
          'current_step_number': 1,
        },
        'context': <String, dynamic>{
          'step_results': <Map<String, dynamic>>[
            <String, dynamic>{
              'success': true,
              'tool': 'open_app',
              'executor': 'omniflow',
              'duration_ms': 120,
              'compile_kind': 'hit',
              'compile_result': <String, dynamic>{
                'compile_status': 'hit',
                'function_id': 'open_settings',
              },
            },
          ],
        },
      });

      expect(result.durationMs, 2450);
      expect(result.startedAtMs, 1700000000000);
      expect(result.finishedAtMs, 1700000002450);
      expect(result.phaseMs['parse_request_ms'], 3);
      expect(result.phaseMs['read_current_package_ms'], 4);
      expect(result.phaseMs['read_current_page_ms'], 5);
      expect(result.phaseMs['page_match_ms'], 6);
      expect(result.phaseMs['rank_functions_ms'], 7);
      expect(result.phaseMs['segment_match_ms'], 8);
      expect(result.currentStepIndex, 0);
      expect(result.currentStepNumber, 1);

      await tester.pumpWidget(
        MaterialApp(
          locale: const Locale('zh'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: Scaffold(
            body: SingleChildScrollView(
              child: FunctionRunResultInlinePanel(
                result: result,
                showRawJson: true,
              ),
            ),
          ),
        ),
      );

      expect(find.text('时间统计'), findsNothing);
      expect(_selectableTextContaining('duration_ms'), findsNothing);
      expect(_selectableTextContaining('2450ms'), findsNothing);
      expect(_selectableTextContaining('started_at_ms'), findsNothing);
      expect(_selectableTextContaining('finished_at_ms'), findsNothing);
      expect(_selectableTextContaining('phase_ms'), findsNothing);
      expect(_selectableTextContaining('parse_request_ms'), findsNothing);
      expect(
        _selectableTextContaining('read_current_package_ms'),
        findsNothing,
      );
      expect(_selectableTextContaining('read_current_page_ms'), findsNothing);
      expect(_selectableTextContaining('page_match_ms'), findsNothing);
      expect(_selectableTextContaining('rank_functions_ms'), findsNothing);
      expect(_selectableTextContaining('segment_match_ms'), findsNothing);
      expect(_richTextContaining('执行到  第 1/1 步'), findsOneWidget);
      expect(find.text('open_app'), findsNothing);
      expect(find.text('120ms'), findsNothing);
      expect(find.text('Runner'), findsNothing);
      expect(find.text('oob_omniflow_replay'), findsNothing);
      expect(find.text('模型'), findsNothing);
      expect(find.text('Fallback'), findsNothing);

      await tester.tap(find.text('原始结果'));
      await tester.pumpAndSettle();

      expect(_selectableTextContaining('execution_kind'), findsOneWidget);
      expect(_selectableTextContaining('execution_status'), findsWidgets);
      expect(_selectableTextContaining('execution_result'), findsOneWidget);
      expect(_selectableTextContaining('compile_kind'), findsNothing);
      expect(_selectableTextContaining('compile_status'), findsNothing);
      expect(_selectableTextContaining('compile_result'), findsNothing);
      expect(_selectableTextContaining('route_kind'), findsNothing);
      expect(_selectableTextContaining('route_status'), findsNothing);
      expect(_selectableTextContaining('route_result'), findsNothing);
      expect(_selectableTextContaining('duration_ms'), findsNothing);
      expect(_selectableTextContaining('started_at_ms'), findsNothing);
      expect(_selectableTextContaining('phase_ms'), findsNothing);
    },
  );

  testWidgets(
    'Reusable Function result shows accessibility preflight message',
    (tester) async {
      final result = UtgManualRunResult.fromMap(<String, dynamic>{
        'success': false,
        'goal': 'oob_reusable_function_run:tap_search',
        'function_id': 'tap_search',
        'execution_status': 'failed',
        'error_code': 'OOB_ACCESSIBILITY_REQUIRED',
        'error_message': '请先开启无障碍权限，复用指令才能执行点击、滑动和输入。',
        'required_permission': 'accessibility',
        'missing_permissions': <String>['accessibility'],
        'terminal_state': <String, dynamic>{
          'status': 'failed',
          'execution_status': 'failed',
          'step_count': 1,
          'success_step_count': 0,
          'failed_step_index': 0,
          'current_step_index': 0,
          'current_step_number': 1,
        },
        'context': <String, dynamic>{
          'step_results': <Map<String, dynamic>>[
            <String, dynamic>{
              'success': false,
              'summary': '请先开启无障碍权限，复用指令才能执行点击、滑动和输入。',
              'tool': 'click',
              'executor': 'omniflow',
              'error_code': 'OOB_ACCESSIBILITY_REQUIRED',
            },
          ],
        },
      });

      await tester.pumpWidget(
        MaterialApp(
          locale: const Locale('zh'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: Scaffold(
            body: SingleChildScrollView(
              child: FunctionRunResultInlinePanel(result: result),
            ),
          ),
        ),
      );

      expect(result.errorCode, 'OOB_ACCESSIBILITY_REQUIRED');
      expect(result.errorMessage, '请先开启无障碍权限，复用指令才能执行点击、滑动和输入。');
      expect(_richTextContaining('状态  执行失败'), findsOneWidget);
      expect(_richTextContaining('执行到  第 1/1 步'), findsOneWidget);
      expect(_richTextContaining('步骤  0/1'), findsOneWidget);
      expect(find.text('请先开启无障碍权限，复用指令才能执行点击、滑动和输入。'), findsWidgets);
      expect(find.text('OOB_ACCESSIBILITY_REQUIRED'), findsNothing);
    },
  );

  testWidgets('Failed local replay does not offer Agent continuation', (
    tester,
  ) async {
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, (call) async {
          calls.add(call);
          return 'SUCCESS';
        });

    final result = UtgManualRunResult.fromMap(<String, dynamic>{
      'success': false,
      'goal': 'oob_reusable_function_run:search_settings',
      'function_id': 'search_settings',
      'execution_status': 'failed',
      'error_message': 'Omniflow step requires model continuation',
      'terminal_state': <String, dynamic>{
        'status': 'failed',
        'execution_status': 'failed',
        'fallback_available': true,
        'step_count': 2,
        'success_step_count': 1,
      },
      'context': <String, dynamic>{
        'fallback_available': true,
        'step_results': <Map<String, dynamic>>[
          <String, dynamic>{
            'success': true,
            'tool': 'open_app',
            'executor': 'omniflow',
          },
          <String, dynamic>{
            'success': false,
            'tool': 'click',
            'executor': 'agent',
            'needs_agent': true,
            'fallback_available': true,
            'blocked_executor': 'omniflow',
          },
        ],
      },
    });

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Builder(
          builder: (context) => Scaffold(
            body: Center(
              child: TextButton(
                onPressed: () => showFunctionRunResultSheet(
                  context,
                  result: result,
                  arguments: const <String, dynamic>{'query': 'bluetooth'},
                ),
                child: const Text('open'),
              ),
            ),
          ),
        ),
      ),
    );

    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();

    expect(find.text('用 Agent 继续'), findsNothing);
    expect(calls, isEmpty);
  });
}

Finder _selectableTextContaining(String text) {
  return find.byWidgetPredicate(
    (widget) => widget is SelectableText && widget.data?.contains(text) == true,
    description: 'SelectableText containing "$text"',
  );
}

Finder _richTextContaining(String text) {
  return find.byWidgetPredicate(
    (widget) => widget is RichText && widget.text.toPlainText().contains(text),
    description: 'RichText containing "$text"',
  );
}
