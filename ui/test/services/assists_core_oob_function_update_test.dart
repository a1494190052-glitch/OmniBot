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
    'updateOobFunction delegates runlog analysis and save to native',
    () async {
      final calls = <MethodCall>[];

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(assistCoreChannel, (call) async {
            calls.add(call);
            if (call.method == 'updateOobFunction') {
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
                'agent_model_invoked': true,
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

      final result = await AssistsMessageService.updateOobFunction(
        functionId: 'fn_weather',
        runId: 'run-1',
      );

      expect(calls.map((call) => call.method), <String>['updateOobFunction']);
      expect(result['success'], isTrue);
      expect(result['changed'], isTrue);
      expect(result['saved'], isTrue);
      expect(result['agent_model_invoked'], isTrue);
      expect(
        Map<String, dynamic>.from(
          result['updated_function'] as Map,
        )['description'],
        'new',
      );
    },
  );

  test(
    'updateOobFunction can explicitly request offline model analysis',
    () async {
      final calls = <MethodCall>[];

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(assistCoreChannel, (call) async {
            calls.add(call);
            if (call.method == 'updateOobFunction') {
              final args = Map<String, dynamic>.from(call.arguments as Map);
              expect(args['function_id'], 'fn_weather');
              expect(args['run_id'], 'run-1');
              expect(args['offline_job'], isTrue);
              expect(args['background_enhancement'], isTrue);
              expect(args['auto_analyze_with_model'], isTrue);
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

      final result = await AssistsMessageService.updateOobFunction(
        functionId: 'fn_weather',
        runId: 'run-1',
        extraArgs: const <String, dynamic>{
          'offline_job': true,
          'background_enhancement': true,
        },
        autoAnalyzeWithModel: true,
      );

      expect(calls.map((call) => call.method), <String>['updateOobFunction']);
      expect(result['success'], isTrue);
    },
  );
}
