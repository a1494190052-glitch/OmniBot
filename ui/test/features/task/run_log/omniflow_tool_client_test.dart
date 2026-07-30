import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/task/run_log/omniflow_tool_client.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
  final calls = <MethodCall>[];

  setUp(() {
    calls.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          final arguments = Map<Object?, Object?>.from(call.arguments as Map);
          return switch (arguments['name']) {
            'list_functions' => <String, Object?>{
              'success': true,
              'functions': <Object?>[],
            },
            'list_run_logs' => <String, Object?>{
              'success': true,
              'runs': <Object?>[],
            },
            _ => <String, Object?>{'success': true},
          };
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test(
    'uses the shared tools call seam for Function and RunLog operations',
    () async {
      await OmniFlowToolClient.listFunctions();
      await OmniFlowToolClient.listRunLogs();
      await OmniFlowToolClient.convertRunLog('run-1');

      expect(calls.map((call) => call.method), everyElement('tools/call'));
      expect(calls.map((call) => (call.arguments as Map)['name']), <String>[
        'list_functions',
        'list_run_logs',
        'convert_run_log',
      ]);
      expect(
        ((calls.last.arguments as Map)['arguments'] as Map)['run_id'],
        'run-1',
      );
    },
  );
}
