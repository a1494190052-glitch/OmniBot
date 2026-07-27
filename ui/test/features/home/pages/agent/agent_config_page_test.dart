import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/agent/agent_config_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const agentRuntimeChannel = MethodChannel('cn.com.omnimind.bot/AgentRuntime');

  setUp(() async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    await StorageService.init();
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(agentRuntimeChannel, null);
  });

  testWidgets('Codex config page reads and writes auth/config fields', (
    tester,
  ) async {
    Map<String, dynamic>? saved;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
          if (call.method == 'agent/list') {
            return _catalog(_agent('codex-acp', 'Codex'));
          }
          if (call.method == 'agent/config/read') {
            return <String, dynamic>{
              'agentId': 'codex-acp',
              'kind': 'codex',
              'configPath': '~/.codex/config.toml',
              'authPath': '~/.codex/auth.json',
              'baseUrl': 'https://old.example/v1',
              'model': 'old-model',
              'apiKey': 'sk-old',
            };
          }
          if (call.method == 'agent/config/write') {
            saved = Map<String, dynamic>.from(call.arguments as Map);
            return <String, dynamic>{
              'agentId': 'codex-acp',
              'kind': 'codex',
              'configPath': '~/.codex/config.toml',
              'authPath': '~/.codex/auth.json',
              'baseUrl': saved!['baseUrl'],
              'model': saved!['model'],
              'apiKey': saved!['apiKey'],
            };
          }
          return null;
        });

    await _pumpPage(tester, 'codex-acp');

    expect(find.textContaining('~/.codex/config.toml'), findsOneWidget);
    expect(find.textContaining('~/.codex/auth.json'), findsOneWidget);
    await tester.enterText(
      find.byKey(const Key('codex-agent-base-url')),
      'https://api.example/v1',
    );
    await tester.enterText(
      find.byKey(const Key('codex-agent-model')),
      'deepseek-chat',
    );
    await tester.enterText(
      find.byKey(const Key('codex-agent-api-key')),
      'sk-new',
    );
    await tester.tap(find.byKey(const Key('agent-config-save')));
    await tester.pumpAndSettle();

    expect(saved?['agentId'], 'codex-acp');
    expect(saved?['baseUrl'], 'https://api.example/v1');
    expect(saved?['model'], 'deepseek-chat');
    expect(saved?['apiKey'], 'sk-new');
  });

  testWidgets('Claude config page edits the complete settings.json content', (
    tester,
  ) async {
    Map<String, dynamic>? saved;
    const initial = '{\n  "env": {"ANTHROPIC_MODEL": "claude-sonnet"}\n}\n';
    const updated = '{\n  "env": {"ANTHROPIC_MODEL": "claude-opus"}\n}\n';
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
          if (call.method == 'agent/list') {
            return _catalog(_agent('claude-code-acp', 'Claude Code'));
          }
          if (call.method == 'agent/config/read') {
            return <String, dynamic>{
              'agentId': 'claude-code-acp',
              'kind': 'json',
              'path': '~/.claude/settings.json',
              'content': initial,
            };
          }
          if (call.method == 'agent/config/write') {
            saved = Map<String, dynamic>.from(call.arguments as Map);
            return <String, dynamic>{
              'agentId': 'claude-code-acp',
              'kind': 'json',
              'path': '~/.claude/settings.json',
              'content': saved!['content'],
            };
          }
          return null;
        });

    await _pumpPage(tester, 'claude-code-acp');

    expect(find.textContaining('~/.claude/settings.json'), findsWidgets);
    expect(find.textContaining('claude-sonnet'), findsOneWidget);
    await tester.enterText(
      find.byKey(const Key('agent-raw-config-content')),
      updated,
    );
    await tester.tap(find.byKey(const Key('agent-config-save')));
    await tester.pumpAndSettle();

    expect(saved?['agentId'], 'claude-code-acp');
    expect(saved?['content'], updated);
    expect(find.textContaining('claude-opus'), findsOneWidget);
  });
}

Future<void> _pumpPage(WidgetTester tester, String agentId) async {
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
      home: AgentConfigPage(agentId: agentId),
    ),
  );
  await tester.pumpAndSettle();
}

Map<String, dynamic> _catalog(Map<String, dynamic> agent) {
  return <String, dynamic>{
    'selectedAgentId': agent['id'],
    'agents': <Map<String, dynamic>>[agent],
  };
}

Map<String, dynamic> _agent(String id, String name) {
  return <String, dynamic>{
    'id': id,
    'name': name,
    'description': '$name ACP Agent',
    'command': id == 'codex-acp' ? 'codex-acp' : 'claude-agent-acp',
    'enabled': true,
    'builtIn': true,
    'source': 'official',
    'status': 'online',
  };
}
