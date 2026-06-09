import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/assists_core_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const assistCoreChannel = MethodChannel(
    'cn.com.omnimind.bot/AssistCoreEvent',
  );

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, null);
  });

  test(
    'updateOobFunction invokes main agent model when runlog needs analysis',
    () async {
      final calls = <MethodCall>[];

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(assistCoreChannel, (call) async {
            calls.add(call);
            if (call.method == 'updateOobFunction') {
              final args = Map<String, dynamic>.from(call.arguments as Map);
              if (!args.containsKey('analysis') && !args.containsKey('patch')) {
                expect(args['function_id'], 'fn_weather');
                expect(args['run_id'], 'run-1');
                return <String, dynamic>{
                  'success': true,
                  'function_id': 'fn_weather',
                  'run_id': 'run-1',
                  'needs_agent_analysis': true,
                  'changed': false,
                  'saved': false,
                  'agent_prompt': 'Analyze runlog and call update_function.',
                  'function': <String, dynamic>{
                    'function_id': 'fn_weather',
                    'description': 'old',
                  },
                  'updated_function': <String, dynamic>{
                    'function_id': 'fn_weather',
                    'description': 'old',
                  },
                };
              }

              final analysis = Map<String, dynamic>.from(
                args['analysis'] as Map,
              );
              final patch = Map<String, dynamic>.from(args['patch'] as Map);
              expect(args['source'], 'assists_core_service_agent_analysis');
              expect(
                analysis['summary'],
                'RunLog shows a clearer description.',
              );
              expect(patch['description'], 'new');
              return <String, dynamic>{
                'success': true,
                'function_id': 'fn_weather',
                'run_id': 'run-1',
                'changed': true,
                'saved': true,
                'function': <String, dynamic>{
                  'function_id': 'fn_weather',
                  'description': 'old',
                },
                'updated_function': <String, dynamic>{
                  'function_id': 'fn_weather',
                  'description': 'new',
                },
              };
            }

            if (call.method == 'postLLMChat') {
              final args = Map<String, dynamic>.from(call.arguments as Map);
              expect(args['text'], contains('Analyze runlog'));
              expect(args['model'], 'scene.dispatch.model');
              expect(args['responseJsonObject'], isTrue);
              return jsonEncode(<String, dynamic>{
                'analysis': <String, dynamic>{
                  'summary': 'RunLog shows a clearer description.',
                  'recommended_patch': <String, dynamic>{'description': 'new'},
                },
                'patch': <String, dynamic>{'description': 'new'},
              });
            }
            fail('Unexpected method call: ${call.method}');
          });

      final result = await AssistsMessageService.updateOobFunction(
        functionId: 'fn_weather',
        runId: 'run-1',
      );

      expect(calls.map((call) => call.method), <String>[
        'updateOobFunction',
        'postLLMChat',
        'updateOobFunction',
      ]);
      expect(result['success'], isTrue);
      expect(result['changed'], isTrue);
      expect(result['saved'], isTrue);
      expect(result['agent_model_invoked'], isTrue);
      expect(
        Map<String, dynamic>.from(
          result['agent_analysis_initial'] as Map,
        )['needs_agent_analysis'],
        isTrue,
      );
      expect(
        Map<String, dynamic>.from(
          result['updated_function'] as Map,
        )['description'],
        'new',
      );
    },
  );
}
