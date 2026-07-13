import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/screen_dialog_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('cn.com.omnimind.bot/ScreenDialogEvent');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
  });

  test('hide returns false when the native channel is unavailable', () async {
    messenger.setMockMethodCallHandler(channel, null);

    expect(await ScreenDialogService.hideForExternalActivity(), isFalse);
  });

  test('hide forwards to the native channel', () async {
    messenger.setMockMethodCallHandler(channel, (call) async {
      expect(call.method, 'hideForExternalActivity');
      return true;
    });

    expect(await ScreenDialogService.hideForExternalActivity(), isTrue);
  });
}
