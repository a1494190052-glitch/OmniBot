import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/scene_model_config_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('scene binding preserves native tool-call capability', () async {
    MethodCall? recordedCall;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          recordedCall = call;
          return <Map<String, dynamic>>[
            Map<String, dynamic>.from(
              (call.arguments as Map).cast<String, dynamic>(),
            ),
          ];
        });

    final bindings = await SceneModelConfigService.saveSceneModelBinding(
      sceneId: 'scene.vlm.operation.primary',
      providerProfileId: 'provider-1',
      modelId: 'gui-model',
      toolCall: false,
    );

    expect(recordedCall?.method, 'saveSceneModelBinding');
    expect((recordedCall?.arguments as Map)['toolCall'], false);
    expect(bindings.single.toolCall, false);
  });
}
