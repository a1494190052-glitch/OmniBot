import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/agent/agent_mode_setting_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const agentRuntimeChannel = MethodChannel('cn.com.omnimind.bot/AgentRuntime');

  setUp(() async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    await StorageService.init();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
          if (call.method != 'agent/list') return null;
          return <String, dynamic>{
            'selectedAgentId': 'codex-acp',
            'agents': <Map<String, dynamic>>[
              _agent('codex-acp', 'Codex', 'codex-acp', 'online'),
              _agent(
                'claude-code-acp',
                'Claude Code',
                'claude-agent-acp',
                'missing',
              ),
              _agent(
                'opencode-acp',
                'OpenCode',
                'opencode',
                'offline',
                arguments: const ['acp'],
              ),
            ],
          };
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(agentRuntimeChannel, null);
  });

  testWidgets('shows the managed ACP Agent catalog without Gemini', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(1080, 2200);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('zh'),
        home: const AgentModeSettingPage(),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Codex'), findsOneWidget);
    expect(find.text('Claude Code'), findsOneWidget);
    expect(find.text('Gemini CLI'), findsNothing);
    expect(find.text('OpenCode'), findsOneWidget);
    expect(find.text('可用'), findsWidgets);
    expect(find.text('未安装'), findsOneWidget);
    expect(find.text('初始化失败'), findsOneWidget);
    expect(find.text('全部 3'), findsOneWidget);
    expect(find.text('预置 Agent'), findsOneWidget);
    expect(find.text('官方 Agent'), findsNothing);
    expect(find.text('官方'), findsNothing);
    expect(find.textContaining('统一 API'), findsNothing);
    expect(find.byType(PopupMenuButton<String>), findsNothing);
    // 3 Agent 卡片 + 1 远程 PC Bridge 入口卡片。
    expect(find.byIcon(Icons.chevron_right_rounded), findsNWidgets(4));
    expect(find.text('远程 PC Bridge'), findsOneWidget);
    expect(find.text('远程运行'), findsOneWidget);
    expect(find.text('使用'), findsNothing);
    expect(find.text('当前使用'), findsNothing);
    expect(find.text('Use Agent'), findsNothing);
    expect(find.text('Selected'), findsNothing);
  });
}

Map<String, dynamic> _agent(
  String id,
  String name,
  String command,
  String status, {
  List<String> arguments = const [],
}) {
  return <String, dynamic>{
    'id': id,
    'name': name,
    'description': '$name ACP Agent',
    'command': command,
    'arguments': arguments,
    'enabled': true,
    'builtIn': true,
    'source': 'official',
    'selected': id == 'codex-acp',
    'installed': status != 'missing',
    'status': status,
  };
}
