import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:ui/features/home/pages/plugin_market/plugin_detail_page.dart';
import 'package:ui/features/home/pages/plugin_market/plugin_market_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/models/omni_plugin_item.dart';
import 'package:ui/theme/app_theme.dart';

class _SvgTestAssetBundle extends CachingAssetBundle {
  static final Uint8List _svgBytes = Uint8List.fromList(
    utf8.encode(
      '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">'
      '<rect width="24" height="24" fill="#000000"/>'
      '</svg>',
    ),
  );

  @override
  Future<ByteData> load(String key) async => ByteData.view(_svgBytes.buffer);

  @override
  Future<String> loadString(String key, {bool cache = true}) async {
    return utf8.decode(_svgBytes);
  }
}

Widget _app() {
  final router = GoRouter(
    initialLocation: '/home/plugin_market',
    routes: [
      GoRoute(
        path: '/home/plugin_market',
        builder: (context, state) => const PluginMarketPage(),
        routes: [
          GoRoute(
            path: ':pluginId',
            builder: (context, state) => PluginDetailPage(
              pluginId: state.pathParameters['pluginId']!,
              initialPlugin: state.extra as OmniPluginItem?,
            ),
          ),
        ],
      ),
    ],
  );
  return MaterialApp.router(
    routerConfig: router,
    locale: const Locale('zh'),
    theme: AppTheme.lightTheme,
    localizationsDelegates: AppLocalizations.localizationsDelegates,
    supportedLocales: AppLocalizations.supportedLocales,
    builder: (context, child) =>
        DefaultAssetBundle(bundle: _SvgTestAssetBundle(), child: child!),
  );
}

Map<String, Object?> _runtimePlugin() => <String, Object?>{
  'id': 'com.omnimind.omni-vlm-lite',
  'name': 'omni-vlm-lite',
  'version': '1.0.0',
  'interfaceVersion': 1,
  'description': '内置的视觉操作能力',
  'publisher': 'OmniMind',
  'kind': 'bundled_module',
  'downloadSizeBytes': 0,
  'capabilities': <String>['agent_tool'],
  'settingsSchema': <String, Object?>{},
  'installed': false,
  'enabled': false,
  'compatible': true,
};

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('cn.com.omnimind.bot/PluginPlatform');
  final calls = <MethodCall>[];
  var plugins = <Map<String, Object?>>[];

  setUp(() {
    calls.clear();
    plugins = <Map<String, Object?>>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          switch (call.method) {
            case 'list':
              return plugins;
            case 'install':
              final installed = <String, Object?>{
                ...plugins.single,
                'installed': true,
                'enabled': true,
              };
              plugins = <Map<String, Object?>>[installed];
              return installed;
            case 'update':
              final updated = <String, Object?>{
                ...plugins.single,
                'version': '2.0.0',
              };
              plugins = <Map<String, Object?>>[updated];
              return updated;
            case 'setEnabled':
              final arguments = Map<Object?, Object?>.from(
                call.arguments as Map,
              );
              final updated = <String, Object?>{
                ...plugins.single,
                'enabled': arguments['enabled'] == true,
              };
              plugins = <Map<String, Object?>>[updated];
              return updated;
            case 'uninstall':
              plugins = <Map<String, Object?>>[
                <String, Object?>{
                  ...plugins.single,
                  'installed': false,
                  'enabled': false,
                },
              ];
              return true;
            default:
              return null;
          }
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  testWidgets('shows official empty state when catalog has no plugins', (
    tester,
  ) async {
    await tester.pumpWidget(_app());
    await tester.pumpAndSettle();

    expect(find.text('插件市场'), findsOneWidget);
    expect(find.text('暂无可用插件'), findsOneWidget);
    expect(find.text('官方插件接入后会显示在这里'), findsOneWidget);
  });

  testWidgets('opens a listed runtime plugin in a separate detail page', (
    tester,
  ) async {
    plugins = <Map<String, Object?>>[_runtimePlugin()];

    await tester.pumpWidget(_app());
    await tester.pumpAndSettle();

    expect(find.text('omni-vlm-lite'), findsOneWidget);
    expect(find.textContaining('内置模块'), findsOneWidget);
    expect(find.byType(Card), findsNothing);
    expect(find.text('安装'), findsNothing);

    await tester.tap(find.text('omni-vlm-lite'));
    await tester.pumpAndSettle();

    expect(find.text('插件详情'), findsOneWidget);
    expect(find.text('内置的视觉操作能力'), findsOneWidget);
    expect(find.text('下载大小'), findsNothing);
    expect(find.text('agent_tool'), findsOneWidget);
    expect(find.text('安装'), findsOneWidget);
  });

  testWidgets('install atomically enables a plugin from its detail page', (
    tester,
  ) async {
    plugins = <Map<String, Object?>>[_runtimePlugin()];

    await tester.pumpWidget(_app());
    await tester.pumpAndSettle();
    await tester.tap(find.text('omni-vlm-lite'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('安装'));
    await tester.pumpAndSettle();

    expect(calls.any((call) => call.method == 'install'), isTrue);
    expect(find.text('卸载'), findsOneWidget);
    expect(find.byType(Switch), findsOneWidget);
    expect(calls.any((call) => call.method == 'setEnabled'), isFalse);
    expect(find.text('已启用'), findsWidgets);
    expect(find.text('打开执行中心'), findsOneWidget);
  });

  testWidgets('updates an installed plugin from its detail page', (
    tester,
  ) async {
    plugins = <Map<String, Object?>>[
      <String, Object?>{
        ..._runtimePlugin(),
        'installed': true,
        'enabled': true,
      },
    ];

    await tester.pumpWidget(_app());
    await tester.pumpAndSettle();
    await tester.tap(find.text('omni-vlm-lite'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('更新'));
    await tester.pumpAndSettle();

    expect(calls.any((call) => call.method == 'update'), isTrue);
    expect(find.textContaining('v2.0.0'), findsOneWidget);
  });
}
