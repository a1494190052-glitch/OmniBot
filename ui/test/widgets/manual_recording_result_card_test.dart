import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/agent_tool_summary_card.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/manual_recording_result_card.dart';
import 'package:ui/l10n/app_text_localizer.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/agent_tool_card_policy.dart';
import 'package:ui/theme/app_theme.dart';

void main() {
  setUp(() {
    AppTextLocalizer.setResolvedLocale(const Locale('zh'));
  });

  tearDown(() {
    AppTextLocalizer.clearResolvedLocale();
  });

  test('projects manual recording result into the shared tool card model', () {
    final projected = manualRecordingResultAgentToolCardData(<String, dynamic>{
      'type': kManualRecordingResultCardType,
      'cardId': 'manual-card-1',
      'success': true,
      'recordingSuccess': true,
      'conversionSuccess': true,
      'runId': 'run-manual-1',
      'actionCount': 4,
      'functionId': 'open_settings',
      'agent_visible': true,
    });

    expect(projected['type'], kAgentToolSummaryCardType);
    expect(projected['sourceCardType'], kManualRecordingResultCardType);
    expect(projected['status'], 'success');
    expect(projected['toolType'], 'oob_function');
    expect(projected['toolTypeLabel'], '手动录制');
    expect(projected['runLogId'], 'run-manual-1');
    expect(projected['openRunLogAsTimeline'], isTrue);
  });

  testWidgets('renders manual recording result with AgentToolSummaryCard', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        theme: AppTheme.lightTheme,
        home: const ManualRecordingResultCard(
          cardData: <String, dynamic>{
            'type': kManualRecordingResultCardType,
            'cardId': 'manual-card-1',
            'success': true,
            'recordingSuccess': true,
            'conversionSuccess': true,
            'runId': 'run-manual-1',
            'actionCount': 4,
            'functionId': 'open_settings',
            'agent_visible': true,
          },
        ),
      ),
    );

    expect(find.byType(AgentToolSummaryCard), findsOneWidget);
    expect(find.textContaining('手动录制完成'), findsOneWidget);
    expect(find.textContaining('4 步'), findsOneWidget);
    expect(find.text('已完成'), findsOneWidget);
  });
}
