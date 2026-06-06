import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/l10n/app_text_localizer.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/oob_function_run_progress_card.dart';

void main() {
  setUp(() {
    AppTextLocalizer.setResolvedLocale(const Locale('zh'));
    AssistsMessageService.oobFunctionRunProgressNotifier.value = null;
  });

  tearDown(() {
    AssistsMessageService.oobFunctionRunProgressNotifier.value = null;
    AppTextLocalizer.clearResolvedLocale();
  });

  test('updates replay shortcut state and clears on successful finish', () {
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

    final running = AssistsMessageService.oobFunctionRunProgressNotifier.value;
    expect(running, isNotNull);
    expect(running!.isRunning, isTrue);
    expect(running.label, '打开蓝牙设置');

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

    expect(AssistsMessageService.oobFunctionRunProgressNotifier.value, isNull);
  });

  test('keeps replay shortcut visible on failed terminal event', () {
    AssistsMessageService.debugDispatchOobFunctionRunProgressForTest(
      <String, dynamic>{
        'status': 'failed',
        'task_id': 'task-1',
        'function_id': 'open_bluetooth',
        'label': '打开蓝牙设置',
        'message': '任务执行失败',
        'step_count': 4,
        'current_step_number': 4,
        'timestamp_ms': 2000,
      },
    );

    final failed = AssistsMessageService.oobFunctionRunProgressNotifier.value;
    expect(failed, isNotNull);
    expect(failed!.status, 'failed');
  });

  testWidgets('renders Function run progress as a conversation card', (
    tester,
  ) async {
    final event = OobFunctionRunProgressEvent.fromMap(<String, dynamic>{
      'status': 'progress',
      'task_id': 'task-1',
      'function_id': 'open_bluetooth',
      'label': '打开蓝牙设置',
      'message': '第 3/5 步 点击开关',
      'step_count': 5,
      'current_step_number': 3,
      'timestamp_ms': 1000,
    });
    final cardData = oobFunctionRunProgressCardDataForEvent(event);

    expect(cardData['type'], kOobFunctionRunProgressCardType);
    expect(cardData['cardId'], 'oob-function-run-progress-task-1');

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        theme: AppTheme.lightTheme,
        home: OobFunctionRunProgressCard.fromCardData(cardData),
      ),
    );

    expect(find.text('复用指令执行中'), findsOneWidget);
    expect(find.text('第 3/5 步'), findsOneWidget);
    expect(find.text('点击开关'), findsOneWidget);
  });
}
