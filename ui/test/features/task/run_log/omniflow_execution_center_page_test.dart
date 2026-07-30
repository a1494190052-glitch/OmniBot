import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/task/pages/execution_history/omniflow_execution_center_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const pluginChannel = MethodChannel('cn.com.omnimind.bot/PluginPlatform');
  const assistChannel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
  final toolCalls = <Map<Object?, Object?>>[];

  setUp(() {
    toolCalls.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(pluginChannel, (call) async {
          if (call.method != 'list') return null;
          return <Object?>[
            <String, Object?>{
              'id': 'com.omnimind.omni-vlm-lite',
              'name': 'Omni VLM Lite',
              'version': '2.0.0',
              'interfaceVersion': 1,
              'description': 'GUI runtime',
              'publisher': 'OmniMind',
              'kind': 'runtime_bundle',
              'downloadSizeBytes': 0,
              'capabilities': <String>[],
              'settingsSchema': <String, Object?>{},
              'installed': true,
              'enabled': true,
              'compatible': true,
            },
          ];
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistChannel, (call) async {
          if (call.method != 'tools/call') return null;
          final arguments = Map<Object?, Object?>.from(call.arguments as Map);
          toolCalls.add(arguments);
          return switch (arguments['name']) {
            'list_functions' => <String, Object?>{
              'success': true,
              'count': 1,
              'functions': <Object?>[
                <String, Object?>{
                  'function_id': 'function.demo',
                  'name': '演示指令',
                  'description': '复用已成功执行的轨迹',
                  'input_schema': <String, Object?>{
                    'type': 'object',
                    'properties': <String, Object?>{
                      'query': <String, Object?>{
                        'type': 'string',
                        'description': 'Text to search for',
                      },
                    },
                    'required': <String>['query'],
                  },
                  'steps': <Object?>[
                    <String, Object?>{
                      'action': <String, Object?>{
                        'tool': 'click',
                        'args': <String, Object?>{'x': 500, 'y': 500},
                      },
                    },
                  ],
                },
              ],
            },
            'list_run_logs' => <String, Object?>{
              'success': true,
              'count': 1,
              'runs': <Object?>[
                <String, Object?>{
                  'run_id': 'run-1',
                  'goal': '完成演示',
                  'status': 'success',
                  'step_count': 1,
                },
              ],
            },
            'function.demo' => <String, Object?>{'success': true},
            'convert_run_log' => <String, Object?>{
              'success': true,
              'function_id': 'function.demo',
            },
            _ => <String, Object?>{'success': true},
          };
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(pluginChannel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistChannel, null);
  });

  testWidgets('exposes reuse, trajectory and RunLog with canonical actions', (
    tester,
  ) async {
    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: OmniFlowExecutionCenterPage(),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('复用指令'), findsOneWidget);
    expect(find.text('轨迹'), findsOneWidget);
    expect(find.text('RunLog'), findsOneWidget);
    await tester.tap(find.text('轨迹'));
    await tester.pumpAndSettle();
    expect(find.text('click'), findsOneWidget);
    await tester.tap(find.text('复用指令'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('回放'));
    await tester.pumpAndSettle();
    expect(find.text('填写参数'), findsOneWidget);
    await tester.enterText(find.byType(TextFormField), 'replay acceptance');
    await tester.tap(find.text('开始回放'));
    await tester.pumpAndSettle();
    expect(
      toolCalls.any(
        (call) =>
            call['name'] == 'function.demo' &&
            (call['arguments'] as Map?)?['query'] == 'replay acceptance',
      ),
      isTrue,
    );

    await tester.tap(find.text('RunLog'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('注册为复用指令'));
    await tester.pumpAndSettle();
    expect(toolCalls.any((call) => call['name'] == 'convert_run_log'), isTrue);
  });
}
