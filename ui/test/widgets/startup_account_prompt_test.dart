import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/constants/storage_keys.dart';
import 'package:ui/services/account_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/startup_account_prompt.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const accountChannel = MethodChannel('cn.com.omnimind.bot/account');

  setUp(() async {
    SharedPreferences.setMockInitialValues(<String, Object>{
      StorageKeys.welcomeCompleted: true,
    });
    await StorageService.init();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(accountChannel, (call) async {
          if (call.method == 'getSessionState') {
            return <String, Object?>{'configured': true, 'signedIn': false};
          }
          return null;
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(accountChannel, null);
  });

  testWidgets(
    'checks version policy before showing the signed-out account card',
    (tester) async {
      final navigatorKey = GlobalKey<NavigatorState>();
      final calls = <String>[];

      await tester.pumpWidget(
        MaterialApp(
          navigatorKey: navigatorKey,
          theme: AppTheme.lightTheme,
          home: StartupAccountPrompt(
            navigatorKey: navigatorKey,
            refreshVersionPolicy: () async => calls.add('version'),
            loadSession: () async {
              calls.add('account');
              return const AccountSessionState(
                configured: true,
                signedIn: false,
              );
            },
            child: const Scaffold(body: Text('home')),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(calls, <String>['version', 'account']);
      expect(
        find.byKey(const ValueKey('startup-account-card')),
        findsOneWidget,
      );
      final header = tester.widget<Image>(
        find.byKey(const ValueKey('startup-account-header-image')),
      );
      expect(header.fit, BoxFit.cover);
      expect(
        tester
            .widget<AspectRatio>(
              find
                  .ancestor(
                    of: find.byKey(
                      const ValueKey('startup-account-header-image'),
                    ),
                    matching: find.byType(AspectRatio),
                  )
                  .first,
            )
            .aspectRatio,
        2.2,
      );
      expect(
        (header.image as AssetImage).assetName,
        'assets/my/atmosphere-light-mineral-02.webp',
      );
      expect(
        find.byKey(const ValueKey('account-auth-only-surface')),
        findsOneWidget,
      );
      expect(
        find.byKey(const Key('account-auth-mode-selector')),
        findsOneWidget,
      );

      await tester.tap(find.byKey(const ValueKey('startup-account-close')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('startup-account-card')), findsNothing);
    },
  );

  testWidgets('uses the dark satin header in dark mode', (tester) async {
    final navigatorKey = GlobalKey<NavigatorState>();
    await tester.pumpWidget(
      MaterialApp(
        navigatorKey: navigatorKey,
        theme: AppTheme.lightTheme,
        darkTheme: AppTheme.darkTheme,
        themeMode: ThemeMode.dark,
        home: StartupAccountPrompt(
          navigatorKey: navigatorKey,
          refreshVersionPolicy: () async {},
          loadSession: () async =>
              const AccountSessionState(configured: true, signedIn: false),
          child: const Scaffold(body: Text('home')),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final header = tester.widget<Image>(
      find.byKey(const ValueKey('startup-account-header-image')),
    );
    expect(
      (header.image as AssetImage).assetName,
      'assets/my/atmosphere-dark-satin-02.webp',
    );
  });

  testWidgets('does not prompt an already signed-in user', (tester) async {
    final navigatorKey = GlobalKey<NavigatorState>();
    await tester.pumpWidget(
      MaterialApp(
        navigatorKey: navigatorKey,
        home: StartupAccountPrompt(
          navigatorKey: navigatorKey,
          refreshVersionPolicy: () async {},
          loadSession: () async =>
              const AccountSessionState(configured: true, signedIn: true),
          child: const Scaffold(body: Text('home')),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('startup-account-card')), findsNothing);
  });

  testWidgets('does not interrupt unfinished onboarding', (tester) async {
    await StorageService.setBool(StorageKeys.welcomeCompleted, false);
    final navigatorKey = GlobalKey<NavigatorState>();
    var accountChecks = 0;
    await tester.pumpWidget(
      MaterialApp(
        navigatorKey: navigatorKey,
        home: StartupAccountPrompt(
          navigatorKey: navigatorKey,
          refreshVersionPolicy: () async {},
          loadSession: () async {
            accountChecks += 1;
            return const AccountSessionState(configured: true, signedIn: false);
          },
          child: const Scaffold(body: Text('onboarding')),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(accountChecks, 0);
    expect(find.byKey(const ValueKey('startup-account-card')), findsNothing);
  });
}
