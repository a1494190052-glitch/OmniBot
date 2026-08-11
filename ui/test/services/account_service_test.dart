import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/account_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('cn.com.omnimind.bot/account');

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('reads configured signed-in state', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'getSessionState');
          return <String, Object?>{'configured': true, 'signedIn': true};
        });

    final state = await AccountService.getSessionState();

    expect(state.configured, isTrue);
    expect(state.signedIn, isTrue);
  });

  test(
    'reads safe platform routing state without receiving credentials',
    () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            expect(call.method, 'getAiRoutingState');
            return <String, Object?>{
              'mode': 'platform',
              'ready': true,
              'usesPlatform': true,
              'unavailableReason': null,
            };
          });

      final state = await AccountService.getAiRoutingState();

      expect(state.mode, AiAccessMode.platform);
      expect(state.ready, isTrue);
      expect(state.usesPlatform, isTrue);
      expect(state.unavailableReason, isNull);
    },
  );

  test('parses account overview and platform quota', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'getOverview');
          return _overviewPayload(mode: 'platform', balance: 750);
        });

    final overview = await AccountService.getOverview();

    expect(overview.user.email, 'learner@example.com');
    expect(overview.settings.mode, AiAccessMode.platform);
    expect(overview.settings.platformAvailable, isTrue);
    expect(overview.settings.platform.balance, 750);
    expect(overview.settings.keyStorage, 'device');
  });

  test('BYOK update sends only mode and never an API key', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'updateAiMode');
          final arguments = Map<dynamic, dynamic>.from(call.arguments as Map);
          expect(arguments, <String, Object?>{'mode': 'byok'});
          expect(arguments.containsKey('apiKey'), isFalse);
          return _settingsPayload(mode: 'byok', balance: 500);
        });

    final settings = await AccountService.updateAiMode(AiAccessMode.byok);

    expect(settings.mode, AiAccessMode.byok);
    expect(settings.keyStorage, 'device');
  });

  test('missing availability flag safely forces BYOK mode', () {
    final settings = AiSettings.fromMap(<String, Object?>{
      'mode': 'platform',
      'keyStorage': 'device',
      'platform': <String, Object?>{
        'platformEnabled': true,
        'balanceQuota': 500,
        'unit': 'new_api_quota',
      },
    });

    expect(settings.platformAvailable, isFalse);
    expect(settings.mode, AiAccessMode.byok);
  });
}

Map<String, Object?> _overviewPayload({
  required String mode,
  required int balance,
}) {
  return <String, Object?>{
    'user': <String, Object?>{
      'id': 'user-1',
      'email': 'learner@example.com',
      'role': 'user',
      'status': 'active',
    },
    'settings': _settingsPayload(mode: mode, balance: balance),
  };
}

Map<String, Object?> _settingsPayload({
  required String mode,
  required int balance,
  bool platformAvailable = true,
}) {
  return <String, Object?>{
    'mode': mode,
    'keyStorage': 'device',
    'platformAvailable': platformAvailable,
    'platform': <String, Object?>{
      'platformEnabled': true,
      'balanceQuota': balance,
      'unit': 'new_api_quota',
    },
  };
}
