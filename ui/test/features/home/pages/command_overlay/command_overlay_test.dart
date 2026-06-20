import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/command_overlay/command_overlay.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const assistCoreChannel = MethodChannel(
    'cn.com.omnimind.bot/AssistCoreEvent',
  );
  const cacheChannel = MethodChannel('cn.com.omnimind.bot/CacheDataEvent');
  const speechChannel = MethodChannel('cn.com.omnimind.bot/SpeechRecognition');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    await StorageService.init();
    messenger.setMockMethodCallHandler(assistCoreChannel, (call) async {
      if (call.method == 'getScheduleInfo') {
        return null;
      }
      return null;
    });
    messenger.setMockMethodCallHandler(cacheChannel, (call) async {
      switch (call.method) {
        case 'doMMKVDecodeString':
          final args = Map<Object?, Object?>.from(call.arguments as Map);
          return args['defaultValue'] ?? '';
        case 'doMMKVDecodeBoole':
          final args = Map<Object?, Object?>.from(call.arguments as Map);
          return args['defaultValue'] ?? false;
        case 'doMMKVDecodeInt':
          final args = Map<Object?, Object?>.from(call.arguments as Map);
          return args['defaultValue'] ?? 0;
        case 'doMMKVDecodeDouble':
          final args = Map<Object?, Object?>.from(call.arguments as Map);
          return (args['defaultValue'] ?? 0.0).toString();
        case 'doMMKVEncodeString':
        case 'doMMKVEncodeBool':
        case 'doMMKVEncodeInt':
        case 'doMMKVEncodeDouble':
          return true;
      }
      return null;
    });
    messenger.setMockMethodCallHandler(speechChannel, (call) async {
      if (call.method == 'initialize') {
        return true;
      }
      return null;
    });
  });

  tearDown(() {
    messenger.setMockMethodCallHandler(assistCoreChannel, null);
    messenger.setMockMethodCallHandler(cacheChannel, null);
    messenger.setMockMethodCallHandler(speechChannel, null);
  });

  testWidgets(
    'overlay input keeps reusable command shortcuts out of default UI',
    (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('zh'),
          home: const CommandOverlay(),
        ),
      );
      await tester.pump();

      expect(
        find.byKey(const ValueKey('chat-input-manual-recording-button')),
        findsNothing,
      );
      expect(
        find.byKey(const ValueKey('chat-input-trajectory-popup')),
        findsNothing,
      );
      expect(find.text('复用指令'), findsNothing);
      expect(find.text('录制轨迹'), findsNothing);
      expect(find.text('当前轨迹'), findsNothing);
    },
  );
}
