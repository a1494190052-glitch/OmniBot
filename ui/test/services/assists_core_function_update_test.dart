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

  test('updateFunction delegates deterministic save to native', () async {
    final calls = <MethodCall>[];

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, (call) async {
          calls.add(call);
          if (call.method == 'updateFunction') {
            final args = Map<String, dynamic>.from(call.arguments as Map);
            expect(args['function_id'], 'fn_weather');
            expect(args['run_id'], 'run-1');
            expect(args['auto_analyze_with_model'], isFalse);
            expect(args.containsKey('analysis'), isFalse);
            expect(args.containsKey('patch'), isFalse);
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

          fail('Unexpected method call: ${call.method}');
        });

    final result = await AssistsMessageService.updateFunction(
      functionId: 'fn_weather',
      runId: 'run-1',
    );

    expect(calls.map((call) => call.method), <String>['updateFunction']);
    expect(result['success'], isTrue);
    expect(result['changed'], isTrue);
    expect(result['saved'], isTrue);
    expect(
      Map<String, dynamic>.from(
        result['updated_function'] as Map,
      )['description'],
      'new',
    );
  });

  test(
    'updateFunction can save a complete function_spec without separate id',
    () async {
      final calls = <MethodCall>[];
      final functionSpec = <String, dynamic>{
        'schema_version': 'oob.reusable_function.v1',
        'function_id': 'fn_weather',
        'name': '查天气',
        'execution': <String, dynamic>{
          'steps': <Map<String, dynamic>>[
            <String, dynamic>{
              'id': 'step_1',
              'tool': 'input_text',
              'args': <String, dynamic>{'text': '上海天气'},
            },
          ],
        },
      };

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(assistCoreChannel, (call) async {
            calls.add(call);
            if (call.method == 'updateFunction') {
              final args = Map<String, dynamic>.from(call.arguments as Map);
              expect(args.containsKey('function_id'), isFalse);
              expect(args['function_spec'], functionSpec);
              expect(args['run_id'], 'run-1');
              expect(args['offline_job'], isTrue);
              expect(args['background_enhancement'], isTrue);
              expect(args['auto_analyze_with_model'], isFalse);
              return <String, dynamic>{
                'success': true,
                'function_id': 'fn_weather',
                'run_id': 'run-1',
                'changed': false,
                'saved': false,
              };
            }

            fail('Unexpected method call: ${call.method}');
          });

      final result = await AssistsMessageService.updateFunction(
        functionSpec: functionSpec,
        runId: 'run-1',
        extraArgs: const <String, dynamic>{
          'offline_job': true,
          'background_enhancement': true,
        },
      );

      expect(calls.map((call) => call.method), <String>['updateFunction']);
      expect(result['success'], isTrue);
    },
  );
}
