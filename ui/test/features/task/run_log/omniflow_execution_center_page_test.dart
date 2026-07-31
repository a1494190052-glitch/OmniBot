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
                  'started_at_ms': DateTime(
                    2026,
                    7,
                    31,
                    9,
                    18,
                  ).millisecondsSinceEpoch,
                  'finished_at_ms': DateTime(
                    2026,
                    7,
                    31,
                    9,
                    18,
                    2,
                    345,
                  ).millisecondsSinceEpoch,
                  'diagnostics': <String, Object?>{
                    'duration_ms': 2345,
                    'token_usage': <String, Object?>{
                      'prompt_tokens': 1000,
                      'completion_tokens': 234,
                      'total_tokens': 1234,
                      'call_count': 2,
                      'resolved_model': 'qwen-vl-max',
                    },
                  },
                },
              ],
            },
            'get_function' => <String, Object?>{
              'success': true,
              'function': <String, Object?>{
                'function_id': 'function.demo',
                'name': '演示指令',
                'description': '复用已成功执行的操作',
                'agent_visible': true,
                'input_schema': <String, Object?>{
                  'type': 'object',
                  'properties': <String, Object?>{
                    'query': <String, Object?>{
                      'type': 'string',
                      'description': '要搜索的文本',
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

  testWidgets('opens Function details from the Functions list', (tester) async {
    tester.view.physicalSize = const Size(360, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: OmniFlowExecutionCenterPage(),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.text('演示指令'));
    await tester.pumpAndSettle();

    expect(find.text('复用指令详情'), findsOneWidget);
    expect(find.text('参数'), findsOneWidget);
    expect(find.text('步骤'), findsOneWidget);
    expect(find.text('要搜索的文本'), findsOneWidget);
    expect(toolCalls.any((call) => call['name'] == 'get_function'), isTrue);

    await tester.tap(find.widgetWithText(FilledButton, '执行').last);
    await tester.pumpAndSettle();
    expect(find.text('填写执行参数'), findsOneWidget);
  });

  testWidgets('uses consistent Chinese labels across the execution center', (
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
    expect(find.text('轨迹'), findsNothing);
    expect(find.text('运行记录'), findsOneWidget);
    expect(find.text('RunLog'), findsNothing);
    await tester.tap(find.text('执行'));
    await tester.pumpAndSettle();
    expect(find.text('填写执行参数'), findsOneWidget);
    await tester.enterText(find.byType(TextFormField), 'replay acceptance');
    await tester.tap(find.text('开始执行'));
    await tester.pumpAndSettle();
    expect(
      toolCalls.any(
        (call) =>
            call['name'] == 'function.demo' &&
            (call['arguments'] as Map?)?['query'] == 'replay acceptance',
      ),
      isTrue,
    );

    await tester.tap(find.text('运行记录'));
    await tester.pumpAndSettle();
    expect(find.text('2026-07-31 09:18:00'), findsOneWidget);
    expect(find.text('2.35 s'), findsOneWidget);
    expect(find.text('模型用量 1.23k'), findsOneWidget);
    expect(find.text('qwen-vl-max'), findsOneWidget);
    expect(find.text('2 次 VLM 调用'), findsOneWidget);
    expect(find.text('成功'), findsOneWidget);
    expect(find.text('查看运行记录'), findsOneWidget);
    await tester.tap(find.text('注册为复用指令'));
    await tester.pumpAndSettle();
    expect(toolCalls.any((call) => call['name'] == 'convert_run_log'), isTrue);
  });

  testWidgets('uses consistent English labels across the execution center', (
    tester,
  ) async {
    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('en'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: OmniFlowExecutionCenterPage(),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Functions'), findsOneWidget);
    expect(find.text('Run Logs'), findsOneWidget);
    expect(find.text('Run'), findsOneWidget);
    expect(find.text('复用指令'), findsNothing);

    await tester.tap(find.text('Run Logs'));
    await tester.pumpAndSettle();
    expect(find.text('Succeeded'), findsOneWidget);
    expect(find.text('1.23k tokens'), findsOneWidget);
    expect(find.text('2 VLM calls'), findsOneWidget);
    expect(find.text('View Run Log'), findsOneWidget);
  });
}
