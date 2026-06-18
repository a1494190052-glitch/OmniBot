import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/material.dart';
import 'package:ui/features/home/pages/command_overlay/services/manual_recording_flow_controller.dart';

void main() {
  test('manual recording command accepts visible shortcut labels', () {
    expect(ManualRecordingFlowController.isCommand('录制轨迹'), isTrue);
    expect(ManualRecordingFlowController.isCommand('开始录制轨迹'), isTrue);
    expect(ManualRecordingFlowController.isCommand('轨迹录制'), isTrue);
    expect(
      ManualRecordingFlowController.isCommand('Record trajectory'),
      isTrue,
    );
  });

  test('manual recording command rejects ordinary chat text', () {
    expect(ManualRecordingFlowController.isCommand('帮我打开设置'), isFalse);
    expect(ManualRecordingFlowController.isCommand(''), isFalse);
  });

  testWidgets('manual recording preparation failure still inserts result and cleans up', (
    tester,
  ) async {
    final focusNode = FocusNode();
    final inserted = <Map<String, dynamic>>[];
    var restoreCalls = 0;
    var finallyCalls = 0;
    var nativeCalls = 0;
    BuildContext? context;
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (innerContext) {
            context = innerContext;
            return const SizedBox.shrink();
          },
        ),
      ),
    );

    final started = await ManualRecordingFlowController.start(
      context: context!,
      inputFocusNode: focusNode,
      userMessageText: '录制轨迹',
      recordDebugScreenshots: false,
      isMounted: () => true,
      ensureAuthorized: (_) async => true,
      addUserMessage: (_) => const ManualRecordingFlowMessageIds(
        userMessageId: 'user-1',
        aiMessageId: 'ai-1',
      ),
      beforeNativeRecording: () {
        throw StateError('prepare failed');
      },
      afterNativeRecording: () {
        restoreCalls += 1;
      },
      startNativeRecording: ({required bool enableDebugScreenshots}) async {
        nativeCalls += 1;
        return <String, dynamic>{'success': true};
      },
      insertResultMessage: (messageId, result) {
        inserted.add(<String, dynamic>{
          'message_id': messageId,
          ...result,
        });
      },
      onFinally: () {
        finallyCalls += 1;
      },
    );

    expect(started, isTrue);
    expect(nativeCalls, 0);
    expect(restoreCalls, 1);
    expect(finallyCalls, 1);
    expect(inserted, hasLength(1));
    expect(inserted.single['message_id'], 'ai-1');
    expect(inserted.single['success'], isFalse);
    expect(inserted.single['error_message'].toString(), contains('prepare failed'));
    focusNode.dispose();
  });

  testWidgets('manual recording success restores once and opens runlog', (
    tester,
  ) async {
    final focusNode = FocusNode();
    final inserted = <Map<String, dynamic>>[];
    final openedRunLogs = <String>[];
    var restoreCalls = 0;
    var finallyCalls = 0;
    BuildContext? context;
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (innerContext) {
            context = innerContext;
            return const SizedBox.shrink();
          },
        ),
      ),
    );

    final started = await ManualRecordingFlowController.start(
      context: context!,
      inputFocusNode: focusNode,
      userMessageText: '录制轨迹',
      recordDebugScreenshots: true,
      isMounted: () => true,
      ensureAuthorized: (_) async => true,
      addUserMessage: (_) => const ManualRecordingFlowMessageIds(
        userMessageId: 'user-2',
        aiMessageId: 'ai-2',
      ),
      beforeNativeRecording: () {},
      afterNativeRecording: () {
        restoreCalls += 1;
      },
      startNativeRecording: ({required bool enableDebugScreenshots}) async {
        expect(enableDebugScreenshots, isTrue);
        return <String, dynamic>{
          'success': true,
          'recording_success': true,
          'conversion_success': true,
          'run_id': 'human-run-1',
          'function_id': 'human-function-1',
        };
      },
      insertResultMessage: (messageId, result) {
        inserted.add(<String, dynamic>{
          'message_id': messageId,
          ...result,
        });
      },
      openRunLogTimeline: openedRunLogs.add,
      onFinally: () {
        finallyCalls += 1;
      },
    );

    expect(started, isTrue);
    expect(restoreCalls, 1);
    expect(finallyCalls, 1);
    expect(inserted, hasLength(1));
    expect(inserted.single['message_id'], 'ai-2');
    expect(inserted.single['success'], isTrue);
    expect(openedRunLogs, ['human-run-1']);
    focusNode.dispose();
  });
}
