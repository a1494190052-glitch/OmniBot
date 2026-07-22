import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/assists_core_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('RunLog completion emits the exact child run id', () async {
    AssistsMessageService.initialize();
    final eventFuture = AssistsMessageService.runLogFinishedStream.first;

    final response = Completer<void>();
    await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .handlePlatformMessage(
          'cn.com.omnimind.bot/AssistCoreEvent',
          const StandardMethodCodec().encodeMethodCall(
            const MethodCall('onRunLogFinished', <String, dynamic>{
              'run_id': 'child-vlm-run',
              'source': 'vlm',
              'tool_name': 'vlm_task',
              'success': true,
            }),
          ),
          (_) => response.complete(),
        );
    await response.future;

    final event = await eventFuture;
    expect(event.runId, 'child-vlm-run');
    expect(event.source, 'vlm');
    expect(event.toolName, 'vlm_task');
    expect(event.success, isTrue);
  });
}
