import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/l10n/app_text_localizer.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/oob_function_run_progress_overlay.dart';

void main() {
  setUp(() {
    AppTextLocalizer.setResolvedLocale(const Locale('zh'));
  });

  tearDown(() {
    AppTextLocalizer.clearResolvedLocale();
  });

  testWidgets('shows Function run progress and clears terminal event', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        theme: AppTheme.lightTheme,
        home: const Stack(children: [OobFunctionRunProgressOverlay()]),
      ),
    );

    AssistsMessageService.debugDispatchOobFunctionRunProgressForTest(
      <String, dynamic>{
        'status': 'progress',
        'task_id': 'task-1',
        'function_id': 'open_bluetooth',
        'label': '打开蓝牙设置',
        'message': '第 2/4 步 点击蓝牙',
        'step_count': 4,
        'current_step_number': 2,
        'timestamp_ms': 1000,
      },
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));

    expect(find.text('复用指令执行中'), findsOneWidget);
    expect(find.text('第 2/4 步'), findsOneWidget);
    expect(find.text('点击蓝牙'), findsOneWidget);

    AssistsMessageService.debugDispatchOobFunctionRunProgressForTest(
      <String, dynamic>{
        'status': 'finished',
        'task_id': 'task-1',
        'function_id': 'open_bluetooth',
        'label': '打开蓝牙设置',
        'message': '任务已完成',
        'step_count': 4,
        'current_step_number': 4,
        'timestamp_ms': 2000,
      },
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));

    expect(find.text('复用指令执行完成'), findsOneWidget);
    expect(find.text('第 4/4 步'), findsOneWidget);

    await tester.pump(const Duration(milliseconds: 1600));
    expect(find.text('复用指令执行完成'), findsNothing);
  });
}
