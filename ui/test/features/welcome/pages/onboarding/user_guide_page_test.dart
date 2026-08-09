import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/welcome/pages/onboarding/pages/other_features_guide_page.dart';
import 'package:ui/features/welcome/pages/onboarding/pages/plugin_market_guide_page.dart';
import 'package:ui/features/welcome/pages/onboarding/pages/user_guide_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/theme/app_theme.dart';

class _GuideTestAssetBundle extends CachingAssetBundle {
  static final Uint8List _svgBytes = Uint8List.fromList(
    utf8.encode(
      '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">'
      '<path d="M15 18l-6-6 6-6"/></svg>',
    ),
  );

  @override
  Future<ByteData> load(String key) async {
    return ByteData.view(_svgBytes.buffer);
  }

  @override
  Future<String> loadString(String key, {bool cache = true}) async {
    return utf8.decode(_svgBytes);
  }
}

Widget _buildGuide(Widget child) {
  return MaterialApp(
    locale: const Locale('zh'),
    localizationsDelegates: AppLocalizations.localizationsDelegates,
    supportedLocales: AppLocalizations.supportedLocales,
    theme: AppTheme.lightTheme,
    home: DefaultAssetBundle(bundle: _GuideTestAssetBundle(), child: child),
  );
}

void main() {
  testWidgets('guide directory opens quick start and advanced features', (
    tester,
  ) async {
    String? openedRoute;
    await tester.pumpWidget(
      _buildGuide(UserGuidePage(onOpenRoute: (route) => openedRoute = route)),
    );
    await tester.pump();

    expect(find.text('小万指南'), findsOneWidget);
    expect(find.text('快速开始'), findsOneWidget);
    expect(find.text('插件市场'), findsOneWidget);
    expect(find.text('高级功能支持'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('user-guide-quick-start')));
    expect(openedRoute, '/home/first_use_tutorial/setup');

    await tester.tap(find.byKey(const ValueKey('user-guide-plugin-market')));
    expect(openedRoute, '/home/first_use_tutorial/plugins');

    await tester.tap(find.byKey(const ValueKey('user-guide-other-features')));
    expect(openedRoute, '/home/first_use_tutorial/features');
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'plugin guide explains distribution and opens real destinations',
    (tester) async {
      tester.view.physicalSize = const Size(320, 640);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      String? openedRoute;
      await tester.pumpWidget(
        _buildGuide(
          PluginMarketGuidePage(onOpenRoute: (route) => openedRoute = route),
        ),
      );
      await tester.pump();

      expect(find.text('插件市场指南'), findsOneWidget);
      expect(find.text('选择并启用'), findsOneWidget);
      expect(find.text('直接告诉小万要做什么'), findsOneWidget);
      expect(find.text('从 Dashboard 或桌面进入'), findsOneWidget);

      final omniflow = find.byKey(const ValueKey('plugin-guide-omniflow'));
      await tester.ensureVisible(omniflow);
      await tester.pumpAndSettle();
      await tester.tap(omniflow);
      expect(openedRoute, '/home/plugin_market/com.omnimind.omni-vlm-lite');

      final market = find.byKey(const ValueKey('plugin-guide-open-market'));
      await tester.ensureVisible(market);
      await tester.pumpAndSettle();
      await tester.tap(market);
      expect(openedRoute, '/home/plugin_market');
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'advanced guide exposes real component destinations on narrow UI',
    (tester) async {
      tester.view.physicalSize = const Size(320, 640);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      String? openedRoute;
      await tester.pumpWidget(
        _buildGuide(
          OtherFeaturesGuidePage(onOpenRoute: (route) => openedRoute = route),
        ),
      );
      await tester.pump();

      expect(find.text('高级功能支持'), findsOneWidget);
      expect(find.text('VLM 与 GUI 操作'), findsOneWidget);
      expect(find.text('RunLog 与复用指令'), findsOneWidget);
      expect(find.text('Memory 管理'), findsOneWidget);
      expect(find.text('插件市场'), findsOneWidget);
      expect(find.text('Skills'), findsOneWidget);

      await tester.tap(find.byKey(const ValueKey('other-features-vlm')));
      expect(openedRoute, '/home/plugin_market/com.omnimind.omni-vlm-lite');

      final memory = find.byKey(const ValueKey('other-features-memory'));
      await tester.ensureVisible(memory);
      await tester.tap(memory);
      expect(openedRoute, '/memory/memory_center_page');

      final plugins = find.byKey(const ValueKey('other-features-plugins'));
      await tester.ensureVisible(plugins);
      await tester.tap(plugins);
      expect(openedRoute, '/home/plugin_market');
      expect(tester.takeException(), isNull);
    },
  );
}
