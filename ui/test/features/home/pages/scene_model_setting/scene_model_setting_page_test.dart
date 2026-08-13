import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_switch/flutter_switch.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/scene_model_setting/scene_model_setting_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/model_provider_config_service.dart';
import 'package:ui/services/models_dev_catalog_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/services/voice_playback_coordinator.dart';
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

const _modelsDevCatalogJson = '''
{
  "custom": {
    "id": "custom",
    "name": "Custom",
    "models": {
      "scene-model": {
        "id": "scene-model",
        "name": "Scene Model",
        "limit": {"context": 128000}
      }
    }
  }
}
''';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
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

  Future<void> pumpSceneSettings(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1080, 2200);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(buildTestApp(const SceneModelSettingPage()));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));
  }

  late Map<String, dynamic> savedVoiceConfig;
  late bool providerConfigured;
  late bool providerDestinationConsentValid;
  late String providerBaseUrl;
  late int providerRevision;
  late String providerSourceType;
  late bool providerReadOnly;
  late bool providerReady;
  late bool includeOfficialProvider;
  late int providerFetchCount;
  late List<Map<String, dynamic>> providerFetchResponse;
  late List<Map<String, dynamic>> officialFetchResponse;
  late Completer<List<Map<String, dynamic>>>? providerFetchCompleter;
  late Object? providerFetchError;
  late Map<dynamic, dynamic>? lastProviderFetchArguments;

  setUp(() async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    await StorageService.init();
    await VoicePlaybackCoordinator.instance.debugResetForTest();
    ModelsDevCatalogService.setCatalogForTesting(
      ModelsDevCatalogService.parseCatalog(_modelsDevCatalogJson),
    );
    providerConfigured = true;
    providerDestinationConsentValid = true;
    providerBaseUrl = 'https://example.com/v1';
    providerRevision = 1;
    providerSourceType = 'custom';
    providerReadOnly = false;
    providerReady = true;
    includeOfficialProvider = false;
    providerFetchCount = 0;
    providerFetchResponse = <Map<String, dynamic>>[];
    officialFetchResponse = <Map<String, dynamic>>[];
    providerFetchCompleter = null;
    providerFetchError = null;
    lastProviderFetchArguments = null;
    savedVoiceConfig = <String, dynamic>{
      'autoPlay': false,
      'voiceId': 'default_zh',
      'stylePreset': '默认',
      'customStyle': '',
    };

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          switch (call.method) {
            case 'getSceneModelCatalog':
              return <Map<String, dynamic>>[
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
                    'baseUrl': providerBaseUrl,
                    'apiKey': 'secret',
                    'hasApiKey': true,
                    'configured': providerConfigured,
                    'destinationConsentValid': providerDestinationConsentValid,
                    'sourceType': providerSourceType,
                    'readOnly': providerReadOnly,
                    'ready': providerReady,
                    'revision': providerRevision,
                    'protocolType': 'openai_compatible',
                  },
                  if (includeOfficialProvider)
                    <String, dynamic>{
                      'id': 'omnibot-official-ai',
                      'name': 'OmniBot 官方 AI',
                      'baseUrl': 'https://official.example/ai',
                      'configured': true,
                      'destinationConsentValid': true,
                      'sourceType': 'omnibot_official',
                      'readOnly': true,
                      'ready': true,
                      'revision': 0,
                      'protocolType': 'openai_compatible',
                    },
                ],
                'editingProfileId': 'provider-1',
              };
            case 'fetchProviderModels':
              providerFetchCount += 1;
              lastProviderFetchArguments = call.arguments as Map?;
              final error = providerFetchError;
              if (error != null) {
                throw PlatformException(
                  code: 'FETCH_FAILED',
                  message: error.toString(),
                );
              }
              final pending = providerFetchCompleter;
              if (pending != null) return pending.future;
              final arguments = (call.arguments as Map?) ?? const {};
              return arguments['profileId'] == 'omnibot-official-ai'
                  ? officialFetchResponse
                  : providerFetchResponse;
            case 'getSceneVoiceConfig':
              return savedVoiceConfig;
            case 'saveSceneVoiceConfig':
              savedVoiceConfig = Map<String, dynamic>.from(
                (call.arguments as Map).cast<String, dynamic>(),
              );
              return savedVoiceConfig;
            default:
              return null;
          }
        });
  });

  tearDown(() async {
    final pending = providerFetchCompleter;
    if (pending != null && !pending.isCompleted) {
      pending.complete(<Map<String, dynamic>>[]);
    }
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
    ModelsDevCatalogService.resetForTesting();
    await VoicePlaybackCoordinator.instance.debugResetForTest();
  });

  testWidgets('scene page does not wait for metadata refresh', (tester) async {
    tester.view.physicalSize = const Size(1080, 2000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    providerConfigured = false;
    await ModelProviderConfigService.saveCachedFetchedModels(
      profileId: 'provider-1',
      apiBase: 'https://example.com/v1',
      profileRevision: providerRevision,
      models: const [
        ProviderModelOption(id: 'scene-model', displayName: 'scene-model'),
      ],
    );
    final loader = Completer<ModelsDevCatalog>();
    addTearDown(() {
      if (!loader.isCompleted) {
        loader.complete(const ModelsDevCatalog(providers: {}));
      }
    });
    var loadCount = 0;
    ModelsDevCatalogService.setCatalogLoaderForTesting(() {
      loadCount += 1;
      return loader.future;
    });

    await tester.pumpWidget(buildTestApp(const SceneModelSettingPage()));
    for (var index = 0; index < 6; index++) {
      await tester.pump(const Duration(milliseconds: 1));
    }

    expect(find.byType(ListView), findsWidgets);
    expect(find.byType(CircularProgressIndicator), findsNothing);
    expect(find.text('Voice'), findsOneWidget);
    expect(loadCount, 1);

    loader.complete(
      ModelsDevCatalogService.parseCatalog(_modelsDevCatalogJson),
    );
    for (var index = 0; index < 4; index++) {
      await tester.pump(const Duration(milliseconds: 1));
    }
    expect(tester.takeException(), isNull);
  });

  testWidgets('scene entry paints cache and has no manual refresh control', (
    tester,
  ) async {
    providerDestinationConsentValid = false;
    await ModelProviderConfigService.saveCachedFetchedModels(
      profileId: 'provider-1',
      apiBase: providerBaseUrl,
      profileRevision: providerRevision,
      models: const <ProviderModelOption>[
        ProviderModelOption(id: 'cached-model', displayName: 'Cached model'),
      ],
    );

    await pumpSceneSettings(tester);

    expect(providerFetchCount, 0);
    expect(
      find.byKey(const Key('scene-model-refresh-provider-models-button')),
      findsNothing,
    );
    await tester.tap(
      find.byKey(
        const Key('scene-model-selector-scene.compactor.context.chat'),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('cached-model'), findsOneWidget);
  });

  testWidgets('confirmed BYOK provider refreshes automatically', (
    tester,
  ) async {
    providerFetchResponse = <Map<String, dynamic>>[
      <String, dynamic>{'id': 'fresh-model', 'displayName': 'Fresh model'},
    ];
    await pumpSceneSettings(tester);

    expect(providerFetchCount, 1);
    expect(lastProviderFetchArguments?['apiBase'], providerBaseUrl);
    expect(lastProviderFetchArguments?['profileId'], 'provider-1');
    expect(lastProviderFetchArguments?['destinationConfirmed'], isNot(true));
    expect(
      find.byKey(const Key('data-destination-confirmation-dialog')),
      findsNothing,
    );
  });

  testWidgets('BYOK provider without destination consent is not refreshed', (
    tester,
  ) async {
    providerDestinationConsentValid = false;
    await pumpSceneSettings(tester);

    expect(providerFetchCount, 0);
    expect(
      find.byKey(const Key('data-destination-confirmation-dialog')),
      findsNothing,
    );
  });

  testWidgets('changed provider revision cannot apply an old fetch result', (
    tester,
  ) async {
    final pending = Completer<List<Map<String, dynamic>>>();
    providerFetchCompleter = pending;
    await pumpSceneSettings(tester);
    expect(providerFetchCount, 1);

    providerBaseUrl = 'https://replacement.example.com/v1';
    providerRevision = 2;
    pending.complete(<Map<String, dynamic>>[
      <String, dynamic>{'id': 'stale-model', 'displayName': 'Stale model'},
    ]);
    for (var attempt = 0; attempt < 10; attempt++) {
      await tester.pump();
    }

    await tester.tap(
      find.byKey(
        const Key('scene-model-selector-scene.compactor.context.chat'),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('stale-model'), findsNothing);
  });

  testWidgets('automatic refresh disposal ignores completion', (tester) async {
    final pending = Completer<List<Map<String, dynamic>>>();
    providerFetchCompleter = pending;
    await pumpSceneSettings(tester);
    expect(providerFetchCount, 1);

    await tester.pumpWidget(const MaterialApp(home: SizedBox.shrink()));
    pending.complete(<Map<String, dynamic>>[
      <String, dynamic>{'id': 'late-model', 'displayName': 'Late model'},
    ]);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 20));
    expect(providerFetchCount, 1);
    expect(tester.takeException(), isNull);
  });

  testWidgets('official catalog refreshes automatically without confirmation', (
    tester,
  ) async {
    providerBaseUrl = 'https://official.example/ai';
    providerSourceType = 'omnibot_official';
    providerReadOnly = true;
    providerFetchResponse = <Map<String, dynamic>>[
      <String, dynamic>{'id': 'official-model'},
    ];

    await pumpSceneSettings(tester);
    expect(providerFetchCount, 1);
    expect(lastProviderFetchArguments?['destinationConfirmed'], isNot(true));
    expect(
      find.byKey(const Key('data-destination-confirmation-dialog')),
      findsNothing,
    );
    await tester.tap(
      find.byKey(
        const Key('scene-model-selector-scene.compactor.context.chat'),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('official-model'), findsOneWidget);
  });

  testWidgets('scene selector shows BYOK and official channels together', (
    tester,
  ) async {
    includeOfficialProvider = true;
    providerFetchResponse = <Map<String, dynamic>>[
      <String, dynamic>{'id': 'byok-model'},
    ];
    officialFetchResponse = <Map<String, dynamic>>[
      <String, dynamic>{'id': 'official-model'},
    ];

    await pumpSceneSettings(tester);
    expect(providerFetchCount, 2);

    await tester.tap(
      find.byKey(
        const Key('scene-model-selector-scene.compactor.context.chat'),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('Provider One'), findsOneWidget);
    expect(find.text('OmniBot 官方 AI'), findsOneWidget);
    expect(find.text('byok-model'), findsOneWidget);

    await tester.tap(find.text('OmniBot 官方 AI'));
    await tester.pumpAndSettle();
    expect(find.text('official-model'), findsOneWidget);
  });

  testWidgets('background refresh errors do not leak endpoint details', (
    tester,
  ) async {
    providerFetchError =
        'socket failed at https://user:token@example.com/private?key=secret';
    await pumpSceneSettings(tester);
    for (var attempt = 0; attempt < 10; attempt++) {
      await tester.pump();
    }

    expect(find.textContaining('user:token'), findsNothing);
    expect(find.textContaining('/private'), findsNothing);
    expect(find.textContaining('key=secret'), findsNothing);
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
    expect(find.text('Compactor'), findsNothing);
    expect(find.text('Chat Compactor'), findsOneWidget);
    expect(find.text('未绑定'), findsOneWidget);
    expect(find.text('AI 响应完成后自动播放'), findsNothing);
    expect(find.byKey(const Key('voice-scene-expand-button')), findsOneWidget);

    await tester.tap(find.byKey(const Key('voice-scene-expand-button')));
    await tester.pumpAndSettle();

    expect(find.text('AI 响应完成后自动播放'), findsOneWidget);
    expect(find.byType(FlutterSwitch), findsOneWidget);
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
  });
}
