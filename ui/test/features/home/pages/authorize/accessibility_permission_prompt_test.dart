import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/authorize/accessibility_permission_prompt.dart';
import 'package:ui/l10n/app_text_localizer.dart';
import 'package:ui/services/special_permission.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  tearDown(() {
    AppTextLocalizer.clearResolvedLocale();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(spePermission, null);
  });

  testWidgets(
    'opens the service settings and completes when service is ready',
    (tester) async {
      var ready = false;
      AppTextLocalizer.setResolvedLocale(const Locale('zh'));
      final calls = <MethodCall>[];
      Future<bool>? result;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(spePermission, (call) async {
            calls.add(call);
            if (call.method == 'isAndroidGuiAccessibilityReady') {
              return ready;
            }
            if (call.method == 'openAndroidGuiAccessibilitySettings') {
              return null;
            }
            return null;
          });

      await tester.pumpWidget(
        MaterialApp(
          home: Builder(
            builder: (context) => Scaffold(
              body: TextButton(
                onPressed: () {
                  result = showAccessibilityPermissionPrompt(context);
                },
                child: const Text('start'),
              ),
            ),
          ),
        ),
      );

      await tester.tap(find.text('start'));
      await tester.pumpAndSettle();
      expect(find.text('开启无障碍以继续'), findsOneWidget);

      await tester.tap(find.text('打开无障碍'));
      await tester.pump();
      expect(
        calls.map((call) => call.method),
        contains('openAndroidGuiAccessibilitySettings'),
      );

      ready = true;
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pump(const Duration(milliseconds: 250));
      await tester.pumpAndSettle();

      expect(find.text('开启无障碍以继续'), findsNothing);
      expect(await result, isTrue);
    },
  );
}
