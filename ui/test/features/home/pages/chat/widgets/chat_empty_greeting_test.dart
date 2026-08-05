import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_empty_greeting.dart';
import 'package:ui/l10n/generated/app_localizations.dart';

void main() {
  testWidgets('shows one lightweight localized guide action', (tester) async {
    var tapCount = 0;

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('en'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(
          body: MediaQuery(
            data: const MediaQueryData(disableAnimations: true),
            child: ChatEmptyGreeting(onGuideTap: () => tapCount += 1),
          ),
        ),
      ),
    );
    await tester.pump();

    final guideButton = find.byKey(const ValueKey('chat-empty-omnibot-guide'));
    expect(guideButton, findsOneWidget);
    expect(find.text('Omnibot Guide'), findsOneWidget);

    await tester.tap(guideButton);
    await tester.pump();

    expect(tapCount, 1);
  });
}
