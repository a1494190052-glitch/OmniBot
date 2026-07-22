import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_switch/flutter_switch.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/codex/codex_setting_page.dart';
import 'package:ui/features/home/pages/scene_model_setting/scene_model_setting_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/storage_service.dart';
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
  Future<ByteData> load(String key) async {
    return ByteData.view(_svgBytes.buffer);
  }

  @override
  Future<String> loadString(String key, {bool cache = true}) async {
    return utf8.decode(_svgBytes);
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
  const codexChannel = MethodChannel('cn.com.omnimind.bot/CodexAppServer');

  Widget buildTestApp(Widget child, {Locale locale = const Locale('zh')}) {
    return MaterialApp(
      theme: AppTheme.lightTheme,
      darkTheme: AppTheme.darkTheme,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: locale,
      home: DefaultAssetBundle(bundle: _SvgTestAssetBundle(), child: child),
    );
  }

  late Map<String, dynamic> savedVoiceConfig;
  late Map<String, dynamic> savedOperationConfig;
  late Map<String, dynamic>? savedCodexConfig;
  late int getSceneModelCatalogCount;
  late int codexWriteCount;

  setUp(() async {
    AssistsMessageService.initialize();
    SharedPreferences.setMockInitialValues(<String, Object>{});
    await StorageService.init();
    getSceneModelCatalogCount = 0;
    codexWriteCount = 0;
    savedCodexConfig = null;
    savedVoiceConfig = <String, dynamic>{
      'autoPlay': false,
      'voiceId': 'default_zh',
      'stylePreset': '默认',
      'customStyle': '',
    };
    savedOperationConfig = <String, dynamic>{'useOfficialService': false};

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          switch (call.method) {
            case 'getSceneModelCatalog':
              getSceneModelCatalogCount += 1;
              return <Map<String, dynamic>>[
                <String, dynamic>{
                  'sceneId': 'scene.vlm.operation.primary',
                  'description': '负责执行 UI 操作主链路',
                  'defaultModel': 'default-operation-model',
                  'effectiveModel': 'default-operation-model',
                  'effectiveProviderProfileId': '',
                  'effectiveProviderProfileName': '',
                  'boundProviderProfileId': '',
                  'boundProviderProfileName': '',
                  'transport': 'openai_compatible',
                  'configSource': 'builtin',
                  'overrideApplied': false,
                  'overrideModel': '',
                  'providerConfigured': false,
                  'bindingExists': false,
                  'bindingProfileMissing': false,
                },
                <String, dynamic>{
                  'sceneId': 'scene.voice',
                  'description': '负责 AI 回复文本的语音合成与播放',
                  'defaultModel': '',
                  'effectiveModel': '',
                  'effectiveProviderProfileId': '',
                  'effectiveProviderProfileName': '',
                  'boundProviderProfileId': '',
                  'boundProviderProfileName': '',
                  'transport': 'openai_compatible',
                  'configSource': 'builtin',
                  'overrideApplied': false,
                  'overrideModel': '',
                  'providerConfigured': false,
                  'bindingExists': false,
                  'bindingProfileMissing': false,
                },
                <String, dynamic>{
                  'sceneId': 'scene.compactor.context',
                  'description': '负责 VLM 执行链的上下文压缩与纠错',
                  'defaultModel': 'legacy-compactor-model',
                  'effectiveModel': 'legacy-compactor-model',
                  'effectiveProviderProfileId': '',
                  'effectiveProviderProfileName': '',
                  'boundProviderProfileId': '',
                  'boundProviderProfileName': '',
                  'transport': 'openai_compatible',
                  'configSource': 'builtin',
                  'overrideApplied': false,
                  'overrideModel': '',
                  'providerConfigured': false,
                  'bindingExists': false,
                  'bindingProfileMissing': false,
                },
                <String, dynamic>{
                  'sceneId': 'scene.compactor.context.chat',
                  'description': '负责聊天历史压缩总结',
                  'defaultModel': 'chat-compactor-model',
                  'effectiveModel': 'chat-compactor-model',
                  'effectiveProviderProfileId': '',
                  'effectiveProviderProfileName': '',
                  'boundProviderProfileId': '',
                  'boundProviderProfileName': '',
                  'transport': 'openai_compatible',
                  'configSource': 'builtin',
                  'overrideApplied': false,
                  'overrideModel': '',
                  'providerConfigured': false,
                  'bindingExists': false,
                  'bindingProfileMissing': false,
                },
              ];
            case 'getSceneModelBindings':
              return <Map<String, dynamic>>[];
            case 'listModelProviderProfiles':
              return <String, dynamic>{
                'profiles': <Map<String, dynamic>>[
                  <String, dynamic>{
                    'id': 'provider-1',
                    'name': 'Provider One',
                    'baseUrl': 'https://example.com/v1',
                    'apiKey': 'secret',
                    'configured': true,
                    'protocolType': 'openai_compatible',
                  },
                ],
                'editingProfileId': 'provider-1',
              };
            case 'fetchProviderModels':
              return <Map<String, dynamic>>[];
            case 'getSceneVoiceConfig':
              return savedVoiceConfig;
            case 'saveSceneVoiceConfig':
              savedVoiceConfig = Map<String, dynamic>.from(
                (call.arguments as Map).cast<String, dynamic>(),
              );
              return savedVoiceConfig;
            case 'getSceneOperationConfig':
              return savedOperationConfig;
            case 'saveSceneOperationConfig':
              savedOperationConfig = Map<String, dynamic>.from(
                (call.arguments as Map).cast<String, dynamic>(),
              );
              return savedOperationConfig;
            default:
              return null;
          }
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(codexChannel, (call) async {
          switch (call.method) {
            case 'config/local/read':
              return <String, dynamic>{
                'baseUrl': 'https://example.com/v1',
                'model': 'gpt-5.5',
                'apiKey': 'test-key',
                'codexHome': '/root/.codex',
              };
            case 'config/local/write':
              savedCodexConfig = Map<String, dynamic>.from(
                (call.arguments as Map).cast<String, dynamic>(),
              );
              codexWriteCount += 1;
              return <String, dynamic>{
                ...savedCodexConfig!,
                'codexHome': '/root/.codex',
              };
            default:
              return null;
          }
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(codexChannel, null);
  });

  testWidgets('voice scene expands and saves voice settings', (tester) async {
    tester.view.physicalSize = const Size(1080, 2000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(buildTestApp(const SceneModelSettingPage()));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    expect(find.text('Voice'), findsOneWidget);
    expect(find.text('Operation'), findsOneWidget);
    expect(find.text('Compactor'), findsNothing);
    expect(find.text('Chat Compactor'), findsOneWidget);
    expect(find.text('未绑定'), findsOneWidget);
    expect(find.text('使用官方托管 VLM 服务'), findsOneWidget);
    expect(
      find.byKey(const Key('operation-official-service-toggle')),
      findsOneWidget,
    );
    expect(find.text('AI 响应完成后自动播放'), findsNothing);
    expect(find.byKey(const Key('voice-scene-expand-button')), findsOneWidget);

    await tester.tap(
      find.byKey(const Key('operation-official-service-toggle')),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    expect(savedOperationConfig['useOfficialService'], isTrue);

    await tester.tap(find.byKey(const Key('voice-scene-expand-button')));
    await tester.pumpAndSettle();

    expect(find.text('AI 响应完成后自动播放'), findsOneWidget);
    expect(find.byType(FlutterSwitch), findsNWidgets(2));
    expect(find.byType(Switch), findsNothing);
    expect(find.byKey(const Key('voice-scene-voice-id-field')), findsOneWidget);
    expect(
      find.byKey(const Key('voice-scene-custom-style-field')),
      findsOneWidget,
    );
    expect(find.text('保存语音设置'), findsNothing);
    expect(find.textContaining('建议绑定 MiMo'), findsNothing);

    await tester.enterText(
      find.byKey(const Key('voice-scene-voice-id-field')),
      'mimo_default',
    );
    await tester.pump(const Duration(milliseconds: 500));

    await tester.tap(find.byKey(const Key('voice-style-option-温柔陪伴')));
    await tester.pumpAndSettle();

    await tester.enterText(
      find.byKey(const Key('voice-scene-custom-style-field')),
      '更温柔一点',
    );
    await tester.pump(const Duration(milliseconds: 500));

    expect(savedVoiceConfig['voiceId'], 'mimo_default');
    expect(savedVoiceConfig['stylePreset'], '温柔陪伴');
    expect(savedVoiceConfig['customStyle'], '更温柔一点');

    final catalogCallCountAfterSave = getSceneModelCatalogCount;
    AssistsMessageService.dispatchAgentAiConfigChanged(
      const AgentAiConfigChangedEvent(source: 'store', path: '/tmp/agent.json'),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    expect(getSceneModelCatalogCount, catalogCallCountAfterSave);
    expect(codexWriteCount, 0);
  });

  testWidgets('operation service toggle uses English copy', (tester) async {
    tester.view.physicalSize = const Size(390, 1200);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(
      buildTestApp(const SceneModelSettingPage(), locale: const Locale('en')),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    expect(find.text('Use Official Hosted VLM Service'), findsOneWidget);

    await tester.tap(
      find.byKey(const Key('operation-official-service-toggle')),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    expect(savedOperationConfig['useOfficialService'], isTrue);
  });

  testWidgets('codex setting page autosaves after fields are complete', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(1080, 2200);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(buildTestApp(const CodexSettingPage()));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    expect(find.byKey(const Key('codex-config-save-button')), findsNothing);

    final baseUrlField = find.byKey(const Key('codex-config-base-url-field'));
    final modelField = find.byKey(const Key('codex-config-model-field'));
    final apiKeyField = find.byKey(const Key('codex-config-api-key-field'));
    await tester.ensureVisible(baseUrlField);
    await tester.enterText(baseUrlField, 'https://new.example/v1');
    await tester.enterText(modelField, 'gpt-5.6');
    await tester.enterText(apiKeyField, 'new-key');

    expect(codexWriteCount, 0);
    await tester.pump(const Duration(milliseconds: 750));
    await tester.pump();

    expect(codexWriteCount, 1);
    expect(savedCodexConfig, <String, dynamic>{
      'baseUrl': 'https://new.example/v1',
      'model': 'gpt-5.6',
      'apiKey': 'new-key',
      'remoteEnabled': false,
      'remoteBridgeUrl': '',
      'remoteBridgeToken': '',
      'remoteCwd': '',
    });
    expect(find.text('已自动保存，将使用本地 Alpine Codex。'), findsOneWidget);
  });
}
