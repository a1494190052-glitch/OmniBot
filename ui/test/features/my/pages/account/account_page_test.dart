import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
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
  });

  testWidgets('shows email quota and platform mode after login', (
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
          return null;
        });

    await tester.pumpWidget(_testApp());
    await tester.pumpAndSettle();

    expect(find.text('learner@example.com'), findsOneWidget);
    expect(find.text('1000'), findsOneWidget);
    expect(find.text('使用平台额度'), findsOneWidget);
    expect(find.byIcon(Icons.radio_button_checked), findsOneWidget);
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

Widget _testApp() {
  return const MaterialApp(
    locale: Locale('zh'),
    supportedLocales: <Locale>[Locale('zh')],
    localizationsDelegates: <LocalizationsDelegate<dynamic>>[
      GlobalMaterialLocalizations.delegate,
      GlobalWidgetsLocalizations.delegate,
      GlobalCupertinoLocalizations.delegate,
    ],
    home: AccountPage(),
  );
}
