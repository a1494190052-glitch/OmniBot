import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/my/pages/account/account_page.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('cn.com.omnimind.bot/account');

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  testWidgets('shows a clear message when account server is not configured', (
    tester,
  ) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'getSessionState') {
            return <String, Object?>{'configured': false, 'signedIn': false};
          }
          return null;
        });

    await tester.pumpWidget(_testApp());
    await tester.pumpAndSettle();

    expect(find.text('账号服务尚未配置'), findsOneWidget);
    expect(find.textContaining('OMNIBOT_BASE_URL'), findsOneWidget);
    expect(find.byIcon(LucideIcons.cloudOff), findsOneWidget);
  });

  testWidgets('shows login form for a configured signed-out user', (
    tester,
  ) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'getSessionState') {
            return <String, Object?>{'configured': true, 'signedIn': false};
          }
          return null;
        });

    await tester.pumpWidget(_testApp());
    await tester.pumpAndSettle();

    expect(find.text('登录小万账号'), findsOneWidget);
    expect(find.text('邮箱'), findsOneWidget);
    expect(find.text('密码'), findsOneWidget);
    expect(find.text('登录'), findsWidgets);
    expect(find.byIcon(LucideIcons.mail), findsOneWidget);
    expect(find.byIcon(LucideIcons.lockKeyhole), findsOneWidget);
  });

  testWidgets('shows email quota and platform mode after login', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(375, 812);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'getSessionState') {
            return <String, Object?>{'configured': true, 'signedIn': true};
          }
          if (call.method == 'getOverview') {
            return <String, Object?>{
              'user': <String, Object?>{
                'id': 'user-1',
                'email': 'learner@example.com',
                'role': 'user',
                'status': 'active',
              },
              'settings': <String, Object?>{
                'mode': 'platform',
                'keyStorage': 'device',
                'platformAvailable': true,
                'platform': <String, Object?>{
                  'platformEnabled': true,
                  'balanceQuota': 1000,
                  'unit': 'new_api_quota',
                },
              },
            };
          }
          if (call.method == 'updateAiMode') {
            return <String, Object?>{
              'mode': 'byok',
              'keyStorage': 'device',
              'platformAvailable': true,
              'platform': <String, Object?>{
                'platformEnabled': true,
                'balanceQuota': 1000,
                'unit': 'new_api_quota',
              },
            };
          }
          return null;
        });

    await tester.pumpWidget(_testApp());
    await tester.pumpAndSettle();

    expect(find.text('learner@example.com'), findsOneWidget);
    expect(find.text('1000'), findsOneWidget);
    expect(find.text('使用平台额度'), findsOneWidget);
    expect(find.byIcon(LucideIcons.userRound), findsOneWidget);
    expect(find.byIcon(LucideIcons.coins), findsOneWidget);
    expect(find.byIcon(LucideIcons.circleCheck), findsOneWidget);
    expect(find.byType(Divider), findsOneWidget);
    _expectModeIconsVerticallyCentered(
      tester,
      optionKey: 'account-ai-mode-platform',
      leadingIcon: LucideIcons.cloud,
      trailingIcon: LucideIcons.circleCheck,
    );
    _expectModeIconsVerticallyCentered(
      tester,
      optionKey: 'account-ai-mode-byok',
      leadingIcon: LucideIcons.keyRound,
      trailingIcon: LucideIcons.circle,
    );
    expect(tester.takeException(), isNull);

    await tester.tap(find.text('使用自己的 API Key'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 250));

    expect(find.text('AI 来源已更新'), findsOneWidget);
    expect(find.byType(SnackBar), findsNothing);
    expect(find.text('配置我的 API Key'), findsOneWidget);
    expect(find.byType(Divider), findsOneWidget);

    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('disables platform mode while platform AI is unavailable', (
    tester,
  ) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'getSessionState') {
            return <String, Object?>{'configured': true, 'signedIn': true};
          }
          if (call.method == 'getOverview') {
            return <String, Object?>{
              'user': <String, Object?>{
                'id': 'user-1',
                'email': 'learner@example.com',
                'role': 'user',
                'status': 'active',
              },
              'settings': <String, Object?>{
                'mode': 'byok',
                'keyStorage': 'device',
                'platformAvailable': false,
                'platformUnavailableReason': '平台 AI 服务暂未开放',
                'platform': <String, Object?>{
                  'platformEnabled': true,
                  'balanceQuota': 1000,
                  'unit': 'new_api_quota',
                },
              },
            };
          }
          return null;
        });

    await tester.pumpWidget(_testApp());
    await tester.pumpAndSettle();

    expect(find.text('平台 AI 服务暂未开放'), findsWidgets);
    expect(find.text('1000'), findsNothing);
    expect(find.text('配置我的 API Key'), findsOneWidget);
  });
}

void _expectModeIconsVerticallyCentered(
  WidgetTester tester, {
  required String optionKey,
  required IconData leadingIcon,
  required IconData trailingIcon,
}) {
  final optionCenterY = tester.getCenter(find.byKey(ValueKey(optionKey))).dy;
  expect(
    tester.getCenter(find.byIcon(leadingIcon)).dy,
    closeTo(optionCenterY, 0.01),
  );
  expect(
    tester.getCenter(find.byIcon(trailingIcon)).dy,
    closeTo(optionCenterY, 0.01),
  );
}

Widget _testApp() {
  return MaterialApp(
    navigatorKey: GoRouterManager.rootNavigatorKey,
    locale: const Locale('zh'),
    supportedLocales: const <Locale>[Locale('zh')],
    localizationsDelegates: <LocalizationsDelegate<dynamic>>[
      GlobalMaterialLocalizations.delegate,
      GlobalWidgetsLocalizations.delegate,
      GlobalCupertinoLocalizations.delegate,
    ],
    home: AccountPage(),
  );
}
