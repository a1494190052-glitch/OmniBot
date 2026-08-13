import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/my/pages/account/account_page.dart';
import 'package:ui/theme/app_theme.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('cn.com.omnimind.bot/account');

  test('weekly quota countdown uses the next Monday at midnight', () {
    expect(
      formatWeeklyQuotaResetCountdown(
        DateTime(2026, 8, 13, 12),
        english: false,
      ),
      '3天 12小时',
    );
    expect(
      formatWeeklyQuotaResetCountdown(DateTime(2026, 8, 17), english: true),
      '7d 0h',
    );
  });

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
    expect(find.byKey(const Key('submit-auth')), findsOneWidget);

    await tester.tap(find.text('注册'));
    await tester.pumpAndSettle();

    expect(find.text('创建小万账号'), findsOneWidget);
    expect(
      find.byKey(const Key('auth-confirm-password-field')),
      findsOneWidget,
    );
  });

  testWidgets(
    'blocks account UI on old versions while preserving BYOK guidance',
    (tester) async {
      var overviewCalls = 0;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            if (call.method == 'getSessionState') {
              return <String, Object?>{
                'configured': true,
                'signedIn': false,
                'cloudServiceAccessAllowed': false,
                'cloudServicePolicyKnown': true,
                'currentVersion': '0.5.6.15',
                'minimumVersion': '0.5.7',
                'cloudServiceUnavailableReason': '请升级到最新版',
              };
            }
            if (call.method == 'getOverview') overviewCalls += 1;
            return null;
          });

      await tester.pumpWidget(_testApp());
      await tester.pumpAndSettle();

      expect(
        find.byKey(const ValueKey('account-cloud-service-version-gate')),
        findsOneWidget,
      );
      expect(find.text('请升级到最新版'), findsOneWidget);
      expect(find.text('当前 v0.5.6.15 · 最低 v0.5.7'), findsOneWidget);
      expect(find.textContaining('BYOK'), findsOneWidget);
      expect(
        find.byKey(const ValueKey('account-required-update-action')),
        findsOneWidget,
      );
      expect(find.byKey(const Key('submit-auth')), findsNothing);
      expect(overviewCalls, 0);
    },
  );

  testWidgets(
    'shows email and quota without an AI source chooser after login',
    (tester) async {
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
                    'weeklyLimitQuota': 5000,
                    'weeklyUsedQuota': 1200,
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
      expect(find.text('AI 来源'), findsNothing);
      expect(find.text('使用平台额度'), findsNothing);
      expect(find.text('使用自己的 API Key'), findsNothing);
      expect(find.text('本周剩余额度'), findsNothing);
      expect(find.text('可用于平台提供的 AI 服务'), findsNothing);
      expect(find.textContaining('距离重置'), findsOneWidget);
      expect(find.textContaining('周一 00:00'), findsNothing);
      expect(
        find.byKey(const ValueKey('account-platform-quota-percent')),
        findsOneWidget,
      );
      expect(find.text('20%'), findsOneWidget);
      final quotaText = tester.widget<RichText>(
        find.byKey(const ValueKey('account-platform-quota-ratio')),
      );
      final quotaSpan = quotaText.text as TextSpan;
      final valueSpan = quotaSpan.children![0] as TextSpan;
      final limitSpan = quotaSpan.children![2] as TextSpan;
      expect(quotaSpan.toPlainText(), '1000/5000');
      expect(
        valueSpan.style!.fontSize,
        greaterThan(limitSpan.style!.fontSize!),
      );
      expect(valueSpan.style!.color, isNot(limitSpan.style!.color));
      expect(find.text('Key 只保存在当前设备，不会上传账号服务器。'), findsNothing);
      expect(find.text('由小万平台统一提供模型服务，不显示内部 API 端。'), findsNothing);
      expect(find.byIcon(LucideIcons.userRound), findsOneWidget);
      expect(find.byIcon(LucideIcons.coins), findsOneWidget);
      expect(find.byType(Divider), findsWidgets);
      expect(tester.takeException(), isNull);
    },
  );

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
    expect(find.text('AI 来源'), findsNothing);
    expect(find.text('配置我的 API Key'), findsNothing);
  });

  testWidgets('resets a forgotten password with a reset-purpose email code', (
    tester,
  ) async {
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          switch (call.method) {
            case 'getSessionState':
              return <String, Object?>{'configured': true, 'signedIn': false};
            case 'requestPasswordResetCode':
              return <String, Object?>{
                'requestId': 'reset-request-1',
                'expiresInSeconds': 600,
              };
            case 'resetPassword':
              return null;
          }
          return null;
        });

    await tester.pumpWidget(_testApp());
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('forgot-password')));
    await tester.pumpAndSettle();

    expect(find.text('重置密码'), findsOneWidget);
    await tester.enterText(
      find.byKey(const ValueKey('auth-email-field')),
      'learner@example.com',
    );
    await tester.tap(find.text('发送'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));

    expect(
      calls.where((call) => call.method == 'requestPasswordResetCode'),
      hasLength(1),
    );
    expect(
      calls.where((call) => call.method == 'requestRegistrationCode'),
      isEmpty,
    );

    const newPassword = 'NewPass26!';
    await tester.enterText(
      find.byKey(const ValueKey('auth-password-field')),
      newPassword,
    );
    await tester.enterText(
      find.byKey(const ValueKey('auth-confirm-password-field')),
      newPassword,
    );
    await tester.enterText(
      find.byKey(const ValueKey('auth-verification-code-field')),
      '123456',
    );
    await tester.tap(find.byKey(const ValueKey('submit-auth')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));

    final resetCall = calls.singleWhere(
      (call) => call.method == 'resetPassword',
    );
    expect(resetCall.arguments, <String, Object?>{
      'email': 'learner@example.com',
      'newPassword': newPassword,
      'verificationRequestId': 'reset-request-1',
      'verificationCode': '123456',
    });
    expect(find.text('登录小万账号'), findsOneWidget);
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('shows recent platform usage without exposing credentials', (
    tester,
  ) async {
    _setPhoneViewport(tester);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          switch (call.method) {
            case 'getSessionState':
              return <String, Object?>{'configured': true, 'signedIn': true};
            case 'getOverview':
              return _signedInOverview();
            case 'listPlatformUsage':
              expect(call.arguments, <String, Object?>{'limit': 20});
              return <Map<String, Object?>>[
                <String, Object?>{
                  'model': 'qwen-official',
                  'promptTokens': 12,
                  'completionTokens': 8,
                  'totalTokens': 20,
                  'quotaUsed': 17,
                  'createdAt': '2026-08-12T08:30:00Z',
                },
              ];
          }
          return null;
        });

    await tester.pumpWidget(_testApp());
    await tester.pumpAndSettle();
    await _scrollTo(tester, const ValueKey('account-usage-action'));
    await tester.tap(find.byKey(const ValueKey('account-usage-action')));
    await tester.pumpAndSettle();

    expect(find.text('qwen-official'), findsOneWidget);
    expect(find.text('消耗 17'), findsOneWidget);
    expect(find.textContaining('输入 12'), findsOneWidget);
  });

  testWidgets('revokes one session and then all remaining other sessions', (
    tester,
  ) async {
    _setPhoneViewport(tester);
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          switch (call.method) {
            case 'getSessionState':
              return <String, Object?>{'configured': true, 'signedIn': true};
            case 'getOverview':
              return _signedInOverview();
            case 'listSessions':
              return <Map<String, Object?>>[
                _sessionPayload('current', current: true, minute: 30),
                _sessionPayload('other-1', current: false, minute: 20),
                _sessionPayload('other-2', current: false, minute: 10),
              ];
            case 'revokeSession':
              return null;
            case 'revokeOtherSessions':
              return <String, Object?>{'revoked': 1};
          }
          return null;
        });

    await tester.pumpWidget(_testApp());
    await tester.pumpAndSettle();
    await _scrollTo(tester, const ValueKey('account-sessions-action'));
    await tester.tap(find.byKey(const ValueKey('account-sessions-action')));
    await tester.pumpAndSettle();

    expect(find.text('当前设备'), findsOneWidget);
    expect(find.text('其他登录设备 1'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('revoke-session-other-1')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('confirm-revoke-session')));
    await tester.pumpAndSettle();

    final revokeCall = calls.singleWhere(
      (call) => call.method == 'revokeSession',
    );
    expect(revokeCall.arguments, <String, Object?>{'sessionId': 'other-1'});
    expect(find.text('已退出该设备'), findsOneWidget);
    expect(
      find.byKey(const ValueKey('account-session-current')),
      findsOneWidget,
    );

    await tester.tap(find.byKey(const ValueKey('revoke-other-sessions')));
    await tester.pumpAndSettle();
    await tester.tap(
      find.byKey(const ValueKey('confirm-revoke-other-sessions')),
    );
    await tester.pumpAndSettle();

    expect(
      calls.where((call) => call.method == 'revokeOtherSessions'),
      hasLength(1),
    );
    expect(find.text('已退出 1 个其他设备'), findsOneWidget);
    expect(find.textContaining('其他登录设备'), findsNothing);
  });

  testWidgets('changes password with a local busy state and stable errors', (
    tester,
  ) async {
    _setPhoneViewport(tester);
    var attempts = 0;
    MethodCall? successfulCall;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          switch (call.method) {
            case 'getSessionState':
              return <String, Object?>{'configured': true, 'signedIn': true};
            case 'getOverview':
              return _signedInOverview();
            case 'changePassword':
              attempts += 1;
              if (attempts == 1) {
                throw PlatformException(
                  code: 'current_password_invalid',
                  message: 'server detail must not be shown',
                );
              }
              successfulCall = call;
              return null;
          }
          return null;
        });

    await tester.pumpWidget(_testApp());
    await tester.pumpAndSettle();
    await _scrollTo(tester, const ValueKey('change-password-action'));
    await tester.tap(find.byKey(const ValueKey('change-password-action')));
    await tester.pumpAndSettle();

    const newPassword = 'Changed26!';
    await tester.enterText(
      find.byKey(const ValueKey('current-password-field')),
      'wrong current password',
    );
    await tester.enterText(
      find.byKey(const ValueKey('new-password-field')),
      newPassword,
    );
    await tester.enterText(
      find.byKey(const ValueKey('confirm-new-password-field')),
      newPassword,
    );
    await tester.tap(find.byKey(const ValueKey('confirm-change-password')));
    await tester.pumpAndSettle();

    expect(find.text('当前密码不正确'), findsOneWidget);
    expect(find.textContaining('server detail'), findsNothing);
    await tester.enterText(
      find.byKey(const ValueKey('current-password-field')),
      'correct current password',
    );
    await tester.tap(find.byKey(const ValueKey('confirm-change-password')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));

    expect(successfulCall?.arguments, <String, Object?>{
      'currentPassword': 'correct current password',
      'newPassword': newPassword,
    });
    expect(find.byKey(const ValueKey('current-password-field')), findsNothing);
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('requires two confirmations before deleting the account', (
    tester,
  ) async {
    _setPhoneViewport(tester);
    final deleteCalls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          switch (call.method) {
            case 'getSessionState':
              return <String, Object?>{'configured': true, 'signedIn': true};
            case 'getOverview':
              return _signedInOverview();
            case 'deleteAccount':
              deleteCalls.add(call);
              return null;
          }
          return null;
        });

    await tester.pumpWidget(_testApp());
    await tester.pumpAndSettle();
    await _scrollTo(tester, const ValueKey('delete-account-action'));
    await tester.tap(find.byKey(const ValueKey('delete-account-action')));
    await tester.pumpAndSettle();

    expect(find.text('永久删除账号？'), findsOneWidget);
    expect(find.textContaining('本机聊天和文件不会自动清理'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('continue-delete-account')));
    await tester.pumpAndSettle();

    expect(find.text('最后确认'), findsOneWidget);
    await tester.enterText(
      find.byKey(const ValueKey('delete-account-email-field')),
      'wrong@example.com',
    );
    await tester.enterText(
      find.byKey(const ValueKey('delete-account-password-field')),
      'current password',
    );
    await tester.tap(find.byKey(const ValueKey('confirm-delete-account')));
    await tester.pump();
    expect(deleteCalls, isEmpty);
    expect(find.text('请输入当前账号的完整邮箱'), findsOneWidget);

    await tester.enterText(
      find.byKey(const ValueKey('delete-account-email-field')),
      'learner@example.com',
    );
    await tester.tap(find.byKey(const ValueKey('confirm-delete-account')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));

    expect(deleteCalls, hasLength(1));
    expect(deleteCalls.single.arguments, <String, Object?>{
      'currentPassword': 'current password',
    });
    expect(find.text('登录小万账号'), findsOneWidget);
    await tester.pump(const Duration(seconds: 3));
  });
}

Widget _testApp() {
  return MaterialApp(
    navigatorKey: GoRouterManager.rootNavigatorKey,
    theme: AppTheme.lightTheme,
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

void _setPhoneViewport(WidgetTester tester) {
  tester.view.physicalSize = const Size(390, 844);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
}

Future<void> _scrollTo(WidgetTester tester, Key key) async {
  await tester.scrollUntilVisible(
    find.byKey(key),
    260,
    scrollable: find.byType(Scrollable).first,
  );
  await tester.pumpAndSettle();
}

Map<String, Object?> _signedInOverview() {
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
      'officialProviderReady': true,
      'platform': <String, Object?>{
        'platformEnabled': true,
        'balanceQuota': 1000,
        'unit': 'new_api_quota',
      },
    },
  };
}

Map<String, Object?> _sessionPayload(
  String id, {
  required bool current,
  required int minute,
}) {
  final timestamp = '2026-08-12T08:${minute.toString().padLeft(2, '0')}:00Z';
  return <String, Object?>{
    'id': id,
    'expiresAt': '2026-09-12T08:00:00Z',
    'createdAt': timestamp,
    'lastUsedAt': timestamp,
    'current': current,
  };
}
