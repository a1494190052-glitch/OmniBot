import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/task/pages/execution_history/run_log_detail_page.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const assistChannel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistChannel, (call) async {
          if (call.method != 'tools/call') return null;
          final arguments = Map<Object?, Object?>.from(call.arguments as Map);
          if (arguments['name'] != 'get_run_log') {
            return <String, Object?>{'success': true};
          }
          return <String, Object?>{
            'run_id': 'run-1',
            'goal': 'Open Settings',
            'status': 'succeeded',
            'started_at_ms': DateTime(
              2026,
              7,
              31,
              9,
              18,
            ).millisecondsSinceEpoch,
            'finished_at_ms': DateTime(
              2026,
              7,
              31,
              9,
              18,
              2,
              345,
            ).millisecondsSinceEpoch,
            'diagnostics': <String, Object?>{
              'duration_ms': 2345,
              'token_usage': <String, Object?>{
                'prompt_tokens': 1000,
                'completion_tokens': 234,
                'total_tokens': 1234,
                'call_count': 2,
                'cached_tokens': 100,
                'resolved_model': 'qwen-vl-max',
              },
              'token_usage_by_step': <Object?>[
                <String, Object?>{
                  'step_index': 0,
                  'tool': 'click',
                  'token_usage': <String, Object?>{
                    'prompt_tokens': 1000,
                    'completion_tokens': 234,
                    'total_tokens': 1234,
                    'resolved_model': 'qwen-vl-max',
                  },
                },
              ],
            },
            'steps': <Object?>[
              <String, Object?>{
                'step_index': 0,
                'before_state_id': 'before-1',
                'action': <String, Object?>{
                  'tool': 'click',
                  'args': <String, Object?>{'x': 100, 'y': 200},
                },
                'result': <String, Object?>{'success': true},
                'after_state_id': 'after-1',
              },
            ],
          };
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistChannel, null);
  });

  testWidgets('shows time model and token usage from canonical RunLog', (
    tester,
  ) async {
    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('en'),
        home: RunLogDetailPage(runId: 'run-1'),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Run summary'), findsOneWidget);
    expect(find.text('2026-07-31 09:18:00'), findsOneWidget);
    expect(find.text('2.35 s'), findsOneWidget);
    expect(find.text('qwen-vl-max'), findsOneWidget);
    expect(find.text('VLM calls'), findsOneWidget);
    expect(find.text('2'), findsOneWidget);
    expect(find.text('Prompt'), findsOneWidget);
    expect(find.text('1.00k'), findsOneWidget);
    expect(find.text('Completion'), findsOneWidget);
    expect(find.text('234'), findsOneWidget);
    expect(find.text('Total'), findsOneWidget);
    expect(find.text('1.23k'), findsOneWidget);
    expect(find.text('Cached'), findsOneWidget);
    expect(find.text('100'), findsOneWidget);
    expect(find.text('Step Token 1.23k · P1.00k/C234'), findsOneWidget);
  });
}
