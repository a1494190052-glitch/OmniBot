import 'package:flutter/services.dart';

enum AiAccessMode { platform, byok }

class AccountSessionState {
  const AccountSessionState({required this.configured, required this.signedIn});

  final bool configured;
  final bool signedIn;

  factory AccountSessionState.fromMap(Map<dynamic, dynamic> map) {
    return AccountSessionState(
      configured: map['configured'] == true,
      signedIn: map['signedIn'] == true,
    );
  }
}

class AiRoutingState {
  const AiRoutingState({
    required this.mode,
    required this.ready,
    required this.usesPlatform,
    this.unavailableReason,
  });

  final AiAccessMode? mode;
  final bool ready;
  final bool usesPlatform;
  final String? unavailableReason;

  factory AiRoutingState.fromMap(Map<dynamic, dynamic> map) {
    final rawMode = map['mode']?.toString();
    final reason = map['unavailableReason']?.toString().trim();
    return AiRoutingState(
      mode: switch (rawMode) {
        'platform' => AiAccessMode.platform,
        'byok' => AiAccessMode.byok,
        _ => null,
      },
      ready: map['ready'] == true,
      usesPlatform: map['usesPlatform'] == true,
      unavailableReason: reason == null || reason.isEmpty ? null : reason,
    );
  }
}

class AccountUser {
  const AccountUser({
    required this.id,
    required this.email,
    required this.role,
    required this.status,
  });

  final String id;
  final String email;
  final String role;
  final String status;

  factory AccountUser.fromMap(Map<dynamic, dynamic> map) {
    return AccountUser(
      id: (map['id'] ?? '').toString(),
      email: (map['email'] ?? '').toString(),
      role: (map['role'] ?? '').toString(),
      status: (map['status'] ?? '').toString(),
    );
  }
}

class RegistrationCodeRequest {
  const RegistrationCodeRequest({
    required this.requestId,
    required this.expiresInSeconds,
  });

  final String requestId;
  final int expiresInSeconds;

  factory RegistrationCodeRequest.fromMap(Map<dynamic, dynamic> map) {
    return RegistrationCodeRequest(
      requestId: (map['requestId'] ?? '').toString(),
      expiresInSeconds: (map['expiresInSeconds'] as num?)?.toInt() ?? 0,
    );
  }
}

class PlatformQuota {
  const PlatformQuota({
    required this.enabled,
    required this.balance,
    required this.unit,
  });

  final bool enabled;
  final int balance;
  final String unit;

  factory PlatformQuota.fromMap(Map<dynamic, dynamic> map) {
    return PlatformQuota(
      enabled: map['platformEnabled'] == true,
      balance: (map['balanceQuota'] as num?)?.toInt() ?? 0,
      unit: (map['unit'] ?? '').toString(),
    );
  }
}

class AiSettings {
  const AiSettings({
    required this.mode,
    required this.keyStorage,
    required this.platform,
    required this.platformAvailable,
    this.platformUnavailableReason,
  });

  final AiAccessMode mode;
  final String keyStorage;
  final PlatformQuota platform;
  final bool platformAvailable;
  final String? platformUnavailableReason;

  factory AiSettings.fromMap(Map<dynamic, dynamic> map) {
    final platform = map['platform'];
    final platformAvailable = map['platformAvailable'] == true;
    final unavailableReason =
        map['platformUnavailableReason']?.toString().trim();
    return AiSettings(
      mode: platformAvailable && (map['mode'] ?? '').toString() == 'platform'
          ? AiAccessMode.platform
          : AiAccessMode.byok,
      keyStorage: (map['keyStorage'] ?? '').toString(),
      platform: PlatformQuota.fromMap(
        platform is Map ? Map<dynamic, dynamic>.from(platform) : const {},
      ),
      platformAvailable: platformAvailable,
      platformUnavailableReason: unavailableReason == null || unavailableReason.isEmpty
          ? null
          : unavailableReason,
    );
  }
}

class AccountOverview {
  const AccountOverview({required this.user, required this.settings});

  final AccountUser user;
  final AiSettings settings;

  factory AccountOverview.fromMap(Map<dynamic, dynamic> map) {
    final user = map['user'];
    final settings = map['settings'];
    if (user is! Map || settings is! Map) {
      throw const FormatException('Invalid account overview');
    }
    return AccountOverview(
      user: AccountUser.fromMap(Map<dynamic, dynamic>.from(user)),
      settings: AiSettings.fromMap(Map<dynamic, dynamic>.from(settings)),
    );
  }
}

class AccountService {
  static const MethodChannel _channel = MethodChannel(
    'cn.com.omnimind.bot/account',
  );

  static Future<AccountSessionState> getSessionState() async {
    final result = await _requiredMap('getSessionState');
    return AccountSessionState.fromMap(result);
  }

  static Future<AiRoutingState> getAiRoutingState() async {
    final result = await _requiredMap('getAiRoutingState');
    return AiRoutingState.fromMap(result);
  }

  static Future<RegistrationCodeRequest> requestRegistrationCode(
    String email,
  ) async {
    final result = await _requiredMap(
      'requestRegistrationCode',
      <String, Object?>{'email': email},
    );
    return RegistrationCodeRequest.fromMap(result);
  }

  static Future<AccountUser> register({
    required String email,
    required String password,
    required String verificationRequestId,
    required String verificationCode,
  }) async {
    final result = await _requiredMap('register', <String, Object?>{
      'email': email,
      'password': password,
      'verificationRequestId': verificationRequestId,
      'verificationCode': verificationCode,
    });
    return AccountUser.fromMap(result);
  }

  static Future<AccountUser> login({
    required String email,
    required String password,
  }) async {
    final result = await _requiredMap('login', <String, Object?>{
      'email': email,
      'password': password,
    });
    return AccountUser.fromMap(result);
  }

  static Future<void> logout() => _channel.invokeMethod<void>('logout');

  static Future<AccountOverview> getOverview() async {
    final result = await _requiredMap('getOverview');
    return AccountOverview.fromMap(result);
  }

  static Future<AiSettings> updateAiMode(AiAccessMode mode) async {
    final result = await _requiredMap('updateAiMode', <String, Object?>{
      'mode': mode == AiAccessMode.byok ? 'byok' : 'platform',
    });
    return AiSettings.fromMap(result);
  }

  static Future<Map<dynamic, dynamic>> _requiredMap(
    String method, [
    Map<String, Object?>? arguments,
  ]) async {
    final result = await _channel.invokeMethod<dynamic>(method, arguments);
    if (result is! Map) {
      throw PlatformException(
        code: 'INVALID_ACCOUNT_RESULT',
        message: 'Account result is invalid',
      );
    }
    return Map<dynamic, dynamic>.from(result);
  }
}
