import 'dart:io';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/task/run_log/run_log_function_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  tearDown(() async {
    messenger.setMockMethodCallHandler(channel, null);
  });

  test('enhanceFunction dispatches explicit semantic enhancement', () async {
    MethodCall? capturedCall;
    messenger.setMockMethodCallHandler(channel, (call) async {
      capturedCall = call;
      return <String, dynamic>{
        'success': true,
        'function_id': 'fn_existing',
        'updated_function': <String, dynamic>{
          'function_id': 'fn_existing',
          'name': 'Enhanced Function',
        },
      };
    });

    final result = await RunLogFunctionService.enhanceFunction(
      functionId: 'fn_existing',
      runId: 'run_source',
    );

    expect(result['success'], isTrue);
    expect(capturedCall?.method, 'updateFunction');
    expect(capturedCall?.arguments, <String, dynamic>{
      'function_id': 'fn_existing',
      'mode': 'enhance',
      'run_id': 'run_source',
    });
  });

  test('convert can explicitly skip default offline enhancement', () async {
    MethodCall? capturedCall;
    messenger.setMockMethodCallHandler(channel, (call) async {
      capturedCall = call;
      return <String, dynamic>{
        'success': true,
        'function_id': 'fn_base',
        'enhancement_status': 'none',
      };
    });

    final result = await RunLogFunctionService.convertInternalRunLogToFunction(
      runId: 'run_source',
      enhance: false,
    );

    expect(result['success'], isTrue);
    expect(capturedCall?.method, 'convertInternalRunLogToFunction');
    expect(capturedCall?.arguments, <String, dynamic>{
      'run_id': 'run_source',
      'register': true,
      'agent_visible': false,
      'enhance': false,
    });
  });

  test('timeline exposes the existing Function enhancement trigger', () {
    var root = Directory.current.absolute;
    while (!File('${root.path}/pubspec.yaml').existsSync()) {
      root = root.parent;
    }
    final source = File(
      '${root.path}/lib/features/task/pages/execution_history/'
      'run_log_timeline_page.dart',
    ).readAsStringSync();
    final panel = source
        .split('Widget? _buildFunctionStatusStrip(BuildContext context)')[1]
        .split('Future<void> _openSavedFunctionSheet()')[0];

    expect(panel, isNot(contains('canEnhance: false')));
    expect(panel, isNot(contains('onEnhance: null')));
    expect(panel, contains('_enhanceSavedRunLogFunction'));
  });
}
