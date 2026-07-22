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

  testWidgets('shows VLM reasoning and arguments for every timeline step', (
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
            'diagnostics': <String, dynamic>{
              'source': 'vlm',
              'title': '点击创建按钮',
              'thinking': '当前页面已经显示创建按钮，下一步点击按钮。',
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
    expect(thinkingPreview, findsOneWidget);
    expect(argumentsPreview, findsOneWidget);
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
}
