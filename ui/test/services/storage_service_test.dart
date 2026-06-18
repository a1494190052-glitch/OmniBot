import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/models/chat_startup_behavior.dart';
import 'package:ui/services/storage_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    await StorageService.init();
  });

  test('defaults chat startup behavior to resume last', () {
    expect(
      StorageService.getChatStartupBehavior(),
      ChatStartupBehavior.resumeLast,
    );
  });

  test('round-trips chat startup behavior', () async {
    await StorageService.setChatStartupBehavior(
      ChatStartupBehavior.newConversation,
    );

    expect(
      StorageService.getChatStartupBehavior(),
      ChatStartupBehavior.newConversation,
    );
  });

  test('falls back to resume last for unknown chat startup behavior', () async {
    await StorageService.setString(
      StorageService.kChatStartupBehaviorKey,
      'unknown',
    );

    expect(
      StorageService.getChatStartupBehavior(),
      ChatStartupBehavior.resumeLast,
    );
  });

  test('defaults enhanced font effects to off', () {
    expect(StorageService.isEnhancedFontEffectsEnabled(), isFalse);
  });

  test('round-trips enhanced font effects preference', () async {
    await StorageService.setEnhancedFontEffectsEnabled(true);

    expect(StorageService.isEnhancedFontEffectsEnabled(), isTrue);

    await StorageService.setEnhancedFontEffectsEnabled(false);

    expect(StorageService.isEnhancedFontEffectsEnabled(), isFalse);
  });
}
